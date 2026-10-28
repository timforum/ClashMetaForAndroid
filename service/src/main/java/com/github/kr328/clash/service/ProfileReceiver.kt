package com.github.kr328.clash.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class ProfileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intents.ACTION_PROFILE_REQUEST_UPDATE) {
            val redirect = intent.setComponent(ProfileWorker::class.componentName)

            context.startForegroundServiceCompat(redirect)
        }
    }

    companion object {
        private val lock = Mutex()
        private var initialized: Boolean = false

        suspend fun rescheduleAll(context: Context) = lock.withLock {
            if (initialized)
                return

            initialized = true

            Log.i("Reschedule all profiles update")

            ImportedDao().queryAllUUIDs()
                .mapNotNull { ImportedDao().queryByUUID(it) }
                .filter { it.type != Profile.Type.File }
                .forEach { scheduleNext(context, it) }
        }

        /**
         * Re-arms every profile's update alarm, as after a reboot or an app
         * update cleared them. [ScheduleReceiver] calls this.
         */
        fun scheduleAll(context: Context) {
            Global.launch {
                reset()

                val service = Intent(Intents.ACTION_PROFILE_SCHEDULE_UPDATES)
                    .setComponent(ProfileWorker::class.componentName)

                context.startForegroundServiceCompat(service)
            }
        }

        fun cancelNext(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)
        }

        fun schedule(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)

            intent.send(context, 0, null)
        }

        /**
         * Re-arms the update alarm after a failed attempt.
         *
         * The alarm the next update rides on was consumed by the attempt that
         * failed, so without this the profile silently stops renewing - the
         * auto-update equivalent of the tunnel coming down. A fixed fifteen
         * minutes back off from hammering a provider that is having a bad
         * hour; the subscription's own interval takes over again from the
         * next successful update.
         */
        fun scheduleRetry(context: Context, imported: Imported) {
            if (imported.interval < TimeUnit.MINUTES.toMillis(15))
                return

            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)

            context.getSystemService<AlarmManager>()
                ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15), intent)
        }

        fun scheduleNext(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)

            if (imported.interval < TimeUnit.MINUTES.toMillis(15))
                return

            val current = System.currentTimeMillis()
            val last = context.importedDir
                .resolve(imported.uuid.toString())
                .resolve("config.yaml")
                .lastModified()

            // file not existed
            if (last < 0)
                return

            val interval = (imported.interval - (current - last)).coerceAtLeast(0)

            // Wake-up and doze-tolerant: an ordinary set(RTC) is both inexact
            // and sleepable, and on a television that idles into doze the
            // next renewal fired whenever the system felt like batching it -
            // hours late, three profiles' worth of updates collapsing into
            // one box the moment all of them woke. exact variants would buy
            // precision that a fifteen-or-thirty minute renewal does not
            // need and that the platform sells for a special permission; an
            // allow-while-idle alarm reaches the maintenance window inside
            // nine minutes wherever the box drifted, and wakes it if it
            // must. The plugged-in television cannot object to the power.
            context.getSystemService<AlarmManager>()
                ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, current + interval, intent)
        }

        private suspend fun reset() = lock.withLock {
            initialized = false
        }

        private fun pendingIntentOf(context: Context, imported: Imported): PendingIntent {
            val intent = Intent(Intents.ACTION_PROFILE_REQUEST_UPDATE)
                .setComponent(ProfileReceiver::class.componentName)
                .setUUID(imported.uuid)

            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }
    }
}