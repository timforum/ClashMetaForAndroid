package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

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

    // The primary candidate subscription a round screens. Required; the probe
    // target itself always falls back to the core default (Cloudflare).
    var probtestTestUrl by store.string(
        key = "probtest_test_url",
        defaultValue = ""
    )

    var probtestRoundGapSeconds by store.long(
        key = "probtest_round_gap_seconds",
        defaultValue = 20L
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
}