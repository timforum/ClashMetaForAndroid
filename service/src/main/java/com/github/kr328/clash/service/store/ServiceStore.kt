package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.SpeedTestResults
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

    // How many nodes the last successful round published.
    //
    // A round that lost most of its sources still finishes and still has a
    // handful of survivors, and publishing that handful replaces a working
    // subscription with one that has almost nothing in it. Comparing against
    // this figure is what lets the next round say "this is a collapse, not a
    // screening result" instead of silently overwriting the pool.
    var probtestPublishedNodes by store.long(
        key = "probtest_published_nodes",
        defaultValue = 0L
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
     * Where a round hands its result when the direct upload failed on every
     * node it tried.
     *
     * Defaults to the sandbox the pool is served from: it accepts the same
     * request the service would have taken and forwards it from there, so the
     * leg that used to give out - a phone uplink writing straight to the
     * service - is replaced rather than merely repeated. Empty disables the
     * fallback, which is what a reader who does not own a relay wants.
     */
    var probtestRelayUrl by store.string(
        key = "probtest_relay_url",
        defaultValue = "https://5001-agqvkboy7ju4atfgk6rzc235zi.instabox.tech/internal/freeclashnode"
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

    /**
     * The group a measurement is in flight for, and when it started.
     *
     * Written by the worker in :background and read by the proxy screen: a run
     * started from the toolbar lives in the service process so that leaving
     * the screen cannot end it, which also means the screen can no longer know
     * about it from a local field. The timestamp is what tells a live mark
     * from one a killed process left behind.
     */
    var speedTestRunningGroup by store.string(
        key = "speed_test_running_group",
        defaultValue = ""
    )

    var speedTestRunningSince by store.long(
        key = "speed_test_running_since",
        defaultValue = 0L
    )

    /**
     * Whether [group] is being measured right now.
     *
     * A mark older than [SPEED_TEST_RUNNING_TTL_MS] is treated as gone: the
     * worker clears its mark when a run ends, but a process killed mid-run
     * cannot, and a spinner that never stops is worse than a spinner that
     * stops late. The timestamp is therefore refreshed by the worker while it
     * works (see [SpeedTestWorker.heartbeat]), which is what keeps the window
     * short without a long round flickering back to idle mid-measurement.
     */
    fun isSpeedTestRunning(group: String, now: Long = System.currentTimeMillis()): Boolean {
        if (speedTestRunningGroup != group) {
            return false
        }

        val since = speedTestRunningSince

        return since > 0 && now - since in 0 until SPEED_TEST_RUNNING_TTL_MS
    }

    fun clearSpeedTestRunning(group: String = "") {
        // Only the group that owns the mark may clear it, so turning one
        // group's switch off cannot wipe the mark of another group's run.
        if (group.isNotEmpty() && speedTestRunningGroup != group) {
            return
        }

        speedTestRunningGroup = ""
        speedTestRunningSince = 0L
    }

    var speedTestIntervalMinutes by store.long(
        key = "speed_test_interval_minutes",
        defaultValue = 30L
    )

    /**
     * What one screening pass actually cost the last time it ran, in
     * milliseconds, as the estimate the next interval is derived from.
     *
     * Zero means no pass has finished yet, and the interval is then whatever
     * was asked for. This is the only honest answer to "how often can this be
     * done": the setting says how often somebody would like it, and this says
     * how long the device actually takes, which depends on how many groups are
     * enabled and how many of their nodes are dead enough to sit out a full
     * timeout. A pool of 174 nodes with most of them unreachable measured for
     * 36 minutes, so no setting below that prevents the rounds from running
     * back to back.
     */
    var speedTestRoundCostMs by store.long(
        key = "speed_test_round_cost_ms",
        defaultValue = 0L
    )

    /**
     * How many nodes the last screening pass actually measured, across every
     * enabled group. Zero means no pass has finished yet.
     *
     * Kept so the interval can be worked out before any pass has run: the
     * number of nodes is what makes a round expensive, and asking a reader to
     * sit through one 36 minute round before the device stops asking for more
     * often than a round takes is the protection arriving too late to be
     * protection.
     */
    var speedTestRoundNodes by store.long(
        key = "speed_test_round_nodes",
        defaultValue = 0L
    )

    /**
     * The structural bound on a screening pass: what it would cost if every
     * single node took the whole time cap.
     *
     * `per node cap x nodes`, divided by the gate's concurrency and multiplied
     * by its round count, because those are what actually decide the wall
     * clock: the gate measures [SPEED_TEST_ROUNDS_PER_NODE] rounds of each
     * node, [SPEED_TEST_CONCURRENCY] at a time, and a node cannot exceed
     * [SPEED_TEST_PER_NODE_TIMEOUT_MS] however dead it is. Left in without
     * those two factors it overstates a 174 node pass by three times, which is
     * not caution but simply a wrong number.
     *
     * This is a ceiling, not a prediction. A healthy pool clears most nodes
     * in a second or two through the early exit, so a real pass lands well
     * under it; what it guarantees is that a pool which is *mostly* dead - the
     * case that actually happens - cannot come back sooner than its own
     * timeout budget allows.
     */
    fun structuralSpeedTestRoundMs(): Long {
        val nodes = speedTestRoundNodes

        if (nodes <= 0L) {
            return 0L
        }

        val measurements = nodes * SPEED_TEST_ROUNDS_PER_NODE
        val batches = (measurements + SPEED_TEST_CONCURRENCY - 1) / SPEED_TEST_CONCURRENCY

        return batches * SPEED_TEST_PER_NODE_TIMEOUT_MS
    }

    /**
     * Folds the cost of a finished pass into the estimate.
     *
     * A pass that cost more than the estimate replaces it outright, because
     * the one thing an estimate must never do is promise a shorter period
     * than the work takes. A cheaper pass only halves the difference, so a
     * single slow afternoon - a link that was down, a pool that had just been
     * replaced - forgets itself within a few rounds instead of stretching
     * every interval afterwards.
     */
    fun recordSpeedTestRound(elapsedMs: Long, nodes: Long) {
        if (nodes > 0L) {
            speedTestRoundNodes = nodes
        }

        if (elapsedMs <= 0) {
            return
        }

        // Capped so a pass that hung for a day cannot put the interval out of
        // reach of the setting; the next round brings the estimate back down.
        val cost = elapsedMs.coerceAtMost(MAX_SPEED_TEST_ROUND_COST_MS)
        val previous = speedTestRoundCostMs

        speedTestRoundCostMs =
            if (previous <= 0 || cost >= previous) cost else (previous + cost) / 2
    }

    /**
     * The interval the alarm is really armed for: what was asked for, unless
     * that is shorter than a pass can take, in which case the pass decides.
     *
     * Two things decide the floor, and they answer different questions. The
     * measured cost is what a pass *did* take; the structural bound is what it
     * could take if every node used its whole timeout. A pool that was fine
     * today would otherwise still be held to a bad afternoon's figure for
     * weeks, so once there is a measurement the measurement wins. Before there
     * is one - which is exactly when the protection is needed first - the
     * structural bound stands in, worked out from the node count, so a reader
     * is not made to sit through a pass that overshoots its period before the
     * device stops overshooting.
     *
     * The headroom is what keeps the rounds from becoming one long run. At
     * least [SPEED_TEST_ROUND_HEADROOM_PERCENT] percent of the period is left
     * empty between passes, which is what gives the watch - and whatever else
     * the phone is doing - room to use the network, rather than the device
     * measuring itself without a break and spending the data either way.
     *
     * The asked-for value is kept as it is rather than overwritten: it is
     * still what the reader chose, and raising it again on the next pass
     * costs nothing. What runs is [effectiveSpeedTestIntervalMs], and it is
     * that number the log and the settings screen report.
     */
    fun effectiveSpeedTestIntervalMinutes(): Long {
        val asked = speedTestIntervalMinutes.coerceAtLeast(MIN_SPEED_TEST_INTERVAL_MINUTES)

        val observed = speedTestRoundCostMs
        val basis = if (observed > 0L) observed else structuralSpeedTestRoundMs()

        if (basis <= 0L) {
            return asked
        }

        // Rounded up, with a minute to spare, so the period is never merely
        // equal to the work and then short by the length of one alarm.
        val needed = basis * SPEED_TEST_ROUND_HEADROOM_PERCENT / 100L / 60_000L + 1L

        return asked.coerceAtLeast(needed)
    }

    /**
     * Which of the two floors is in force, for the settings screen to name.
     * "measured" means the interval is following a real pass; "structural"
     * means nothing has been timed yet and the node count is standing in.
     */
    fun speedTestIntervalBasis(): String =
        if (speedTestRoundCostMs > 0L) "measured" else "structural"

    fun effectiveSpeedTestIntervalMs(): Long =
        effectiveSpeedTestIntervalMinutes() * 60_000L

    // 主页抽屉的「在线监测」开关（.74）。打开：SPEEDTEST_WATCH 以固定 10 秒
    // 节奏盯每个开着的组当下骑的节点；关闭：watch 闹钟整体不排（组的全量轮、
    // 视频快速轮、筛选发布都不受它影响）。写在 UI 进程、由 :background 的接收
    // 器与 worker 读取，与 speed_test_groups 同一条多进程偏好。旧版每分钟
    // watch 的分钟数设置随之退役——这把开关就是它唯一的节奏。
    var speedTestWatchOnline by store.boolean(
        key = "speed_test_watch_online",
        defaultValue = false
    )

    /**
     * How often the video group's fast pass re-runs its short list.
     *
     * The pass is deliberately cheap (a handful of names, one round, a small
     * throughput cap each), so its rhythm can sit far tighter than the full
     * hourly round it rides beside. Five minutes is the floor and the
     * default: an inexact alarm already drifts a couple of minutes per
     * window, and below that the pass would spend the hour chasing itself.
     */
    var speedTestVideoIntervalMinutes by store.long(
        key = "speed_test_video_interval_minutes",
        defaultValue = 5L
    )

    /**
     * How wide the video group's fast pass is: the node in use plus this
     * many of the best measured. The short list is what keeps a five-minute
     * rhythm cheap - a name costs a latency probe at the least, a capped
     * transfer at the most - and the hourly full round remains what re-ranks
     * the long tail the list never looks at.
     */
    var speedTestVideoRoundSize by store.long(
        key = "speed_test_video_round_size",
        defaultValue = 10L
    )

    /**
     * The enabled groups the last pass did not find in the running
     * configuration, kept so a second pass can confirm they are really gone.
     *
     * A profile update swaps the configuration out from under the core, and a
     * pass landing inside that window reads a list that is empty or half
     * built. Believing it on the first miss would delete every toggle the user
     * had set, so a name has to be missing twice before it is dropped, and
     * this set is how the two passes agree. A group that reappears is simply
     * absent from it next time and is forgotten.
     */
    var speedTestMissingGroups by store.stringSet(
        key = "speed_test_missing_groups",
        defaultValue = emptySet()
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

    /**
     * What every node scored in the last round, so the proxy screen can show a
     * figure beside every node rather than only beside the one that won.
     *
     * Deliberately not keyed by group: a node's throughput does not depend on
     * which group measured it, and a pool reached through two groups would
     * otherwise show two different numbers for the same node.
     */
    fun speedTestResults(): SpeedTestResults {
        val raw = prefs.getString(speedTestResultsKey, "") ?: ""

        if (raw.isEmpty()) {
            return SpeedTestResults()
        }

        return try {
            SpeedTestResultsJson.decodeFromString(SpeedTestResults.serializer(), raw)
        } catch (e: Exception) {
            SpeedTestResults()
        }
    }

    fun setSpeedTestResults(results: SpeedTestResults) {
        prefs.edit()
            .putString(
                speedTestResultsKey,
                SpeedTestResultsJson.encodeToString(SpeedTestResults.serializer(), results)
            )
            .apply()
    }

    private fun speedTestSelectionKey(group: String) = "speed_test_selection:$group"

    private val speedTestResultsKey = "speed_test_results"

    /**
     * Where the last failover for [group] stopped sweeping its members.
     *
     * Only matters when the group has no measured nodes to rank, so there is
     * nothing to walk and the only candidates left are the members
     * themselves. Taking the first few of those every time would probe the
     * same dead nodes for as long as the pool stays broken: none of them ever
     * had a figure, so none of them ever leave the queue, and a standby that
     * answers sits untouched behind them. The cursor is what turns one pass's
     * guess into a sweep that gets a little further every pass.
     */
    fun speedTestFailoverCursor(group: String): Int =
        prefs.getString(speedTestFailoverCursorKey(group), "")?.toIntOrNull() ?: 0

    fun setSpeedTestFailoverCursor(group: String, cursor: Int) {
        prefs.edit()
            .putString(speedTestFailoverCursorKey(group), cursor.toString())
            .apply()
    }

    private fun speedTestFailoverCursorKey(group: String) =
        "speed_test_failover_cursor:$group"

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
        // The shortest period the setting may ask for. A mistyped 2 would
        // otherwise ask the device to re-measure the whole configuration
        // constantly, which costs traffic without buying a fresher answer.
        const val MIN_SPEED_TEST_INTERVAL_MINUTES = 5L

        // How much of the period a pass may occupy, in percent. The remaining
        // third is what stops two rounds from running back to back.
        const val SPEED_TEST_ROUND_HEADROOM_PERCENT = 150L

        // Rounds the screening takes of each node. A single lucky transfer must
        // not decide which node the whole app connects through, so the worst of
        // the two is the score - and the cost of a round is what it does to
        // the interval.
        const val SPEED_TEST_ROUNDS_PER_NODE = 2L

        // How many nodes the gate measures at once. Parallel transfers compete
        // for the device's own uplink, so this stays well below the latency
        // rounds' cap - and it is also the divisor that keeps the structural
        // bound honest.
        const val SPEED_TEST_CONCURRENCY = 6L

        // The gate's own per-node wall clock cap: the throughput stage is given
        // the whole of this budget after the reachability probes, and a node
        // that has not answered within it is dropped however dead it is. This
        // is the "one node cannot take longer than" the structural bound is
        // built from.
        const val SPEED_TEST_PER_NODE_TIMEOUT_MS = 60_000L

        // Ceiling on what one pass is believed to have cost, so a round that
        // hung cannot stretch the interval beyond a few hours on its own.
        const val MAX_SPEED_TEST_ROUND_COST_MS = 6L * 60L * 60L * 1000L

        // How long a running mark may stand before the screen stops believing
        // it. The worker clears its own mark when a run ends; this only covers
        // a process killed mid-run, which cannot clear anything.
        const val SPEED_TEST_RUNNING_TTL_MS = 90L * 1000L

        private val SpeedTestSelectionJson = Json {
            ignoreUnknownKeys = true
        }

        private val SpeedTestResultsJson = Json {
            ignoreUnknownKeys = true
        }

        private val SpeedTestWatchJson = Json {
            ignoreUnknownKeys = true
        }
    }
}