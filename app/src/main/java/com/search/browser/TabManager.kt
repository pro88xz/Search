package com.search.browser

/**
 * Owns the list of tabs and tracks which is active.
 * Enforces a cap on how many tabs stay live (hold a WebView) at once,
 * freezing the least-recently-used tab when the cap is exceeded.
 *
 * This class holds only data/bookkeeping. Actual WebView creation,
 * freezing (saveState + destroy) and restoring is done by the Activity,
 * which has the Context and view hierarchy. TabManager tells it what to do
 * via the callbacks.
 */
class TabManager(
    // A var because the Activity sizes it to the phone's memory in onCreate,
    // after the field holding this has been built.
    var maxLiveTabs: Int = 3
) {
    private var nextId = 1L
    val tabs = mutableListOf<Tab>()
    var activeTab: Tab? = null
        private set

    // Recently-used order of LIVE tab ids (most recent last).
    private val liveOrder = mutableListOf<Long>()

    // Callbacks the Activity provides:
    // onNeedFreeze: freeze this tab (saveState + destroy its WebView)
    var onNeedFreeze: ((Tab) -> Unit)? = null
    // canFreeze: false for a tab that has to stay live over the cap for now
    var canFreeze: ((Tab) -> Boolean)? = null

    fun createTab(url: String? = null): Tab {
        val tab = Tab(id = nextId++)
        url?.let { tab.url = it }
        tabs.add(tab)
        return tab
    }

    fun count(): Int = tabs.size

    fun setActive(tab: Tab) {
        activeTab = tab
        touchLive(tab.id)
    }

    /** Call when a tab becomes live (gets a WebView). Enforces the cap. */
    fun markLive(tab: Tab) {
        touchLive(tab.id)
        enforceCap()
    }

    private fun touchLive(id: Long) {
        liveOrder.remove(id)
        liveOrder.add(id)
    }

    /**
     * Freezes background tabs until at most [keep] are live - the active one
     * always among them. For when the system says memory is short: a frozen
     * tab costs a saved history, a live one a whole page in the renderer.
     */
    fun trimLive(keep: Int) = enforceCap(keep.coerceAtLeast(1))

    private fun enforceCap(limit: Int = maxLiveTabs) {
        // Least recently used first. The active tab is never frozen, and nor
        // is a tab the Activity says must stay live. Both keep their place in
        // the order: a tab passed over now is still holding a WebView, so it
        // has to be counted, and reconsidered on the next pass.
        //
        // This used to drop a refused tab from the order while leaving its
        // WebView alive, so it was never counted or frozen again.
        for (id in liveOrder.toList()) {
            if (liveOrder.size <= limit) break
            if (id == activeTab?.id) continue
            val tab = tabs.find { it.id == id }
            if (tab == null || !tab.isLive) {
                liveOrder.remove(id)
                continue
            }
            if (canFreeze?.invoke(tab) == false) continue
            liveOrder.remove(id)
            onNeedFreeze?.invoke(tab)
        }
    }

    fun removeTab(tab: Tab) {
        tabs.remove(tab)
        liveOrder.remove(tab.id)
        if (activeTab == tab) {
            activeTab = tabs.lastOrNull()
        }
    }
}
