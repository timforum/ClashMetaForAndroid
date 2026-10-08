package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.Proxy
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
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyDesign(
    context: Context,
    overrideMode: TunnelState.Mode?,
    private val groupNames: List<String>,
    uiStore: UiStore,
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
            }
        ) {
            groupNames.getOrNull(binding.pagesView.currentItem)?.let {
                requests.trySend(Request.SpeedTest(binding.pagesView.currentItem))
            }
        }
    }

    private val adapter: ProxyPageAdapter
        get() = binding.pagesView.adapter!! as ProxyPageAdapter

    private var horizontalScrolling = false

    // Whose speed test is in flight, or -1 when none: a second group must not
    // start one until the first returns, so the spinner always means something.
    private var speedTestingIndex = -1
    private val verticalBottomScrolled: Boolean
        get() = adapter.states[binding.pagesView.currentItem].bottom
    private var urlTesting: Boolean
        get() = adapter.states[binding.pagesView.currentItem].urlTesting
        set(value) {
            adapter.states[binding.pagesView.currentItem].urlTesting = value
        }

    override val root: View = binding.root

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        adapter.updateAdapter(position, proxies, selectable, parent, links)

        adapter.states[position].urlTesting = false

        updateUrlTestButtonStatus()

        if (position == binding.pagesView.currentItem)
            refreshSpeedTestButton()
    }

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
        urlTesting = true

        requests.trySend(Request.UrlTest(binding.pagesView.currentItem))

        updateUrlTestButtonStatus()
    }

    /**
     * The toolbar's speed toggle. The running mark is claimed here rather than
     * in the activity: two taps in the same frame would otherwise queue two
     * requests, and one run at a time keeps the spinner honest. The activity
     * releases the mark again whether the tap turned the switch on or off.
     */
    fun requestSpeedTest() {
        if (speedTestingIndex != -1)
            return

        // Nothing to toggle without a group: the toolbar hides the icon, but a
        // key event can still land here and an index would be a lie.
        if (groupNames.isEmpty())
            return

        speedTestingIndex = binding.pagesView.currentItem

        refreshSpeedTestButton()

        requests.trySend(Request.SpeedTest(binding.pagesView.currentItem))
    }

    /**
     * Marks [index] as measuring (or idle when [running] is false) and repaints
     * the toggle when that group is the one on screen.
     */
    fun setSpeedTestRunning(index: Int, running: Boolean) {
        speedTestingIndex = if (running) index else -1

        if (index == binding.pagesView.currentItem)
            refreshSpeedTestButton()
    }

    /**
     * Paints the speed toggle for the group on screen: highlighted while the
     * per-group switch is on ([ServiceStore.speedTestGroups]) and spinning
     * while its measurement is in flight. The tint comes from the view, not
     * the drawable, so turning the switch off falls back to the icon's own
     * neutral tint by clearing it.
     */
    fun refreshSpeedTestButton() {
        if (groupNames.isEmpty())
            return

        val index = binding.pagesView.currentItem
        val enabled = serviceStore.speedTestGroups.contains(groupNames[index])
        val running = speedTestingIndex == index

        binding.speedTestView.imageTintList =
            if (enabled)
                ColorStateList.valueOf(
                    context.resolveThemedColor(com.google.android.material.R.attr.colorSecondary)
                )
            else
                ColorStateList.valueOf(
                    context.resolveThemedColor(com.google.android.material.R.attr.colorControlNormal)
                )

        if (running) {
            binding.speedTestView.visibility = View.GONE
            binding.speedTestProgressView.visibility = View.VISIBLE
        } else {
            binding.speedTestView.visibility = View.VISIBLE
            binding.speedTestProgressView.visibility = View.GONE
        }
    }

    private fun updateUrlTestButtonStatus() {
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
}