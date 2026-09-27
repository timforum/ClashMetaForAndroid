package com.github.kr328.clash.design

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.backup.BackupManager
import com.github.kr328.clash.service.backup.BackupRecord
import com.github.kr328.clash.service.backup.BackupRecordStore
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupSettingsDesign(
    context: Context,
    srvStore: ServiceStore,
    private val manager: BackupManager,
    private val activity: Activity,
) : Design<BackupSettingsDesign.Request>(context) {

    enum class Request {
        ExportFile, ImportFile, ViewLocalRecords,
        WebdavUpload, WebdavList,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private lateinit var indicator: ProgressPreference

    fun showWorking(message: String) {
        indicator.view.visibility = View.VISIBLE
        indicator.title = context.getText(R.string.backup_status)
        indicator.setMessage(message)
    }

    fun showResult(message: String) {
        indicator.view.visibility = View.VISIBLE
        indicator.title = context.getText(R.string.backup_status)
        indicator.setMessage(message)
    }

    fun hideIndicator() {
        indicator.view.visibility = View.GONE
    }

    private fun run(message: String, block: suspend () -> String?) {
        showWorking(message)
        launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            result.fold(
                onSuccess = { showResult(it ?: context.getString(R.string.backup_status)) },
                onFailure = { showResult(context.getString(R.string.backup_failed, it.message ?: it.toString())) },
            )
        }
    }

    /**
     * Runs a backup or restore that needs a user-chosen file. The activity
     * supplies the SAF uri; this only does the file work and records it.
     */
    fun doExport(uri: Uri) {
        run(context.getString(R.string.backup_export)) {
            val file = manager.createBackup()
            try {
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            } finally {
                file.delete()
            }
            BackupRecordStore.add(
                context,
                BackupRecord(
                    time = System.currentTimeMillis(),
                    action = BackupRecord.ACTION_BACKUP,
                    target = BackupRecord.TARGET_LOCAL,
                    fileName = uri.lastPathSegment ?: "backup.zip",
                    sizeBytes = file.length(),
                    ok = true,
                )
            )
            context.getString(R.string.backup_created, uri.lastPathSegment ?: "backup")
        }
    }

    fun doImport(uri: Uri) {
        run(context.getString(R.string.backup_import)) {
            val file = manager.importFromUri(uri)
            val count = manager.restoreBackup(file)
            BackupRecordStore.add(
                context,
                BackupRecord(
                    time = System.currentTimeMillis(),
                    action = BackupRecord.ACTION_RESTORE,
                    target = BackupRecord.TARGET_IMPORT,
                    fileName = file.name,
                    sizeBytes = file.length(),
                    ok = true,
                )
            )
            context.getString(R.string.backup_restored, count)
        }
    }

    fun confirmRestore(onConfirmed: () -> Unit) {
        AlertDialog.Builder(context)
            .setMessage(R.string.backup_confirm_restore)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirmed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showLocalRecords() {
        val records = BackupRecordStore.list(context)
        val text = if (records.isEmpty()) {
            context.getString(R.string.backup_records_empty)
        } else {
            records.take(50).joinToString("\n") { r ->
                val action = when (r.action) {
                    BackupRecord.ACTION_BACKUP -> "backup"
                    BackupRecord.ACTION_RESTORE -> "restore"
                    else -> r.action
                }
                "${r.timeText}  $action  ${r.target}  ${r.fileName}  (${r.sizeBytes} B)"
            }
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.backup_records)
            .setMessage(text)
            .setNeutralButton("Clear") { _, _ -> BackupRecordStore.clear(context) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun webdavUpload() {
        if (manager.configuredWebdav() == null) {
            showResult(context.getString(R.string.backup_webdav_not_configured))
            return
        }
        run(context.getString(R.string.backup_webdav_upload)) {
            val file = manager.createBackup()
            try {
                val url = manager.uploadToWebdav(file)
                BackupRecordStore.add(
                    context,
                    BackupRecord(
                        time = System.currentTimeMillis(),
                        action = BackupRecord.ACTION_BACKUP,
                        target = BackupRecord.webdavTarget(url),
                        fileName = file.name,
                        sizeBytes = file.length(),
                        ok = true,
                    )
                )
                context.getString(R.string.backup_uploaded, file.name)
            } finally {
                file.delete()
            }
        }
    }

    fun webdavList() {
        if (manager.configuredWebdav() == null) {
            showResult(context.getString(R.string.backup_webdav_not_configured))
            return
        }
        showWorking(context.getString(R.string.backup_webdav_list))
        launch {
            val files = runCatching { withContext(Dispatchers.IO) { manager.listWebdav() } }
            files.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) {
                        showResult(context.getString(R.string.backup_records_empty))
                    } else {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.backup_webdav_list)
                            .setItems(
                                list.map { it.name }.toTypedArray()
                            ) { _, which ->
                                val f = list[which]
                                launch {
                                    showWorking(f.name)
                                    runCatching {
                                        withContext(Dispatchers.IO) { manager.downloadFromWebdav(f.name) }
                                    }.fold(
                                        onSuccess = {
                                            showResult(context.getString(R.string.backup_restored, 1))
                                        },
                                        onFailure = {
                                            showResult(context.getString(R.string.backup_failed, it.message ?: it.toString()))
                                        }
                                    )
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                },
                onFailure = {
                    showResult(context.getString(R.string.backup_failed, it.message ?: it.toString()))
                },
            )
        }
    }

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            indicator = progress(R.string.backup_status) {
                view.visibility = View.GONE
            }

            category(R.string.backup_local)

            clickable(
                title = R.string.backup_export,
                summary = R.string.backup_export_summary,
            ) {
                clicked { requests.trySend(Request.ExportFile) }
            }

            clickable(
                title = R.string.backup_import,
                summary = R.string.backup_import_summary,
            ) {
                clicked { requests.trySend(Request.ImportFile) }
            }

            clickable(
                title = R.string.backup_records,
                summary = R.string.backup_records_summary,
            ) {
                clicked { showLocalRecords() }
            }

            category(R.string.backup_webdav)

            editableText(
                value = srvStore::webdavUrl,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_url,
                placeholder = R.string.backup_webdav_url_summary,
            )

            editableText(
                value = srvStore::webdavUsername,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_username,
                placeholder = R.string.backup_webdav_username_summary,
            )

            editableText(
                value = srvStore::webdavPassword,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_password,
                placeholder = R.string.backup_webdav_password_summary,
            )

            editableText(
                value = srvStore::webdavPath,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_path,
                placeholder = R.string.backup_webdav_path_summary,
            )

            clickable(
                title = R.string.backup_webdav_upload,
                summary = R.string.backup_webdav_upload_summary,
            ) {
                clicked { requests.trySend(Request.WebdavUpload) }
            }

            clickable(
                title = R.string.backup_webdav_list,
                summary = R.string.backup_webdav_list_summary,
            ) {
                clicked { requests.trySend(Request.WebdavList) }
            }
        }

        binding.content.addView(screen.root)
    }
}
