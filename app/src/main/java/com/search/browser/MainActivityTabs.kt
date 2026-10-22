package com.search.browser

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.search.browser.MainActivity.ClosedTab
import org.json.JSONObject

// MainActivity, continued: Tab groups and the deck, Closed tabs come back, Swipe between tabs, Jump back in, Home after a long break, Tab deck.
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

// ---------- Tab groups and the deck ----------
//
// The deck shows tabs as cards and tab groups as cards of their own - the
// group's colour, name and size over its first four tabs. Tapping a group
// opens it in the deck; Tabs | Groups at the top shows everything, or only
// the groups. A tab joins a group from its long-press menu ("Add to new
// group", "Add to group"), several at once from Select tabs, and the tabs
// of each site with two or more open from "Group tabs by site". Groups are
// saved with the session (SessionStore); one with no tabs left is dropped.

internal const val DECK_TABS = 0
internal const val DECK_GROUPS = 1

/** A tab's site, as "Group tabs by site" knows it ("bbc.co.uk"), or null for home, a new tab or an app page. */
internal fun MainActivity.tabGroupKey(t: Tab): String? {
    val url = t.url
    if (!url.startsWith("https://") && !url.startsWith("http://")) return null
    return try {
        android.net.Uri.parse(url).host?.lowercase()
            ?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("mobile.")
            ?.ifBlank { null }
    } catch (e: Exception) { null }
}

/**
 * The deck's cards for what it is showing: one group's tabs when a group is
 * open; every tab, flat, while choosing tabs or searching; otherwise each
 * group as one card where its first tab sits, and (in Tabs) the tabs in no
 * group around them.
 */
internal fun MainActivity.deckItems(): List<DeckItem> {
    val q = deckFilter
    fun matches(t: Tab) = q.isBlank() || t.title.contains(q, ignoreCase = true) ||
        t.url.contains(q, ignoreCase = true) || PageLabel.title(t.title, t.url).contains(q, ignoreCase = true)
    tabs.group(deckOpenGroup)?.let { g ->
        return tabs.tabsIn(g).filter(::matches).map { DeckItem.TabItem(it) }
    }
    if (deckSelecting || q.isNotBlank()) return tabs.tabs.filter(::matches).map { DeckItem.TabItem(it) }
    val out = ArrayList<DeckItem>()
    val seen = HashSet<Long>()
    for (t in tabs.tabs) {
        val g = tabs.group(t.groupId)
        if (g == null) {
            if (deckView == DECK_TABS) out.add(DeckItem.TabItem(t))
        } else if (seen.add(g.id)) {
            out.add(DeckItem.GroupItem(g, tabs.tabsIn(g)))
        }
    }
    return out
}

/** The deck shows its tabs (not history, bookmarks or downloads). */
internal fun MainActivity.deckOnTabs() = !historyOpen && !bookmarksOpen && !downloadsOpen

/** Re-reads the deck - its cards, its header and, in Groups with none yet, how to make one. */
internal fun MainActivity.refreshDeck(fade: Boolean = false) {
    if (tabs.group(deckOpenGroup) == null) deckOpenGroup = null
    if (deckSelecting) deckSelected.retainAll(tabs.tabs.map { it.id }.toSet())
    renderDeckHeader()
    if (!deckOnTabs()) return
    deckAdapter.refresh()
    val noGroups = deckView == DECK_GROUPS && deckOpenGroup == null && !deckSelecting &&
        deckFilter.isBlank() && tabs.groups.isEmpty()
    if (noGroups) {
        binding.tabList.visibility = View.GONE
        showDeckEmpty(R.drawable.set_widget, "No tab groups yet",
            "Hold a tab and choose Add to new group, or use Group tabs by site in the menu.")
    } else {
        binding.deckEmpty.visibility = View.GONE
        binding.tabList.visibility = View.VISIBLE
        if (fade) {
            binding.tabList.alpha = 0f
            binding.tabList.animate().alpha(1f).setDuration(170L).start()
        }
    }
}

