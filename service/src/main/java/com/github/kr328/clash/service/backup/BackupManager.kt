package com.github.kr328.clash.service.backup

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Packages the app's configuration into a single zip and restores it again.
 *
 * The backup covers everything that defines a setup: the shared-preferences
 * files (service / ui / app / tips), the Room database that tracks profiles,
 * and the profile content directories. Restoring overwrites all of it, so a
 * restore is always a full replacement of the previous configuration.
 */
class BackupManager(private val context: Context) {
    private val backupsDir: File
        get() = context.filesDir.resolve("backups").apply { mkdirs() }

    /**
     * Everything a backup must carry, expressed as (zip entry name, source
     * file) pairs plus whole directories. Keeping this in one place means a
     * new preference file or profile directory only has to be added once.
     */
    private data class Source(val entryName: String, val file: File)

    private fun collectSources(): List<Source> {
        val sources = mutableListOf<Source>()

        val prefsDir = context.filesDir.parentFile?.resolve("shared_prefs")
        for (name in PREFERENCE_FILES) {
            val file = prefsDir?.resolve("$name.xml") ?: continue
            if (file.exists()) {
                sources += Source("shared_prefs/$name.xml", file)
            }
        }

        val dbDir = context.filesDir.parentFile?.resolve("databases")
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            val db = dbDir?.resolve("profiles$suffix") ?: continue
            if (db.exists()) {
                sources += Source("databases/profiles$suffix", db)
            }
        }

