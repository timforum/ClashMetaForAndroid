package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Input for a live group speed run.
 *
 * Field names mirror `groupSpeedRequest` in `core/src/main/golang/native/speedtest.go`.
 * Zero values mean the core's gate defaults apply, so an empty options object
 * is a valid request and the two sides cannot drift apart on limits.
 */
@Serializable
data class SpeedTestOptions(
    /**
     * Repetitions per node; the worst round is the node's score. One round is
     * what the interactive toggle uses to answer now, the periodic supervisor
     * runs more so the ranking rests on something less lucky.
     */
    val rounds: Int = 1,
    @SerialName("floorMbps") val floorMbps: Double = 0.0,
    @SerialName("maxBytes") val maxBytes: Long = 0,
    @SerialName("maxTimeMs") val maxTimeMs: Long = 0,
    val concurrency: Int = 0,
    @SerialName("throughputUrl") val throughputUrl: String = "",
    /**
     * Latency limit a node must clear before it may be ranked: a node whose
     * delay probe answered slower than this is dropped even when the exit
     * itself worked and carried bytes, because the delay the proxy screen
     * shows is exactly the number a reader judges a node by. Zero defers to
     * the core's own default, the same limit the candidate rounds use.
     */
    @SerialName("maxDelayMs") val maxDelayMs: Long = 500,
    /**
     * Endpoint the latency probe measures against. Empty keeps each node's
     * own, the URL its displayed delay was measured against, so a fresh
     * measurement lands in the same history the proxy screen reads and
     * refreshes the number it shows.
     */
    @SerialName("testUrl") val testUrl: String = "",
    /**
     * Narrows a run to one named node, which is what the periodic watch of
     * the node the connection is on asks: whether that one node still
     * answers and still carries, at the cost of measuring one node instead
     * of the whole group.
     */
    @SerialName("only") val only: String = "",
    /**
     * Drops the YouTube reachability probes from one measurement, leaving
     * latency plus throughput. The watch re-measures often: re-fetching
     * those pages every pass would spend more traffic on the check than
     * the node serves between checks.
     */
    @SerialName("skipReachability") val skipReachability: Boolean = false,
)

/** Emitted once per finished node while a run is in flight. */
@Serializable
data class SpeedTestProgress(
    val group: String,
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val rounds: Int = 0,
    val passed: Int = 0,
    val failed: Int = 0,
    /** The node that just finished; absent when the core omits empty values. */
    val current: String? = null,
    @SerialName("elapsedMs") val elapsedMs: Long = 0,
)

/** Outcome for a single measured node of the group. */
@Serializable
data class SpeedTestOutcome(
    val name: String,
    val delay: Int = 0,
    /**
     * Group a selection may be written to for this node: its own direct parent
     * when that parent can hold a selection, absent when no group along the way
     * can. The node may sit behind a sub group, so the group asked about is not
     * necessarily the one that owns the node.
     */
    val owner: String? = null,
    /**
     * Groups on the way from the group that was asked about down to this node's
     * direct parent, outermost first. A selector only accepts one of its own
     * members, so a node behind a sub group cannot be reached in a single
     * patch: every group in here has to be moved in turn for the switch to
     * actually take effect.
     */
    val path: List<String>? = null,
    /** Megabits per second; absent when the node failed the gate. */
    val mbps: Double? = null,
    /** Human tier label (e.g. 1080p/720p/360p/240p) derived from [mbps]. */
    val tier: String? = null,
    val error: String? = null,
) {
    val passed: Boolean
        get() = error == null && (mbps ?: 0.0) > 0.0
}

/**
 * The single envelope `nativeSpeedTestGroup` returns, whether the run
 * produced a ranking or a reason why it could not.
 */
@Serializable
data class SpeedTestEnvelope(
    val ok: Boolean,
    val error: String? = null,
    val group: String? = null,
    val results: List<SpeedTestOutcome> = emptyList(),
    /** Fastest node that cleared the gate — the default connection node. */
    val best: String? = null,
    /** Next fastest distinct node — the standby that takes over on failure. */
    val backup: String? = null,
)

/** One node's share of the traffic the core is carrying right now. */
@Serializable
data class NodeTraffic(
    val name: String,
    /**
     * Cumulative bytes attributed to this node since the core's monitor first
     * looked at it, so subtracting a previous snapshot gives the bytes that
     * moved over the window between the two reads. Bytes a node carried
     * through connections that closed in between are included: the core
     * counted them while they were still open.
     */
    @SerialName("bytes") val bytes: Long = 0,
    /** Bytes sitting on the connections still open through this node. */
    @SerialName("live") val live: Long = 0,
    /** How many connections are open through this node right now. */
    @SerialName("conns") val conns: Int = 0,
    /** How long the longest-running of those has been going. */
    @SerialName("oldestMs") val oldestMs: Long = 0,
)

/**
 * The single envelope `nativeMonitorGroup` returns: the per-node share of
 * the traffic the core is carrying, plus the core's own running totals so a
 * reader can tell "nothing flowed anywhere" from "traffic flowed but not
 * through this node".
 */
@Serializable
data class NodeTrafficEnvelope(
    val ok: Boolean,
    val error: String? = null,
    val group: String? = null,
    /**
     * Leaf proxy the connection is on behind this group, reached by following
     * the selection down. For a group whose members are other groups the
     * group's own `now` answers with the sub group it routes through, which is
     * not a node the gate could measure, so the watcher reads this instead.
     */
    @SerialName("inUse") val inUse: String? = null,
    val nodes: List<NodeTraffic> = emptyList(),
    @SerialName("liveBytes") val liveBytes: Long = 0,
    @SerialName("totalUp") val totalUp: Long = 0,
    @SerialName("totalDown") val totalDown: Long = 0,
)

/** Codec shared with the Go side; unknown keys are ignored so old cores stay readable. */
val SpeedTestJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
