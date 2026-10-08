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
import com.github.kr328.clash.service.model.SpeedTestSelection
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Re-measures every group whose toolbar toggle is on and repairs the
 * connection when the default node stopped answering.
 *
 * The core only lives in :background, which is why this is a foreground
 * service rather than anything the UI process could run: the measurement goes
 * through [Clash.speedTestGroup], the same live adapters the connection uses.
 * The alarm is re-armed once the pass finishes, including when it fails.
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
                } catch (e: Exception) {
                    Log.w("speedtest: $group failed", e)
                }
            }
        } finally {
            SpeedTestReceiver.scheduleNext(this)
        }
    }

    /**
     * Measures one group, refreshes its standing ranking, and moves the
     * selector only when the current default has stopped clearing the gate.
     *
     * Returns true when the selector was moved, so the caller can tell the
     * user about the failover.
     */
    private suspend fun screen(store: ServiceStore, group: String): Boolean {
        // Two rounds: a single lucky transfer must not decide which node the
        // whole app connects through when nobody is watching. The worst round
        // is the score, so the ranking needs both rounds to hold up.
        val envelope = Clash.speedTestGroup(group, SpeedTestOptions(rounds = 2))

        if (!envelope.ok) {
            Log.w("speedtest: $group: ${envelope.error}")

            return false
        }

        val passed = envelope.results.filter { it.passed }.map { it.name }.toSet()
        val best = envelope.best

        if (best == null || best.isEmpty() || best !in passed)
            return false

        // Read before writing: the previous standby is the first candidate to
        // take over, and overwriting first would lose it.
        val previous = store.speedTestSelection(group)
        val top = envelope.results.firstOrNull { it.name == best }

        store.setSpeedTestSelection(
            group,
            SpeedTestSelection(
                default = best,
                backup = envelope.backup.orEmpty(),
                mbps = top?.mbps ?: 0.0,
                updatedAt = System.currentTimeMillis(),
            )
        )

        val current = Clash.queryGroup(group, ProxySort.Default).now

        // The default still passes: keep it. The user's choice (or a previous
        // round's switch) stands as long as it works, and only the ranking
        // underneath it is refreshed.
        if (current in passed)
            return false

        val target = listOfNotNull(previous?.backup, best, envelope.backup)
            .firstOrNull { it.isNotEmpty() && it in passed && it != current }

        if (target == null) {
            Log.w("speedtest: $group: default '$current' failed and nothing stood behind it")

            return false
        }

        // Deliberately Clash.patchSelector and not the binder's patchSelector:
        // a failover is transient and must not be persisted into the profile's
        // saved selection, so the next profile load returns to the user's node.
        if (!Clash.patchSelector(group, target)) {
            Log.w("speedtest: $group: selector refused '$target'")

            return false
        }

        Log.d("speedtest: $group: '$current' failed, switched to '$target'")

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
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }
}
