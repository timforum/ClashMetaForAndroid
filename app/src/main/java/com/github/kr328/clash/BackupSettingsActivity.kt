package com.github.kr328.clash

import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.BackupSettingsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.backup.BackupManager
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupSettingsActivity : BaseActivity<BackupSettingsDesign>() {
    override suspend fun main() {
        val manager = BackupManager(this)
        val design = BackupSettingsDesign(this, ServiceStore(this), manager, this)

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
}
