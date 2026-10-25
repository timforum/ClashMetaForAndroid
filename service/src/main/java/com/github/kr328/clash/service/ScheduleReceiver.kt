package com.github.kr328.clash.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.log.Log

/**
 * Re-arms every alarm the app lives by after the system has cleared them.
 *
 * This is a receiver of its own rather than a branch in the other three
 * because those declare [BroadcastReceiver] permissions: a manifest receiver
 * with an `android:permission` only receives a broadcast from a sender that
 * holds that permission, and the system does not hold one of ours. Boot,
 * package replaced and clock broadcasts are all sent by the platform, so a
 * receiver that named our permission never heard any of them and every
 * schedule died at the first reboot or update, silently.
 *
 * Kept deliberately permission-free for the same reason. Nothing else can
 * send the actions below: they are protected broadcasts, which only the
 * system may use.
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                Log.i("schedule: ${intent.action}, re-arming the alarms")

                ProfileReceiver.scheduleAll(context)
                ProbTestReceiver.scheduleNext(context)
                SpeedTestReceiver.scheduleNext(context)
                SpeedTestReceiver.scheduleWatch(context)
                SpeedTestReceiver.scheduleVideo(context)
            }
        }
    }
}
