package com.search.browser

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.search.browser.MainActivity.SearchAppBridge
import org.json.JSONArray

// MainActivity, continued: Bars hide while scrolling, The page's colours in the bars, How far the page runs under the bottom bar.
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- Bars hide while scrolling ----------

internal fun MainActivity.barsMayHide(web: View): Boolean =
    web === activeWeb() && !searchMode && !findActive && !deckVisible &&
        binding.menuScrim.visibility != View.VISIBLE && fullscreenView == null &&
        pullRaw == 0f && !pullRefreshing && hideBarsPref &&
        // Not while typing into the page: the keyboard owns the bottom,
        // and the bars come straight back when it opens anyway.
        !pageTyping &&
        // With TalkBack the bars are found by touch; they stay put.
        !touchExploration

internal fun MainActivity.onPageScrollForBars(web: View, y: Int, oldY: Int) {
    val dy = y - oldY
    if (dy == 0 || web !== activeWeb()) return
    // The page changing size moves it too: near the end of a page the
    // scroll is pulled back by as much as it grew. That is not the reader
    // scrolling up, and must not bring the bars straight back.
    if (android.os.SystemClock.uptimeMillis() - barsMovedAt < 350L) { barScrollAcc = 0; return }
    val density = resources.displayMetrics.density
    if (y <= (8 * density).toInt()) {          // back at the top
        barScrollAcc = 0
        if (barsHidden) showBars()
        return
    }
    if (!barsMayHide(web)) { barScrollAcc = 0; return }
    // Only the user's own scrolling - a finger on the page, or the fling it
    // left behind - not a page scrolling itself to an anchor.
    val b = web as? BrowserWebView ?: return
    if (!b.touching && android.os.SystemClock.uptimeMillis() - b.lastTouchUp > 1500L) return
    if ((dy > 0) != (barScrollAcc > 0)) barScrollAcc = 0
    barScrollAcc += dy
    val threshold = (24 * density).toInt()
    // Only on a page that still scrolls once it has the whole screen: on
    // one that would then fit, there would be no scrolling up to bring
    // the bars back.
    val grow = binding.topBar.height + binding.bottomBar.height
    // Nor near the page's end. Growing the page there pulls its scroll
    // back by as much as it grew, so the end of the page - the home feed's
    // ad card, a site's footer - jumps under the finger. Within the bars'
    // height of the end they stay.
    val left = b.scrollRange() - web.height - y
    if (!barsHidden && barScrollAcc > threshold && y > binding.topBar.height &&
        b.scrollRange() - web.height - grow > threshold * 2 && left > grow + threshold) hideBars()
    else if (barsHidden && barScrollAcc < -threshold) showBars()
}

internal fun MainActivity.hideBars() {
    val top = binding.topBar
    val bottom = binding.bottomBar
    val area = binding.webArea
    if (barsHidden || top.height <= 0 || top.visibility != View.VISIBLE) return
    barsHidden = true
    updateVideoFab()
    barsMovedAt = android.os.SystemClock.uptimeMillis()
    top.animate().cancel(); area.animate().cancel(); bottom.animate().cancel()
    barsTopH = top.height
    val h = barsTopH
    // The page takes the whole screen at once, while both bars still cover
    // its new edges - so the page has painted them by the time the bars
    // have moved off. Its top goes up under the top bar, so it is offset
    // by the bar's height in the same frame and nothing on it moves yet;
    // then it rises with the bar. Bars that were on their way back are
    // simply turned round: the page is still under them.
    if (!pageUnderBars) {
        setPageUnderBars(true)
        area.translationY = area.translationY + h
    }
    showEdgeScrims()
    val drop = (bottom.height + binding.navSheet.bottomInset).toFloat()
    if (!animationsOn()) {
        top.translationY = -h.toFloat(); area.translationY = 0f
        bottom.translationY = drop
        top.visibility = View.INVISIBLE; bottom.visibility = View.INVISIBLE
        return
    }
    val ms = 220L
    val ease = android.view.animation.PathInterpolator(0.3f, 0f, 0.2f, 1f)
    top.animate().translationY(-h.toFloat()).setDuration(ms).setInterpolator(ease).start()
    area.animate().translationY(0f).setDuration(ms).setInterpolator(ease).start()
    bottom.animate().translationY(drop).setDuration(ms).setInterpolator(ease)
        .withEndAction {
            if (!barsHidden) return@withEndAction
            // Off screen and under the strips: nothing to draw or animate
            // (the owl button stops), and nothing for TalkBack to find.
            top.visibility = View.INVISIBLE
            bottom.visibility = View.INVISIBLE
        }.start()
}

