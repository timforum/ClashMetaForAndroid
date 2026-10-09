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
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                scheduleNext(context)
                scheduleWatch(context)
            }

            Intents.ACTION_SPEEDTEST_REQUEST -> {
                val redirect = intent.setComponent(SpeedTestWorker::class.componentName)

                context.startForegroundServiceCompat(redirect)
            }

            Intents.ACTION_SPEEDTEST_WATCH -> {
                val redirect = intent.setComponent(SpeedTestWorker::class.componentName)

                context.startForegroundServiceCompat(redirect)
            }
        }
    }

    companion object {
        /**
         * Arms the next round, or cancels it when no group is enabled any
         * more. A minimum of five minutes keeps a mistyped interval from
         * re-measuring the whole configuration in a loop.
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

            val delay = TimeUnit.MINUTES.toMillis(store.speedTestIntervalMinutes.coerceAtLeast(5))

            Log.d("speedtest: next round in ${delay / 60000} minutes")

            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + delay, intent)
        }

        fun cancelNext(context: Context) {
            context.getSystemService<AlarmManager>()?.cancel(pendingIntentOf(context))
        }

        /**
         * Arms the next watch pass of the node each enabled group is
         * connected through, or cancels it when no group is enabled any more.
         *
         * Deliberately far tighter than [scheduleNext]: a watch pass costs a
         * free traffic snapshot per group and only spends a probe when that
         * snapshot says the node in use stopped carrying, so a tight rhythm
         * answers a stalled connection without spending the device's data on
         * the check. A minimum of one minute keeps a mistyped value from
         * turning the watch into a probe loop.
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

            val delay = TimeUnit.MINUTES.toMillis(store.speedTestWatchMinutes.coerceAtLeast(1))

            Log.d("speedtest: next watch in ${delay / 60000} minutes")

            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + delay, intent)
        }

        fun cancelWatch(context: Context) {
            context.getSystemService<AlarmManager>()?.cancel(watchIntentOf(context))
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
                0,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }
    }
}
