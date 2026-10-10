package com.github.kr328.clash.service.probtest

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * The picked file of subscription URLs, kept where the worker can read it.
 *
 * A content uri carries its permission with it, and the grant a picker hands
 * out belongs to the process that asked for it. The worker runs in the service
 * process and opens the file long after the screen that picked it is gone, so
 * there is nothing there to authorise it - opening a file the Downloads app
 * owns fails outright rather than returning nothing. Copying the content while
 * the picker is still in front is the only point at which the read is
 * guaranteed to work.
 *
 * The uri itself stays on the store so the row can name the file that was
 * picked; this is the content behind it.
 */
object ExtraSubFile {
    // Named for what it is rather than for the file it replaced, so a stale
    // copy from an older install is not read as if it were current.
    private const val NAME = "probtest-extra-sub.txt"

    private const val MAX_BYTES = 8L * 1024 * 1024

    fun copy(context: Context, uri: Uri): File? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readAtMost(MAX_BYTES)
            } ?: return null

            val target = file(context)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)

            target
        } catch (e: Exception) {
            Log.w("probtest: cannot copy the picked subscription file", e)
            null
        }
    }

    fun file(context: Context) = File(context.filesDir, NAME)

    /**
     * The URLs the copy holds, or nothing when there is none. A missing copy
     * is not an error: the file may never have been picked on this version, and
     * a round screens whatever else it has rather than failing over one source.
     */
    fun read(context: Context): List<String> {
        val file = file(context)

        return try {
            if (!file.isFile) {
                emptyList()
            } else {
                file.readText(Charsets.UTF_8)
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        } catch (e: Exception) {
            Log.w("probtest: cannot read the copied subscription file", e)
            emptyList()
        }
    }

    private fun InputStream.readAtMost(limit: Long): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)

        var total = 0L
        while (total < limit) {
            val read = read(chunk)
            if (read <= 0) break

            total += read
            buffer.write(chunk, 0, read)
        }

        return buffer.toByteArray()
    }
}