/**
 * Lets the page run under both bars, or stops it at their edges again.
 *
 * Done with the bars' own margins, not the page's. A bar whose negative
 * margin matches its height takes no room in the layout, and the page,
 * which takes all the room the bars leave, grows by exactly that. A
 * negative margin on the page itself does not work: LinearLayout never
 * lets a child's margins shrink the room the others take, so the page
 * only moved up, and a band the height of both bars stayed empty at the
 * bottom of the screen for as long as they were away. The bars stay
 * where they were laid out either way; only the page changes.
 */
internal fun MainActivity.setPageUnderBars(under: Boolean) {
    pageUnderBars = under
    val top = binding.topBar
    (top.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
        val m = if (under) -top.height else 0
        if (lp.bottomMargin != m) { lp.bottomMargin = m; top.layoutParams = lp }
    }
    val bottom = binding.bottomBar
    (bottom.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
        val m = if (under) -bottom.height else -barRestOverlap
        if (lp.topMargin != m) { lp.topMargin = m; bottom.layoutParams = lp }
    }
}

internal fun MainActivity.showEdgeScrims() {
    val root = binding.rootView
    // Straight after the browser layer: over the bars, under every sheet
    // and overlay that opens above them.
    fun make(gravity: Int): EdgeScrim = EdgeScrim(this).also { v ->
        v.elevation = 0f
        root.addView(v, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, gravity))
    }
    val status = statusScrim ?: make(android.view.Gravity.TOP).also { statusScrim = it }
    val nav = navScrim ?: make(android.view.Gravity.BOTTOM).also { navScrim = it }
    // The root keeps the system bars as padding; the strips sit in it.
    val topH = root.paddingTop
    val botH = root.paddingBottom
    (status.layoutParams as FrameLayout.LayoutParams).let { if (it.height != topH) { it.height = topH; status.layoutParams = it } }
    (nav.layoutParams as FrameLayout.LayoutParams).let { if (it.height != botH) { it.height = botH; nav.layoutParams = it } }
    status.translationY = -topH.toFloat()
    nav.translationY = botH.toFloat()
    status.colours = binding.topBar.paintColours()
    nav.colours = binding.navSheet.paintColours()
    status.visibility = if (topH > 0) View.VISIBLE else View.GONE
    nav.visibility = if (botH > 0) View.VISIBLE else View.GONE
}

internal fun MainActivity.hideEdgeScrims() {
    statusScrim?.visibility = View.GONE
    navScrim?.visibility = View.GONE
}

