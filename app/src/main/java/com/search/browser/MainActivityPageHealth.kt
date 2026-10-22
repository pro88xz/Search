package com.search.browser

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.search.browser.MainActivity.Companion.suggestIo
import java.net.URLEncoder
import org.json.JSONObject

// MainActivity, continued: A page that stops answering, Memory, Load measurement, Connection states.
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- Connection states ----------

internal fun MainActivity.beginLoadWatch(tab: Tab, url: String?) {
    loadInterrupted = false
    loadCommitted = false
    loadStartedAt = android.os.SystemClock.uptimeMillis()
    val web = url != null && (url.startsWith("http://") || url.startsWith("https://"))
    loadWatchTab = if (web) tab else null
    loadWatchHost = if (web) {
        try { android.net.Uri.parse(url).host?.removePrefix("www.") } catch (e: Exception) { null }
            ?: ""
    } else ""
    updateLoadStatus()
}

internal fun MainActivity.commitLoadWatch(tab: Tab?) {
    if (tab == null || tab !== loadWatchTab || loadCommitted) return
    loadCommitted = true
    updateLoadStatus()
}

/** The load is over. An interruption stays on show until it is retried. */
internal fun MainActivity.endLoadWatch(tab: Tab?) {
    if (tab == null || tab !== loadWatchTab || loadInterrupted) return
    loadWatchTab = null
    updateLoadStatus()
}

// ---------- Load measurement ----------

/**
 * The host of the website on screen, for the menu's per-site ad blocking
 * row - or null when ad blocking is off or no website is showing.
 */
internal fun MainActivity.siteForAds(): String? {
    if (!AdBlocker.isEnabled(this)) return null
    val url = tabs.activeTab?.url ?: return null
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    return try { android.net.Uri.parse(url).host?.ifBlank { null } } catch (e: Exception) { null }
}

/** Whether the page on screen is still loading. */
internal fun MainActivity.pageLoading(): Boolean = (activeWeb()?.progress ?: 100) in 1..99

internal fun MainActivity.retryInterruptedLoad() {
    loadInterrupted = false
    val tab = loadWatchTab
    loadWatchTab = null
    updateLoadStatus()
    val web = activeWeb() ?: return
    if (tab === tabs.activeTab) web.reload()
}

internal fun MainActivity.isErrorPage(url: String?): Boolean =
    url != null && (url.startsWith("file:///android_asset/offline.html") ||
        url.startsWith("file:///android_asset/error.html")) &&
        // A page stopped for not responding is not a failure to retry by
        // itself: it would only stop everything again (stoppedPage).
        !url.contains("stopped=1")

/**
 * The page shown in place of one the user ended for not responding: what
 * happened, and a Try again that goes back to [url] only when tapped.
 */
internal fun MainActivity.stoppedPage(url: String, crashed: Boolean = false): String {
    val base = "file:///android_asset/error.html?stopped=1" + (if (crashed) "&crashed=1" else "")
    return try {
        base + "&u=" + java.net.URLEncoder.encode(url, "UTF-8")
    } catch (e: Exception) { base }
}

/**
 * Pictures off: Data saver on, or Save data on mobile data while the
 * phone is on a metered network (mobile data, a hotspot).
 */