/** A change to the tabs or groups the deck shows: redrawn, counted, saved. */
internal fun MainActivity.deckChanged() {
    if (deckVisible) refreshDeck()
    updateTabCount()
    scheduleSessionSave()
}

private fun MainActivity.rounded(color: Int, radiusDp: Float) =
    android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(color)
    }

/**
 * The deck's header for what it shows: the tab bar (a new tab, Tabs |
 * Groups, the menu) over the tabs; a back arrow and a title over a group,
 * while choosing tabs, and over history, bookmarks and downloads.
 */
internal fun MainActivity.renderDeckHeader() {
    val b = binding
    val d = resources.displayMetrics.density
    val accent = currentAccent()
    val onAccent = if (android.graphics.Color.luminance(accent) > 0.6f) 0xFF16151B.toInt() else 0xFFFFFFFF.toInt()
    val ink = getColor(R.color.feedFg)
    val muted = getColor(R.color.sugIcon)
    val listTitle = when {
        historyOpen -> "History"
        bookmarksOpen -> "Bookmarks"
        downloadsOpen -> "Downloads"
        else -> null
    }
    val group = if (listTitle == null) tabs.group(deckOpenGroup) else null
    val choosing = listTitle == null && deckSelecting
    val titled = listTitle != null || group != null || choosing
    b.deckTabBar.visibility = if (titled) View.GONE else View.VISIBLE
    b.deckTitleBar.visibility = if (titled) View.VISIBLE else View.GONE
    b.deckSearch.visibility = if (titled) View.GONE else View.VISIBLE
    if (titled) {
        b.deckBack.imageTintList = android.content.res.ColorStateList.valueOf(ink)
        b.deckBack.setImageResource(if (choosing) R.drawable.menu_close else R.drawable.menu_back)
        b.deckBack.contentDescription = if (choosing) "Stop choosing tabs" else "Back"
        b.deckTitleDot.visibility = View.GONE
        b.deckAction1.visibility = View.GONE
        b.deckAction2.visibility = View.GONE
        b.deckTitleAdd.visibility = View.GONE
        b.deckTitleMore.visibility = View.GONE
        b.deckTitle.setOnClickListener(null)
        b.deckTitle.isClickable = false
        b.deckTitle.setTextColor(ink)
        when {
            listTitle != null -> b.deckTitle.text = listTitle
            choosing -> {
                val n = deckSelected.size
                b.deckTitle.text = if (n == 0) "Choose tabs" else "$n selected"
                listOf(b.deckAction1 to "Group", b.deckAction2 to "Close").forEach { (v, label) ->
                    v.text = label
                    v.visibility = View.VISIBLE
                    v.isEnabled = n > 0
                    v.setTextColor(if (n > 0) Settings.accentInk(this) else muted)
                }
            }
            group != null -> {
                val color = TabGroups.color(this, group.color)
                b.deckTitleDot.visibility = View.VISIBLE
                b.deckTitleDot.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                }
                b.deckTitle.text = group.name.ifBlank { "Group" }
                b.deckTitle.contentDescription = group.name + " tab group. Tap to rename"
                b.deckTitle.setOnClickListener { showGroupDialog(group, emptyList()) }
                b.deckTitleAdd.visibility = View.VISIBLE
                b.deckTitleAdd.imageTintList = android.content.res.ColorStateList.valueOf(Settings.accentInk(this))
                b.deckTitleMore.visibility = View.VISIBLE
                b.deckTitleMore.imageTintList = android.content.res.ColorStateList.valueOf(ink)
            }
        }
        return
    }
    // The new-tab button wears the accent, as the top bar's own actions do.
    b.deckNewTab.background = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x33FFFFFF), rounded(accent, 16f), null)
    b.deckNewTab.imageTintList = android.content.res.ColorStateList.valueOf(onAccent)
    // Tabs | Groups: a pill, the side showing raised in the card colour.
    b.deckSegments.background = rounded(getColor(R.color.menuTileBg), 23f)
    val onTabs = deckView == DECK_TABS
    b.deckSegTabs.background = if (onTabs) rounded(getColor(R.color.settingsCard), 19f) else null
    b.deckSegGroups.background = if (!onTabs) rounded(getColor(R.color.settingsCard), 19f) else null
    b.deckSegTabs.elevation = if (onTabs) 1.5f * d else 0f
    b.deckSegGroups.elevation = if (!onTabs) 1.5f * d else 0f
    val n = tabs.count()
    b.deckTabCount.text = if (n > 99) "99+" else n.toString()
    val countInk = if (onTabs) ink else muted
    b.deckTabCount.setTextColor(countInk)
    b.deckTabCount.background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 6f * d
        setStroke((1.8f * d).toInt(), countInk)
    }
    b.deckGroupsIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (onTabs) muted else ink)
    b.deckSegTabs.contentDescription = "Tabs, " + n + " open" + if (onTabs) ", showing" else ""
    val g = tabs.groups.size
    b.deckSegGroups.contentDescription = "Tab groups, " + g + if (!onTabs) ", showing" else ""
    b.deckMore.imageTintList = android.content.res.ColorStateList.valueOf(ink)
    b.deckSearch.background = rounded(getColor(R.color.menuTileBg), 24f)
    b.deckSearch.compoundDrawablesRelative[0]?.setTint(muted)
}

