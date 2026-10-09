package com.github.kr328.clash.service.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The watch state of one group: what the core's traffic monitor last saw
 * flowing through the node the connection was on, and the node the last
 * switch left behind.
 *
 * [bytesAt] is the cumulative byte count the monitor reported for [node] at
 * [atMs], so the next watch subtracts it to get the traffic that moved over
 * the window between the two reads — a node that demonstrably carried the
 * connection needs no probe at all, which is what lets the watch run every
 * couple of minutes without spending traffic on the check. [switchedFrom]
 * and [switchedAt] name the node a switch left behind and when, which is
 * what keeps a node that failed once from being picked again the next
 * moment and ping-ponging the connection between two nodes. Written by the
 * watch worker and read back across processes, so it lives in the
 * multi-process service preferences like [SpeedTestSelection].
 */
@Serializable
data class SpeedTestWatchState(
    val node: String = "",
    @SerialName("bytesAt") val bytesAt: Long = 0,
    @SerialName("atMs") val atMs: Long = 0,
    @SerialName("switchedFrom") val switchedFrom: String = "",
    @SerialName("switchedAt") val switchedAt: Long = 0,
)