internal fun MainActivity.imagesBlocked(): Boolean {
    if (Settings.getBool(this, Settings.SITE_BLOCK_IMAGES, false)) return true
    if (!Settings.getBool(this, Settings.DATA_SAVER_MOBILE, false)) return false
    return try {
        (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
    } catch (e: Exception) { false }
}

/** On a change of network: every tab's pictures on or off to match, at once. Turning them on loads them. */
internal fun MainActivity.applyDataSaverNow() {
    val b = imagesBlocked()
    if (b == lastImagesBlocked) return
    lastImagesBlocked = b
    tabs.tabs.forEach { t -> try { t.webView?.settings?.blockNetworkImage = b } catch (e: Exception) {} }
}

internal fun MainActivity.cleanLinksOn() = Settings.getBool(this, Settings.CLEAN_LINKS, true)

/** [url] as it is copied or shared: without its tracking tags, while that is on (LinkCleaner). */
internal fun MainActivity.shareable(url: String): String = if (cleanLinksOn()) LinkCleaner.clean(url) ?: url else url

internal fun MainActivity.siteSettingsSignature(): String {
    return listOf(
        Settings.getBool(this, Settings.SITE_JAVASCRIPT, true),
        imagesBlocked(),
        Settings.getBool(this, Settings.SITE_BLOCK_AUTOPLAY, true),
        // Both of these were read once, when a tab's WebView was built, and
        // never looked at again. Toggling either did nothing to any tab
        // already open - and unlike ad blocking, which is consulted per
        // request, a reload did not help: they are properties of the
        // WebView, not of the page in it.
        Settings.getBool(this, Settings.SEC_SAFE_BROWSING, true),
        Settings.getBool(this, Settings.SEC_BLOCK_3P_COOKIES, false),
        Settings.getTextScale(this),
        SiteZoom.signature(this),
        Settings.getBool(this, Settings.DARK_WEB, false)
    ).joinToString("|")
}

/**
 * Stretches [v] down over the strip under the navigation bar, which the
 * root leaves as padding, so its background runs to the bottom of the
 * screen as the bottom bar's does. The strip is added to its bottom
 * padding, so nothing in it is laid out behind Android's buttons.
 * [padding] is the bottom padding it would have without the strip.
 */
internal fun MainActivity.reachUnderNavBar(v: View, strip: Int,
                             padding: Int = navPadBase.getOrPut(v) { v.paddingBottom }) {
    val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    if (lp.bottomMargin != -strip) {
        lp.bottomMargin = -strip
        v.layoutParams = lp
    }
    if (v.paddingBottom != padding + strip) {
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, padding + strip)
    }
}

internal fun MainActivity.setupSuggestOverlay() {
    if (suggestBasePad < 0) suggestBasePad = binding.suggestBackdrop.paddingBottom
    suggestAdapter = SuggestAdapter(emptyList(), { item ->
        if (suggestListMoving()) return@SuggestAdapter
        val kind = item.optString("kind")
        val title = item.optString("title")
        val url = item.optString("url")
        when (kind) {
            // An open tab: go to it rather than loading the page again.
            "tab" -> {
                exitSearchMode()
                val id = item.optLong("tabId", -1L)
                tabs.tabs.firstOrNull { it.id == id }?.let { openTab(it) }
            }
            // The copied link, read now if it was not read to offer it.
            "clip" -> {
                val link = url.ifBlank { readClipboardText() ?: "" }
                exitSearchMode()
                if (link.isNotBlank()) go(link)
            }
            else -> {
                exitSearchMode()
                if (kind == "web" || url.isBlank()) go(title) else loadInto(activeWeb(), url)
            }
        }
    }, { item ->
        // The arrow loads a suggestion into the box instead of running it, so
        // a near-miss can be edited rather than retyped. The box's own text
        // watcher refreshes the list, so nothing is fetched twice here.
        if (suggestListMoving()) return@SuggestAdapter
        val fill = if (item.optString("kind") == "clip" && item.optString("url").isBlank()) {
            readClipboardText() ?: ""
        } else {
            item.optString("fill")
                .ifBlank { item.optString("url") }
                .ifBlank { item.optString("title") }
        }
        binding.urlBar.setText(fill)
        binding.urlBar.setSelection(fill.length)
    })
    // Rows are clipped to the card's rounded corners. Set here rather
    // than in XML: android:clipToOutline is API 31+, and this app runs
    // from 24, where it would be ignored and the corner would square off
    // under a ripple.
    binding.suggestCard.clipToOutline = true
    // The card starts hidden and stays hidden whenever it is empty.
    //
    // It is wrap_content with 8dp of padding above and below, so with no
    // rows it still measures 16dp and paints every one of them white: a
    // thin strip under the search box that looks like a rendering fault.
    // This did not show before the card, because the list was the page
    // and an empty page is just a page.
    //
    // An adapter observer rather than a check after each fetch, so it
    // holds for every route to empty - the clear at the end of
    // animateSearchOut, a query with no results, and the gap between
    // search opening and the first suggestion arriving.
    binding.suggestCard.visibility = View.GONE
    suggestAdapter?.registerAdapterDataObserver(
        object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            private fun sync() {
                // The whole card, heading included. Hiding only the list
                // would leave a white box with a heading and nothing
                // under it, which is worse than the strip this replaced.
                binding.suggestCard.visibility =
                    if ((suggestAdapter?.itemCount ?: 0) > 0) View.VISIBLE
                    else View.GONE
            }
            override fun onChanged() = sync()
            override fun onItemRangeInserted(start: Int, count: Int) = sync()
            override fun onItemRangeRemoved(start: Int, count: Int) = sync()
        })
    binding.suggestOverlay.layoutManager =
        androidx.recyclerview.widget.LinearLayoutManager(this)
    binding.suggestOverlay.adapter = suggestAdapter
    binding.suggestOverlay.addOnScrollListener(
        object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int
            ) {
                if (dy != 0) lastSuggestScroll = android.os.SystemClock.uptimeMillis()
            }
        })
}

