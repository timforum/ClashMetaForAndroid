package com.github.kr328.clash.service.backup

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One entry in the "view records" list: a backup (or restore) that happened,
 * where it went, and how big it was. Kept as a JSON array in a dedicated
 * preferences file so the list survives process restarts without a database.
 */
@Serializable
data class BackupRecord(
    val time: Long,
    val action: String,      // "backup" | "restore"
    val target: String,      // "local" | "webdav:<url>" | "import"
    val fileName: String,
    val sizeBytes: Long,
    val ok: Boolean,
    val detail: String = "",
) {
    val timeText: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))

    companion object {
        const val ACTION_BACKUP = "backup"
        const val ACTION_RESTORE = "restore"
        const val ACTION_IMPORT = "import"
        const val TARGET_LOCAL = "local"
        const val TARGET_IMPORT = "import"

        fun webdavTarget(url: String): String = "webdav:${url.trimEnd('/')}"
    }
}

object BackupRecordStore {
    private const val FILE_NAME = "backup_records"
    private const val KEY = "records"
    private const val MAX_RECORDS = 100
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(BackupRecord.serializer())

    fun list(context: Context): List<BackupRecord> {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            json.decodeFromString(listSerializer, raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, record: BackupRecord) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val current = list(context).toMutableList()
        current.add(0, record)
        // Cap the list so it cannot grow without bound across months of runs.
        val trimmed = current.take(MAX_RECORDS)
        prefs.edit().putString(KEY, json.encodeToString(listSerializer, trimmed)).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
