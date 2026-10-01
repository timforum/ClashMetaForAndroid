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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
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

    /**
     * True while a restore owns the screen.
     *
     * A restore may stop the tunnel, which makes the activity recreate itself.
     * Doing that mid-restore would tear down the coroutine performing it, so
     * the activity reads this flag and holds off until the result is out.
     */
    @Volatile
    var restoring: Boolean = false

    /**
     * Stops the tunnel for the duration of a restore and reports whether it
     * was running. Supplied by the activity, which is the only place that can
     * reach the service controls.
     */
    var prepareRestore: (suspend () -> Boolean)? = null

    /** Brings the tunnel back once the result has been on screen long enough. */
    var resumeClash: (() -> Unit)? = null

    /**
     * Rebuilds this screen once a restore has landed.
     *
     * Every row here reads its store once, at construction, so without a
     * rebuild the WebDAV fields keep showing the values the restore has just
     * replaced - which reads as a restore that did nothing. A running tunnel
     * supplies this on its own by announcing ClashStart; this covers the case
     * where there was no tunnel to restart.
     */
    var refreshAfterRestore: (() -> Unit)? = null

    private var bottomBar: Snackbar? = null

    fun showWorking(message: String) {
        indicator.view.visibility = View.VISIBLE
        indicator.title = context.getText(R.string.backup_status)
        indicator.setMessage(message)

        // The status row sits at the top of a long page, and every WebDAV
        // action is started from the bottom of it: on its own the screen looks
        // dead for as long as the round trip takes, which for a download can
        // be minutes. Say so where the finger just was.
        showBottom(message, ToastDuration.Indefinite)
    }

    fun showResult(message: String) {
        indicator.view.visibility = View.VISIBLE
        indicator.title = context.getText(R.string.backup_status)
        indicator.setMessage(message)

        // Mirrors the outcome at the bottom of the screen for the same reason.
        showBottom(message, ToastDuration.Long)
    }

    /**
     * Replaces whatever is showing at the bottom of the screen.
     *
     * The working bar has to be dismissed by hand: one with an indefinite
     * duration never leaves on its own, and the next bar would queue behind it
     * instead of appearing.
     */
    private fun showBottom(message: CharSequence, duration: ToastDuration) {
        launch(Dispatchers.Main) {
            bottomBar?.dismiss()

            val bar = Snackbar.make(
                root,
                message,
                when (duration) {
                    ToastDuration.Short -> Snackbar.LENGTH_SHORT
                    ToastDuration.Long -> Snackbar.LENGTH_LONG
                    ToastDuration.Indefinite -> Snackbar.LENGTH_INDEFINITE
                },
            )

            bottomBar = bar
            bar.show()
        }
    }

    fun hideIndicator() {
        bottomBar?.dismiss()
        bottomBar = null

        indicator.view.visibility = View.GONE
    }

    private fun run(message: String, block: suspend () -> String?) {
        showWorking(message)

        // The design scope is Unconfined, so a coroutine that comes back from
        // withContext(IO) would keep executing on the IO thread 鈥?and touching
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
     * Runs a restore.
     *
     * On top of the file work this owns the screen for as long as it needs:
     * the tunnel is stopped first so nothing is holding the profile database
     * open underneath the replacement, the activity stops recreating itself
     * while [restoring] is set - a recreate would tear down this coroutine -
     * and the tunnel only comes back after the result has been on screen for
     * a moment, because restarting it is what makes the activity refresh and
     * take the message with it.
     */
    private fun runRestore(message: String, block: suspend () -> Int) {
        showWorking(message)

        launch(Dispatchers.Main) {
            var resume = false

            val result = runCatching {
                restoring = true
                resume = runCatching { prepareRestore?.invoke() ?: false }
                    .onFailure { Log.w("backup: tunnel was not stopped cleanly", it) }
                    .getOrDefault(false)

                withContext(Dispatchers.IO) { block() }
            }

            result.fold(
                onSuccess = { count ->
                    Log.i("backup: restore finished ($count entries)")
                    showResult(context.getString(R.string.backup_restored, count))
                },
                onFailure = {
                    Log.w("backup: restore failed", it)
                    showResult(
                        context.getString(R.string.backup_failed, it.message ?: it.toString())
                    )
                },
            )

            delay(RESTORE_RESULT_MS)

            restoring = false

            if (resume) {
                runCatching { resumeClash?.invoke() }
                    .onFailure { Log.w("backup: tunnel was not restarted", it) }
            } else {
                runCatching { refreshAfterRestore?.invoke() }
                    .onFailure { Log.w("backup: screen was not refreshed", it) }
            }
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
        runRestore(context.getString(R.string.backup_import)) {
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
            count
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
     * then download it and unpack it over the stores 鈥?the WebDAV counterpart
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
                        // The list dialog is dismissed before the confirm
                        // dialog takes its place. Leaving it up would put a
                        // second window over the first and then sit there
                        // covering whatever the restore has to say.
                        var listDialog: AlertDialog? = null

                        listDialog = AlertDialog.Builder(context)
                            .setTitle(R.string.backup_webdav_restore)
                            .setItems(list.map { it.name }.toTypedArray()) { _, which ->
                                val f = list[which]

                                listDialog?.dismiss()

                                confirmRestore {
                                    runRestore(f.name) {
                                        val file = manager.downloadFromWebdav(f.name)
                                        val count = manager.restoreBackup(file)

                                        BackupRecordStore.add(
                                            context,
                                            BackupRecord(
                                                time = System.currentTimeMillis(),
                                                action = BackupRecord.ACTION_RESTORE,
                                                target = BackupRecord.webdavTarget(f.name),
                                                fileName = f.name,
                                                sizeBytes = file.length(),
                                                ok = true,
                                            ),
                                        )

                                        count
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
            indicator = progress(R.string.backup_status, card = true) {
                view.visibility = View.GONE
            }

            tips(R.string.backup_tips, card = true)

            category(R.string.backup_local, card = true)

            clickable(
                title = R.string.backup_export,
                icon = R.drawable.ic_baseline_save,
                summary = R.string.backup_export_summary,
                card = true,
            ) {
                clicked { requests.trySend(Request.ExportFile) }
            }

            clickable(
                title = R.string.backup_import,
                icon = R.drawable.ic_baseline_restore,
                summary = R.string.backup_import_summary,
                card = true,
            ) {
                clicked { requests.trySend(Request.ImportFile) }
            }

            clickable(
                title = R.string.backup_records,
                icon = R.drawable.ic_baseline_view_list,
                summary = R.string.backup_records_summary,
                card = true,
            ) {
                clicked { showLocalRecords() }
            }

            category(R.string.backup_webdav, card = true)

            editableText(
                value = srvStore::webdavUrl,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_url,
                icon = R.drawable.ic_baseline_domain,
                placeholder = R.string.backup_webdav_url_summary,
                card = true,
            )

            editableText(
                value = srvStore::webdavUsername,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_username,
                icon = R.drawable.ic_baseline_person,
                placeholder = R.string.backup_webdav_username_summary,
                card = true,
            )

            editableText(
                value = srvStore::webdavPassword,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_password,
                icon = R.drawable.ic_baseline_key,
                placeholder = R.string.backup_webdav_password_summary,
                card = true,
            ) {
                // The row must never show the password itself; the editor still
                // opens on the stored value so it can be changed, not retyped.
                password = true
            }

            editableText(
                value = srvStore::webdavPath,
                adapter = NullableTextAdapter.Text,
                title = R.string.backup_webdav_path,
                icon = R.drawable.ic_outline_folder,
                placeholder = R.string.backup_webdav_path_summary,
                card = true,
            )

            verifyRow = clickable(
                title = R.string.backup_webdav_verify,
                icon = R.drawable.ic_outline_check_circle,
                summary = R.string.backup_webdav_verify_summary,
                card = true,
            ) {
                clicked { requests.trySend(Request.WebdavVerify) }
            }

            clickable(
                title = R.string.backup_webdav_upload,
                icon = R.drawable.ic_baseline_publish,
                summary = R.string.backup_webdav_upload_summary,
                card = true,
            ) {
                clicked { requests.trySend(Request.WebdavUpload) }
            }

            clickable(
                title = R.string.backup_webdav_list,
                icon = R.drawable.ic_outline_inbox,
                summary = R.string.backup_webdav_list_summary,
                card = true,
            ) {
                clicked { requests.trySend(Request.WebdavList) }
            }

            clickable(
                title = R.string.backup_webdav_restore,
                icon = R.drawable.ic_baseline_restore,
                summary = R.string.backup_webdav_restore_summary,
                card = true,
            ) {
                clicked { requests.trySend(Request.WebdavRestore) }
            }
        }

        screen.shrinkText()

        binding.content.addView(screen.root)
    }
}

// Long enough to read the result. The tunnel restart that follows recreates
// the activity, which takes the snackbar with it, so the message has to have
// had its time before that happens.
private const val RESTORE_RESULT_MS = 3_000L
