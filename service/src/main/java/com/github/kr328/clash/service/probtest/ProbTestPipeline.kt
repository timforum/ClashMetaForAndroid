package com.github.kr328.clash.service.probtest

import android.content.Context
import android.util.Base64
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProbTestOptions
import com.github.kr328.clash.core.model.ProbTestReport
import com.github.kr328.clash.core.model.ProbTestSpeedTest
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One subscription to be screened. */
data class ProbTestCandidate(val name: String, val url: String)

/**
 * How many probe targets the editor remembers. Three is what fits the dialog
 * as choices under the field without the list having to scroll.
 */
private const val PROBE_URL_HISTORY = 3

/**
 * The phase a round is in. Downloading and publishing are quick but not
 * instant, and the probing stage dominates the wall clock, so the three are
 * reported separately instead of as one opaque "running".
 */
enum class Stage {
    DOWNLOADING,
    SCREENING,
    SPEED_TEST,
    PUBLISHING,
}

/**
 * A snapshot of how far a round has come. Every field carries a zero when it
 * does not apply to the current [stage], so a consumer only has to read the
 * ones its stage defines.
 *
 * Serializable so the worker can leave the latest snapshot in the shared
 * store, which is what lets a screen opened mid-run show the bar immediately
 * instead of waiting for the next increment to be broadcast.
 */
