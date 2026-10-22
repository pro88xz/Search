package com.search.browser

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import com.search.browser.MainActivity.SiteIconOutline
import com.search.browser.MainActivity.TabStore
import java.net.URLEncoder

// MainActivity, continued: The site's icon in the search field.
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- The site's icon in the search field ----------

/**
 * The site's crisp icon at the start of the address box: there as soon as
 * the page has loaded, and kept while a search is typed from it. Nothing
 * on the home page or on pages that are not websites. [fetch]: the page
 * has loaded, so its declared icons can be asked for; before that only an
 * icon already known for the site is shown, never the last site's.
 */
internal fun MainActivity.showSiteIcon(url: String?, fetch: Boolean = true) {
    val key = SiteIcons.keyOf(url)
    val web = activeWeb()
    if (url == null || key == null || web == null || url == homePage || isErrorPage(url)) {
        hideSiteIcon(); return
    }
    val ready = SiteIcons.cached(url)
    if (ready != null) { setSiteIcon(ready); return }
    if (!fetch) { hideSiteIcon(); return }
    // WebView's own small icon while the crisp one is fetched, copied
    // because WebView may recycle the one it holds.
    web.favicon?.let { f ->
        try { setSiteIcon(f.copy(f.config ?: Bitmap.Config.ARGB_8888, false)) } catch (e: Exception) { }
    }
    SiteIcons.load(this, web, url, nightOwl) { icon ->
        if (SiteIcons.keyOf(tabs.activeTab?.url) == key) setSiteIcon(icon)
    }
}

internal fun MainActivity.setSiteIcon(icon: Bitmap) {
    val v = binding.siteIcon
    if (v.outlineProvider !is SiteIconOutline) {
        v.outlineProvider = SiteIconOutline
        v.clipToOutline = true
    }
    v.setImageBitmap(icon)
    if (v.visibility != View.VISIBLE) {
        v.visibility = View.VISIBLE
        padFieldForIcon()
    }
}

internal fun MainActivity.hideSiteIcon() {
    val v = binding.siteIcon
    if (v.visibility == View.GONE) return
    v.visibility = View.GONE
    v.setImageDrawable(null)
    padFieldForIcon()
}

/** Moves the field's text clear of the icon, or back, for whichever look the field has. */
internal fun MainActivity.padFieldForIcon() {
    if (fieldMode != FIELD_NORMAL) { updateSearchFieldActions(); return }
    val d = resources.displayMetrics.density
    val start = if (binding.siteIcon.visibility == View.VISIBLE) 48 else 14
    binding.urlBar.setPaddingRelative((start * d).toInt(), binding.urlBar.paddingTop,
        (56 * d).toInt(), binding.urlBar.paddingBottom)
}

/**
 * The field rises, and the sheet follows it up.
 *
 * The stagger is the whole effect: 40ms behind and travelling further, the
 * suggestions read as being pulled up BY the field rather than the two
 * appearing together. Without it they arrive as one block and it looks
 * like a screen being swapped rather than a bar being opened.
 *
 * [fieldWasShowing] is why this does not blink on a web page. There the
 * bar is already on screen with the address in it, so fading it from zero
 * would flash it out and back. Only the home page, where the field is not
 * there at rest, gets the rise.
 */
internal fun MainActivity.animateSearchIn(fieldWasShowing: Boolean) {
    val d = resources.displayMetrics.density
    val bar = binding.urlBarContainer
    val sheet = binding.suggestBackdrop

    bar.animate().cancel()
    sheet.animate().cancel()

    if (!fieldWasShowing) {
        bar.alpha = 0f
        bar.translationY = 18 * d
        bar.animate().alpha(1f).translationY(0f)
            .setDuration(300L).setInterpolator(searchEase)
            // The field must be visible when this lands, whatever else ran
            // during it. A missing address bar in search mode is a dead end
            // for the user, so it is worth stating outright rather than
            // trusting every other writer to behave.
            .withEndAction { bar.alpha = 1f; bar.translationY = 0f }
            .start()
    } else {
        bar.alpha = 1f
        bar.translationY = 0f
    }

    sheet.alpha = 0f
    sheet.translationY = 44 * d
    sheet.animate().alpha(1f).translationY(0f)
        .setDuration(380L).setStartDelay(60L)
        .setInterpolator(searchEase)
        // The page now hides the owl: it stops drawing until search closes.
        .withEndAction { if (searchMode) binding.navOwl.covered = true }
        .start()
}

/**
 * The reverse, and deliberately quicker - leaving should feel like getting
 * out of the way, not like a second performance.
 *
 * The rows are cleared at the end rather than the start, or the sheet
 * would empty itself and then fade an empty panel out.
 */
