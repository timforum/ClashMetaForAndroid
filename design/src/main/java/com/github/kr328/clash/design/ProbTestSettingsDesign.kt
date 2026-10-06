package com.github.kr328.clash.design

import android.content.Context
import android.net.Uri
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.probtest.ProbTestProgress
import com.github.kr328.clash.service.probtest.Stage
import com.github.kr328.clash.service.store.ServiceStore

class ProbTestSettingsDesign(
    context: Context,
    srvStore: ServiceStore,
) : Design<ProbTestSettingsDesign.Request>(context) {
    enum class Request {
        RunNow,
        PickExtraSubFile,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private lateinit var indicator: ProgressPreference

    /**
     * A round runs in the service process and can take minutes, so the screen
     * shows what the worker reports instead of leaving the tap looking inert.
     */
    fun showProgress(state: ProbTestProgress) {
        indicator.view.visibility = View.VISIBLE

        // Restores the title a previous showResult() may have overwritten with
        // a success or failure label.
        indicator.title = context.getText(R.string.probtest_status)

        when (state.stage) {
            Stage.DOWNLOADING -> {
                if (state.total > 0) {
                    indicator.summary = context.getString(
                        R.string.probtest_progress_downloading,
                        state.done,
                        state.total,
                    )
                    indicator.setProgress(state.done, state.total)
                } else {
                    indicator.summary = context.getString(R.string.probtest_downloading)
                    indicator.setIndeterminate()
                }
            }
            Stage.SCREENING -> {
                if (state.rounds > 0) {
                    indicator.summary = context.getString(
                        R.string.probtest_progress_screening,
                        state.round,
                        state.rounds,
                        state.passed,
                        state.failed,
                    )
                    indicator.setProgress(state.round, state.rounds)
                } else {
                    indicator.summary = context.getString(R.string.probtest_screening)
                    indicator.setIndeterminate()
                }
            }
            Stage.SPEED_TEST -> {
                if (state.total > 0) {
                    indicator.summary = context.getString(
                        R.string.probtest_progress_speed,
                        state.done,
                        state.total,
                        state.passed,
                        state.failed,
                    )
                    indicator.setProgress(state.done, state.total)
                } else {
                    indicator.summary = context.getString(R.string.probtest_speed)
                    indicator.setIndeterminate()
                }
            }
            Stage.PUBLISHING -> {
                indicator.summary = context.getString(R.string.probtest_publishing)
                indicator.setIndeterminate()
            }
            else -> {
                // A stage this build does not know about is still a running
                // round, so show the bar rather than dropping the information.
                indicator.summary = context.getString(R.string.probtest_running)
                indicator.setIndeterminate()
            }
        }
    }

    fun hideProgress() {
        indicator.view.visibility = View.GONE
    }

    /**
     * Leaves the outcome of a round on screen in place of the bar.
     *
     * Swapping the bar out rather than hiding it is the point: a round that
     * dies on the first subscription download lasts a fraction of a second, and
     * a screen that simply goes back to being blank gives the user no way to
     * tell a failed run from a button that never fired.
     */
    fun showResult(published: Boolean, summary: String) {
        indicator.view.visibility = View.VISIBLE

        indicator.title = context.getText(
            if (published) R.string.probtest_success else R.string.probtest_failure
        )

        indicator.setMessage(summary.ifBlank { context.getString(R.string.probtest_running) })
    }

    /**
     * Paints whatever the worker left behind.
     *
     * [ServiceStore.probtestState] holds the latest in-flight snapshot, but the
     * worker clears [ServiceStore.probtestLastResult] the moment a new round
     * starts and rewrites it when one finishes. That makes the last result a
     * reliable "is a round actually running" signal: when it is set, no round
     * is in flight, so [probtestState] is a stale leftover from a round that
     * already ended (its finish broadcast simply never reached a screen that
     * was open) and must not be repainted as a live bar. When the last result
     * is blank a round really is running, so the in-flight snapshot is shown.
     */
    fun restoreProgress() {
        val store = ServiceStore(context)

        val lastResult = store.probtestLastResult
        if (lastResult.isNotEmpty()) {
            val separator = lastResult.indexOf('|')
            val published = separator > 0 && lastResult.substring(0, separator) == "true"
            val summary = if (separator >= 0) lastResult.substring(separator + 1) else lastResult

            showResult(published, summary)

            return
        }

        val progress = ProbTestProgress.decode(store.probtestState)

        if (progress == null) {
            hideProgress()
        } else {
            showProgress(progress)
        }
    }

    private lateinit var extraSubFileRow: ClickablePreference

    /**
     * Shows the file the user picked as the "Extra subscriptions" source, or
     * the hint when none is set. The picker hands back a content uri, so the
     * row reports the file name rather than the raw uri.
     */
    fun updateExtraSubFile(uri: String?) {
        extraSubFileRow.summary = when {
            uri.isNullOrBlank() -> context.getString(R.string.probtest_extra_sub_file_summary)
            else -> {
                val name = Uri.parse(uri).lastPathSegment ?: uri
                context.getString(R.string.probtest_extra_sub_file_set, name)
            }
        }
    }

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val dependencies: MutableList<Preference> = mutableListOf()

        val screen = preferenceScreen(context) {
            switch(
                value = srvStore::probtestEnabled,
                icon = R.drawable.ic_baseline_search,
                title = R.string.probtest_enabled,
                summary = R.string.probtest_enabled_summary,
                card = true,
            ) {
                listener = OnChangedListener {
                    dependencies.forEach { it.enabled = srvStore.probtestEnabled }
                }
            }

            // Sits directly under the switch so the effect of tapping "run a
            // round now" is visible where the tap happened.
            clickable(
                title = R.string.probtest_run_now,
                icon = R.drawable.ic_baseline_replay,
                summary = R.string.probtest_run_now_summary,
                card = true,
            ) {
                clicked {
                    requests.trySend(Request.RunNow)
                }

                // Deliberately not added to dependencies. A manual run has to
                // work with the schedule switched off, otherwise the only way
                // to test a configuration is to turn on a recurring job first.
                // ProbTestWorker re-arms nothing in that case, so a one-off run
                // does not silently start the interval.
            }

            indicator = progress(R.string.probtest_status, card = true) {
                view.visibility = View.GONE
            }

            tips(R.string.probtest_tips, card = true)

            category(R.string.probtest_sources, card = true)

            editableText(
                value = srvStore::probtestTestUrl,
                adapter = NullableTextAdapter.Text,
                title = R.string.probtest_primary_sub,
                icon = R.drawable.ic_baseline_domain,
                placeholder = R.string.probtest_primary_sub_summary,
                card = true,
                configure = dependencies::add,
            )

            switch(
                value = srvStore::probtestIncludeImported,
                icon = R.drawable.ic_outline_inbox,
                title = R.string.probtest_include_imported,
                summary = R.string.probtest_include_imported_summary,
                card = true,
                configure = dependencies::add,
            )

            editableText(
                value = srvStore::probtestCandidates,
                adapter = NullableTextAdapter.Text,
                title = R.string.probtest_candidates,
                icon = R.drawable.ic_baseline_view_list,
                placeholder = R.string.probtest_candidates_summary,
                card = true,
                configure = dependencies::add,
            )

            extraSubFileRow = clickable(
                title = R.string.probtest_extra_sub_file,
                icon = R.drawable.ic_outline_folder,
                card = true,
                configure = {
                    dependencies.add(this)
                    clicked {
                        requests.trySend(Request.PickExtraSubFile)
                    }
                },
            )

            updateExtraSubFile(srvStore.probtestExtraSubFile)

            category(R.string.probtest_schedule, card = true)

            editableText(
                value = srvStore::probtestIntervalMinutes,
                adapter = NullableTextAdapter.Number,
                title = R.string.probtest_interval,
                icon = R.drawable.ic_outline_update,
                placeholder = R.string.probtest_interval_summary,
                card = true,
                configure = dependencies::add,
            )

            editableText(
                value = srvStore::probtestRoundGapSeconds,
                adapter = NullableTextAdapter.Number,
                title = R.string.probtest_round_gap,
                icon = R.drawable.ic_baseline_swap_vert,
                placeholder = R.string.probtest_round_gap_summary,
                card = true,
                configure = dependencies::add,
            )

            category(R.string.probtest_github, card = true)

            editableText(
                value = srvStore::probtestGitHubToken,
                adapter = NullableTextAdapter.Text,
                title = R.string.probtest_github_token,
                icon = R.drawable.ic_baseline_key,
                placeholder = R.string.probtest_github_token_summary,
                card = true,
                configure = {
                    // The row must never show the token itself; the editor still
                    // opens on the stored value so it can be changed, not retyped.
                    password = true
                    dependencies.add(this)
                },
            )

            editableText(
                value = srvStore::probtestGitHubRepo,
                adapter = NullableTextAdapter.Text,
                title = R.string.probtest_github_repo,
                icon = R.drawable.ic_outline_article,
                placeholder = R.string.probtest_github_repo_summary,
                card = true,
                configure = dependencies::add,
            )

            editableText(
                value = srvStore::probtestGitHubBranch,
                adapter = NullableTextAdapter.Text,
                title = R.string.probtest_github_branch,
                icon = R.drawable.ic_outline_label,
                placeholder = R.string.probtest_github_branch_summary,
                card = true,
                configure = dependencies::add,
            )

            editableText(
                value = srvStore::probtestGitHubPath,
                adapter = NullableTextAdapter.Text,
                title = R.string.probtest_github_path,
                icon = R.drawable.ic_outline_folder,
                placeholder = R.string.probtest_github_path_summary,
                card = true,
                configure = dependencies::add,
            )

            category(R.string.probtest_service, card = true)

            editableText(
                value = srvStore::probtestUploadUrl,
                adapter = NullableTextAdapter.Text,
                title = R.string.probtest_upload_url,
                icon = R.drawable.ic_baseline_publish,
                placeholder = R.string.probtest_upload_url_summary,
                card = true,
                configure = dependencies::add,
            )

            editableText(
                value = srvStore::probtestUploadToken,
                adapter = NullableTextAdapter.Text,
                title = R.string.probtest_upload_token,
                icon = R.drawable.ic_baseline_key,
                placeholder = R.string.probtest_upload_token_summary,
                card = true,
                configure = {
                    password = true
                    dependencies.add(this)
                },
            )
        }

        screen.shrinkText()

        binding.content.addView(screen.root)

        // Everything below the master switch is meaningless while screening is
        // off, so start from the stored value instead of the default.
        dependencies.forEach { it.enabled = srvStore.probtestEnabled }

        restoreProgress()
    }
}
