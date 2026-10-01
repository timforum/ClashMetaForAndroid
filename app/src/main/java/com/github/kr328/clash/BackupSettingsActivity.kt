package com.github.kr328.clash

import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.BackupSettingsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.backup.BackupManager
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupSettingsActivity : BaseActivity<BackupSettingsDesign>() {
    override suspend fun main() {
        val manager = BackupManager(this)
        val design = BackupSettingsDesign(this, ServiceStore(this), manager, this)

        setContentDesign(design)

        // A restore replaces the database the tunnel has been reading all
        // along, so the tunnel has to be down while it runs and back up when
        // it is done. Both ends of that live here: this is the only place that
        // can reach the service controls.
        design.prepareRestore = {
            val running = clashRunning

            if (running) {
                stopClashService()

                // A stop that never lands must not hold the screen hostage
                // forever; the restore proceeds either way and the bounded
                // wait only decides whether the tunnel gets restarted after.
                withTimeoutOrNull(CLASH_STOP_TIMEOUT_MS) {
                    while (clashRunning) delay(50L)
                }
            }

            running
        }

        design.resumeClash = {
            // It was running a moment ago, so the VPN consent has already been
            // granted and this cannot come back with a prompt to answer.
            runCatching {
                if (startClashService() != null) {
                    Log.w("backup: tunnel restart asked for VPN consent, skipped")
                }
            }.onFailure {
                Log.w("backup: tunnel restart failed", it)
            }
        }

        // Without a tunnel to restart no ClashStart/ServiceRecreated event
        // fires, and the WebDAV fields on this page read their store only once
        // at construction: left alone they would keep showing what was there
        // before the restore.
        design.refreshAfterRestore = {
            recreate()
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    // A restore in progress owns this screen. Stopping and
                    // restarting the tunnel both announce themselves here, and
                    // recreating the activity at either moment would cancel the
                    // coroutine that is performing the restore - which is how
                    // a restore ends with nothing to show for it.
                    val wouldRecreate = it == Event.ClashStart ||
                        it == Event.ClashStop ||
                        it == Event.ServiceRecreated

                    if (design.restoring && wouldRecreate) {
                        return@onReceive
                    }

                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    // One guard around every action: a throwing menu action
                    // used to unwind the whole select loop and take the screen
                    // down with it. Report through the page's status row and
                    // keep the activity alive instead.
                    runCatching {
                        when (it) {
                            BackupSettingsDesign.Request.ExportFile -> {
                                val name = "clash-backup-${
                                    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                }.zip"
                                val uri = startActivityForResult(
                                    ActivityResultContracts.CreateDocument("application/zip"),
                                    name,
                                )
                                if (uri != null) {
                                    design?.doExport(uri)
                                }
                            }
                            BackupSettingsDesign.Request.ImportFile -> {
                                val uri = startActivityForResult(
                                    ActivityResultContracts.OpenDocument(),
                                    arrayOf("application/zip", "application/octet-stream", "*/*"),
                                )
                                if (uri != null) {
                                    // A restore overwrites the live configuration, so
                                    // confirm before touching any file.
                                    design?.confirmRestore {
                                        design?.doImport(uri)
                                    }
                                }
                            }
                            BackupSettingsDesign.Request.ViewLocalRecords -> {
                                design?.showLocalRecords()
                            }
                            BackupSettingsDesign.Request.WebdavVerify -> {
                                design?.verifyWebdav()
                            }
                            BackupSettingsDesign.Request.WebdavUpload -> {
                                design?.webdavUpload()
                            }
                            BackupSettingsDesign.Request.WebdavList -> {
                                design?.webdavList()
                            }
                            BackupSettingsDesign.Request.WebdavRestore -> {
                                design?.webdavRestore()
                            }
                            else -> Unit
                        }
                    }.onFailure { e ->
                        if (e is CancellationException) throw e

                        Log.w("backup: request failed", e)

                        design?.showResult(
                            getString(R.string.backup_failed, e.message ?: e.toString())
                        )
                    }
                }
            }
        }
    }

    companion object {
        // How long to wait for the tunnel to confirm it stopped before the
        // restore goes ahead anyway. A stop that never lands only costs the
        // restart afterwards, it never blocks the screen.
        private const val CLASH_STOP_TIMEOUT_MS = 5_000L
    }
}
