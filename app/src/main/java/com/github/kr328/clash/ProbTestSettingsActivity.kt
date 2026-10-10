package com.github.kr328.clash

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.ProbTestSettingsDesign
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.service.ProbTestWorker
import com.github.kr328.clash.service.probtest.ExtraSubFile
import com.github.kr328.clash.service.probtest.ProbTestProgress
import com.github.kr328.clash.service.probtest.Stage
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class ProbTestSettingsActivity : BaseActivity<ProbTestSettingsDesign>() {
    override suspend fun main() {
        val design = ProbTestSettingsDesign(
            this,
            ServiceStore(this),
        )

        setContentDesign(design)

        // A file picked before this build existed was stored as a uri and never
        // copied, so the worker has nothing to read. Take the copy here, while
        // the grant that opened it may still be alive. It usually is not - the
        // screen has been closed since - and failing is expected then; the row
        // is left showing the file so it can be picked again.
        withContext(Dispatchers.IO) {
            val uri = ServiceStore(this@ProbTestSettingsActivity).probtestExtraSubFile

            if (uri.isNotBlank() && !ExtraSubFile.file(this@ProbTestSettingsActivity).isFile) {
                val copied = ExtraSubFile.copy(this@ProbTestSettingsActivity, Uri.parse(uri))

                Log.i("probtest: backfilled the picked file to ${copied ?: "nothing"}")
            }
        }

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
                        ProbTestSettingsDesign.Request.OpenIntervals ->
                            startActivity(ProbTestIntervalsActivity::class.intent)
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
                            // The content is copied here, where the picker's grant
                            // still reaches it: that grant belongs to this process
                            // and to nothing else, so the worker could not open the
                            // file later even with the persistable flag taken. The
                            // uri is still stored so the row can name what was
                            // picked, but the round reads the copy.
                            val uri = startActivityForResult(
                                ActivityResultContracts.OpenDocument(),
                                arrayOf("text/*", "application/octet-stream", "*/*"),
                            )
                            if (uri != null) {
                                runCatching {
                                    contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                }.onFailure { e ->
                                    // Not fatal on its own: the copy below does not
                                    // need the grant to outlive this screen.
                                    Log.w("probtest: could not persist extra sub file uri $uri", e)
                                }

                                val copied = ExtraSubFile.copy(this@ProbTestSettingsActivity, uri)
                                if (copied == null) {
                                    Log.w("probtest: could not read the picked file $uri")
                                } else {
                                    Log.i("probtest: copied the picked file to $copied")
                                }

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
