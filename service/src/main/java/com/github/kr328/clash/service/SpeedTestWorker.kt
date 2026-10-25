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
import com.github.kr328.clash.core.model.SpeedTestEnvelope
import com.github.kr328.clash.core.model.SpeedTestProgress
import com.github.kr328.clash.service.model.SpeedTestResults
import com.github.kr328.clash.service.model.SpeedTestSelection
import com.github.kr328.clash.service.model.SpeedTestWatchState
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.SpeedTestProfile
import com.github.kr328.clash.service.util.logSpeedTestStages
import com.github.kr328.clash.service.util.sendSpeedTestGroupFinished
import com.github.kr328.clash.service.util.sendSpeedTestResultsChanged
import com.github.kr328.clash.service.util.sendSpeedTestRunningChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Serialises the rounds this process runs.
     *
     * Two measurements at once would drive the same live adapters - a periodic
     * pass over the pool and a toolbar run over one of the groups it is
     * measuring - and the second one would both distort the first one's
     * figures and spend the device's data twice. The lock makes the toolbar's
     * request wait its turn instead of losing it, which is the difference
     * between a button that is slow today and a button that silently does
     * nothing.
     */
    private val runLock = Mutex()

    /**
     * Whether a screening pass is in flight in this process.
     *
     * The alarm is armed for a fixed period, and the pass it starts can easily
     * take longer than that: a pool of 174 nodes with most of them dead sits
     * out a full timeout each, which measured 36 minutes against a five
     * minute period. Without this the next alarm would land while the first
     * pass is still going and start a second one beside it, and the phone
     * would measure two configurations at once for as long as that continues.
     * The interval formula keeps that from being normal; this keeps it from
     * happening at all when the estimate is still catching up.
     */
    @Volatile
    private var screening = false

    /**
     * How many nodes the pass in flight has measured so far.
     *
     * Only the count matters here, not a name or a figure: the structural
     * bound on the next interval is a function of how much work there is, and
     * the pass itself is the only place that knows how much work there was.
     * Reset at the top of [run] and read in its `finally`, so the number
     * always describes one pass rather than the process's whole lifetime.
     */
    @Volatile
    private var nodesMeasured = 0L

    override fun onCreate() {
        super.onCreate()

        createChannels()
        foreground()

        // A mark left behind by a process that was killed mid-run is not a run:
        // this service is the only thing that can be measuring, and it has not
        // started one yet. Clearing it here is what keeps a stale spinner from
        // outliving the round it described.
        ServiceStore(applicationContext).clearSpeedTestRunning()

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
            if (screening) {
                Log.d("speedtest: a round is still running, this alarm is dropped")
            } else {
                screening = true

                jobs.add(launch { run() })
            }
        } else if (intent?.action == Intents.ACTION_SPEEDTEST_GROUP) {
            val group = intent.getStringExtra(Intents.EXTRA_GROUP).orEmpty()

            if (group.isEmpty()) {
                Log.d("speedtest: a toolbar run arrived without a group")

                return START_NOT_STICKY
            }

            // Queued rather than dropped when a round is in flight: the reader
            // pressed the button, so the answer has to arrive even if it has to
            // wait for the group already being measured. The lock is what makes
            // the wait safe; this log is what explains a late start.
            if (runLock.isLocked) {
                Log.d("speedtest: $group: queued behind the round in flight")
            }

            jobs.add(launch { runFromToolbar(group) })
        } else if (intent?.action == Intents.ACTION_SPEEDTEST_WATCH) {
            jobs.add(launch { watch() })
        } else if (intent?.action == Intents.ACTION_SPEEDTEST_VIDEO) {
            val group = intent.getStringExtra(Intents.EXTRA_GROUP).orEmpty()

            if (group.isEmpty()) {
                Log.d("speedtest: a fast pass arrived without a group")

                return START_NOT_STICKY
            }

            // The fast pass is a handful of names, so a queue behind a round
            // in flight costs little; dropping it would leave the ranking
            // stale until the next window anyway.
            jobs.add(launch { runFromVideoAlarm(group) })
        }

        return START_NOT_STICKY
    }

    private suspend fun run() {
        val store = ServiceStore(applicationContext)

        screening = true

        val startedAt = System.currentTimeMillis()
        nodesMeasured = 0L

        try {
            val groups = liveGroups(store)

            groups.forEachIndexed { index, group ->
                // Taken per group rather than for the whole pass: a toolbar run
                // that arrives mid-pass then waits for the group being measured
                // instead of for however long the rest of the pass still needs -
                // twenty minutes of spinner for a button the reader pressed is
                // not an answer.
                runLock.withLock {
                    updateProgress(
                        getString(R.string.speed_test_group_progress, group, index + 1, groups.size)
                    )

                    // The mark is what puts the spinner on this group's toolbar
                    // button in the UI process: a periodic pass is exactly the
                    // run the screen cannot see starting, so without it the
                    // button sits idle while the group is being measured.
                    markRunning(store, group)

                    val beat = heartbeat(store, group)

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
                    } finally {
                        beat.cancel()

                        clearRunning(store, group)
                    }
                }
            }
        } finally {
            // What the pass cost is what the next interval is derived from, so
            // it is folded in before the alarm is armed: a round that took
            // longer than the period it was given must lengthen that period
            // now, not after another round has already overlapped this one.
            store.recordSpeedTestRound(
                System.currentTimeMillis() - startedAt,
                nodesMeasured,
            )

            screening = false

            SpeedTestReceiver.scheduleNext(this)
        }
    }

    /**
     * Measures one group the toolbar asked for, and moves the connection onto
     * the winner.
     *
     * The round used to run in the proxy screen's own coroutine, which meant
     * walking back to the group list cancelled it: the screen owned the work,
     * so the work ended with the screen. Here it belongs to the service, and
     * the screen only asks for it and watches the mark - which is also why the
     * answer arrives as broadcasts rather than as a return value.
     *
     * The switch is left on by the caller, so a finished round also re-arms
     * the periodic pass and the watch: the first round of the set is this one.
     */
    private suspend fun runFromToolbar(group: String) {
        val store = ServiceStore(applicationContext)

        // Answered before the lock is taken: a toolbar run queued behind a
        // periodic pass can wait twenty minutes, and a toggle that sits
        // unchanged for that long reads as a press that did nothing. The mark
        // is what puts the spinner on the button in the UI process, and the
        // heartbeat keeps it alive through the wait as well as the measurement.
        if (group in store.speedTestGroups) {
            markRunning(store, group)
        }

        val beat = heartbeat(store, group)

        try {
            runLock.withLock {
                // Turned off before the request reached the service: nothing to
                // do, and starting anyway would connect through a group the
                // reader had just told the app to leave alone.
                if (group !in store.speedTestGroups) {
                    clearRunning(store, group)

                    Log.d("speedtest: $group: the switch is off, the request is dropped")

                    return@withLock
                }

                markRunning(store, group)

                updateProgress(getString(R.string.speed_test_group_progress, group, 1, 1))

                var ok = false

                try {
                    ok = runToolbarRound(store, group)
                } catch (e: Throwable) {
                    Log.w("speedtest: $group failed", e)
                } finally {
                    clearRunning(store, group)

                    SpeedTestReceiver.scheduleNext(this)
                    SpeedTestReceiver.scheduleWatch(this)

                    sendSpeedTestGroupFinished(group, ok)
                }
            }
        } finally {
            beat.cancel()

            clearRunning(store, group)
        }
    }

    /**
     * Runs the video group's fast pass for the alarm that asked for it, and
     * re-arms that alarm whatever the answer was.
     *
     * Same ceremony as the toolbar's round - one mark, one heartbeat, the
     * shared lock - because it is a second alarm arriving at its own rhythm
     * into a service that must never measure two things at once. The pass
     * itself is small, so a queue behind a full round costs little.
     */
    private suspend fun runFromVideoAlarm(group: String) {
        val store = ServiceStore(applicationContext)

        if (group in store.speedTestGroups) {
            markRunning(store, group)
        }

        val beat = heartbeat(store, group)

        try {
            runLock.withLock {
                if (group !in store.speedTestGroups) {
                    clearRunning(store, group)

                    Log.d("speedtest: $group: the switch is off, the fast pass is dropped")

                    return@withLock
                }

                markRunning(store, group)

                var ok = false

                try {
                    ok = runVideoFastRound(store, group)
                } catch (e: Throwable) {
                    // Same jaw as the other callers: a LinkageError escaping
                    // here would take the core's whole process down.
                    Log.w("speedtest: $group fast pass failed", e)
                } finally {
                    clearRunning(store, group)

                    SpeedTestReceiver.scheduleVideo(this)

                    sendSpeedTestGroupFinished(group, ok)
                }
            }
        } finally {
            beat.cancel()

            clearRunning(store, group)
        }
    }

    /**
     * One fast pass of the video group: delay first, then the short list.
     *
     * The list is the node in use plus the best handful the last rounds
     * scored - ranked on the throughput figure each one carries, because
     * that group is ranked on speed. Candidates nothing has measured yet
     * come behind them, ordered by the delay the core's history holds: a
     * node with a delay figure is a node that answered, and the quickest
     * answer is the next most promising exit to try. A node with neither a
     * figure nor a delay is left for the hourly full round to wake up.
     *
     * Every name on the list runs the full gate - the latency probe first,
     * which is where "only delays with data" is enforced: a node that does
     * not answer loses its throughput attempt before a byte of it is spent.
     * One round, and a small transfer cap per node, keep the whole pass a
     * fraction of what the hourly round costs.
     *
     * Returns through [screen], so the same bubble switcher, result recording
     * and speed-ranked closing move apply as in every other run.
     */
    private suspend fun runVideoFastRound(store: ServiceStore, group: String): Boolean {
        val info = Clash.queryGroup(group, ProxySort.Default)
        val current = info.inUse.ifEmpty { info.now }
        val members = info.proxies.filter { !it.isGroup && it.name.isNotBlank() }
        val measured = store.speedTestResults().mbps

        val size = store.speedTestVideoRoundSize.coerceIn(3L, 30L).toInt()
        val ranked = members
            .filter { it.name != current && (measured[it.name] ?: 0.0) > 0.0 }
            .sortedByDescending { measured[it.name] ?: 0.0 }
            .map { it.name }
        // Nobody has scored them yet, but their delay history says they
        // answered at some point - the quickest answer is tried first.
        val answered = members
            .filter { it.name != current && it.delay > 0 && measured[it.name] == null }
            .sortedBy { it.delay }
            .map { it.name }

        val pick = (
            ranked.asSequence() + answered.asSequence()
            ).distinct().take(size).toList()

        val shortList = listOfNotNull(current.ifEmpty { null }) + pick

        if (shortList.isEmpty()) {
            Log.d("speedtest: $group: the fast pass has no list to measure")

            return false
        }

        Log.d("speedtest: $group: fast pass over ${shortList.size} nodes")

        return screen(
            store,
            group,
            rounds = 1,
            pick = shortList,
            maxBytes = FAST_ROUND_MAX_BYTES,
        )
    }

    /**
     * Applies the running measurement's improving best to the connection while
     * the round is still running, so the switch never waits for the group to
     * finish. The core measures the node in use first, so the bar everything
     * is judged by is the speed the reader is riding right now, and a later
     * node takes the connection only when it is strictly faster than the best
     * figure seen so far - the fastest one still wins, only sooner than the
     * ranking at the end.
     *
     * The patch is made on this side of a channel rather than inside the
     * callback: the callback is hosted by the run itself, and calling back
     * into the same core from it would make the measurement wait on a call
     * the measurement is hosting.
     */
    private fun bubbleSwitcher(
        store: ServiceStore,
        group: String,
        early: Channel<SpeedTestProgress>,
    ): Job = launch {
        var bestMbps = 0.0

        for (progress in early) {
            val name = progress.best ?: continue
            val mbps = progress.bestMbps

            if (name.isEmpty() || mbps <= bestMbps)
                continue

            // Turned off during the run: the figures may stay, the
            // connection must not move.
            if (group !in store.speedTestGroups)
                continue

            val moved = patchSelectorPath(
                group,
                name,
                progress.bestPath ?: listOf(group),
                { it -> Clash.queryGroup(it, ProxySort.Default) },
                { from, to -> Clash.patchSelector(from, to) },
            ).orEmpty()

            // The figure is adopted whether or not anything moved: the first
            // event is usually the node in use itself, which is already
            // routed and leaves the patch nothing to do, but its speed is
            // still the bar every later node is judged against. Without
            // adopting it the run would take the next passer whatever it
            // measured instead of demanding strictly more than the reader
            // already has.
            bestMbps = mbps

            if (moved.isNotEmpty()) {
                Log.d(
                    "speedtest: $group: switched early to '$name' at " +
                        "${"%.1f".format(mbps)}Mbps via ${moved.joinToString(" -> ")}"
                )
            }
        }
    }

    /**
     * One toolbar round: measure, keep the figures, and move the selection onto
     * the fastest member that passed.
     *
     * The periodic pass only switches when the node in use stopped working,
     * because it is repairing a connection nobody asked about. This is the
     * reader asking for the best node now, so the winner takes the selection
     * whether or not the previous node still works.
     */
    private suspend fun runToolbarRound(store: ServiceStore, group: String): Boolean {
        val startedAt = System.currentTimeMillis()

        // A manual run is started because the node in use stopped carrying, so
        // waiting for the whole group before moving the connection leaves the
        // reader offline for the length of the run.
        val early = Channel<SpeedTestProgress>(Channel.CONFLATED)

        val switcher = bubbleSwitcher(store, group, early)
        // The video group proves YouTube playback; every other group only
        // needs latency plus a generic transfer, so the YouTube probes are
        // left out of its run rather than spending traffic on them.
        val envelope = try {
            Clash.speedTestGroup(group, SpeedTestProfile.options(group)) { early.trySend(it) }
        } finally {
            early.close()

            switcher.cancelAndJoin()
        }

        if (envelope.ok) {
            logSpeedTestStages(
                group,
                envelope,
                System.currentTimeMillis() - startedAt,
            )
        } else {
            Log.d("speedtest: $group: ${envelope.error}")

            return false
        }

        val best = envelope.best

        if (best.isNullOrEmpty()) {
            Log.w("speedtest: $group: nothing cleared the gate")

            return false
        }

        val top = envelope.results.firstOrNull { r -> r.name == best }

        // The whole list, not just the winner: the proxy screen shows a
        // figure beside every node the round measured, and this is the only
        // moment those figures exist.
        recordResults(store, envelope)

        store.setSpeedTestSelection(
            group,
            SpeedTestSelection(
                default = best,
                backup = envelope.backup.orEmpty(),
                mbps = top?.mbps ?: 0.0,
                updatedAt = System.currentTimeMillis(),
            )
        )

        // Pressing the button again turns the switch off, and the request to
        // stop has to win even though the measurement it was sent during cannot
        // be interrupted: the figures stay, but the connection is not moved.
        if (group !in store.speedTestGroups) {
            Log.d("speedtest: $group: the switch was turned off during the run, selection left alone")

            return true
        }

        // A node behind a sub group cannot be picked from the group above
        // it: Set names a member, so the whole route down to the node has
        // to be moved, not just the group that holds it.
        val path = top?.path ?: listOf(group)

        val moved = patchSelectorPath(
            group,
            best,
            path,
            { name -> Clash.queryGroup(name, ProxySort.Default) },
            { from, name -> Clash.patchSelector(from, name) },
        ).orEmpty()

        if (moved.isNotEmpty()) {
            Log.d("speedtest: $group: switched to '$best' via ${moved.joinToString(" -> ")}")
        }

        return true
    }

    /**
     * Tells the UI process a round started for [group].
     *
     * Written before the measurement rather than after it: the spinner has to
     * appear while the work is being done, and a mark written at the end would
     * only ever describe a round that already finished.
     */
    private fun markRunning(store: ServiceStore, group: String) {
        store.speedTestRunningGroup = group
        store.speedTestRunningSince = System.currentTimeMillis()

        sendSpeedTestRunningChanged()
    }

    /**
     * Refreshes [group]'s mark while the measurement runs.
     *
     * The mark is read in another process, which cannot see that this one is
     * still alive: a killed worker leaves the mark behind, and the screen then
     * shows a spinner for a round that will never end. The mark therefore
     * carries a timestamp with a short window (see
     * [ServiceStore.SPEED_TEST_RUNNING_TTL_MS]), and a long round keeps it
     * alive by rewriting it. Folded into a coroutine the caller cancels, so an
     * abandoned round stops beating the moment it stops working.
     */
    private fun heartbeat(store: ServiceStore, group: String) = launch {
        while (isActive) {
            if (store.speedTestRunningGroup == group) {
                store.speedTestRunningSince = System.currentTimeMillis()
            }

            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    /**
     * Clears [group]'s mark, if it still holds it.
     *
     * Guarded by the group name so a round that ends after the reader has
     * already turned another group on cannot clear that group's mark.
     */
    private fun clearRunning(store: ServiceStore, group: String) {
        if (store.speedTestRunningGroup != group) {
            return
        }

        store.clearSpeedTestRunning(group)

        sendSpeedTestRunningChanged()
    }

    /**
     * Drops the enabled groups the running configuration no longer has, and
     * returns what is left.
     *
     * The toggle writes a name into a set and the name is all it keeps, so a
     * profile update that renames or removes a group leaves the entry behind
     * with nothing pointing at it. Every pass then measures a group that does
     * not exist and answers "not found", forever, for a switch nobody can see
     * and turn off. Reading the live names is the only honest test: the set is
     * only meaningful as a subset of the groups the core is actually running.
     *
     * Two strikes, though, because the opposite failure is far worse than the
     * one being fixed. A profile update swaps the configuration out from under
     * a running core, and a pass that lands inside that window reads a list
     * that is empty or half-built. Dropping on the first miss would answer
     * that window by silently deleting every toggle the user had set, with no
     * way to tell afterwards that they had ever been on. A group therefore has
     * to be missing from two consecutive passes - at least five minutes apart -
     * before it is believed gone, which no reload window lasts long enough to
     * fake. The empty list is refused outright for the same reason: a core with
     * no groups is a core mid-swap, not a configuration that lost them all.
     *
     * The ranking and the watch state go with a group that is dropped. They are
     * keyed by the same name and nothing would ever read them again, so leaving
     * them behind would grow the preferences file by a group per stale entry
     * for as long as the profile kept moving.
     *
     * Cancelling the alarms is left to the caller's `scheduleNext`, which
     * already stands both down when the set comes back empty.
     */
    private fun liveGroups(store: ServiceStore): List<String> {
        val enabled = store.speedTestGroups

        if (enabled.isEmpty()) {
            return emptyList()
        }

        val configured = try {
            Clash.queryGroupNames(excludeNotSelectable = false).toSet()
        } catch (e: Throwable) {
            // A bridge that cannot answer says nothing about which groups exist,
            // so the set is left exactly as it was rather than judged against
            // an empty list.
            Log.w("speedtest: cannot list the running groups", e)

            return enabled.sorted()
        }

        if (configured.isEmpty()) {
            return enabled.sorted()
        }

        val missing = enabled - configured
        val gone = missing.intersect(store.speedTestMissingGroups)

        store.speedTestMissingGroups = missing - gone

        val kept = enabled - gone

        if (kept.size == enabled.size) {
            return kept.sorted()
        }

        store.speedTestGroups = kept

        gone.forEach { group ->
            store.setSpeedTestSelection(group, null)
            store.setSpeedTestWatchState(group, null)
            store.setSpeedTestFailoverCursor(group, 0)

            Log.d("speedtest: dropped '$group': not in the running configuration")
        }

        return kept.sorted()
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

            liveGroups(store).forEach { group ->
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
        // slower than the limit: hand the connection to the next node already
        // known to work.
        //
        // The node the last switch left behind is off the table while the
        // cooldown lasts, otherwise two nodes that both drag would trade
        // places every pass and the connection would ping-pong between them.
        val avoid = if (
            previous != null && previous.switchedFrom.isNotEmpty() &&
            now - previous.switchedAt < WATCH_COOLDOWN_MS
        ) previous.switchedFrom else ""

        val took = failover(store, group, current, avoid)

        if (took.isEmpty()) {
            return false
        }

        store.setSpeedTestWatchState(
            group,
            SpeedTestWatchState(
                node = took,
                // The snapshot was taken before the switch, so the count of
                // the node that just took over is where its window starts;
                // traffic it carries from here on is what the next pass sees.
                bytesAt = traffic.nodes.firstOrNull { it.name == took }?.bytes ?: 0,
                atMs = now,
                switchedFrom = current,
                switchedAt = now,
            )
        )

        return true
    }

    /**
     * Hands the connection to the next node already known to work, and returns
     * the node it settled on. Empty when nothing could take over.
     *
     * This used to re-measure the whole group to find a replacement, and that
     * is the wrong way round: picking a substitute is a question about who
     * answers, and the answer is already standing in the per-node figures -
     * every node the last round cleared the gate, fastest first. The node that
     * just failed drops out of that order (its figure is removed the moment a
     * probe fails), the next one is probed, and only that one is paid for.
     *
     * A full round over a group of this size costs 174 nodes twice over to
     * replace one dead connection, and it does it every time: a connection
     * that flapped twice in an hour spent 174 MiB each time, on a phone that
     * may be streaming something else at the time. A candidate here costs a
     * quarter of a megabyte through the same real gate, and a switch is never
     * made on the strength of a stored number alone - the probe is what
     * proves the traffic went through that node.
     *
     * Only the first few are tried, and each one that fails leaves the queue,
     * so the next pass starts from a pool one node shorter rather than paying
     * the same probe again.
     *
     * When there is no queue at all - nothing of this group has ever cleared
     * the gate - it falls back to sweeping the group's own members instead of
     * doing nothing. Standing still would leave a dead connection dead for a
     * whole interval, and the cost of trying is the same few hundred
     * kilobytes either way. It does *not* fall back to measuring the group:
     * that is the 174 MiB this exists to avoid, and a sweep of the members
     * will find a working one just as often as a sweep of everything would,
     * for a hundredth of the data.
     *
     * The periodic round is what fills the queue properly again.
     */
    private suspend fun failover(
        store: ServiceStore,
        group: String,
        failed: String,
        avoid: String,
    ): String {
        val members = memberNames(group)

        // Membership first, because the figures are kept per node rather than
        // per group and every group shares them. That is right for reading -
        // a node's throughput does not care which group reached it - and wrong
        // for choosing a substitute, since only a member of *this* group can
        // be switched to. Left unfiltered, a group whose own nodes happen not
        // to be the fastest ones spends its candidates on nodes it cannot
        // reach and leaves a dead connection standing, which is the one thing
        // a watch exists to prevent.
        val known = store.speedTestResults().mbps
            .filterKeys { it in members && it != failed && it != avoid }
            .entries
            .sortedByDescending { it.value }

        val queue = if (known.isEmpty()) {
            // Nothing of this group has ever cleared the gate, so there is no
            // ranking to walk. That is not an excuse to leave a dead
            // connection dead for a whole interval: the members are still
            // there, and asking the gate about a few of them costs the same
            // bounded amount a walk would.
            //
            // The sweep starts where the last one stopped rather than at the
            // top of the group every time. Starting at the top would probe the
            // same dead nodes for as long as the pool stays broken - they
            // never had a figure, so they never leave the queue, and a standby
            // that does answer stays untouched behind them.
            val pool = members.filter { it != failed && it != avoid }

            if (pool.isEmpty()) {
                emptyList()
            } else {
                val cursor = store.speedTestFailoverCursor(group)
                val offset = cursor % pool.size
                val window = List(minOf(FAILOVER_SCAN, pool.size)) {
                    pool[(offset + it) % pool.size]
                }

                // Advanced by what was looked at rather than by the budget: a
                // name the gate refuses is skipped over, not measured, and
                // stopping the cursor short would walk past the very names
                // this sweep exists to reach.
                store.setSpeedTestFailoverCursor(group, cursor + window.size)

                window
            }
        } else {
            known.take(FAILOVER_SCAN).map { it.key }
        }

        // Candidates the gate refuses cost nothing - the refusal comes back
        // before a byte is measured - so they are walked over rather than
        // counted against the budget. A configuration that keeps a name the
        // gate will not measure at the head of its groups would otherwise
        // spend every pass on that one name and never reach a real node.
        var measured = 0

        for (candidate in queue) {
            if (measured >= FAILOVER_CANDIDATES) {
                break
            }

            val probe = Clash.speedTestGroup(
                group,
                SpeedTestOptions(
                    rounds = 1,
                    only = candidate,
                    maxBytes = WATCH_PROBE_BYTES,
                    skipReachability = true,
                )
            )

            if (!probe.ok) {
                // Not a member of this group any more, or the core will not
                // measure it at all: either way nothing was learned and
                // nothing was spent.
                Log.d(
                    "speedtest watch: $group: cannot probe '$candidate': " +
                        probe.error.orEmpty()
                )

                continue
            }

            measured++

            // The probe's own outcome goes into the standing figures, so a
            // candidate that fails here leaves the queue instead of costing
            // this probe again on every pass until the next full round. A
            // candidate that passes joins the ranking the next failover walks.
            recordResults(store, probe)

            val outcome = probe.results.firstOrNull { it.name == candidate }

            if (outcome == null || !outcome.passed) {
                continue
            }

            val owner = outcome.owner.orEmpty()

            if (owner.isEmpty()) {
                // Nothing along the way can hold a selection - a loadbalance
                // or an available group picks on its own - so there is nothing
                // to move and the node is left alone rather than patched into
                // a group it is not a member of.
                Log.d("speedtest watch: $group: '$candidate' has no group that can be moved")

                continue
            }

            // The probe measured one node of this group, so the route it came
            // back with is the route to patch: hop by hop down to the group
            // that actually holds it.
            val moved = patchSelectorPath(
                group,
                candidate,
                outcome.path ?: listOf(group),
                { name -> Clash.queryGroup(name, ProxySort.Default) },
                { from, name -> Clash.patchSelector(from, name) },
            ) ?: continue

            store.setSpeedTestSelection(
                group,
                SpeedTestSelection(
                    default = candidate,
                    backup = store.speedTestResults().mbps
                        .filterKeys { it in members && it != candidate }
                        .maxByOrNull { it.value }?.key
                        .orEmpty(),
                    mbps = outcome.mbps ?: 0.0,
                    updatedAt = System.currentTimeMillis(),
                )
            )

            if (moved.isEmpty()) {
                // The route already reaches it, so nothing was switched:
                // claiming one would report a node change that never
                // happened, though the node is healthy and the state is right.
                Log.d("speedtest watch: $group: '$candidate' already carries the connection")

                store.setSpeedTestFailoverCursor(group, 0)

                return candidate
            }

            Log.d(
                "speedtest watch: $group: '$failed' failed, switched to " +
                    "'$candidate' via ${moved.joinToString(" -> ")}"
            )

            store.setSpeedTestFailoverCursor(group, 0)

            return candidate
        }

        Log.w(
            "speedtest watch: $group: '$failed' failed and none of " +
                "$measured candidates answered; waiting for the next round"
        )

        return ""
    }

    /**
     * Every leaf node reachable through [group], sub groups walked into.
     *
     * The core can already do this walk as part of a measurement, but asking
     * it to measure is the expensive thing this whole change exists to avoid,
     * and walking the tree in Kotlin costs a few lookups that answer
     * immediately. A name that resolves to nothing yields no members rather
     * than an error, because the caller is only ever narrowing a list it
     * already has.
     *
     * Bounded twice over: by the same depth the core's own walk uses, and by a
     * visited set, so a configuration that names a group inside itself costs
     * nothing instead of looping.
     */
    private fun memberNames(group: String): Set<String> {
        val members = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<Pair<String, Int>>()

        pending.addLast(group to 0)

        while (pending.isNotEmpty()) {
            val (name, depth) = pending.removeLast()

            if (!visited.add(name) || depth > MAX_GROUP_DEPTH) {
                continue
            }

            Clash.queryGroup(name, ProxySort.Default).proxies.forEach { proxy ->
                if (proxy.isGroup) {
                    pending.addLast(proxy.name to depth + 1)
                } else {
                    members.add(proxy.name)
                }
            }
        }

        return members
    }

    /**
     * Keeps the figures a round measured, so the proxy screen can show one
     * beside every node instead of only beside the winner.
     *
     * Merged rather than replaced. A pass that measures one group must not
     * erase what the others measured an hour ago, and the watch measures a
     * single node every couple of minutes - replacing outright would empty the
     * map minutes after it was filled. A node this round did measure has its
     * old figure *removed* rather than kept, so a node that has since stopped
     * passing stops showing a speed instead of showing the last good one for
     * ever.
     */
    private fun recordResults(store: ServiceStore, envelope: SpeedTestEnvelope) {
        val measured = envelope.results.filter { !it.name.isBlank() }

        if (measured.isEmpty()) {
            return
        }

        // Counted here rather than from the group's size: this is where the
        // pass learns how much work it actually did, which is what the next
        // interval is derived from before any of it has been timed.
        nodesMeasured += measured.size

        val previous = store.speedTestResults()
        val merged = previous.mbps.toMutableMap()

        measured.forEach {
            if (it.passed) {
                merged[it.name] = it.mbps ?: 0.0
            } else {
                merged.remove(it.name)
            }
        }

        store.setSpeedTestResults(SpeedTestResults(merged, System.currentTimeMillis()))

        // Sent only when the figures actually moved: a group whose nodes all
        // failed this round changes nothing on screen, and a reload per round
        // would repaint six hundred proxy cards to say the same thing.
        if (merged != previous.mbps) {
            sendSpeedTestResultsChanged()
        }
    }

    /**
     * Measures one group, refreshes its standing ranking, and moves the
     * selector only when the current default has stopped clearing the gate.
     * On the video group it also follows the speed ranking while it is
     * measured, and ends on the round's winner even when the old default
     * still passes, because that group is picked on speed alone.
     *
     * Returns true when the selector was moved, so the caller can tell the
     * user about the failover. [avoid] names a node that must not be picked
     * even though it passed, which is how the watch keeps a node it just
     * switched away from from being chosen again the next moment.
     */    private suspend fun screen(
        store: ServiceStore,
        group: String,
        avoid: String = "",
        rounds: Int = 2,
        pick: List<String> = emptyList(),
        maxBytes: Long = 0,
    ): Boolean {
        // Two rounds: a single lucky transfer must not decide which node the
        // whole app connects through when nobody is watching. The worst round
        // is the score, so the ranking needs both rounds to hold up.
        val startedAt = System.currentTimeMillis()

        // On the video group the ranking is speed, which is exactly what the
        // moving best a measurement reports is for: while the round runs, the
        // connection already rides the best node measured so far instead of
        // waiting for the whole group to finish. Every other group keeps the
        // old deal - its pass only repairs, so there is nothing to decide
        // before the end.
        val early = Channel<SpeedTestProgress>(Channel.CONFLATED)
        val switcher =
            if (SpeedTestProfile.isVideoGroup(group)) bubbleSwitcher(store, group, early) else null

        // The group profile decides the gate itself; the fast pass narrows
        // the roster to its short list and caps every transfer on it, so a
        // five-minute rhythm costs a fraction of the full round.
        val options = SpeedTestProfile.options(group, rounds)
            .let { if (pick.isEmpty()) it else it.copy(pick = pick) }
            .let { if (maxBytes > 0) it.copy(maxBytes = maxBytes) else it }

        val envelope = try {
            Clash.speedTestGroup(group, options) {
                early.trySend(it)
            }
        } finally {
            early.close()

            switcher?.cancelAndJoin()
        }

        if (envelope.ok) {
            logSpeedTestStages(
                group,
                envelope,
                System.currentTimeMillis() - startedAt,
            )
        }

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

        // Every node the round measured, not only the one that won. The ranking
        // needs the winner; the reader needs the rest, and this is the only
        // moment the figures exist.
        recordResults(store, envelope)

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
        // underneath it is refreshed. Not on the video group: its standard is
        // speed, so a slower default does not stand and the round's winner
        // takes the connection even though the old one still works. That move
        // is an upgrade, not a repair - nothing broke - so the pass stays
        // quiet about it, and the early switcher above has usually already
        // landed here first.
        if (current in passedNames) {
            if (!SpeedTestProfile.isVideoGroup(group) || best == current)
                return false

            val upgraded = patchSelectorPath(
                group,
                best,
                paths[best] ?: listOf(group),
                { name -> Clash.queryGroup(name, ProxySort.Default) },
                { from, name -> Clash.patchSelector(from, name) },
            ).orEmpty()

            if (upgraded.isEmpty()) {
                // The early switcher already moved the connection onto the
                // winner, or the route already reaches it: nothing to claim.
                return false
            }

            Log.d(
                "speedtest: $group: upgraded '$current' to the faster '$best' " +
                    "(${top?.mbps ?: 0.0}Mbps) via ${upgraded.joinToString(" -> ")}"
            )

            return false
        }

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
        // How far down the known-good queue one failover reaches. Three is
        // three quarter-megabyte probes in the worst case, against a full
        // round's hundreds of megabytes; a candidate that fails leaves the
        // queue, so a pass that finds nothing just starts further down next
        // time rather than paying for the same refusals again.
        private const val FAILOVER_CANDIDATES = 3

        // How many names one failover looks at to find that many worth
        // measuring. Larger than the budget because names the core refuses
        // cost nothing - the refusal returns before any transfer - so walking
        // over them is free, while stopping at the budget would spend the
        // whole allowance on whatever the configuration happens to list first.
        private const val FAILOVER_SCAN = 12

        // How deep a sub group chain is followed when collecting the members a
        // failover may choose from. Matches the core's own bound on the walk a
        // measurement does.
        private const val MAX_GROUP_DEPTH = 8

        // How often a running mark is refreshed. Must stay well under
        // ServiceStore.SPEED_TEST_RUNNING_TTL_MS, and a long round is the
        // reason the mark cannot simply be written once: the screen in the
        // other process needs to be able to tell a live round from one whose
        // worker was killed.
        private const val HEARTBEAT_INTERVAL_MS = 20L * 1000L

        // The transfer cap of one video fast pass per node: the watch probe's
        // own budget. The five-minute rhythm is what makes this small - a full
        // gate on a wide list is the hourly round's job, and a quarter
        // megabyte is enough to tell a living exit from one that answers
        // probes but carries nothing.
        private const val FAST_ROUND_MAX_BYTES = 256L * 1024L
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }
}
