package com.github.kr328.clash.core

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.ProxyGroup

/**
 * The group types that answer Set with one of their own members, so a route
 * through them can be aimed at a specific node. A loadbalance picks per
 * connection and a relay chains on its own, so a switch through either of
 * those cannot be made to take effect at all.
 */
private val SELECTABLE_GROUP_TYPES = setOf("Selector", "Fallback", "URLTest")

/**
 * Moves every group on [path] so traffic through [group] reaches [target] and
 * returns the groups that were moved.
 *
 * A selector only accepts one of its own members, so a node behind a sub group
 * cannot be picked from the group above it: the outer group is pointed at the
 * sub group, that one at the one below it, and so on until the group that
 * actually holds [target]. Patching only the group that holds the node would
 * move a branch no connection travels, which looks like a successful switch
 * while nothing changes.
 *
 * A hop is skipped when it already points where it needs to, which keeps a
 * switch inside the current branch a single patch and leaves groups that are
 * already right alone.
 *
 * Every hop is checked before anything is moved. A loadbalance in the middle
 * cannot be aimed, so the switch is abandoned whole rather than half applied.
 *
 * The core is only loaded in the service process, so the two calls this needs
 * are handed in rather than made here: the caller sitting in that process
 * passes [Clash] itself, and a caller in any other process passes whatever
 * reaches it.
 *
 * Returns the groups that were moved, empty when the route already reaches the
 * target, or null when nothing happened: an unknown route, a hop that cannot
 * hold a selection, or a group refusing a name it does not list as a member.
 * The reason is logged at debug level, except the refusal which is a warning.
 */
fun patchSelectorPath(
    group: String,
    target: String,
    path: List<String>,
    query: (String) -> ProxyGroup,
    patch: (String, String) -> Boolean,
): List<String>? {
    if (path.isEmpty() || path.first() != group) {
        Log.d("speedtest: $group: no known route from this group to '$target'")

        return null
    }

    for (hop in path) {
        val type = query(hop).type

        if (type !in SELECTABLE_GROUP_TYPES) {
            Log.d(
                "speedtest: $group: route to '$target' crosses '$hop' " +
                    "($type), which cannot be aimed"
            )

            return null
        }
    }

    val moved = mutableListOf<String>()
    var from = group

    for (hop in path.drop(1)) {
        if (query(from).now != hop) {
            if (!patch(from, hop)) {
                Log.w("speedtest: $group: selector refused '$hop' in '$from'")

                return null
            }

            moved += from
        }

        from = hop
    }

    if (query(from).now != target) {
        if (!patch(from, target)) {
            Log.w("speedtest: $group: selector refused '$target' in '$from'")

            return null
        }

        moved += from
    }

    return moved
}