/** Whether the phone runs animations (Remove animations / animator scale 0 says not). */
internal fun MainActivity.animationsOn(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.animation.ValueAnimator.areAnimatorsEnabled()
    else android.provider.Settings.Global.getFloat(contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** Brings the bars back; [animate] false puts them straight in place. */
internal fun MainActivity.showBars(animate: Boolean = true) {
    if (!barsHidden) return
    barsHidden = false
    updateVideoFab()
    barScrollAcc = 0
    barsMovedAt = android.os.SystemClock.uptimeMillis()
    val top = binding.topBar
    val bottom = binding.bottomBar
    val area = binding.webArea
    top.animate().cancel(); area.animate().cancel(); bottom.animate().cancel()
    top.visibility = View.VISIBLE
    bottom.visibility = if (pageTyping) View.GONE else View.VISIBLE
    if (!animate || !animationsOn()) {
        settleBars()
        return
    }
    // The page keeps the whole screen while the bars come back and comes
    // down with the top bar, and the bottom bar rises over its foot. Only
    // once both are in place does it stop at their edges again - which
    // takes away page the bars already cover, so nothing new has to be
    // painted and no gap can open on the way.
    showEdgeScrims()
    val ms = 220L
    val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    val areaTo = if (pageUnderBars) barsTopH.toFloat() else 0f
    top.animate().translationY(0f).setDuration(ms).setInterpolator(ease).start()
    area.animate().translationY(areaTo).setDuration(ms).setInterpolator(ease).start()
    bottom.animate().translationY(0f).setDuration(ms).setInterpolator(ease)
        .withEndAction { if (!barsHidden) settleBars() }.start()
}

/** The bars in place and the page stopped at their edges, in one frame. */
internal fun MainActivity.settleBars() {
    setPageUnderBars(false)
    binding.topBar.translationY = 0f
    binding.webArea.translationY = 0f
    binding.bottomBar.translationY = 0f
    hideEdgeScrims()
    schedulePageTint(60L)
}

// ---------- The page's colours in the bars ----------

internal fun MainActivity.tintAllowed(url: String?): Boolean =
    !nightOwl && url != null && url != homePage && !isErrorPage(url) &&
        (url.startsWith("https://") || url.startsWith("http://")) && tintBarsPref

internal fun MainActivity.schedulePageTint(delay: Long = 300L) {
    tintRetries = 0
    uiHandler.removeCallbacks(tintSample)
    uiHandler.postDelayed(tintSample, delay)
}

internal fun MainActivity.samplePageTint() {
    val web = activeWeb() ?: return
    if (!tintAllowed(tabs.activeTab?.url)) { plainBars(tabs.activeTab?.url, animate = true); return }
    // A tab switched to from Focus or Zen's home: not the wash while its own colours are found.
    if (binding.topBar.seamless) plainBars(tabs.activeTab?.url, animate = false)
    // Only a settled page with nothing over it: the strips must be the page.
    // (The loading pill sits lower than the top strip, so it does not count.)
    if (barsHidden || searchMode || deckVisible || fullscreenView != null ||
        binding.menuScrim.visibility == View.VISIBLE ||
        binding.webContainer.translationX != 0f || !web.isAttachedToWindow ||
        binding.topBar.translationY != 0f || binding.bottomBar.visibility != View.VISIBLE
    ) {
        // Something is in the way for a moment; look again shortly rather
        // than leave the page untinted until the next scroll. Not while
        // the bars are away: bringing them back looks again.
        if (!barsHidden && tintRetries++ < 20) uiHandler.postDelayed(tintSample, 700L)
        return
    }
    tintRetries = 0
    val url = tabs.activeTab?.url
    // Steady: once this page has its colour, scrolling never changes it.
    // Only back at the top - where the header that set it is - is it
    // looked at again, for a header that paints late.
    if (url != null && url == tintedUrl && web.scrollY > (48 * resources.displayMetrics.density)) return
    themeColour(web) { theme ->
        if (web !== activeWeb() || !tintAllowed(tabs.activeTab?.url)) return@themeColour
        if (theme != null) { applyPageTint(IntArray(3) { theme }, url); return@themeColour }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            jsPageColours(web) { t, _ -> if (web === activeWeb()) applyPageTint(t, url) }
            return@themeColour
        }
        val d = resources.displayMetrics.density
        val area = IntArray(2).also { binding.webArea.getLocationInWindow(it) }
        val w = binding.webArea.width
        // Just under the top bar, clear of the shadow it casts on the page.
        val top = android.graphics.Rect(area[0], area[1] + (2 * d).toInt(), area[0] + w, area[1] + (9 * d).toInt())
        captureStrip(top) { t ->
            if (web !== activeWeb() || !tintAllowed(tabs.activeTab?.url)) return@captureStrip
            if (t != null) { applyPageTint(t, url); return@captureStrip }
            // The screen could not be read here (some phones refuse a
            // window copy now and then): ask the page for its colours.
            jsPageColours(web) { jt, _ -> if (web === activeWeb()) applyPageTint(jt, url) }
        }
    }
}

/**
 * The colour the page names for the browser's toolbar - <meta
 * name="theme-color">, the one for the current light or dark scheme when
 * it gives both - or null when it names none. Read through a canvas,
 * which turns any CSS colour into #rrggbb without touching the page.
 */
internal fun MainActivity.themeColour(web: WebView, done: (Int?) -> Unit) {
    val js = "(function(){try{var ms=document.querySelectorAll('meta[name=\"theme-color\"]');" +
        "for(var i=0;i<ms.length;i++){var m=ms[i],q=m.getAttribute('media');" +
        "if(q&&!matchMedia(q).matches)continue;var v=(m.getAttribute('content')||'').trim();if(!v)continue;" +
        "var x=document.createElement('canvas').getContext('2d');x.fillStyle='#010203';x.fillStyle=v;" +
        "var f=x.fillStyle;if(/^#[0-9a-f]{6}$/i.test(f)&&f!=='#010203')return f;}}catch(e){}return '';})()"
    try {
        web.evaluateJavascript(js) { result ->
            val hex = try { org.json.JSONTokener(result ?: "").nextValue() as? String } catch (e: Exception) { null }
            done(try {
                if (hex.isNullOrEmpty()) null else android.graphics.Color.parseColor(hex)
            } catch (e: Exception) { null })
        }
    } catch (e: Exception) {
        done(null)
    }
}

/**
 * The page's own colours along its top and bottom edges, left, middle and
 * right: the background of whatever sits at each point, walking up to the
 * first element that paints one (a gradient counts by its first colour).
 */
internal fun MainActivity.jsPageColours(web: WebView, done: (IntArray?, IntArray?) -> Unit) {
    val js = "(function(){function c(e){while(e&&e.nodeType===1){var s=getComputedStyle(e);" +
        "var m=(s.backgroundColor||'').match(/rgba?\\(([^)]+)\\)/);" +
        "if(m){var p=m[1].split(',').map(parseFloat);if(p.length<4||p[3]>0.5)return[p[0],p[1],p[2]];}" +
        "var g=(s.backgroundImage||'').indexOf('gradient')>=0&&s.backgroundImage.match(/rgba?\\(([^)]+)\\)/);" +
        "if(g){var q=g[1].split(',').map(parseFloat);return[q[0],q[1],q[2]];}e=e.parentElement;}" +
        "return null;}var w=innerWidth,h=innerHeight,o=[];" +
        "[[.17,4],[.5,4],[.83,4],[.17,h-30],[.5,h-30],[.83,h-30]].forEach(function(p){" +
        "var e=document.elementFromPoint(w*p[0],p[1]);o.push(e?c(e):null);});" +
        "var d=c(document.body)||c(document.documentElement)||[255,255,255];" +
        "return JSON.stringify(o.map(function(x){return x||d;}));})()"
    try {
        web.evaluateJavascript(js) { result ->
            val colours = try {
                val text = org.json.JSONTokener(result ?: "").nextValue() as? String
                val arr = org.json.JSONArray(text ?: "[]")
                IntArray(arr.length()) { i ->
                    val c = arr.getJSONArray(i)
                    android.graphics.Color.rgb(c.getDouble(0).toInt().coerceIn(0, 255),
                        c.getDouble(1).toInt().coerceIn(0, 255), c.getDouble(2).toInt().coerceIn(0, 255))
                }
            } catch (e: Exception) { null }
            if (colours == null || colours.size < 6) done(null, null)
            else done(colours.copyOfRange(0, 3), colours.copyOfRange(3, 6))
        }
    } catch (e: Exception) {
        done(null, null)
    }
}

/** The average colour of each third of [rect] on screen, or null. */
internal fun MainActivity.captureStrip(rect: android.graphics.Rect, done: (IntArray?) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || rect.width() < 3 || rect.height() < 1) {
        done(null); return
    }
    val sw = 48
    val sh = 4
    val bmp = try { Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { done(null); return }
    try {
        PixelCopy.request(window, rect, bmp, { result ->
            val colours = if (result == PixelCopy.SUCCESS) {
                val px = IntArray(sw * sh)
                bmp.getPixels(px, 0, sw, 0, 0, sw, sh)
                PageTint.thirds(px, sw, sh)
            } else null
            bmp.recycle()
            done(colours)
        }, uiHandler)
    } catch (e: Exception) {
        bmp.recycle()
        done(null)
    }
}

/** Both bars in the colour of [edge], the top of the page at [url] (null: their plain surface). */
internal fun MainActivity.applyPageTint(edge: IntArray?, url: String?) {
    if (edge == null) return
    tintedUrl = url
    val bar = PageTint.barColours(edge, getColor(R.color.barSurface), isDarkTheme())
    setBarTint(bar, bar, animate = true)
}

internal fun MainActivity.isDarkTheme(): Boolean = (resources.configuration.uiMode and
    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
    android.content.res.Configuration.UI_MODE_NIGHT_YES

/**
 * The address field on a bar the page has coloured: a solid pane worked
 * out from the bar's own colour - a shade darker on a light bar, a few
 * shades lighter on a dark one, as Chrome's omnibox does - with text and
 * icons that read on it.
 *
 * The pane used to come from tinting the field's own see-through fill
 * (7% black, or 13% white in dark mode) with 20% white. A tint keeps the
 * fill's alpha and multiplies the tint's into it, so the pane came out at
 * 1-3% white: on every dark site's bar the search box all but vanished.
 * Now the tint replaces the colour outright (SRC), so the pane is exactly
 * the colour asked for.
 */
internal fun MainActivity.paintTopForeground() {
    val field = binding.urlBar
    if (urlTextDefault == null) urlTextDefault = field.textColors
    val bar = if (fieldMode == FIELD_NORMAL) fieldPaneFor else null
    val light = topOnDark && bar != null
    val white = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
    if (bar != null) {
        field.backgroundTintMode = android.graphics.PorterDuff.Mode.SRC
        field.backgroundTintList = android.content.res.ColorStateList.valueOf(fieldPane(bar, light))
    } else {
        field.backgroundTintList = null
        field.backgroundTintMode = android.graphics.PorterDuff.Mode.SRC_IN
    }
    if (light) {
        field.setTextColor(android.graphics.Color.WHITE)
        field.setHintTextColor(0xB3FFFFFF.toInt())
        binding.starBtn.imageTintList = white
        binding.clearBtn.imageTintList = white
    } else {
        urlTextDefault?.let { field.setTextColor(it) }
        // On a coloured light bar the plain grey hint can sink into the
        // pane; a share of black keeps it readable on any of them.
        field.setHintTextColor(if (bar != null) 0x99000000.toInt()
            else androidx.core.content.ContextCompat.getColor(this, R.color.sugMuted))
        binding.starBtn.imageTintList = null
        binding.clearBtn.imageTintList = null
    }
    refreshNav()
}

/** The address field's pane on a bar of colour [bar]: a shade darker, or on a dark bar lighter. */
internal fun MainActivity.fieldPane(bar: Int, onDark: Boolean): Int =
    if (onDark) PageTint.mix(bar, android.graphics.Color.WHITE, 0.18f)
    else PageTint.mix(bar, android.graphics.Color.BLACK, 0.075f)

/**
 * The bars on the home page, top and bottom (null: the plain surface).
 * Home has no white sheet across its top: the owl and the gear sit on
 * the page itself, and the bar's sheet, line and shadow are for web
 * pages and search. In Explore that is the page's grey (appBackground,
 * home.html's --bg) with the bottom bar as it is; in Focus and Zen the
 * wash in the accent - the top edge's three colours (HomeWash, which
 * home.html is painted from too, at the same places) and the colour the
 * page settles into for the bottom bar. Null anywhere but home, and in
 * Night Owl, which keeps its own sky.
 */
internal fun MainActivity.homeWash(url: String?): Array<IntArray?>? {
    if (nightOwl || url == null || url != homePage) return null
    // What the page on screen says it shows; until it has said, the mode
    // it was asked for. They differ only for a moment - home takes up the
    // app's mode whenever it comes into view - but in that moment the
    // bars must match the page, not the setting.
    val shown = activeWeb()?.tag as? SearchAppBridge
    val mode = shown?.shownMode ?: browsingMode
    // Light or dark as the page is drawn, which is what the bar meets.
    val dark = shown?.shownDark ?: isDarkTheme()
    if (mode != BrowsingMode.FOCUS && mode != BrowsingMode.ZEN) {
        // home.html's --bg, light and dark.
        val page = if (dark) 0xFF0E0E10.toInt() else 0xFFF5F5F5.toInt()
        return arrayOf(IntArray(3) { page }, null)
    }
    // In the accent the page is painted in (or will be).
    val wash = HomeWash.palette(shown?.shownAccent ?: Settings.getHomeAccent(this), dark)
    return arrayOf(wash.top.copyOf(), IntArray(3) { wash.bottom })
}

/** The bars for a page that does not lend them its colours: plain, or on home, the page's own (homeWash). */
internal fun MainActivity.plainBars(url: String?, animate: Boolean) {
    val w = homeWash(url)
    binding.topBar.seamless = w != null
    setBarTint(w?.get(0), w?.get(1), animate)
}

/** Eases both bars to [top] / [bottom] (null: their plain surface). */
internal fun MainActivity.setBarTint(top: IntArray?, bottom: IntArray?, animate: Boolean) {
    if (top?.contentEquals(topTint) ?: (topTint == null) &&
        bottom?.contentEquals(bottomTint) ?: (bottomTint == null)) return
    tintAnim?.cancel()
    val plainTop = IntArray(3) { binding.topBar.surfaceColor }
    val plainBottom = IntArray(3) { binding.navSheet.surfaceColor }
    val fromTop = topTint ?: plainTop
    val fromBottom = bottomTint ?: plainBottom
    val toTop = top ?: plainTop
    val toBottom = bottom ?: plainBottom
    topTint = top
    bottomTint = bottom
    applyBarForeground()
    if (!animate || !animationsOn()) {
        binding.topBar.tint = top
        binding.navSheet.tint = bottom
        return
    }
    // The pane starts where the bar starts, not where it is going.
    if (fieldPaneFor != null && fieldMode == FIELD_NORMAL && fromTop.size == 3) {
        binding.urlBar.backgroundTintList =
            android.content.res.ColorStateList.valueOf(fieldPane(fromTop[1], topOnDark))
    }
    tintAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 340L
        interpolator = android.view.animation.DecelerateInterpolator()
        addUpdateListener { a ->
            val f = a.animatedValue as Float
            val nowTop = PageTint.mixAll(fromTop, toTop, f)
            binding.topBar.tint = nowTop
            binding.navSheet.tint = PageTint.mixAll(fromBottom, toBottom, f)
            // The field's pane eases with the bar it sits on.
            if (fieldPaneFor != null && fieldMode == FIELD_NORMAL && nowTop.size == 3) {
                binding.urlBar.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(fieldPane(nowTop[1], topOnDark))
            }
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Settled on the plain surface: drop the gradient entirely.
                if (topTint == null) binding.topBar.tint = null
                if (bottomTint == null) binding.navSheet.tint = null
            }
        })
        start()
    }
}

