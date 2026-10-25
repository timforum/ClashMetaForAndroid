package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ServiceStore

/**
 * The screening rhythms side by side: how often the candidate
 * subscriptions go through the delay rounds, how often the live groups the
 * toolbar toggled get re-measured for real transfer, and how often the node
 * each of those groups is connected through is watched. All are plain
 * minutes on the shared service store, so this screen only edits them.
 */
class ProbTestIntervalsDesign(
    context: Context,
    private val srvStore: ServiceStore,
) : Design<Unit>(context) {
    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            category(R.string.probtest_intervals)

            editableText(
                value = srvStore::probtestIntervalMinutes,
                adapter = NullableTextAdapter.Number,
                title = R.string.probtest_delay_interval,
                icon = R.drawable.ic_outline_update,
                placeholder = R.string.probtest_delay_interval_summary,
                card = true,
            )

            editableText(
                value = srvStore::speedTestIntervalMinutes,
                adapter = NullableTextAdapter.Number,
                title = R.string.probtest_speed_interval,
                icon = R.drawable.ic_baseline_speed,
                placeholder = R.string.probtest_speed_interval_summary,
                card = true,
            )

            editableText(
                value = srvStore::speedTestWatchMinutes,
                adapter = NullableTextAdapter.Number,
                title = R.string.probtest_watch_interval,
                icon = R.drawable.ic_baseline_sync,
                placeholder = R.string.probtest_watch_interval_summary,
                card = true,
            )

            editableText(
                value = srvStore::speedTestVideoIntervalMinutes,
                adapter = NullableTextAdapter.Number,
                title = R.string.probtest_video_interval,
                icon = R.drawable.ic_baseline_speed,
                placeholder = R.string.probtest_video_interval_summary,
                card = true,
            )

            editableText(
                value = srvStore::speedTestVideoRoundSize,
                adapter = NullableTextAdapter.Number,
                title = R.string.probtest_video_round_size,
                icon = R.drawable.ic_baseline_sync,
                placeholder = R.string.probtest_video_round_size_summary,
                card = true,
            )
        }

        // The speed interval is not the number that runs, so the number that
        // runs is reported next to it. Left unsaid, a reader who typed 5 and
        // is told nothing sees rounds arriving far less often than they asked
        // for and has no way to tell the device is protecting them from
        // measuring itself flat out.
        screen.tips(
            text = R.string.probtest_speed_interval_effective_unknown,
            card = true,
        ).also { tip ->
            tip.text = effectiveIntervalText(context)
        }

        screen.shrinkText()

        binding.content.addView(screen.root)
    }

    /**
     * What the speed interval actually resolves to right now, said in the
     * units it is set in rather than as a raw millisecond count.
     */
    private fun effectiveIntervalText(context: Context): CharSequence {
        val observed = srvStore.speedTestRoundCostMs
        val structural = srvStore.structuralSpeedTestRoundMs()
        val nodes = srvStore.speedTestRoundNodes

        // Nothing has been timed and no node count is known either: there is
        // genuinely nothing to say but that the value above is what runs.
        if (observed <= 0L && structural <= 0L) {
            return context.getText(R.string.probtest_speed_interval_effective_unknown)
        }

        val asked = srvStore.speedTestIntervalMinutes
            .coerceAtLeast(ServiceStore.MIN_SPEED_TEST_INTERVAL_MINUTES)
        val effective = srvStore.effectiveSpeedTestIntervalMinutes()

        val cost = if (observed > 0L) {
            humanDuration(observed)
        } else {
            context.getString(
                R.string.probtest_speed_interval_bound,
                nodes.toString(),
                humanDuration(structural),
            )
        }

        return if (effective == asked) {
            context.getString(
                R.string.probtest_speed_interval_effective,
                "$effective min",
                cost,
            )
        } else {
            context.getString(
                R.string.probtest_speed_interval_effective_raised,
                "$effective min",
                "$asked min",
                cost,
            )
        }
    }

    /** Rounded to something a person would say: "36 min", "2 h 10 min". */
    private fun humanDuration(ms: Long): String {
        val totalMinutes = (ms + 30_000L) / 60_000L

        if (totalMinutes < 60L) {
            return "$totalMinutes min"
        }

        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L

        return if (minutes == 0L) "$hours h" else "$hours h $minutes min"
    }
}
