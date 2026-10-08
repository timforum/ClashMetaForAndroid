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
