package com.github.kr328.clash.service.backup

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
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

    private val davClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            // Redirects are carried by hand below so the method and body of
            // PUT/PROPFIND survive them unchanged.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * Sends one WebDAV request and carries it across up to five redirects.
     *
     * OkHttp is used instead of HttpURLConnection because the platform class
     * refuses PROPFIND and MKCOL outright with "Expected one of [OPTIONS, GET,
     * HEAD, ...]" — the request would die before ever reaching the server.
     * Redirects are followed manually because an automatic follow can downgrade
     * a redirected PUT to GET, which answers 200 without writing anything:
     * that is exactly how an upload can report success while the server keeps
     * no file. The returned response must be closed by the caller.
     */
    private fun execute(
        url: String,
        method: String,
        target: WebdavTarget,
        body: ByteArray? = null,
        depth: String? = null,
    ): DavResponse {
        var current = url
        var redirects = 0

        while (true) {
            val mediaType = when {
                method == "PROPFIND" -> "application/xml".toMediaType()
                body != null -> "application/octet-stream".toMediaType()
                else -> null
            }

            val request = Request.Builder()
                .url(current)
                .method(method, body?.toRequestBody(mediaType))
                .apply {
                    if (target.username.isNotEmpty()) {
                        header("Authorization", authHeader(target.username, target.password))
                    }
                    if (depth != null) {
                        header("Depth", depth)
                    }
                    if (method == "PROPFIND") {
                        header("Content-Type", "application/xml")
                    }
                }
                .build()

            val response = davClient.newCall(request).execute()

            if (response.code in 301..308 && response.code != 304 && response.code != 305) {
                val location = response.header("Location")
                val code = response.code
                response.close()

                if (location == null || ++redirects > 5) {
                    error("WebDAV $method failed with HTTP $code at $current")
                }

                current = current.toHttpUrl().resolve(location)?.toString()
                    ?: error("WebDAV $method redirected to invalid location: $location")
                continue
            }

            return DavResponse(current, response)
        }
    }

    /**
     * The response of one exchange together with the URL it finally reached.
     *
     * Redirects are carried by hand, so the last hop is generally not the URL
     * we asked for: a server that moves `/dir/file` to `/dir/file/` or from
     * http to https stores the file at the new address only. Anything that
     * follows up on a request — the PROPFIND that proves an upload landed —
     * has to ask the server about that final address, not the original one.
     */
    private class DavResponse(val url: String, private val delegate: Response) : Closeable {
        val code: Int get() = delegate.code

        val body: ResponseBody? get() = delegate.body

        override fun close() = delegate.close()
    }

    private fun join(base: String, path: String, file: String): String {
        val trimmedBase = base.trimEnd('/')
        return if (path.isEmpty()) "$trimmedBase/$file" else "$trimmedBase/$path/$file"
    }

    /**
     * Uploads a backup zip to the configured WebDAV server and then asks the
     * server what it actually stores, so a success message always means the
     * file is really there.
     */
    suspend fun uploadToWebdav(file: File): String = withContext(Dispatchers.IO) {
        val target = configuredWebdav()
            ?: error("WebDAV is not configured")
        val url = join(target.url, target.path, file.name)
        val bytes = file.readBytes()

        val storedAt = putFile(url, target, bytes)

        runCatching { propfindDepth0(storedAt, target) }.onFailure {
            error("PUT succeeded but ${file.name} is not visible on the server (${it.message})")
        }

        storedAt
    }

    /**
     * Sends the bytes, re-issuing the request after every redirect and
     * creating the remote folder on a first 404/409 before trying again.
     * Returns the address the server finally kept the file at.
     */
    private fun putFile(url: String, target: WebdavTarget, bytes: ByteArray): String {
        var folderCreated = false

        while (true) {
            val response = execute(url, "PUT", target, body = bytes)
            val code = response.code

            if (code in 200..299) {
                val storedAt = response.url
                response.close()
                return storedAt
            }

            val detail = response.body?.string()?.take(300) ?: ""
            response.close()

            // A folder typed by hand, or a freshly created remote, answers
            // 404/409 to the PUT: make the collection once and retry.
            if ((code == 404 || code == 409) && !folderCreated && target.path.isNotEmpty()) {
                folderCreated = true
                mkcol(join(target.url, target.path, ""), target)
                continue
            }

            error("WebDAV PUT failed with HTTP $code: $detail")
        }
    }

    /** Best-effort folder creation; a real failure resurfaces on the retried PUT. */
    private fun mkcol(url: String, target: WebdavTarget) {
        try {
            execute(url, "MKCOL", target).close()
        } catch (e: Exception) {
            Log.w("backup: mkcol failed", e)
        }
    }

    /** Downloads a backup file from the WebDAV server into the local backups dir. */
    suspend fun downloadFromWebdav(remoteName: String): File = withContext(Dispatchers.IO) {
        val target = configuredWebdav()
            ?: error("WebDAV is not configured")
        val url = join(target.url, target.path, remoteName)

        val out = backupsDir.resolve(remoteName)
        execute(url, "GET", target).use { response ->
            if (response.code !in 200..299) {
                error("WebDAV GET failed with HTTP ${response.code}")
            }
            val input = response.body?.byteStream()
                ?: error("WebDAV GET returned no body")
            input.use {
                FileOutputStream(out).buffered().use { file -> it.copyTo(file) }
            }
        }
        out
    }

    /**
     * Verifies the configured server without moving a file: a Depth 0 PROPFIND
     * at the base URL proves the address and credentials, and a second one at
     * the configured folder proves the remote path. Runs off the main thread.
     */
    suspend fun verifyWebdav() = withContext(Dispatchers.IO) {
        val target = configuredWebdav()
            ?: error("WebDAV is not configured")
        val base = target.url.trimEnd('/') + "/"

        propfindDepth0(base, target)

        if (target.path.isNotEmpty()) {
            propfindDepth0(base + target.path.trim('/') + "/", target)
        }
    }

    /**
     * Sends a Depth 0 PROPFIND and fails unless the server answers 2xx.
     *
     * The very first check after a write is a race the server usually loses:
     * a backend that commits uploads asynchronously answers 404 for a moment,
     * and a load balancer can route the follow-up to a node that has not seen
     * the PUT yet. Both clear on their own, so the request is repeated a few
     * times with a growing pause instead of failing a backup that did land.
     * Answers that will not change on a retry — bad credentials, a server that
     * does not speak WebDAV — are reported straight away.
     */
    private fun propfindDepth0(url: String, target: WebdavTarget) {
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:resourcetype/></d:prop></d:propfind>"

        var lastError = "the request was never made"

        for (attempt in 1..PROPFIND_ATTEMPTS) {
            if (attempt > 1) {
                val waitMs = PROPFIND_RETRY_DELAY_MS * (attempt - 1)
                Log.i("backup: PROPFIND retry $attempt/$PROPFIND_ATTEMPTS in $waitMs ms")
                Thread.sleep(waitMs)
            }

            val failure: String? = try {
                execute(url, "PROPFIND", target, body = body.toByteArray(), depth = "0").use { response ->
                    if (response.code in 200..299) {
                        null
                    } else {
                        val detail = response.body?.string()?.take(300) ?: ""
                        "HTTP ${response.code}: $detail"
                    }
                }
            } catch (e: Exception) {
                e.message ?: e.toString()
            }

            if (failure == null) return

            lastError = failure
            if (PERMANENT_PROPFIND_FAILURE.containsMatchIn(failure)) break
        }

        error("WebDAV PROPFIND failed with $lastError")
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

        execute(dirUrl, "PROPFIND", target, body = body.toByteArray(), depth = "1")
            .use { response ->
                if (response.code !in 200..299) {
                    error("WebDAV PROPFIND failed with HTTP ${response.code}")
                }
                parsePropfind(response.body?.string() ?: "")
            }
    }

    private fun parsePropfind(xml: String): List<WebdavFile> {
        val files = mutableListOf<WebdavFile>()
        // Inline (?i)(?s): servers differ in namespace prefix case (d: vs D:)
        // and pretty-print the multistatus across lines. Kotlin's Regex accepts
        // only one constructor option, so the flags ride along in the pattern.
        val responseRe = Regex("(?is)<d:response>.*?</d:response>")
        for (block in responseRe.findAll(xml)) {
            val text = block.value
            val href = Regex("(?is)<d:href>(.*?)</d:href>")
                .find(text)?.groupValues?.get(1) ?: continue
            val length = Regex("(?i)<d:getcontentlength>(\\d+)</d:getcontentlength>")
                .find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            // Servers write the collection marker as <collection/>, <d:collection/>
            // or <D:collection></D:collection>; all of them mean "skip this entry".
            if (Regex("(?i)<(?:\\w+:)?collection").containsMatchIn(text)) continue
            val name = decodeHref(href)
            if (name.isEmpty()) continue
            files += WebdavFile(name, length)
        }
        return files
    }

    /**
     * Turns a multistatus href into a plain file name: percent-decoding it,
     * dropping any fragment, and keeping only what follows the last slash so
     * both path-style and full-URL hrefs work.
     */
    private fun decodeHref(href: String): String {
        val decoded = try {
            java.net.URLDecoder.decode(href, "UTF-8")
        } catch (e: Exception) {
            href
        }
        return decoded.substringAfterLast('#').trimEnd('/').substringAfterLast('/')
    }

    data class WebdavFile(val name: String, val size: Long)

    companion object {
        private val PREFERENCE_FILES = listOf("service", "ui", "app", "tips")

        // A freshly written file can need a moment before the server reports it
        // back; three attempts with a growing pause cover that without turning
        // a genuinely unreachable server into a long wait.
        private const val PROPFIND_ATTEMPTS = 3
        private const val PROPFIND_RETRY_DELAY_MS = 1_000L

        // Credentials and a missing WebDAV implementation are answers rather
        // than races: repeating them only delays the same failure.
        private val PERMANENT_PROPFIND_FAILURE = Regex("HTTP (401|403|405)")

        private fun timestamp(): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }
}
