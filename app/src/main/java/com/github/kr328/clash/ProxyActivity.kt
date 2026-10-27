package com.github.kr328.clash

import android.view.KeyEvent
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.service.SpeedTestReceiver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.SpeedTestProfile
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Taken here rather than from a focused view so it works whichever part
        // of the screen the remote happens to be sitting on.
        if (design?.handlePageKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN) == true)
            return true

        return super.dispatchKeyEvent(event)
    }

    override suspend fun main() {
        val mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        val names = pinnedGroupOrder(withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) })
        val states = List(names.size) { ProxyState("?") }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)

        // Multi-process preferences: the toggle's set is read by the periodic
        // worker in :background, so it cannot live in the UI-only ui store.
        val serviceStore = ServiceStore(this)

        val design = ProxyDesign(
            this,
            mode,
            names,
            uiStore,
            serviceStore
        )

        setContentDesign(design)

        design.requests.send(ProxyDesign.Request.ReloadAll)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded -> {
                            val newNames = pinnedGroupOrder(withClash {
                                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                            })

                            if (newNames != names) {
                                startActivity(ProxyActivity::class.intent)

                                finish()
                            }
                        }
                        // A round in :background wrote new figures into the
                        // multi-process preferences this process cannot see on
                        // its own, so every tab is reloaded rather than only the
                        // one on screen: the other tabs are one tap away and
                        // would otherwise show the previous round until then.
                        Event.SpeedTestResultsChanged -> {
                            design.requests.send(ProxyDesign.Request.ReloadAll)
                        }
                        Event.SpeedTestRunningChanged -> {
                            // Only the toolbar's state changed: repainting the
                            // toggle is enough, and reloading the group would
                            // query the core again for a value that did not
                            // move.
                            design.refreshSpeedTestButton()
                        }
                        Event.ActivityStart -> {
                            // Coming back to the screen is the moment a round
                            // that ran while the activity was stopped becomes
                            // visible: the broadcasts it sent were missed, so
                            // the toggle is repainted from the mark and the
                            // group re-read in case a selection moved.
                            design.refreshSpeedTestButton()
                            design.reloadVisible()
                        }
                        is Event.SpeedTestFinished -> {
                            val index = names.indexOf(it.group)

                            if (index >= 0) {
                                // The round may have moved the selection and
                                // written figures for every node it measured,
                                // so the group is read again rather than the
                                // repaint alone.
                                design.requests.send(ProxyDesign.Request.Reload(index))
                            }

                            if (!it.ok) {
                                launch { design.showSpeedTestFailed() }
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(ProxyActivity::class.intent)

                            finish()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            names.indices.forEach { idx ->
                                design.requests.trySend(ProxyDesign.Request.Reload(idx))
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                val groupName = names[it.index]
                                val group = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(groupName, uiStore.proxySort)
                                    }
                                }
                                val state = states[it.index]

                                state.now = group.now

                                design.updateGroup(
                                    it.index,
                                    pinSelectorMembers(group.proxies),
                                    group.type == "Selector",
                                    state,
                                    unorderedStates
                                )
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            withClash {
                                patchSelector(names[it.index], it.name)

                                states[it.index].now = it.name
                            }

                            design.requestRedrawVisible()
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                withClash {
                                    healthCheck(names[it.index])
                                }

                                design.requests.send(ProxyDesign.Request.Reload(it.index))
                            }
                        }
                        is ProxyDesign.Request.WatchOnline -> {
                            // Global switch, so unlike the per-group toggle
                            // there is no activity-held mark to repaint: the
                            // menu's checkmark is redrawn at the next open,
                            // and scheduleWatch turns the alarm on or off
                            // right here, whichever side the flip landed on.
                            val on = !serviceStore.speedTestWatchOnline

                            serviceStore.speedTestWatchOnline = on

                            SpeedTestReceiver.scheduleWatch(this@ProxyActivity)
                        }
                        is ProxyDesign.Request.SpeedTest -> {
                            val index = it.index
                            val group = names[index]
                            val enabling = group !in serviceStore.speedTestGroups

                            serviceStore.speedTestGroups =
                                if (enabling)
                                    serviceStore.speedTestGroups + group
                                else
                                    serviceStore.speedTestGroups - group

                            if (enabling) {
                                // Measured by the service rather than by this
                                // screen: walking back to the group list must
                                // not end the round, so the run cannot belong
                                // to an activity that can be closed. The
                                // spinner comes back through the running mark
                                // for the same reason.
                                //
                                // The tint is repainted now because the mark
                                // only arrives once the worker actually starts:
                                // a request queued behind another round would
                                // otherwise leave the toggle looking untouched,
                                // which reads as a press that did nothing.
                                design.refreshSpeedTestButton()

                                SpeedTestReceiver.requestGroup(this@ProxyActivity, group)
                            } else {
                                // Off means off: release the mark this group's
                                // run holds so the spinner stops now rather
                                // than when the measurement it interrupts
                                // returns.
                                serviceStore.clearSpeedTestRunning(group)

                                design.refreshSpeedTestButton()

                                if (serviceStore.speedTestGroups.isEmpty()) {
                                    // Nothing is enabled, so nothing is on its
                                    // way out: the names a pass flagged as gone
                                    // would otherwise still be sitting there
                                    // waiting to condemn a group the user turns
                                    // back on.
                                    serviceStore.speedTestMissingGroups = emptySet()

                                    SpeedTestReceiver.cancelNext(this@ProxyActivity)
                                    SpeedTestReceiver.cancelWatch(this@ProxyActivity)

                                    // The fast pass belongs to the video group
                                    // alone, so switching it off takes its
                                    // alarm down with it instead of leaving a
                                    // broadcast that would arrive, find no
                                    // video group and die without re-arming.
                                    SpeedTestReceiver.cancelVideo(this@ProxyActivity)
                                } else {
                                    SpeedTestReceiver.scheduleNext(this@ProxyActivity)
                                    SpeedTestReceiver.scheduleWatch(this@ProxyActivity)
                                    // Re-evaluated rather than cancelled: if
                                    // the group just switched off was not the
                                    // video group, the call re-arms the same
                                    // window; if it was, the call discovers
                                    // there is nothing left to schedule and
                                    // takes the alarm down.
                                    SpeedTestReceiver.scheduleVideo(this@ProxyActivity)
                                }
                            }
                        }
                        is ProxyDesign.Request.PatchMode -> {
                            design.showModeSwitchTips()

                            withClash {
                                val o = queryOverride(Clash.OverrideSlot.Session)

                                o.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Session, o)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Pins the steering groups to the front of the tab strip.
     *
     * The proxy selector, auto, fallback and load-balance decide where traffic
     * actually goes, and the video group is the one watched ten seconds at a
     * time, so all five belong within a thumb's reach no matter where the
     * subscription happened to list them. They are pinned in the declared
     * order — the proxy selector, auto, fallback, load-balance, YouTube — with
     * every other group following right behind in its original order,
     * untouched: re-sorting what the profile author arranged would only make
     * the strip jump between profile updates.
     *
     * A name is claimed by the first rule that fits, so a group matching two
     * rules is pinned once at the higher slot and never rescued by the lower.
     * Both languages are matched because the same profile can arrive with
     * either: 节点选择/自动选择/故障转移/负载均衡 and auto/fallback/loadbalance
     * name the same ideas. The video group reuses [SpeedTestProfile]'s own
     * marker so the tab the fast pass steers and the tab this pins stay one
     * thing rather than drifting apart.
     */
    private fun pinnedGroupOrder(names: List<String>): List<String> {
        val rules: List<(String) -> Boolean> = listOf(
            {
                n ->
                n.contains("节点选择", true)
                    || n.contains("手动切换", true)
                    || n.contains("手动选择", true)
            },
            { n -> n.contains("自动选择", true) || n.contains("auto", true) },
            { n -> n.contains("故障转移", true) || n.contains("fallback", true) },
            { n -> n.contains("负载均衡", true) || n.contains("loadbalance", true) },
            SpeedTestProfile::isVideoGroup,
        )

        val pinned = List(rules.size) { mutableListOf<String>() }
        val rest = mutableListOf<String>()

        outer@ for (name in names) {
            for (rule in rules.indices) {
                if (rules[rule](name)) {
                    pinned[rule].add(name)
                    continue@outer
                }
            }

            rest.add(name)
        }

        return pinned.flatten() + rest
    }

    /**
     * Pins the steering groups to the top of the proxy selector's member list.
     *
     * The groups a subscriber picks between when steering traffic by hand —
     * the proxy selector opens on every subscription in the same four — auto,
     * fallback, load-balance, YouTube — are members of the selection page
     * like any node, but they mean infrastructure rather than a country, and
     * they sit wherever the subscription listed them among hundreds of flags.
     * Opening the selector page therefore means scrolling to find the one row
     * that switches every other page at once. They are pinned to the top in
     * the declared order, and the remaining members follow in whatever order
     * the query left them (the reader's own sort choice applied),
     * unrecognised names untouched.
     *
     * Every group's member list is treated alike rather than only the
     * selection: subscriptions name that page differently — 节点选择 here,
     * 手动切换 or plain PROXY elsewhere — and keying on a name is what let
     * half the subscriptions keep their steering rows buried. The list is
     * harmless where the members are all nodes: the two-part test (it is a
     * group, and its name matches) pins nothing in a group that carries no
     * sub-groups, so a server whose address happens to say auto or youtube
     * keeps its place still. A name is claimed by the first rule that fits,
     * so a member matching two rules is pinned once at the higher slot. The
     * video marker is [SpeedTestProfile]'s own, so the row that turns the
     * video group and the group the fast pass steers stay one thing.
     */
    private fun pinSelectorMembers(proxies: List<Proxy>): List<Proxy> {
        val rules: List<(String) -> Boolean> = listOf(
            { n -> n.contains("自动选择", true) || n.contains("auto", true) },
            { n ->
                n.contains("故障转移", true)
                    || n.contains("故障切换", true)
                    || n.contains("fallback", true)
            },
            { n ->
                n.contains("负载均衡", true)
                    || n.contains("负荷均衡", true)
                    || n.contains("loadbalance", true)
                    || n.contains("load balance", true)
                    || n.contains("load-balance", true)
            },
            SpeedTestProfile::isVideoGroup,
        )

        val pinned = List(rules.size) { mutableListOf<Proxy>() }
        val rest = mutableListOf<Proxy>()

        outer@ for (proxy in proxies) {
            if (!proxy.isGroup) {
                rest.add(proxy)

                continue
            }

            for (rule in rules.indices) {
                if (rules[rule](proxy.name)) {
                    pinned[rule].add(proxy)

                    continue@outer
                }
            }

            rest.add(proxy)
        }

        return pinned.flatten() + rest
    }
}
