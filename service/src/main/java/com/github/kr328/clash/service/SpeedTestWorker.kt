package com.github.kr328.clash.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.id.UndefinedIds
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.SpeedTestOptions
import com.github.kr328.clash.core.patchSelectorPath
import com.github.kr328.clash.service.model.SpeedTestSelection
import com.github.kr328.clash.service.model.SpeedTestWatchState
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Re-measures every group whose toolbar toggle is on, repairs the
 * connection when the default node stopped answering, and watches the node
 * each group is actually connected through so a stalled connection is
 * noticed within minutes rather than on the next screening round.
 *
 * The core only lives in :background, which is why this is a foreground
 * service rather than anything the UI process could run: the measurement goes
 * through [Clash.speedTestGroup], the same live adapters the connection uses.
 * Two alarms land here, the screening round and the tighter watch, and both
 * are re-armed once the pass finishes, including when it fails.
 */
class SpeedTestWorker : BaseService() {
    private val jobs = mutableListOf<Job>()

    override fun onCreate() {
        super.onCreate()

        createChannels()
        foreground()

        launch {
            delay(TimeUnit.SECONDS.toMillis(10))

            while (true) {
                jobs.removeFirstOrNull()?.join() ?: break
            }

            stopSelf()
        }
    }

    override fun onDestroy() {
        stopForeground(true)

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == Intents.ACTION_SPEEDTEST_REQUEST) {
            jobs.add(launch { run() })
        } else if (intent?.action == Intents.ACTION_SPEEDTEST_WATCH) {
            jobs.add(launch { watch() })
        }

