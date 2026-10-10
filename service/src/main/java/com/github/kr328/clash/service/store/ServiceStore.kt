package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.SpeedTestSelection
import com.github.kr328.clash.service.model.SpeedTestWatchState
import kotlinx.serialization.json.Json
import java.util.*

class ServiceStore(context: Context) {
    private val prefs = PreferenceProvider.createSharedPreferencesFromContext(context)

    private val store = Store(prefs.asStoreProvider())

    var activeProfile: UUID? by store.typedString(
        key = "active_profile",
        from = { if (it.isBlank()) null else UUID.fromString(it) },
        to = { it?.toString() ?: "" }
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptAll,
        values = AccessControlMode.values()
    )

    var accessControlPackages by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    var dnsHijacking by store.boolean(
        key = "dns_hijacking",
        defaultValue = true
    )

    var systemProxy by store.boolean(
        key = "system_proxy",
        defaultValue = true
    )

    var allowBypass by store.boolean(
        key = "allow_bypass",
        defaultValue = true
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "mips"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = true
    )

    // Candidate subscription screening. All keys are read by the worker running
    // in the :background process and written by the settings screen in the UI
    // process, which is why they live in the multi-process "service" preferences.
    var probtestEnabled by store.boolean(
        key = "probtest_enabled",
        defaultValue = false
    )

    var probtestIntervalMinutes by store.long(
        key = "probtest_interval_minutes",
        defaultValue = 30L
    )

    var probtestIncludeImported by store.boolean(
        key = "probtest_include_imported",
        defaultValue = true
    )

    // Newline separated URLs of extra candidate subscriptions.
    var probtestCandidates by store.string(
        key = "probtest_candidates",
        defaultValue = ""
    )

    // A file on the phone (a content:// uri from the document picker) that
    // holds one subscription URL per line, the counterpart of the inline
    // candidates list. Stored as a string so the worker can read it back across
    // processes. Empty means no file is attached.
    var probtestExtraSubFile by store.string(
        key = "probtest_extra_sub_file",
        defaultValue = ""
    )

    // The primary candidate subscription a round screens.
    var probtestTestUrl by store.string(
        key = "probtest_test_url",
        defaultValue = ""
    )

    // What the latency rounds measure against. The built in target is
    // Cloudflare's generate_204, which a pool of any size ends up overloading:
    // its first round passes and its second is refused outright, which reads as
    // every node dying at once. Empty keeps that default.
    var probtestProbeUrl by store.string(
        key = "probtest_probe_url",
        defaultValue = ""
    )

    // The last few probe targets, newest first, one per line. Kept so the
    // editor can offer them without the field having to be retyped, and so a
    // target that stops answering can be swapped away from in one tap.
    var probtestProbeUrlHistory by store.string(
        key = "probtest_probe_url_history",
        defaultValue = ""
    )

    var probtestRoundGapSeconds by store.long(
        key = "probtest_round_gap_seconds",
        defaultValue = 20L
    )

    // How many nodes one delay round probes at the same time. The figure is a
    // compromise in both directions and neither end of the range is right for
    // every pool: too low and a round over a few thousand nodes takes most of
    // an hour, too high and the phone opens connections faster than the exits
    // behind them can answer, which arrives as timeouts rather than as
    // slowness and costs perfectly good nodes. Sixteen held a pool of about
    // 1800, but a larger one needs fewer at a time and a link that is itself
    // slow needs more.
    var probtestConcurrency by store.long(
        key = "probtest_concurrency",
        defaultValue = 16L
    )

    var probtestGitHubToken by store.string(
        key = "probtest_github_token",
        defaultValue = ""
    )

    // "owner/name"
    var probtestGitHubRepo by store.string(
        key = "probtest_github_repo",
        defaultValue = ""
    )

    var probtestGitHubBranch by store.string(
        key = "probtest_github_branch",
        defaultValue = "main"
    )

    var probtestGitHubPath by store.string(
        key = "probtest_github_path",
        defaultValue = "clash/config.yaml"
    )

    // Direct destination of a screening run. Preferred over GitHub because a
    // subscription service reads the result straight away, while a repository
    // would only be picked up by whatever else is configured to pull from it.
    var probtestUploadUrl by store.string(
        key = "probtest_upload_url",
        defaultValue = ""
    )

    var probtestUploadToken by store.string(
        key = "probtest_upload_token",
        defaultValue = ""
    )

