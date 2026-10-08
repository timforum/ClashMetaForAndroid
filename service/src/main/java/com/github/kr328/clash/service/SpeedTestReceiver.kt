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
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> scheduleNext(context)

            Intents.ACTION_SPEEDTEST_REQUEST -> {
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

                return
            }

            val delay = TimeUnit.MINUTES.toMillis(store.speedTestIntervalMinutes.coerceAtLeast(5))

            Log.d("speedtest: next round in ${delay / 60000} minutes")

            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + delay, intent)
        }

        fun cancelNext(context: Context) {
            context.getSystemService<AlarmManager>()?.cancel(pendingIntentOf(context))
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
    }
}
