package com.github.kr328.clash.service.probtest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProbTestOptions
import com.github.kr328.clash.core.model.ProbTestReport
import com.github.kr328.clash.core.model.ProbTestSpeedTest
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * How many times the same document is offered before the round gives up on
 * reaching the service from here.
 *
 * The first attempt keeps the route the reader chose. The four after it each
 * move to the next best measured node, so a node that drops the write costs one
 * attempt rather than the round: five exits is enough to tell a broken node
 * from a broken destination, and still finishes inside a schedule's pause.
 */
private const val UPLOAD_ATTEMPTS = 5

/**
 * The ceiling for pushing a configuration out. Larger than the download one
 * because it carries the whole document, and a timeout here loses the round's
 * only result rather than one of its inputs. The body is compressed, so this is
 * headroom over a transfer that is measured in tens of seconds, not a figure
 * taken from one.
 */
private const val UPLOAD_TIMEOUT_MS = 300_000L

/**
 * One direct attempt's ceiling. Shorter than [UPLOAD_TIMEOUT_MS] because it is
 * no longer the only attempt: a node that cannot move a compressed
 * configuration in seconds is not one the next attempt should wait on.
 */
private const val UPLOAD_ATTEMPT_TIMEOUT_MS = 120_000L

/**
 * The relay's ceiling, back to the full length: it forwards the body itself and
 * may spend several attempts upstream before it answers, so the phone is waiting
 * on work it did not start.
 */
private const val UPLOAD_RELAY_TIMEOUT_MS = UPLOAD_TIMEOUT_MS

/** Time for the tunnel to dial a node the route was just moved to. */
private const val UPLOAD_SETTLE_MS = 1_000L

/** Spaces the attempts that could not change the route at all. */
private const val UPLOAD_RETRY_MS = 2_000L

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
 * Where the configuration ended up, and how it got there.
 *
 * [relayed] is not another word for "took a retry": it means the round never
 * reached the service from this device at all and handed the result to the
 * sandbox instead. A reader looking at a published pool wants to know which of
 * the two carried it, because only one of them exercised the path the phone
 * itself uses.
 */
private data class UploadRouteResult(
    val url: String,
    val attempts: Int,
    val relayed: Boolean,
) {
    val description: String
        get() = when {
            relayed -> "to $url through the relay after $attempts attempts"
            attempts == 1 -> "to $url"
            else -> "to $url on attempt $attempts of $UPLOAD_ATTEMPTS"
        }
}

/**
 * Downloads the candidate subscriptions, asks the core to keep only the nodes
 * that pass every round and publishes the result to GitHub.
 *
 * Nothing here reconfigures the running core: the candidates are fetched with a
 * plain HTTP client and the screening happens through `Clash.probTest`, which
 * builds proxies directly instead of calling into `hub/executor`.
 */
/**
 * What kind of failure a node's error describes, coarsely enough that a pool of
 * a thousand nodes collapses into a handful of buckets.
 *
 * The order matters: the first match wins, so the conditions that would make
 * the whole device the problem are tested before the ones that are about the
 * node. "too many open files" and a socket exhaustion in any other wording come
 * first, because a round that hit either was never measuring nodes at all and
 * everything after it is an artefact.
 */
private fun failureKind(error: String?): String {
    val message = error?.lowercase().orEmpty()

    // The gate names the probe it stopped at, so a node that answered the
    // latency rounds and then failed on YouTube reads differently from one that
    // never answered at all - kept for last so a specific wording above wins.
    //
    // A stage can be two words: "parse vless: ..." names the protocol the core
    // could not read, and the type was put there precisely so the count stops
    // being an anonymous "parse". A test that admits only a single word folded
    // every one of those into "other", which is the silence the type was added
    // to remove, so the pair is kept whole as one token.
    val stage = message.substringBefore(':').trim()
    val stageName = when {
        stage.startsWith("parse ") && stage.length <= 24 -> stage.replace(' ', '-')
        stage.isNotEmpty() && stage.length <= 16 && stage.none { it.isWhitespace() } -> stage
        else -> "other"
    }

    return when {
        message.contains("too many open files") ||
            message.contains("file table overflow") ||
            message.contains("cannot allocate") ||
            (message.contains("socket") && message.contains("limit")) -> "socket-exhausted"

        message.contains("connection reset") -> "reset"
        message.contains("i/o timeout") || message.contains("timed out") -> "timeout"
        message.contains("no such host") -> "dns"
        message.contains("refused") -> "refused"
        message.contains("deadline exceeded") || message.contains("did not answer") -> "no-answer"
        message.contains("above") && message.contains("limit") -> "over-delay-limit"

        else -> stageName
    }
}

