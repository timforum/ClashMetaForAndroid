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
    srvStore: ServiceStore,
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
        }

        screen.shrinkText()

        binding.content.addView(screen.root)
    }
}