/** Back in the deck: out of choosing tabs, out of a group, then out of the deck. */
internal fun MainActivity.deckBack() {
    when {
        !deckOnTabs() -> closeDeck()
        deckSelecting -> endSelecting()
        deckOpenGroup != null -> {
            deckOpenGroup = null
            refreshDeck(fade = true)
        }
        else -> closeDeck()
    }
}

internal fun MainActivity.openDeckGroup(g: TabGroup) {
    hideKeyboard()
    deckOpenGroup = g.id
    refreshDeck(fade = true)
}

internal fun MainActivity.deckTabClicked(tab: Tab) {
    if (deckSelecting) { toggleSelected(tab); return }
    // openTab before closeDeck: openTab pictures the tab being left, and
    // refuses while the deck is still up - closed first, the last frame on
    // screen would be the deck itself.
    openTab(tab)
    closeDeck()
}

// ---------- Choosing tabs ----------

internal fun MainActivity.beginSelecting(first: Tab?) {
    hideKeyboard()
    deckSelecting = true
    deckSelected.clear()
    first?.let { deckSelected.add(it.id) }
    refreshDeck(fade = true)
}

internal fun MainActivity.endSelecting() {
    deckSelecting = false
    deckSelected.clear()
    refreshDeck(fade = true)
}

internal fun MainActivity.toggleSelected(tab: Tab) {
    Haptics.tick(binding.tabList)
    if (!deckSelected.remove(tab.id)) deckSelected.add(tab.id)
    deckAdapter.notifyDataSetChanged()
    renderDeckHeader()
}

internal fun MainActivity.selectedTabs(): List<Tab> = tabs.tabs.filter { it.id in deckSelected }

