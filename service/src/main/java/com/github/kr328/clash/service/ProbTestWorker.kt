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
import com.github.kr328.clash.service.probtest.ProbTestPipeline
import com.github.kr328.clash.service.probtest.ProbTestProgress
import com.github.kr328.clash.service.probtest.Stage
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendProbTestFinished
import com.github.kr328.clash.service.util.sendProbTestProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Runs one candidate screening round in the background.
 *
 * The round lasts as long as three spaced rounds of probing, so the service has
 * to be a foreground service on every supported Android version; the alarm is
 * re-armed once the round finishes, including when it fails.
 */
class ProbTestWorker : BaseService() {
    private val jobs = mutableListOf<Job>()

    override fun onCreate() {
        super.onCreate()

        createChannels()
        foreground()

        // A round is about to run, so whatever the previous one left on screen
        // is now stale. Drop it immediately rather than letting the settings
        // screen repaint a finished round's result for the whole duration of
        // this one.
        ServiceStore(applicationContext).probtestLastResult = ""

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

        if (intent?.action == Intents.ACTION_PROBTEST_REQUEST) {
            jobs.add(launch { run() })
        }

        return START_NOT_STICKY
    }

    private suspend fun run() {
        var didPublish = false
        var summary = getString(R.string.probtest_failure)

        try {
            val pipeline = ProbTestPipeline(this)

            // Publish a first snapshot before anything slow happens. Without
            // it a screen has no bar to show for the whole time the first
            // subscription is downloading, which looks exactly like a hang.
            update(ProbTestProgress(Stage.DOWNLOADING))

            val outcome = pipeline.run { progress ->
                update(progress)
            }

            didPublish = outcome.published
            summary = outcome.summary

            if (didPublish) {
                published(summary)
            } else {
                Log.w("probtest: round ended without publishing: $summary")

                failed(summary)
            }
        } catch (e: Exception) {
            summary = e.message ?: "Unknown error"

            Log.w("probtest: round failed", e)

            failed(summary)
        } finally {
            clearState()

            // Carried through the finally so a failure of any kind still
            // reports a reason on screen instead of just hiding the bar.
            sendProbTestFinished(didPublish, summary)

            // Left behind so a screen opened between rounds shows this result
            // rather than a stale in-flight snapshot. The "|" separator is
            // safe: summary is a fixed app string and never contains it.
            ServiceStore(applicationContext).probtestLastResult = "$didPublish|$summary"

            ProbTestReceiver.scheduleNext(this)
        }
    }

    private fun clearState() {
        // The notification itself is left alone: onDestroy's stopForeground
        // removes it shortly after, and repainting it here would show a fresh
        // spinner for a round that has already finished.
        ServiceStore(applicationContext).probtestState = ""
    }

    private fun createChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    SERVICE_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.probtest_worker)).build(),
                NotificationChannelCompat.Builder(
                    RESULT_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(R.string.probtest_result)).build()
            )
        )
    }

    private fun foregroundNotification(): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.probtest_worker))
            .setContentText(getString(R.string.probtest_running))
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun foreground() {
        startForeground(R.id.nf_probtest_worker, foregroundNotification())
    }

    /**
     * Repaints the ongoing notification and mirrors the same state into the UI
     * process. A round spans several minutes of mostly waiting, so a bar that
     * at least names the phase is what tells the user the run is alive and not
     * stuck.
     */
    private fun update(progress: ProbTestProgress) {
        val text = describe(progress)

        // The denominator depends on the phase: subscriptions while
        // downloading, rounds while probing, and nothing to count towards
        // while publishing, which is why that one stays indeterminate.
        val (current, total) = when (progress.stage) {
            Stage.DOWNLOADING -> progress.done to progress.total
            Stage.SCREENING -> progress.round to progress.rounds
            Stage.PUBLISHING -> 0 to 0
        }

        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.probtest_worker))
            .setContentText(text)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, current, total <= 0)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(R.id.nf_probtest_worker, notification)

        // Persisted as well as broadcast, so a screen that opens after this
        // point still has something to draw.
        ServiceStore(applicationContext).probtestState = ProbTestProgress.encode(progress)

        sendProbTestProgress(
            stage = progress.stage.name,
            done = progress.done,
            total = progress.total,
            round = progress.round,
            rounds = progress.rounds,
            passed = progress.passed,
            failed = progress.failed,
        )
    }

    private fun describe(progress: ProbTestProgress): String = when (progress.stage) {
        Stage.DOWNLOADING -> if (progress.total > 0) {
            getString(R.string.probtest_progress_downloading, progress.done, progress.total)
        } else {
            getString(R.string.probtest_downloading)
        }
        Stage.SCREENING -> if (progress.rounds > 0) {
            getString(
                R.string.probtest_progress_screening,
                progress.round,
                progress.rounds,
                progress.passed,
                progress.failed,
            )
        } else {
            getString(R.string.probtest_screening)
        }
        Stage.PUBLISHING -> getString(R.string.probtest_publishing)
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

    private fun published(summary: String) {
        val notification = resultBuilder()
            .setContentTitle(getString(R.string.probtest_success))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .build()

        notifyOnce(notification)
    }

    private fun failed(reason: String) {
        val notification = resultBuilder()
            .setContentTitle(getString(R.string.probtest_failure))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .build()

        notifyOnce(notification)
    }

    private fun notifyOnce(notification: Notification) {
        val id = UndefinedIds.next()

        launch(NonCancellable) {
            NotificationManagerCompat.from(applicationContext)
                .notify(id, notification)
        }
    }

    companion object {
        private const val SERVICE_CHANNEL = "probtest_service_channel"
        private const val RESULT_CHANNEL = "probtest_result_channel"
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }
}
