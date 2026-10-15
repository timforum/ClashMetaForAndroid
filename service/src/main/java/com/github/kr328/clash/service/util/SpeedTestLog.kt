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
 * `latency-dead=152, throughput=8` has told you where the next hour went; one
 * that prints `latency-dead=3, throughput=162` has told you the cap was the
 * wrong lever. The run also reports the spread of the delays it did measure,
 * which is what separates a pool that answers too slowly from one that does
 * not answer at all - both print as `latency` failures, and only one of them
 * is anything the probe budget can do anything about.
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

    // The delays the run did measure, because "nothing passed" and "every node
    // answered and every answer was over the limit" are opposite problems and
    // the failure counts alone cannot tell them apart - both are `latency`.
    // The spread says which one this was: a pool that answers in the seconds
    // is slow and the limit is what is refusing it, while a pool whose fastest
    // answer is zero has nothing to measure at all.
    //
    // [NO_DELAY] is left out on purpose. The core measures delay as a uint16
    // and reports its maximum for a node it never measured, so counting it
    // would put a pool of dead nodes at a median of 65535ms and say it was
    // slow rather than silent - the opposite of what happened to it.
    val answered = envelope.results
        .map { it.delay }
        .filter { it > 0 && it < NO_DELAY }
        .sorted()

    val delays = if (answered.isEmpty()) {
        ""
    } else {
        ", delay ${answered.first()}-${answered.last()}ms " +
            "median ${answered[answered.size / 2]}ms"
    }

    Log.d(
        "speedtest: $group: ${elapsedMs / 1000}s, $total nodes, $passed passed" +
            "$breakdown$delays"
    )
}

/**
 * The stage an outcome died at, taken from the prefix the core writes into
 * every failure.
 *
 * The latency prefix is split, because it covers two failures that need
 * opposite answers and read identically otherwise. A node whose probe never
 * came back and a node that answered in 1400ms are both reported as
 * `latency:`, yet the first is a dead exit and the cost of the run is what the
 * probe budget decides, while the second is a working node refused by the
 * limit - shortening the probe cannot help it and raising the limit would
 * admit it. Counting them together made a pool of slow nodes look like a pool
 * of dead ones.
 */
private fun failureStage(error: String?): String {
    val message = error.orEmpty()

    if (message.isEmpty()) {
        return "other"
    }

    val prefix = message.substringBefore(':').trim()

    // A bare error can contain a colon without naming a stage, so only a short
    // word is taken as one.
    if (prefix.isEmpty() || prefix.length > 16 || prefix.any { it.isWhitespace() }) {
        return "other"
    }

    if (prefix == "latency") {
        return if (SLOW_LIMIT_MARKER in message) "latency-slow" else "latency-dead"
    }

    return prefix
}

/** The wording the core uses when a node answered but was over the limit. */
private const val SLOW_LIMIT_MARKER = "ms limit"

/**
 * The largest value the core's delay can hold, and what it reports for a node
 * it has never measured.
 *
 * Delay travels as a uint16, so a node that never answered has nothing to
 * report and the field comes back at its ceiling. Taken at face value it is
 * the slowest reading a delay can have rather than the absence of one, and a
 * median built over it describes a pool of silent nodes as a pool of very slow
 * ones.
 */
private const val NO_DELAY = 65535