        return START_NOT_STICKY
    }

    private suspend fun run() {
        try {
            val store = ServiceStore(applicationContext)
            val groups = store.speedTestGroups.sorted()

            groups.forEachIndexed { index, group ->
                updateProgress(
                    getString(R.string.speed_test_group_progress, group, index + 1, groups.size)
                )

                try {
                    if (screen(store, group)) {
                        // Only a failover is worth a notification: a pass keeps
                        // quiet so the hourly rhythm never becomes noise.
                        notifySwitched(store, group)
                    }
                } catch (e: Throwable) {
                    // Not merely Exception: a JNI bridge that cannot resolve a
                    // symbol throws a LinkageError, and letting that escape
                    // would end the process which hosts the core, so every
                    // group would lose its connection at once instead of
                    // leaving one group broken.
                    Log.w("speedtest: $group failed", e)
                }
            }
        } finally {
            SpeedTestReceiver.scheduleNext(this)
        }
    }

    /**
     * Re-checks the node each enabled group is connected through and repairs
     * the connection when that node stopped carrying traffic.
     *
     * Deliberately quiet: a watch pass that finds the node healthy leaves no
     * trace, so the tight rhythm never becomes noise. Only a switch is worth
     * a notification, and the alarm is re-armed even when a group fails, so
     * one broken group cannot silence the watch of the rest.
     */
    private suspend fun watch() {
        try {
            val store = ServiceStore(applicationContext)

            store.speedTestGroups.sorted().forEach { group ->
                try {
                    if (watchGroup(store, group)) {
                        notifySwitched(store, group)
                    }
                } catch (e: Throwable) {
                    // Same reason as the screening round: the watch runs every
                    // couple of minutes, so a failure it cannot survive would
                    // take the core down within one pass of being enabled.
                    Log.w("speedtest watch: $group failed", e)
                }
            }
        } finally {
            SpeedTestReceiver.scheduleWatch(this)
        }
    }

    /**
     * Watches the node [group] is connected through and repairs it when the
     * node stopped carrying traffic.
     *
     * The cheap signal comes first: the core already counts the bytes every
     * open connection carries and names the node behind it, so a node that
     * has demonstrably carried the connection at or above the floor since the
     * last watch needs no probe at all, and a pass that costs no traffic is
     * what lets the watch run every couple of minutes. Anything else asks
     * the gate about that one node, and only a node the gate refuses moves
     * the connection.
     *
     * Returns true when the selector was moved.
     */
    private suspend fun watchGroup(store: ServiceStore, group: String): Boolean {
        // A group whose members are other groups routes through the sub group it
        // selected, so the node in use has to be reached by following that
        // selection down: the group's own now names the sub group, which is not a
        // node the gate could measure.
        val info = Clash.queryGroup(group, ProxySort.Default)
        val current = info.inUse.ifEmpty { info.now }
        if (current.isEmpty())
            return false

        val now = System.currentTimeMillis()
        val previous = store.speedTestWatchState(group)
        val traffic = Clash.queryNodeTraffic(group)

        if (!traffic.ok) {
            // The monitor could not even name the group's nodes: nothing can
            // be concluded, so the node is left alone and the state untouched
            // until the next pass. A toggle left behind for a removed group
            // lands here every pass, so this stays quiet.
            Log.d("speedtest watch: $group: monitor refused: ${traffic.error}")

            return false
        }

        if (previous != null && previous.node == current && previous.atMs > 0) {
            val node = traffic.nodes.firstOrNull { it.name == current }
            if (node != null && node.bytes > previous.bytesAt) {
                val elapsedMs = now - previous.atMs
                // Bytes at or above the floor over the window between the two
                // reads: the connection moved on its own, so the node is
                // proven by the traffic it carried rather than by a request
                // this pass would have to pay for.
                if (elapsedMs > 0 &&
                    node.bytes - previous.bytesAt >= (WATCH_FLOOR_MBPS * elapsedMs).toLong()) {
                    store.setSpeedTestWatchState(
                        group,
                        SpeedTestWatchState(
                            node = current,
                            bytesAt = node.bytes,
                            atMs = now,
                            switchedFrom = previous.switchedFrom,
                            switchedAt = previous.switchedAt,
                        )
                    )

                    return false
                }
            }
        }

        // Nothing flowed, or only slow traffic: ask the gate about this one
        // node. One round, reachability probes skipped, a bounded download
        // window: the watch costs what one connection would spend.
        val probe = Clash.speedTestGroup(
            group,
            SpeedTestOptions(
                rounds = 1,
                only = current,
                maxBytes = WATCH_PROBE_BYTES,
                skipReachability = true,
            )
        )

        if (!probe.ok) {
            // The probe itself could not run (the group is gone, the node is
            // not a member of it): never switch on an unknown.
            Log.w("speedtest watch: $group: ${probe.error}")

            return false
        }

        if (probe.results.any { it.name == current && it.passed }) {
            // The node answers and carries: the silence was only an idle
            // connection. Record what the monitor saw so the next pass can
            // skip the probe when the traffic proves the node again.
            val node = traffic.nodes.firstOrNull { it.name == current }

            store.setSpeedTestWatchState(
                group,
                SpeedTestWatchState(
                    node = current,
                    bytesAt = node?.bytes ?: 0,
                    atMs = now,
                    switchedFrom = previous?.switchedFrom ?: "",
                    switchedAt = previous?.switchedAt ?: 0,
                )
            )

            return false
        }

        // The node in use stopped answering, stopped carrying, or answered
        // slower than the limit: re-rank the group and move the selector.
        //
        // The node the last switch left behind is off the table while the
        // cooldown lasts, otherwise two nodes that both drag would trade
        // places every pass and the connection would ping-pong between them.
        val avoid = if (
            previous != null && previous.switchedFrom.isNotEmpty() &&
            now - previous.switchedAt < WATCH_COOLDOWN_MS
        ) previous.switchedFrom else ""

        if (!screen(store, group, avoid))
            return false

        val switched = store.speedTestSelection(group) ?: return false

        store.setSpeedTestWatchState(
            group,
            SpeedTestWatchState(
                node = switched.default,
                // The snapshot was taken before the switch, so the count of
                // the node that just took over is where its window starts;
                // traffic it carries from here on is what the next pass sees.
                bytesAt = traffic.nodes.firstOrNull { it.name == switched.default }?.bytes ?: 0,
                atMs = now,
                switchedFrom = current,
                switchedAt = now,
            )
        )

        return true
    }

    /**
     * Measures one group, refreshes its standing ranking, and moves the
     * selector only when the current default has stopped clearing the gate.
     *
     * Returns true when the selector was moved, so the caller can tell the
     * user about the failover. [avoid] names a node that must not be picked
     * even though it passed, which is how the watch keeps a node it just
     * switched away from from being chosen again the next moment.
     */
    private suspend fun screen(store: ServiceStore, group: String, avoid: String = ""): Boolean {
        // Two rounds: a single lucky transfer must not decide which node the
        // whole app connects through when nobody is watching. The worst round
        // is the score, so the ranking needs both rounds to hold up.
        val envelope = Clash.speedTestGroup(group, SpeedTestOptions(rounds = 2))

        if (!envelope.ok) {
            // A toggle left behind for a removed group lands here every pass,
            // which is why this stays quiet: it is dropped, not announced.
            Log.d("speedtest: $group: ${envelope.error}")

            return false
        }

        // A group whose members are other groups reports the sub group it routes
        // through as its selection, so the node in use has to be reached by
        // following that selection down: it is the only name the gate could
        // measure and the only one the innermost selector could be moved to.
        val info = Clash.queryGroup(group, ProxySort.Default)
        val current = info.inUse.ifEmpty { info.now }

        val passed = envelope.results.filter { it.passed }
        val passedNames = passed.map { it.name }.toSet()
        val best = envelope.best

        if (best == null || best.isEmpty() || best !in passedNames)
            return false

        // Each measured node knows the group that actually holds it, which for a
        // node behind a sub group is not the group the run was asked about. The
        // selection is written there, because Set names a member and the outer
        // group cannot name a node two levels down.
        val owners = passed.associate { it.name to (it.owner ?: "") }
        // Every group on the way down to the node, so a node behind a sub group
        // can be reached hop by hop rather than by patching a group that merely
        // routes to it. A node measured directly under this group has a one
        // element path, which is the group itself.
        val paths = passed.associate { it.name to (it.path ?: listOf(group)) }

        // Read before writing: the previous standby is the first candidate to
        // take over, and overwriting first would lose it.
        val previous = store.speedTestSelection(group)
        val top = passed.firstOrNull { it.name == best }

        store.setSpeedTestSelection(
            group,
            SpeedTestSelection(
                default = best,
                backup = envelope.backup.orEmpty(),
                mbps = top?.mbps ?: 0.0,
                updatedAt = System.currentTimeMillis(),
            )
        )

        // The default still passes: keep it. The user's choice (or a previous
        // round's switch) stands as long as it works, and only the ranking
        // underneath it is refreshed.
        if (current in passedNames)
            return false

        val target = listOfNotNull(previous?.backup, best, envelope.backup)
            .firstOrNull { it.isNotEmpty() && it in passedNames && it != current && it != avoid }

        if (target == null) {
            Log.w("speedtest: $group: default '$current' failed and nothing stood behind it")

            return false
        }

        val owner = owners[target].orEmpty()

        if (owner.isEmpty()) {
            // No group along the way can hold a selection: a loadbalance or an
            // available group picks on its own, so there is no selector to move
            // and the node is left alone rather than patched into a group it is
            // not a member of.
            Log.d("speedtest: $group: '$target' has no group that can be moved")

            return false
        }

        // The route has to be walked hop by hop: the outer group cannot name a
        // node two levels down, so pointing only the group that holds the node
        // would move a branch the connection never travels.
        //
        // The worker runs in the service process, which is where the core lives,
        // so it hands in Clash itself rather than a remote that has to reach it.
        val moved = patchSelectorPath(
            group,
            target,
            paths[target] ?: listOf(group),
            { name -> Clash.queryGroup(name, ProxySort.Default) },
            { from, name -> Clash.patchSelector(from, name) },
        ) ?: return false

        if (moved.isEmpty()) {
            // The route already reaches it, so nothing was switched: claiming
            // one would report a node change that never happened.
            return false
        }

        Log.d("speedtest: $group: '$current' failed, switched to '$target' via ${moved.joinToString(" -> ")}")

        return true
    }

    private fun notifySwitched(store: ServiceStore, group: String) {
        val selection = store.speedTestSelection(group) ?: return

        val notification = resultBuilder()
            .setContentTitle(getString(R.string.speed_test_worker))
            .setContentText(
                getString(R.string.speed_test_switched, group, selection.default)
            )
            .build()

        notifyOnce(notification)
    }

    private fun createChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    SERVICE_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.speed_test_worker)).build(),
                NotificationChannelCompat.Builder(
                    RESULT_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(R.string.speed_test_worker)).build()
            )
        )
    }

    private fun foregroundNotification(): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.speed_test_worker))
            .setContentText(getString(R.string.speed_test_running))
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun foreground() {
        startForeground(R.id.nf_speedtest_worker, foregroundNotification())
    }

    private fun updateProgress(text: String) {
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.speed_test_worker))
            .setContentText(text)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(R.id.nf_speedtest_worker, notification)
    }

    private fun resultBuilder(): NotificationCompat.Builder {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent().setComponent(Components.MAIN_ACTIVITY),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        return NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setGroup(RESULT_CHANNEL)
    }

    private fun notifyOnce(notification: Notification) {
        val id = UndefinedIds.next()

        launch(NonCancellable) {
            NotificationManagerCompat.from(applicationContext)
                .notify(id, notification)
        }
    }

    companion object {
        private const val SERVICE_CHANNEL = "speedtest_service_channel"
        private const val RESULT_CHANNEL = "speedtest_result_channel"

        // The rate the traffic snapshot has to show before a node counts as
        // proven by its own traffic. Same floor the throughput gate uses, so
        // the watch and the screening cannot disagree about what usable is.
        private const val WATCH_FLOOR_MBPS = 0.5
        // The download window the watch's probe may spend. Deliberately a
        // quarter of the gate's: the watch asks whether the node still
        // carries, not which node of the group carries best.
        private const val WATCH_PROBE_BYTES = 256L shl 10
        // How long a node the watch switched away from stays off the table,
        // which is what keeps two nodes that both drag from trading places
        // every pass.
        private const val WATCH_COOLDOWN_MS = 10L * 60L * 1000L
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }
}
