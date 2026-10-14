package com.github.kr328.clash.service.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The standing outcome of a group's last speed screening: which node won and
 * which one stands behind it.
 *
 * [default] is the node the group should select while it keeps passing;
 * [backup] is the standby the supervisor switches to the moment [default]
 * stops clearing the gate. [mbps] is what the winner scored, kept so a later
 * run can tell a slow "still the best of a bad lot" from a genuinely healthy
 * node. Written by both the proxy screen's toggle and the periodic worker,
 * so it lives in the multi-process service preferences.
 */
@Serializable
data class SpeedTestSelection(
    val default: String,
    val backup: String = "",
    @SerialName("mbps") val mbps: Double = 0.0,
    @SerialName("updatedAt") val updatedAt: Long = 0,
)

/**
 * What every node of the last round scored, not only the one that won.
 *
 * A round measures a whole group and then throws all of it away except the
 * winner's figure, which is what the ranking needs and is not what a reader
 * needs: the question on the proxy screen is "how fast is this one compared to
 * the rest", and that is unanswerable while only the top of the list survives.
 *
 * Keyed by node name rather than by group because throughput is a property of
 * the node, not of the group that happened to be measured. A node reached
 * through 🇺🇲 美国节点 and the same node reached through ♻️ 自动选择 carry the
 * same credential and the same exit, and a second round over an overlapping
 * group would otherwise report a different figure for it.
 */
@Serializable
data class SpeedTestResults(
    val mbps: Map<String, Double> = emptyMap(),
    /**
     * When the round that produced these ran. Every entry shares it, because
     * they are written by the same round; it is carried so a reader - and a
     * future staleness rule - can tell a fresh figure from one left over.
     */
    @SerialName("updatedAt") val updatedAt: Long = 0,
)