        for (dir in listOf(context.importedDir, context.pendingDir)) {
            if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(dir).path.replace('\\', '/')
                    sources += Source("files/${dir.name}/$rel", f)
                }
            }
        }

        return sources
    }

    /** Builds the backup zip and returns it. Runs off the main thread. */
    suspend fun createBackup(): File = withContext(Dispatchers.IO) {
        val out = backupsDir.resolve("clash-backup-${timestamp()}.zip")
        ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
            for (source in collectSources()) {
                if (!source.file.exists()) continue
                zip.putNextEntry(ZipEntry(source.entryName))
                FileInputStream(source.file).buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        Log.i("backup: wrote ${out.name} (${out.length()} bytes, ${countSources()} files)")
        out
    }

    /**
     * Restores a backup. The Room database files are only safe to copy while
     * the writer is idle, so the caller is responsible for stopping the clash
     * service first; this method does the file work and then asks the process
     * to re-read its stores.
     */
    suspend fun restoreBackup(file: File): Int = withContext(Dispatchers.IO) {
        var restored = 0
        ZipInputStream(FileInputStream(file).buffered()).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                entry?.let { e ->
                    val target = resolveTarget(e.name) ?: return@let
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).buffered().use { zip.copyTo(it) }
                    restored++
                }
                zip.closeEntry()
            }
        }
        Log.i("backup: restored $restored entries from ${file.name}")
        restored
    }

    /** Maps a zip entry name back onto an absolute path inside the app sandbox. */
    private fun resolveTarget(entryName: String): File? {
        val parts = entryName.replace('\\', '/').split('/')
        return when (parts.firstOrNull()) {
            "shared_prefs" -> {
                val dir = context.filesDir.parentFile?.resolve("shared_prefs")
                dir?.resolve(parts.drop(1).joinToString("/"))
            }
            "databases" -> context.filesDir.parentFile?.resolve("databases")?.resolve(parts.last())
            "files" -> {
                // files/imported/<...> or files/pending/<...>
                context.filesDir.resolve(parts.drop(1).joinToString("/"))
            }
            else -> null
        }
    }

    private fun countSources(): Int = collectSources().count { it.file.exists() }

    // --- Local file access (used by the SAF flow) --------------------------

    fun listLocalBackups(): List<File> =
        backupsDir.listFiles { f -> f.name.startsWith("clash-backup-") && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun deleteLocalBackup(file: File) {
        if (file.parentFile == backupsDir) file.delete()
    }

    /** Reads a backup picked through SAF into the private backups dir. */
    suspend fun importFromUri(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = "clash-backup-imported-${timestamp()}.zip"
        val out = backupsDir.resolve(name)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(out).buffered().use { input.copyTo(it) }
        }
        out
    }

    // --- WebDAV ------------------------------------------------------------

    data class WebdavTarget(val url: String, val username: String, val password: String, val path: String)

    val store: ServiceStore
        get() = ServiceStore(context)

    fun configuredWebdav(): WebdavTarget? {
        val s = store
        val base = s.webdavUrl.trim()
        if (base.isEmpty()) return null
        return WebdavTarget(
            url = base,
            username = s.webdavUsername.trim(),
            password = s.webdavPassword,
            path = s.webdavPath.trim().trim('/'),
        )
    }

    private fun authHeader(username: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    private fun openConnection(url: String, method: String, target: WebdavTarget): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        if (target.username.isNotEmpty()) {
            conn.setRequestProperty("Authorization", authHeader(target.username, target.password))
        }
        return conn
    }

    private fun join(base: String, path: String, file: String): String {
        val trimmedBase = base.trimEnd('/')
        return if (path.isEmpty()) "$trimmedBase/$file" else "$trimmedBase/$path/$file"
    }

    /** Uploads a backup zip to the configured WebDAV server. */
    suspend fun uploadToWebdav(file: File): String = withContext(Dispatchers.IO) {
        val target = configuredWebdav()
            ?: error("WebDAV is not configured")
        val url = join(target.url, target.path, file.name)
        val bytes = file.readBytes()

        val conn = openConnection(url, "PUT", target)
        conn.doOutput = true
        conn.outputStream.use { it.write(bytes) }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
                error("WebDAV PUT failed with HTTP $code: $body")
            }
        } finally {
            conn.disconnect()
        }
        url
    }

    /** Downloads a backup file from the WebDAV server into the local backups dir. */
    suspend fun downloadFromWebdav(remoteName: String): File = withContext(Dispatchers.IO) {
        val target = configuredWebdav()
            ?: error("WebDAV is not configured")
        val url = join(target.url, target.path, remoteName)

        val conn = openConnection(url, "GET", target)
        conn.setRequestProperty("Depth", "0")
        val out = backupsDir.resolve(remoteName)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("WebDAV GET failed with HTTP $code")
            }
            conn.inputStream.use { input ->
                FileOutputStream(out).buffered().use { input.copyTo(it) }
            }
        } finally {
            conn.disconnect()
        }
        out
    }

    /**
     * Lists the backup files on the WebDAV server by issuing a PROPFIND with
     * Depth 1 and parsing the returned multistatus XML. Only the simple
     * subset (href + getcontentlength) is understood, which every conforming
     * server returns.
     */
    suspend fun listWebdav(): List<WebdavFile> = withContext(Dispatchers.IO) {
        val target = configuredWebdav()
            ?: error("WebDAV is not configured")
        val dirUrl = if (target.path.isEmpty()) target.url.trimEnd('/') + "/"
            else target.url.trimEnd('/') + "/" + target.path.trim('/') + "/"

        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"

        val conn = openConnection(dirUrl, "PROPFIND", target)
        conn.setRequestProperty("Depth", "1")
        conn.setRequestProperty("Content-Type", "application/xml")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("WebDAV PROPFIND failed with HTTP $code")
            }
            parsePropfind(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun parsePropfind(xml: String): List<WebdavFile> {
        val files = mutableListOf<WebdavFile>()
        val responseRe = Regex("<d:response>.*?</d:response>", RegexOption.DOT_MATCHES_ALL)
        for (block in responseRe.findAll(xml)) {
            val text = block.value
            val href = Regex("<d:href>(.*?)</d:href>").find(text)?.groupValues?.get(1) ?: continue
            val length = Regex("<d:getcontentlength>(\\d+)</d:getcontentlength>").find(text)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val isCollection = "<collection/>" in text
            if (isCollection) continue
            val name = decodeHref(href).takeLast(1)
            if (name.isEmpty()) continue
            files += WebdavFile(name, length)
        }
        return files
    }

    private fun decodeHref(href: String): String {
        val decoded = try {
            java.net.URLDecoder.decode(href, "UTF-8")
        } catch (e: Exception) {
            href
        }
        return decoded.substringAfterLast('#')
    }

    data class WebdavFile(val name: String, val size: Long)

    companion object {
        private val PREFERENCE_FILES = listOf("service", "ui", "app", "tips")

        private fun timestamp(): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }
}
