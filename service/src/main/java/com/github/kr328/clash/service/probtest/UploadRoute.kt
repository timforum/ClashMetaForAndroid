package com.github.kr328.clash.service.probtest

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.patchSelectorPath
import com.github.kr328.clash.service.StatusProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves the profile the tunnel is running onto a node of the caller's choice,
 * then puts the selection back.
 *
 * A screening round's upload leaves the phone through whichever node the
 * profile happens to be on. The round cannot measure that node's upload speed
 * the way it measures the candidates, and a node that carries a download
 * perfectly well can still drop a long write, so the one part of the path the
 * round can change from here is the exit itself. This borrows the route for the
 * duration of one request and restores it afterwards: the reader picked a node,
 * and the round moving it for good would be a second decision nobody asked for.
 *
 * The group names are the ones the bundled template generates, because that is
 * what a published profile is built from. A profile with other names yields no
 * route here, which leaves the caller retrying on the route it already had
 * rather than aiming at a group that does not exist.
 */
internal class UploadRoute {
    /** The groups the rules' fallthrough uses, most specific name first. */
    private val rootNames = listOf("🚀 节点选择", "PROXY", "GLOBAL", "Proxy")

    /** How deep a group may nest inside another before the walk gives up. */
    private val maxDepth = 8

    /** A ceiling on the routes examined, since groups may be reachable twice. */
    private val maxRoutes = 400

    /** State of every group on the chain, taken before the first patch. */
    private var saved: List<Pair<String, String>>? = null

    /**
     * The live nodes this install has a measured figure for, fastest first.
     *
     * [skip] is the node already carrying the tunnel, since switching to it
     * would spend an attempt on the route that just failed. A figure is only a
     * hint that a node was good once, so the caller verifies each name can
     * actually be reached before using it.
     */
    suspend fun ranked(measured: Map<String, Double>, skip: String, limit: Int): List<String> =
        withContext(Dispatchers.IO) {
            val root = rootGroup() ?: return@withContext emptyList()
            val live = leaves(root)

            measured.entries
                .asSequence()
                .filter { it.value > 0.0 && it.key != skip && it.key in live }
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
                .toList()
        }

    /** The leaf the tunnel is on right now, or empty when nothing is running. */
    suspend fun inUse(): String = withContext(Dispatchers.IO) {
        val root = rootGroup() ?: return@withContext ""

        // Followed here rather than read from the group's own field: the query
        // the app makes answers with the selection of each group, and the leaf
        // is what those selections add up to.
        var name = root
        val seen = mutableSetOf<String>()

        repeat(maxDepth) {
            if (!seen.add(name)) {
                return@withContext name
            }

            val next = query(name).now

            if (next.isEmpty() || next == name) {
                return@withContext name
            }

            name = next
        }

        name
    }

    /** True when traffic now leaves through [node]. */
    suspend fun moveTo(node: String): Boolean = withContext(Dispatchers.IO) {
        // Without the tunnel there is no core to aim, and the upload is not
        // going through a node in the first place.
        if (!StatusProvider.vpnRunning) {
            return@withContext false
        }

        val root = rootGroup() ?: return@withContext false
        val chain = chainTo(root, node) ?: return@withContext false

        if (saved == null) {
            saved = chain.map { it to query(it).now }
        }

        val moved = patchSelectorPath(root, node, chain, ::query, ::patch)

        if (moved == null) {
            Log.w("probtest: cannot aim '$root' at '$node'")

            return@withContext false
        }

        Log.i(
            "probtest: upload now leaves through '$node' " +
                "(${moved.joinToString()})"
        )

        true
    }

    /** Puts every group back the way [moveTo] found it. */
    suspend fun restore() = withContext(Dispatchers.IO) {
        val states = saved

        saved = null

        if (states == null) {
            return@withContext
        }

        // Deepest first: the group that holds the node has to be back on its
        // old member before the group above it is pointed away again.
        for ((group, now) in states.asReversed()) {
            if (now.isEmpty() || query(group).now == now) {
                continue
            }

            if (!patch(group, now)) {
                Log.w("probtest: could not put '$group' back on '$now'")
            }
        }
    }

    private fun rootGroup(): String? {
        val names = runCatching { Clash.queryGroupNames(excludeNotSelectable = true) }
            .getOrNull()
            .orEmpty()

        return rootNames.firstOrNull { it in names }
            ?: names.firstOrNull { it.contains("节点选择") }
            ?: names.firstOrNull { it.equals("proxy", ignoreCase = true) }
    }

    /**
     * The chain of groups from [root] down to the one that lists [node].
     *
     * A selector only accepts its own members, so a node three groups down
     * cannot be picked from the top: every hop between has to be pointed at the
     * one below it first, which is what the returned chain is for.
     *
     * Every route is collected rather than the first one found, and the plainest
     * wins: a node usually sits in several groups at once - the manual one, the
     * automatic one and its country's - and moving the automatic one leaves a
     * group that picks its own member pinned to whatever the upload used until
     * its next test. Going through groups that only hold a selection makes the
     * way back exact.
     */
    private fun chainTo(root: String, node: String): List<String>? {
        val pending = ArrayDeque<List<String>>()
        var best: List<String>? = null
        var examined = 0

        pending.addLast(listOf(root))

        while (pending.isNotEmpty() && examined++ < maxRoutes) {
            val chain = pending.removeFirst()

            if (chain.size > maxDepth) {
                continue
            }

            val group = query(chain.last())

            if (group.proxies.any { it.name == node }) {
                if (best == null || preference(chain) < preference(best)) {
                    best = chain
                }

                // Groups below this one cannot offer a shorter way here.
                continue
            }

            group.proxies
                .filter { it.isGroup && it.name !in chain }
                .forEach { pending.addLast(chain + it.name) }
        }

        return best
    }

    /** Lower is better: the fewest groups that are not plain selectors, then the shortest. */
    private fun preference(chain: List<String>): Int =
        chain.count { query(it).type != "Selector" } * 10 + chain.size

    /** Every leaf node reachable through [root], sub groups walked into. */
    private fun leaves(root: String): Set<String> {
        val found = mutableSetOf<String>()
        val seen = mutableSetOf(root)
        val pending = ArrayDeque<String>()

        pending.addLast(root)

        while (pending.isNotEmpty()) {
            val group = query(pending.removeFirst())

            for (member in group.proxies) {
                if (member.isGroup) {
                    if (seen.add(member.name)) {
                        pending.addLast(member.name)
                    }
                } else {
                    found += member.name
                }
            }
        }

        return found
    }

    private fun query(name: String): ProxyGroup =
        runCatching { Clash.queryGroup(name, ProxySort.Default) }
            .getOrDefault(ProxyGroup("Unknown", emptyList(), ""))

    private fun patch(from: String, to: String): Boolean =
        runCatching { Clash.patchSelector(from, to) }.getOrDefault(false)
}