/** Closes the chosen tabs, asked first; each is remembered for Reopen closed tab. */
internal fun MainActivity.closeSelectedTabs() {
    val going = selectedTabs().sortedBy { it === tabs.activeTab }
    if (going.isEmpty()) return
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
        .setTitle("Close " + going.size + (if (going.size == 1) " tab?" else " tabs?"))
        .setMessage("Menu > Reopen closed tab brings them back.")
        .setPositiveButton("Close") { _, _ ->
            Haptics.confirm(binding.tabList)
            deckSelecting = false
            deckSelected.clear()
            going.forEach { t ->
                if (!tabs.tabs.contains(t)) return@forEach
                rememberClosed(t)
                closeTabFromDeck(t)
            }
            deckChanged()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

// ---------- Menus ----------

/** A tab card held: what can be done with that tab. */
internal fun MainActivity.showTabMenu(tab: Tab, card: View) {
    if (deckSelecting) { toggleSelected(tab); return }
    Haptics.confirm(card)
    val popup = android.widget.PopupMenu(this, card)
    val m = popup.menu
    val others = tabs.groups.filter { it.id != tab.groupId }
    if (others.isEmpty()) {
        m.add(0, 1, 0, "Add to new group")
    } else {
        val sub = m.addSubMenu(0, 2, 0, "Add to group")
        others.forEachIndexed { i, g -> sub.add(1, 100 + i, i, g.name.ifBlank { "Group" }) }
        sub.add(1, 1, others.size, "New group…")
    }
    if (tab.groupId != null) m.add(0, 3, 1, "Remove from group")
    if (tabs.count() > 1) m.add(0, 4, 2, "Select tabs")
    val site = tab.url.startsWith("https://") || tab.url.startsWith("http://")
    if (site && !tab.isPrivate) {
        m.add(0, 5, 3, if (Bookmarks.isBookmarked(this, tab.url)) "Remove bookmark" else "Add to bookmarks")
    }
    if (site) m.add(0, 6, 4, "Share")
    m.add(0, 7, 5, "Close tab")
    popup.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            1 -> showGroupDialog(null, listOf(tab))
            2 -> Unit
            3 -> {
                tabs.removeFromGroup(tab)
                deckChanged()
            }
            4 -> beginSelecting(tab)
            5 -> {
                if (Bookmarks.isBookmarked(this, tab.url)) {
                    Bookmarks.remove(this, tab.url)
                    toast("Bookmark removed")
                } else {
                    Bookmarks.add(this, PageLabel.title(tab.title, tab.url).ifBlank { tab.title }, tab.url)
                    toast("Bookmarked")
                }
            }
            6 -> shareLink(tab.url, tab.title)
            7 -> closeTabByUser(tab)
            else -> others.getOrNull(item.itemId - 100)?.let { g ->
                tabs.addToGroup(tab, g)
                deckChanged()
                toast("Added to " + g.name.ifBlank { "the group" })
            }
        }
        true
    }
    deckPopup = popup
    popup.setOnDismissListener { if (deckPopup === popup) deckPopup = null }
    popup.show()
}

/** A group card held, or the menu over an open group. */
internal fun MainActivity.showGroupMenu(g: TabGroup, anchor: View) {
    Haptics.confirm(anchor)
    val popup = android.widget.PopupMenu(this, anchor)
    val m = popup.menu
    if (deckOpenGroup != g.id) m.add(0, 1, 0, "Open")
    m.add(0, 2, 1, "Name and colour")
    m.add(0, 3, 2, "Ungroup")
    m.add(0, 4, 3, "Close group")
    popup.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            1 -> openDeckGroup(g)
            2 -> showGroupDialog(g, emptyList())
            3 -> {
                tabs.tabsIn(g).forEach { it.groupId = null }
                tabs.pruneGroups()
                deckChanged()
            }
            4 -> confirmCloseGroup(g)
        }
        true
    }
    deckPopup = popup
    popup.setOnDismissListener { if (deckPopup === popup) deckPopup = null }
    popup.show()
}

