package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Input for a candidate subscription probe.
 *
 * Field names mirror `probTestRequest` in `core/src/main/golang/native/probtest.go`
 * and durations are milliseconds because that is how they cross the JNI boundary.
 */
@Serializable
data class ProbTestOptions(
    val testUrl: String = "",
    val rounds: Int = 3,
    @SerialName("roundGapMs") val roundGapMs: Long = 20_000,
    @SerialName("roundTimeoutMs") val roundTimeoutMs: Long = 10_000,
    val concurrency: Int = 16,
    val expectStatus: String = "",
    /**
     * The subconverter style template that decides which groups the surviving
     * nodes fill and where each rule set sends its traffic. Empty keeps the
     * fixed built in layout the core publishes when no template is offered.
     */
    @SerialName("templateIni") val templateIni: String = "",
    /**
     * The rule files the template's ruleset lines name, keyed by the file name
     * their URLs end in. Referenced but absent files fail the round in the core
     * rather than being skipped here, so a partial bundle is never published.
     */
    @SerialName("ruleFiles") val ruleFiles: Map<String, String> = emptyMap(),
)

/** Emitted once per round while a probe is running. */
@Serializable
data class ProbTestProgress(
    val round: Int,
    val rounds: Int,
    @SerialName("elapsedMs") val elapsedMs: Long,
    val passed: Int,
    val failed: Int,
    val total: Int,
    val rejected: Int,
)

/** Outcome for a single candidate node. */
@Serializable
data class ProbTestNode(
    val name: String,
    val type: String,
    val passes: Int = 0,
    val delays: List<Int> = emptyList(),
    val error: String? = null,
    val rejected: Boolean = false,
)

@Serializable
data class ProbTestReport(
    val rounds: Int,
    @SerialName("roundGapMs") val roundGapMs: Long,
    @SerialName("testUrl") val testUrl: String,
    val total: Int,
    val survivors: Int,
    val rejected: Int,
    @SerialName("elapsedMs") val elapsedMs: Long,
    val nodes: List<ProbTestNode> = emptyList(),
)

/** Why one candidate subscription contributed nothing to the merged document. */
@Serializable
data class ProbTestSkip(
    val index: Int,
    val reason: String,
)

/** The single envelope `nativeProbTest` returns, whether the run succeeded or not. */
@Serializable
data class ProbTestEnvelope(
    val ok: Boolean,
    val error: String? = null,
    val yaml: String? = null,
    val report: ProbTestReport? = null,
    val skipped: List<ProbTestSkip> = emptyList(),
    @SerialName("proxiesYaml") val proxiesYaml: String? = null,
) {
    val configuration: String?
        get() = yaml?.takeIf { ok }

    /**
     * The surviving nodes on their own, with no groups and no rules, for a
     * service that merges nodes into its own pool.
     */
    val proxiesDocument: String?
        get() = proxiesYaml?.takeIf { ok }
}