internal fun MainActivity.fetchSuggests(query: String) {
    val id = ++suggestSeq
    val q = query.trim()
    // Gathered here, on the main thread, where the tab list and the
    // clipboard live: the link just copied, then open tabs that match.
    val first = mutableListOf<JSONObject>()
    copiedLinkSuggestion(q)?.let { first += it }
    first += openTabSuggestions(q)
    val firstUrls = first.map { it.optString("url") }.filter { it.isNotBlank() }.toSet()
    try {
        suggestIo.execute {
            if (id != suggestSeq) return@execute
            // On this executor's thread an uncaught throw ends the app;
            // a suggestion list that cannot be built is just the first part.
            val more = try { buildSuggestions(q) } catch (t: Throwable) { emptyList() }
            val items = first + more.filter { it.optString("url") !in firstUrls }
            runOnUiThread {
                if (id == suggestSeq && searchMode) suggestAdapter?.submit(items, q)
            }
        }
    } catch (e: java.util.concurrent.RejectedExecutionException) { /* closing down */ }
}

/**
 * Open tabs matching what is typed, offered as "Switch to tab" - so typing
 * the name of a site already open goes to it instead of opening it twice.
 * Night Owl's tabs and the others are never offered across the line.
 */
internal fun MainActivity.openTabSuggestions(q: String): List<JSONObject> {
    if (q.length < 2) return emptyList()
    val active = tabs.activeTab
    return tabs.tabs.asSequence()
        .filter { it !== active && it.isPrivate == nightOwl }
        .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
        .filter { matchesQuery(it.title, it.url, q) }
        .take(2)
        .map { t ->
            JSONObject()
                .put("kind", "tab")
                .put("title", PageLabel.title(t.title, t.url).ifBlank { siteNameOf(t.url) })
                .put("url", t.url)
                .put("sub", "Switch to tab \u00B7 " + siteNameOf(t.url))
                .put("tabId", t.id)
        }
        .toList()
}

/**
 * A link copied in the last ten minutes, offered when the box is empty.
 *
 * From Android 12 reading the clipboard shows a "pasted from your
 * clipboard" notice, so there it is not read just to make the offer: the
 * system's own description says whether it holds a link, and the link is
 * only read when the suggestion is picked. Before Android 12 there is no
 * notice, and the link itself is shown.
 */
internal fun MainActivity.copiedLinkSuggestion(q: String): JSONObject? {
    if (q.isNotEmpty()) return null
    return try {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val desc = cm.primaryClipDescription ?: return null
        if (!desc.hasMimeType("text/*")) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val age = System.currentTimeMillis() - desc.timestamp
            if (age < 0 || age > 10 * 60 * 1000L) return null
        }
        val offer = JSONObject().put("kind", "clip").put("title", "Link you copied")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isLink = desc.classificationStatus ==
                android.content.ClipDescription.CLASSIFICATION_COMPLETE &&
                desc.getConfidenceScore(android.view.textclassifier.TextClassifier.TYPE_URL) >= 0.5f
            if (!isLink) return null
            offer.put("url", "").put("sub", "Tap to open it")
        } else {
            val text = readClipboardText() ?: return null
            if (!looksLikeLink(text)) return null
            offer.put("url", text).put("sub", PageLabel.siteOf(text).ifEmpty { text })
        }
    } catch (e: Exception) { null }
}

/** The clipboard's text, or null. */
internal fun MainActivity.readClipboardText(): String? = try {
    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        ?.coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length < 2048 }
} catch (e: Exception) { null }

internal fun MainActivity.looksLikeLink(text: String): Boolean =
    !text.contains(' ') && !text.contains('\n') &&
        android.util.Patterns.WEB_URL.matcher(text).matches()

internal fun MainActivity.styleUrlBarForSearch(searching: Boolean) {
    styleUrlBar(
        when {
            searching -> FIELD_SEARCH
            homeCompact -> FIELD_COMPACT
            else -> FIELD_NORMAL
        })
}

