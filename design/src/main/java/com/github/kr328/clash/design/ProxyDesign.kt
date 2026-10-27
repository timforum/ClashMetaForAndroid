package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.adapter.ProxyAdapter
import com.github.kr328.clash.design.adapter.ProxyPageAdapter
import com.github.kr328.clash.design.component.ProxyMenu
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.SpeedTestProfile
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyDesign(
    context: Context,
    overrideMode: TunnelState.Mode?,
    private val groupNames: List<String>,
    private val uiStore: UiStore,
    private val serviceStore: ServiceStore,
) : Design<ProxyDesign.Request>(context) {
    sealed class Request {
        object ReloadAll : Request()
        object ReLaunch : Request()

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class Reload(val index: Int) : Request()
        data class Select(val index: Int, val name: String) : Request()
        data class UrlTest(val index: Int) : Request()

        /** The toolbar toggle for [index] changed; the activity flips the per-group state. */
        data class SpeedTest(val index: Int) : Request()

        /** The three-dot online watch switch clicked; the activity flips the global state. */
        object WatchOnline : Request()
    }

    private val binding = DesignProxyBinding
        .inflate(context.layoutInflater, context.root, false)

    private var config = ProxyViewConfig(context, uiStore.proxyLine)

    private val menu: ProxyMenu by lazy {
        ProxyMenu(
            context,
            binding.menuView,
            overrideMode,
            uiStore,
            requests,
            { config.proxyLine = uiStore.proxyLine },
            {
                groupNames.getOrNull(binding.pagesView.currentItem)
                    ?.let { it in serviceStore.speedTestGroups }
            },
            {
                groupNames.getOrNull(binding.pagesView.currentItem)?.let {
                    requests.trySend(Request.SpeedTest(binding.pagesView.currentItem))
                }
            },
            { serviceStore.speedTestWatchOnline },
        ) {
            requests.trySend(Request.WatchOnline)
        }
    }

    private val adapter: ProxyPageAdapter
        get() = binding.pagesView.adapter!! as ProxyPageAdapter

    private var horizontalScrolling = false

    private val verticalBottomScrolled: Boolean
        get() = adapter.states[binding.pagesView.currentItem].bottom
    private var urlTesting: Boolean
        get() = adapter.states[binding.pagesView.currentItem].urlTesting
        set(value) {
            adapter.states[binding.pagesView.currentItem].urlTesting = value
        }

    /**
     * Whether the page on screen is the group the YouTube rules point at.
     *
     * That group is ranked on speed rather than on delay - its own run proves
     * YouTube playback with reachability probes and a real transfer, while
     * generate_204 latency answers a question the routing of this group never
     * asks - so the delay buttons stand down on it.
     */
    private val videoGroupVisible: Boolean
        get() = groupNames.getOrNull(binding.pagesView.currentItem)
            ?.let(SpeedTestProfile::isVideoGroup) == true

    override val root: View = binding.root

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        // Read once: the figures drive the second line, the order and the tick
        // in the corner, and reading the store three times could show a round
        // that finished between two of the reads in only two of the three.
        val speeds = serviceStore.speedTestResults().mbps
        val measured = speeds.filterValues { it > 0.0 }.keys

        adapter.updateAdapter(
            position,
            sortedBySpeed(withSpeeds(proxies, speeds), speeds),
            selectable,
            parent,
            links,
            measured,
        )

        adapter.states[position].urlTesting = false

        updateUrlTestButtonStatus()

        if (position == binding.pagesView.currentItem)
            refreshSpeedTestButton()
    }

    /**
     * Puts the throughput the last round measured on each node's second line,
     * in place of the protocol.
     *
     * In place of, not after, the protocol: `ProxyView` cuts the second line
     * at a pixel width with `breakText` and draws no ellipsis, so in the
     * two-column layout - the one people actually use - there is room for
     * about ten characters. `AnyTLS · 2.7 MB/s` renders as `AnyTLS · 2` and
     * the figure is gone, which is worse than not having shown it. `2.7 Mbps`
     * fits, and of the two it is the one being asked for; the protocol is the
     * least useful thing on that card to somebody ranking by speed.
     *
     * A node nobody has a figure for keeps its protocol, so the two states are
     * distinguishable at a glance: a row reading `3.6 Mbps` was measured, one
     * reading `Http` was not.
     *
     * A group is left alone: it has no throughput of its own, only the nodes
     * behind it, and printing a figure against a branch would be reporting a
     * number that describes nothing.
     */
    private fun withSpeeds(proxies: List<Proxy>, speeds: Map<String, Double>): List<Proxy> {
        if (speeds.isEmpty()) {
            return proxies
        }

        return proxies.map { proxy ->
            val mbps = if (proxy.isGroup) null else speeds[proxy.name]

            if (mbps == null) {
                proxy
            } else {
                proxy.copy(subtitle = formatMbps(mbps))
            }
        }
    }

    /**
     * Puts the fastest measured node at the top when the sort says so.
     *
     * The core cannot do this one: the figures live in the service
     * preferences rather than anywhere in the proxy tree, so the core is asked
     * for its ordinary order and the order is imposed here instead. It is
     * applied after [withSpeeds] rather than before, which costs one lookup
     * per proxy and saves keeping the two passes in step.
     *
     * A node with no figure sorts last rather than first. Ordering an unknown
     * ahead of a measured node would claim it is the better of the two, which
     * is the one thing a figure nobody took must not do; and it keeps the
     * nodes a round has not reached yet visibly below the ones it cleared.
     *
     * Within the unknown group the core's order is left alone, so the list is
     * still stable and does not shuffle between reloads.
     */
    private fun sortedBySpeed(proxies: List<Proxy>, speeds: Map<String, Double>): List<Proxy> {
        if (uiStore.proxySort != ProxySort.Speed) {
            return proxies
        }

        if (speeds.isEmpty()) {
            return proxies
        }

        return proxies.sortedWith(
            compareByDescending<Proxy> { speeds[it.name] ?: 0.0 }
        )
    }

    /**
     * Always megabits, never a unit that changes with the magnitude, and
     * without the space before it.
     *
     * Switching to MB/s above ten was tried and it lies: `2.9 Mbps` printed
     * beside `3.6 MB/s` reads as the slower of the two when they are the same
     * size, and a list is read by comparing its numbers. One unit for every
     * row means a bigger number is a faster node, always. Mbps is also the
     * unit the engine reports and the unit speedtest.net prints, so the figure
     * can be checked against something outside this app.
     *
     * The space is the one character the two-column card cannot spare: that
     * layout cuts the line at a pixel width with no ellipsis, and `19.3 Mbps`
     * came out as `19.3 Mbp` beside a three digit delay. Dropping it is the
     * whole difference between a figure that reads and one that does not, and
     * the decimal is worth more than the space - rounding to whole megabits
     * would print 19 and 20 for nodes a reader is trying to tell apart.
     */
    private fun formatMbps(mbps: Double): String = String.format("%.1fMbps", mbps)

    suspend fun requestRedrawVisible() {
        withContext(Dispatchers.Main) {
            adapter.requestRedrawVisible()
        }
    }

    suspend fun showModeSwitchTips() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.mode_switch_tips, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun showSpeedTestFailed() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.speed_test_failure, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Gives a remote's page keys something to do in the node list.
     *
     * A remote has no scrollbar to drag and no gesture to make, so without
     * this a long group could only be walked one focus step at a time. Keys
     * the window ignores still click, which reads to the user as a broken
     * button, so only a key that can actually page a list is swallowed here.
     *
     * Returns false for anything that is not a page key, or when there is no
     * list to page through, so the caller can pass it down.
     */
    fun handlePageKey(keyCode: Int, down: Boolean): Boolean {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> 1
            KeyEvent.KEYCODE_PAGE_UP -> -1
            else -> return false
        }

        if (groupNames.isEmpty())
            return false

        val grid = (binding.pagesView.adapter as? ProxyPageAdapter)
            ?.recyclerViewAt(binding.pagesView.currentItem) ?: return false

        if (down)
            scrollPage(grid, step)

        return true
    }

    private fun scrollPage(grid: RecyclerView, step: Int) {
        val lm = grid.layoutManager as? GridLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()

        if (first == RecyclerView.NO_POSITION || first > last)
            return

        val page = grid.height - grid.paddingTop - grid.paddingBottom

        // Three quarters of the viewport keeps one row of context, so the list
        // reads as a jump rather than a teleport to unrelated nodes.
        if (page > 0)
            grid.scrollBy(0, (page * 3 / 4).coerceAtLeast(1) * step)

        focusTopRow(grid, lm)
    }

    private fun focusTopRow(grid: RecyclerView, lm: GridLayoutManager) {
        val last = lm.findLastVisibleItemPosition()

        var position = lm.findFirstVisibleItemPosition()

        if (position == RecyclerView.NO_POSITION)
            return

        // Park on the first row cut clean by the top edge; focusing a partial
        // row would have the grid snap it into place and undo part of the jump.
        while (position < last) {
            val child = lm.findViewByPosition(position) ?: break

            if (child.top >= grid.paddingTop)
                break

            position++
        }

        lm.findViewByPosition(position)?.requestFocus()
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.menuView.setOnClickListener {
            menu.show()
        }

        if (groupNames.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE

            binding.urlTestView.visibility = View.GONE
            binding.speedTestLayout.visibility = View.GONE
            binding.tabLayoutView.visibility = View.GONE
            binding.elevationView.visibility = View.GONE
            binding.pagesView.visibility = View.GONE
            binding.urlTestFloatView.visibility = View.GONE
        } else {
            binding.urlTestFloatView.supportImageTintList = ColorStateList.valueOf(
                context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
            )

            binding.pagesView.apply {
                adapter = ProxyPageAdapter(
                    surface,
                    config,
                    List(groupNames.size) { index ->
                        ProxyAdapter(config) { name ->
                            requests.trySend(Request.Select(index, name))
                        }
                    }
                ) {
                    if (it == currentItem)
                        updateUrlTestButtonStatus()
                }

                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrollStateChanged(state: Int) {
                        horizontalScrolling = state != ViewPager2.SCROLL_STATE_IDLE

                        updateUrlTestButtonStatus()
                    }

                    override fun onPageSelected(position: Int) {
                        uiStore.proxyLastGroup = groupNames[position]

                        refreshSpeedTestButton()

                        // The scroll-state callback only swings the delay
                        // buttons after a swipe; a direct jump - like landing
                        // straight on the video page - selects without one,
                        // so the greyed-out state is applied here too.
                        updateUrlTestButtonStatus()
                    }
                })
            }

            TabLayoutMediator(binding.tabLayoutView, binding.pagesView) { tab, index ->
                tab.text = groupNames[index]
            }.attach()

            refreshSpeedTestButton()

            val initialPosition = groupNames.indexOf(uiStore.proxyLastGroup)

            binding.pagesView.post {
                if (initialPosition > 0)
                    binding.pagesView.setCurrentItem(initialPosition, false)
            }
        }
    }

    fun requestUrlTesting() {
        // Greyed out on the video page, but a stale layout state can still land
        // a press here: the request is dropped rather than sent, so latency
        // cannot decide anything for a group whose standard is speed.
        if (videoGroupVisible)
            return

        urlTesting = true

        requests.trySend(Request.UrlTest(binding.pagesView.currentItem))

        updateUrlTestButtonStatus()
    }

    /**
     * The toolbar's speed toggle.
     *
     * Asking is all this does: the round itself runs in the service, so leaving
     * the screen cannot end it, and the spinner is painted from the running
     * mark the service writes rather than from anything kept here. A tap while
     * a round is in flight therefore turns the switch off - the request to stop
     * - instead of being swallowed by a local "one at a time" guard.
     */
    fun requestSpeedTest() {
        // Nothing to toggle without a group: the toolbar hides the icon, but a
        // key event can still land here and an index would be a lie.
        if (groupNames.isEmpty())
            return

        requests.trySend(Request.SpeedTest(binding.pagesView.currentItem))
    }

    /**
     * Re-reads the group on screen.
     *
     * Used when the screen comes back: a round that ran while the activity was
     * stopped may have moved the selection, and its broadcasts were sent into a
     * stopped screen that had no observer to receive them.
     */
    fun reloadVisible() {
        if (groupNames.isEmpty())
            return

        requests.trySend(Request.Reload(binding.pagesView.currentItem))
    }

    /**
     * Paints the speed toggle for the group on screen: highlighted while the
     * per-group switch is on ([ServiceStore.speedTestGroups]) and showing the
     * spinner while the service is measuring it. Both come from the service
     * preferences, so a round the periodic pass started shows up here exactly
     * like one the toolbar asked for. The tint comes from the view, not the
     * drawable, so turning the switch off falls back to the icon's own neutral
     * tint by clearing it.
     */
    fun refreshSpeedTestButton() {
        if (groupNames.isEmpty())
            return

        val index = binding.pagesView.currentItem
        val name = groupNames[index]
        val enabled = serviceStore.speedTestGroups.contains(name)
        val running = serviceStore.isSpeedTestRunning(name)

        // Three states, three colours: off is the toolbar's own neutral, on is
        // the theme's accent, and measuring is green. Two shades of the same
        // blue were indistinguishable on a television at three metres, which is
        // what made a press look like it had done nothing.
        binding.speedTestView.imageTintList = ColorStateList.valueOf(
            when {
                running -> ContextCompat.getColor(context, R.color.color_speed_test_running)
                enabled -> context.resolveThemedColor(
                    com.google.android.material.R.attr.colorSecondary
                )
                else -> context.resolveThemedColor(
                    com.google.android.material.R.attr.colorControlNormal
                )
            }
        )

        // The icon stays: a switch that turns into a bare spinner is a switch
        // the reader can no longer recognise, and on a television it is the
        // only thing on that button. The spinner is drawn behind it instead
        // (see design_proxy.xml), so "measuring" reads as the same button
        // still turning - and the same tap still ends it.
        binding.speedTestView.visibility = View.VISIBLE
        binding.speedTestProgressView.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun updateUrlTestButtonStatus() {
        // On the video page the delay buttons stand down: the toolbar icon
        // stays visible but is greyed and dead, and the floating one hides
        // itself, so the screen says the same thing whichever of the two a
        // reader is used to pressing. A running test on a *previous* page keeps
        // running; it simply has no place making a claim about this group.
        if (videoGroupVisible) {
            binding.urlTestView.isEnabled = false
            binding.urlTestView.alpha = DISABLED_BUTTON_ALPHA
            binding.urlTestView.visibility = View.VISIBLE
            binding.urlTestProgressView.visibility = View.GONE

            binding.urlTestFloatView.hide()

            return
        }

        binding.urlTestView.isEnabled = true
        binding.urlTestView.alpha = 1f

        if (verticalBottomScrolled || horizontalScrolling || urlTesting) {
            binding.urlTestFloatView.hide()
        } else {
            binding.urlTestFloatView.show()
        }

        if (urlTesting) {
            binding.urlTestView.visibility = View.GONE
            binding.urlTestProgressView.visibility = View.VISIBLE
        } else {
            binding.urlTestView.visibility = View.VISIBLE
            binding.urlTestProgressView.visibility = View.GONE
        }
    }

    companion object {
        /** How faint the delay button reads while it is unusable on the video page. */
        private const val DISABLED_BUTTON_ALPHA = 0.4f
    }
}