// ---------- How far the page runs under the bottom bar ----------

/**
 * On the home page the page runs the full overlap under the bottom bar:
 * under the see-through strip where the arc rises and 28dp on under the
 * bar itself, as it always has - the feed's ad card and the page's bottom
 * padding are laid out for exactly that, so home is left as it was.
 *
 * On a website the page stops at the bar's straight edge, running only
 * under the see-through strip. A site lays out what it pins to the bottom
 * of its window - a toolbar, a cookie banner, a player's controls, a
 * "Download our app" strip - against the bottom of the WebView, and with
 * the page running under the bar's solid part that was hidden behind it.
 */
internal fun MainActivity.applyBarOverlap(onHome: Boolean) {
    if (barOverlapHome == onHome) return
    val lp = binding.bottomBar.layoutParams
        as? android.widget.LinearLayout.LayoutParams ?: return
    if (homeBarOverlap < 0) homeBarOverlap = barRestOverlap
    barOverlapHome = onHome
    barRestOverlap = if (onHome) homeBarOverlap else binding.navSheet.edgeTop.toInt()
    // While the bars are away the page runs under all of the bar; it
    // stops at the new edge when they come back (settleBars).
    if (!pageUnderBars && lp.topMargin != -barRestOverlap) {
        lp.topMargin = -barRestOverlap
        binding.bottomBar.layoutParams = lp
    }
}

