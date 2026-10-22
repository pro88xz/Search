package com.search.browser

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.search.browser.MainActivity.ClosedTab
import org.json.JSONObject

// MainActivity, continued: Smart tab groups, Closed tabs come back, Swipe between tabs, Jump back in, Home after a long break, Tab deck.
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- Jump back in ----------

/**
 * Notes the tab on screen if it is showing a website, and rebuilds the
 * card's copy. Called as pages finish and tabs are switched; a Night Owl
 * tab is never offered.
 */
internal fun MainActivity.noteResumable() {
    val t = tabs.activeTab
    if (t != null && !t.isPrivate && (t.url.startsWith("https://") || t.url.startsWith("http://"))) {
        resumeTabId = t.id
    }
    val r = resumeTabId?.let { id -> tabs.tabs.firstOrNull { it.id == id } }
    resumeJson = if (r == null || r.isPrivate || nightOwl ||
        !(r.url.startsWith("https://") || r.url.startsWith("http://"))) "{}"
    else try {
        val site = siteNameOf(r.url)
        JSONObject()
            .put("id", r.id.toString())
            .put("url", r.url)
            .put("title", PageLabel.title(r.title, r.url).ifBlank { site })
            .put("site", site)
            .put("domain", (android.net.Uri.parse(r.url).host ?: "").removePrefix("www.")
                .removePrefix("mobile.").removePrefix("m."))
            .toString()
    } catch (e: Exception) { "{}" }
}

/** Home on screen: its card catches up with the tab last read. */
internal fun MainActivity.refreshResumeCard() {
    noteResumable()
    val web = activeWeb() ?: return
    if (web.url == homePage) web.evaluateJavascript("window.__refreshResume && window.__refreshResume()", null)
}

// ---------- Home after a long break ----------

internal fun MainActivity.showHomeAfterAway() {
    if (!Settings.getBool(this, Settings.HOME_AFTER_AWAY, true)) return
    val current = tabs.activeTab
    if (current == null || current.url == homePage) return
    noteResumable()
    val home = tabs.tabs.lastOrNull { it.url == homePage && it.isPrivate == nightOwl }
    if (home != null) openTab(home) else addNewTab(homePage)
}

// ---------- Smart tab groups ----------

/** A tab's site, as its group knows it ("bbc.co.uk"), or null for home, a new tab or an app page. */
internal fun MainActivity.tabGroupKey(t: Tab): String? {
    val url = t.url
    if (!url.startsWith("https://") && !url.startsWith("http://")) return null
    return try {
        android.net.Uri.parse(url).host?.lowercase()
            ?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("mobile.")
            ?.ifBlank { null }
    } catch (e: Exception) { null }
}

internal fun MainActivity.renderDeckGroups() {
    val row = binding.deckGroupRow
    val counts = LinkedHashMap<String, Int>()
    tabs.tabs.forEach { t -> tabGroupKey(t)?.let { counts[it] = (counts[it] ?: 0) + 1 } }
    val groups = counts.entries.filter { it.value >= 2 }
        .sortedByDescending { it.value }.take(8).map { it.key }
    if (deckGroup != null && deckGroup !in groups) deckGroup = null
    row.removeAllViews()
    if (groups.isEmpty() || binding.tabList.visibility != View.VISIBLE) {
        binding.deckGroups.visibility = View.GONE
        return
    }
    binding.deckGroups.visibility = View.VISIBLE
    row.addView(groupChip("All", tabs.count(), deckGroup == null, null))
    groups.forEach { key ->
        row.addView(groupChip(siteNameOf("https://$key/").ifBlank { key }, counts[key] ?: 0,
            deckGroup == key, key))
    }
}

internal fun MainActivity.groupChip(label: String, n: Int, selected: Boolean, key: String?): View {
    val d = resources.displayMetrics.density
    val accent = currentAccent()
    val chip = android.widget.TextView(this)
    chip.text = label + "  " + n
    chip.textSize = 13.5f
    chip.setTypeface(chip.typeface, android.graphics.Typeface.BOLD)
    chip.maxLines = 1
    chip.gravity = android.view.Gravity.CENTER
    chip.setPaddingRelative((14 * d).toInt(), 0, (14 * d).toInt(), 0)
    chip.minHeight = (36 * d).toInt()
    val bg = android.graphics.drawable.GradientDrawable()
    bg.cornerRadius = 18f * d
    if (selected) {
        bg.setColor(accent)
        chip.setTextColor(if (android.graphics.Color.luminance(accent) > 0.6f)
            android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    } else {
        bg.setColor(getColor(R.color.menuTileBg))
        chip.setTextColor(getColor(R.color.feedFg))
    }
    chip.background = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x22000000), bg, null)
    chip.isSelected = selected
    chip.contentDescription = (if (key == null) "All tabs, " else "$label tabs, ") + n +
        if (selected) ", showing" else ""
    val lp = android.widget.LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, (36 * d).toInt())
    lp.marginEnd = (8 * d).toInt()
    chip.layoutParams = lp
    chip.setOnClickListener { v ->
        Haptics.tap(v)
        deckGroup = key
        renderDeckGroups()
        tabAdapter.notifyDataSetChanged()
    }
    if (key != null) chip.setOnLongClickListener { v ->
        Haptics.confirm(v)
        confirmCloseGroup(key, label)
        true
    }
    return chip
}