/** Closes every tab in [g], asked first; each is remembered for Reopen closed tab. */
internal fun MainActivity.confirmCloseGroup(g: TabGroup) {
    val going = tabs.tabsIn(g).sortedBy { it === tabs.activeTab }
    if (going.isEmpty()) return
    val name = g.name.ifBlank { "this group" }
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
        .setTitle("Close " + name + "?")
        .setMessage(going.size.toString() + (if (going.size == 1) " tab" else " tabs") +
            " will close. Menu > Reopen closed tab brings them back.")
        .setPositiveButton("Close") { _, _ ->
            Haptics.confirm(binding.tabList)
            going.forEach { t ->
                if (!tabs.tabs.contains(t)) return@forEach
                rememberClosed(t)
                closeTabFromDeck(t)
            }
            deckChanged()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** What a new group of [joining] is called until the user says: their site, if they share one. */
internal fun MainActivity.suggestGroupName(joining: List<Tab>): String {
    val sites = joining.mapNotNull { tabGroupKey(it) }.distinct()
    if (sites.size == 1 && joining.all { tabGroupKey(it) != null }) {
        return siteNameOf("https://" + sites[0] + "/").ifBlank { sites[0] }
    }
    var n = tabs.groups.size + 1
    while (tabs.groups.any { it.name == "Group $n" }) n++
    return "Group $n"
}

/**
 * Names and colours a group: a new one for [joining] (from a tab's menu or
 * Select tabs), or [existing] to rename and recolour.
 */
internal fun MainActivity.showGroupDialog(existing: TabGroup?, joining: List<Tab>) {
    val d = resources.displayMetrics.density
    val suggested = existing?.name ?: suggestGroupName(joining)
    val box = android.widget.LinearLayout(this)
    box.orientation = android.widget.LinearLayout.VERTICAL
    box.setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
    val name = android.widget.EditText(this)
    name.setSingleLine()
    name.hint = "Group name"
    name.setText(suggested)
    name.setSelectAllOnFocus(true)
    name.filters = arrayOf(android.text.InputFilter.LengthFilter(40))
    box.addView(name)
    val label = android.widget.TextView(this)
    label.text = "Colour"
    label.textSize = 13f
    label.setTextColor(getColor(R.color.sugIcon))
    label.setPadding(0, (18 * d).toInt(), 0, (8 * d).toInt())
    box.addView(label)
    var chosen = existing?.color ?: TabGroups.freeColor(tabs.groups)
    val swatches = ArrayList<android.widget.ImageView>()
    fun paint() {
        swatches.forEachIndexed { i, v ->
            v.setImageResource(if (i == chosen) R.drawable.deck_check else 0)
            v.contentDescription = TabGroups.NAMES[i] + if (i == chosen) ", chosen" else ""
        }
    }
    repeat(2) { row ->
        val line = android.widget.LinearLayout(this)
        line.orientation = android.widget.LinearLayout.HORIZONTAL
        repeat(4) { col ->
            val i = row * 4 + col
            val cell = android.widget.FrameLayout(this)
            val swatch = android.widget.ImageView(this)
            swatch.scaleType = android.widget.ImageView.ScaleType.CENTER
            swatch.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(TabGroups.color(this@showGroupDialog, i))
            }
            swatch.setOnClickListener { v -> Haptics.tick(v); chosen = i; paint() }
            cell.addView(swatch, android.widget.FrameLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt(),
                android.view.Gravity.CENTER))
            line.addView(cell, android.widget.LinearLayout.LayoutParams(0, (50 * d).toInt(), 1f))
            swatches.add(swatch)
        }
        box.addView(line)
    }
    paint()
    val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
        .setTitle(if (existing == null) "New tab group" else "Name and colour")
        .setView(box)
        .setPositiveButton(if (existing == null) "Create" else "Save") { _, _ ->
            val n = name.text.toString().trim().ifBlank { suggested }
            if (existing == null) {
                val g = tabs.newGroup(n, chosen)
                joining.sortedBy { tabs.tabs.indexOf(it) }.forEach { if (tabs.tabs.contains(it)) tabs.addToGroup(it, g) }
                tabs.pruneGroups()
                deckSelecting = false
                deckSelected.clear()
                Haptics.confirm(binding.tabList)
            } else {
                existing.name = n
                existing.color = chosen
            }
            deckChanged()
        }
        .setNegativeButton("Cancel", null)
        .create()
    dialog.show()
}

