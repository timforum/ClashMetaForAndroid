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

/** Codec shared with the Go side; unknown keys are ignored so old cores stay readable. */
val SpeedTestJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
