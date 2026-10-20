package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.SpeedTestOptions

/**
 * The kind of measurement one group asks for.
 *
 * A group whose name says YouTube is the one the subscription's YouTube rules
 * point at, so its run proves what those rules promise: the three YouTube
 * reachability probes followed by a real transfer. Every other group is not
 * carrying video, so it only needs latency and a generic download; fetching
 * the YouTube pages there would spend traffic answering a question nobody
 * asked.
 *
 * The marker is a name, not a group list, because the group is created by the
 * screening template ([service/src/main/assets/template]) and has no flag of
 * its own to read: the emoji and the word are what a reader recognises too, so
 * a renamed group still fails loudly rather than silently testing the wrong
 * thing.
 */
object SpeedTestProfile {
    private val videoMarkers = listOf("油管", "youtube")

    /** Whether [group] is the group whose members are judged for video. */
    fun isVideoGroup(group: String): Boolean =
        videoMarkers.any { group.contains(it, ignoreCase = true) }

    /**
     * The options a run on [group] carries.
     *
     * [rounds] is the caller's: the toolbar answers now with one round, the
     * periodic pass ranks on two.
     */
    fun options(group: String, rounds: Int = 1): SpeedTestOptions {
        val video = isVideoGroup(group)

        return SpeedTestOptions(
            rounds = rounds,
            skipReachability = !video,
            requireReachability = video,
        )
    }
}