    /**
     * The last progress a round reported, encoded as a
     * [com.github.kr328.clash.service.probtest.ProbTestProgress]. The worker
     * writes it and the settings screen reads it back, so a screen opened
     * halfway through a round can paint the bar instead of waiting for the
     * next broadcast. Empty means no round is in flight.
     */
    var probtestState by store.string(
        key = "probtest_state",
        defaultValue = ""
    )

    /**
     * The outcome of the last finished round, encoded as
     * `published|summary`. The worker writes it when a round ends and clears
     * it when a new one starts, so a screen that opens between rounds can show
     * the previous result instead of a stale in-flight snapshot that
     * [probtestState] may still hold. Empty means no round has finished yet.
     */
    var probtestLastResult by store.string(
        key = "probtest_last_result",
        defaultValue = ""
    )

    // WebDAV backup destination. The base URL must end with "/" when a remote
    // path is also given; the final file name is appended by the backup manager.
    var webdavUrl by store.string(
        key = "webdav_url",
        defaultValue = ""
    )

    var webdavUsername by store.string(
        key = "webdav_username",
        defaultValue = ""
    )

    var webdavPassword by store.string(
        key = "webdav_password",
        defaultValue = ""
    )

    // Folder the backup file is written to on the server. May be empty, which
    // means the root of the WebDAV base URL.
    var webdavPath by store.string(
        key = "webdav_path",
        defaultValue = ""
    )

    // Actual-transfer speed screening of live groups. The set is written by the
    // proxy screen's toolbar toggle in the UI process and read by the periodic
    // worker in :background, so like the probtest keys it lives in the
    // multi-process "service" preferences. Each enabled group also carries a
    // SpeedTestSelection naming the default node and the standby that takes
    // over when the default stops answering.
    var speedTestGroups by store.stringSet(
        key = "speed_test_groups",
        defaultValue = emptySet()
    )

    var speedTestIntervalMinutes by store.long(
        key = "speed_test_interval_minutes",
        defaultValue = 30L
    )

    // How often the watch of the node each enabled group is connected through
    // re-checks it. Deliberately far tighter than speedTestIntervalMinutes:
    // the watch is the cheap half (a free traffic snapshot per group, plus a
    // probe only when that snapshot says something is wrong), and a stalled
    // connection is what a reader notices first.
    var speedTestWatchMinutes by store.long(
        key = "speed_test_watch_minutes",
        defaultValue = 2L
    )

    /**
     * The last ranking for [group]: which node won, which one stands behind it,
     * and how fast the winner was. Keyed per group because each group keeps its
     * own default and its own standby. Empty means the group has never been
     * measured (or was reset).
     */
    fun speedTestSelection(group: String): SpeedTestSelection? {
        val raw = prefs.getString(speedTestSelectionKey(group), "") ?: ""

        if (raw.isEmpty())
            return null

        return try {
            SpeedTestSelectionJson.decodeFromString(SpeedTestSelection.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    /** Pass null to forget the ranking of [group]. */
    fun setSpeedTestSelection(group: String, selection: SpeedTestSelection?) {
        val editor = prefs.edit()

        if (selection == null) {
            editor.remove(speedTestSelectionKey(group))
        } else {
            editor.putString(
                speedTestSelectionKey(group),
                SpeedTestSelectionJson.encodeToString(SpeedTestSelection.serializer(), selection)
            )
        }

        editor.apply()
    }

    private fun speedTestSelectionKey(group: String) = "speed_test_selection:$group"

    /**
     * The watch state of [group]: what the core's traffic monitor last saw
     * flowing through its current node, and the node the last switch left
     * behind. Empty means the group has never been watched (or was reset).
     */
    fun speedTestWatchState(group: String): SpeedTestWatchState? {
        val raw = prefs.getString(speedTestWatchStateKey(group), "") ?: ""

        if (raw.isEmpty())
            return null

        return try {
            SpeedTestWatchJson.decodeFromString(SpeedTestWatchState.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    /** Pass null to forget the watch state of [group]. */
    fun setSpeedTestWatchState(group: String, state: SpeedTestWatchState?) {
        val editor = prefs.edit()

        if (state == null) {
            editor.remove(speedTestWatchStateKey(group))
        } else {
            editor.putString(
                speedTestWatchStateKey(group),
                SpeedTestWatchJson.encodeToString(SpeedTestWatchState.serializer(), state)
            )
        }

        editor.apply()
    }

    private fun speedTestWatchStateKey(group: String) = "speed_test_watch_state:$group"

    companion object {
        private val SpeedTestSelectionJson = Json {
            ignoreUnknownKeys = true
        }

        private val SpeedTestWatchJson = Json {
            ignoreUnknownKeys = true
        }
    }
}