/**
 * The trailing controls swap on content: mic and scan while the box is
 * empty, one clear button once there is something to clear. Three controls
 * in a 50dp pill is too crowded, and the end inset has to move with them or
 * long text runs underneath whichever pair is showing.
 */
internal fun MainActivity.updateSearchFieldActions(
    top: Int = binding.urlBar.paddingTop,
    bottom: Int = binding.urlBar.paddingBottom
) {
    val d = resources.displayMetrics.density
    val empty = binding.urlBar.text.isNullOrEmpty()
    binding.searchActions.visibility = if (empty) View.VISIBLE else View.GONE
    binding.clearBtn.visibility = if (empty) View.GONE else View.VISIBLE
    // Empty: mic 48 + 9dp divider + scan 48 + 8dp margin, plus breathing
    // room. Otherwise: clear 48 + its 7dp margin, same. Both grew with the
    // controls; left as they were, a long address would run under them.
    // The site's icon, when it shows, sits in front of the text.
    val start = if (binding.siteIcon.visibility == View.VISIBLE) 50 else 26
    binding.urlBar.setPaddingRelative(
        (start * d).toInt(), top, ((if (empty) 120 else 60) * d).toInt(), bottom)
}

// ---------- A page that stops answering ----------

@android.annotation.TargetApi(Build.VERSION_CODES.Q)
internal fun MainActivity.onPageUnresponsive(view: WebView, renderer: android.webkit.WebViewRenderProcess?) {
    DebugLog.add { "page not responding in tab#" + tabOf(view)?.id }
    // Any tab, not only the one on screen: they all share the renderer.
    showPagesStuck(view, renderer)
}

internal fun MainActivity.onPageResponsive(view: WebView) {
    DebugLog.add { "page responding again in tab#" + tabOf(view)?.id }
    hangQuietUntil = 0L
    unresponsiveDialog?.dismiss()
    unresponsiveDialog = null
}

/**
 * Offers the way out of a stuck page: wait, or end it. Ending it ends the
 * renderer, so every page restarts - the stuck one included. On Android 9
 * and below a renderer cannot be ended on its own, and pages still stuck
 * just after one was ended are not held up by it, so in those two cases
 * the offer is to restart Search, whose tabs come back from disk
 * (SessionStore).
 *
 * [renderer] is the WebViewRenderProcess when Android reported the hang,
 * typed loosely so this compiles into code that also runs before Android 10.
 */
internal fun MainActivity.showPagesStuck(view: WebView?, renderer: Any?) {
    if (unresponsiveDialog != null || isFinishing || isDestroyed) return
    val now = android.os.SystemClock.uptimeMillis()
    if (now < hangQuietUntil) return
    HealthStats.count(this, HealthStats.PAGE_UNRESPONSIVE)
    val endedRecently = rendererEndedAt != 0L && now - rendererEndedAt < ENDED_RECENTLY_MS
    val canEnd = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !endedRecently
    if (endedRecently) DebugLog.add { "pages still stuck after ending the page: offering a restart" }
    unresponsiveDialog = android.app.AlertDialog.Builder(this)
        .setTitle(if (endedRecently) "Pages still aren't loading" else "This page isn't responding")
        .setMessage(when {
            endedRecently -> "Ending the page didn't free them. Restart Search to clear " +
                "it - your tabs will come back."
            canEnd -> "It's holding up every tab. Wait for it, or end it - " +
                "your tabs will reload."
            else -> "It's holding up every tab. Wait for it, or restart Search - " +
                "your tabs will come back."
        })
        .setNegativeButton("Wait") { _, _ ->
            hangQuietUntil = android.os.SystemClock.uptimeMillis() + HANG_WAIT_MS
        }
        .setPositiveButton(if (canEnd) "End it" else "Restart") { _, _ ->
            endStuckPages(view, renderer, restart = !canEnd)
        }
        .setOnDismissListener { unresponsiveDialog = null }
        .show()
}

/**
 * Ends this process and opens Search again in a new one (RestartActivity).
 * The tabs are written to disk first and restored on the way back up.
 */