/** Closes every tab of the site group [key], asked first; each is remembered for Reopen closed tab. */
internal fun MainActivity.confirmCloseGroup(key: String, label: String) {
    val going = tabs.tabs.filter { tabGroupKey(it) == key }.sortedBy { it === tabs.activeTab }
    if (going.isEmpty()) return
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
        .setTitle("Close " + going.size + " " + label + " tabs?")
        .setMessage("Menu > Reopen closed tab brings them back.")
        .setPositiveButton("Close") { _, _ ->
            Haptics.confirm(binding.tabList)
            if (deckGroup == key) deckGroup = null
            going.forEach { t ->
                if (!tabs.tabs.contains(t)) return@forEach
                rememberClosed(t)
                closeTabFromDeck(t)
            }
            if (deckVisible) renderDeckGroups()
            tabAdapter.notifyDataSetChanged()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/**
 * The tabs the deck shows: all, or those of the site group picked
 * ([deckGroup]), and of those the ones whose title or address has
 * [deckFilter] in it.
 */
internal fun MainActivity.shownTabs(): List<Tab> {
    val q = deckFilter
    val g = deckGroup
    val base = if (g == null) tabs.tabs else tabs.tabs.filter { tabGroupKey(it) == g }
    if (q.isBlank()) return base
    return base.filter { t ->
        t.title.contains(q, ignoreCase = true) || t.url.contains(q, ignoreCase = true) ||
            PageLabel.title(t.title, t.url).contains(q, ignoreCase = true)
    }
}

/** The deck's overflow: close every tab, or every tab but the one on screen. */
internal fun MainActivity.showDeckMenu(anchor: View) {
    val menu = android.widget.PopupMenu(this, anchor)
    menu.menu.add(0, 1, 0, "Close all tabs")
    if (tabs.count() > 1) menu.menu.add(0, 2, 1, "Close other tabs")
    if (closedTabs.isNotEmpty() && !nightOwl) menu.menu.add(0, 3, 2, "Reopen closed tab")
    val dupes = duplicateTabs()
    if (dupes.isNotEmpty()) menu.menu.add(0, 4, 3, "Close duplicate tabs (" + dupes.size + ")")
    if (!nightOwl) menu.menu.add(0, 5, 4, "Delete last 15 minutes")
    menu.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            1 -> confirmCloseTabs(keepActive = false)
            2 -> confirmCloseTabs(keepActive = true)
            3 -> reopenClosedTab(inDeck = true)
            4 -> closeTabsQuietly(duplicateTabs())
            5 -> {
                val n = BrowsingData.deleteRecent(this)
                Haptics.confirm(anchor)
                toast(if (n == 0) "Nothing from the last 15 minutes"
                    else "Deleted " + n + (if (n == 1) " page" else " pages") + " from history")
            }
        }
        true
    }
    menu.show()
}

/**
 * Tabs showing the same page as another, ignoring the #part: all but one
 * of each set, keeping the one on screen if it is among them, otherwise
 * the first. Home tabs count too; empty new tabs do not.
 */
internal fun MainActivity.duplicateTabs(): List<Tab> {
    val groups = tabs.tabs.filter { it.url.isNotBlank() && !it.isPrivate }
        .groupBy { it.url.substringBefore('#') }
    return groups.values.filter { it.size > 1 }.flatMap { same ->
        val keep = same.firstOrNull { it === tabs.activeTab } ?: same.first()
        same.filter { it !== keep }
    }
}

/**
 * Closes [going] with no question: each is remembered, so Reopen closed
 * tab brings them back. For duplicates, where nothing unique is lost.
 */
internal fun MainActivity.closeTabsQuietly(going: List<Tab>) {
    if (going.isEmpty()) return
    Haptics.confirm(binding.tabList)
    going.sortedBy { it === tabs.activeTab }.forEach { t ->
        if (!tabs.tabs.contains(t)) return@forEach
        rememberClosed(t)
        closeTabFromDeck(t)
    }
    toast("Closed " + going.size + (if (going.size == 1) " duplicate tab" else " duplicate tabs"))
}

// ---------- Closed tabs come back ----------