/** How many failure kinds one round reports, so a long tail cannot bury the head. */
private const val FAILURE_KINDS_LOGGED = 8

/** The bucket a message falls into when no named kind claims it. */
private const val UNNAMED_KIND = "other"

/** How many of those to spell out, which is enough to see a new shape appear. */
private const val UNNAMED_SIGNATURES_LOGGED = 6

/** An address, which differs for every node and says nothing about the failure. */
private val IPV4_ADDRESS = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

/** Any bare number: a port, a port count, a duration, a status code. */
private val BARE_NUMBER = Regex("""\b\d+\b""")

/**
 * A failure message with the per-node detail folded away, so messages that mean
 * the same thing group together.
 *
 * What is removed is exactly what identifies the node - its address, its port,
 * the lengths and timings of one particular attempt - because those differ in
 * every message and would make a thousand distinct failures read as a thousand
 * distinct problems. What is left is the shape of the complaint, which is the
 * part a reader can act on. The result is not a secret: it still names protocols
 * and ports, the same things the core's own logging prints.
 */
private fun errorSignature(error: String?): String =
    BARE_NUMBER
        .replace(IPV4_ADDRESS.replace(error.orEmpty().lowercase(), "<ip>"), "<n>")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(SIGNATURE_LENGTH)

/** Long enough for a message to be told apart, short enough to stay one line. */
private const val SIGNATURE_LENGTH = 64

/** The delay and the limit the core compared it against, out of a rejection. */
private val OVER_LIMIT_DELAY = Regex("""delay (\d+)ms above (\d+)ms limit""")

/**
 * A band of delays above the limit, expressed as multiples of the limit so the
 * bands mean the same thing whichever limit happens to be in force.
 *
 * The first band ends at 1.6, which is where a 1000ms limit's obvious
 * alternative - 1600ms - lands, so it is the band that settles the question of
 * whether raising the line would admit a cluster or a tail.
 */
private val DELAY_BANDS = listOf(
    DelayBand(1.0, 1.6),
    DelayBand(1.6, 2.5),
    DelayBand(2.5, 4.0),
    DelayBand(4.0, null),
)

/** One band above the limit. A null upper bound means the band has no ceiling. */
private data class DelayBand(val from: Double, val to: Double?) {
    fun holds(delayMs: Long, limitMs: Long): Boolean {
        val low = limitMs * from
        return delayMs >= low && (to == null || delayMs <= limitMs * to)
    }

