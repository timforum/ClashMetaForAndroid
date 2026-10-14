package com.github.kr328.clash.core.model

enum class ProxySort {
    Default, Title, Delay,

    /**
     * By the throughput the last screening round measured, fastest first, with
     * the nodes nobody has a figure for at the bottom.
     *
     * The only mode the core does not know: the figures live in the service
     * preferences, not in the proxy tree, so it falls through to `Default`
     * there and the order is imposed on the Kotlin side. An unmeasured node is
     * not "slowest", it is unknown, and putting the unknown ones last keeps
     * them from sitting above a node that was actually measured.
     */
    Speed,
}