internal fun MainActivity.rememberClosed(tab: Tab): Boolean {
    if (tab.isPrivate || nightOwl) return false
    val url = tab.url
    if (!(url.startsWith("https://") || url.startsWith("http://"))) return false
    val state = tab.webView?.let { w ->
        try { Bundle().also { w.saveState(it) } } catch (e: Exception) { null }
    } ?: tab.savedState
    closedTabs.addLast(ClosedTab(url, tab.title, state, tabs.tabs.indexOf(tab)))
    while (closedTabs.size > 10) closedTabs.removeFirst()
    // History kept for the newest three only; older ones keep their address.
    closedTabs.toList().dropLast(3).forEach { it.state = null }
    return true
}

/** A tab the user closed in the deck: remembered, closed, and offered back. */
internal fun MainActivity.closeTabByUser(tab: Tab) {
    Haptics.confirm(binding.tabList)
    val remembered = rememberClosed(tab)
    closeTabFromDeck(tab)
    if (remembered && deckVisible) {
        try {
            com.google.android.material.snackbar.Snackbar
                .make(binding.tabDeck, "Tab closed", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("Undo") { reopenClosedTab(inDeck = true) }
                .setActionTextColor(currentAccent())
                .show()
        } catch (e: Exception) { /* no window to show it in */ }
    }
}

/** The last closed tab, back in its place: in the deck if it is open, otherwise on screen. */
internal fun MainActivity.reopenClosedTab(inDeck: Boolean) {
    if (nightOwl) { toast("Not in Night Owl"); return }
    val c = closedTabs.removeLastOrNull() ?: return
    val tab = tabs.createTab(c.url)
    tab.title = c.title
    tab.savedState = c.state
    tabs.tabs.remove(tab)
    tabs.tabs.add(c.index.coerceIn(0, tabs.tabs.size), tab)
    if (inDeck && deckVisible) {
        tabAdapter.notifyDataSetChanged()
        updateTabCount()
        scheduleSessionSave()
    } else {
        leaveTransientUi()
        openTab(tab)
    }
}

// ---------- Swipe between tabs ----------

internal fun MainActivity.setupTabSwipe() {
    binding.bottomBar.onSwipeStart = { beginSwipe() }
    binding.bottomBar.onSwipe = { dx ->
        swipeSpring.cancel()
        placeSwipe(followSwipe(dx))
    }
    binding.bottomBar.onSwipeEnd = { dx, vx -> finishSwipe(followSwipe(dx), vx) }
}

internal fun MainActivity.beginSwipe(): Boolean {
    if (!swipeTabsPref) return false
    if (searchMode || deckVisible || swipeSpring.isRunning || activeWeb() == null) return false
    if (binding.menuScrim.visibility == View.VISIBLE) return false
    swipeDir = 0
    swipeNeighbor = null
    // The deck's picture of this tab, taken while it is still in place.
    tabs.activeTab?.let { captureThumbnail(it) }
    return true
}

internal fun MainActivity.followSwipe(dx: Float): Float {
    val dir = if (dx < 0f) -1 else 1
    if (dir != swipeDir) {
        swipeDir = dir
        val list = tabs.tabs
        val i = list.indexOf(tabs.activeTab)
        swipeNeighbor = if (i < 0) null else list.getOrNull(if (dir < 0) i + 1 else i - 1)
        val peek = binding.tabPeek
        peek.setImageBitmap(swipeNeighbor?.thumbnail?.takeIf { !it.isRecycled })
        peek.visibility = if (swipeNeighbor != null) View.VISIBLE else View.GONE
    }
    if (swipeNeighbor != null) return dx
    val reach = 56f * resources.displayMetrics.density
    return kotlin.math.sign(dx) * reach * (1f - kotlin.math.exp(-kotlin.math.abs(dx) / reach))
}

internal fun MainActivity.finishSwipe(x: Float, vx: Float) {
    val w = binding.webArea.width.toFloat()
    val n = swipeNeighbor
    val fast = 900f * resources.displayMetrics.density
    val flung = kotlin.math.abs(vx) > fast
    val toward = flung && kotlin.math.sign(vx) == kotlin.math.sign(x)
    val back = flung && !toward
    if (n != null && w > 0f && !back && (toward || kotlin.math.abs(x) > w * 0.35f)) {
        Haptics.tick(binding.bottomBar)
        swipeSpring.animate(x, if (x < 0f) -w else w, vx) {
            // The page is off screen; the tab it slid to takes its place
            // in the same frame, where its picture was.
            if (tabs.tabs.contains(n)) openTab(n)
            endSwipe()
        }
    } else {
        swipeSpring.animate(x, 0f, vx) { endSwipe() }
    }
}

internal fun MainActivity.endSwipe() {
    swipeSpring.cancel()
    placeSwipe(0f)
    binding.tabPeek.visibility = View.GONE
    binding.tabPeek.setImageDrawable(null)
    swipeNeighbor = null
    swipeDir = 0
}