@Serializable
data class ProbTestProgress(
    val stage: Stage,
    /** Subscriptions fetched so far. */
    val done: Int = 0,
    /** Subscriptions to fetch, zero while the total is not known yet. */
    val total: Int = 0,
    val round: Int = 0,
    val rounds: Int = 0,
    /** Nodes that have not failed yet. */
    val passed: Int = 0,
    /** Nodes already eliminated. */
    val failed: Int = 0,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(progress: ProbTestProgress): String =
            json.encodeToString(serializer(), progress)

        /** Null for anything unreadable, so a corrupt value reads as "no round". */
        fun decode(raw: String): ProbTestProgress? = try {
            json.decodeFromString(serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }
}

/** The outcome of one scheduled screening round, suitable for a notification. */
data class ProbTestOutcome(
    val published: Boolean,
    val summary: String,
    val report: ProbTestReport? = null,
)

/**
 * Downloads the candidate subscriptions, asks the core to keep only the nodes
 * that pass every round and publishes the result to GitHub.
 *
 * Nothing here reconfigures the running core: the candidates are fetched with a
 * plain HTTP client and the screening happens through `Clash.probTest`, which
 * builds proxies directly instead of calling into `hub/executor`.
 */
class ProbTestPipeline(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // The per-read timeout says nothing about the whole call: a source
            // that keeps trickling bytes never trips it, so the call needs its
            // own ceiling for the coroutine's timeout to have anything to
            // interrupt. execute() is blocking, so cancelling the coroutine
            // alone would leave the socket open until the body finished.
            .callTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    // Uploading and publishing move a whole configuration rather than reading
    // one, so they get their own client: the download ceiling is sized for
    // fetching a subscription and is too tight for pushing one out over a slow
    // link, and nothing should be able to shorten it by way of a setting.
    private val uploadClient by lazy {
        client.newBuilder()
            .callTimeout(UPLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * The gate's tunables live in `speedtest.json` in the app's external files
     * directory, so a round can be re-tuned by editing the file instead of
     * rebuilding the app. Blank stays at the built in defaults.
     */
    private fun readSpeedConfig(): String {
        val file = File(context.getExternalFilesDir(null), "speedtest.json")
        return try {
            if (!file.exists()) "" else file.readText().also {
                if (it.isNotBlank()) Log.i("probtest: speed config from ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w("probtest: cannot read speed config", e)
            ""
        }
    }

    suspend fun run(onProgress: (ProbTestProgress) -> Unit = {}): ProbTestOutcome {
        val store = ServiceStore(context)

        val uploadUrl = secureUrl(store.probtestUploadUrl.trim())
        val useService = uploadUrl.isNotEmpty()
        if (!useService && (store.probtestGitHubToken.isBlank() || store.probtestGitHubRepo.isBlank())) {
            return ProbTestOutcome(
                false,
                "Neither an upload URL nor a GitHub token and repository is configured",
            )
        }

        // The primary candidate is required. Without it a round would screen
        // only whatever happens to be imported, which is not what the user asked
        // for when they set up a feed.
        if (store.probtestTestUrl.isBlank()) {
            return ProbTestOutcome(false, "No candidate subscription configured")
        }

        val candidates = collectCandidates(store, uploadUrl)
        if (candidates.isEmpty()) {
            return ProbTestOutcome(false, "No candidate subscription configured")
        }

        // Remembered here rather than when the field is edited, so a target only
        // earns a place once a round has actually measured against it. A URL
        // typed and abandoned would otherwise take a slot from one that works.
        val probeUrl = store.probtestProbeUrl.trim()
        if (probeUrl.isNotEmpty()) {
            store.probtestProbeUrlHistory = rememberProbeUrl(store.probtestProbeUrlHistory, probeUrl)
        }

        // The gate's tunables live in a JSON file on the device so a round
        // can be re-tuned by editing the file instead of rebuilding. It wins
        // over the built in defaults whenever present; a broken file fails
        // the round instead of silently weakening the gate.
        val speedConfig = readSpeedConfig()

        // Read before downloading anything: a round whose routing policy cannot
        // be assembled is a round that would have to publish something the
        // reader was never promised, and that is cheaper to report now than
        // after every candidate has been fetched.
        val template = try {
            withContext(Dispatchers.IO) { TemplateAssets.read(context) }
        } catch (e: Exception) {
            Log.w("probtest template unreadable", e)
            return ProbTestOutcome(false, e.message ?: "The screening template is missing")
        }

        // Every candidate is fetched at once rather than one after another: each
        // subscription can hold a megabyte of nodes and a slow one used to hold
        // the whole round behind it for the length of its read timeout. The
        // overall timeout is what keeps a source that stalls without closing
        // from doing that again, and a source that misses it is dropped - the
        // round screens whatever else answered, which is what it did when the
        // fetch simply failed.
        val documents = mutableListOf<String>()
        val progress = AtomicInteger()

        coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    // A null answer here means the ceiling stopped the fetch.
                    // A source that answered and failed carries a null document
                    // inside a non-null answer, so the two stay apart: "gave up"
                    // and "answered with nothing" point at different failures,
                    // and reporting both as the first one sends whoever reads
                    // the log after the wrong one.
                    val answer = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                        Downloaded(fetch(candidate.url))
                    }

                    if (answer == null) {
                        Log.w("probtest: $candidate.url gave up after ${DOWNLOAD_TIMEOUT_MS}ms")
                    }

                    progress.incrementAndGet()

                    candidate to answer?.text
                }
            }.awaitAll()
        }.forEach { (candidate, text) ->
            onProgress(
                ProbTestProgress(
                    Stage.DOWNLOADING,
                    done = progress.get(),
                    total = candidates.size,
                )
            )

            if (text != null) {
                documents += text
            } else {
                Log.w("probtest: no document from ${candidate.name}")
            }
        }

        if (documents.isEmpty()) {
            return ProbTestOutcome(false, "Every candidate subscription failed to download")
        }

        val options = ProbTestOptions(
            // Empty hands the probe to the core default (Cloudflare generate_204).
            // The Test URL field is a candidate subscription, not a probe target.
            testUrl = store.probtestProbeUrl.trim(),
            rounds = ROUNDS,
            roundGapMs = store.probtestRoundGapSeconds.coerceAtLeast(0L) * 1000L,
            templateIni = template.ini,
            ruleFiles = template.ruleFiles,
            speedTest = ProbTestSpeedTest(enabled = true),
            speedTestConfig = speedConfig,
        )

        // Stated before the run rather than inferred from it afterwards: the
        // number of nodes that fail a round is what the setting is meant to
        // move, and without the figure beside it a round of timeouts cannot be
        // told apart from one caused by a probe target that stopped answering.
        Log.i(
            "probtest: probing ${documents.size} documents, ${options.rounds} rounds" +
                " ${options.roundGapMs / 1000}s apart, ${options.concurrency} at a time," +
                " against ${options.testUrl.ifEmpty { "the core default" }}"
        )

        val envelope = Clash.probTest(documents, options) { progress ->
            Log.d("probtest round ${progress.round}/${progress.rounds} passed=${progress.passed} failed=${progress.failed}")

            onProgress(
                ProbTestProgress(
                    stage = when (progress.stage) {
                        // The core reports the throughput gate by name; the
                        // latency rounds arrive with no stage at all.
                        "speedtest" -> Stage.SPEED_TEST
                        else -> Stage.SCREENING
                    },
                    round = progress.round,
                    rounds = progress.rounds,
                    passed = progress.passed,
                    failed = progress.failed,
                    total = progress.total,
                    done = progress.done,
                )
            )
        }

        val report = envelope.report
        val survived = report?.survivors ?: 0
        val configuration = envelope.configuration

        if (!envelope.ok || configuration == null) {
            return ProbTestOutcome(false, envelope.error ?: "Screening failed", report)
        }

        onProgress(ProbTestProgress(stage = Stage.PUBLISHING, total = survived))

        if (useService) {
            // The whole configuration is uploaded, not the bare node list. The
            // template has already decided which groups the survivors fill and
            // where every rule set sends its traffic, so handing over only the
            // proxies would discard that work and leave the service rebuilding
            // a layout of its own.
            val destination = uploadTo(
                url = uploadUrl,
                token = store.probtestUploadToken.trim(),
                document = configuration,
            )

            return ProbTestOutcome(
                published = true,
                summary = "Uploaded $survived of ${report?.total ?: 0} nodes to $destination",
                report = report,
            )
        }

        val target = GitHubTarget(
            token = store.probtestGitHubToken,
            repository = store.probtestGitHubRepo,
            branch = store.probtestGitHubBranch.ifBlank { "main" },
            path = store.probtestGitHubPath.ifBlank { "clash/config.yaml" },
        )

        publish(target, configuration, report, documents.size)

        return ProbTestOutcome(
            published = true,
            summary = "Published $survived of ${report?.total ?: 0} nodes to " +
                "${target.repository}:${target.path}",
            report = report,
        )
    }

    /**
     * Rewrites a cleartext URL to https before it is ever used.
     *
     * The app blocks cleartext traffic outright, so a plain http upload URL
     * can only ever fail with a network security policy error. Every host this
     * is pointed at (a Cloudflare worker, GitHub) terminates TLS, and the
     * request carries the upload token, so sending it in the clear is exactly
     * the case that must not be preserved. Upgrading the scheme is therefore
     * the only outcome that can work, rather than a guess.
     */
    private fun secureUrl(url: String): String {
        val upgraded = if (url.startsWith("http://", ignoreCase = true)) {
            "https://" + url.substring("http://".length)
        } else {
            url
        }

        if (upgraded != url) {
            Log.i("probtest: upgraded upload URL from cleartext http to https")
        }

        return upgraded
    }

    /**
     * Pushes the published configuration to the service.
     *
     * The body goes out compressed: a configuration is mostly rule providers,
     * which repeat heavily and shrink to a fraction of their size, and the exit
     * a round measures through is frequently far slower upstream than down - one
     * node measured at 319 KB/s down and 37 KB/s up, which turned a four and a
     * half megabyte upload into a timeout. The endpoint reads
     * `Content-Encoding: gzip` and answers with what it stored, so this is only
     * a smaller request rather than a different one.
     */
    private suspend fun uploadTo(url: String, token: String, document: String): String {
        val compressed = gzip(document)

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/yaml; charset=utf-8")
            .header("Content-Encoding", "gzip")
            .header("User-Agent", USER_AGENT)
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .post(compressed.toRequestBody(YAML_MEDIA_TYPE))
            .build()

        withContext(Dispatchers.IO) {
            uploadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error(
                        "Upload endpoint responded with HTTP ${response.code}: " +
                            response.body?.string()?.take(300),
                    )
                }
            }
        }

        Log.i(
            "probtest: uploaded ${compressed.size} bytes of gzip " +
                "(${document.toByteArray().size} plain) to $url"
        )

        return url
    }

    /**
     * The gzipped [text], or the plain bytes when it cannot be compressed.
     *
     * Compression is an optimisation, not a requirement: an endpoint that
     * ignores Content-Encoding would store the plain body it is sent, so a
     * failure here must not cost the round its only result.
     */
    private fun gzip(text: String): ByteArray {
        return try {
            val buffer = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(buffer).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }

            buffer.toByteArray()
        } catch (e: Exception) {
            Log.w("probtest: cannot compress the upload, sending it plain", e)

            text.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * The subscriptions a round screens: the required primary, any extra URLs,
     * and, when enabled, every subscription already imported. A subscription
     * that is not worth using as the live profile can still contribute good
     * nodes to the published feed.
     *
     * The same URL reached through more than one source (imported plus listed
     * again, or listed twice) is screened once, because a second fetch of the
     * identical document only duplicates the nodes it returns.
     */

    /**
     * Puts [url] at the front of a newline separated history, keeping only the
     * most recent [PROBE_URL_HISTORY] distinct entries. A repeat moves to the
     * front instead of taking a second slot, so alternating between two
     * targets does not push a third one out.
     */
    private fun rememberProbeUrl(history: String, url: String): String {
        val kept = LinkedHashSet<String>()
        kept += url

        for (line in history.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                kept += trimmed
            }

            if (kept.size == PROBE_URL_HISTORY) {
                break
            }
        }

        return kept.take(PROBE_URL_HISTORY).joinToString("\n")
    }

    /**
     * Lists the subscriptions a round screens.
     *
     * [uploadUrl] is where the result is published, and nothing on the same
     * origin may be screened: the worker answers the publish path and the
     * subscription path from the same stored document, so screening the
     * service's own output makes every round re-read what the previous one
     * published. Nodes dropped by one round are gone from that document
     * forever and can never come back, so the pool only ever shrinks - 1000
     * nodes become 100, then 1, and a round against a single survivor can
     * never grow again. Comparing origins rather than whole URLs is what
     * catches it, since the two paths differ even though the host does not.
     */
    private suspend fun collectCandidates(
        store: ServiceStore,
        uploadUrl: String,
    ): List<ProbTestCandidate> {
        val destination = uploadUrl.takeIf { it.isNotEmpty() }?.let { originOf(it) }

        val seen = LinkedHashSet<String>()
        val candidates = mutableListOf<ProbTestCandidate>()

        fun offer(name: String, url: String) {
            if (url.isEmpty() || !seen.add(url)) {
                return
            }

            if (destination != null && originOf(url) == destination) {
                Log.w(
                    "probtest: skipping '$url': it is served by the same origin as " +
                        "the upload destination $uploadUrl, and screening the " +
                        "published result would shrink the pool every round"
                )
                return
            }

            candidates += ProbTestCandidate(name, url)
        }

        val primary = store.probtestTestUrl.trim()
        if (primary.isNotEmpty()) {
            offer(primary, primary)
        }

        for (line in store.probtestCandidates.lineSequence()) {
            val url = line.trim()
            if (url.isNotEmpty()) {
                offer(url, url)
            }
        }

        // The file the user picked on this phone, one URL per line: the on-device
        // counterpart of the inline list above, which keeps the worker from
        // hard-coding every source and lets the pool grow without a new build.
        for (url in readExtraSubFile()) {
            offer(url, url)
        }

        if (store.probtestIncludeImported) {
            for (profile in ImportedDao().queryAll()) {
                if (profile.type == Profile.Type.Url && profile.source.isNotBlank()) {
                    offer(profile.name, profile.source)
                }
            }
        }

        return candidates
    }

    /**
     * The scheme, host and port a URL is served from, or null when it has
     * none. Lower-cased and without a trailing slash so that a host spelled
     * with or without a default port compares equal.
     */
    private fun originOf(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isEmpty()) {
            return null
        }

        val port = uri.port.takeIf { it >= 0 }?.toString() ?: ""

        return "$scheme://$host:$port"
    }

    /**
     * The URLs in the file the user picked, if there is one.
     *
     * Read from the copy taken while the picker was up rather than from the
     * content uri: the grant behind that uri belongs to the process that
     * asked for it, so this process cannot open the file at all. Reading the
     * copy is also what lets the picked file outlive the screen that chose it.
     */
    private suspend fun readExtraSubFile(): List<String> {
        return withContext(Dispatchers.IO) { ExtraSubFile.read(context) }
    }

    private suspend fun fetch(url: String): String? {
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w("probtest: $url returned HTTP ${response.code}")
                        return@use null
                    }

                    val bytes = (response.body?.byteStream() ?: return@use null)
                        .readAtMost(MAX_CANDIDATE_BYTES)

                    decode(bytes)
                }
            }
        } catch (e: Exception) {
            Log.w("probtest: failed to download $url", e)
            null
        }
    }

    /**
     * Some panels serve a base64 subscription instead of YAML, so fall back to
     * decoding it when the payload does not look like a configuration.
     */
    private fun decode(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8).trim()

        if (text.startsWith("proxies:") || text.contains("\nproxies:") || text.contains("proxy-groups:")) {
            return text
        }

        return try {
            String(Base64.decode(text, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            text
        }
    }

    private suspend fun publish(
        target: GitHubTarget,
        configuration: String,
        report: ProbTestReport?,
        candidates: Int,
    ) {
        val content = Base64.encodeToString(
            configuration.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )

        val url = "https://api.github.com/repos/${target.repository}/contents/${target.path}" +
            "?ref=${target.branch}"

        val existing = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${target.token}")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        null
                    } else {
                        json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                    }
                }
            } catch (e: Exception) {
                Log.w("probtest: cannot read the current file", e)
                null
            }
        }

        val sha = existing?.get("sha")?.jsonPrimitive?.content

        val payload = buildString {
            append("{")
            append("\"message\":").append(quote(message(report, candidates))).append(",")
            append("\"content\":").append(quote(content)).append(",")
            append("\"branch\":").append(quote(target.branch))
            if (sha != null) {
                append(",\"sha\":").append(quote(sha))
            }
            append("}")
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${target.token}")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .put(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        withContext(Dispatchers.IO) {
            uploadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("GitHub responded with HTTP ${response.code}: ${response.body?.string()?.take(300)}")
                }
            }
        }
    }

    private fun message(
        report: ProbTestReport?,
        candidates: Int,
    ): String {
        val survivors = report?.survivors ?: 0
        val total = report?.total ?: 0

        return "chore: publish $survivors/$total nodes passing ${ROUNDS}/${ROUNDS} rounds " +
            "from $candidates candidate subscriptions"
    }

    /** JSON string literal, escaped the way the Contents API expects. */
    private fun quote(value: String): String {
        val escaped = StringBuilder(value.length + 2)
        escaped.append('"')

        for (c in value) {
            when (c) {
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                else -> if (c < ' ') {
                    escaped.append("\\u").append(String.format("%04x", c.code))
                } else {
                    escaped.append(c)
                }
            }
        }

        return escaped.append('"').toString()
    }

    data class GitHubTarget(
        val token: String,
        val repository: String,
        val branch: String,
        val path: String,
    )

    /**
     * A candidate download that finished, whether or not it produced a document.
     *
     * Only the [text] is ever null here. The wrapper exists so that "finished
     * with nothing" stays distinguishable from "never finished", which a bare
     * nullable string cannot express.
     */
    private class Downloaded(val text: String?)

    private companion object {
        const val ROUNDS = 3
        const val USER_AGENT = "ClashMetaForAndroid/1.0"
        const val MAX_CANDIDATE_BYTES = 8L * 1024 * 1024

        /**
         * How long one candidate may take in all, whatever the socket-level
         * timeouts allow. Long enough for a multi-megabyte subscription over a
         * slow link, short enough that a stalled source does not hold the round
         * for the sum of every read timeout it can take.
         */
        const val DOWNLOAD_TIMEOUT_MS = 90_000L

        /**
         * The ceiling for pushing a configuration out. Larger than the download
         * one because it carries the whole document, and a timeout here loses
         * the round's only result rather than one of its inputs. The body is
         * compressed, so this is headroom over a transfer that is measured in
         * tens of seconds, not a figure taken from one.
         */
        const val UPLOAD_TIMEOUT_MS = 300_000L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val YAML_MEDIA_TYPE = "application/yaml; charset=utf-8".toMediaType()
    }

    /** Reads at most [limit] bytes so a hostile subscription cannot exhaust memory. */
    private fun InputStream.readAtMost(limit: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)

        var total = 0L
        while (true) {
            val read = read(chunk)
            if (read <= 0) break

            total += read
            buffer.write(chunk, 0, read)

            if (total >= limit) {
                Log.w("probtest: candidate truncated at $limit bytes")
                break
            }
        }

        return buffer.toByteArray()
    }
}
