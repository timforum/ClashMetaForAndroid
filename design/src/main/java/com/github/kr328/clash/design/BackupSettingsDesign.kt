package com.github.kr328.clash.design

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.ui.ToastDuration
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
        WebdavVerify, WebdavUpload, WebdavList, WebdavRestore,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private lateinit var indicator: ProgressPreference

    private lateinit var verifyRow: ClickablePreference

    fun showWorking(message: String) {
        indicator.view.visibility = View.VISIBLE
        indicator.title = context.getText(R.string.backup_status)
        indicator.setMessage(message)
    }

    fun showResult(message: String) {
        indicator.view.visibility = View.VISIBLE
        indicator.title = context.getText(R.string.backup_status)
        indicator.setMessage(message)

        // The status row sits at the top of a long page, so a result would be
        // off-screen exactly when the action was triggered down in the WebDAV
        // section. Mirror every outcome through a snackbar at the bottom.
        launch { showToast(message, ToastDuration.Long) }
    }

    fun hideIndicator() {
        indicator.view.visibility = View.GONE
    }

    private fun run(message: String, block: suspend () -> String?) {
        showWorking(message)

        // The design scope is Unconfined, so a coroutine that comes back from
        // withContext(IO) would keep executing on the IO thread — and touching
        // the views from there is what threw the screen away after an upload.
        // Pin the whole round trip (result included) to the main dispatcher.
        launch(Dispatchers.Main) {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            result.fold(
                onSuccess = {
                    Log.i("backup: ${message} finished")
                    showResult(it ?: context.getString(R.string.backup_status))
                },
                onFailure = {
                    Log.w("backup: $message failed", it)
                    showResult(context.getString(R.string.backup_failed, it.message ?: it.toString()))
                },
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

    /**
     * Checks the configured server without moving any file: a Depth 0 PROPFIND
     * at the base URL proves the address and credentials, and a second one at
     * the configured folder proves the remote path. A pass leaves a green tick
     * at the end of the row; starting a new test clears the old one.
     */
    fun verifyWebdav() {
        Log.i("backup: webdav verify requested")

        if (manager.configuredWebdav() == null) {
            showResult(context.getString(R.string.backup_webdav_not_configured))
            return
        }

        verifyRow.endIcon = null
        showWorking(context.getString(R.string.backup_webdav_verify))

        launch(Dispatchers.Main) {
            val result = runCatching { withContext(Dispatchers.IO) { manager.verifyWebdav() } }
            result.fold(
                onSuccess = {
                    Log.i("backup: webdav verify ok")
                    verifyRow.endIcon =
                        context.getDrawableCompat(R.drawable.ic_baseline_check_circle_green)
                    showResult(context.getString(R.string.backup_webdav_ok))
                },
                onFailure = {
                    Log.w("backup: webdav verify failed", it)
                    showResult(context.getString(R.string.backup_failed, it.message ?: it.toString()))
                },
            )
        }
    }

    fun webdavUpload() {
        Log.i("backup: webdav upload requested")
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

    /**
     * Browses what is on the server: a read-only listing with sizes. Restoring
     * is a separate action so that looking at the server can never replace the
     * live configuration by accident.
     */
    fun webdavList() {
        if (manager.configuredWebdav() == null) {
            showResult(context.getString(R.string.backup_webdav_not_configured))
            return
        }
        showWorking(context.getString(R.string.backup_webdav_list))

        launch(Dispatchers.Main) {
            val files = runCatching { withContext(Dispatchers.IO) { manager.listWebdav() } }
            files.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) {
                        showResult(context.getString(R.string.backup_records_empty))
                    } else {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.backup_webdav_list)
                            .setMessage(
                                list.take(50).joinToString("\n") { "${it.name} (${it.size} B)" }
                            )
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                },
                onFailure = {
                    Log.w("backup: webdav list failed", it)
                    showResult(context.getString(R.string.backup_failed, it.message ?: it.toString()))
                },
            )
        }
    }

    /**
     * Restores straight from the server: pick a file, confirm the replacement,
     * then download it and unpack it over the stores — the WebDAV counterpart
     * of the local import flow.
     */
    fun webdavRestore() {
        Log.i("backup: webdav restore requested")

        if (manager.configuredWebdav() == null) {
            showResult(context.getString(R.string.backup_webdav_not_configured))
            return
        }

        showWorking(context.getString(R.string.backup_webdav_restore))

        launch(Dispatchers.Main) {
            val files = runCatching { withContext(Dispatchers.IO) { manager.listWebdav() } }
            files.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) {
                        showResult(context.getString(R.string.backup_records_empty))
                    } else {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.backup_webdav_restore)
                            .setItems(list.map { it.name }.toTypedArray()) { _, which ->
                                val f = list[which]

                                confirmRestore {
                                    launch(Dispatchers.Main) {
                                        showWorking(f.name)

                                        val restored = runCatching {
                                            val file = withContext(Dispatchers.IO) {
                                                manager.downloadFromWebdav(f.name)
                                            }
                                            val count = manager.restoreBackup(file)

                                            withContext(Dispatchers.IO) {
                                                BackupRecordStore.add(
                                                    context,
                                                    BackupRecord(
                                                        time = System.currentTimeMillis(),
                                                        action = BackupRecord.ACTION_RESTORE,
                                                        target = BackupRecord.webdavTarget(f.name),
                                                        fileName = f.name,
                                                        sizeBytes = file.length(),
                                                        ok = true,
                                                    )
                                                )
                                            }

                                            count
                                        }

                                        restored.fold(
                                            onSuccess = {
                                                showResult(context.getString(R.string.backup_restored, it))
                                            },
                                            onFailure = {
                                                Log.w("backup: webdav restore failed", it)
                                                showResult(
                                                    context.getString(
                                                        R.string.backup_failed,
                                                        it.message ?: it.toString(),
                                                    )
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                },
                onFailure = {
                    Log.w("backup: webdav restore list failed", it)
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

            tips(R.string.backup_tips)

            category(R.string.backup_local)

            clickable(
                title = R.string.backup_export,
                icon = R.drawable.ic_baseline_save,
                summary = R.string.backup_export_summary,
            ) {
                clicked { requests.trySend(Request.ExportFile) }
            }

            clickable(
                title = R.string.backup_import,
                icon = R.drawable.ic_baseline_restore,
                summary = R.string.backup_import_summary,
            ) {
                clicked { requests.trySend(Request.ImportFile) }
            }

            clickable(
                title = R.string.backup_records,
                icon = R.drawable.ic_baseline_view_list,
                summary = R.string.backup_records_summary,
            ) {
                clicked { showLocalRecords() }
            }

            category(R.string.backup_webdav)

            editableText(
                value = srvStore::webdavUrl,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_url,
                icon = R.drawable.ic_baseline_domain,
                placeholder = R.string.backup_webdav_url_summary,
            )

            editableText(
                value = srvStore::webdavUsername,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_username,
                icon = R.drawable.ic_baseline_person,
                placeholder = R.string.backup_webdav_username_summary,
            )

            editableText(
                value = srvStore::webdavPassword,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_password,
                icon = R.drawable.ic_baseline_key,
                placeholder = R.string.backup_webdav_password_summary,
            )

            editableText(
                value = srvStore::webdavPath,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_path,
                icon = R.drawable.ic_outline_folder,
                placeholder = R.string.backup_webdav_path_summary,
            )

            verifyRow = clickable(
                title = R.string.backup_webdav_verify,
                icon = R.drawable.ic_outline_check_circle,
                summary = R.string.backup_webdav_verify_summary,
            ) {
                clicked { requests.trySend(Request.WebdavVerify) }
            }

            clickable(
                title = R.string.backup_webdav_upload,
                icon = R.drawable.ic_baseline_publish,
                summary = R.string.backup_webdav_upload_summary,
            ) {
                clicked { requests.trySend(Request.WebdavUpload) }
            }

            clickable(
                title = R.string.backup_webdav_list,
                icon = R.drawable.ic_outline_inbox,
                summary = R.string.backup_webdav_list_summary,
            ) {
                clicked { requests.trySend(Request.WebdavList) }
            }

            clickable(
                title = R.string.backup_webdav_restore,
                icon = R.drawable.ic_baseline_restore,
                summary = R.string.backup_webdav_restore_summary,
            ) {
                clicked { requests.trySend(Request.WebdavRestore) }
            }
        }

        screen.shrinkText()

        binding.content.addView(screen.root)
    }
}