    /** The band in milliseconds, so the numbers can be read without arithmetic. */
    fun label(limitMs: Long): String {
        val low = (limitMs * from).toInt()
        val high = to?.let { (limitMs * it).toInt() }
        return if (high == null) ">$low" else "$low-$high"
    }
}

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

    /**
     * Runs [block] with the process pinned to the network under the active VPN,
     * when another app owns that VPN.
     *
     * The screening dials every candidate itself, so what it measures has to be
     * the raw network: a round run through someone else's tunnel measures that
     * tunnel, not the nodes, and publishing the survivors would promise the
     * reader something the measurement never showed. When this app owns the
     * tunnel its sockets are already protected per socket, and rebinding the
     * process on top of that would only disturb the core, so that case is left
     * alone. When another app owns it the only way out is to bind the process
     * to the network the VPN sits on; a VPN built without `allowBypass` refuses
     * that, and then the round can do nothing but say so in the log, because
     * quietly reporting tunnel numbers as the nodes' own would be a lie.
     *
     * Only the screening is wrapped, never the round: the candidate
     * subscriptions are fetched over the normal path on purpose, because a
     * source that is blocked in the device's own country still has to be
     * reachable through the tunnel the user has up.
     */
    private suspend fun <T> offForeignVpn(block: suspend () -> T): T {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return block()

        val connectivity = context.getSystemService<ConnectivityManager>() ?: return block()

        val active = connectivity.activeNetwork
        val capabilities = active?.let(connectivity::getNetworkCapabilities)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return block()

        // Our own tunnel: per-socket protection is already in place and binding
        // the process would change routing for the core running beside us.
        if (StatusProvider.vpnRunning) return block()

        val underlying = connectivity.allNetworks.firstOrNull { candidate ->
            val c = connectivity.getNetworkCapabilities(candidate) ?: return@firstOrNull false

            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        if (underlying == null || !connectivity.bindProcessToNetwork(underlying)) {
            Log.w(
                "probtest: another app's VPN is active and will not let this process bypass it;" +
                    " this round measures through that tunnel"
            )

            return block()
        }

        Log.i("probtest: another app's VPN is active; screening on the underlying network")

        return try {
            block()
        } finally {
            connectivity.bindProcessToNetwork(null)
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

        // What this round will actually screen, named rather than merely
        // counted. The pool is assembled from four sources and two of them can
        // silently contribute nothing - a source on the upload's own origin is
        // refused to keep the published result out of its own input, and the
        // picked file is read through a copy that may not have survived. A
        // round that publishes a handful of nodes out of a thousand looks
        // like a strict gate, and without this line there is nothing in the
        // log that distinguishes that from a pool that was never fed.
        //
        // URLs are reduced to their host and path shape, never printed whole:
        // these are subscription links and they carry the credentials that
        // authorise them.
        Log.i(
            "probtest: screening ${candidates.size} source(s): " +
                candidates.joinToString { redactedUrl(it.url) }
        )

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
            // Clamped rather than merely defaulted: the core falls back to
            // sixteen for anything at or below zero, but takes a large number
            // at face value, and a round that opens a hundred thousand sockets
            // takes the whole phone down before it finishes failing.
            concurrency = store.probtestConcurrency.toInt().coerceIn(1, 128),
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

        // The screening itself runs off any VPN another app owns (see
        // [offForeignVpn]): the downloads above stay on the normal path, the
        // node measurements below must not.
        val envelope = offForeignVpn {
            Clash.probTest(documents, options) { progress ->
                // One shape for every stage would have been shorter, but the gate
                // borrows the latency rounds' numbers on the way through, so
                // "round 3/3 passed=0 failed=1" lands after the last round has
                // already been reported and reads as a fourth pass over screening
                // survivors that were still being measured. Name the stage.
                if (progress.stage == "speedtest") {
                    Log.d(
                        "probtest speed gate ${progress.done}/${progress.total}" +
                            " passed=${progress.passed} failed=${progress.failed}"
                    )
                } else {
                    Log.d(
                        "probtest round ${progress.round}/${progress.rounds}" +
                            " passed=${progress.passed} failed=${progress.failed}"
                    )
                }

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
        }

        val report = envelope.report
        val survived = report?.survivors ?: 0
        val total = report?.total ?: 0
        val configuration = envelope.configuration

        // Why the round lost what it lost, counted by kind rather than told in
        // totals. Two rounds measured here came out the same way - over a
        // thousand candidates, four or one survivor - and the per-round counts
        // cannot say which of two very different things happened: a probe target
        // that started refusing after the first round's worth of requests, or
        // nodes whose own servers stopped answering. They look identical from
        // the outside, a cliff between round one and round two, and they call
        // for opposite fixes - one is the target's, the other's is the pool's.
        //
        // The messages separate them. A refused target reads as a reset or a
        // status the transport could not use; a node whose server gave up reads
        // as a read or write to the node's own address; and a device that ran
        // out of sockets mid-round says so outright, which looks exactly like a
        // dead pool from the outside and is not one at all.
        val failures = report?.nodes?.filter { !it.error.isNullOrBlank() }.orEmpty()

        failures
            .groupingBy { failureKind(it.error) }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(FAILURE_KINDS_LOGGED)
            .takeIf { it.isNotEmpty() }
            ?.let { kinds ->
                Log.i(
                    "probtest: ${survived} of ${total} survived; failures by kind " +
                        kinds.joinToString { "${it.key}=${it.value}" }
                )
            }

        // The nodes that answered and were refused, and by how much. These are
        // the only losses in the round that a decision could recover: a node
        // that never answered is lost to the network, while a node that
        // answered above the limit is lost to a number, and the number is
        // chosen. Which number is defensible depends entirely on the shape of
        // this pile - a hundred nodes just over the line mean the line is
        // drawn through a working cluster, while a hundred spread from one to
        // ten seconds mean there is nothing there worth keeping and raising the
        // line would only admit the tail. So say where they sit relative to it.
        failures.mapNotNull { OVER_LIMIT_DELAY.find(it.error ?: "")?.groupValues?.get(1)?.toLongOrNull() }
            .takeIf { it.isNotEmpty() }
            ?.let { overLimit ->
                val limit = options.maxDelayMs
                Log.i(
                    "probtest: ${overLimit.size} node(s) answered over the ${limit}ms limit; " +
                        "relative to it " +
                        DELAY_BANDS.joinToString { band ->
                            "${band.label(limit)}=${overLimit.count { band.holds(it, limit) }}"
                        }
                )
            }

        // The buckets above are a list someone wrote down in advance, so
        // whatever they do not name lands in one anonymous bucket - and on the
        // round that produced this line that bucket held a seventh of everything
        // lost. A count with no name is the same silence as no count at all.
        // Group those by what the message says, with the parts that differ per
        // node folded away, so the remainder is readable without being
        // predictable: whatever shows up here is by definition the part of the
        // failure space nobody thought of.
        failures
            .filter { failureKind(it.error) == UNNAMED_KIND }
            .groupingBy { errorSignature(it.error) }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(UNNAMED_SIGNATURES_LOGGED)
            .takeIf { it.isNotEmpty() }
            ?.let { rest ->
                Log.i(
                    "probtest: unnamed failures by message " +
                        rest.joinToString { "${it.value}x ${it.key}" }
                )
            }

        if (!envelope.ok || configuration == null) {
            return ProbTestOutcome(false, envelope.error ?: "Screening failed", report)
        }

        onProgress(ProbTestProgress(stage = Stage.PUBLISHING, total = survived))

        // A round that lost most of its sources still finishes: the documents
        // that did arrive are probed, a handful survive, and publishing them
        // replaces a working subscription with a near-empty one. That is what
        // happened when a round downloaded 53 of 1274 candidates - the one node
        // left was published and the pool was gone until the next good round.
        // The count of the last publish is the only record of what "working"
        // meant, so it is what a collapse is measured against.
        val previousPublished = store.probtestPublishedNodes

        // Most of the sources failing is the shape this guard exists for: the
        // round still finishes, because the one source that answered is probed
        // to the end, but what it measured is a fraction of the pool - and a
        // handful of survivors from a fraction is not a screening result. This
        // is the round that published a single node and emptied the
        // subscription until the next good pass.
        if (candidates.size >= MIN_SOURCES && documents.size * 2 < candidates.size) {
            val message = "Kept the current subscription: only ${documents.size} of " +
                "${candidates.size} candidate sources answered, so this round " +
                "measured too little to replace the pool"

            Log.w("probtest: refused to publish - $message")

            return ProbTestOutcome(false, message, report)
        }

        if (previousPublished >= COLLAPSE_FLOOR &&
            survived.toLong() * COLLAPSE_RATIO < previousPublished
        ) {
            val message = "Kept the previous $previousPublished nodes: only " +
                "$survived of ${report?.total ?: 0} passed this round, which " +
                "reads as a failed download rather than a screening result"

            Log.w("probtest: refused to publish - $message")

            return ProbTestOutcome(false, message, report)
        }

        if (useService) {
            // The whole configuration is uploaded, not the bare node list. The
            // template has already decided which groups the survivors fill and
            // where every rule set sends its traffic, so handing over only the
            // proxies would discard that work and leave the service rebuilding
            // a layout of its own.
            val route = uploadWithFallback(
                store = store,
                primaryUrl = uploadUrl,
                token = store.probtestUploadToken.trim(),
                document = configuration,
                report = report,
            )

            store.probtestPublishedNodes = survived.toLong()

            return ProbTestOutcome(
                published = true,
                summary = "Uploaded $survived of ${report?.total ?: 0} nodes ${route.description}",
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

        store.probtestPublishedNodes = survived.toLong()

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
     * Offers the configuration until one route takes it.
     *
     * The first attempt uses the tunnel as it stands. Every later one first
     * moves the profile's route onto the next best node this install has a
     * measured figure for - the same move the speed test makes - because the
     * exit is the one part of the path a round cannot otherwise change from
     * here. When every attempt on the direct destination has failed, the body
     * goes to the configured relay instead, which forwards it from the sandbox
     * where the leg to the service is short.
     *
     * A round that lost its pool to a dropped connection at the last step is
     * the case this exists for: the screening measured correctly, the publish
     * never arrived, and the previous subscription stayed in place.
     */
    private suspend fun uploadWithFallback(
        store: ServiceStore,
        primaryUrl: String,
        token: String,
        document: String,
        report: ProbTestReport?,
    ): UploadRouteResult {
        val compressed = gzip(document)
        val plainBytes = document.toByteArray().size
        val route = UploadRoute()

        // Two rankings, merged. The group speed tests say how fast the nodes the
        // profile is running today were, and this round says how fast the
        // candidates that survived were. The pools overlap whenever a
        // subscription keeps delivering the same nodes, and the newer figure
        // wins for every name both know: it was measured minutes ago, through
        // the same keys.
        val measured = LinkedHashMap<String, Double>(store.speedTestResults().mbps)

        report?.nodes.orEmpty()
            .filter { it.speedMbps > 0.0 }
            .forEach { measured[it.name] = maxOf(measured[it.name] ?: 0.0, it.speedMbps) }

        // Every name here comes from a measured round, but a name survives in
        // the results after the pool it came from is gone, so the ranking is
        // limited to the nodes the running configuration still has.
        val candidates = route.ranked(
            measured = measured,
            skip = route.inUse(),
            limit = UPLOAD_ATTEMPTS - 1,
        )

        if (candidates.isNotEmpty()) {
            Log.i(
                "probtest: upload fallback order: " +
                    candidates.joinToString { "'$it'" }
            )
        }

        var lastError: Exception? = null

        try {
            for (attempt in 1..UPLOAD_ATTEMPTS) {
                if (attempt > 1) {
                    val node = candidates.getOrNull(attempt - 2)

                    if (node != null && route.moveTo(node)) {
                        Log.i(
                            "probtest: upload attempt $attempt of " +
                                "$UPLOAD_ATTEMPTS through '$node'"
                        )

                        // A selector takes effect on the next connection, and
                        // the tunnel needs a moment to dial the new node before
                        // a request through it can be expected to go anywhere.
                        delay(UPLOAD_SETTLE_MS)
                    } else {
                        Log.w(
                            "probtest: upload attempt $attempt of " +
                                "$UPLOAD_ATTEMPTS stays on the current route"
                        )

                        delay(UPLOAD_RETRY_MS * (attempt - 1))
                    }
                }

                try {
                    uploadTo(
                        url = primaryUrl,
                        token = token,
                        compressed = compressed,
                        plainBytes = plainBytes,
                        timeoutMs = UPLOAD_ATTEMPT_TIMEOUT_MS,
                    )

                    return UploadRouteResult(primaryUrl, attempt, relayed = false)
                } catch (e: Exception) {
                    lastError = e

                    Log.w(
                        "probtest: upload attempt $attempt of $UPLOAD_ATTEMPTS " +
                            "to $primaryUrl failed",
                        e
                    )
                }
            }
        } finally {
            // The route was borrowed for these attempts only. Failing here too
            // must not leave the reader on a node the round picked.
            route.restore()
        }

        val relayUrl = secureUrl(store.probtestRelayUrl.trim())

        if (relayUrl.isEmpty() || relayUrl == primaryUrl) {
            throw IllegalStateException(
                "Upload failed after $UPLOAD_ATTEMPTS attempts: " +
                    (lastError?.message ?: "unknown error") +
                    "; no relay is configured",
                lastError,
            )
        }

        Log.i("probtest: direct upload failed $UPLOAD_ATTEMPTS time(s); handing it to $relayUrl")

        try {
            uploadTo(
                url = relayUrl,
                token = token,
                compressed = compressed,
                plainBytes = plainBytes,
                timeoutMs = UPLOAD_RELAY_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            Log.w("probtest: relay upload to $relayUrl failed", e)

            throw IllegalStateException(
                "Upload failed after $UPLOAD_ATTEMPTS attempts " +
                    "(${lastError?.message ?: "unknown error"}) and the relay refused it too: " +
                    (e.message ?: "unknown error"),
                e,
            )
        }

        return UploadRouteResult(relayUrl, UPLOAD_ATTEMPTS, relayed = true)
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
     *
     * [compressed] is passed in already compressed so a retry after a failed
     * attempt does not spend the round's time compressing the same document
     * again.
     */
    private suspend fun uploadTo(
        url: String,
        token: String,
        compressed: ByteArray,
        plainBytes: Int,
        timeoutMs: Long,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/yaml; charset=utf-8")
            .header("Content-Encoding", "gzip")
            .header("User-Agent", USER_AGENT)
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .post(compressed.toRequestBody(YAML_MEDIA_TYPE))
            .build()

        withContext(Dispatchers.IO) {
            // A fresh call per attempt is the point: the route was moved
            // between attempts, and a connection kept alive from before the
            // move would still leave through the node that failed. A failed
            // call is discarded anyway, so the pool cannot hand one over - the
            // per-call timeout is the only thing that differs from the client.
            val call = uploadClient.newCall(request)

            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)

            call.execute().use { response ->
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
                "($plainBytes plain) to $url"
        )
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

        // Last word on the pipeline's own output. The origin check in [offer]
        // already keeps a source that shares a host with the upload out of the
        // round, and that is the rule that matters, but it holds only because
        // this deployment publishes to the same host the reader fetches the
        // result from. Move the upload somewhere else - a mirror, a bucket, a
        // second origin - and the profile carrying the previous result becomes
        // an ordinary imported subscription and feeds itself back in, where a
        // round screens yesterday's survivors and republishes them, which is
        // how a pool that should shrink instead holds its ground.
        //
        // So the result is named directly as well as by host: a profile whose
        // source is the upload target itself is refused whatever host it is
        // served from. Kept last so it also covers a primary or an extra entry
        // that reached here by a route the origin rule missed.
        if (destination != null) {
            candidates.removeAll { sameTarget(it.url, uploadUrl) }
        }

        return candidates
    }

    /**
     * Whether [url] is the very document this round publishes to, compared
     * whole - scheme, host, port, path and query - rather than by host alone.
     *
     * Trailing slashes and case in the scheme and host are forgiven because
     * those never change what a server serves; the path and query are not,
     * since two documents on one host are two documents.
     */
    private fun sameTarget(url: String, uploadUrl: String): Boolean {
        val a = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val b = runCatching { URI(uploadUrl.trim()) }.getOrNull() ?: return false

        fun norm(u: URI) = listOf(
            u.scheme?.lowercase(),
            u.host?.lowercase(),
            u.port.takeIf { it >= 0 }?.toString() ?: "",
            u.path.orEmpty().trimEnd('/'),
            u.query.orEmpty(),
        )

        return norm(a) == norm(b)
    }

    /**
     * A URL reduced to what identifies the source without carrying its
     * credential: scheme, host and the first path segment, with any query or
     * fragment dropped.
     *
     * A subscription link is a secret - the path or the query on it is the
     * token the server checks - so it must never be written to a log that gets
     * attached to a bug report or pasted into a chat. The host is enough to
     * tell one source from another, and where there is only a token on the
     * root the source is named by its host, which is what a reader is asking
     * for anyway.
     */
    private fun redactedUrl(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "<unparsable>"

        val host = uri.host ?: return "<no host>"
        val first = uri.path.orEmpty().trim('/').substringBefore('/')

        return if (first.isEmpty()) "$host/" else "$host/$first"
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
        // Two spaced rounds, not three: the third round's only contribution was
        // time - a node that answers twice, ten seconds apart, on a link this
        // flaky is already the difference being looked for, and the round that
        // went to a third pass mostly pruned nodes that were still usable.
        const val ROUNDS = 2

        // Guards against a round that lost its sources overwriting a working
        // subscription: below the floor there is no history to compare against,
        // and a quarter of the previous count in one round is not attrition.
        const val COLLAPSE_FLOOR = 8L
        const val COLLAPSE_RATIO = 4L

        // Below this many candidate sources there is no quorum to speak of, so
        // "half of them answered" would be one source out of two - a round that
        // is thin by design rather than a reading of the pool.
        const val MIN_SOURCES = 4
        const val USER_AGENT = "ClashMetaForAndroid/1.0"
        const val MAX_CANDIDATE_BYTES = 8L * 1024 * 1024

        /**
         * How long one candidate may take in all, whatever the socket-level
         * timeouts allow. Long enough for a multi-megabyte subscription over a
         * slow link, short enough that a stalled source does not hold the round
         * for the sum of every read timeout it can take.
         */
        const val DOWNLOAD_TIMEOUT_MS = 90_000L

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
