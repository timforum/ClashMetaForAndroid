package com.github.kr328.clash

import android.view.KeyEvent
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.SpeedTestEnvelope
import com.github.kr328.clash.core.model.SpeedTestJson
import com.github.kr328.clash.core.model.SpeedTestOptions
import com.github.kr328.clash.core.patchSelectorPath
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.service.SpeedTestReceiver
import com.github.kr328.clash.service.model.SpeedTestResults
import com.github.kr328.clash.service.model.SpeedTestSelection
import com.github.kr328.clash.service.store.ServiceStore
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
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
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
                            val newNames = withClash {
                                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                            }

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
                                val group = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                }
                                val state = states[it.index]

                                state.now = group.now

                                design.updateGroup(
                                    it.index,
                                    group.proxies,
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
                                launch {
                                    runSpeedTest(index, group, states, serviceStore, design)
                                }
                            } else {
                                // Off means off: release the mark the toggle claimed,
                                // and let the set decide whether a round is still due.
                                design.setSpeedTestRunning(index, false)

                                if (serviceStore.speedTestGroups.isEmpty()) {
                                    // Nothing is enabled, so nothing is on its
                                    // way out: the names a pass flagged as gone
                                    // would otherwise still be sitting there
                                    // waiting to condemn a group the user turns
                                    // back on.
                                    serviceStore.speedTestMissingGroups = emptySet()

                                    SpeedTestReceiver.cancelNext(this@ProxyActivity)
                                    SpeedTestReceiver.cancelWatch(this@ProxyActivity)
                                } else {
                                    SpeedTestReceiver.scheduleNext(this@ProxyActivity)
                                    SpeedTestReceiver.scheduleWatch(this@ProxyActivity)
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
     * Measures one group from the toolbar toggle: keeps the ranking for the
     * periodic supervisor, then moves the selector onto the winner so the
     * connection starts on the fastest node that passed.
     *
     * The running mark is released in a finally so a failure cannot leave the
     * toolbar spinning; a failed run simply reports the toast and keeps the
     * switch on, since the supervisor will retry it anyway.
     */
    private suspend fun runSpeedTest(
        index: Int,
        group: String,
        states: List<ProxyState>,
        store: ServiceStore,
        design: ProxyDesign,
    ) {
        try {
            val options = SpeedTestJson.encodeToString(
                SpeedTestOptions.serializer(),
                SpeedTestOptions()
            )

            val envelope = SpeedTestJson.decodeFromString(
                SpeedTestEnvelope.serializer(),
                withClash { speedTestGroup(group, options) }
            )

            val best = envelope.best

            if (!envelope.ok || best.isNullOrEmpty()) {
                design.showSpeedTestFailed()

                return
            }

            val top = envelope.results.firstOrNull { r -> r.name == best }

            // The whole list, not just the winner: the proxy screen shows a
            // figure beside every node the round measured, and this is the
            // only moment those figures exist.
            mergeResults(
                store,
                envelope.results.mapNotNull { r ->
                    r.mbps?.takeIf { r.passed }?.let { r.name to it }
                }.toMap()
            )

            store.setSpeedTestSelection(
                group,
                SpeedTestSelection(
                    default = best,
                    backup = envelope.backup.orEmpty(),
                    mbps = top?.mbps ?: 0.0,
                    updatedAt = System.currentTimeMillis(),
                )
            )

            // A node behind a sub group cannot be picked from the group above
            // it: Set names a member, so the whole route down to the node has
            // to be moved, not just the group that holds it.
            val path = top?.path ?: listOf(group)

            // The core is only loaded in the service process, so a switch asked
            // for here has to travel there and back. Going straight to Clash
            // instead would patch an empty core and report a move that nothing
            // ever makes.
            val moved = withClash {
                patchSelectorPath(
                    group,
                    best,
                    path,
                    { name -> queryProxyGroup(name, ProxySort.Default) },
                    { from, name -> patchSelector(from, name) },
                )
            }.orEmpty()

            if (moved.isNotEmpty()) {
                Log.d(
                    "speedtest: $group: switched to '$best' " +
                        "via ${moved.joinToString(" -> ")}"
                )

                // What the group itself now routes to: the sub group it was
                // pointed at, or the node itself when it holds it directly.
                states[index].now = path.getOrElse(1) { best }

                design.requestRedrawVisible()
            }

            // First round of the set is this one, already done; the supervisor
            // takes over from here on the configured interval, and so does the
            // tighter watch of the node this run just connected through.
            SpeedTestReceiver.scheduleNext(this)
            SpeedTestReceiver.scheduleWatch(this)        } catch (e: Exception) {
            design.showSpeedTestFailed()
        } finally {
            design.setSpeedTestRunning(index, false)

            design.requests.send(ProxyDesign.Request.Reload(index))
        }
    }

    /**
     * Folds one round's figures into what is already stored.
     *
     * Merged rather than replaced: a pass that measures one group must not
     * erase what the periodic pass over the others measured, and a node the
     * round did not reach this time keeps the last figure anyone took, which
     * is a number about that node rather than about this group.
     */
    private fun mergeResults(
        store: ServiceStore,
        measured: Map<String, Double>,
    ) {
        if (measured.isEmpty()) {
            return
        }

        val merged = store.speedTestResults().mbps.toMutableMap()

        measured.forEach { (name, mbps) -> merged[name] = mbps }

        store.setSpeedTestResults(SpeedTestResults(merged, System.currentTimeMillis()))
    }
}