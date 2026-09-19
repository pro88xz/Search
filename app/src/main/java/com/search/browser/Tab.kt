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

    val isLive: Boolean get() = webView != null
}
