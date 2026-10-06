package com.search.browser

import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView

/**
 * The page view, with the two things the browser's gestures need to know and
 * a plain WebView does not say.
 *
 * Whether a finger is on the page: the bars finish their move with a spring
 * only once the page has been let go and has stopped, and the pull-to-refresh
 * decides on release.
 *
 * When the page itself is pulled past its top. Chromium reports overscroll
 * through overScrollBy - the same signal that drives Android's own stretch at
 * the edge - and only for the page's root scroller once it is at the top, or
 * when an inner list hands the scroll on after reaching its own top. So a
 * pull-to-refresh driven by it never fires while a list inside the page is
 * still scrolling, and a page that turns overscroll off (overscroll-behavior)
 * never triggers it.
 */
class BrowserWebView(context: Context) : WebView(context) {

    /** A finger is on the page. */
    var touching = false
        private set

    /** Called after the last finger leaves the page. */
    var onTouchEnd: (() -> Unit)? = null

    /** The page was pulled down past its top by this many px (positive). */
    var onTopOverscroll: ((Int) -> Unit)? = null

    /**
     * The host of the page this view is showing, for the ad blocker's
     * per-site exceptions. Written on the main thread as pages start and on
     * WebView's request thread as a page's own request goes out; read on the
     * request threads, which cannot ask the view for its address.
     */
    @Volatile var pageHost: String? = null

    /**
     * The page scrolled. Separate from the scroll listener MainActivity sets,
     * which places the feed's ad card and is left to that alone.
     */
    var onScrolled: (() -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        // super first: it is what calls the scroll listener.
        super.onScrollChanged(l, t, oldl, oldt)
        onScrolled?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            onTouchEnd?.invoke()
        }
        return handled
    }

    override fun overScrollBy(
        deltaX: Int, deltaY: Int, scrollX: Int, scrollY: Int,
        scrollRangeX: Int, scrollRangeY: Int,
        maxOverScrollX: Int, maxOverScrollY: Int, isTouchEvent: Boolean
    ): Boolean {
        if (isTouchEvent && touching && deltaY < 0 && scrollY <= 0) onTopOverscroll?.invoke(-deltaY)
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
            maxOverScrollX, maxOverScrollY, isTouchEvent)
    }
}
