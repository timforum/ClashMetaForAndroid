package com.github.kr328.clash

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.ProbTestSettingsDesign
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.service.ProbTestWorker
import com.github.kr328.clash.service.probtest.ProbTestProgress
import com.github.kr328.clash.service.probtest.Stage
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class ProbTestSettingsActivity : BaseActivity<ProbTestSettingsDesign>() {
    override suspend fun main() {
        val design = ProbTestSettingsDesign(
            this,
            ServiceStore(this),
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProbTestSettingsDesign.Request.RunNow -> {
                            // Logged before anything else: if the bar never
                            // appears but this line does, the tap landed and
                            // the failure is downstream; if neither does, the
                            // click never reached the channel at all.
                            Log.i("probtest: run requested")

                            // Show something straight away. The worker announces
                            // itself too, but only once the service has started,
                            // and until then the tap would look like it did
                            // nothing.
                            design?.showProgress(ProbTestProgress(Stage.DOWNLOADING))

                            try {
                                startForegroundServiceCompat(
                                    Intent(this@ProbTestSettingsActivity, ProbTestWorker::class.java)
                                        .setAction(Intents.ACTION_PROBTEST_REQUEST)
                                )

                                Log.i("probtest: worker start requested")
                            } catch (e: Exception) {
                                Log.w("probtest: worker could not start", e)

                                design?.showResult(false, e.message ?: "Worker could not start")
                            }
                        }
                        ProbTestSettingsDesign.Request.PickExtraSubFile -> {
                            // A plain text file of subscription URLs, one per line.
                            // The content uri is stored as a string so the worker
                            // process can read the file back across processes.
                            val uri = startActivityForResult(
                                ActivityResultContracts.OpenDocument(),
                                arrayOf("text/*", "application/octet-stream", "*/*"),
                            )
                            if (uri != null) {
                                ServiceStore(this@ProbTestSettingsActivity).probtestExtraSubFile = uri.toString()
                                design?.let {
                                    // Rebuild the screen so the row shows the new file name
                                    // and the stored value is re-read from the store.
                                    it.updateExtraSubFile(uri.toString())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onProbTestProgress(progress: Broadcasts.ProbTestState) {
        design?.showProgress(
            ProbTestProgress(
                // An unrecognised stage still means a round is running, so it
                // must not be turned into a crash here.
                stage = runCatching { Stage.valueOf(progress.stage) }.getOrDefault(Stage.SCREENING),
                done = progress.done,
                total = progress.total,
                round = progress.round,
                rounds = progress.rounds,
                passed = progress.passed,
                failed = progress.failed,
            )
        )
    }

    override fun onProbTestFinished(published: Boolean, summary: String) {
        Log.i("probtest: round finished (published=$published): $summary")

        // The bar is replaced by the outcome rather than simply dropped. A
        // round that fails during the first subscription download lasts only a
        // moment, and hiding the bar without leaving anything behind is what
        // made a working button look dead.
        design?.showResult(published, summary)
    }
}