internal fun MainActivity.animateSearchOut() {
    binding.navOwl.covered = false
    val d = resources.displayMetrics.density
    val sheet = binding.suggestBackdrop
    sheet.animate().cancel()
    sheet.animate().alpha(0f).translationY(28 * d)
        .setDuration(210L).setInterpolator(searchEase)
        .withEndAction {
            // Search may have been re-entered while this was running, in
            // which case the sheet is wanted and must not be hidden.
            if (!searchMode) {
                sheet.visibility = View.GONE
                suggestAdapter?.submit(emptyList())
            }
            sheet.alpha = 1f
            sheet.translationY = 0f
        }.start()
}

internal fun MainActivity.enterSearchMode() {
    if (searchMode) return
    showBars(animate = false)
    searchMode = true
    // The bar is non-focusable at rest; make it typable now that the user
    // has deliberately entered search mode. This is the ONLY place focus is
    // enabled, so system/incidental focus can never trigger search mode.
    binding.urlBar.isFocusable = true
    binding.urlBar.isFocusableInTouchMode = true
    // The owl stays on the search page, so the field runs from the owl
    // to the right edge rather than replacing it - the gear steps aside.
    binding.starBtn.visibility = View.GONE
    binding.settingsBtn.visibility = View.GONE
    // The home bar's collapse animation writes urlBarContainer.alpha
    // directly, from setHomeBarProgress, and it is a ValueAnimator - so the
    // animate().cancel() in animateSearchIn does not reach it. Left running
    // it keeps overwriting the entrance, and if it settles on the expanded
    // state that value is 0: search opens with no field at all, and the
    // only way out is Back. Rare, because the tap has to land inside the
    // animation's 300ms.
    //
    // Cancelled here rather than in animateSearchIn so that searchMode is
    // already true, which also stops applyHomeCompact starting a new one.
    homeBarAnim?.cancel()
    homeBarAnim = null
    // The owl stays on the search page even when search starts from
    // home's pinned box, which had sent it away.
    homeRowWidths().getOrNull(0)?.let { w ->
        val owl = binding.homeBtn
        val lp = owl.layoutParams
        lp.width = w
        owl.layoutParams = lp
        owl.alpha = 1f
        owl.visibility = View.VISIBLE
    }

    // Captured before it is shown: on home the field is not on screen at
    // rest, on a page it already is, and the two want different entrances.
    val fieldWasShowing = binding.urlBarContainer.visibility == View.VISIBLE
    binding.urlBarContainer.visibility = View.VISIBLE
    styleUrlBarForSearch(true)
    val current = tabs.activeTab?.url
    val onHome = (current == null || current == homePage)
    if (onHome) binding.urlBar.setText("") else {
        binding.urlBar.setText(current); binding.urlBar.selectAll()
    }
    if (onHome) hideSiteIcon() else showSiteIcon(current)
    binding.urlBar.requestFocus()
    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    imm.showSoftInput(binding.urlBar, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    binding.suggestBackdrop.visibility = View.VISIBLE
    androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
    // The search page, as Chrome's: the field and the suggestions are
    // two cards of the same colour on a background of their own, and the
    // top bar goes flat in that background - no curve, shadow or line.
    // Night Owl's wash for the background while Night Owl is on.
    val pageBg = if (nightOwl) binding.topBar.surfaceColor else getColor(R.color.searchPageBg)
    binding.topBar.flatColor = pageBg
    binding.topBar.flat = true
    applyBarForeground()
    binding.suggestBackdrop.setBackgroundColor(pageBg)
    animateSearchIn(fieldWasShowing)
    fetchSuggests(binding.urlBar.text.toString())
}

internal fun MainActivity.exitSearchMode() {
    if (!searchMode) return
    searchMode = false
    styleUrlBarForSearch(false)
    animateSearchOut()
    // The bar's own look back.
    binding.topBar.flat = false
    applyBarForeground()
    // Nothing transient left on the field: applyHomeCompact below may
    // animate it, and it should start from rest rather than from whatever
    // the entrance left behind.
    binding.urlBarContainer.animate().cancel()
    binding.urlBarContainer.alpha = 1f
    binding.urlBarContainer.translationY = 0f
    binding.homeBtn.visibility = View.VISIBLE
    binding.starBtn.visibility = View.VISIBLE
    binding.settingsBtn.visibility = View.VISIBLE
    binding.urlBar.clearFocus()
    // Return the bar to non-focusable at rest so nothing but an explicit
    // tap (which re-enables focus via enterSearchMode) can re-open search.
    binding.urlBar.isFocusable = false
    binding.urlBar.isFocusableInTouchMode = false
    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    val current = tabs.activeTab?.url
    val onHome = (current == null || current == homePage)
    if (onHome) {
        binding.urlBarContainer.visibility = View.INVISIBLE
        binding.urlBar.setText("")
    } else {
        binding.urlBarContainer.visibility = View.VISIBLE
        binding.urlBar.setText(displayUrl(current))
    }
    // The page may still be scrolled past its pill; re-apply the compact bar.
    if (onHome && homeCompact) applyHomeCompact(true)
    if (onHome) hideSiteIcon() else showSiteIcon(current, fetch = false)
    schedulePageTint(220L)
    androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
}

internal fun MainActivity.httpsOnly() = Settings.getBool(this, Settings.SEC_HTTPS_ONLY, true)

/**
 * Shows the warning in place of the page, holding the address so
 * "Continue anyway" has somewhere to go.
 */
internal fun MainActivity.showInsecureWarning(view: WebView?, httpUrl: String) {
    pendingInsecure = httpUrl
    val enc = try {
        java.net.URLEncoder.encode(httpUrl, "UTF-8")
    } catch (e: Exception) { "" }
    // Both callers are WebView callbacks, so the load waits until they
    // have returned. See loadAfterCallback.
    loadAfterCallback(view, "file:///android_asset/insecure.html?u=" + enc)
}

/** Freezes the current tab list into the store, without disturbing it. */
internal fun MainActivity.snapshotTabs() {
    val list = tabs.tabs
    tabStore.snapshot = list.map { t ->
        val state = t.webView?.let { w -> Bundle().also { b -> w.saveState(b) } }
            ?: t.savedState
        TabStore.Snap(
            url = t.url,
            title = t.title,
            state = state,
            openerIndex = list.indexOfFirst { it.id == t.openerId },
            openerSite = t.openerSite,
            leftOpenerSite = t.leftOpenerSite,
            signIn = t.signIn,
            isPrivate = t.isPrivate,
            diskKey = t.diskKey,
            diskStateHash = t.diskStateHash,
            group = tabs.groups.indexOfFirst { it.id == t.groupId }
        )
    }
    tabStore.groups = tabs.groups.map { SessionStore.Group(it.name, it.color) }
    tabStore.activeIndex = list.indexOf(tabs.activeTab).coerceAtLeast(0)
}

/**
 * Rebuilds the tab list after a recreation. Returns false when there is
 * nothing to restore and a fresh tab should be opened instead.
 */
internal fun MainActivity.restoreTabs(saved: Bundle?): Boolean {
    val snaps = tabStore.snapshot
    if (!snaps.isNullOrEmpty()) {
        snaps.forEach { s ->
            val t = tabs.createTab(s.url)
            t.title = s.title
            t.savedState = s.state
            t.openerSite = s.openerSite
            t.leftOpenerSite = s.leftOpenerSite
            t.signIn = s.signIn
            t.isPrivate = s.isPrivate
            s.diskKey?.let { t.diskKey = it }
            t.diskStateHash = s.diskStateHash
            // The opener counts its own restore as a load, so whether it
            // moves on afterwards can no longer be told by counting.
            t.openerLoadsAtOpen = -1
        }
        // Re-link the pop-up relationships once every tab has its new id.
        snaps.forEachIndexed { i, s ->
            if (s.openerIndex >= 0) {
                tabs.tabs[i].openerId = tabs.tabs.getOrNull(s.openerIndex)?.id
            }
        }
        restoreGroups(tabStore.groups, snaps.map { it.group })
        openTab(tabs.tabs.getOrNull(tabStore.activeIndex) ?: tabs.tabs.first())
        return true
    }
    // The process was killed: the store is gone, but the addresses survived
    // in instance state. The pages reload rather than resuming, which is a
    // far smaller loss than the whole session.
    val urls = saved?.getStringArrayList("tab_urls") ?: return false
    if (urls.isEmpty()) return false
    val titles = saved.getStringArrayList("tab_titles") ?: ArrayList()
    urls.forEachIndexed { i, u ->
        tabs.createTab(u).title = titles.getOrNull(i) ?: "New Tab"
    }
    val names = saved.getStringArrayList("group_names") ?: ArrayList()
    val colors = saved.getIntArray("group_colors") ?: IntArray(0)
    restoreGroups(names.mapIndexed { i, n -> SessionStore.Group(n, colors.getOrElse(i) { 0 }) },
        (saved.getIntArray("tab_groups") ?: IntArray(0)).toList())
    openTab(tabs.tabs.getOrNull(saved.getInt("tab_active", 0)) ?: tabs.tabs.first())
    return true
}