internal fun MainActivity.restartBrowser() {
    tabs.tabs.firstOrNull { it.id == stoppedTabId }?.let { t ->
        if (t.url.startsWith("http://") || t.url.startsWith("https://")) t.stoppedUrl = t.url
    }
    saveSession(full = true)
    SessionStore.flush(1500L)
    try {
        // RestartActivity, in a process of its own, ends this one and
        // opens the browser again.
        RestartActivity.restart(this)
    } catch (e: Exception) {
        // Could not be started: close instead, and the tabs come back
        // from disk when Search is next opened.
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/**
 * After asking [tab] for a local page - home, or the page standing in for
 * one the user ended - checks [afterMs] later that it started. A local
 * file starts in milliseconds; one that has not started means the pages
 * are stuck.
 */
internal fun MainActivity.expectPageStart(tab: Tab, afterMs: Long = 4000L) {
    val started = tab.loadsStarted
    uiHandler.postDelayed({
        if (isFinishing || isDestroyed || !tabs.tabs.contains(tab)) return@postDelayed
        if (tab.loadsStarted != started) return@postDelayed
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            return@postDelayed
        }
        val web = tab.webView ?: return@postDelayed
        DebugLog.add { "tab#" + tab.id + " did not start loading: pages stuck" }
        showPagesStuck(web, null)
    }, afterMs)
}

// ---------- Memory ----------

/**
 * How many tabs keep a live page, by how much memory the phone has. Every
 * live tab's page sits in the same renderer process, and when Android
 * needs memory back it kills that process - every page at once - so a
 * small phone keeps fewer, and a big one keeps enough that switching tabs
 * does not mean waiting for a reload.
 */
internal fun MainActivity.liveTabBudget(): Int {
    val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
    if (am.isLowRamDevice) return 2
    val info = android.app.ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    val gb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
    return when {
        gb < 3.0 -> 2
        gb < 5.0 -> 3
        gb < 7.0 -> 4
        else -> 5
    }
}

internal fun MainActivity.openTab(tab: Tab, loadUrl: String? = null) {
    endPullRefresh(now = true)
    showBars(animate = false)
    schedulePageTint(350L)
    setFoundVideos(emptyList(), null)
    scheduleVideoScan(600L)
    // The tab being left keeps a picture of itself for the deck, taken
    // while it is still on screen. PixelCopy reads what the display already
    // shows and asks the page for nothing. freezeTab used to draw the
    // WebView into a software canvas instead, which makes Chromium render a
    // frame on the main thread and wait for it - on a busy page, a wait of
    // no fixed length, and an ANR. captureThumbnail skips anything not on
    // screen, and nothing is taken while the deck is up, because the deck
    // is what would be in the picture.
    val leaving = tabs.activeTab
    if (leaving != null && leaving !== tab &&
        binding.menuScrim.visibility != View.VISIBLE
    ) captureThumbnail(leaving)
    // Already on screen - the current tab picked in the deck - so it stays
    // attached. Taking a WebView off the window and putting it back makes
    // it release its drawing resources and claim them again, each time in
    // step with the render thread, and the main thread waits for that.
    val shown = binding.webContainer.childCount == 1 &&
        binding.webContainer.getChildAt(0) === tab.webView
    if (!shown) binding.webContainer.removeAllViews()
    if (tab.webView == null) {
        // A tab restored from disk reads its history now, when first shown.
        tab.diskStateFile?.let { f ->
            if (tab.savedState == null) tab.savedState = SessionStore.readState(f)
            tab.diskStateFile = null
        }
        val web = newWebView()
        tab.webView = web
        val restored = tab.savedState?.let { web.restoreState(it) != null } ?: false
        // Spent. Kept, it would be restored again over newer pages the
        // next time this tab is rebuilt - after a renderer loss, say.
        tab.savedState = null
        if (!restored) loadInto(web, loadUrl ?: tab.url)
    } else if (loadUrl != null) {
        loadInto(tab.webView, loadUrl)
    }
    if (!shown) binding.webContainer.addView(tab.webView)
    // A tab switch eases in rather than cutting (not during a swipe,
    // which carries its own movement, nor with animations off).
    if (!shown && leaving != null && leaving !== tab && swipeNeighbor == null && animationsOn()) {
        tab.webView?.let { w ->
            w.animate().cancel()
            w.alpha = 0.35f
            w.animate().alpha(1f).setDuration(160L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .withEndAction { w.alpha = 1f }
                .start()
        }
    }
    tabs.setActive(tab)
    tabs.markLive(tab)
    // The loading ring shows this tab's load, not the one just left, and
    // so does the connection pill.
    binding.loadRing.jumpTo(tab.webView?.progress ?: 0)
    binding.navOwl.setLoad(tab.webView?.progress ?: 0, jump = true)
    updateLoadStatus()
    // A tab left on the offline page tries again when opened, if the
    // network has come back since.
    val failed = tab.failedUrl
    if (failed != null && loadUrl == null && isErrorPage(tab.webView?.url) && hasNetwork()) {
        uiHandler.post { if (tabs.activeTab === tab) loadInto(tab.webView, failed) }
    }
    binding.urlBar.setText(displayUrl(tab.url))
    updateTabCount()
    refreshStar()
    refreshOmniboxVisibility(tab.url)
    // A sign-in pop-up that came back while another tab was showing is
    // only judged while on screen, so the watch starts again here.
    if (tab.openerId != null && returnedFromSignIn(tab)) watchReturnedPopup(tab, tab.url)
    scheduleSessionSave()
    if (tab.url == homePage) refreshResumeCard()
}

/**
 * The home/new-tab page has its own search field under the wordmark,
 * so the native address bar (and the star button riding along with it)
 * is hidden while it's showing — that top-bar space just sits empty,
 * the same way it does on any other page before you start typing.
 * Everywhere else, the native bar behaves exactly as it always has.
 */
/** Opens Android's native share sheet for a feed article. */
internal fun MainActivity.shareLink(link: String, title: String) {
    if (link.isBlank()) return
    val url = shareable(link)
    try {
        val text = if (title.isBlank()) url else "$title\n$url"
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        }
        startActivity(android.content.Intent.createChooser(send, "Share via"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(this, "Couldn't share", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * The views that give up their width and fade as the bar collapses: the
 * owl and the gear. Once home's search box has scrolled away it takes the
 * top on its own, as wide as it is on the page.
 */
internal fun MainActivity.homeBarRow(): List<View> = listOf(binding.homeBtn, binding.settingsBtn)

/**
 * One continuous position between the two bar states, where 1 is the full
 * icon row and 0 is the bare pill. The icons give up their width as they
 * fade, and because the pill is weighted it takes that space on the same
 * frame - so the two states cross over rather than one replacing the other.
 */
internal fun MainActivity.setHomeBarProgress(t: Float) {
    homeBarProgress = t
    val widths = homeRowWidths()
    homeBarRow().forEachIndexed { i, v ->
        val lp = v.layoutParams
        lp.width = (widths[i] * t).toInt()
        v.layoutParams = lp
        v.alpha = t
    }
    binding.urlBarContainer.alpha = 1f - t
    // And the bar fades into the home page's background, its line and
    // shadow with it, so the box sits on the page as it does further down.
    binding.topBar.blend = 1f - t
    // And it arrives rather than appearing. t is 1 for the icon row and 0
    // for the pill, so the pill starts SLIDE_DP below its resting place and
    // reaches it exactly as it reaches full opacity. The bar sets
    // clipChildren="false" for this: the compact pill is 44dp inside a 58dp
    // bar, leaving 7dp of room, so the travel would otherwise be sheared
    // off at the bar's edge.
    binding.urlBarContainer.translationY =
        SLIDE_DP * t * resources.displayMetrics.density
    // The pill's height rides the same curve as its opacity and the icons'
    // width. It used to be set once, instantly, at the start of a collapse
    // and at the end of an expand - so the size changed in a single frame
    // while everything else moved over a quarter of a second, and it did it
    // at opposite ends of the two directions. That mismatch is most of what
    // reads as the bar snapping into place rather than arriving.
    val lp = binding.urlBarContainer.layoutParams
    lp.height = (((FIELD_H_COMPACT * (1f - t)) + (FIELD_H_NORMAL * t)) *
        resources.displayMetrics.density).toInt()
    binding.urlBarContainer.layoutParams = lp
}

internal fun MainActivity.snapHomeBar(compact: Boolean) {
    homeBarProgress = if (compact) 0f else 1f
    val widths = homeRowWidths()
    homeBarRow().forEachIndexed { i, v ->
        val lp = v.layoutParams
        lp.width = widths[i]
        v.layoutParams = lp
        v.alpha = 1f
        v.translationY = 0f
        v.visibility = if (compact) View.GONE else View.VISIBLE
    }
    binding.starBtn.visibility = View.GONE
    binding.urlBarContainer.alpha = 1f
    binding.urlBarContainer.translationY = 0f
    binding.urlBarContainer.visibility = if (compact) View.VISIBLE else View.INVISIBLE
    binding.topBar.blend = if (compact) 1f else 0f
    styleUrlBar(if (compact) FIELD_COMPACT else FIELD_NORMAL)
}

/**
 * The owl and the gear back in full and the bar its own colour: for any
 * page but home, and for home before it says whether its search box has
 * scrolled away. Not while searching, which keeps the gear away itself.
 */
internal fun MainActivity.resetHomeBarRow() {
    if (searchMode) return
    if (fieldMode == FIELD_COMPACT) styleUrlBar(FIELD_NORMAL)
    if (homeBarProgress == 1f && binding.topBar.blend == 0f &&
        homeBarRow().all { it.visibility == View.VISIBLE }
    ) return
    // Stops a collapse under way without letting it finish (homeBarSeq).
    homeBarSeq++
    homeBarAnim?.cancel()
    homeBarAnim = null
    homeBarProgress = 1f
    val widths = homeRowWidths()
    homeBarRow().forEachIndexed { i, v ->
        val lp = v.layoutParams
        lp.width = widths[i]
        v.layoutParams = lp
        v.alpha = 1f
        v.translationY = 0f
        v.visibility = View.VISIBLE
    }
    binding.urlBarContainer.alpha = 1f
    binding.urlBarContainer.translationY = 0f
    binding.topBar.blend = 0f
}

internal fun MainActivity.applyHomeCompact(compact: Boolean, animate: Boolean = false) {
    homeCompact = compact
    if (searchMode || deckVisible) return
    val url = tabs.activeTab?.url
    if (!(url == null || url == homePage)) return
    if (compact && binding.urlBar.text.isNotEmpty()) binding.urlBar.setText("")

    homeBarAnim?.cancel()
    homeBarAnim = null
    val seq = ++homeBarSeq

    // This read the owl's visibility, which told the truth only while the
    // owl collapsed with the row. It does not any more. The pill's own
    // visibility does: snapHomeBar sets it VISIBLE when compact and
    // INVISIBLE when the icon row holds the bar.
    val settled = binding.urlBarContainer.visibility ==
        (if (compact) View.VISIBLE else View.INVISIBLE)
    if (!animate || settled) { snapHomeBar(compact); return }

    // Collapsing: the pill takes its final styling now - background, text
    // size, hint - while it is still fully transparent, so none of that is
    // seen changing. Its height is no longer set here; that is animated in
    // setHomeBarProgress along with everything else.
    if (compact) styleUrlBar(FIELD_COMPACT)

    homeBarRow().forEach { it.visibility = View.VISIBLE; it.translationY = 0f }
    binding.urlBarContainer.visibility = View.VISIBLE
    binding.urlBarContainer.translationY = 0f

    // Start from wherever a cancelled transition left the row, so reversing
    // mid-way continues from the current position instead of jumping.
    //
    // This used to read the row's width as if it were progress. It is not:
    // snapHomeBar sets every width back to full and hides the row with
    // visibility, so after a completed collapse the width claimed 1.0 while
    // the row sat at 0. Expanding then measured a span of 0 and ran for the
    // 90ms floor instead of its 440ms - the abrupt snap back. Reversing
    // mid-flight was equally wrong, despite what the line above promised.
    val from = homeBarProgress
    val to = if (compact) 0f else 1f
    // Expanding is the slower half: things arriving on screen should settle,
    // where things leaving can go quickly. Duration also scales with the
    // distance left, so reversing part-way does not spend a full beat
    // covering a sliver.
    val span = kotlin.math.abs(to - from)
    // One motion, whichever way it is going.
    //
    // Collapsing ran 240ms on a sharper curve and expanding 440ms on a
    // softer one - nearly twice as long coming back as going away, on a
    // different easing. The reasoning was that things arriving should
    // settle while things leaving can go quickly, which is sound for two
    // different elements and wrong for one element moving between two
    // states: the eye reads the pair as the same control behaving
    // inconsistently, not as considered asymmetry.
    //
    // 300ms on Material's standard easing, both directions. Still scaled by
    // the distance left to cover, so reversing part-way through does not
    // spend a full beat crossing a sliver.
    val anim = android.animation.ValueAnimator.ofFloat(from, to).apply {
        duration = (300 * span).toLong().coerceAtLeast(120L)
        interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        addUpdateListener { a -> setHomeBarProgress(a.animatedValue as Float) }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                if (seq != homeBarSeq) return
                homeBarAnim = null
                snapHomeBar(compact)
            }
        })
    }
    homeBarAnim = anim
    anim.start()
}
