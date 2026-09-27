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
 * Fires the periodic candidate screening round.
 *
 * Scheduling is a plain repeating alarm rather than a coroutine loop so the
 * round survives the worker being killed, and it is re-armed after every run
 * exactly like the profile updater does.
 */
class ProbTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> scheduleNext(context)

            Intents.ACTION_PROBTEST_REQUEST -> {
                val redirect = intent.setComponent(ProbTestWorker::class.componentName)

                context.startForegroundServiceCompat(redirect)
            }
        }
    }

    companion object {
        /** Runs a round now and re-arms the next one. */
        fun schedule(context: Context) {
            context.startForegroundServiceCompat(
                Intent(Intents.ACTION_PROBTEST_REQUEST)
                    .setComponent(ProbTestReceiver::class.componentName)
            )
        }

        /**
         * Arms the next round, or cancels it when the feature is switched off.
         * A minimum of five minutes keeps a mistyped interval from hammering the
         * test URL and the GitHub API.
         */
        fun scheduleNext(context: Context) {
            val intent = pendingIntentOf(context)
            val alarm = context.getSystemService<AlarmManager>() ?: return

            alarm.cancel(intent)

            val interval = ServiceStore(context).probtestIntervalMinutes
            if (!ServiceStore(context).probtestEnabled) {
                Log.d("probtest: disabled, no round scheduled")
                return
            }

            val delay = TimeUnit.MINUTES.toMillis(interval.coerceAtLeast(5))

            Log.d("probtest: next round in ${delay / 60000} minutes")

            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + delay, intent)
        }

        fun cancelNext(context: Context) {
            context.getSystemService<AlarmManager>()?.cancel(pendingIntentOf(context))
        }

        private fun pendingIntentOf(context: Context): PendingIntent {
            val intent = Intent(Intents.ACTION_PROBTEST_REQUEST)
                .setComponent(ProbTestReceiver::class.componentName)

            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }
    }
}