internal fun MainActivity.canFreezeTab(tab: Tab): Boolean {
    // A cart or checkout in progress keeps its page - the basket, an
    // address typed half-way, a payment step - under the live-tab cap and
    // the gentle trims, so opening a few more tabs mid-purchase never
    // reloads it. Only when memory is really short does it go like any
    // other tab.
    if (!memoryShort && UrlHelper.looksLikeCheckout(tab.url)) return false
    // The opener side, while any pop-up it opened is still open.
    if (tabs.tabs.any { it.openerId == tab.id }) return false
    // The pop-up side, while its opener is still live. Only the newest
    // pop-up from each opener is held: every target=_blank link is a
    // pop-up too, and holding all of them would hold a whole news site's
    // worth of articles in memory.
    val openerId = tab.openerId ?: return true
    if (tabs.tabs.none { it.id == openerId && it.isLive }) return true
    return tabs.tabs.lastOrNull { it.openerId == openerId } !== tab
}

internal fun MainActivity.freezeTab(tab: Tab) {
    val web = tab.webView ?: return
    // No picture is taken here. It used to be drawn now, through a
    // software canvas, which blocks the main thread on the renderer. The
    // tab already has the one openTab took when it was last left.
    val state = Bundle()
    web.saveState(state)
    tab.savedState = state
    (web.parent as? ViewGroup)?.removeView(web)
    web.destroy()
    tab.webView = null
}

