package com.github.kr328.clash.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.SpeedTestProfile
import java.util.concurrent.TimeUnit

/**
 * Fires the periodic speed screening of the groups the toolbar toggle has
 * enabled.
 *
 * Same shape as [ProbTestReceiver]: a plain repeating alarm rather than a
 * coroutine loop so the round survives the worker being killed, re-armed
 * after every run. The alarm lands on this receiver, which redirects to the
 * foreground [SpeedTestWorker] in :background — where the live core the
 * measurement needs actually runs.
 */
class SpeedTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intents.ACTION_SPEEDTEST_REQUEST -> {
                val redirect = intent.setComponent(SpeedTestWorker::class.componentName)

                context.startForegroundServiceCompat(redirect)
            }

            Intents.ACTION_SPEEDTEST_WATCH -> {
                val redirect = intent.setComponent(SpeedTestWorker::class.componentName)

                context.startForegroundServiceCompat(redirect)
            }

            Intents.ACTION_SPEEDTEST_VIDEO -> {
                // The alarm intent carries no group: the roster may have
                // changed while the alarm sat, so the name is re-read here
                // rather than frozen into the pending intent at arm time.
                val group = ServiceStore(context).speedTestGroups
                    .firstOrNull(SpeedTestProfile::isVideoGroup)

                if (group == null) {
                    Log.d("speedtest: no video group enabled, the fast pass is dropped")

                    return
                }

                val redirect = intent.setComponent(SpeedTestWorker::class.componentName)
                    .putExtra(Intents.EXTRA_GROUP, group)

                context.startForegroundServiceCompat(redirect)
            }
        }
    }

    companion object {
        /**
         * Arms the next round, or cancels it when no group is enabled any
         * more.
         *
         * The period is not simply the setting: it is
         * [ServiceStore.effectiveSpeedTestIntervalMinutes], which is the setting
         * unless the last pass took longer than that. A pass over 174 nodes
         * with most of them dead was measured at 36 minutes, so a five minute
         * setting would fire every fifth minute of a round that is still
         * running and leave the device measuring continuously. The setting
         * says how often the reader would like this; this is what the device
         * can actually do, and when the two disagree the device wins, because
         * the alternative is not a faster answer but two rounds at once.
         *
         * Safe to call from the UI process: the alarm is held by the system
         * and the pending intent names this receiver explicitly, so delivery
         * always ends up in :background regardless of who armed it.
         */
        fun scheduleNext(context: Context) {
            val intent = pendingIntentOf(context)
            val alarm = context.getSystemService<AlarmManager>() ?: return

            alarm.cancel(intent)

            val store = ServiceStore(context)

            if (store.speedTestGroups.isEmpty()) {
                Log.d("speedtest: no group enabled, no round scheduled")

                cancelWatch(context)

                return
            }

            val asked = store.speedTestIntervalMinutes
                .coerceAtLeast(ServiceStore.MIN_SPEED_TEST_INTERVAL_MINUTES)
            val effective = store.effectiveSpeedTestIntervalMinutes()
            val basis = store.speedTestIntervalBasis()
            val delay = TimeUnit.MINUTES.toMillis(effective)

            Log.d(
                if (effective == asked) {
                    "speedtest: next round in ${delay / 60000} minutes"
                } else {
                    // Said out loud, and said with its reason, because a reader
                    // who typed 5 and is told 87 has no way to tell that the
                    // number came from a measurement rather than from a
                    // mistake.
                    val why = if (basis == "structural") {
                        "${store.speedTestRoundNodes} nodes cannot be measured " +
                            "in less than ${store.structuralSpeedTestRoundMs() / 60000} minutes"
                    } else {
                        "the last round took longer than that"
                    }

                    "speedtest: next round in ${delay / 60000} minutes " +
                        "(raised from $asked: $why)"
                }
            )

            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + delay, intent)
        }

        fun cancelNext(context: Context) {
            context.getSystemService<AlarmManager>()?.cancel(pendingIntentOf(context))
        }

        /**
         * Arms the watch pass of the node each enabled group is connected
         * through, or cancels it when the drawer's online-watch switch is
         * off or no group is enabled any more.
         *
         * The rhythm this arms is the switch, not the old minutes row: one
         * pass costs a free traffic snapshot per group and only spends a
         * probe when that snapshot says the node in use stopped carrying, so
         * a tight rhythm answers a stalled connection without spending the
         * device's data on the check — while the reader is actually riding
         * the tunnel. An idle connection walks the paid-probe arm of the
         * funnel every pass, which is exactly why the switch exists: the
         * top-of-the-minute pace is the reader's own choice, made once at
         * the drawer, not a number buried in the intervals screen.
         *
         * Safe to call from the UI process: the alarm is held by the system
         * and the pending intent names this receiver explicitly, so delivery
         * always ends up in :background regardless of who armed it.
         */
        fun scheduleWatch(context: Context) {
            val intent = watchIntentOf(context)
            val alarm = context.getSystemService<AlarmManager>() ?: return

            alarm.cancel(intent)

            val store = ServiceStore(context)

            if (store.speedTestGroups.isEmpty()) {
                Log.d("speedtest: no group enabled, no watch scheduled")

                return
            }

            if (!store.speedTestWatchOnline) {
                Log.d("speedtest: online watch off, no watch scheduled")

                return
            }

            val delay = WATCH_ONLINE_INTERVAL_MS

            Log.d("speedtest: next watch in ${delay / 1000} seconds")

            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + delay, intent)
        }

        fun cancelWatch(context: Context) {
            context.getSystemService<AlarmManager>()?.cancel(watchIntentOf(context))
        }

        /**
         * The heartbeat the online-watch switch arms: a 10 second pass.
         *
         * The interval lives here rather than in the intervals screen on
         * purpose. The snapshot half of a pass is free, so when the reader
         * rides the tunnel the tight pace costs nothing; but an idle
         * connection draws the paid-probe arm of the funnel on every pass,
         * and at ten seconds that is 2.2 GiB a day - 256 KiB per probe,
         * 8,640 probes. Leaving the number out of the settings is what keeps
         * the switch honest: it is the reader saying "watch me now", and the
         * reader who wants to stop paying simply drops the switch again.
         */
        private const val WATCH_ONLINE_INTERVAL_MS = 10_000L

        /**
         * Arms the video group's fast pass, or cancels it when no video group
         * is enabled any more.
         *
         * A third alarm rather than a branch of the existing two: the full
         * round answers "is every node of every group still worth ranking"
         * and the watch answers "is the current exit alive", but neither is
         * built to run a short, capped list every few minutes against one
         * group. The interval is cheap by construction - a handful of names,
         * one round, a small transfer cap each - so the minimum sits well
         * under the full round's own floor.
         */
        fun scheduleVideo(context: Context) {
            val intent = videoIntentOf(context)
            val alarm = context.getSystemService<AlarmManager>() ?: return

            alarm.cancel(intent)

            val store = ServiceStore(context)

            if (store.speedTestGroups.none(SpeedTestProfile::isVideoGroup)) {
                Log.d("speedtest: no video group enabled, no fast pass scheduled")

                return
            }

            val delay =
                TimeUnit.MINUTES.toMillis(store.speedTestVideoIntervalMinutes.coerceAtLeast(5))

            Log.d("speedtest: next video fast pass in ${delay / 60000} minutes")

            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + delay, intent)
        }

        fun cancelVideo(context: Context) {
            context.getSystemService<AlarmManager>()?.cancel(videoIntentOf(context))
        }

        /**
         * Measures one group now, in :background.
         *
         * Called from the proxy screen's toolbar. The round runs in the worker
         * rather than in the caller because the caller is a screen that can be
         * closed: walking back to the group list used to cancel the round with
         * the screen's coroutine, and the service is also where the live core
         * the measurement needs actually lives.
         *
         * Not an alarm: this is one run asked for now, so there is nothing to
         * re-arm. The worker serialises it with a periodic pass that is
         * already in flight instead of measuring two groups at once.
         */
        fun requestGroup(context: Context, group: String) {
            val intent = Intent(Intents.ACTION_SPEEDTEST_GROUP)
                .setComponent(SpeedTestWorker::class.componentName)
                .putExtra(Intents.EXTRA_GROUP, group)

            context.startForegroundServiceCompat(intent)
        }

        private fun pendingIntentOf(context: Context): PendingIntent {
            val intent = Intent(Intents.ACTION_SPEEDTEST_REQUEST)
                .setComponent(SpeedTestReceiver::class.componentName)

            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }

        private fun watchIntentOf(context: Context): PendingIntent {
            val intent = Intent(Intents.ACTION_SPEEDTEST_WATCH)
                .setComponent(SpeedTestReceiver::class.componentName)

            return PendingIntent.getBroadcast(
                context,
                1,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }

        private fun videoIntentOf(context: Context): PendingIntent {
            val intent = Intent(Intents.ACTION_SPEEDTEST_VIDEO)
                .setComponent(SpeedTestReceiver::class.componentName)

            return PendingIntent.getBroadcast(
                context,
                2,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }
    }
}
