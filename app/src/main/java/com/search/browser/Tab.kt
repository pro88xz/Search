package com.search.browser

import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView

/**
 * Represents a single browser tab.
 * A tab is "live" when it holds a WebView, or "frozen" when only its
 * saved state (url, title, WebView.saveState bundle) is kept to save RAM.
 * The thumbnail is captured when the tab goes to the background, so it
 * survives freezing and can be shown in the tab deck.
 */
class Tab(
    val id: Long,
    var url: String = "about:blank",
    var title: String = "New Tab"
) {
    var webView: WebView? = null
    var savedState: Bundle? = null
    var thumbnail: Bitmap? = null

    /**
     * Id of the tab whose page called window.open() to create this one, or null
     * for a tab the user opened. A sign-in pop-up hands its result back through
     * window.opener, so closing it has to return to that exact tab - not to
     * whichever tab happens to sit last in the list.
     */
    var openerId: Long? = null

    /**
     * For a pop-up: the site its opener was on when it opened, whether the
     * pop-up has since been to a different one (the sign-in provider), and how
     * many loads the opener had started at the time - so it can be told later
     * whether the opener moved on by itself.
     */
    var openerSite: String? = null
    var leftOpenerSite = false
    // -1 for a pop-up rebuilt after a recreation, whose opener has since
    // counted a load of its own: then it is not known whether it moved on.
    var openerLoadsAtOpen = 0

    /** Whether this pop-up has been through a sign-in (UrlHelper.looksLikeSignIn). */
    var signIn = false

    /** Main-frame loads started in this tab. */
    var loadsStarted = 0

    /**
     * Bumped with every load, so a check scheduled against one page can tell
     * that the tab has moved on before it runs.
     */
    var loadToken = 0

    val isLive: Boolean get() = webView != null
}
