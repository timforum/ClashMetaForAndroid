package com.github.kr328.clash.service.util

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.SpeedTestEnvelope

/**
 * One line per run naming which stage the failures died at, and how long the
 * run took.
 *
 * This exists because the cost of a round was a number nobody could act on. A
 * round over a pool of this size was measured at 15 minutes and at 47, and
 * both figures were consistent with two completely different problems: nodes
 * that never answer, which die in the latency probe, and nodes that answer but
 * have no working exit behind them, which spend the whole throughput budget
 * before failing. Capping the latency probe only fixes the first, and until
 * the two are told apart there is no way to know whether that cap bought
 * anything.
 *
 * The stage is already on every failed outcome as the prefix of its error -
 * `latency:`, `site:`, `edge:`, `watch:`, `throughput:` - so nothing has to
 * be reported from the core that it does not already say. A run that prints
 * `latency=152, throughput=8` has told you where the next hour went; one that
 * prints `latency=3, throughput=162` has told you the cap was the wrong lever.
 *
 * Written to debug level like the rest of this feature: a healthy pass prints
 * nothing worth reading, and this line only matters when a pool is unhealthy.
 */
fun logSpeedTestStages(group: String, envelope: SpeedTestEnvelope, elapsedMs: Long) {
    val total = envelope.results.size
    val passed = envelope.results.count { it.passed }

    val stages = envelope.results
        .filterNot { it.passed }
        .groupingBy { failureStage(it.error) }
        .eachCount()
        .entries
        .sortedByDescending { it.value }

    val breakdown = if (stages.isEmpty()) {
        ""
    } else {
        ", failures " + stages.joinToString { "${it.key}=${it.value}" }
    }

    Log.d(
        "speedtest: $group: ${elapsedMs / 1000}s, $total nodes, $passed passed$breakdown"
    )
}

/**
 * The stage an outcome died at, taken from the prefix the core writes into
 * every failure.
 *
 * Falls back to the whole message when it carries no prefix - a cancelled run
 * reports the context error alone - and to `other` when there is no message at
 * all, so a node that failed without saying why still appears in the count
 * rather than disappearing from the totals.
 */
private fun failureStage(error: String?): String {
    val message = error.orEmpty()

    if (message.isEmpty()) {
        return "other"
    }

    val prefix = message.substringBefore(':').trim()

    // A bare error can contain a colon without naming a stage, so only a short
    // word is taken as one.
    return if (prefix.isNotEmpty() && prefix.length <= 16 && prefix.none { it.isWhitespace() }) {
        prefix
    } else {
        "other"
    }
}