/** Whether any site has two or more tabs in no group - what Group tabs by site would gather. */
internal fun MainActivity.siteGroupsPossible(): Boolean =
    tabs.tabs.filter { it.groupId == null }.mapNotNull { tabGroupKey(it) }
        .groupingBy { it }.eachCount().values.any { it >= 2 }

/** A group for each site with two or more tabs in no group, named after the site. */
internal fun MainActivity.groupTabsBySite() {
    val bySite = LinkedHashMap<String, MutableList<Tab>>()
    tabs.tabs.filter { it.groupId == null }.forEach { t ->
        tabGroupKey(t)?.let { bySite.getOrPut(it) { ArrayList() }.add(t) }
    }
    val made = bySite.filter { it.value.size >= 2 }
    made.forEach { (site, list) ->
        val g = tabs.newGroup(siteNameOf("https://$site/").ifBlank { site }, TabGroups.freeColor(tabs.groups))
        list.forEach { tabs.addToGroup(it, g) }
    }
    Haptics.confirm(binding.tabList)
    toast(if (made.size == 1) "Made 1 group" else "Made " + made.size + " groups")
    deckChanged()
}

/**
 * Puts the tabs in the order the deck's cards are in now, after a card was
 * dragged: in an open group, its tabs take the group's places in the list in
 * their new order; otherwise each card in turn, a group card standing for all
 * of its tabs.
 */
internal fun MainActivity.applyDeckOrder() {
    val items = deckAdapter.items
    val open = tabs.group(deckOpenGroup)
    val order: List<Tab> = if (open != null) {
        val members = items.mapNotNull { (it as? DeckItem.TabItem)?.tab }.iterator()
        tabs.tabs.map { t -> if (t.groupId == open.id && members.hasNext()) members.next() else t }
    } else {
        val placed = items.flatMap { item ->
            when (item) {
                is DeckItem.TabItem -> listOf(item.tab)
                is DeckItem.GroupItem -> tabs.tabsIn(item.group)
            }
        }
        placed + tabs.tabs.filter { it !in placed }
    }
    if (order.size != tabs.tabs.size || order.toSet().size != order.size) return
    tabs.tabs.clear()
    tabs.tabs.addAll(order)
}

/** The deck's menu: choosing tabs, grouping by site, and closing many at once. */
internal fun MainActivity.showDeckMenu(anchor: View) {
    val menu = android.widget.PopupMenu(this, anchor)
    if (tabs.count() > 1) menu.menu.add(0, 6, 0, "Select tabs")
    if (siteGroupsPossible()) menu.menu.add(0, 7, 1, "Group tabs by site")
    if (closedTabs.isNotEmpty() && !nightOwl) menu.menu.add(0, 3, 2, "Reopen closed tab")
    val dupes = duplicateTabs()
    if (dupes.isNotEmpty()) menu.menu.add(0, 4, 3, "Close duplicate tabs (" + dupes.size + ")")
    if (tabs.count() > 1) menu.menu.add(0, 2, 4, "Close other tabs")
    menu.menu.add(0, 1, 5, "Close all tabs")
    if (!nightOwl) menu.menu.add(0, 5, 6, "Delete last 15 minutes")
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
            6 -> beginSelecting(null)
            7 -> groupTabsBySite()
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
    val group = tabs.group(tab.groupId)
    closedTabs.addLast(ClosedTab(url, tab.title, state, tabs.tabs.indexOf(tab),
        group?.id, group?.name, group?.color ?: 0))
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
    // Back into its group: the same one if it is still open; else one of the
    // same name and colour made by reopening its neighbours; else made anew.
    if (c.groupName != null) {
        val g = tabs.group(c.groupId)
            ?: tabs.groups.firstOrNull { it.name == c.groupName && it.color == c.groupColor }
            ?: tabs.newGroup(c.groupName, c.groupColor)
        tabs.addToGroup(tab, g)
    }
    if (inDeck && deckVisible) {
        deckChanged()
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