internal fun MainActivity.applyDesktopMode(on: Boolean) {
    val web = tabs.activeTab?.webView ?: return
    val desktopUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    web.settings.apply {
        if (on) {
            userAgentString = desktopUA
            useWideViewPort = true
            loadWithOverviewMode = true
            // Force a desktop-width layout so responsive sites render desktop.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        } else {
            userAgentString = null
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }
    // Scale the 980px desktop layout to fit the screen width (like Chrome).
    if (on) {
        val screenWidthDp = resources.displayMetrics.widthPixels
        val scale = (screenWidthDp.toFloat() / 980f * 100f).toInt().coerceIn(20, 100)
        web.setInitialScale(scale)
    } else {
        web.setInitialScale(0)
    }
    web.reload()
}

internal fun MainActivity.applyNightOwlChrome(on: Boolean) {
    // Night Owl tabs always show the owl's night sky.
    binding.navOwl.privateMode = on
    // And keep the Night Owl wash: no page colours in its bars, nor
    // Focus and Zen's pastel one (home brings that back on its reload).
    binding.topBar.seamless = false
    setBarTint(null, null, animate = false)
    // By id, not by binding.homeBtn.parent. The owl now sits inside a
    // FrameLayout so its tab badge can be positioned against it, and the
    // old expression would quietly resolve to that 48dp wrapper - putting
    // Night Owl's wash on a box behind the owl instead of on the bar.
    val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)

    // Detect dark mode.
    val isDark = (resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    if (on) {
        // Subtle purple wash matched to the theme.
        val tint = if (isDark) "#231A3A" else "#ECE7F5"
        binding.topBar.surfaceColor = android.graphics.Color.parseColor(tint)
        binding.navSheet.surfaceColor = android.graphics.Color.parseColor(tint)
        binding.rootView.setBackgroundColor(android.graphics.Color.parseColor(tint))
        // Icons: light icons on dark tint, dark icons on light tint.
        controller.isAppearanceLightStatusBars = !isDark
    } else {
        binding.topBar.surfaceColor = getColor(R.color.barSurface)
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
        binding.rootView.setBackgroundColor(tv.data)
        binding.navSheet.surfaceColor = getColor(R.color.barSurface)
        controller.isAppearanceLightStatusBars = !isDark
    }
}

internal fun MainActivity.openMenu() {
    showBars()
    // The first row carries both of the jobs the owl's long press used to,
    // and says which one it is about to do. On the home page there is
    // nothing to go home to, so it offers the way out instead.
    val menuUrl = tabs.activeTab?.url
    val menuOnHome = (menuUrl == null || menuUrl == homePage)
    (binding.menuHome.getChildAt(0) as? android.widget.ImageView)
        ?.setImageResource(
            if (menuOnHome) R.drawable.menu_close else R.drawable.menu_home)
    (binding.menuHome.getChildAt(1) as? android.widget.TextView)?.text =
        if (menuOnHome) "Close Search" else "Home"
    // Reflect current Night Owl state in the menu label.
    (binding.menuNightOwl.getChildAt(1) as? android.widget.TextView)?.text =
        if (nightOwl) "Exit Night Owl" else "Night Owl"
    // The last closed tab, while there is one (never in Night Owl).
    binding.menuReopen.visibility =
        if (closedTabs.isNotEmpty() && !nightOwl) View.VISIBLE else View.GONE
    // A page Google Translate is showing offers the way back.
    (binding.menuTranslate.getChildAt(1) as? android.widget.TextView)?.text =
        if (PageTranslate.isTranslated(menuUrl)) "Show original" else "Translate"
    // Ads on this site: only while ad blocking is on and a website is on
    // screen, labelled with what tapping it will do.
    val adsHost = siteForAds()
    binding.menuSiteAds.visibility = if (adsHost != null) View.VISIBLE else View.GONE
    if (adsHost != null) {
        (binding.menuSiteAds.getChildAt(1) as? android.widget.TextView)?.text =
            if (AdBlockSites.isAllowed(this, adsHost)) "Block ads here"
            else "Allow ads here"
    }
    // While the page is still loading, Reload is Stop, so a slow page can
    // always be called off.
    val stop = pageLoading()
    binding.menuRefresh.setImageResource(
        if (stop) R.drawable.menu_close else R.drawable.menu_refresh)
    binding.menuRefresh.contentDescription = if (stop) "Stop" else "Reload"
    androidx.core.view.ViewCompat.setTooltipText(
        binding.menuRefresh, binding.menuRefresh.contentDescription)
    // Every group starts closed, so the menu opens at its short height.
    showMenuGroup(null, animate = false)
    menuDragSpring.cancel()
    menuSpring.cancel()
    val panel = binding.menuPanel
    panel.translationY = 0f
    binding.menuScrim.background?.mutate()?.alpha = 255
    placeMenu(0f)
    binding.menuScrim.visibility = View.VISIBLE
    // Drawn once into a layer while it grows, so each frame only scales
    // and fades that picture of the menu rather than redrawing its rows.
    panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    // Once laid out, so it grows from the corner where the Menu button is.
    // Before the first draw rather than on a post: a post can run ahead
    // of the layout, when the panel has no size to take its corner from.
    val seq = ++menuOpenSeq
    androidx.core.view.OneShotPreDrawListener.add(panel) {
        if (seq == menuOpenSeq && binding.menuScrim.visibility == View.VISIBLE) {
            placeMenu(0f)
            menuSpring.animate(0f, 1f) { endMenuLayer() }
        }
    }
}
