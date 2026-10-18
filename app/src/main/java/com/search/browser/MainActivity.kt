package com.search.browser
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen


import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.search.browser.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    companion object {
        // Runs the launch update check once per process, so recreating the
        // activity (e.g. a theme change) doesn't re-trigger the update prompt.
        private var didLaunchUpdateCheck = false
        // Same reasoning for the review prompt, and more so: two Play dialogs
        // in one session would be a lot to put in front of someone.
        private var didOfferReview = false
        // The owl's first hello is once per process: a rotation or a theme
        // change recreates the activity and must not greet again.
        private var greetedThisProcess = false

        /**
         * Where every CookieManager call that waits for an answer is made.
         *
         * flush(), getCookie() and setCookie() are synchronous: Chromium posts
         * the work to its cookie thread and the caller sleeps on a native lock
         * until it is done. The cookie thread can be busy - loading the store
         * from disk, or writing a page's worth of cookies - and a main thread
         * asleep behind it is the "native lock contention" ANR Play reports.
         * One thread, so the calls still run in the order they were made.
         */
        private val cookieIo = java.util.concurrent.Executors.newSingleThreadExecutor()

        // History is rewritten whole on every page load; one thread keeps
        // those writes in order and off the main thread.
        private val historyIo = java.util.concurrent.Executors.newSingleThreadExecutor()

        /**
         * One thread for suggestions: each keystroke used to start a thread of
         * its own with a network request in it, so fast typing had a dozen
         * requests in flight at once, most of them for queries already typed
         * past. A query superseded before its turn is skipped.
         */
        private val suggestIo = java.util.concurrent.Executors.newSingleThreadExecutor()

        /**
         * How long a sign-in pop-up may sit on the site's own page, back from
         * the provider and showing nothing, before it is taken to have
         * stalled. A callback page that works closes itself well inside this.
         */
        private const val POPUP_STALL_MS = 3500L

        /** The second look at a returned pop-up, before it is judged stalled. */
        private const val POPUP_RECHECK_MS = 4000L

        /**
         * How long the page behind a returned sign-in gets to show something
         * before it is reloaded. Longer than the pop-up's allowance: a heavy
         * site can spend a few seconds drawing nothing while it starts up.
         */
        private const val OPENER_STALL_MS = 6000L

        // Launcher shortcuts (res/xml/shortcuts.xml), the search widget
        // (SearchWidget) and the Night Owl tile (OwlTileService).
        const val ACTION_SEARCH = "com.search.browser.action.SEARCH"
        const val ACTION_NEW_TAB = "com.search.browser.action.NEW_TAB"
        const val ACTION_NIGHT_OWL = "com.search.browser.action.NIGHT_OWL"
        const val ACTION_SCAN = "com.search.browser.action.SCAN"
        const val ACTION_VOICE = "com.search.browser.action.VOICE"
    }

    // Posts to the main thread, attached or not. View.post on a WebView that
    // is not on screen waits until it is, which is no use for a background tab.
    private val uiHandler = Handler(Looper.getMainLooper())

    // ---- Native search-page mode ----
    private var searchMode = false
    // True when the home page is scrolled past its in-page search pill, so the
    // native top bar shows the pill instead of the icon row.
    private var homeCompact = false
    private var lastFailedUrl: String? = null

    // Tracks the site-settings signature last applied, so we only reload when it changed.
    private var lastSiteSig: String = ""

    // True while the window is the small floating PiP one.
    private var inPip = false

    // ---- HTML5 fullscreen video ----
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: android.widget.FrameLayout? = null
    private var savedOrientation =
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // Voice search launcher (RecognizerIntent -> go()).
    private val voiceLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) go(spoken)
        }
    }

    // Requests POST_NOTIFICATIONS (Android 13+) so the media notification can show.
    private val notifPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* granted or not; media notification simply won't show if denied */ }
    private var askedNotifPerm = false
    private fun ensureNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (askedNotifPerm) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            askedNotifPerm = true
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Storage permission for downloads on Android 9 and below (API <= 28).
    // Holds the pending download so it can resume after the user grants it.
    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )
    private var pendingDownload: PendingDownload? = null
    // Same deal for a long-pressed image on API <= 28.
    private var pendingImageSave: String? = null
    // One-shot ticket for a blob: save. Minted when the user taps Save and
    // spent on first use - the bridge is on every page, so without this any
    // site could write to the gallery unprompted.
    @Volatile private var blobSaveToken: String? = null
    // Same one-shot idea for a blob: download, one ticket per file being
    // saved. The bridge is reachable from every page, so without a ticket any
    // site could drop a file into the user's Downloads folder unprompted.
    private class BlobSave(val name: String, val mime: String?) {
        @Volatile var sink: DownloadSink? = null
        @Volatile var total = -1L
        @Volatile var progress: SaveProgress? = null
        @Volatile var checkedAt = 0L
    }
    private val blobSaves = java.util.concurrent.ConcurrentHashMap<String, BlobSave>()
    private val storagePermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pd = pendingDownload
        val pi = pendingImageSave
        pendingDownload = null
        pendingImageSave = null
        // Both, not one or the other: a download waiting on the grant and an
        // image saved while it waited are two separate requests, and the
        // second used to be dropped without a word.
        if (granted && pd != null) {
            performDownload(pd.url, pd.userAgent, pd.contentDisposition, pd.mimeType)
        }
        if (granted && pi != null) {
            saveImage(pi)
        }
        if (!granted) {
            android.widget.Toast.makeText(this,
                "Storage permission is needed to download files",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // A site has been allowed something that needs an Android permission too.
    // The answer is held here so it can be delivered once the system dialog
    // closes, because the web request cannot be left waiting on a callback.
    private var pendingWebGrant: (() -> Unit)? = null
    private var pendingWebDeny: (() -> Unit)? = null
    private var pendingWebNeedsAll = true
    private val webPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val grant = pendingWebGrant
        val deny = pendingWebDeny
        val all = pendingWebNeedsAll
        pendingWebGrant = null
        pendingWebDeny = null
        val ok = if (all) result.values.all { it } else result.values.any { it }
        if (ok) grant?.invoke() else deny?.invoke()
    }

    /**
     * Runs [onGranted] once this app itself holds what the web request needs.
     *
     * [needsAll] is false for location, where Android lets the user hand over
     * the approximate position only - one of the two is a real answer, not a
     * refusal.
     */
    private fun withAndroidPermission(
        perms: Array<String>,
        needsAll: Boolean,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        val missing = perms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { onGranted(); return }
        pendingWebGrant = onGranted
        pendingWebDeny = onDenied
        pendingWebNeedsAll = needsAll
        webPermLauncher.launch(missing.toTypedArray())
    }

    /**
     * Asks about one site, once, and remembers the answer.
     *
     * Nothing is granted silently any more. Both of these used to be handed to
     * any site that asked, with no prompt and no record - on by default, so a
     * page could open the camera or read the user's position without the user
     * ever being told it had happened.
     */
    private fun askSitePermission(
        origin: String,
        kind: String,
        message: String,
        onAllow: () -> Unit,
        onDeny: () -> Unit
    ) {
        when (SitePermissions.get(this, origin, kind)) {
            SitePermissions.ALLOW -> { onAllow(); return }
            SitePermissions.DENY -> { onDeny(); return }
        }
        if (isFinishing || isDestroyed) { onDeny(); return }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Search_Dialog)
            .setTitle(origin.ifBlank { "This site" })
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Allow") { d, _ ->
                d.dismiss()
                SitePermissions.set(this, origin, kind, SitePermissions.ALLOW)
                onAllow()
            }
            .setNegativeButton("Block") { d, _ ->
                d.dismiss()
                SitePermissions.set(this, origin, kind, SitePermissions.DENY)
                onDeny()
            }
            .show()
    }

    // File upload (WebView <input type=file>) support.
    private var filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback
        filePathCallback = null
        if (cb == null) return@registerForActivityResult
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris: Array<android.net.Uri>? = when {
                data?.clipData != null -> {
                    val cd = data.clipData!!
                    // A picked item can come back with no uri (a text-only
                    // item from some pickers); a null handed to the page's
                    // file input is a crash inside WebView, so it is dropped.
                    (0 until cd.itemCount).mapNotNull { i -> cd.getItemAt(i)?.uri }
                        .toTypedArray().takeIf { it.isNotEmpty() }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            cb.onReceiveValue(uris)
        } else {
            cb.onReceiveValue(null)
        }
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var wasOffline = false

    /** Watches connectivity; when it returns after being offline, tells the home
     *  page to swap cached/offline content for fresh stories. */
    private fun registerNetworkWatch() {
        if (netCallback != null) return
        wasOffline = !hasNetwork()
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runOnUiThread {
                    if (wasOffline) {
                        wasOffline = false
                        val web = activeWeb()
                        val url = web?.url
                        if (web != null && (url == null || url == homePage)) {
                            web.evaluateJavascript(
                                "window.__reloadFeedOnline && window.__reloadFeedOnline();", null)
                        }
                        // And the page that could not load, or stopped loading,
                        // goes again by itself - after a moment, so the new
                        // network has settled before it is asked.
                        uiHandler.removeCallbacks(recoverAfterReconnect)
                        uiHandler.postDelayed(recoverAfterReconnect, 700L)
                        // And the feed ad, if the launch had no connection
                        // to ask for one with (or to settle consent with).
                        uiHandler.postDelayed({ retryAdsAfterReconnect() }, 1500L)
                    }
                }
            }
            override fun onLost(network: android.net.Network) {
                runOnUiThread {
                    if (!hasNetwork()) {
                        wasOffline = true
                        // A page part-way in keeps what it has; say the rest
                        // is not coming, and offer to try again.
                        if (loadWatchTab != null && loadCommitted) {
                            loadInterrupted = true
                            updateLoadStatus()
                        }
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkWatch() {
        val cb = netCallback ?: return
        try {
            (getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        netCallback = null
    }

    // ---------- Connection states ----------
    //
    // A page that is slow to arrive says what it is waiting on, in a small
    // pill over the top of the page, instead of leaving only the ring to turn:
    // "Connecting to example.com..." while no answer has come, "Waiting for
    // example.com..." once that has gone on for a few seconds, and "Loading
    // content..." from the moment the page starts to arrive
    // (onPageCommitVisible). Loads that finish within a second show nothing.
    // If the network goes while a page is part-way in, the pill says so and
    // offers Retry, and the page reloads by itself when the network is back.
    // Only the tab on screen is watched.

    private var loadWatchTab: Tab? = null
    private var loadWatchHost = ""
    private var loadStartedAt = 0L
    private var loadCommitted = false
    private var loadInterrupted = false
    private val loadStatusTick = Runnable { updateLoadStatus() }

    /** The address a load broken by a network change was last retried at. */
    private var networkRetryUrl: String? = null

    /** When a renderer was last lost; one loss reaches every tab it served. */
    private var lastRendererLoss = 0L

    private fun beginLoadWatch(tab: Tab, url: String?) {
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

    private fun commitLoadWatch(tab: Tab?) {
        if (tab == null || tab !== loadWatchTab || loadCommitted) return
        loadCommitted = true
        updateLoadStatus()
    }

    /** The load is over. An interruption stays on show until it is retried. */
    private fun endLoadWatch(tab: Tab?) {
        if (tab == null || tab !== loadWatchTab || loadInterrupted) return
        loadWatchTab = null
        updateLoadStatus()
    }

    private fun updateLoadStatus() {
        if (!::binding.isInitialized) return
        uiHandler.removeCallbacks(loadStatusTick)
        val pill = binding.loadStatus
        val tab = loadWatchTab
        if (tab == null || tab !== tabs.activeTab || pullRefreshing) {
            pill.visibility = View.GONE
            return
        }
        if (loadInterrupted) {
            val accent = currentAccent()
            val text = android.text.SpannableStringBuilder("Connection lost \u00B7 ")
            val start = text.length
            text.append("Retry")
            text.setSpan(android.text.style.ForegroundColorSpan(accent),
                start, text.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, text.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            pill.text = text
            pill.contentDescription = "Connection lost. Retry"
            pill.setOnClickListener { retryInterruptedLoad() }
            pill.visibility = View.VISIBLE
            return
        }
        pill.setOnClickListener(null)
        pill.isClickable = false
        val elapsed = android.os.SystemClock.uptimeMillis() - loadStartedAt
        if (elapsed < 1200L) {
            pill.visibility = View.GONE
            uiHandler.postDelayed(loadStatusTick, 1200L - elapsed)
            return
        }
        val host = loadWatchHost.ifBlank { "the site" }
        pill.text = when {
            loadCommitted -> "Loading content\u2026"
            elapsed < 4000L -> "Connecting to " + host + "\u2026"
            else -> "Waiting for " + host + "\u2026"
        }
        pill.contentDescription = pill.text
        pill.visibility = View.VISIBLE
        if (!loadCommitted && elapsed < 4000L) {
            uiHandler.postDelayed(loadStatusTick, 4000L - elapsed)
        }
    }

    // ---------- Load measurement ----------

    /**
     * Times the page that just finished loading in [tab] (PageMetrics): DNS,
     * connection, TLS, time to first byte, first and largest paint, long
     * tasks, bytes and page size, and from those its weight. The watchers
     * start now; the reading is taken a little later, so a large picture or
     * script arriving after the load event still counts. A newer load in the
     * tab, or the tab losing its WebView, calls it off.
     */
    private fun measurePage(web: WebView, tab: Tab) {
        val token = tab.loadToken
        if (tab.measuredToken == token) return
        tab.measuredToken = token
        tab.metrics = null
        web.evaluateJavascript(PageMetrics.observeJs, null)
        uiHandler.postDelayed({
            if (!isLiveWeb(web) || tab.webView !== web || tab.loadToken != token) {
                return@postDelayed
            }
            web.evaluateJavascript(PageMetrics.collectJs) { result ->
                val m = PageMetrics.parse(result)
                if (m != null && tab.loadToken == token) {
                    tab.metrics = m
                    DebugLog.add { "tab#" + tab.id + " metrics " + m.summary() }
                    HealthStats.page(this, m)
                }
            }
        }, 2500L)
    }

    /**
     * The host of the website on screen, for the menu's per-site ad blocking
     * row - or null when ad blocking is off or no website is showing.
     */
    private fun siteForAds(): String? {
        if (!AdBlocker.isEnabled(this)) return null
        val url = tabs.activeTab?.url ?: return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try { android.net.Uri.parse(url).host?.ifBlank { null } } catch (e: Exception) { null }
    }

    /** Whether the page on screen is still loading. */
    private fun pageLoading(): Boolean = (activeWeb()?.progress ?: 100) in 1..99

    private fun retryInterruptedLoad() {
        loadInterrupted = false
        val tab = loadWatchTab
        loadWatchTab = null
        updateLoadStatus()
        val web = activeWeb() ?: return
        if (tab === tabs.activeTab) web.reload()
    }

    private fun isErrorPage(url: String?): Boolean =
        url != null && (url.startsWith("file:///android_asset/offline.html") ||
            url.startsWith("file:///android_asset/error.html")) &&
            // A page stopped for not responding is not a failure to retry by
            // itself: it would only stop everything again (stoppedPage).
            !url.contains("stopped=1")

    /**
     * The page shown in place of one the user ended for not responding: what
     * happened, and a Try again that goes back to [url] only when tapped.
     */
    private fun stoppedPage(url: String, crashed: Boolean = false): String {
        val base = "file:///android_asset/error.html?stopped=1" + (if (crashed) "&crashed=1" else "")
        return try {
            base + "&u=" + java.net.URLEncoder.encode(url, "UTF-8")
        } catch (e: Exception) { base }
    }

    /**
     * Back online: the tab on screen tries again if it is showing the offline
     * or "can't reach" page in place of a page, or had a load cut short.
     */
    private val recoverAfterReconnect = Runnable {
        val tab = tabs.activeTab
        val web = activeWeb()
        if (tab != null && web != null && hasNetwork()) {
            val failed = tab.failedUrl
            when {
                failed != null && isErrorPage(web.url) -> loadInto(web, failed)
                loadInterrupted && loadWatchTab === tab -> retryInterruptedLoad()
            }
        }
    }

    private fun siteSettingsSignature(): String {
        return listOf(
            Settings.getBool(this, Settings.SITE_JAVASCRIPT, true),
            Settings.getBool(this, Settings.SITE_BLOCK_IMAGES, false),
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
    private var suggestAdapter: SuggestAdapter? = null
    @Volatile private var suggestSeq = 0

    // The suggestion backdrop's own bottom padding from the layout, kept so
    // the keyboard inset can be added on top of it rather than replacing it.
    // Each overlay's own bottom padding, before reachUnderNavBar adds to it.
    private val navPadBase = HashMap<View, Int>()

    /**
     * Stretches [v] down over the strip under the navigation bar, which the
     * root leaves as padding, so its background runs to the bottom of the
     * screen as the bottom bar's does. The strip is added to its bottom
     * padding, so nothing in it is laid out behind Android's buttons.
     * [padding] is the bottom padding it would have without the strip.
     */
    private fun reachUnderNavBar(v: View, strip: Int,
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

    private var suggestBasePad = -1

    private fun setupSuggestOverlay() {
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

    private fun fetchSuggests(query: String) {
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
                val items = first + buildSuggestions(q).filter { it.optString("url") !in firstUrls }
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
    private fun openTabSuggestions(q: String): List<JSONObject> {
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
    private fun copiedLinkSuggestion(q: String): JSONObject? {
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
    private fun readClipboardText(): String? = try {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            ?.coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length < 2048 }
    } catch (e: Exception) { null }

    private fun looksLikeLink(text: String): Boolean =
        !text.contains(' ') && !text.contains('\n') &&
            android.util.Patterns.WEB_URL.matcher(text).matches()

    /**
     * While searching, the field becomes a card sitting above a stack of cards
     * rather than a flat grey pill across a white sheet. Margins widen to 12dp
     * so its edges line up with the suggestion cards, and it gains 4dp of height
     * so it does not read as the runt of the stack.
     *
     * Padding is captured and re-applied around the swap: setBackgroundResource
     * will overwrite a view's padding whenever the incoming drawable reports any
     * of its own, and the end inset differs between the two states because the
     * star button is hidden while searching.
     */
    // One control at three sizes: grey address bar while browsing, and the same
    // white pill as the home field when collapsed or searching. Previously the
    // collapsed state kept the browsing style, so it was the odd one of three.
    private val FIELD_NORMAL = 0
    private val FIELD_COMPACT = 1
    private val FIELD_SEARCH = 2

    // The pill's height in each state, in dp. Named rather than written into
    // the when below, because the collapse animation has to travel between two
    // of them instead of jumping.
    private val FIELD_H_NORMAL = 48f
    // Pinned to the top of the home page, and searching: 44 in the 58dp bar,
    // so the box sits centred with 7dp of air above and below, as Chrome's
    // does, rather than filling the bar and crowding the status bar and any
    // camera cutout above it. The buttons inside stay centred.
    private val FIELD_H_COMPACT = 44f

    /**
     * How far the search pill travels as it slides up into the bar, in dp.
     * Small on purpose: this is a dock, not an entrance.
     */
    private val SLIDE_DP = 12f
    private val FIELD_H_SEARCH = 44f

    /** The style styleUrlBar last gave the field. */
    private var fieldMode = FIELD_NORMAL

    private fun styleUrlBar(mode: Int) {
        val d = resources.displayMetrics.density
        val pill = mode != FIELD_NORMAL
        val top = binding.urlBar.paddingTop
        val bottom = binding.urlBar.paddingBottom
        fieldMode = mode
        // Searching, the field is a card like the one the suggestions are
        // in. Pinned to the top of the home page, it is the home page's own
        // search box: its colour, its size, no outline - the same box,
        // whether the page has scrolled or not.
        binding.urlBar.setBackgroundResource(when (mode) {
            FIELD_SEARCH -> R.drawable.urlbar_search_card
            FIELD_COMPACT -> R.drawable.urlbar_search_bg
            else -> R.drawable.urlbar_bg
        })
        binding.urlBar.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            when (mode) {
                FIELD_SEARCH -> 18f
                FIELD_COMPACT -> 16f
                else -> 15f
            })
        binding.urlBar.hint = if (pill) "Search the web" else "Search"
        binding.urlBar.setHintTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.sugMuted))
        refreshAdSlot()

        if (pill) {
            updateSearchFieldActions(top, bottom)
        } else {
            binding.clearBtn.visibility = View.GONE
            binding.searchActions.visibility = View.GONE
            // 48dp star + its 8dp margin. Was 38 for a 30dp star. The site's
            // icon, when it shows, sits before the text.
            val start = if (binding.siteIcon.visibility == View.VISIBLE) 48 else 14
            binding.urlBar.setPaddingRelative((start * d).toInt(), top, (56 * d).toInt(), bottom)
        }

        val lp = binding.urlBarContainer.layoutParams
            as? android.widget.LinearLayout.LayoutParams ?: return
        // The field reaches as far as it can: about 9dp of air to the owl
        // (whose picture is inset in its square) and 10dp to the gear's icon
        // (centred in its 44dp button), the same either side. With the gear
        // away while searching, 8dp to the screen edge. The old 6dp margins
        // added to those insets left gaps twice as wide as the ones the
        // field sits between on the home page.
        // Pinned on the home page, with the owl and the gear gone, it has the
        // home box's 16dp from either edge (12dp plus the bar's own 4dp).
        lp.marginStart = ((if (mode == FIELD_COMPACT) 12 else 4) * d).toInt()
        lp.marginEnd = (when (mode) {
            FIELD_SEARCH -> 4
            FIELD_COMPACT -> 12
            else -> 0
        } * d).toInt()
        // Each of these has to hold a 48dp control, which is what a touch
        // target has to be. The three sizes stay three sizes - the step between
        // browsing and collapsed is the same 4dp it was - they just start from
        // a height a thumb can actually hit.
        lp.height = (when (mode) {
            FIELD_SEARCH -> FIELD_H_SEARCH
            FIELD_COMPACT -> FIELD_H_COMPACT
            else -> FIELD_H_NORMAL
        } * d).toInt()
        binding.urlBarContainer.layoutParams = lp
        // The hint colour was just reset; on a bar the page has coloured the
        // field keeps its pane and its readable text.
        if (fieldPaneFor != null || topOnDark) paintTopForeground()
    }

    private fun styleUrlBarForSearch(searching: Boolean) {
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
    private fun updateSearchFieldActions(
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

    // ---------- The site's icon in the search field ----------

    /**
     * The site's crisp icon at the start of the address box: there as soon as
     * the page has loaded, and kept while a search is typed from it. Nothing
     * on the home page or on pages that are not websites. [fetch]: the page
     * has loaded, so its declared icons can be asked for; before that only an
     * icon already known for the site is shown, never the last site's.
     */
    private fun showSiteIcon(url: String?, fetch: Boolean = true) {
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

    private fun setSiteIcon(icon: Bitmap) {
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

    private fun hideSiteIcon() {
        val v = binding.siteIcon
        if (v.visibility == View.GONE) return
        v.visibility = View.GONE
        v.setImageDrawable(null)
        padFieldForIcon()
    }

    /** Moves the field's text clear of the icon, or back, for whichever look the field has. */
    private fun padFieldForIcon() {
        if (fieldMode != FIELD_NORMAL) { updateSearchFieldActions(); return }
        val d = resources.displayMetrics.density
        val start = if (binding.siteIcon.visibility == View.VISIBLE) 48 else 14
        binding.urlBar.setPaddingRelative((start * d).toInt(), binding.urlBar.paddingTop,
            (56 * d).toInt(), binding.urlBar.paddingBottom)
    }

    /** Rounded corners, as a launcher draws a site's icon. */
    private object SiteIconOutline : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            // Inside the padding: the view is a 40dp touch target around a 24dp icon.
            val w = view.width - view.paddingLeft - view.paddingRight
            outline.setRoundRect(view.paddingLeft, view.paddingTop,
                view.width - view.paddingRight, view.height - view.paddingBottom, w * 0.22f)
        }
    }

    /**
     * The same curve the home bar collapses on. Search and scroll are the two
     * things that move this chrome, and they were never going to read as one
     * app while they used different easing.
     */
    private val searchEase = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

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
    private fun animateSearchIn(fieldWasShowing: Boolean) {
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
    private fun animateSearchOut() {
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

    private fun enterSearchMode() {
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
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
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

    private fun exitSearchMode() {
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
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
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

    // ---- HTTPS-only ----
    //
    // The setting reads "Always try to connect securely and warn on insecure
    // sites". It did neither: the upgrade happened only in go(), so it covered
    // what was typed into the address bar and nothing else. Every http link
    // tapped on a page loaded in the clear, and there was no warning anywhere.

    /**
     * Hosts the user has chosen to open over http anyway, for this run of the
     * app only. A decision to give up encryption should not outlive the
     * session that made it, and should never be written to disk.
     */
    private val httpAllowed = mutableSetOf<String>()
    /** https addresses produced here, mapped back to the http they came from. */
    private val upgradedFrom = HashMap<String, String>()
    /** When each host was last upgraded, to break a redirect loop. */
    private val upgradedAt = HashMap<String, Long>()
    private var pendingInsecure: String? = null

    private fun httpsOnly() = Settings.getBool(this, Settings.SEC_HTTPS_ONLY, true)

    /**
     * Shows the warning in place of the page, holding the address so
     * "Continue anyway" has somewhere to go.
     */
    private fun showInsecureWarning(view: WebView?, httpUrl: String) {
        pendingInsecure = httpUrl
        val enc = try {
            java.net.URLEncoder.encode(httpUrl, "UTF-8")
        } catch (e: Exception) { "" }
        // Both callers are WebView callbacks, so the load waits until they
        // have returned. See loadAfterCallback.
        loadAfterCallback(view, "file:///android_asset/insecure.html?u=" + enc)
    }

    // Night Owl (private browsing) mode state.
    private var nightOwl = false

    override fun onResume() {
        super.onResume()
        // Back from the background: an app open ad that is ready opens the
        // way in (a launch is handled by the splash: onSplashMinimumDone).
        if (AppOpenAds.takeOpen() == AppOpenAds.WARM_OPEN) warmOpenAd()
        resumePendingApkInstall()
        refreshScrollPrefs()
        // Back after a while: the owl says hello - wide awake after twenty
        // minutes or more, a nod after a few. Not for a trip to Settings, a
        // permission dialog or the share sheet. After four hours, home is
        // where it opens.
        val away = if (leftAt > 0L) android.os.SystemClock.elapsedRealtime() - leftAt else 0L
        when {
            away >= 20L * 60_000L -> binding.navOwl.requestGreet(OwlButtonView.Hello.RETURN)
            away >= 3L * 60_000L -> binding.navOwl.requestGreet(OwlButtonView.Hello.NOD)
        }
        // Nothing opaque can still be over the owl unless search or the deck is up.
        if (!searchMode && !deckVisible) binding.navOwl.covered = false
        if (away > AWAY_FOR_HOME_MS && !searchMode && !deckVisible && fullscreenView == null &&
            urlFromIntent(intent) == null) {
            showHomeAfterAway()
        }
        // Top Stories fetched while the app comes to the front, so home opens
        // on fresh ones rather than waiting for them; nothing is fetched when
        // the saved ones are under half an hour old.
        // A mode changed in Settings (or the introduction): the home page
        // takes it now, with no reload of anything else.
        val mode = BrowsingMode.get(this)
        val zen = Settings.getBool(this, BrowsingMode.ZEN_SHORTCUTS, false)
        if (mode != browsingMode || zen != zenShortcuts) applyBrowsingMode(mode, zen)
        // News only for Explore: Focus and Zen fetch none, not even behind the scenes.
        if (!nightOwl && browsingMode == BrowsingMode.EXPLORE) {
            NewsFeed.refreshIfDue(this) { fresh -> runOnUiThread { pushFeedUpdate(fresh) } }
        }
        // Back from the introduction on a fresh install: consent, then the ads.
        if (adsDeferred && BrowsingMode.introDone(this)) { adsDeferred = false; initAds() }
        uiHandler.postDelayed({ maybeAnnounceModes() }, 900L)
        if (!tintBarsPref) setBarTint(null, null, animate = false)
        HealthStats.sessionStarted(this)
        registerNetworkWatch()
        // If a flexible update finished downloading while away, offer to install it.
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() ==
                com.google.android.play.core.install.model.InstallStatus.DOWNLOADED) {
                android.widget.Toast.makeText(this,
                    "Update ready — finishing install", android.widget.Toast.LENGTH_SHORT).show()
                appUpdateManager.completeUpdate()
            }
        }
        // An accent changed in Settings reaches the dialogs opened from here on.
        Settings.applyAccent(this)
        applyAccentTints()
        refreshStaleFeedAd()
        resumeFeedAd()
        // Back from the supporter page, possibly having just bought the ads
        // away. Above the early return below, which fires whenever the site
        // settings are unchanged - which is nearly every resume.
        if (Settings.getBool(this, Settings.IS_SUPPORTER, false) && nativeAd != null) {
            binding.adSlot.removeAllViews()
            nativeAd?.destroy()
            nativeAd = null
            refreshAdSlot()
        }
        val sig = siteSettingsSignature()
        if (sig == lastSiteSig) return  // nothing changed -> don't touch anything
        lastSiteSig = sig

        val js = Settings.getBool(this, Settings.SITE_JAVASCRIPT, true)
        val blockImg = Settings.getBool(this, Settings.SITE_BLOCK_IMAGES, false)
        val blockAutoplay = Settings.getBool(this, Settings.SITE_BLOCK_AUTOPLAY, true)
        val safeBrowsing = Settings.getBool(this, Settings.SEC_SAFE_BROWSING, true)
        val block3p = Settings.getBool(this, Settings.SEC_BLOCK_3P_COOKIES, false)
        tabs.tabs.forEach { tab ->
            tab.webView?.settings?.apply {
                textZoom = zoomFor(tab.webView?.url ?: tab.url)
                javaScriptEnabled = js
                blockNetworkImage = blockImg
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = safeBrowsing
                }
                applyDarkWeb(this)
            }
            (tab.webView as? BrowserWebView)?.blockAutoplay = blockAutoplay
            // Third-party cookies are a property of the WebView held by the
            // CookieManager, not of its settings object.
            tab.webView?.let {
                android.webkit.CookieManager.getInstance()
                    .setAcceptThirdPartyCookies(it, !block3p)
            }
        }
        // Settings are applied live to the WebView above; we do NOT reload,
        // because reloading wipes the tab's back/forward history. Changes take
        // full effect on the next navigation.
    }
    /**
     * When the browser was last left, on the clock that keeps running while
     * the phone sleeps (uptime stops, so a night away read as minutes): for
     * the owl's hello and for home after a long break.
     */
    private var leftAt = 0L

    override fun onPause() {
        super.onPause()
        leftAt = android.os.SystemClock.elapsedRealtime()
        Settings.setLong(this, Settings.LEFT_AT, System.currentTimeMillis())
        PrivacyStats.flush(this)
        unregisterNetworkWatch()
        // Being left - possibly for good, by a swipe from Recents. The tabs go
        // to disk now, every live one's history included.
        SessionStore.restoreSettled(this)
        saveSession(full = true)
        HealthStats.sessionEnded(this)
        // Persist cookies to disk so logins survive the app being killed
        // (common on low-RAM devices). Without this, sessions can be lost.
        //
        // Never on the main thread: flush() blocks its caller until the
        // cookie store has written to disk. Called here, on every pause, it
        // was the app's ANR - the main thread asleep on a native lock while
        // the user was trying to leave. Running it a moment later on a
        // background thread loses nothing, because the store is still there.
        cookieIo.execute {
            try { android.webkit.CookieManager.getInstance().flush() } catch (e: Exception) {}
        }
    }

    // Held as a field so onDestroy can tell whether the one installed on
    // MediaService is still this activity's, rather than a newer one's.
    private val mediaControl: (String) -> Unit = { action ->
        runOnUiThread {
            activeWeb()?.evaluateJavascript(
                "window.__searchMediaControl && window.__searchMediaControl('" + action + "');", null)
        }
    }

    /**
     * Carries the tab list across an activity recreation.
     *
     * The tabs are a plain field of the activity, so a new MainActivity always
     * began with an empty TabManager. That is why the `tabs.count() == 0` test
     * in onCreate - commented as preventing tab loss on recreation - could
     * never once have prevented it: the count is always zero on a new
     * instance. Every recreation took every tab with it, and changing the
     * theme in Settings is a recreation.
     *
     * A ViewModel outlives the activity but not the process, which is exactly
     * the right lifetime: it carries the full WebView back/forward state
     * through a rotation or a theme change. Process death is handled
     * separately, out of saved instance state, where only addresses survive.
     */
    class TabStore : androidx.lifecycle.ViewModel() {
        class Snap(
            val url: String,
            val title: String,
            val state: Bundle?,
            // The opener's position in the list, not its id: ids are handed out
            // afresh by the new TabManager, so an id captured here would point
            // at the wrong tab or none at all.
            val openerIndex: Int,
            // What the sign-in recovery knows about a pop-up (Tab.kt).
            val openerSite: String? = null,
            val leftOpenerSite: Boolean = false,
            val signIn: Boolean = false,
            // The on-disk session's view of the tab (SessionStore, Tab.kt).
            val isPrivate: Boolean = false,
            val diskKey: String? = null,
            val diskStateHash: Int = 0
        )
        var snapshot: List<Snap>? = null
        var activeIndex: Int = 0
    }

    private val tabStore: TabStore by viewModels()

    /** Freezes the current tab list into the store, without disturbing it. */
    private fun snapshotTabs() {
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
                diskStateHash = t.diskStateHash
            )
        }
        tabStore.activeIndex = list.indexOf(tabs.activeTab).coerceAtLeast(0)
    }

    /**
     * Rebuilds the tab list after a recreation. Returns false when there is
     * nothing to restore and a fresh tab should be opened instead.
     */
    private fun restoreTabs(saved: Bundle?): Boolean {
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
        openTab(tabs.tabs.getOrNull(saved.getInt("tab_active", 0)) ?: tabs.tabs.first())
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        snapshotTabs()
        // Addresses only here, deliberately. Instance state crosses a Binder
        // transaction with a hard size limit, and a WebView's saved history is
        // easily big enough to burst it - which fails as a crash on the way
        // out. The full state rides in the ViewModel instead, where there is
        // no such limit.
        outState.putStringArrayList("tab_urls", ArrayList(tabs.tabs.map { it.url }))
        outState.putStringArrayList("tab_titles", ArrayList(tabs.tabs.map { it.title }))
        outState.putInt("tab_active", tabStore.activeIndex)
    }

    // ---------- The session on disk ----------
    //
    // Tabs written to disk (SessionStore) shortly after every navigation and
    // tab change, and at once whenever the app is left, so a crash or a swipe
    // from Recents brings them back. Private tabs are never written.

    private val sessionSave = Runnable { saveSession(full = false) }

    /** A save a moment from now, so a burst of navigations is one write. */
    private fun scheduleSessionSave() {
        uiHandler.removeCallbacks(sessionSave)
        uiHandler.postDelayed(sessionSave, 1500L)
    }

    /**
     * Collects the session on the main thread, which WebView.saveState needs,
     * and hands it to SessionStore to write. [full] refreshes every live tab's
     * history - for when the app is being left. Otherwise only the tab on
     * screen, which is the one that moved, plus any tab with none on disk yet.
     */
    private fun saveSession(full: Boolean) {
        uiHandler.removeCallbacks(sessionSave)
        if (!::binding.isInitialized) return
        val keep = tabs.tabs.filter { !it.isPrivate && it.url != "about:blank" }
        val active = tabs.activeTab
        val entries = keep.map { t ->
            // Standing in for a page that failed: saved as that page, without
            // a history that would only lead back to the error page.
            val failed = t.stoppedUrl?.let { stoppedPage(it) }
                ?: t.failedUrl?.takeIf { isErrorPage(t.url) }
            if (failed != null) t.diskStateHash = 0
            var bytes: ByteArray? = null
            if (failed == null && (full || t === active || t.diskStateHash == 0)) {
                val bundle = t.webView?.let { w ->
                    try { Bundle().also { b -> w.saveState(b) } } catch (e: Exception) { null }
                } ?: t.savedState
                val b = bundle?.let { SessionStore.marshall(it) }
                if (b != null && b.size <= SessionStore.MAX_STATE_BYTES) {
                    val hash = b.contentHashCode()
                    if (hash != t.diskStateHash) {
                        bytes = b
                        t.diskStateHash = hash
                    }
                }
            }
            SessionStore.Entry(
                key = t.diskKey,
                url = failed ?: t.url,
                title = t.title,
                openerIndex = keep.indexOfFirst { it.id == t.openerId },
                signIn = t.signIn,
                hasState = t.diskStateHash != 0,
                state = bytes
            )
        }
        val activeIndex = keep.indexOf(active).let { if (it >= 0) it else keep.size - 1 }
        SessionStore.save(this, entries, activeIndex.coerceAtLeast(0))
    }

    /**
     * Brings back the tabs saved on disk, for a launch with nothing in memory
     * or instance state. The tab that was on screen opens; the others wait,
     * frozen, with their history still on disk until they are opened.
     */
    private fun restoreSessionFromDisk(): Boolean {
        val session = SessionStore.load(this) ?: return false
        val failed = SessionStore.beginRestore(this)
        if (failed >= 2) {
            // Two launches in a row died restoring this session. Start fresh.
            SessionStore.clear(this)
            SessionStore.restoreSettled(this)
            return false
        }
        // One did: bring back the addresses, but not the saved histories.
        val useState = session.stateUsable && failed == 0
        session.tabs.forEach { s ->
            val t = tabs.createTab(s.url)
            t.title = s.title
            t.signIn = s.signIn
            t.diskKey = s.key
            if (useState && s.hasState) {
                t.diskStateFile = SessionStore.stateFile(this, s.key)
                t.diskStateHash = 1
            }
            t.openerLoadsAtOpen = -1
        }
        session.tabs.forEachIndexed { i, s ->
            if (s.openerIndex >= 0) {
                tabs.tabs[i].openerId = tabs.tabs.getOrNull(s.openerIndex)?.id
            }
        }
        openTab(tabs.tabs.getOrNull(session.active) ?: tabs.tabs.first())
        // Settled once the app is up; onPause settles it sooner.
        uiHandler.postDelayed({ SessionStore.restoreSettled(this) }, 8000L)
        return true
    }

    private lateinit var binding: ActivityMainBinding
    private val homePage = "file:///android_asset/home.html"
    private val tabs = TabManager(maxLiveTabs = 3)
    private lateinit var tabAdapter: TabAdapter

    private val thumbWidthPx = 400
    // Capture the deck thumbnail at reduced resolution to cut the
    // main-thread bitmap allocation (full-screen was ~5MB per open).
    // PixelCopy scales the source rect into this smaller dest bitmap.
    private val thumbCaptureDivisor = 2
    private var deckVisible = false
    // Held true to keep the system splash on its final frame briefly so the
    // launch animation lands cleanly before the browser appears.
    private var keepSplash = true
    private val appUpdateManager by lazy {
        com.google.android.play.core.appupdate.AppUpdateManagerFactory.create(this)
    }
    private val updateLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { /* user accepted/dismissed the Play update UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Launched with the splash theme so the system splash shows the owl;
        // switch to the real app theme before inflating the browser UI.
        setTheme(R.style.Theme_Search)
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { keepSplash }
        // Release the splash ~1.2s after the animation so it settles, then
        // enters - or, on an open with an app open ad, hold it for the ad,
        // which is shown over it, so the browser opens splash, ad, home.
        // Opened for something specific (the widget, a link, a shortcut) it
        // goes as soon as the browser is drawn: the launcher's own animation
        // from the widget has already led in, and a second and a half of
        // splash after it read as a stall.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            onSplashMinimumDone()
        }, if (savedInstanceState == null && opensForATask(intent)) 150L else 1500L)
        enableEdgeToEdge()
        // The bottom bar paints behind the navigation bar itself
        // (NavSheetView.bottomInset), so Android's own shade over the buttons
        // goes: with three-button navigation it laid a second, different
        // colour under the bar - a dark band below a dark bar. Before Android
        // 10 there is no switch for the shade, so the bar is made clear; under
        // Android 8 its buttons are always white and keep their dark backing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        // Debug builds report main-thread disk and network use and leaked
        // resources to the log - the slow paths that become ANRs on a cheap
        // phone - so they are seen while testing. Log only: never a crash.
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
        }
        // The saved theme, before super.onCreate so the browser is built once,
        // in the chosen theme (Settings.applyNightMode).
        Settings.applyNightMode(this)
        super.onCreate(savedInstanceState)
        // The chosen accent over the theme, for dialogs and text handles.
        Settings.applyAccent(this)
        // The browsing mode (Explore, Focus, Zen): upgraded installs are put
        // on Explore once, before anything reads it or onPause writes LEFT_AT.
        BrowsingMode.migrate(this)
        browsingMode = BrowsingMode.get(this)
        zenShortcuts = Settings.getBool(this, BrowsingMode.ZEN_SHORTCUTS, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The app open ad watches opens from here on, and asks this activity
        // whether it may be asked for and shown (AppOpenAds).
        AppOpenAds.install(application)
        AppOpenAds.gate = appOpenGate
        if (opensForATask(intent)) AppOpenAds.skipThisOpen()
        launchedForTask = opensForATask(intent)
        // A plain launch (not a link, not a recreation), consent already
        // given on an earlier launch, an ad possible: the splash will wait a
        // moment for the app open ad. Never on the very first launch, which
        // has the consent message and the owl's introduction to get through.
        coldLaunch = savedInstanceState == null
        // A fresh install: the owl's introduction opens straight from the
        // splash - home is never seen first - and the consent message and
        // the ads wait until it is done (adsDeferred, onResume).
        introFromSplash = savedInstanceState == null && !launchedForTask && !BrowsingMode.introDone(this)
        // The owl's hello, asked for now so its first frame after the splash
        // is already the sleeping owl; it plays once the bar is on screen and
        // the app has settled (OwlButtonView.requestGreet). A fresh start, or
        // a restore after twenty minutes away, gets the full hello; a quick
        // restore or a link from another app, a nod.
        if (!greetedThisProcess) {
            greetedThisProcess = true
            val leftWall = Settings.getLong(this, Settings.LEFT_AT, 0L)
            val awayWall = if (leftWall > 0L) System.currentTimeMillis() - leftWall else Long.MAX_VALUE
            binding.navOwl.requestGreet(when {
                urlFromIntent(intent) != null -> OwlButtonView.Hello.NOD
                savedInstanceState == null || awayWall >= 20L * 60_000L -> OwlButtonView.Hello.COLD
                else -> OwlButtonView.Hello.NOD
            })
        }
        // The bottom bar's overlap as laid out (the home page's), before
        // anything moves it (applyBarOverlap, setPageUnderBars).
        barRestOverlap = -((binding.bottomBar.layoutParams as? android.widget.LinearLayout.LayoutParams)?.topMargin ?: 0)
        // Health numbers (HealthStats): the last session's ending, Android's
        // exit records, the crash counter; and how long until the browser is
        // ready to draw - from the process starting on a cold start, from
        // here on a recreation.
        HealthStats.onLaunch(this)
        val createdAt = android.os.SystemClock.uptimeMillis()
        val coldStart = HealthStats.claimColdStart()
        androidx.core.view.OneShotPreDrawListener.add(binding.root) {
            val now = android.os.SystemClock.uptimeMillis()
            val sinceProcess = now - android.os.Process.getStartUptimeMillis()
            if (coldStart && sinceProcess < 30_000L) {
                HealthStats.time(this, HealthStats.COLD_START, sinceProcess.toDouble())
            } else {
                HealthStats.time(this, HealthStats.WARM_START, (now - createdAt).toDouble())
            }
        }
        // Edge-to-edge (mandatory on Android 16 / SDK 36): pad the root by the
        // system-bar insets so the top bar sits below the status bar and content
        // stays above the navigation bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // A camera cut into the top of the screen - a teardrop or a hole -
            // is normally inside the status bar, but not on every phone. The
            // top is kept clear of whichever reaches further, so the bar and
            // the search box never sit under the camera.
            val cutoutTop = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top
            val bars = androidx.core.graphics.Insets.of(systemBars.left,
                maxOf(systemBars.top, cutoutTop), systemBars.right, systemBars.bottom)
            // Under edge-to-edge the keyboard never resizes the window - it
            // just arrives as an inset. Nothing read it for the page, so a
            // field in the lower half of a form - the email or password box of
            // a sign-up - sat behind the keyboard while being typed into, and
            // the page could not scroll it into view because, as far as the
            // WebView knew, nothing had covered it. Taking the keyboard off the
            // bottom of the whole layer shrinks the page above it, and
            // Chromium then brings the focused field into view as Chrome does.
            // The suggestion list, inside the same layer, ends where the
            // keyboard starts for the same reason.
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val typing = ime > bars.bottom
            // Only when the page itself is being typed into. In the address
            // bar or the tab search the page is covered anyway, and resizing
            // it would make a heavy site relayout - and fire resize at its
            // own menus and players - for every tap on the address bar.
            val pageTyping = typing && !searchMode && !deckVisible
            v.setPadding(bars.left, bars.top, bars.right, if (pageTyping) ime else bars.bottom)
            // How far the top sheet paints above itself to fill the status bar
            // strip. Layout is untouched - only the surface goes up.
            binding.topBar.topInset = bars.top
            // And the bottom bar steps out of the way while typing into the
            // page, as it does in Chrome, so the keyboard does not cost the
            // page its height twice.
            this.pageTyping = pageTyping
            // Typing into a page brings the bars back (as in Chrome); the page
            // then shrinks above the keyboard as it always has.
            if (pageTyping && barsHidden) showBars(animate = false)
            if (!barsHidden) {
                val bar = if (pageTyping) View.GONE else View.VISIBLE
                if (binding.bottomBar.visibility != bar) binding.bottomBar.visibility = bar
            }
            // The suggestion list ends where the keyboard starts: padded by
            // whatever the keyboard covers beyond what the root already gives
            // back for the navigation bar. With clipToPadding=false that is
            // scrollable room, not dead space. On the backdrop, not the card,
            // so the card stops at the keyboard rather than growing a band.
            // Read once, before anything here has added to it.
            if (suggestBasePad < 0) suggestBasePad = binding.suggestBackdrop.paddingBottom
            val base = suggestBasePad
            val extra = if (pageTyping) 0 else (ime - bars.bottom).coerceAtLeast(0)
            // The strip under the navigation bar. The bottom bar's sheet
            // paints down into it, so the bar and the strip under Android's
            // buttons are one surface, and the screens that cover the bar -
            // the tabs, the search sheet, the menu's dimming - reach down
            // with it, each in its own colour. Nothing is laid out there:
            // each keeps the strip as padding.
            val navStrip = if (pageTyping) 0 else bars.bottom
            binding.navSheet.bottomInset = navStrip
            reachUnderNavBar(binding.tabDeck, navStrip)
            reachUnderNavBar(binding.menuScrim, navStrip)
            if (barsHidden) v.post { if (barsHidden) showEdgeScrims() }
            reachUnderNavBar(binding.suggestBackdrop, navStrip, base + extra)
            insets
        }
        setupSuggestOverlay()
        // The page changes height when the keyboard comes and goes, and when
        // the bars leave or come back (setPageUnderBars). The feed ad card is
        // placed against the page's bottom and only re-placed on scroll, so it
        // is re-seated here too - in this same layout pass, before the frame
        // is drawn. It used to be posted, so for one frame after each resize
        // the card kept the offset worked out for the old height: at the end
        // of the bars' return the page lost 86dp (58 of top bar, 28 of bottom
        // bar) and the card flashed 86dp too low before snapping into place.
        // The posted pass stays as a second look, for a WebView that settles
        // its scroll a moment after its size.
        binding.webArea.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                activeWeb()?.let { w ->
                    if (isLiveWeb(w)) positionAdSlot(w, w.scrollY)
                    binding.adSlot.post { if (isLiveWeb(w)) positionAdSlot(w, w.scrollY) }
                }
            }
        }
        lastSiteSig = siteSettingsSignature()

        onBackPressedDispatcher.addCallback(this, object :
            androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = activeWeb()
                when {
                    // The splash picture is up while the app open ad loads:
                    // it is the way in, as the splash is, and Back does not
                    // act on the page hidden under it.
                    launchCover != null -> {}
                    // Fullscreen is a mode over the whole app, so it unwinds first.
                    fullscreenView != null -> exitFullscreen()
                    findActive -> closeFindBar()
                    searchMode -> exitSearchMode()
                    binding.menuScrim.visibility == View.VISIBLE -> closeMenu()
                    binding.tabDeck.visibility == View.VISIBLE -> closeDeck()
                    web?.canGoBack() == true -> web.goBack()
                    // A tab a PAGE opened - an ad, a sign-in pop-up, any
                    // target="_blank" link. It has no history of its own, so
                    // this used to fall through to finish() and take the whole
                    // browser down with it. closeTabFromDeck resolves openerId
                    // and re-attaches the tab that opened this one, which is
                    // the same path window.close() already used.
                    tabs.activeTab?.openerId != null ->
                        tabs.activeTab?.let { foldBackPopup(it, "closed with Back") }
                    // A tab another app's link opened, read to its start: it
                    // closes and the app that sent it comes back, as in
                    // Chrome. Remembered, so Reopen closed tab still has it.
                    tabs.activeTab?.fromApp == true && tabs.count() > 1 -> {
                        tabs.activeTab?.let { t -> rememberClosed(t); closeTabFromDeck(t) }
                        if (!moveTaskToBack(true)) finish()
                    }
                    // Nothing left to go back to. Leave, but do not die:
                    // finish() destroys the activity and every live WebView
                    // with it, so returning means rebuilding every tab from
                    // saved state. Backgrounding keeps them warm, which is
                    // what a browser is expected to do. finish() stays as the
                    // fallback for the rare case the task cannot be backgrounded.
                    else -> if (!moveTaskToBack(true)) finish()
                }
            }
        })

        tabs.maxLiveTabs = liveTabBudget()
        tabs.onNeedFreeze = { tab -> freezeTab(tab) }
        tabs.canFreeze = { tab -> canFreezeTab(tab) }
        // Tabs opened in Night Owl are marked private, and never saved to disk.
        tabs.newTabsPrivate = { nightOwl }

        setupUrlBar()
        setupToolbar()
        setupDeck()
        setupFindBar()
        // Opened from the Night Owl tile or shortcut: Night Owl is switched on
        // a moment later (handleShortcut, posted), and no ad request may slip
        // out in between - the SDK can finish starting first.
        // The same test as `fresh` below, which decides whether the shortcut is acted on.
        nightOwlLaunchPending = savedInstanceState == null && tabStore.snapshot == null &&
            intent?.action == ACTION_NIGHT_OWL
        if (introFromSplash) adsDeferred = true else initAds()
        // Notification media buttons -> drive the page's media element.
        MediaService.onControl = mediaControl
        // Quietly check Play for a newer version on launch; prompts only if one
        // exists. Guarded to run once per process so an activity recreation
        // doesn't re-trigger the update prompt.
        if (!didLaunchUpdateCheck) {
            didLaunchUpdateCheck = true
            checkForUpdate(fromUser = false)
        }

        // One launch counted per process, next to the update check for the
        // same reason: a rotation is not a launch.
        if (!didOfferReview) {
            didOfferReview = true
            Reviews.noteLaunch(this)
            // Not now. The window is still settling, tabs are being restored,
            // and the update check above may be about to put its own dialog
            // up. A few seconds in, the app is idle and the home page is
            // there - which is the only moment this is not an interruption.
            binding.root.postDelayed({ maybeAskForReview() }, 6000L)
            // Later, and never in the same launch as a review: one ask at a time.
            binding.root.postDelayed({ maybeOfferDefaultBrowser() }, 9000L)
        }

        // A recreation must not re-handle the intent that launched the app, or
        // every rotation would reopen the link it was started with.
        val fresh = savedInstanceState == null && tabStore.snapshot == null
        if (!tabStore.snapshot.isNullOrEmpty() && restoreTabs(savedInstanceState)) {
            // A recreation: the tabs came back from memory, history and all.
        } else if (restoreSessionFromDisk()) {
            // From disk: a cold start after a crash or a swipe from Recents,
            // or Android having reclaimed the app - where the disk copy beats
            // instance state, which holds addresses only. A link the app was
            // launched with opens beside the tabs, as it would were it open.
            val link = if (fresh) urlFromIntent(intent) else null
            if (link != null) { addNewTab(resolveInput(link)); tabs.activeTab?.fromApp = true }
            else {
                // Four hours or more since the browser was last used: home.
                val left = Settings.getLong(this, Settings.LEFT_AT, 0L)
                val now = System.currentTimeMillis()
                if (fresh && left > 0L && now - left > AWAY_FOR_HOME_MS && now > left) showHomeAfterAway()
            }
        } else if (restoreTabs(savedInstanceState)) {
            // Instance state's addresses, when there is nothing on disk.
        } else {
            // If launched by a tapped/shared link, open that; otherwise home.
            val link = if (fresh) urlFromIntent(intent) else null
            val startUrl = link?.let { resolveInput(it) } ?: homePage
            val first = tabs.createTab(startUrl)
            first.fromApp = link != null
            openTab(first, startUrl)
        }
        updateTabCount()
        // Started from a "Saved" notification (SaveProgress): straight to Downloads.
        if (fresh && intent?.getStringExtra(BrowsingData.EXTRA_OPEN) == BrowsingData.OPEN_DOWNLOADS) {
            binding.root.post { if (!isFinishing && !isDestroyed) openDownloads() }
        }
        // Started from a launcher shortcut: once the browser is laid out.
        if (fresh) {
            val launch = intent
            binding.root.post {
                if (!isFinishing && !isDestroyed) handleShortcut(launch)
                // Whatever happened, the hold on ads for a Night Owl launch ends here.
                nightOwlLaunchPending = false
            }
        }

    }

    // Handles links tapped/shared while the app is already open (singleTop
    // delivers them here instead of a fresh onCreate). Opens the link in a
    // new tab via the normal go() path.
    // ---------- Launcher shortcuts (res/xml/shortcuts.xml) ----------


    /** Acts on a launcher shortcut's intent; false when [intent] is not one. */
    private fun handleShortcut(intent: android.content.Intent?): Boolean {
        when (intent?.action) {
            ACTION_SEARCH -> { leaveTransientUi(); closeDeckIfOpen(); enterSearchMode() }
            ACTION_NEW_TAB -> { leaveTransientUi(); closeDeckIfOpen(); addNewTab(homePage) }
            ACTION_NIGHT_OWL -> {
                leaveTransientUi(); closeDeckIfOpen()
                if (nightOwl) addNewTab(homePage) else enterNightOwl()
                nightOwlLaunchPending = false
            }
            ACTION_SCAN -> { leaveTransientUi(); launchScan() }
            ACTION_VOICE -> { leaveTransientUi(); closeDeckIfOpen(); launchVoiceSearch() }
            else -> return false
        }
        return true
    }

    private fun closeDeckIfOpen() { if (deckVisible) closeDeck() }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Brought up to open a link or a shortcut: no app open ad over that.
        if (opensForATask(intent)) AppOpenAds.skipThisOpen()
        if (handleShortcut(intent)) return
        // Settings > Browsing data > Downloads.
        if (intent.getStringExtra(BrowsingData.EXTRA_OPEN) == BrowsingData.OPEN_DOWNLOADS) {
            leaveTransientUi()
            openDownloads()
            return
        }
        val incoming = urlFromIntent(intent)
        if (incoming != null) {
            // Straight into the new tab. This used to open home and then
            // navigate away from it, which left the home page sitting in the
            // new tab's history - so back, from a link someone had just shared
            // into the browser, went to the home page instead of leaving.
            addNewTab(resolveInput(incoming))
            tabs.activeTab?.fromApp =
                !intent.getBooleanExtra(ProcessTextActivity.EXTRA_FROM_SELF, false)
        }
    }

    // ---------- JS bridge ----------

    /**
     * The bridge between a page and the app.
     *
     * It is attached to every WebView, because a few of its methods have to
     * work on ordinary web pages: the media detector reports playback from
     * whatever site is playing, and a blob: save can only be read out of the
     * page that owns the blob. Everything else here exists for this app's own
     * asset pages - and every one of them was reachable from any site the user
     * visited. Three lines of script on any page could read getRecentSites()
     * and post the user's browsing history back out through open().
     *
     * [privileged] separates the two halves. It is set in onPageStarted for the
     * document loading in this bridge's own WebView, so it is already correct
     * before that document's script runs, and it fails closed: a method that
     * cannot show it is talking to an asset page does nothing at all.
     *
     * The blob callbacks are deliberately not gated on it. They are meant to
     * run on real pages, and they carry a one-shot token instead, which is the
     * right control for them.
     */
    inner class SearchAppBridge {
        // Volatile: bridge calls arrive on a WebView thread, not the main one.
        @Volatile var privileged: Boolean = false

        @JavascriptInterface
        fun submit(query: String) {
            if (!privileged) return
            runOnUiThread { go(query) }
        }
        @JavascriptInterface
        fun open(url: String) {
            if (!privileged) return
            runOnUiThread { if (!openPlayListing(url)) loadInto(activeWeb(), url) }
        }

        /**
         * The icon of the app a home tile stands for (Via), as a data: URL,
         * or "" while it is being fetched - the row is redrawn when it lands.
         */
        @JavascriptInterface
        fun getAppIcon(appId: String): String {
            if (!privileged) return ""
            return AppIcons.cached(this@MainActivity, appId) ?: run {
                AppIcons.fetch(this@MainActivity, appId) { runOnUiThread { refreshHomeTiles() } }
                ""
            }
        }
        @JavascriptInterface
        fun focusSearch() {
            if (!privileged) return
            runOnUiThread { enterSearchMode() }
        }
        @JavascriptInterface
        fun homeFieldVisible(visible: Boolean) {
            if (!privileged) return
            runOnUiThread { applyHomeCompact(!visible, animate = true) }
        }
        @JavascriptInterface
        fun shareUrl(url: String, title: String) {
            if (!privileged) return
            runOnUiThread { shareLink(url, title) }
        }
        @JavascriptInterface
        fun startVoice() {
            if (!privileged) return
            runOnUiThread { launchVoiceSearch() }
        }
        @JavascriptInterface
        fun startScan() {
            // A site being able to open the camera scanner on its own is
            // exactly the kind of thing this gate is for.
            if (!privileged) return
            runOnUiThread { launchScan() }
        }
        // Not gated: the media detector runs on whatever site is playing,
        // which is the whole point of it.
        @JavascriptInterface
        fun mediaState(state: String, title: String, host: String) {
            runOnUiThread { onMediaState(state, title, host) }
        }

        /**
         * Only ever reached from the warning page, which is an asset page, so
         * the privilege gate covers it: a site cannot talk the browser into
         * dropping to http by calling this itself.
         */
        @JavascriptInterface
        fun continueInsecure() {
            if (!privileged) return
            runOnUiThread {
                val target = pendingInsecure ?: return@runOnUiThread
                pendingInsecure = null
                try {
                    android.net.Uri.parse(target).host
                        ?.let { httpAllowed.add(it) }
                } catch (e: Exception) { /* unparseable; load it anyway */ }
                loadInto(activeWeb(), target)
            }
        }

        @JavascriptInterface
        fun retry() {
            if (!privileged) return
            runOnUiThread {
                val target = tabs.activeTab?.failedUrl ?: lastFailedUrl
                if (target != null) loadInto(activeWeb(), target)
                else activeWeb()?.reload()
            }
        }
        @JavascriptInterface
        fun getCachedFavicon(domain: String): String {
            if (!privileged) return ""
            if (domain.isBlank() || domain == "__order") return ""
            // The crisp icon (SiteIcons: the site's own large icon, found
            // when it was last open) first.
            SiteIcons.tileDataUrl(this@MainActivity, domain)?.let { return it }
            // Else the favicon WebView captured, but only a sharp one: a 16
            // or 32px favicon is what made the tiles blurry. Without a sharp
            // icon a built-in tile fetches its own (home.html), and a site in
            // history gets its large icon from the site, redrawing the row.
            val captured = getSharedPreferences("favicon_cache", Context.MODE_PRIVATE)
                .getString(domain, "") ?: ""
            if (captured.isNotEmpty() && capturedIsSharp(domain, captured)) return captured
            if (!nightOwl) SiteIcons.prefetchForTile(this@MainActivity, domain) {
                if (!isFinishing && !isDestroyed && activeWeb()?.url == homePage) refreshHomeTiles()
            }
            return ""
        }

        /** home.html, last resort for a site in history with no sharp icon: the small captured favicon. */
        @JavascriptInterface
        fun getSmallFavicon(domain: String): String {
            if (!privileged) return ""
            if (domain.isBlank() || domain == "__order") return ""
            return getSharedPreferences("favicon_cache", Context.MODE_PRIVATE)
                .getString(domain, "") ?: ""
        }

        /**
         * Reached only for a blob: image the user just chose to save. A blob URL
         * is a handle into the page's own memory - nothing outside the renderer
         * can read it, which is why DownloadManager and HttpURLConnection both
         * refuse one. So the page hands the bytes back as a data: URL and the
         * ordinary save path takes over.
         */
        @JavascriptInterface
        fun saveBlobImage(token: String?, dataUrl: String?) {
            if (token == null || token != blobSaveToken) return
            blobSaveToken = null
            if (dataUrl == null || !dataUrl.startsWith("data:")) { imageSaveFailed(); return }
            Thread { saveDataImage(dataUrl) }.start()
        }

        @JavascriptInterface
        fun saveBlobFailed(token: String?) {
            if (token == null || token != blobSaveToken) return
            blobSaveToken = null
            imageSaveFailed()
        }

        // A blob: download, streamed in pieces (requestBlobDownload). These
        // run on WebView's bridge thread, never the main one, and the page
        // waits for each to return before it reads the next piece - so the
        // file is written as fast as the disk takes it and no faster, and
        // only one piece is ever held in memory.

        /** The page has the file and its size (-1 unknown); false stops it. */
        @JavascriptInterface
        fun blobFileBegin(token: String?, length: String?, type: String?): Boolean {
            val save = token?.let { blobSaves[it] } ?: return false
            if (save.sink != null) return false
            val total = length?.toLongOrNull()?.takeIf { it > 0 } ?: -1L
            val free = freeDownloadSpace()
            if (downloadRoom(total, free) == ROOM_NONE) {
                blobSaves.remove(token)
                runOnUiThread { if (!isFinishing && !isDestroyed) showNoRoomForDownload(save.name, total, free) }
                return false
            }
            val declared = save.mime?.takeIf { !isGenericMime(it) }
                ?: type?.substringBefore(';')?.trim()?.takeIf { !isGenericMime(it) }
            val mime = downloadMime(save.name, declared) ?: "application/octet-stream"
            val sink = DownloadSink.open(this@MainActivity, save.name, mime)
            if (sink == null) { blobSaves.remove(token); downloadFailed(); return false }
            save.total = total
            save.progress = SaveProgress(this@MainActivity, sink.name)
            save.sink = sink
            return true
        }

        /** One piece of the file, base64; false stops the page sending more. */
        @JavascriptInterface
        fun blobFileChunk(token: String?, b64: String?): Boolean {
            val save = token?.let { blobSaves[it] } ?: return false
            val sink = save.sink ?: return false
            return try {
                val bytes = android.util.Base64.decode(b64 ?: "", android.util.Base64.DEFAULT)
                sink.write(bytes)
                // A file of unknown size can still run the phone out of
                // room: looked at again every 64MB, and stopped while there is
                // still space for Android to keep running.
                if (sink.written - save.checkedAt >= 64L * 1024 * 1024) {
                    save.checkedAt = sink.written
                    val free = freeDownloadSpace()
                    if (free in 0 until 200L * 1024 * 1024) throw java.io.IOException("storage full")
                }
                save.progress?.update(sink.written, save.total)
                true
            } catch (t: Throwable) {
                // Out of memory on a modest phone, or the disk refusing: a
                // clear failure and the half file removed, never the app dying.
                blobSaves.remove(token)
                sink.abort()
                save.progress?.done(false)
                downloadFailed()
                false
            }
        }

        /** The last piece has arrived. */
        @JavascriptInterface
        fun blobFileEnd(token: String?) {
            val save = token?.let { blobSaves.remove(it) } ?: return
            val sink = save.sink ?: return
            val uri = if (sink.written > 0) sink.finish() else { sink.abort(); null }
            if (uri == null) { save.progress?.done(false); downloadFailed(); return }
            SavedFiles.add(this@MainActivity, sink.name, uri, sink.mime, sink.written)
            save.progress?.done(true)
            runOnUiThread {
                HealthStats.count(this@MainActivity, HealthStats.DOWNLOADS)
                toast("Saved to Downloads: " + sink.name)
                if (downloadsOpen) refreshDownloads()
            }
        }

        @JavascriptInterface
        fun blobFileFailed(token: String?) {
            val save = token?.let { blobSaves.remove(it) } ?: return
            save.sink?.let { it.abort(); save.progress?.done(false) }
            downloadFailed()
        }

        /**
         * The page last read in another tab, for home's "Jump back in" card:
         * {id, url, title, site, domain}, or {} when there is none. A copy the
         * main thread keeps (resumeJson), since this runs on WebView's bridge
         * thread and the tab list belongs to the main one.
         */
        @JavascriptInterface
        fun getResume(): String {
            if (!privileged) return "{}"
            return resumeJson
        }

        /** Back to that tab, or to its page if the tab has gone since. */
        @JavascriptInterface
        fun resumeTab(id: String, url: String) {
            if (!privileged) return
            runOnUiThread {
                val tab = id.toLongOrNull()?.let { want -> tabs.tabs.firstOrNull { it.id == want } }
                Haptics.tap(binding.webContainer)
                if (tab != null && !tab.isPrivate) openTab(tab)
                else if (url.startsWith("https://") || url.startsWith("http://")) loadInto(activeWeb(), url)
            }
        }

        /** Home's "blocked this week" line: {on, week, saved} (saved as "38 MB"). */
        @JavascriptInterface
        fun getPrivacyStats(): String {
            if (!privileged) return "{}"
            val on = AdBlocker.isEnabled(this@MainActivity) && !nightOwl
            val week = PrivacyStats.week(this@MainActivity)
            return JSONObject()
                .put("on", on)
                .put("week", week)
                .put("saved", android.text.format.Formatter.formatShortFileSize(
                    this@MainActivity, PrivacyStats.bytesSaved(week)))
                .toString()
        }

        @JavascriptInterface
        fun getRecentSites(): String {
            // The browsing history. This is the call the gate exists for.
            if (!privileged) return "[]"
            // The most used sites first (SiteVisits, frecency), then, while
            // there are fewer than eight of those - a new install, or after
            // an update from a version that did not count visits - the most
            // recent ones from history fill the row.
            val seen = LinkedHashSet<String>()
            val out0 = JSONArray()
            try {
                SiteVisits.top(this@MainActivity, 8).forEach { (domain, url) ->
                    if (seen.add(domain)) out0.put(JSONObject().put("domain", domain).put("url", url))
                }
            } catch (e: Exception) { /* fall back to history alone */ }
            val entries = History.load(this@MainActivity)
            // Built with the JSON library rather than by hand. The hand-rolled
            // version escaped the url but not the domain, and neither against
            // control characters, so one odd address in the history produced
            // JSON the home page could not parse - and the tiles silently came
            // back empty with nothing to say why.
            val out = out0
            for (e in entries) {
                if (out.length() >= 8) break
                val host = try {
                    android.net.Uri.parse(e.url).host ?: continue
                } catch (ex: Exception) { continue }
                // Mobile hosts stripped too, or labelFromDomain takes the first
                // label of m.youtube.com and titles the tile "M" - and the dedup
                // below counts m.youtube.com and youtube.com as two sites.
                val domain = host.removePrefix("www.")
                    .removePrefix("mobile.").removePrefix("m.")
                if (domain.isBlank() || !seen.add(domain)) continue
                out.put(JSONObject().put("domain", domain).put("url", e.url))
            }
            return out.toString()
        }

        @JavascriptInterface
        fun getShortcuts(): String {
            // "{}" when unprivileged, and the page treats that as "no answer"
            // rather than "no shortcuts" - the difference matters, or a failed
            // read would look like the user had deleted everything.
            if (!privileged) return "{}"
            val custom = JSONArray()
            Shortcuts.loadCustom(this@MainActivity).forEach {
                custom.put(JSONObject().put("label", it.label).put("url", it.url))
            }
            val hidden = JSONArray()
            Shortcuts.loadHidden(this@MainActivity).forEach { hidden.put(it) }
            return JSONObject().put("custom", custom).put("hidden", hidden).toString()
        }

        @JavascriptInterface
        fun addShortcut() {
            if (!privileged) return
            runOnUiThread { showAddShortcutDialog() }
        }

        @JavascriptInterface
        fun removeShortcut(label: String, url: String) {
            if (!privileged) return
            runOnUiThread { showRemoveShortcutDialog(label, url) }
        }

        @JavascriptInterface
        fun getConfig(): String {
            // Carries nightOwl, so without the gate a site could ask whether
            // the user believes they are browsing privately.
            if (!privileged) return "{}"
            val bg = Settings.getHomeBackground(this@MainActivity)
            val accent = Settings.getHomeAccent(this@MainActivity)
            val tiles = Settings.getBool(this@MainActivity, Settings.HOME_SHOW_TILES, true)
            val mode = browsingMode
            val zen = zenShortcuts
            return "{\"background\":\"$bg\",\"accent\":\"$accent\",\"tiles\":$tiles,\"nightOwl\":$nightOwl," +
                "\"mode\":\"$mode\",\"zenTiles\":$zen}"
        }
        /** home.html: the stories and publishers the reader hid (FeedHidden). */
        @JavascriptInterface
        fun getFeedHidden(): String {
            if (!privileged) return "{}"
            return try { FeedHidden.json(this@MainActivity) } catch (e: Exception) { "{}" }
        }

        /** home.html: a story card's menu (its three dots). */
        @JavascriptInterface
        fun storyMenu(link: String, title: String, source: String, domain: String) {
            if (!privileged) return
            if (!(link.startsWith("https://") || link.startsWith("http://"))) return
            runOnUiThread { showStoryMenu(link, title, source, domain) }
        }

        /** home.html: where the feed ad's place is now, in CSS px; top -1 for none. */
        @JavascriptInterface
        fun adSlotAt(top: Int, left: Int, width: Int, height: Int) {
            if (!privileged) return
            val from = this
            runOnUiThread { onAdSlotReported(from, top, left, width, height) }
        }

        @JavascriptInterface
        fun getFeed(requestId: Int) {
            if (!privileged) return
            Thread {
                // Nothing the feed does may take the app down. This thread has
                // no handler above it, so an error escaping here - one bad
                // pattern in NewsFeed failed its initialisation on Android -
                // ended the process on every launch, as soon as home asked for
                // stories. The page shows its empty state instead.
                val json = try {
                    // The saved stories at once; newer ones, when a refresh
                    // behind them finds some, follow (pushFeedUpdate).
                    NewsFeed.fetch(this@MainActivity) { fresh -> runOnUiThread { pushFeedUpdate(fresh) } }
                } catch (t: Throwable) {
                    DebugLog.add { "feed  failed: " + t.javaClass.simpleName + ": " + t.message }
                    "[]"
                }
                runOnUiThread { pushFeed(requestId, json) }
            }.start()
        }
    }
    private fun pushFeed(requestId: Int, json: String) {
        val web = activeWeb() ?: return
        val js = "window.__onFeed && window.__onFeed(" + requestId + ", JSON.parse(" +
            org.json.JSONObject.quote(json) + "));"
        web.evaluateJavascript(js, null)
    }

    /**
     * Newer stories than the ones home is showing, from a refresh that ran
     * behind them. Home takes them in when the reader is not in the middle of
     * the stories, and otherwise when they are next back at the top.
     */
    private fun pushFeedUpdate(json: String) {
        if (isFinishing || isDestroyed || nightOwl) return
        if (browsingMode != BrowsingMode.EXPLORE) return
        val web = activeWeb() ?: return
        if (web.url != homePage) return
        web.evaluateJavascript("window.__onFeedUpdate && window.__onFeedUpdate(JSON.parse(" +
            org.json.JSONObject.quote(json) + "));", null)
    }

    private fun localSuggestionMatches(query: String, limit: Int): List<Triple<String, String, String>> {
        val marks = Bookmarks.load(this).filter { matchesQuery(it.title, it.url, query) }
            .map { Triple("bookmark", it.title, it.url) }
        val hist = History.load(this).filter { matchesQuery(it.title, it.url, query) }
            .map { Triple("history", it.title, it.url) }
        return (marks + hist).distinctBy { it.third }.take(limit)
    }

    /**
     * Prefix-first. The old test was contains() anywhere in the title or the
     * whole URL, so a single letter matched most of history and "ube" matched
     * youtube.com - the rows never looked like they were responding to typing.
     * Now the domain has to start with what was typed, or some word in the
     * title does, so a row appears as the user closes in on it.
     */
    private fun matchesQuery(title: String, url: String, query: String): Boolean {
        if (query.isEmpty()) return true
        val q = query.lowercase()
        val host = try {
            (android.net.Uri.parse(url).host ?: "").lowercase()
                .removePrefix("www.").removePrefix("mobile.").removePrefix("m.")
        } catch (e: Exception) { "" }
        if (host.startsWith(q)) return true
        return title.lowercase().split(' ', '-', '|', ':', '/').any { it.startsWith(q) }
    }

    private fun fetchWebSuggestions(query: String): List<String> {
        // Night Owl (private): never send keystrokes to a search engine's
        // autocomplete endpoint. Local history/bookmark matches still work
        // because they never leave the device.
        if (nightOwl) return emptyList()
        if (query.isEmpty()) return emptyList()
        // Settings > Search Security > Search suggestions off: what is typed
        // stays on the phone until it is searched for.
        if (!Settings.getBool(this, Settings.SEARCH_SUGGESTIONS, true)) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val endpoint = when (Settings.getEngineName(this)) {
            "DuckDuckGo" -> "https://duckduckgo.com/ac/?type=list&q=$q"
            "Bing" -> "https://api.bing.com/osjson.aspx?query=$q"
            else -> "https://www.google.com/complete/search?client=chrome&q=$q"
        }
        return try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val cleaned = body.trim().removePrefix(")]}'").trim()
            val list = JSONArray(cleaned).optJSONArray(1) ?: return emptyList()
            (0 until list.length()).mapNotNull { i -> list.optString(i, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * The site a row came from - "Youtube", "Deezer" - for the second line of a
     * history or bookmark suggestion. It used to be the address with the scheme
     * filed off, which on any real page meant a wall of query string.
     */
    private fun siteNameOf(url: String): String = try {
        val host = (android.net.Uri.parse(url).host ?: "").lowercase()
            .removePrefix("www.").removePrefix("mobile.").removePrefix("m.")
        val name = host.substringBefore('.')
        if (name.isBlank()) host else name.replaceFirstChar { it.uppercase() }
    } catch (e: Exception) {
        ""
    }

    private fun suggestSub(url: String): String = siteNameOf(url)

    private var lastSuggestScroll = 0L

    /**
     * True while the list is still settling. A tap that halts a fling would
     * otherwise also open whatever row happened to be under the thumb, which is
     * the one mistake in this list the user cannot undo.
     */
    private fun suggestListMoving(): Boolean =
        android.os.SystemClock.uptimeMillis() - lastSuggestScroll < 250L

    // Recognises a stored results page, so a past search can be offered as the
    // query that was typed rather than as the engine's URL - on every engine
    // in Settings, not only the ones whose query is called "q" (PageLabel).
    private fun searchQueryOf(url: String): String? = PageLabel.searchOf(url)?.first

    private fun buildSuggestions(query: String): List<JSONObject> {
        // On focus, offer recents. While typing, hold local matches back until
        // the query is specific enough to be worth pinning above the web
        // suggestions: on one or two letters they are noise at the top.
        val local = when {
            query.isEmpty() -> localSuggestionMatches(query, 5)
            query.trim().length >= 3 -> localSuggestionMatches(query, 3)
            else -> emptyList()
        }
        val web = if (query.isEmpty()) emptyList() else fetchWebSuggestions(query)
        val seen = mutableSetOf<String>()
        val out = mutableListOf<JSONObject>()
        local.forEach { (kind, title, url) ->
            val q = searchQueryOf(url)
            // A page saved under its address gets a name made from it.
            val shown = q ?: PageLabel.title(title, url)
            if (!seen.add(shown.lowercase())) return@forEach
            val o = JSONObject().put("kind", kind).put("title", shown).put("url", url)
            // A past search reads as a query: no page title, no results URL.
            if (q != null) o.put("sub", "").put("fill", q)
            else o.put("sub", suggestSub(url))
            out += o
        }
        web.forEach { text ->
            if (out.size < 7 && seen.add(text.lowercase())) {
                out += JSONObject().put("kind", "web").put("title", text)
            }
        }
        return out
    }

    // ---------- WebView creation / lifecycle ----------

    /**
     * Settings > Customize > Dark websites: in the dark theme, a site with no
     * dark look of its own is darkened by WebView itself, as Chrome's "Dark
     * mode for web contents" does. Sites that have a dark look use it either
     * way. Android 13 and up (WebView darkens only for an app in a dark theme,
     * and the older switch does nothing for an app targeting Android 13+).
     */
    private fun applyDarkWeb(s: android.webkit.WebSettings) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            s.setAlgorithmicDarkeningAllowed(Settings.getBool(this, Settings.DARK_WEB, false))
        } catch (e: Throwable) { /* an older WebView without the switch */ }
    }

    /**
     * Settings > Accessibility > Force zoom: a page that turns pinch-zoom off
     * in its viewport (user-scalable=no, maximum-scale=1) can be zoomed all
     * the same, as Chrome's "Force enable zoom" allows.
     */
    private val FORCE_ZOOM_JS = "(function(){var v=document.querySelector('meta[name=viewport]');" +
        "if(!v)return;var c=v.getAttribute('content')||'';" +
        "var n=c.replace(/\\s*,?\\s*(user-scalable|maximum-scale|minimum-scale)\\s*=\\s*[^,]*/gi,'').replace(/^\\s*,/,'');" +
        "v.setAttribute('content',(n?n+',':'')+'user-scalable=yes,maximum-scale=10');})()"

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun newWebView(): WebView {
        val web = BrowserWebView(this)
        web.blockAutoplay = Settings.getBool(this, Settings.SITE_BLOCK_AUTOPLAY, true)
        // The page never sits under a system bar - the root keeps them as
        // padding - but the window's insets reached the WebView anyway, and
        // recent WebViews hand them to pages as env(safe-area-inset-*). Sites
        // that honour it (YouTube's bottom bar) then left a band the height of
        // the navigation bar empty above it. Chrome gives a page no such inset
        // when nothing covers it, and neither does this.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(web) { _, _ ->
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        // Letting go of the page: a pull at the top either refreshes or springs back.
        web.onTouchEnd = { if (web === activeWeb()) releasePull() }
        web.onTopOverscroll = { px -> if (web === activeWeb()) pullBy(px) }
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        web.settings.apply {
            javaScriptEnabled = Settings.getBool(this@MainActivity, Settings.SITE_JAVASCRIPT, true)
            domStorageEnabled = true
            // Applied here, where the private tab is actually built.
            // enterNightOwl used to set this on the tab that was open at the
            // time and then immediately create a new one - so the tab the user
            // browsed privately in was the one that never got it.
            if (nightOwl) cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            javaScriptCanOpenWindowsAutomatically = true
            // Mixed content as browsers handle it: an http picture, video or
            // sound on an https page is fetched (Chromium upgrades it to https
            // where it can), while http scripts and frames stay blocked.
            // WebView's own default refused all of it, and many video sites
            // serve their streams from plain http hosts - the player came up
            // and never played.
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // Data saver: block images when enabled.
            blockNetworkImage = Settings.getBool(this@MainActivity, Settings.SITE_BLOCK_IMAGES, false)

            // Present as clean mobile Chrome: drop the "; wv" WebView token AND
            // the legacy "Version/4.0" token. Strict sites (e.g. Notion) read
            // "Version/4.0" as an ancient browser and block it, so removing it
            // lets those sites render normally.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace("Version/4.0 ", "")

            // --- Desktop mode ---
            if (Settings.getBool(this@MainActivity, Settings.DESKTOP_MODE, false)) {
                userAgentString =
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
            }

            // --- Accessibility: text size ---
            textZoom = Settings.getTextScale(this@MainActivity)

            // --- Security toggles ---
            // Multiple windows stay ON whatever the pop-up setting says, because
            // window.open() is how every OAuth sign-in hands control to the
            // provider and gets it back. Turning it off makes window.open()
            // return null: the provider authenticates, calls back into an opener
            // that does not exist, and the flow dead-ends on a blank page.
            // The setting is enforced in onCreateWindow instead, where
            // isUserGesture tells a pop-up the user asked for apart from a site
            // spawning windows on its own.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)

            // Safe Browsing (WebView built-in), where supported.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = Settings.getBool(
                    this@MainActivity, Settings.SEC_SAFE_BROWSING, true)
            }
            applyDarkWeb(this)
        }
        // Let the system password manager (Google) offer saved logins in web
        // forms, so sign-ins autofill like Chrome.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }

        // Third-party cookie policy.
        val block3p = Settings.getBool(this, Settings.SEC_BLOCK_3P_COOKIES, false)
        android.webkit.CookieManager.getInstance()
            .setAcceptThirdPartyCookies(web, !block3p)

        // One bridge per WebView, kept on the view itself so the page
        // callbacks can tell it whether the document now loading is ours.
        val bridge = SearchAppBridge()
        web.tag = bridge
        web.addJavascriptInterface(bridge, "SearchApp")

        web.webViewClient = object : WebViewClient() {
            /**
             * A WebView can only load web schemes. Anything else - a mail link,
             * a phone number, a map pin, a Play listing, the custom scheme an
             * app registers for its own sign-in callback - fails inside it with
             * ERR_UNKNOWN_URL_SCHEME, which this browser then paints as "this
             * site can't be reached". The site was fine; the link was simply
             * never ours to load. Hand those to the system, as every other
             * browser does.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = (uri.scheme ?: "").lowercase()
                // Every main-frame request, server redirects included, which
                // onPageStarted never sees: a provider that signs a returning
                // user straight back with a 302 commits no page of its own.
                if (request.isForMainFrame) notePopupNavigation(view, uri.toString())
                // HTTPS-only: a tapped http link is upgraded here, which is
                // the half that was missing. Sub-resources are left alone -
                // this is about where the user is being taken.
                if (scheme == "http" && request.isForMainFrame && httpsOnly()) {
                    val target = uri.toString()
                    val host = uri.host ?: ""
                    if (!httpAllowed.contains(host)) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - (upgradedAt[host] ?: 0L) < 10_000L) {
                            // Upgraded moments ago and sent straight back to
                            // http: the site is redirecting, so https is not
                            // the answer here. Ask rather than loop.
                            showInsecureWarning(view, target)
                        } else {
                            upgradedAt[host] = now
                            val https = "https://" + target.substring("http://".length)
                            upgradedFrom[https] = target
                            // Not view.loadUrl() here - that was the crash.
                            loadAfterCallback(view, https)
                        }
                        return true
                    }
                }
                // A tapped link to an app's Play Store listing opens the Play
                // Store app on it, as Chrome does - that is where it can be
                // installed. Only for a tap: a page cannot send the user off
                // to the store by itself.
                if (scheme == "https" && request.isForMainFrame && request.hasGesture() &&
                    openPlayListing(uri.toString())) {
                    closeIfNeverLoaded(view)
                    return true
                }
                // Web content and our own asset pages: load here as normal.
                if (scheme == "http" || scheme == "https" || scheme == "file" ||
                    scheme == "about" || scheme == "data" || scheme == "blob"
                ) return false
                // A navigation must never be a way to run script.
                if (scheme == "javascript") return true
                // Only a top-level navigation may leave the app. Without this a
                // hidden iframe could throw the user into another app on its own.
                if (!request.isForMainFrame) return true
                // A link that opened a new tab only to hand itself to another
                // app - a target=_blank mailto:, a "share on WhatsApp" button,
                // an app's own sign-in scheme - would otherwise leave that tab
                // behind empty, which reads as a blank page that never loads.
                // Chrome closes such a tab, and so does this.
                DebugLog.add { "tab#" + tabOf(view)?.id + " hands " + scheme + ": link to another app" }
                if (!openExternal(uri.toString(), view)) closeIfNeverLoaded(view)
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                // Ad/tracker blocking (when enabled in settings), unless the
                // user has allowed ads on the site this page is on. The page's
                // own request names that site before anything it pulls in is
                // asked for, which onPageStarted, on the main thread, may not.
                val page = view as? BrowserWebView
                if (request?.isForMainFrame == true) {
                    request.url?.host?.let { page?.pageHost = it }
                }
                return AdBlocker.check(this@MainActivity, request, page?.pageHost)
                    ?: super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: android.webkit.HttpAuthHandler?,
                host: String?,
                realm: String?
            ) {
                val h = handler ?: return
                askHttpAuth(h, host ?: "this site", realm ?: "")
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only replace the main-frame failure (not sub-resources like images/ads).
                if (request?.isForMainFrame == true) {
                    DebugLog.add { "tab#" + tabOf(view)?.id + " load error " +
                        error?.errorCode + " " + error?.description + " at " +
                        DebugLog.url(request.url?.toString()) }
                    endLoadWatch(tabOf(view))
                    // Wi-Fi to mobile data, or back, mid-load: Chromium drops the
                    // request with ERR_NETWORK_CHANGED. That is not the site
                    // failing, so it is tried once more on the new network
                    // before any error page is shown.
                    val failedNow = request.url?.toString()
                    val desc = error?.description?.toString() ?: ""
                    if (view != null && failedNow != null &&
                        desc.contains("ERR_NETWORK_CHANGED") && networkRetryUrl != failedNow
                    ) {
                        networkRetryUrl = failedNow
                        val w: WebView = view
                        uiHandler.postDelayed({
                            if (isLiveWeb(w) && hasNetwork()) loadInto(w, failedNow)
                        }, 700L)
                        return
                    }
                    // The real page, kept on the tab while an error page stands
                    // in for it.
                    tabOf(view)?.failedUrl = failedNow
                    HealthStats.count(this@MainActivity, HealthStats.PAGE_ERRORS)
                    // If this was our own https attempt, the site may simply
                    // not offer https. Say that, rather than reporting it as
                    // unreachable - which was both wrong and a dead end.
                    val original = request.url?.toString()?.let { upgradedFrom.remove(it) }
                    if (original != null) {
                        showInsecureWarning(view, original)
                        return
                    }
                    lastFailedUrl = request.url?.toString()
                    if (hasNetwork()) {
                        // Online but the site failed (bad address, host down, refused):
                        // show the "can't reach site" page with the failed URL.
                        val enc = try {
                            java.net.URLEncoder.encode(lastFailedUrl ?: "", "UTF-8")
                        } catch (e: Exception) { "" }
                        loadAfterCallback(view, "file:///android_asset/error.html?u=$enc")
                    } else {
                        // Genuinely no connectivity: show the offline page.
                        loadAfterCallback(view, "file:///android_asset/offline.html")
                    }
                }
            }

            /**
             * The page renderer has died - crashed, or killed by Android to
             * free memory, which on a low-RAM phone is routine once the app
             * is in the background.
             *
             * Not overriding this means returning false, and false tells
             * WebView to take the whole app down with it: a renderer crash
             * becomes a crash of this app (SIGTRAP in libwebviewchromium.so,
             * no Java frame from here in the stack), and a renderer killed for
             * memory kills this app too. Returning true keeps the app alive;
             * the price is that this WebView can never be used again, so it
             * is thrown away and the tab reloads in a new one.
             *
             * Called once per WebView the renderer was serving, which is
             * usually every tab at once.
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                DebugLog.add { "renderer gone for tab#" + tabOf(view)?.id +
                    " crashed=" + (detail?.didCrash() ?: "?") }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastRendererLoss > 2000L) {
                    lastRendererLoss = now
                    HealthStats.count(this@MainActivity,
                        if (detail?.didCrash() == true) HealthStats.RENDERER_CRASH
                        else HealthStats.RENDERER_KILLED)
                }
                if (view != null) replaceDeadWebView(view)
                return true
            }

            // The page has started to arrive: from here it is loading content,
            // not waiting on the server.
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (view != null && view === activeWeb()) schedulePageTint(150L)
                super.onPageCommitVisible(view, url)
                commitLoadWatch(tabOf(view))
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    DebugLog.add { "tab#" + tabOf(view)?.id + " HTTP " +
                        errorResponse?.statusCode + " at " + DebugLog.url(request.url?.toString()) }
                    if ((errorResponse?.statusCode ?: 0) >= 400) {
                        HealthStats.count(this@MainActivity, HealthStats.HTTP_ERRORS)
                    }
                }
            }

            // Every history change, including the ones a single-page site makes
            // with pushState and that never start a page load - so Back and
            // Forward light up exactly when there is somewhere to go.
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // An app-style site changing its route with pushState, or a
                // #fragment, gets no onPageStarted, so the tab kept the address
                // of the first page it loaded on that site - wrong in the bar,
                // and wrong for everything that decides by the tab's address.
                val t = tabOf(view)
                if (url != null) {
                    (view as? BrowserWebView)?.pageHost =
                        try { android.net.Uri.parse(url).host } catch (e: Exception) { null }
                }
                if (t != null && url != null && url != t.url) {
                    t.url = url
                    if (view === activeWeb()) {
                        if (!binding.urlBar.hasFocus()) binding.urlBar.setText(displayUrl(url))
                        refreshStar()
                        refreshAdSlot()
                    }
                }
                if (view != null && view === activeWeb()) refreshNav()
                scheduleSessionSave()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // First, and before this document's script runs: decide whether
                // it may reach the private half of the bridge. Only pages
                // shipped inside the app may.
                (view?.tag as? SearchAppBridge)?.privileged =
                    url != null && url.startsWith("file:///android_asset/")
                (view as? BrowserWebView)?.notePageSite(url)
                // The site's own zoom (Menu > Zoom), or the size from Settings.
                view?.settings?.let { s -> zoomFor(url).let { z -> if (s.textZoom != z) s.textZoom = z } }
                if (searchMode && url != null && url != homePage && view != null &&
                    view === activeWeb()) exitSearchMode()
                super.onPageStarted(view, url, favicon)
                if (view == tabs.activeTab?.webView) {
                    // A new page arrives with the bars, as in Chrome.
                    showBars()
                    // Plain bars for home and pages that are not to be tinted;
                    // a site's colours arrive once it has painted.
                    if (!tintAllowed(url)) setBarTint(null, null, animate = true)
                    if (!binding.urlBar.hasFocus()) binding.urlBar.setText(displayUrl(url))
                    refreshOmniboxVisibility(url)
                    tabs.activeTab?.let { beginLoadWatch(it, url) }
                }
                // The tab this page is loading in, which is not necessarily
                // the one on screen. This used to write to the active tab
                // whatever the source: while a sign-in pop-up was showing, the
                // page behind it moving on after the sign-in landed in the
                // pop-up's tab, and the tab that really changed kept its old
                // address - the one it would be rebuilt from if its WebView
                // were frozen or lost.
                url?.let { u -> tabOf(view)?.url = u }
                // The site the ad blocker judges this page's requests by.
                (view as? BrowserWebView)?.pageHost =
                    try { android.net.Uri.parse(url ?: "").host } catch (e: Exception) { null }
                tabOf(view)?.let { t ->
                    t.loadsStarted++
                    t.loadToken++
                    notePopupNavigation(view, url)
                    DebugLog.add { "tab#" + t.id + " start " + DebugLog.url(url) }
                }
                refreshNav()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view != null && view === activeWeb()) {
                    schedulePageTint(250L)
                    uiHandler.removeCallbacks(tintLate)
                    uiHandler.postDelayed(tintLate, 1600L)
                    // The page's own icon list exists now.
                    showSiteIcon(tabs.activeTab?.url ?: url)
                }
                // The upgrade worked, so nothing needs remembering about it.
                url?.let { upgradedFrom.remove(it) }
                // This is also reported for a load that never committed - one
                // superseded or cancelled before it arrived - with that load's
                // address. Taken at its word, a Home tap cancelled on a site
                // put home's address on the site's tab, turned the feed ad on
                // over the site, and handed the site home's bridge. What the
                // WebView shows is the page that is actually there.
                val shown = view?.url ?: url
                // Restated here as well, so a page that arrives by a route
                // which skips the start callback - a restored tab, say - still
                // ends up with the right answer.
                (view?.tag as? SearchAppBridge)?.privileged =
                    shown != null && shown.startsWith("file:///android_asset/")
                super.onPageFinished(view, url)
                // Media detection: report HTML5 playback to the app for the
                // media-control notification (skipped in Night Owl).
                if (!nightOwl) view?.evaluateJavascript(MediaDetect.js(), null)
                // Cosmetic ad-hiding: hide common ad containers when blocking is
                // on - and not on a site the user has allowed ads on.
                val shownHost = try { android.net.Uri.parse(shown ?: "").host } catch (e: Exception) { null }
                if (AdBlocker.isEnabled(this@MainActivity) && !AdBlocker.isOwnSite(shownHost) &&
                    !AdBlockSites.isAllowed(this@MainActivity, shownHost)
                ) {
                    view?.evaluateJavascript(AdBlocker.hideCss(), null)
                }
                if (Settings.getBool(this@MainActivity, Settings.A11Y_FORCE_ZOOM, false) &&
                    shownHost != null) view?.evaluateJavascript(FORCE_ZOOM_JS, null)
                // Desktop mode: force a desktop-width viewport so responsive
                // sites render their desktop layout.
                if (Settings.getBool(this@MainActivity, Settings.DESKTOP_MODE, false)) {
                    val js = "(function(){var v=document.querySelector('meta[name=viewport]');" +
                        "if(!v){v=document.createElement('meta');v.name='viewport';" +
                        "document.head.appendChild(v);}" +
                        "v.setAttribute('content','width=980');})();"
                    view?.evaluateJavascript(js, null)
                    val sw = resources.displayMetrics.widthPixels
                    val scale = (sw.toFloat() / 980f * 100f).toInt().coerceIn(20, 100)
                    view?.setInitialScale(scale)
                }
                // The owning tab, for the reason given in onPageStarted.
                tabOf(view)?.let { t ->
                    t.title = view?.title ?: t.title
                    t.url = shown ?: t.url
                    endLoadWatch(t)
                    // A real page made it, so there is no failure left to retry.
                    if (shown != null && !shown.startsWith("file:///android_asset/")) {
                        t.failedUrl = null
                        if (shown == networkRetryUrl) networkRetryUrl = null
                    }
                    // And how it loaded, measured from the page's own timing.
                    if (view != null && shown != null &&
                        (shown.startsWith("https://") || shown.startsWith("http://"))
                    ) {
                        HealthStats.count(this@MainActivity, HealthStats.PAGE_LOADS)
                        measurePage(view, t)
                    }
                    DebugLog.add { "tab#" + t.id + " finish " + DebugLog.url(url) }
                    if (t.openerId != null) watchReturnedPopup(t, url)
                    scheduleSessionSave()
                }
                // Record the visited page in history (never in Night Owl mode).
                if (url != null && !nightOwl) {
                    // Off the main thread: it reads, edits and rewrites the
                    // whole 200-entry list as JSON, on every page load.
                    val title = view?.title ?: ""
                    val ctx = applicationContext
                    historyIo.execute {
                        try {
                            History.add(ctx, title, url)
                            SiteVisits.add(ctx, url)
                        } catch (t: Throwable) { /* one visit unrecorded */ }
                    }
                } else if (url != null && nightOwl) {
                    // Noted so leaving can clear these hosts and only these.
                    // Held in memory only: a list of privately visited sites
                    // written to disk would be the very thing being avoided.
                    try {
                        android.net.Uri.parse(url).host
                            ?.takeIf { it.isNotBlank() }
                            ?.let { nightOwlHosts.add(it) }
                    } catch (e: Exception) { /* not an addressable page */ }
                }
                if (view == tabs.activeTab?.webView) {
                    refreshStar(); refreshOmniboxVisibility(shown)
                    pushAccentToPage(view)
                    // A website read: home's Jump back in offers it; home
                    // itself, loaded, shows the card.
                    if (shown == homePage) {
                        refreshResumeCard()
                        uiHandler.postDelayed({ maybeAnnounceModes() }, 1200L)
                    } else noteResumable()
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // HTML5 fullscreen: YouTube's expand button, and every other player,
            // calls the fullscreen API. The default implementation ignores it,
            // so without these two the button does nothing at all.
            override fun onShowCustomView(
                view: View?,
                callback: WebChromeClient.CustomViewCallback?
            ) {
                if (view == null) { callback?.onCustomViewHidden(); return }
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            /**
             * The picture a video shows before it plays when the page gave it
             * none: a transparent pixel, as in Chrome, instead of WebView's
             * own grey box with a play button.
             *
             * More than looks. WebView hands this picture to the page through
             * a 1 KB pipe, with one of its loading threads waiting to read it
             * and a low-priority thread writing it - and the grey picture is a
             * few KB, so each writer has to wait for its reader to make room.
             * A page with many videos and no posters asks for it many times at
             * once (figma.com's front page has eight Vimeo players, and its
             * debug log shows each one asking), and those readers and writers
             * waiting on each other can hold every thread WebView loads pages
             * with. Then nothing loads in any tab, not even this app's own
             * pages, and ending the page does not help, because the threads
             * belong to the app: on Figma, after tapping Log in, only closing
             * and reopening the app did. A single pixel is written in one go,
             * so neither side ever waits on the other.
             */
            override fun getDefaultVideoPoster(): Bitmap? =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                val cb = callback ?: return
                val site = origin ?: ""
                runOnUiThread {
                    // Master switch off: refuse without troubling the user.
                    if (!Settings.getBool(this@MainActivity, Settings.SITE_LOCATION, true)) {
                        cb.invoke(site, false, false)
                        return@runOnUiThread
                    }
                    askSitePermission(
                        site, SitePermissions.LOCATION,
                        "This site wants to know where you are.",
                        onAllow = {
                            withAndroidPermission(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                ),
                                needsAll = false,
                                onGranted = { cb.invoke(site, true, false) },
                                onDenied = { cb.invoke(site, false, false) }
                            )
                        },
                        onDeny = { cb.invoke(site, false, false) }
                    )
                }
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                val req = request ?: return
                runOnUiThread {
                    val wanted = req.resources
                    val needCam = wanted.contains(
                        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    val needMic = wanted.contains(
                        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (!needCam && !needMic) {
                        // Neither camera nor microphone. In practice this is the
                        // protected-media id a DRM video player asks for, which
                        // is not what this gate is about - refusing it would
                        // break paid video for no privacy gain here.
                        req.grant(wanted)
                        return@runOnUiThread
                    }
                    if (!Settings.getBool(this@MainActivity, Settings.SITE_CAMERA_MIC, true)) {
                        req.deny()
                        return@runOnUiThread
                    }
                    val what = when {
                        needCam && needMic -> "camera and microphone"
                        needCam -> "camera"
                        else -> "microphone"
                    }
                    val perms = ArrayList<String>()
                    if (needCam) perms.add(android.Manifest.permission.CAMERA)
                    if (needMic) perms.add(android.Manifest.permission.RECORD_AUDIO)
                    askSitePermission(
                        req.origin?.toString() ?: "", SitePermissions.CAMERA_MIC,
                        "This site wants to use your " + what + ".",
                        onAllow = {
                            withAndroidPermission(
                                perms.toTypedArray(),
                                needsAll = true,
                                onGranted = { req.grant(wanted) },
                                onDenied = { req.deny() }
                            )
                        },
                        onDeny = { req.deny() }
                    )
                }
            }

            /**
             * Keeps each site's own favicon, so the home tiles can be drawn
             * without asking anyone who the user has been visiting.
             *
             * Never in Night Owl: a private visit leaving an icon behind on
             * disk is a record of that visit.
             */
            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                if (nightOwl) return
                val src = icon ?: return
                val host = try {
                    android.net.Uri.parse(view?.url ?: return).host
                } catch (e: Exception) { null } ?: return
                // Copied here and now: the WebView owns the bitmap it handed
                // over and is free to recycle it the moment this returns.
                // Kept at the size the site sent, up to 256.
                //
                // This was a hard 64x64, which was already too small when the
                // tiles were 62px circles and is plainly blurry now: a 44dp
                // tile is about 154 real pixels on a high-density phone, and a
                // 64px source stretched over that is soft no matter what else
                // is done to it.
                //
                // Never scaled UP, only down. Enlarging a 32px favicon to 256
                // adds no detail, just bytes.
                val copy = try {
                    val w = src.width.coerceIn(1, 256)
                    val h = src.height.coerceIn(1, 256)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(b).drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), null)
                    b
                } catch (e: Exception) { return }
                Thread { storeFavicon(host, copy) }.start()
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view == tabs.activeTab?.webView) {
                    // The owl watches the ring while the page comes in.
                    binding.navOwl.setLoad(newProgress)
                    if (newProgress >= 100) endPullRefresh()
                    // The ring around the New tab button, not a line over the page.
                    binding.loadRing.setProgress(newProgress)
                    if (newProgress >= 100) endLoadWatch(tabs.activeTab)
                }
            }
            // Popups / window.open (e.g. "Sign in with Google" flows). Create a
            // real child WebView wired through the transport so the opener keeps
            // its handle to the popup (needed for OAuth callbacks), and host it
            // as a new tab.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (resultMsg == null) return false
                // Where "block pop-ups" is actually enforced. A pop-up that
                // follows a tap carries a gesture and is let through - every
                // sign-in pop-up is one. A site opening windows by itself does
                // not, and is refused, so ad pop-ups stay blocked.
                if (!isUserGesture && Settings.getBool(
                        this@MainActivity, Settings.SEC_BLOCK_POPUPS, true)) {
                    DebugLog.add { "pop-up refused: no user gesture, from tab#" + tabOf(view)?.id }
                    return false
                }
                // Validate the transport BEFORE creating any tab, so a malformed
                // window.open() can't leave an orphan about:blank tab in the list.
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                // The tab whose page called window.open(), which is usually but
                // not necessarily the one on screen.
                val opener = tabOf(view) ?: tabs.activeTab
                val popupTab = tabs.createTab("about:blank")
                popupTab.openerId = opener?.id
                popupTab.openerSite = UrlHelper.siteOf(opener?.url)
                popupTab.openerLoadsAtOpen = opener?.loadsStarted ?: 0
                popupTab.title = "Opening\u2026"
                DebugLog.add { "pop-up tab#" + popupTab.id + " opened by tab#" + opener?.id +
                    " on " + popupTab.openerSite + " (dialog=" + isDialog + ")" }
                val popupWeb = newWebView()
                popupTab.webView = popupWeb
                transport.webView = popupWeb
                resultMsg.sendToTarget()
                openTab(popupTab)
                return true
            }
            // When a pop-up finishes (window.close), fall back to the tab that
            // opened it. closeTabFromDeck resolves the opener and re-attaches it.
            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                // After the callback, not inside it: closing the tab destroys
                // this very WebView, and Chromium is still part-way through
                // closing it when it calls here.
                val closing = window ?: return
                DebugLog.add { "window.close() from tab#" + tabOf(closing)?.id }
                uiHandler.post {
                    val tab = tabs.tabs.firstOrNull { it.webView === closing } ?: return@post
                    // A sign-in pop-up that came back to the site and closed
                    // itself, as it should. The page behind it is then
                    // watched in case it never shows the result.
                    val opener = tabs.tabs.firstOrNull { it.id == tab.openerId && it !== tab }
                    val returned = returnedFromSignIn(tab)
                    closeTabFromDeck(tab)
                    if (returned && opener != null) watchOpenerAfterSignIn(opener)
                }
            }
            /**
             * The page's console, read for one message in particular.
             *
             * "Scripts may close only the windows that were opened by them" is
             * what Chromium logs when a page calls window.close() and is
             * refused. In a pop-up that is a sign-in callback trying to hand
             * back to the site and finding it is not allowed to - after
             * which it sits on its blank page for good. The page asked to be
             * closed, so it is closed, and the site behind it picks up the
             * sign-in. In debug builds warnings and errors are also kept for
             * the debug log.
             */
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                val m = message ?: return false
                val text = m.message() ?: ""
                val level = m.messageLevel()
                if (DebugLog.enabled && (level == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                        level == android.webkit.ConsoleMessage.MessageLevel.WARNING)
                ) {
                    DebugLog.add { "tab#" + tabOf(web)?.id + " console " + level + ": " +
                        text.take(300) + " (" + DebugLog.url(m.sourceId()) + ":" + m.lineNumber() + ")" }
                }
                if (text.startsWith("Scripts may close only the windows that were opened by")) {
                    uiHandler.post {
                        val tab = tabOf(web)
                        if (tab != null && tab.openerId != null) {
                            foldBackPopup(tab, "window.close() was refused")
                        }
                    }
                }
                return false
            }

            // File uploads: <input type="file"> on web pages.
            override fun onShowFileChooser(
                webView: WebView?,
                callback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                // Cancel any previous pending chooser.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent()
                    ?: android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    android.widget.Toast.makeText(this@MainActivity,
                        "Can't open file picker", android.widget.Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        // Handle file downloads via Android's DownloadManager.
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            // A download usually begins life as an ordinary navigation, so
            // onPageStarted has already written this address into the tab and
            // the address bar before the server's Content-Disposition turns it
            // into a download. The page itself never changes, so the tab is put
            // back where it was - otherwise a download address is left sitting
            // in the bar over the home page, belonging to nothing on screen.
            restoreAfterDownload(web)
            startDownload(url, userAgent, contentDisposition, mimeType, contentLength)
        }

        web.setFindListener { activeIndex, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                binding.findCount.text = if (numberOfMatches > 0)
                    "${activeIndex + 1}/$numberOfMatches" else "0/0"
            }
        }

        setupLongPress(web)

        // A page that stops answering - a script stuck in a loop, a site too
        // heavy for the phone - is offered the way out Chrome offers: wait, or
        // end it. Android 10 and up; earlier versions have no such signal.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            web.setWebViewRenderProcessClient(object : android.webkit.WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(
                    view: WebView,
                    renderer: android.webkit.WebViewRenderProcess?
                ) = onPageUnresponsive(view, renderer)

                override fun onRenderProcessResponsive(
                    view: WebView,
                    renderer: android.webkit.WebViewRenderProcess?
                ) = onPageResponsive(view)
            })
        }

        web.setOnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
            lastWebScroll = android.os.SystemClock.uptimeMillis()
            positionAdSlot(v as android.webkit.WebView, scrollY)
            onPageScrollForBars(v, scrollY, oldScrollY)
            // Back at the top, the header that set the bars' colour is in view
            // again: a header that changed colour since is followed.
            if (v === activeWeb() && scrollY == 0 && oldScrollY > 0) schedulePageTint()
        }
        return web
    }

    // ---------- Bars hide while scrolling ----------
    //
    // Scrolling down a page slides both bars away and gives the page the whole
    // screen; scrolling up, or reaching the top, brings them back, as Chrome's
    // toolbar does. On the home page and on websites alike.
    //
    // The bars move by translation, and the page is resized only twice: at the
    // start of a hide, when it grows to the whole screen while both bars still
    // cover its new edges, and at the end of a show, when it stops at their
    // edges again under bars already back in place. Neither uncovers page that
    // has not been painted yet, which is what showed as a white band. Each
    // time the page area is offset by exactly the top bar's height in the same
    // frame, so the text under the finger never jumps. Resizing on every frame
    // instead would make a heavy page relayout continuously, and letting the
    // page run under the bars all the time would hide what sites pin to their
    // bottom edge - the reason the bar stops the page at its straight edge
    // (applyBarOverlap).

    private var barsHidden = false
    // Read once per resume rather than on every scroll event (refreshScrollPrefs).
    private var hideBarsPref = true
    // Off unless chosen in Settings > Customize: the bars keep the app's own
    // surface on every site.
    private var tintBarsPref = false
    private var touchExploration = false

    private fun refreshScrollPrefs() {
        Haptics.refresh(this)
        // Vibration on tap off silences the system's own feedback on the bar
        // too (a tooltip's long press), not only the app's.
        if (::binding.isInitialized) {
            listOf(binding.navHome, binding.navBookmarks, binding.navNewTab, binding.navTabs,
                binding.navMenu, binding.settingsBtn, binding.starBtn, binding.menuBack,
                binding.menuForward, binding.menuRefresh
            ).forEach { it.isHapticFeedbackEnabled = Haptics.isOn }
        }
        hideBarsPref = Settings.getBool(this, Settings.HIDE_BARS_ON_SCROLL, true)
        tintBarsPref = Settings.getBool(this, Settings.TINT_BARS, false)
        touchExploration = (getSystemService(ACCESSIBILITY_SERVICE) as?
            android.view.accessibility.AccessibilityManager)?.isTouchExplorationEnabled == true
        if (!hideBarsPref || touchExploration) showBars(animate = false)
    }
    private var barScrollAcc = 0
    private var barsTopH = 0
    /** Whether the page runs under both bars (setPageUnderBars): while they are away or coming back. */
    private var pageUnderBars = false
    /** When the bars last started to move (uptime). */
    private var barsMovedAt = 0L
    private var pageTyping = false

    private fun barsMayHide(web: View): Boolean =
        web === activeWeb() && !searchMode && !findActive && !deckVisible &&
            binding.menuScrim.visibility != View.VISIBLE && fullscreenView == null &&
            pullRaw == 0f && !pullRefreshing && hideBarsPref &&
            // Not while typing into the page: the keyboard owns the bottom,
            // and the bars come straight back when it opens anyway.
            !pageTyping &&
            // With TalkBack the bars are found by touch; they stay put.
            !touchExploration

    private fun onPageScrollForBars(web: View, y: Int, oldY: Int) {
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

    private fun hideBars() {
        val top = binding.topBar
        val bottom = binding.bottomBar
        val area = binding.webArea
        if (barsHidden || top.height <= 0 || top.visibility != View.VISIBLE) return
        barsHidden = true
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
    private fun setPageUnderBars(under: Boolean) {
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

    // The status bar and navigation bar strips, held in the bars' colours
    // while the bars are away (EdgeScrim). Made on first use.
    private var statusScrim: EdgeScrim? = null
    private var navScrim: EdgeScrim? = null

    private fun showEdgeScrims() {
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

    private fun hideEdgeScrims() {
        statusScrim?.visibility = View.GONE
        navScrim?.visibility = View.GONE
    }

    /** Whether the phone runs animations (Remove animations / animator scale 0 says not). */
    private fun animationsOn(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.animation.ValueAnimator.areAnimatorsEnabled()
        else android.provider.Settings.Global.getFloat(contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    /** Brings the bars back; [animate] false puts them straight in place. */
    private fun showBars(animate: Boolean = true) {
        if (!barsHidden) return
        barsHidden = false
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
    private fun settleBars() {
        setPageUnderBars(false)
        binding.topBar.translationY = 0f
        binding.webArea.translationY = 0f
        binding.bottomBar.translationY = 0f
        hideEdgeScrims()
        schedulePageTint(60L)
    }

    // ---------- The page's colours in the bars ----------
    //
    // Both bars take on the site's colour, as Chrome's toolbar does: the
    // colour the site names for it (<meta name="theme-color">) when it names
    // one, otherwise the colours along the top of the page - its header.
    // Calmed (PageTint), so a red header gives a muted red, and the icons keep
    // their contrast. One colour for both bars, and steady: decided as the
    // page appears and finishes, and looked at again only at the top of the
    // page. The bottom bar used to read the page under it, so it changed
    // colour with every card that scrolled past while the top stayed put.
    // Eased over a third of a second. Not on the home page (which has its own
    // colours), Night Owl, error pages or with Settings > Customize > Page
    // colours in the bars off.

    private var topTint: IntArray? = null
    private var bottomTint: IntArray? = null
    private var tintAnim: android.animation.ValueAnimator? = null
    private val tintSample = Runnable { samplePageTint() }
    /** A second look a moment after a page finishes: hero pictures and dark sections paint late. */
    private val tintLate = Runnable { samplePageTint() }
    /** Tries while something is briefly in the way (the menu, a bar moving), so a page is never left untinted. */
    private var tintRetries = 0
    /** The page the bars' colour was taken from; it holds until the page changes or is back at its top. */
    private var tintedUrl: String? = null

    private fun tintAllowed(url: String?): Boolean =
        !nightOwl && url != null && url != homePage && !isErrorPage(url) &&
            (url.startsWith("https://") || url.startsWith("http://")) && tintBarsPref

    private fun schedulePageTint(delay: Long = 300L) {
        tintRetries = 0
        uiHandler.removeCallbacks(tintSample)
        uiHandler.postDelayed(tintSample, delay)
    }

    private fun samplePageTint() {
        val web = activeWeb() ?: return
        if (!tintAllowed(tabs.activeTab?.url)) { setBarTint(null, null, animate = true); return }
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
    private fun themeColour(web: WebView, done: (Int?) -> Unit) {
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
    private fun jsPageColours(web: WebView, done: (IntArray?, IntArray?) -> Unit) {
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
    private fun captureStrip(rect: android.graphics.Rect, done: (IntArray?) -> Unit) {
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
    private fun applyPageTint(edge: IntArray?, url: String?) {
        if (edge == null) return
        tintedUrl = url
        val bar = PageTint.barColours(edge, getColor(R.color.barSurface), isDarkTheme())
        setBarTint(bar, bar, animate = true)
    }

    private fun isDarkTheme(): Boolean = (resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    // Text and icons on a bar the page has turned dark go light, as on
    // Chrome's toolbar: the address, its pill, the star, the gear and the
    // bottom bar's icons, and the status and navigation bar icons with them.
    private var topOnDark = false
    private var bottomOnDark = false
    private var urlTextDefault: android.content.res.ColorStateList? = null

    private fun applyBarForeground() {
        if (!::binding.isInitialized) return
        val themeDark = isDarkTheme()
        val top = topTint.takeIf { !searchMode }
        val bottom = bottomTint
        val topDark = top?.let { PageTint.isDark(it) } ?: false
        val bottomDark = bottom?.let { PageTint.isDark(it) } ?: false
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = if (top != null) !topDark else !themeDark
        controller.isAppearanceLightNavigationBars = if (bottom != null) !bottomDark else !themeDark
        val pane = top?.getOrNull(1)
        if (topDark != topOnDark || pane != fieldPaneFor) {
            topOnDark = topDark
            fieldPaneFor = pane
            paintTopForeground()
        }
        if (bottomDark != bottomOnDark) { bottomOnDark = bottomDark; refreshNav() }
    }

    /** The bar colour the address field's pane was last worked out for (null: the plain bar). */
    private var fieldPaneFor: Int? = null

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
    private fun paintTopForeground() {
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
    private fun fieldPane(bar: Int, onDark: Boolean): Int =
        if (onDark) PageTint.mix(bar, android.graphics.Color.WHITE, 0.18f)
        else PageTint.mix(bar, android.graphics.Color.BLACK, 0.075f)

    /** Eases both bars to [top] / [bottom] (null: their plain surface). */
    private fun setBarTint(top: IntArray?, bottom: IntArray?, animate: Boolean) {
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

    // ---------- HTML5 fullscreen video ----------

    private fun enterFullscreen(view: View, cb: WebChromeClient.CustomViewCallback?) {
        // Some players fire this twice. The second must be refused, or the first
        // view is orphaned and the page can never come back out of fullscreen.
        if (fullscreenView != null) { cb?.onCustomViewHidden(); return }

        fullscreenView = view
        fullscreenCallback = cb
        savedOrientation = requestedOrientation

        // Hosted in the activity content root, which is the PARENT of our app
        // root - so it covers the inset padding, the top bar and the toolbar
        // without any of them needing to know fullscreen exists. Clickable so
        // player taps cannot fall through to the chrome underneath.
        val root = findViewById<android.widget.FrameLayout>(android.R.id.content)
        val holder = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = true
        }
        holder.addView(
            view,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER
            )
        )
        root.addView(
            holder,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenContainer = holder

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setSystemBarsVisible(false)
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // A portrait video (a short, a phone-shot clip) is turned upright
        // again rather than shown sideways in a landscape screen. The page
        // knows the video's size; landscape stays when it does not say.
        activeWeb()?.evaluateJavascript(
            "(function(){var e=document.fullscreenElement||document.webkitFullscreenElement;" +
                "var v=e&&(e.tagName==='VIDEO'?e:e.querySelector('video'));" +
                "return v&&v.videoWidth?v.videoWidth+'x'+v.videoHeight:'';})()"
        ) { r ->
            val size = r?.trim('"')?.split('x')
            val w = size?.getOrNull(0)?.toIntOrNull() ?: 0
            val h = size?.getOrNull(1)?.toIntOrNull() ?: 0
            if (fullscreenView === view && w > 0 && h > w) {
                requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    private fun exitFullscreen() {
        val holder = fullscreenContainer ?: return
        val root = findViewById<android.widget.FrameLayout>(android.R.id.content)
        holder.removeAllViews()
        root.removeView(holder)
        fullscreenContainer = null
        fullscreenView = null

        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setSystemBarsVisible(true)
        requestedOrientation = savedOrientation

        // Last, deliberately: the player tears its surface down here, and doing
        // that before the view is detached leaves a black frame on some devices.
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    // ---------- Picture in picture ----------

    /**
     * Leaving the app with a video fullscreened hands it to a floating window
     * instead of stopping it. Only ever from fullscreen: shrinking an ordinary
     * page into a 200dp box helps nobody.
     *
     * onPause deliberately does not pause the WebView or its timers, so
     * playback simply continues once the window shrinks.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeEnterPip()
    }

    private fun maybeEnterPip() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        if (inPip) return
        val v = fullscreenView ?: return
        if (!packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) return
        // The system rejects anything outside roughly 1:2.39 to 2.39:1 by
        // throwing, so clamp rather than hand it something it will refuse.
        val ratio = (w.toFloat() / h.toFloat()).coerceIn(0.42f, 2.38f)
        try {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational((ratio * 1000).toInt(), 1000))
                    .build()
            )
        } catch (e: Exception) {
            // A device can refuse PiP for its own reasons; staying put is fine.
        }
    }

    // Parameter named as the supertype names it. Renaming one in an override
    // is legal but means a named-argument call goes to the wrong place, which
    // is why the compiler warns about it.
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // Closed from the PiP window rather than expanded back into the app:
        // the activity is on its way to stopped, so tear the player down now
        // instead of leaving a fullscreen view behind for the next launch.
        if (!isInPictureInPictureMode &&
            lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) {
            exitFullscreen()
        }
    }

    private fun setSystemBarsVisible(show: Boolean) {
        val c = androidx.core.view.WindowCompat
            .getInsetsController(window, window.decorView)
        if (show) {
            c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------- Long-press context menu ----------

    private fun setupLongPress(web: WebView) {
        web.setOnLongClickListener {
            val r = web.hitTestResult
            when (r.type) {
                WebView.HitTestResult.IMAGE_TYPE -> {
                    val img = r.extra
                    if (img.isNullOrBlank()) false else { showTargetMenu(img, null); true }
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val img = r.extra
                    // hitTestResult carries the image, never the anchor around
                    // it; the href only arrives through this async hop, so the
                    // menu is built there, once both halves are known.
                    val h = object : android.os.Handler(android.os.Looper.getMainLooper()) {
                        override fun handleMessage(m: android.os.Message) {
                            if (!img.isNullOrBlank()) showTargetMenu(img, m.data?.getString("url"))
                        }
                    }
                    web.requestFocusNodeHref(h.obtainMessage())
                    !img.isNullOrBlank()
                }
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    val href = r.extra
                    if (href.isNullOrBlank()) false else {
                        // The link's own text comes back through the same
                        // async hop as an image anchor's href, for Copy link text.
                        val h = object : android.os.Handler(android.os.Looper.getMainLooper()) {
                            override fun handleMessage(m: android.os.Message) {
                                showTargetMenu(null, href, m.data?.getString("title"))
                            }
                        }
                        try { web.requestFocusNodeHref(h.obtainMessage()) }
                        catch (e: Exception) { showTargetMenu(null, href) }
                        true
                    }
                }
                // Plain text and input fields fall through to the system, or we
                // would break text selection everywhere to add this menu.
                else -> false
            }
        }
    }

    private fun showTargetMenu(image: String?, link: String?, linkText: String? = null) {
        if (isFinishing || isDestroyed) return
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        val webLink = link?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

        if (!link.isNullOrBlank()) {
            // In the background, as Chrome does, so a list of results can be
            // worked down without leaving it; "Switch" goes there.
            labels.add("Open in new tab"); acts.add { addBackgroundTab(link) }
            if (!nightOwl && webLink != null) {
                labels.add("Open in Night Owl tab"); acts.add { enterNightOwl(webLink) }
            }
            labels.add("Copy link address"); acts.add { copyText("Link", link) }
            val text = linkText?.trim()?.takeIf { it.isNotEmpty() && it != link }
            if (text != null) { labels.add("Copy link text"); acts.add { copyText("Link text", text) } }
            labels.add("Share link"); acts.add { shareLink(link, "") }
            if (webLink != null) {
                labels.add("Download link"); acts.add {
                    val name = webLink.substringBefore('?').substringAfterLast('/')
                    val mime = try { java.net.URLConnection.guessContentTypeFromName(name) } catch (e: Exception) { null }
                    startDownload(webLink, activeWeb()?.settings?.userAgentString, null, mime, -1L)
                }
            }
        }
        if (!image.isNullOrBlank()) {
            labels.add("Save image"); acts.add { saveImage(image) }
            labels.add("Open image in new tab"); acts.add { addBackgroundTab(image) }
            // A data: or blob: address is meaningless outside this page, so it
            // is not offered for copy or share - a link nobody can open is a
            // worse answer than no menu entry.
            if (image.startsWith("http")) {
                labels.add("Search image with Google Lens"); acts.add {
                    addBackgroundTab("https://lens.google.com/uploadbyurl?url=" + android.net.Uri.encode(image))
                }
                labels.add("Copy image address"); acts.add { copyText("Image", image) }
                labels.add("Share image"); acts.add { shareLink(image, "") }
            }
        }
        if (labels.isEmpty()) return

        val head = image ?: link ?: ""
        val title = if (head.startsWith("data:") || head.startsWith("blob:"))
            "Image" else head.take(60)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Search_Dialog)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { d, i -> d.dismiss(); acts[i]() }
            .show()
    }

    /**
     * A tab for [url] opened beside the one on screen without leaving it: it
     * gets no page until it is first shown (openTab builds it then), so it
     * costs nothing until wanted. "Switch" goes to it.
     */
    private fun addBackgroundTab(url: String) {
        val target = resolveInput(url)
        val tab = tabs.createTab(target)
        tab.title = siteNameOf(target).ifBlank { "New tab" }
        // Just after the tab it came from, as Chrome places it.
        val from = tabs.tabs.indexOf(tabs.activeTab)
        if (from >= 0) {
            tabs.tabs.remove(tab)
            tabs.tabs.add((from + 1).coerceAtMost(tabs.tabs.size), tab)
        }
        updateTabCount()
        scheduleSessionSave()
        Haptics.tick(binding.webContainer)
        try {
            com.google.android.material.snackbar.Snackbar
                .make(binding.webArea, "Opened in a new tab", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("Switch") { if (tabs.tabs.contains(tab)) { leaveTransientUi(); openTab(tab) } }
                .setActionTextColor(currentAccent())
                .show()
        } catch (e: Exception) { /* no window to show it in */ }
    }

    private fun copyText(label: String, text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            // Android 13+ raises its own copy confirmation; ours would double it.
            if (android.os.Build.VERSION.SDK_INT < 33) {
                android.widget.Toast.makeText(
                    this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this, "Couldn't copy", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Saving images ----------

    private fun saveImage(url: String) {
        if (android.os.Build.VERSION.SDK_INT <= 28) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingImageSave = url
                storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        when {
            url.startsWith("data:") -> {
                toast("Saving image\u2026")
                Thread { saveDataImage(url) }.start()
            }
            url.startsWith("http://") || url.startsWith("https://") -> {
                toast("Saving image\u2026")
                val ua = activeWeb()?.settings?.userAgentString
                val referer = activeWeb()?.url
                Thread { saveRemoteImage(url, ua, referer) }.start()
            }
            // Only the page can read a blob:, so we ask it to (see requestBlobSave).
            url.startsWith("blob:") -> {
                toast("Saving image\u2026")
                requestBlobSave(url)
            }
            else -> toast("Can't save this image")
        }
    }

    /**
     * Fetched here rather than handed to DownloadManager for two reasons: the
     * cookies and referer below are what separate a real image from a saved 403
     * page, and writing through MediaStore ourselves is what makes the file
     * actually appear in the gallery.
     */
    private fun saveRemoteImage(url: String, ua: String?, referer: String?) {
        var conn: HttpURLConnection? = null
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            ua?.let { c.setRequestProperty("User-Agent", it) }
            android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                c.setRequestProperty("Cookie", it)
            }
            referer?.let { if (it.startsWith("http")) c.setRequestProperty("Referer", it) }
            c.connect()
            if (c.responseCode !in 200..299) { imageSaveFailed(); return }
            val mime = c.contentType?.substringBefore(';')?.trim()
            // An "image" can be anything a server says it is, and it is read
            // whole into memory here: past 64MB it is refused rather than
            // risking the app being killed for memory part-way through.
            val max = 64L * 1024 * 1024
            if (c.contentLengthLong > max) { imageSaveFailed(); return }
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > max) { imageSaveFailed(); return }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            if (bytes.isEmpty()) { imageSaveFailed(); return }
            writeImage(bytes, if (mime.isNullOrBlank()) "image/jpeg" else mime)
        } catch (e: Throwable) {
            // OutOfMemoryError included: a failed save, never a dead app.
            imageSaveFailed()
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    /**
     * The page fetches its own blob, turns it into a data: URL and hands that
     * back over the bridge. Capped at 16MB: base64 inflates by a third and the
     * whole string crosses the bridge in one piece.
     *
     * A site with a strict connect-src CSP can refuse the fetch. That path ends
     * in saveBlobFailed, so it reports rather than hanging.
     */
    private fun requestBlobSave(url: String) {
        val web = activeWeb() ?: run { imageSaveFailed(); return }
        val token = java.util.UUID.randomUUID().toString()
        blobSaveToken = token
        val js = "(function(u,t){try{" +
            "fetch(u).then(function(r){if(!r.ok)throw 0;return r.blob();})" +
            ".then(function(b){if(b.size>16777216)throw 0;" +
            "var fr=new FileReader();" +
            "fr.onloadend=function(){SearchApp.saveBlobImage(t,fr.result);};" +
            "fr.onerror=function(){SearchApp.saveBlobFailed(t);};" +
            "fr.readAsDataURL(b);})" +
            ".catch(function(){SearchApp.saveBlobFailed(t);});" +
            "}catch(e){SearchApp.saveBlobFailed(t);}})(" +
            JSONObject.quote(url) + "," + JSONObject.quote(token) + ");"
        web.evaluateJavascript(js, null)
    }

    private fun saveDataImage(url: String) {
        try {
            val comma = url.indexOf(',')
            if (comma < 0) { imageSaveFailed(); return }
            val header = url.substring(5, comma)
            val mime = header.substringBefore(';').ifBlank { "image/png" }
            val body = url.substring(comma + 1)
            val bytes = if (header.contains("base64"))
                android.util.Base64.decode(body, android.util.Base64.DEFAULT)
            else java.net.URLDecoder.decode(body, "UTF-8").toByteArray()
            if (bytes.isEmpty()) { imageSaveFailed(); return }
            writeImage(bytes, mime)
        } catch (e: Throwable) {
            imageSaveFailed()
        }
    }

    private fun writeImage(bytes: ByteArray, mime: String) {
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            else -> "jpg"
        }
        val name = "IMG_" + System.currentTimeMillis() + "." + ext
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/Search"
                    )
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: run { imageSaveFailed(); return }
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                val dir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES
                    ), "Search"
                )
                if (!dir.exists()) dir.mkdirs()
                val f = java.io.File(dir, name)
                java.io.FileOutputStream(f).use { it.write(bytes) }
                // Without the scan the file is on disk but no gallery lists it,
                // which reads to the user as another failed save.
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(f.absolutePath), arrayOf(mime), null
                )
            }
            runOnUiThread { toast("Image saved to Pictures/Search") }
        } catch (e: Exception) {
            imageSaveFailed()
        }
    }

    private fun imageSaveFailed() {
        runOnUiThread { toast("Couldn't save image") }
    }

    /**
     * Print or save as PDF, through Android's print screen, which offers
     * "Save as PDF" on every phone - as Chrome's Share > Print does. The page
     * is laid out for paper by the page itself (its print stylesheet).
     */
    private fun printPage() {
        val web = activeWeb() ?: return
        val url = tabs.activeTab?.url
        if (url == null || url == homePage || isErrorPage(url)) { toast("Open a page to print it"); return }
        val pm = getSystemService(PRINT_SERVICE) as? android.print.PrintManager ?: return
        val name = (web.title?.takeIf { it.isNotBlank() } ?: UrlHelper.siteOf(url) ?: "Page").take(80)
        try {
            pm.print(name, web.createPrintDocumentAdapter(name),
                android.print.PrintAttributes.Builder().build())
        } catch (e: Exception) {
            toast("Couldn't print this page")
        }
    }

    /**
     * A site that asks for a user name and password itself (HTTP sign-in: a
     * router's admin page, a NAS, an office intranet). WebView's default is
     * to refuse without asking, which left these pages blank with no way in.
     * The answer goes to exactly one of proceed or cancel.
     */
    private fun askHttpAuth(handler: android.webkit.HttpAuthHandler, host: String, realm: String) {
        if (isFinishing || isDestroyed) { handler.cancel(); return }
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val user = android.widget.EditText(this).apply {
            hint = "User name"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val pass = android.widget.EditText(this).apply {
            hint = "Password"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(user); addView(pass)
        }
        var answered = false
        fun answer(ok: Boolean) {
            if (answered) return
            answered = true
            if (ok) handler.proceed(user.text.toString(), pass.text.toString()) else handler.cancel()
        }
        try {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
                .setTitle("Sign in to $host")
                .setMessage(if (realm.isBlank()) "This site asks for a user name and password."
                    else "The site says: \u201c" + realm.take(120) + "\u201d")
                .setView(box)
                .setPositiveButton("Sign in") { _, _ -> answer(true) }
                .setNegativeButton("Cancel") { _, _ -> answer(false) }
                .setOnDismissListener { answer(false) }
                .show()
        } catch (e: Exception) {
            answer(false)
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * What the address bar shows. The app's own pages have no address worth
     * showing: a failed load used to leave
     * "file:///android_asset/error.html?u=https%3A%2F%2F..." sitting in the
     * bar, which is neither where the user is nor anywhere they can go.
     */
    /**
     * Writes one site's icon into the cache the home page reads.
     *
     * Bounded: an icon per site visited would grow without limit, so the
     * oldest fall out past a cap. Off the main thread - PNG encoding and a
     * base64 of it are not free.
     */
    /** Whether a captured favicon (a PNG data: URL) is big enough to look sharp in a tile. */
    private val sharpMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private fun capturedIsSharp(domain: String, dataUrl: String): Boolean {
        val key = domain + ":" + dataUrl.length
        sharpMemo[key]?.let { return it }
        val sharp = try {
            val bytes = android.util.Base64.decode(dataUrl.substringAfter(','), android.util.Base64.DEFAULT)
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            minOf(o.outWidth, o.outHeight) >= 96
        } catch (e: Exception) { false }
        sharpMemo[key] = sharp
        return sharp
    }

    private fun storeFavicon(host: String, bmp: android.graphics.Bitmap) {
        try {
            val domain = host.removePrefix("www.")
                .removePrefix("mobile.").removePrefix("m.")
            if (domain.isBlank()) return
            val bytes = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
            bmp.recycle()
            val dataUrl = "data:image/png;base64," + android.util.Base64.encodeToString(
                bytes.toByteArray(), android.util.Base64.NO_WRAP)
            // A 64x64 PNG is a couple of kB; anything far past that is not an
            // icon and is not worth carrying across the bridge.
            // Room for a real 256px icon, which the 40k cap would have
            // thrown away - the very icons worth keeping were the ones being
            // rejected. Paired with a smaller cache below, since all of this
            // is held in SharedPreferences and loaded into memory at once.
            if (dataUrl.length > 60000) return
            val prefs = getSharedPreferences("favicon_cache", Context.MODE_PRIVATE)
            val order = (prefs.getString("__order", "") ?: "")
                .split(",").filter { it.isNotBlank() && it != domain }
                .toMutableList()
            order.add(domain)
            val edit = prefs.edit().putString(domain, dataUrl)
            while (order.size > 40) {
                edit.remove(order.removeAt(0))
            }
            edit.putString("__order", order.joinToString(",")).apply()
        } catch (e: Exception) { /* an icon is not worth a crash */ }
    }

    /** Re-draws the home tiles in place, with no page reload and no flash. */
    private fun refreshHomeTiles() {
        activeWeb()?.evaluateJavascript(
            "window.__renderTiles && window.__renderTiles()", null)
    }

    private fun showAddShortcutDialog() {
        val d = resources.displayMetrics.density
        val input = android.widget.EditText(this).apply {
            hint = "example.com"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Search_Dialog)
            .setTitle("Add shortcut")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val typed = input.text.toString().trim()
                // A phrase is a search, not a site. Without this the tile
                // would be a frozen search results page.
                if (typed.isBlank() || typed.contains(' ')) return@setPositiveButton
                val url = UrlHelper.toUrlOrSearch(typed, Settings.getEngineUrl(this))
                val dom = Shortcuts.domainOf(url)
                if (dom.isBlank()) return@setPositiveButton
                val label = dom.substringBefore('.')
                    .replaceFirstChar { it.uppercase() }
                Shortcuts.addCustom(this, label, url)
                refreshHomeTiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun showRemoveShortcutDialog(label: String, url: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Search_Dialog)
            .setTitle("Remove " + label.ifBlank { "this shortcut" } + "?")
            .setMessage("It comes off the home page. Your history and bookmarks are not touched.")
            .setPositiveButton("Remove") { _, _ ->
                val dom = Shortcuts.keyOf(url)
                // One the user added is simply forgotten. A built-in or a site
                // from history is generated every render, so the only way to
                // keep it gone is to remember that it was removed.
                val wasCustom = Shortcuts.loadCustom(this)
                    .any { Shortcuts.keyOf(it.url) == dom }
                if (wasCustom) Shortcuts.removeCustom(this, url)
                else Shortcuts.hide(this, dom)
                refreshHomeTiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * What the address bar shows at rest: the site, not the address.
     *
     * "google.com" rather than "https://www.google.com/search?q=moviebox&oq=",
     * which is what every other browser does and what makes the bar readable
     * at a glance. Nothing is lost - enterSearchMode fills the bar with the
     * full url and selects it as soon as it is focused, so copying and editing
     * the real address are unchanged.
     *
     * http:// is kept on purpose. Hiding the scheme would make an insecure
     * page look exactly like a secure one, and there is no lock in this bar to
     * carry that distinction. HTTPS-only is on by default and plain http
     * already shows an interstitial first, so arriving on one is deliberate -
     * but the bar should not then imply otherwise. A non-standard port is kept
     * for the same reason: it is part of what you are looking at.
     *
     * Anything that is not a web page is returned untouched. about:, intent:
     * and custom schemes have no host to extract, and rewriting them would
     * hide what the page really is.
     */
    private fun displayUrl(url: String?): String {
        if (url == null || url.startsWith("file:///android_asset/")) return ""
        val uri = try {
            android.net.Uri.parse(url)
        } catch (e: Exception) {
            return url
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return url
        val host = uri.host ?: return url
        if (host.isEmpty()) return url
        val shown = host.removePrefix("www.") +
            (if (uri.port > 0) ":" + uri.port else "")
        return if (scheme == "http") "http://" + shown else shown
    }

    private fun captureThumbnail(tab: Tab, onDone: (() -> Unit)? = null) {
        val web = tab.webView
        if (deckVisible || web == null || web.width <= 0 || web.height <= 0 ||
            !web.isAttachedToWindow || binding.webContainer.translationX != 0f
        ) { onDone?.invoke(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Allocate the destination at reduced resolution; PixelCopy
                // scales the full-size source rect down into it. Clamp to >=1px
                // so a tiny view never produces a zero-sized bitmap.
                val dstW = (web.width / thumbCaptureDivisor).coerceAtLeast(1)
                val dstH = (web.height / thumbCaptureDivisor).coerceAtLeast(1)
                val source = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.RGB_565)
                val location = IntArray(2)
                web.getLocationInWindow(location)
                val rect = android.graphics.Rect(
                    location[0], location[1],
                    location[0] + web.width, location[1] + web.height
                )
                PixelCopy.request(
                    window, rect, source,
                    { result ->
                        if (result == PixelCopy.SUCCESS && !deckVisible) storeScaled(tab, source)
                        else source.recycle()
                        onDone?.invoke()
                    },
                    Handler(Looper.getMainLooper())
                )
                return
            } catch (e: Exception) { /* fall through */ }
        }
        onDone?.invoke()
    }

    private fun storeScaled(tab: Tab, full: Bitmap) {
        try {
            val ratio = full.height.toFloat() / full.width.toFloat()
            val targetH = (thumbWidthPx * ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(full, thumbWidthPx, targetH, true)
            if (scaled != full) full.recycle()
            tab.thumbnail?.recycle()
            tab.thumbnail = scaled
        } catch (e: Exception) { if (!full.isRecycled) full.recycle() }
    }

    /**
     * Loads a url, settling the bridge's privilege before the page can ask.
     *
     * onPageStarted cannot be relied on for this. It arrives on the main
     * thread; the document's script runs in the renderer. For an asset page
     * there is no network latency between them, so a busy main thread loses
     * the race and the page is told it is unprivileged - which it then renders
     * as an ordinary, non-private home page.
     *
     * Deciding it here is exact: the caller already knows the url. Privilege is
     * granted only to app asset pages and taken away for everything else, so a
     * tab leaving home.html for a website drops it in the same instant rather
     * than whenever the callback happens to land.
     */
    private fun loadInto(web: WebView?, url: String) {
        val target = web ?: return
        (target.tag as? SearchAppBridge)?.privileged =
            url.startsWith("file:///android_asset/")
        target.loadUrl(url)
        // Leaving home: the feed ad card goes now, not when the next page
        // commits. Home stays on screen until then, and a search or a story
        // opened from it showed the card over home and then over the new page
        // as it arrived. The WebView already reports the address it is going
        // to, which is what refreshAdSlot reads.
        if (target === activeWeb() && url != homePage) refreshAdSlot()
    }

    /**
     * Loads [url] into [web] once the WebView callback that asked for it has
     * returned.
     *
     * Starting a navigation from inside a navigation callback is the crash Play
     * reports against shouldOverrideUrlLoading. For a link that opens a new
     * tab - target=_blank, window.open - Chromium asks that callback from
     * inside its own navigation start, and that code is not re-entrant: a
     * loadUrl() there fails a CHECK and the process is aborted with SIGTRAP,
     * which nothing in Kotlin can catch. With HTTPS-only on, the default,
     * every http:// link that opened in a new tab went down that path.
     *
     * The pages put up from onReceivedError are loaded the same way, since a
     * load can fail - and report it - before the call that started it has
     * returned. Posted, the load runs a moment later from a clean stack, and
     * only if the WebView is still one the app owns.
     */
    private fun loadAfterCallback(web: WebView?, url: String) {
        val target = web ?: return
        uiHandler.post { if (isLiveWeb(target)) loadInto(target, url) }
    }

    /** The tab a WebView belongs to - not necessarily the one on screen. */
    private fun tabOf(web: WebView?): Tab? =
        if (web == null) null else tabs.tabs.firstOrNull { it.webView === web }

    /**
     * Closes a tab a page opened, if nothing ever loaded in it. Run after the
     * callback that decided its first navigation was not ours to load.
     */
    private fun closeIfNeverLoaded(web: WebView?) {
        val target = web ?: return
        uiHandler.post {
            val tab = tabOf(target) ?: return@post
            // about:blank is what onCreateWindow names the tab, and
            // onPageStarted replaces it as soon as a page starts loading.
            if (tab.openerId != null && tab.url == "about:blank") closeTabFromDeck(tab)
        }
    }

    /**
     * Throws away a WebView whose renderer has gone, and puts the tab's page
     * back in a new one if it is the tab on screen. Background tabs are left
     * frozen and reload when next opened, as any frozen tab does.
     *
     * The dead view is only removed and destroyed - WebView's own instruction,
     * since anything else called on it may reach the renderer that is gone.
     */
    private fun replaceDeadWebView(dead: WebView) {
        val tab = tabOf(dead)
        if (tab != null && tab === tabs.activeTab && fullscreenView != null) {
            // The player's callback belongs to the dead page. Dropped rather
            // than called, then fullscreen is unwound as usual.
            fullscreenCallback = null
            exitFullscreen()
        }
        (dead.parent as? ViewGroup)?.removeView(dead)
        dead.destroy()
        if (tab == null) return
        tab.webView = null
        // State saved by an earlier freeze is older than the page that just
        // died. The address onPageStarted last recorded is where the tab was.
        tab.savedState = null
        // Unless the user ended it for hanging, or it has now taken the
        // renderer down twice in a row - a page too heavy for the phone,
        // killed for memory each time it loads. Reloaded either way it would
        // only do it again, taking every tab with it. It is rebuilt on a page
        // that says so instead, and goes back only when Try again is tapped.
        val now = android.os.SystemClock.uptimeMillis()
        val crashLoop = now - tab.lastRendererLoss < 20_000L
        tab.lastRendererLoss = now
        val ended = tab.id == stoppedTabId
        if (ended) stoppedTabId = null
        if ((ended || crashLoop) &&
            (tab.url.startsWith("http://") || tab.url.startsWith("https://"))
        ) {
            DebugLog.add { "tab#" + tab.id + (if (ended) " ended by the user" else
                " lost its renderer twice in 20s") + "; not reloading it" }
            tab.url = stoppedPage(tab.url, crashed = !ended)
            tab.diskStateHash = 0
        }
        if (tab === tabs.activeTab) {
            // Outside the callback, which WebView is still running for the
            // other tabs this renderer served.
            uiHandler.post {
                if (!isFinishing && !isDestroyed && tab === tabs.activeTab &&
                    tab.webView == null
                ) {
                    // Ended by the user: if the page put up in its place does
                    // not start either, the hang was never in the renderer,
                    // and the prompt comes back offering a restart. Longer
                    // than for Home, as a new renderer has to start first.
                    if (ended) expectPageStart(tab, 6000L)
                    openTab(tab)
                }
            }
        }
    }

    /**
     * A pop-up that has been to another site and come back to its opener's is
     * a sign-in that has returned: the provider is done, and the page now
     * showing is the site's own callback. A working one hands the result to
     * its opener and closes within a second. One that is still there a few
     * seconds later, with nothing on it to use, has stalled - the opener
     * could not be reached, or the close was refused - and that is the blank
     * page the user was left on, signed in but stuck. It is folded back into
     * the tab that opened it, as foldBackPopup describes.
     *
     * Only while the pop-up is the tab on screen, and only if the page really
     * is empty: a site that carries on in the pop-up with a form or a page of
     * its own is left alone.
     */
    private fun watchReturnedPopup(popup: Tab, url: String?) {
        if (!popup.signIn || !popup.leftOpenerSite) return
        val site = UrlHelper.siteOf(url) ?: return
        if (site != popup.openerSite) return
        val web = popup.webView ?: return
        DebugLog.add { "pop-up tab#" + popup.id + " is back on " + site + "; watching for a stall" }
        checkStalledPopup(popup, web, popup.loadToken, emptyLooks = 0, attempt = 0)
    }

    /**
     * One look at a returned pop-up. It is folded back only after two looks,
     * [POPUP_STALL_MS] and then [POPUP_RECHECK_MS] apart, both finding a page
     * that has finished loading and has nothing on it: a callback that is
     * still exchanging its code over a slow connection gets the second look's
     * worth of time, and one that is still loading is never judged at all.
     * Any new load in the pop-up, or leaving it, ends the watch; coming back
     * to it starts a new one (openTab).
     */
    private fun checkStalledPopup(popup: Tab, web: WebView, token: Int, emptyLooks: Int, attempt: Int) {
        val delay = if (emptyLooks == 0 && attempt == 0) POPUP_STALL_MS else POPUP_RECHECK_MS
        uiHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (popup.loadToken != token || popup.webView !== web) return@postDelayed
            if (popup !== tabs.activeTab || !tabs.tabs.contains(popup)) return@postDelayed
            if (web.progress < 100) {
                if (attempt < 6) checkStalledPopup(popup, web, token, emptyLooks, attempt + 1)
                return@postDelayed
            }
            web.evaluateJavascript(PAGE_EMPTINESS_JS) { result ->
                if (popup.loadToken != token || popup.webView !== web ||
                    !tabs.tabs.contains(popup)
                ) return@evaluateJavascript
                val empty = isEmptyPage(result)
                DebugLog.add { "pop-up tab#" + popup.id + " look " + (emptyLooks + 1) + ": " +
                    result + (if (empty) " - empty" else " - has content, left alone") }
                if (!empty) return@evaluateJavascript
                if (emptyLooks >= 1) foldBackPopup(popup, "stalled on its callback page")
                else checkStalledPopup(popup, web, token, emptyLooks + 1, attempt + 1)
            }
        }, delay)
    }

    /**
     * Records where a pop-up has been: whether it has left its opener's site
     * (for the identity provider) and whether anything it loaded was part of
     * a sign-in. Fed from shouldOverrideUrlLoading, which sees redirects, and
     * from onPageStarted, which sees what committed.
     */
    private fun notePopupNavigation(view: WebView?, url: String?) {
        val t = tabOf(view) ?: return
        if (t.openerId == null || url == null) return
        val site = UrlHelper.siteOf(url)
        if (site != null && t.openerSite != null && site != t.openerSite) t.leftOpenerSite = true
        if (!t.signIn && UrlHelper.looksLikeSignIn(url)) {
            t.signIn = true
            DebugLog.add { "pop-up tab#" + t.id + " is a sign-in (" + DebugLog.url(url) + ")" }
        }
    }

    /** A sign-in pop-up that went to its provider and is back on the opener's site. */
    private fun returnedFromSignIn(popup: Tab): Boolean =
        popup.signIn && popup.leftOpenerSite && popup.openerSite != null &&
            UrlHelper.siteOf(popup.url) == popup.openerSite

    /**
     * How much of a page there is to use: visible text, and visible controls
     * someone could press or type into. Read in one pass, as a JSON object.
     */
    private val PAGE_EMPTINESS_JS = """
        (function(){
          var b = document.body;
          if (!b) return {text: 0, controls: 0};
          var controls = 0;
          var els = document.querySelectorAll('input:not([type=hidden]),button,select,textarea,a[href],[role=button]');
          for (var i = 0; i < els.length && controls < 3; i++) {
            var r = els[i].getBoundingClientRect();
            if (r.width > 0 && r.height > 0) controls++;
          }
          return {text: (b.innerText || '').trim().length, controls: controls};
        })()
    """.trimIndent()

    /** Nothing to press and next to nothing to read: "Signing you in..." at most. */
    private fun isEmptyPage(json: String?): Boolean = try {
        val o = JSONObject(json ?: "")
        o.optInt("controls", 1) == 0 && o.optInt("text", 999) < 80
    } catch (e: Exception) { false }

    /**
     * Closes a sign-in pop-up and puts the user back on the page that opened
     * it - and, when the sign-in came back without that page ever hearing of
     * it, reloads that page.
     *
     * The session a sign-in leaves behind is a cookie or local storage on the
     * site, so a fresh load of the site is signed in. That is the "close it
     * and open the site again" users were having to do by hand. The reload is
     * skipped when the opener has already started a load of its own since the
     * pop-up opened, because then it did hear, and is on its way.
     */
    private fun foldBackPopup(popup: Tab, why: String) {
        if (!tabs.tabs.contains(popup)) return
        val opener = tabs.tabs.firstOrNull { it.id == popup.openerId && it !== popup }
        val returned = returnedFromSignIn(popup)
        // Moved on: started a load of its own since the pop-up opened, or is
        // part-way through one now - which a reload would cancel.
        val openerBusy = (opener?.webView?.progress ?: 100) < 100
        val openerMovedOn = opener != null && (openerBusy ||
            (popup.openerLoadsAtOpen >= 0 && opener.loadsStarted != popup.openerLoadsAtOpen))
        DebugLog.add { "folding pop-up tab#" + popup.id + " back into tab#" + opener?.id +
            " (" + why + "); returned=" + returned + " openerMovedOn=" + openerMovedOn }
        val openerWasLive = opener?.webView != null
        closeTabFromDeck(popup)
        if (opener == null || !returned) return
        if (openerMovedOn) {
            // It heard, and started loading. Make sure it got somewhere.
            watchOpenerAfterSignIn(opener)
            return
        }
        // A frozen opener needs nothing more: it loads afresh whenever it is
        // next opened, which closeTabFromDeck has just done if the pop-up was
        // the tab on screen.
        if (!openerWasLive) return
        val web = opener.webView ?: return
        loadInto(web, web.url ?: opener.url)
    }

    /**
     * After a sign-in pop-up has returned, the page that opened it should show
     * the result - by loading a new page, or by redrawing itself. One that
     * has done neither a few seconds later, and is showing nothing at all, is
     * stuck on the way, and a fresh load of it is signed in. A page that
     * moved on, or that has anything on it, is left exactly as it is.
     */
    private fun watchOpenerAfterSignIn(opener: Tab) {
        val web = opener.webView ?: return
        val token = opener.loadToken
        DebugLog.add { "watching tab#" + opener.id + " after its sign-in pop-up returned" }
        uiHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (opener.webView !== web || !tabs.tabs.contains(opener)) return@postDelayed
            web.evaluateJavascript(PAGE_EMPTINESS_JS) { result ->
                val empty = isEmptyPage(result)
                DebugLog.add { "tab#" + opener.id + " after sign-in: " + result +
                    (if (empty) " - blank, reloading" else " - fine") }
                if (empty && opener.webView === web && tabs.tabs.contains(opener) &&
                    opener.loadToken == token && web.progress >= 100
                ) loadInto(web, web.url ?: opener.url)
            }
        }, OPENER_STALL_MS)
    }

    // ---------- A page that stops answering ----------
    //
    // Every WebView in the app shares one renderer process, so a page that
    // locks it up - a script that never ends - locks every tab with it. The
    // app's own buttons still work, but anything they ask of a page goes
    // nowhere: Home, a new tab, another tab all stay blank, and the only way
    // out used to be closing the app.
    //
    // Two things catch it now. Android reports a page that stops responding
    // (Android 10 and up), and keeps reporting it while it stays stuck. And
    // the app checks for itself: the home page is a local file that starts
    // loading in milliseconds, so Home or New tab that has not started it
    // after a few seconds means the pages are stuck - which also covers
    // Android 9 and below, where nothing is reported.
    //
    // Either way the same prompt offers the way out. "Wait" holds it back for
    // a while, not for good - it used to silence it until the page recovered,
    // and a page that never did left no way out at all.
    //
    // Ending the page ends the renderer, which clears a page that is stuck.
    // It does not clear a hang in the app's own process - WebView's loading
    // threads are the app's - and Figma showed one: ended, the new renderer
    // could not load even the "stopped" page, and Home stayed blank. So the
    // page that replaces an ended one is watched too, and pages still stuck
    // soon after an end are offered a restart of Search instead, with the
    // tabs coming back from disk.

    private var unresponsiveDialog: android.app.AlertDialog? = null
    private var hangQuietUntil = 0L
    /** The tab the user ended for not responding, until it is rebuilt. */
    private var stoppedTabId: Long? = null
    private val HANG_WAIT_MS = 10_000L
    /** When the user last ended the renderer for a hang (uptime), or 0. */
    private var rendererEndedAt = 0L
    /** Pages stuck again this soon after an end are not stuck in the renderer. */
    private val ENDED_RECENTLY_MS = 90_000L

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun onPageUnresponsive(view: WebView, renderer: android.webkit.WebViewRenderProcess?) {
        DebugLog.add { "page not responding in tab#" + tabOf(view)?.id }
        // Any tab, not only the one on screen: they all share the renderer.
        showPagesStuck(view, renderer)
    }

    private fun onPageResponsive(view: WebView) {
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
    private fun showPagesStuck(view: WebView?, renderer: Any?) {
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

    private fun endStuckPages(view: WebView?, renderer: Any?, restart: Boolean) {
        // The tab that hung is not loaded straight back into the same hang:
        // it comes back as a "this page stopped" page with Try again.
        val stuck = tabOf(view) ?: tabs.activeTab
        stoppedTabId = stuck?.id
        if (!restart && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DebugLog.add { "user ended the unresponsive page" }
            rendererEndedAt = android.os.SystemClock.uptimeMillis()
            // Ending the renderer comes back through onRenderProcessGone,
            // which rebuilds the tabs. If there is no renderer to end, a plain
            // reload is the next best thing.
            val r = (renderer as? android.webkit.WebViewRenderProcess)
                ?: view?.webViewRenderProcess ?: activeWeb()?.webViewRenderProcess
            val ended = try { r?.terminate() == true } catch (e: Exception) { false }
            if (!ended) view?.takeIf { isLiveWeb(it) }?.reload()
        } else {
            DebugLog.add { "user restarted Search for stuck pages" }
            restartBrowser()
        }
    }

    /**
     * Ends this process and opens Search again in a new one (RestartActivity).
     * The tabs are written to disk first and restored on the way back up.
     */
    private fun restartBrowser() {
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
    private fun expectPageStart(tab: Tab, afterMs: Long = 4000L) {
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
    private fun liveTabBudget(): Int {
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

    /**
     * When Android says memory is running short, background tabs are frozen
     * before it decides to take the renderer - and with it every tab - by
     * force. Not on simply leaving the app (TRIM_MEMORY_UI_HIDDEN): only when
     * memory is low while running, or the app is on the list to be killed.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val low = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level < android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        val background = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        // Memory getting short, but not short yet: only the background tabs
        // holding heavy pages (PageMetrics) give theirs up. They cost the
        // most to keep and are the likeliest to tip the renderer over.
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            HealthStats.count(this, HealthStats.MEMORY_MODERATE)
            DebugLog.add { "memory trim level " + level + ": freezing heavy background tabs" }
            tabs.freezeBackground { it.metrics?.weight == PageMetrics.Weight.HEAVY }
            return
        }
        if (!low && !background) return
        if (low) HealthStats.count(this, HealthStats.MEMORY_LOW)
        DebugLog.add { "memory trim level " + level + ": freezing background tabs" }
        tabs.trimLive(1)
        // What only saves a moment: icons are on disk too, and a closed tab
        // can still come back by its address without its history.
        SiteIcons.trimMemory()
        closedTabs.forEach { it.state = null }
    }

    private fun openTab(tab: Tab, loadUrl: String? = null) {
        endPullRefresh(now = true)
        showBars(animate = false)
        schedulePageTint(350L)
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
    private fun shareLink(url: String, title: String) {
        if (url.isBlank()) return
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

    // Swaps the native top bar between the icon row (home / reload / tabs /
    // settings) and a single full-width search pill. Nothing is resized and the
    // WebView is never re-laid-out; only child visibility inside the existing
    // 58dp bar changes, so the page cannot jump or reflow while scrolling.
    private var homeBarAnim: android.animation.Animator? = null
    private var homeBarSeq = 0
    // Where the row actually is: 1f icons full, 0f fully collapsed. Kept here
    // because the row's width cannot answer it - snapHomeBar restores every
    // width to full and encodes the collapsed state in visibility instead.
    private var homeBarProgress = 1f
    private var homeRowWidths: List<Int>? = null

    /**
     * The views that give up their width and fade as the bar collapses: the
     * owl and the gear. Once home's search box has scrolled away it takes the
     * top on its own, as wide as it is on the page.
     */
    private fun homeBarRow(): List<View> = listOf(binding.homeBtn, binding.settingsBtn)

    // Natural widths live in the layout params, which keep their fixed dp even
    // while the view is collapsed to zero, so they survive the transition.
    private fun homeRowWidths(): List<Int> {
        var w = homeRowWidths
        if (w == null) {
            w = homeBarRow().map { it.layoutParams.width }
            if (w.all { it > 0 }) homeRowWidths = w
        }
        return w
    }

    /**
     * One continuous position between the two bar states, where 1 is the full
     * icon row and 0 is the bare pill. The icons give up their width as they
     * fade, and because the pill is weighted it takes that space on the same
     * frame - so the two states cross over rather than one replacing the other.
     */
    private fun setHomeBarProgress(t: Float) {
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

    private fun snapHomeBar(compact: Boolean) {
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
    private fun resetHomeBarRow() {
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

    private fun applyHomeCompact(compact: Boolean, animate: Boolean = false) {
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

    private fun refreshOmniboxVisibility(url: String?) {
        val isHome = url == null || url == homePage
        resetHomeBarRow()
        if (!isHome) {
            // Leaving home: drop compact state and put the icon row back, in case
            // the user opened a link while the bar was collapsed.
            homeCompact = false
            if (!searchMode) {
                binding.homeBtn.visibility = View.VISIBLE
                binding.starBtn.visibility = View.VISIBLE
            }
        } else {
            // Landing on home (load or tab switch): the page may already be
            // scrolled, and no intersection change would fire on its own.
            homeCompact = false
            activeWeb()?.evaluateJavascript(
                "window.__syncHomeBar && window.__syncHomeBar()", null)
        }
        // Never while searching: a page that arrives behind the search sheet
        // - the home page loading, say - must not take away the field being
        // typed into.
        binding.urlBarContainer.visibility =
            if (isHome && !homeCompact && !searchMode) View.INVISIBLE else View.VISIBLE
        if (isHome) hideSiteIcon()
        else showSiteIcon(url, fetch = (activeWeb()?.progress ?: 0) >= 100)
        applyBarOverlap(isHome)
        refreshNav()
        refreshAdSlot()
        // Desktop mode is meaningless on the home page — reset it when we land
        // home so the next site opens as a normal mobile page.
        if (isHome && Settings.getBool(this, Settings.DESKTOP_MODE, false)) {
            Settings.setBool(this, Settings.DESKTOP_MODE, false)
            applyDesktopMode(false)
        }
    }

    // ---------- How far the page runs under the bottom bar ----------

    /** The layout's own overlap (the home page's), read once. */
    private var homeBarOverlap = -1
    private var barOverlapHome: Boolean? = null
    /** How far the bottom bar overlaps the page while it is in place (setPageUnderBars). */
    private var barRestOverlap = 0

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
    private fun applyBarOverlap(onHome: Boolean) {
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

    /**
     * Whether a tab may be frozen to stay under the live-tab cap.
     *
     * Freezing destroys the WebView, and a window.open() pair lives in its two
     * WebViews: the pop-up hands its result back through window.opener, then
     * closes itself, which is how every pop-up sign-in returns to the site. A
     * restored tab is a new page with no opener, so a sign-in pop-up rebuilt
     * from a frozen tab finishes on its callback page with nobody to tell and
     * no right to close - a blank page that never moves. Only reachable with
     * several tabs live at once, but checking email in another tab halfway
     * through a sign-up is exactly that.
     */
    private fun canFreezeTab(tab: Tab): Boolean {
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

    private fun freezeTab(tab: Tab) {
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

    private fun applyDesktopMode(on: Boolean) {
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

    /**
     * Hosts visited while Night Owl was on, so leaving can clear those and
     * leave every other site alone. In memory only - a list of privately
     * visited sites written to disk would defeat the point of the mode.
     */
    private val nightOwlHosts = linkedSetOf<String>()

    private fun enterNightOwl(openUrl: String = homePage) {
        nightOwl = true
        nightOwlHosts.clear()
        // The card goes now, and the ad it was bound to goes with it, so
        // nothing from before the private session stays behind it.
        //
        // No refreshAdSlot() here on purpose: addNewTab below reaches it
        // through openTab -> refreshOmniboxVisibility. Calling it here as well
        // only adds main-thread work between asking for the page and the page
        // being answered, which is the window that caused this morning's bug.
        binding.adSlot.removeAllViews()
        nativeAd?.destroy()
        nativeAd = null
        binding.nightOwlBadge.visibility = View.VISIBLE
        applyNightOwlChrome(true)
        // The private settings ride on the tab itself, in newWebView, so this
        // new tab is built with them rather than having them applied to the
        // tab being left behind.
        addNewTab(openUrl)
        android.widget.Toast.makeText(this,
            "Night Owl on — private browsing", android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Clears what the private session touched, and nothing else.
     *
     * This used to call WebStorage.deleteAllData() and removeSessionCookies()
     * with no argument. Both are global: turning private browsing off wiped
     * localStorage for every site the user had ever visited and signed them
     * out of sessions that had nothing to do with Night Owl. Leaving a mode is
     * not consent to lose the data from outside it.
     *
     * A caveat worth stating plainly: WebView keeps one cookie jar for the
     * whole process, so this is a clean-up rather than an isolated session.
     * Cookies are expired per host, which cannot reach one scoped to a parent
     * domain. Properly isolating a private session needs a separate WebView
     * data directory in its own process, which the app does not have.
     */
    private fun exitNightOwl() {
        nightOwl = false
        val hosts = nightOwlHosts.toSet()
        nightOwlHosts.clear()

        // On the cookie thread: getCookie and setCookie each wait on Chromium
        // for an answer, two per host visited, and on the main thread a long
        // private session made leaving it an ANR.
        cookieIo.execute {
            // A WebView being updated by Play can refuse getInstance; on this
            // thread an uncaught throw would end the app.
            val cookies = try { android.webkit.CookieManager.getInstance() } catch (e: Throwable) { return@execute }
            hosts.forEach { host ->
                listOf("https://" + host, "http://" + host).forEach { origin ->
                    try {
                        cookies.getCookie(origin)?.split(";")?.forEach { pair ->
                            val name = pair.substringBefore('=').trim()
                            if (name.isNotEmpty()) {
                                cookies.setCookie(origin, name + "=; Max-Age=0; Path=/")
                            }
                        }
                    } catch (e: Exception) { /* nothing stored for this one */ }
                }
            }
            try { cookies.flush() } catch (e: Exception) {}
        }

        // Storage is addressable per origin, so this part is exact. getOrigins
        // reports them in WebView's own spelling, which is why they are matched
        // by host rather than rebuilt by hand.
        try {
            val storage = android.webkit.WebStorage.getInstance()
            storage.getOrigins { map ->
                map?.keys?.forEach { key ->
                    val origin = key?.toString() ?: return@forEach
                    val host = try {
                        android.net.Uri.parse(origin).host
                    } catch (e: Exception) { null }
                    if (host != null && hosts.contains(host)) {
                        try { storage.deleteOrigin(origin) } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) { /* storage unavailable; cookies already cleared */ }

        // The private tab's own cache. clearCache is process-wide, so it is
        // aimed at the tab that was private rather than fired blindly.
        activeWeb()?.clearCache(false)

        binding.nightOwlBadge.visibility = View.GONE
        applyNightOwlChrome(false)
        // If we're on the home page, reload it so it drops the private empty-state
        // and shows the normal tiles/feed again immediately.
        val current = activeWeb()?.url
        if (current == null || current == homePage) loadInto(activeWeb(), homePage)
        // The private session is over, so the feed can carry an ad again.
        // Nothing was requested while it was on, so this is the first request
        // since - including the case where the app started in Night Owl and
        // has never asked for one at all.
        loadFeedAd()
        android.widget.Toast.makeText(this,
            "Night Owl off", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun applyNightOwlChrome(on: Boolean) {
        // Night Owl tabs always show the owl's night sky.
        binding.navOwl.privateMode = on
        // And keep the Night Owl wash: no page colours in its bars.
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

    private fun openDownloads() {
        openDeck()
        showDownloads()
    }

    private fun openMenu() {
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

    private var menuOpenSeq = 0

    // ---------- Menu groups ----------
    //
    // Similar actions share one row, so the menu is six rows under Back,
    // Forward and Reload and their hairline instead of seventeen; tapping a
    // row opens a strip of its actions under it - swiped sideways when there
    // are more than fit - and closes any other.

    private val menuGroups by lazy {
        listOf(
            binding.menuGroupTabs to binding.menuStripTabs,
            binding.menuGroupPage to binding.menuStripPage,
            binding.menuGroupLibrary to binding.menuStripLibrary,
            binding.menuGroupShare to binding.menuStripShare,
            binding.menuGroupMore to binding.menuStripMore)
    }

    private fun setupMenuGroups() {
        menuGroups.forEach { (row, strip) ->
            row.setOnClickListener {
                Haptics.tap(it)
                showMenuGroup(if (strip.visibility == View.VISIBLE) null else strip, animate = true)
            }
            // Read out as a button that says whether its group is open.
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View, info: android.view.accessibility.AccessibilityNodeInfo
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                    if (Build.VERSION.SDK_INT >= 30) {
                        info.stateDescription = if (strip.visibility == View.VISIBLE) "Expanded" else "Collapsed"
                    }
                }
            }
        }
    }

    /** Opens [strip]'s group and closes the others; null closes them all. */
    private fun showMenuGroup(strip: View?, animate: Boolean) {
        val moving = animate && Motion.animationsOn() && binding.menuScrim.visibility == View.VISIBLE
        if (moving) {
            android.transition.TransitionManager.beginDelayedTransition(binding.menuScrim,
                android.transition.AutoTransition().setDuration(200L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()))
        }
        menuGroups.forEach { (row, s) ->
            val open = s === strip
            if (open && s.visibility != View.VISIBLE) s.scrollTo(0, 0)
            s.visibility = if (open) View.VISIBLE else View.GONE
            val chevron = row.findViewWithTag<View>("chevron")
            val turn = if (open) 180f else 0f
            if (chevron != null) {
                if (moving) chevron.animate().rotation(turn).setDuration(200L).start()
                else { chevron.animate().cancel(); chevron.rotation = turn }
            }
        }
        // On a short screen the rows scroll: bring the opened strip into view.
        if (strip != null) {
            binding.menuScroll.postDelayed({
                if (strip.visibility == View.VISIBLE) {
                    strip.requestRectangleOnScreen(android.graphics.Rect(0, 0, strip.width, strip.height), !moving)
                }
            }, if (moving) 220L else 0L)
        }
    }

    private fun endMenuLayer() {
        binding.menuPanel.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    // ---------- Menu motion ----------
    //
    // The menu opens and closes on a spring, growing from the Menu button's
    // corner with a little give. It can also be dragged down to close: the
    // panel follows the finger, the dimming lightens with it, and on release
    // it drops away or bounces back - by how far it went, or how fast.

    private val menuSpring by lazy {
        Spring(stiffness = 460f, dampingRatio = 0.72f, precision = 0.002f) { placeMenu(it) }
    }
    private val menuDragSpring by lazy {
        Spring(stiffness = 420f, dampingRatio = 0.86f, precision = 0.5f) { dragMenu(it) }
    }

    private fun placeMenu(p: Float) {
        val panel = binding.menuPanel
        panel.pivotX = panel.width.toFloat()
        panel.pivotY = panel.height.toFloat()
        val scale = 0.86f + 0.14f * p
        panel.scaleX = scale
        panel.scaleY = scale
        // The panel and the dimming fade separately. Fading the whole scrim
        // made every frame redraw the screen-sized dimming with the menu in it
        // off screen first; this way each is one cheap step.
        val a = p.coerceIn(0f, 1f)
        panel.alpha = a
        binding.menuScrim.background?.alpha = (255f * a).toInt()
    }

    private fun dragMenu(y: Float) {
        binding.menuPanel.translationY = y
        val h = binding.menuPanel.height.toFloat().coerceAtLeast(1f)
        // Only the dimming lightens; the panel itself stays solid.
        binding.menuScrim.background?.alpha = (255f * (1f - y / (h * 1.3f)).coerceIn(0f, 1f)).toInt()
    }

    private fun setupMenuDrag() {
        val panel = binding.menuPanel
        panel.canDrag = { !binding.menuScroll.canScrollVertically(-1) && !menuSpring.isRunning }
        panel.onDrag = { dy ->
            menuDragSpring.cancel()
            // Upward it only gives a little.
            val give = 24f * resources.displayMetrics.density
            dragMenu(if (dy >= 0f) dy else -give * (1f - kotlin.math.exp(dy / give)))
        }
        panel.onRelease = { dy, vy ->
            val y = panel.translationY
            val h = panel.height.toFloat()
            val fast = 900f * resources.displayMetrics.density
            if (dy > 0f && (y > h * 0.28f || vy > fast)) {
                val away = h + 80f * resources.displayMetrics.density
                menuDragSpring.animate(y, away, vy) { closeMenuNow() }
            } else {
                menuDragSpring.animate(y, 0f, vy)
            }
        }
    }

    private fun closeMenu() {
        menuDragSpring.cancel()
        // An open still waiting for its first frame must not start after this.
        menuOpenSeq++
        menuSpring.animate(menuSpring.value, 0f) { closeMenuNow() }
    }

    /** Instant close (no fade) for when a menu item's action follows immediately,
     *  so the panel doesn't linger see-through over the page during the action. */
    private fun closeMenuNow() {
        menuSpring.cancel()
        menuDragSpring.cancel()
        menuOpenSeq++
        endMenuLayer()
        binding.menuScrim.visibility = View.GONE
        binding.menuScrim.alpha = 1f
        binding.menuScrim.background?.alpha = 255
        binding.menuPanel.scaleX = 1f
        binding.menuPanel.scaleY = 1f
        binding.menuPanel.alpha = 1f
        binding.menuPanel.translationY = 0f
    }

    private fun addNewTab(loadUrl: String = homePage) {
        val tab = tabs.createTab(loadUrl)
        openTab(tab, loadUrl)
        if (loadUrl == homePage) expectPageStart(tab)
    }

    // ---------- Tab deck ----------

    // ---------- Find in page ----------
    private var findActive = false

    private fun openFindBar() {
        val web = activeWeb() ?: return
        showBars()
        findActive = true
        binding.findBar.visibility = View.VISIBLE
        binding.findInput.text?.clear()
        binding.findCount.text = ""
        binding.findInput.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.findInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFindBar() {
        findActive = false
        binding.findBar.visibility = View.GONE
        activeWeb()?.clearMatches()
        hideKeyboard()
    }

    // ---------- Native feed ad ----------

    // Debug builds ask for Google's native test unit. Live ads in a build
    // being tested, and taps on them, are invalid traffic - the most common
    // way an AdMob account gets suspended.
    private val feedAdUnit = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/2247696110"
        else "ca-app-pub-9121922395304175/6184493298"

    private var nativeAd: com.google.android.gms.ads.nativead.NativeAd? = null

    private var lastWebScroll = 0L

    /** MobileAds.initialize has finished. Loading before it has is not valid. */
    private var adsReady = false
    /** MobileAds.initialize has been asked for (startAdsSdk). */
    private var adsStarted = false
    /** MobileAds.initialize has been called and returned: the app open ad may be asked for. */
    @Volatile private var adsSdkCalled = false
    /**
     * When the ad on the card arrived, on the clock that keeps running while
     * the phone sleeps. A card older than an hour is replaced. Uptime stops
     * in deep sleep, so a card left overnight read as minutes old and stayed
     * up long after AdMob had stopped counting it.
     */
    private var nativeAdAt = 0L
    /** When the last request went out (elapsed real time): no new one sooner than AD_MIN_GAP_MS after it. */
    private var lastAdAttemptAt = 0L
    /** The last request's error code, or -1: a set-up fault (1, 8) is not asked again by itself. */
    private var lastAdError = -1
    /** Launched into Night Owl, which is not on yet: no request (see onCreate). */
    private var nightOwlLaunchPending = false
    private val AD_MIN_GAP_MS = 30_000L
    /** The consent update is out; a resume while it is does not send another. */
    private var consentInFlight = false
    /** The card has been on screen with its current ad (counted once per ad). */
    private var adSeen = false
    /**
     * The ad's place in home's feed, as the page last reported it
     * (SearchApp.adSlotAt), in CSS px from the top-left of the document;
     * top -1 when the page has no place for it. [adSlotFrom] is the WebView
     * that reported: another tab's home page must not place the card.
     */
    private var adSlotFrom: android.webkit.WebView? = null
    private var adSlotTopCss = -1
    private var adSlotLeftCss = 0
    private var adSlotWidthCss = 0
    private var adSlotHCss = 0
    /** The card has faded in over its place (reset whenever it is parked). */
    private var adCardShown = false
    private val adLocWeb = IntArray(2)
    private val adLocSlot = IntArray(2)
    /**
     * Failed requests in a row this session. A failure used to be the end of
     * it: one empty answer at launch - routine for a new ad unit, or with no
     * signal - and the feed had no ad until the app was next started. Now it
     * asks again after a growing pause (AD_RETRY_MS), a few times at most,
     * and once more when the network comes back.
     */
    private var adFailures = 0
    private val adRetry = Runnable { loadFeedAd() }
    private val AD_RETRY_MS = longArrayOf(30_000L, 120_000L, 300_000L, 900_000L)
    /**
     * A request is in flight.
     *
     * Leaving Night Owl asks for an ad, so without this a quick off/on/off
     * would put two requests out at once. Cleared on both outcomes - the SDK
     * calls exactly one of forNativeAd or onAdFailedToLoad for every request.
     */
    private var adLoading = false

    private fun initAds() {
        // Gestures are mirrored onto the page so it scrolls under the card;
        // only the tap that halts a fling is withheld.
        binding.adSlot.page = { activeWeb() }
        // The card's height follows its width (set from the page) and its
        // creative; whenever it changes, the page's place for it must too.
        binding.adSlot.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && v.visibility == View.VISIBLE) {
                v.post { if (!isFinishing && !isDestroyed) syncAdSlotReserve() }
            }
        }
        binding.adSlot.blocked = {
            android.os.SystemClock.uptimeMillis() - lastWebScroll < 300L
        }
        // Supporters paid for the ads to be gone, so the SDK is never started
        // rather than started and never asked - and with no ads there is no
        // consent to ask for. Initialisation makes network calls of its own;
        // "gone" should mean gone.
        if (Settings.getBool(this, Settings.IS_SUPPORTER, false)) {
            HealthStats.noteAdGate(this, "supporter (ads bought away)")
            return
        }
        gatherConsentThenStart()
    }

    /**
     * Consent first: where the law requires an answer, the AdMob message is
     * shown and the SDK is not started until there is one. Someone who
     * already answered in an earlier session can be asked for ads at once,
     * in parallel with the update, as Google's UMP guide does - otherwise
     * every launch waited for a network round trip before even starting the
     * SDK, and a launch with a slow or no connection showed no ad all session.
     */
    private fun gatherConsentThenStart() {
        if (consentInFlight) return
        consentInFlight = true
        AdConsent.gather(this, onFormNeeded = {
            // A consent message has to be answered: it must be seen, so the
            // splash makes way, and this launch goes without the app open ad.
            splashAdWanted = false
            releaseSplash()
        }) { allowed ->
            consentInFlight = false
            if (isFinishing || isDestroyed) return@gather
            // Why not, when not, is written by AdConsent itself, with the
            // form's or the server's error where there was one.
            if (allowed) startAdsSdk()
            // The splash may be waiting on this answer before asking for the ad.
            continueSplashAd()
        }
        // Outside the regions that require consent, and wherever an earlier
        // answer allows it, the SDK starts now, alongside the check.
        if (AdConsent.adsAllowed(this)) startAdsSdk()
    }

    // Initialization does disk work and can take a moment, so it runs off the
    // main thread per the SDK guide - otherwise it lands squarely in cold
    // start. Loading is bounced back to the main thread, where it must run.
    private fun startAdsSdk() {
        // Once: the consent answer and the parallel start can both get here.
        if (adsStarted) return
        adsStarted = true
        Thread {
            com.google.android.gms.ads.MobileAds.initialize(this) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    adsReady = true
                    // Not gated on Night Owl here: loadFeedAd makes that call,
                    // so initialization finishing DURING a private session is
                    // handled by the same test as every other path in.
                    loadFeedAd()
                }
            }
            // The app open ad is asked for as soon as the SDK has been started
            // - not when it reports it has finished starting, which can take
            // longer than the splash: waiting for that is why the ad used to
            // arrive too late for the launch it was asked for and only showed
            // on the next one. With no mediation there is nothing to wait for,
            // as Google's own app open sample does.
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adsSdkCalled = true
                AppOpenAds.sdkReady()
            }
        }.start()
    }

    /**
     * Asks for the one feed ad this session shows.
     *
     * Safe to call from anywhere: every reason not to ask is tested here rather
     * than at the call sites, so no caller has to remember them.
     */
    private fun loadFeedAd(replace: Boolean = false) {
        uiHandler.removeCallbacks(adRetry)
        // Night Owl: no request at all. The card being hidden is not the point
        // - an ad request carries device signals for targeting, and a private
        // session is exactly when those should not be sent.
        if (nightOwl || nightOwlLaunchPending) { HealthStats.noteAdGate(this, "Night Owl was on"); return }
        // Supporters bought the feed back. Tested before the request rather
        // than at bind time: an ad request carries device signals for targeting
        // whether or not the card is ever shown.
        if (Settings.getBool(this, Settings.IS_SUPPORTER, false)) return
        if (!adsReady || adLoading) return
        // Never without consent where it is required. Checked here as well as
        // at startup because this is reached from other paths - leaving Night
        // Owl - and the answer can change in Settings, Ad privacy choices.
        if (!AdConsent.adsAllowed(this)) {
            HealthStats.noteAdGate(this, "consent does not allow ads yet")
            return
        }
        // One ad at a time. Without this, leaving Night Owl a second time
        // would ask again for a card that is already on screen. [replace]
        // asks for a new one to take the place of a card over an hour old,
        // which AdMob counts as expired; the old one stays until it arrives.
        if (nativeAd != null && !replace) return
        // The feed ad belongs to the feed, which only Explore has.
        if (browsingMode != BrowsingMode.EXPLORE) {
            HealthStats.noteAdGate(this, "home is in " + BrowsingMode.title(browsingMode) + " (no feed)")
            return
        }
        adLoading = true
        lastAdAttemptAt = android.os.SystemClock.elapsedRealtime()
        HealthStats.count(this, HealthStats.AD_REQUESTS)
        com.google.android.gms.ads.AdLoader.Builder(this, feedAdUnit)
            .forNativeAd { ad ->
                adLoading = false
                if (isFinishing || isDestroyed) { ad.destroy(); return@forNativeAd }
                // Night Owl was turned on while this was in flight. The request
                // is already out and cannot be recalled, but the card does not
                // appear and nothing is kept.
                if (nightOwl) { ad.destroy(); return@forNativeAd }
                HealthStats.count(this, HealthStats.AD_FILLED)
                adFailures = 0
                lastAdError = -1
                nativeAd?.destroy()
                nativeAd = ad
                nativeAdAt = android.os.SystemClock.elapsedRealtime()
                adSeen = false
                bindFeedAd(ad)
            }
            .withAdListener(object : com.google.android.gms.ads.AdListener() {
                override fun onAdFailedToLoad(e: com.google.android.gms.ads.LoadAdError) {
                    // Code 3 is no-fill, which a new unit does for hours and is
                    // not a wiring fault. 0 internal, 1 invalid request (bad unit
                    // id or app id mismatch), 2 network.
                    adLoading = false
                    HealthStats.count(this@MainActivity, HealthStats.AD_FAILED)
                    HealthStats.noteAdError(this@MainActivity, e.code, e.message)
                    // A card already up (this was a replacement) stays up.
                    if (nativeAd == null) binding.adSlot.visibility = View.GONE
                    // Asked again later, a few times, with a growing pause -
                    // never in a tight loop, which AdMob treats as abuse. Not
                    // after an invalid request (1) or a missing app ID (8):
                    // those are set-up faults that the same request repeats.
                    lastAdError = e.code
                    val fixable = e.code != 1 && e.code != 8
                    // A failed replacement leaves the old card up and is not
                    // chased: the next return to home after the spacing asks again.
                    if (!replace) {
                        if (fixable && adFailures < AD_RETRY_MS.size && !isFinishing && !isDestroyed) {
                            uiHandler.postDelayed(adRetry, AD_RETRY_MS[adFailures])
                        }
                        adFailures++
                    }
                    // Debug only. Without this a missing ad and a broken one
                    // look identical from the phone, which is no way to tell
                    // routine no-fill from a wiring fault. Never in release:
                    // no-fill is normal and a user can do nothing about it.
                    if (BuildConfig.DEBUG) {
                        // Logged, not toasted. Code 3 is no-fill and happens
                        // constantly on a fresh unit; a toast over the feed
                        // every time is a nuisance of its own.
                        android.util.Log.d("Ads", "failed \u00b7 code " + e.code + " \u00b7 " + e.message)
                    }
                }            })
            // Muted is the SDK default, but stated outright: a video ad that
            // opens with sound in a browser home feed is unforgivable.
            .withNativeAdOptions(
                com.google.android.gms.ads.nativead.NativeAdOptions.Builder()
                    .setVideoOptions(
                        com.google.android.gms.ads.VideoOptions.Builder()
                            .setStartMuted(true).build())
                    .build())
            .build()
            .loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
    }

    /** Back online: the ad flow picks up wherever it was left. */
    private fun retryAdsAfterReconnect() {
        if (isFinishing || isDestroyed || nightOwl) return
        if (Settings.getBool(this, Settings.IS_SUPPORTER, false)) return
        when {
            !adsStarted -> gatherConsentThenStart()
            // Spaced, so a connection dropping in and out does not become a
            // request every few seconds; never after a set-up fault (1, 8);
            // the backoff starts over only when the last failure was the
            // network's (2), which is what coming back online fixes.
            adsReady && nativeAd == null && !adLoading && lastAdError != 1 && lastAdError != 8 &&
                android.os.SystemClock.elapsedRealtime() - lastAdAttemptAt >= AD_MIN_GAP_MS -> {
                if (lastAdError == 2) adFailures = 0
                loadFeedAd()
            }
        }
    }

    /** A card over an hour old is replaced when home is shown again, as AdMob asks. */
    private fun refreshStaleFeedAd() {
        if (nativeAd == null || adLoading || !adsReady) return
        if (android.os.SystemClock.elapsedRealtime() - nativeAdAt < 60L * 60_000L) return
        // A replacement that came back empty is not asked again on every
        // return to home: five minutes apart at least.
        if (android.os.SystemClock.elapsedRealtime() - lastAdAttemptAt < 5L * 60_000L) return
        loadFeedAd(replace = true)
    }

    /**
     * Back in the app with no card: the flow picks up rather than staying
     * empty until the next cold start. The SDK not started yet (consent could
     * not be settled offline at launch) is started now; a card that failed
     * and ran out of retries is asked for again, at most every 15 minutes.
     */
    private fun resumeFeedAd() {
        if (isFinishing || isDestroyed || nightOwl) return
        if (Settings.getBool(this, Settings.IS_SUPPORTER, false)) return
        if (!hasNetwork()) return
        when {
            !adsStarted -> gatherConsentThenStart()
            adsReady && nativeAd == null && !adLoading && lastAdError != 1 && lastAdError != 8 &&
                android.os.SystemClock.elapsedRealtime() - lastAdAttemptAt > 15L * 60_000L -> {
                adFailures = 0
                loadFeedAd()
            }
        }
    }

    /**
     * Every asset is handed to the SDK through NativeAdView, which is what makes
     * impressions and clicks countable. Drawing these values anywhere else - into
     * the page, for instance - renders the same pixels and earns nothing.
     */
    private fun bindFeedAd(ad: com.google.android.gms.ads.nativead.NativeAd) {
        val view = layoutInflater.inflate(
            R.layout.native_ad_feed_card, binding.adSlot, false)
            as com.google.android.gms.ads.nativead.NativeAdView
        val headline = view.findViewById<android.widget.TextView>(R.id.adHeadline)
        headline.text = ad.headline
        view.headlineView = headline

        // Full width, as tall as the creative's shape (AdMediaFrame clamps it).
        val frame = view.findViewById<AdMediaFrame>(R.id.adMediaFrame)
        frame.ratio = ad.mediaContent?.aspectRatio ?: (16f / 9f)
        val media = view.findViewById<com.google.android.gms.ads.nativead.MediaView>(R.id.adMedia)
        media.setImageScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
        ad.mediaContent?.let { media.mediaContent = it }
        view.mediaView = media

        val body = view.findViewById<android.widget.TextView>(R.id.adBody)
        val bodyText = ad.body
        if (!bodyText.isNullOrBlank()) {
            body.text = bodyText
            body.visibility = View.VISIBLE
            view.bodyView = body
        }

        // The advertiser in the publisher's place. With none given, a plain
        // "Sponsored" stands there - not registered, as it is not an asset.
        val advertiser = view.findViewById<android.widget.TextView>(R.id.adAdvertiser)
        val who = ad.advertiser ?: ad.store
        if (!who.isNullOrBlank()) {
            advertiser.text = who
            view.advertiserView = advertiser
        } else advertiser.text = "Sponsored"

        // The call to action and the "Ad" label in the accent, as the feed's
        // own buttons are; the Ink accent on a dark card would vanish, so it
        // turns light there.
        var accent = try { android.graphics.Color.parseColor(Settings.getHomeAccent(this)) }
            catch (e: Exception) { 0xFF8B6BD8.toInt() }
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (night && android.graphics.Color.luminance(accent) < 0.08f) accent = 0xFFF4F3F7.toInt()
        val onAccent = if (android.graphics.Color.luminance(accent) > 0.6f)
            0xFF16151B.toInt() else android.graphics.Color.WHITE
        val tint = android.content.res.ColorStateList.valueOf(accent)

        val cta = view.findViewById<android.widget.TextView>(R.id.adCta)
        cta.text = ad.callToAction
        cta.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
        cta.backgroundTintList = tint
        cta.setTextColor(onAccent)
        view.callToActionView = cta

        val badge = view.findViewById<android.widget.TextView>(R.id.adBadge)
        badge.backgroundTintList = tint
        badge.setTextColor(onAccent)

        val icon = view.findViewById<android.widget.ImageView>(R.id.adIcon)
        val art = ad.icon?.drawable
        if (art != null) {
            icon.setImageDrawable(art)
            icon.visibility = View.VISIBLE
            icon.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            icon.clipToOutline = true
            view.iconView = icon
        } else icon.visibility = View.GONE

        view.setNativeAd(ad)
        binding.adSlot.removeAllViews()
        binding.adSlot.addView(view)
        // A new card fades in afresh once its place has opened to its size.
        parkAdSlot()
        refreshAdSlot()
    }

    /**
     * A story's menu on home, as Discover's three dots offer: open it in the
     * background or in Night Owl, copy or share it, or see less - this story,
     * or everything from its publisher (FeedHidden; Undo on the snackbar).
     */
    private fun showStoryMenu(link: String, title: String, source: String, domain: String) {
        if (isFinishing || isDestroyed) return
        val name = source.ifBlank { domain }.ifBlank { "this site" }
        val labels = mutableListOf("Open in new tab", "Open in Night Owl tab", "Copy link", "Share\u2026",
            "Not interested in this story")
        val canHideSource = domain.isNotBlank()
        if (canHideSource) labels.add("Hide stories from $name")
        Haptics.tick(binding.root)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
            .setTitle(title.ifBlank { name })
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> addBackgroundTab(link)
                    1 -> enterNightOwl(link)
                    2 -> copyText("Link", link)
                    3 -> shareLink(link, title)
                    4 -> {
                        FeedHidden.hideLink(this, link)
                        hideOnHome("link", link)
                        toast("You'll see less like this")
                    }
                    5 -> if (canHideSource) {
                        FeedHidden.hideSource(this, domain)
                        hideOnHome("source", domain)
                        com.google.android.material.snackbar.Snackbar
                            .make(binding.root, "Stories from $name hidden", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            .setAction("Undo") {
                                FeedHidden.showSource(this, domain)
                                // Back on the next load of the feed.
                                activeWeb()?.takeIf { it.url == homePage }?.evaluateJavascript(
                                    "window.__reloadFeedOnline && window.__reloadFeedOnline()", null)
                            }
                            .show()
                    }
                }
            }
            .show()
    }

    private fun hideOnHome(kind: String, value: String) {
        val web = activeWeb() ?: return
        if (web.url != homePage) return
        web.evaluateJavascript("window.__hideStories && window.__hideStories(" +
            org.json.JSONObject.quote(kind) + "," + org.json.JSONObject.quote(value) + ")", null)
    }

    /** The page reported where the ad's place is (SearchApp.adSlotAt). */
    private fun onAdSlotReported(from: SearchAppBridge, top: Int, left: Int, width: Int, height: Int) {
        if (isFinishing || isDestroyed) return
        val web = activeWeb() ?: return
        // A home page in a tab that is not on screen does not place the card.
        if (web.tag !== from) return
        adSlotFrom = web
        adSlotTopCss = top
        adSlotLeftCss = left
        adSlotWidthCss = width
        adSlotHCss = height
        fitAdSlotWidth(web)
        if (binding.adSlot.visibility == View.VISIBLE && isLiveWeb(web)) positionAdSlot(web, web.scrollY)
    }

    /** The card as wide as its place in the feed (the feed's column, on a tablet too). */
    @Suppress("DEPRECATION") // getScale(): see positionAdSlot.
    private fun fitAdSlotWidth(web: android.webkit.WebView) {
        if (adSlotWidthCss <= 0 || !isLiveWeb(web)) return
        val scale = web.scale.takeIf { it > 0f } ?: resources.displayMetrics.density
        val w = Math.round(adSlotWidthCss * scale)
        val lp = binding.adSlot.layoutParams ?: return
        if (lp.width != w) {
            lp.width = w
            binding.adSlot.layoutParams = lp
        }
    }

    /** Out of sight below the page, and faded out, until it has a place to go. */
    private fun parkAdSlot() {
        binding.adSlot.translationY = adSlotParkedY()
        binding.adSlot.animate().cancel()
        binding.adSlot.alpha = 0f
        adCardShown = false
    }

    // Far enough down to be off screen whatever the card's height, so a slot
    // that is switched on before its place is known shows nothing at all.
    private fun adSlotParkedY(): Float =
        (binding.root.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels).toFloat()

    // WebView.getScale() has been deprecated since API 17 and still works on
    // 36, with no removal announced. The replacement is tracking scale through
    // onScaleChanged, which means reworking the arithmetic that places the ad
    // card - the one piece of this app that earns anything. A rewrite there
    // buys a quieter build log and risks the revenue, so the warning is
    // acknowledged here instead of silenced by accident.
    @Suppress("DEPRECATION")
    /**
     * Is this WebView still one the app owns, or has it been destroyed?
     *
     * Both destroy paths drop every reference on the way out - freezeTab nulls
     * tab.webView, closeTabFromDeck removes the tab entirely - so identity
     * against the live tabs is an exact test for "not destroyed". No flag to
     * maintain, and a third destroy site added later is covered for free.
     *
     * This matters because calling into a destroyed WebView is not a
     * recoverable error. Chromium fails a CHECK() and aborts the process:
     * SIGTRAP in libwebviewchromium.so, with nothing in the Java stack to
     * point at.
     */
    private fun isLiveWeb(web: android.webkit.WebView): Boolean =
        !isFinishing && !isDestroyed && tabs.tabs.any { it.webView === web }

    /**
     * Lays the card over its place in the feed: the place's top in the
     * document (CSS px, from the page) scaled to the screen, less how far the
     * page has scrolled, plus where the page itself sits - which moves while
     * the bars slide or a tab is swiped aside. Called on every scroll of the
     * page, so it does no layout of its own, only translation.
     */
    private fun positionAdSlot(web: android.webkit.WebView, scrollY: Int) {
        // Belt as well as braces: the posted callers check this before
        // calling, and this covers the scroll listener and anything added
        // later without either having to remember.
        if (!isLiveWeb(web)) return
        if (binding.adSlot.visibility != View.VISIBLE) return
        // Only ever against the home page on screen, and only at the place
        // that page reported. A pass posted for one page, or a scroll from a
        // tab in the background, would otherwise seat the card over a site.
        if (web !== activeWeb() || web.url != homePage || adSlotFrom !== web || adSlotTopCss < 0) {
            parkAdSlot()
            return
        }
        // contentHeight reads 0 while the page lays out; nothing to place
        // against until it has one.
        if (web.contentHeight <= 0) { parkAdSlot(); return }
        val scale = web.scale.takeIf { it > 0f } ?: return
        val frame = binding.adSlot.parent as? View ?: return
        web.getLocationInWindow(adLocWeb)
        frame.getLocationInWindow(adLocSlot)
        binding.adSlot.translationX = (adLocWeb[0] - adLocSlot[0]) + adSlotLeftCss * scale
        binding.adSlot.translationY = (adLocWeb[1] - adLocSlot[1]) + adSlotTopCss * scale - scrollY
        // Shown once its place has opened to the card's height; until then
        // the card would lie over the story below it.
        val h = binding.adSlot.height
        val ready = h > 0 && adSlotHCss * scale >= h - 3f * resources.displayMetrics.density
        if (ready && !adCardShown) {
            adCardShown = true
            binding.adSlot.animate().cancel()
            if (Motion.animationsOn()) {
                binding.adSlot.animate().alpha(1f).setDuration(220L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            } else binding.adSlot.alpha = 1f
        } else if (!ready && adCardShown) {
            adCardShown = false
            binding.adSlot.animate().cancel()
            binding.adSlot.alpha = 0f
        }
        // Counted once per ad when half of the card is in view, for the
        // numbers in Performance & health (the SDK counts real impressions).
        if (ready && !adSeen) {
            val y = binding.adSlot.translationY
            if (y < frame.height - h / 2f && y > -h / 2f) {
                adSeen = true
                HealthStats.count(this, HealthStats.AD_SEEN)
            }
        }
    }

    // Tells the page how tall the card is, so its place in the feed is
    // exactly that tall, and asks where that place is. Sent after layout,
    // since the card's height is not known until it has measured with a
    // creative in it, and again whenever that height changes.
    @Suppress("DEPRECATION") // See positionAdSlot: same getScale() call.
    private fun syncAdSlotReserve() {
        val web = activeWeb() ?: return
        if (!isLiveWeb(web)) return
        val scale = web.scale.takeIf { it > 0f } ?: 1f
        val visible = binding.adSlot.visibility == View.VISIBLE
        val h = binding.adSlot.height
        val css = if (visible && h > 0) Math.ceil((h / scale).toDouble()).toInt() else 0
        web.evaluateJavascript("window.__setAdSlot && window.__setAdSlot($css);" +
            "window.__adSlotWhere && window.__adSlotWhere();", null)
        if (visible) {
            // isLiveWeb before each, and outside positionAdSlot, so that even
            // the argument expression web.scrollY is not evaluated against a
            // WebView that has been destroyed in the meantime.
            binding.adSlot.post {
                if (isLiveWeb(web)) positionAdSlot(web, web.scrollY)
            }
            // The page answers through the bridge, a frame or so later, and
            // opens the place over a third of a second; one late pass makes
            // sure the card is seated whatever the timing. It stays parked
            // until then, so the wait costs nothing on screen.
            binding.adSlot.postDelayed({
                if (isLiveWeb(web)) positionAdSlot(web, web.scrollY)
            }, 450L)
        }
    }

    /** The page the card was last placed against: its WebView and address. */
    private var adSlotPage: Pair<android.webkit.WebView?, String?>? = null

    // Home page only, and never over the search sheet or the tab deck.
    private fun refreshAdSlot() {
        val url = tabs.activeTab?.url
        // Both the tab and its WebView must say home. The tab's address is
        // written when a page arrives, and the WebView's moves first - to the
        // address being loaded as soon as the app loads one, to the new page
        // the moment it commits. Going by the tab alone, the card stayed up
        // over search results and sites until they finished loading.
        val web = activeWeb()
        val shown = web?.url
        val onHome = (url == null || url == homePage) && (shown == null || shown == homePage)
        val show = nativeAd != null && onHome && !searchMode &&
            !deckVisible && !nightOwl && browsingMode == BrowsingMode.EXPLORE
        val changed = (binding.adSlot.visibility == View.VISIBLE) != show
        // A different page under the card - another home tab, or home loaded
        // again - starts it parked too, rather than where the last one had it.
        val page = web to shown
        if (show && page != adSlotPage) {
            parkAdSlot()
            // That page's place is not known until it says (__adSlotWhere,
            // asked in syncAdSlotReserve).
            if (adSlotFrom !== web) adSlotTopCss = -1
        }
        adSlotPage = if (show) page else null
        // THE FLICKER. translationY 0 means "seated at the bottom of the
        // viewport", which is exactly where a fully scrolled page shows the
        // card. Switching the slot on before positionAdSlot has run therefore
        // paints it on screen for a frame, then shoves it down out of the way -
        // read as a flash on app open, because that is when the ad arrives.
        // Park it first; positionAdSlot brings it to wherever it belongs.
        if (show && changed) parkAdSlot()
        binding.adSlot.visibility = if (show) View.VISIBLE else View.GONE
        // Home again after a long stay on sites: an hour-old card is swapped
        // for a new one, the old staying up until it arrives.
        if (show && changed) refreshStaleFeedAd()
        // Re-sent whenever the card is up, not only on a change: a home reload
        // (accent change, private toggle, tab switch) drops the page's CSS
        // reserve back to 0 without the slot's visibility ever changing, and
        // the card then overlaps the last feed card.
        if (changed || show) binding.adSlot.post { syncAdSlotReserve() }
    }

    // ---------- Browsing modes (BrowsingMode) ----------

    @Volatile private var browsingMode = BrowsingMode.EXPLORE
    @Volatile private var zenShortcuts = false
    private var launchedForTask = false
    private var announcing = false

    /**
     * A new mode for the home page: every home page open in a tab recomposes
     * on the spot (window.__setMode) - no reload, nothing else touched - and
     * the feed ad follows: requested and shown in Explore, hidden otherwise.
     */
    private fun applyBrowsingMode(mode: String, zen: Boolean) {
        browsingMode = mode
        zenShortcuts = zen
        val js = "window.__setMode && window.__setMode(" + org.json.JSONObject.quote(mode) + "," + zen + ")"
        tabs.tabs.forEach { t ->
            val w = t.webView ?: return@forEach
            if (w.url == homePage) w.evaluateJavascript(js, null)
        }
        refreshAdSlot()
        if (mode == BrowsingMode.EXPLORE) loadFeedAd()
    }


    /**
     * Once after the upgrade that brought the modes: "Search has 3 modes now".
     * Only on home, once it is up, with nothing else in front - not while
     * typing, searching, in the tab deck, during the consent message or an
     * app open ad (the window has no focus then) or another dialog.
     */
    private fun maybeAnnounceModes() {
        if (announcing || isFinishing || isDestroyed) return
        if (!BrowsingMode.announcementDue(this)) return
        if (keepSplash || searchMode || deckVisible || findActive || nightOwl) return
        if (AdConsent.formShowing || !hasWindowFocus()) return
        if (activeWeb()?.url != homePage) return
        announcing = true
        BrowsingMode.markAnnounced(this)
        HealthStats.count(this, HealthStats.MODE_ANNOUNCED)
        // The owl dialogs' own design (as Play Games' welcome): the owl, the
        // accent, the pill buttons, the owl's entrance.
        val view = layoutInflater.inflate(R.layout.dialog_modes_new, null)
        applyOwlArtIn(view)
        val dialog = android.app.AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.OwlDialogAnim
        view.findViewById<android.widget.TextView>(R.id.modesGotIt).setOnClickListener {
            Haptics.tap(it)
            dialog.dismiss()
        }
        view.findViewById<android.widget.TextView>(R.id.modesChoose).setOnClickListener {
            Haptics.tap(it)
            dialog.dismiss()
            startActivity(android.content.Intent(this, SectionActivity::class.java)
                .putExtra(SectionActivity.EXTRA_SECTION, SectionActivity.SEC_MODE))
        }
        dialog.setOnDismissListener { announcing = false }
        dialog.show()
        dialog.window?.let { w ->
            val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    // ---------- The app open ad's questions (AppOpenAds) ----------

    /** Asked for and shown only where the feed ad may be: consent, no Night Owl, no supporter. */
    private val appOpenGate: () -> Boolean = {
        !isFinishing && !isDestroyed && adsSdkCalled && !nightOwl && !nightOwlLaunchPending &&
            !Settings.getBool(this, Settings.IS_SUPPORTER, false) &&
            AdConsent.adsAllowed(this) && !AdConsent.formShowing
    }

    // ---------- Opening through the app open ad: splash, ad, home ----------

    /**
     * Only a safeguard, for an ad request that never answers at all (the
     * SDK reports every failure, a lost connection included, long before
     * this): the splash and the cover wait for the ad's own answer, not for
     * a clock. Even then the request is not cancelled - its ad is kept for
     * the next open.
     */
    private val AD_WAIT_GUARD_MS = 15_000L
    private var splashAdWanted = false
    private var launchCover: View? = null
    private var coverAdShowing = false

    /**
     * The splash has been up its usual 1.5 s. On a plain launch it now stays
     * until the app open ad has loaded and is shown over it: first until the
     * consent check has answered (whether ads may be asked for is only known
     * then - on a fresh start it is not known from before), then until the
     * ad itself has loaded. Only a request that fails, a consent message
     * that has to be answered, or no ad being allowed lets the browser open
     * without the ad. Nothing is ever shown over home once it is up.
     */
    private fun onSplashMinimumDone() {
        if (isFinishing || isDestroyed) { keepSplash = false; return }
        if (introFromSplash) { introFromSplash = false; openIntroFromSplash(); return }
        splashAdWanted = keepSplash && coldLaunch && !launchedForTask && !nightOwl &&
            BrowsingMode.introDone(this) && !Settings.getBool(this, Settings.IS_SUPPORTER, false) &&
            hasNetwork() && AppOpenAds.wantedThisOpen()
        if (!splashAdWanted) { releaseSplash(); return }
        uiHandler.postDelayed(splashGuard, AD_WAIT_GUARD_MS)
        continueSplashAd()
    }

    /** The next step of the splash's wait: consent answered, then the ad loaded, then shown. */
    private fun continueSplashAd() {
        if (!keepSplash || !splashAdWanted) return
        // Where consent is required the consent check is waited for (this runs
        // again when it answers); everywhere else the ad does not wait on it.
        if (consentInFlight && AdConsent.regulated(this)) return
        if (!AdConsent.adsAllowed(this)) { splashAdWanted = false; releaseSplash(); return }
        if (AppOpenAds.readyNow()) { splashAd(); return }
        AppOpenAds.onResult = { loaded ->
            AppOpenAds.onResult = null
            if (keepSplash && splashAdWanted) {
                if (loaded) splashAd() else { splashAdWanted = false; releaseSplash() }
            }
        }
        // Asked for now if it can be; if the SDK is still starting, it asks
        // the moment it can (sdkReady), and the answer comes all the same.
        // With the SDK started and still no request out, one has just failed
        // (the next is not due for a while): nothing is coming to wait for.
        if (!AppOpenAds.requestNow() && adsSdkCalled) { splashAdWanted = false; releaseSplash() }
    }

    private val splashGuard = Runnable {
        if (keepSplash && splashAdWanted) { splashAdWanted = false; releaseSplash() }
    }
    private var coldLaunch = false

    private fun splashAd() {
        uiHandler.removeCallbacks(splashGuard)
        AppOpenAds.onResult = null
        splashAdWanted = false
        var onScreen = false
        // Some of AdMob's app open creatives are a card on a see-through
        // screen, and home showed through them. So the splash picture stays
        // behind the ad: the moment the ad is up, the same picture goes over
        // the browser (the launch cover, as on a return from the background)
        // and only then does the splash let go; closing the ad uncovers home.
        AppOpenAds.showNow(this, onShowing = {
            onScreen = true
            showLaunchCover()
            releaseSplash()
        }, onDone = {
            releaseSplash()
            hideLaunchCover()
        })
        // Should the SDK say nothing either way, the browser still opens; an
        // ad on screen keeps its cover until it is closed.
        uiHandler.postDelayed({ if (!onScreen) releaseSplash() }, 5000L)
    }

    private fun releaseSplash() {
        uiHandler.removeCallbacks(splashGuard)
        AppOpenAds.onResult = null
        if (!keepSplash) return
        keepSplash = false
    }

    /**
     * The introduction is started while the splash still stands, and the
     * splash is let go only once the introduction has covered this screen
     * (onStop), so the way in is splash, introduction, home - with no
     * glimpse of home in between.
     */
    private fun openIntroFromSplash() {
        releaseSplashOnStop = true
        try {
            startActivity(android.content.Intent(this, ModeIntroActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } catch (e: Exception) {
            releaseSplashOnStop = false
            releaseSplash()
            return
        }
        // Should the introduction not come up for any reason, the browser opens.
        uiHandler.postDelayed({ releaseSplashOnStop = false; releaseSplash() }, 6000L)
    }
    private var introFromSplash = false
    private var releaseSplashOnStop = false
    private var adsDeferred = false

    override fun onStop() {
        super.onStop()
        if (releaseSplashOnStop) { releaseSplashOnStop = false; releaseSplash() }
    }

    /**
     * Back to the browser from the background: the splash's picture goes up
     * over the browser before its first frame, the ad is shown over it -
     * at once if it is ready, or as soon as it has loaded - and closing the
     * ad uncovers home: the same splash, ad, home as a launch. Only when no
     * ad can come (none can be asked for right now, or the request fails)
     * does the browser open without one.
     */
    private fun warmOpenAd() {
        if (keepSplash || isFinishing || isDestroyed || fullscreenView != null) return
        if (!AppOpenAds.wantedThisOpen() || !appOpenGate()) return
        if (AppOpenAds.readyNow()) { showLaunchCover(); coverAd(); return }
        if (!AppOpenAds.requestNow()) return
        showLaunchCover()
        AppOpenAds.onResult = { loaded ->
            AppOpenAds.onResult = null
            if (launchCover != null) { if (loaded) coverAd() else hideLaunchCover() }
        }
        uiHandler.postDelayed(coverGuard, AD_WAIT_GUARD_MS)
    }

    private fun coverAd() {
        uiHandler.removeCallbacks(coverGuard)
        coverAdShowing = false
        AppOpenAds.showNow(this, onShowing = { coverAdShowing = true }, onDone = { hideLaunchCover() })
        uiHandler.postDelayed(coverSafety, 5000L)
    }

    private val coverGuard = Runnable {
        if (launchCover != null && !coverAdShowing) { AppOpenAds.onResult = null; hideLaunchCover() }
    }

    private val coverSafety = Runnable { if (!coverAdShowing) hideLaunchCover() }

    /** The splash picture (the owl on the splash colour) over the whole window. */
    private fun showLaunchCover() {
        if (launchCover != null) return
        val v = View(this)
        v.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.splash_window)
        v.isClickable = true
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        v.elevation = 64f * resources.displayMetrics.density
        (window.decorView as? android.view.ViewGroup)?.addView(v,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            ?: return
        launchCover = v
    }

    private fun hideLaunchCover() {
        uiHandler.removeCallbacks(coverSafety)
        uiHandler.removeCallbacks(coverGuard)
        val v = launchCover ?: return
        launchCover = null
        coverAdShowing = false
        if (Motion.animationsOn()) {
            v.animate().alpha(0f).setDuration(260L)
                .withEndAction { (v.parent as? android.view.ViewGroup)?.removeView(v) }.start()
        } else (v.parent as? android.view.ViewGroup)?.removeView(v)
    }

    /**
     * Opened to do something specific - a link or text from another app, a
     * launcher shortcut, the widget or the tile, a download notification -
     * rather than to browse: that is put straight in front, with no ad first.
     */
    private fun opensForATask(i: android.content.Intent?): Boolean {
        if (i == null) return false
        if (urlFromIntent(i) != null) return true
        if (i.hasExtra(BrowsingData.EXTRA_OPEN)) return true
        return when (i.action) {
            android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_PROCESS_TEXT,
            android.content.Intent.ACTION_WEB_SEARCH, android.content.Intent.ACTION_VIEW,
            ACTION_SEARCH, ACTION_NEW_TAB, ACTION_NIGHT_OWL, ACTION_SCAN, ACTION_VOICE -> true
            else -> false
        }
    }

    // Everything this screen starts passes through here (startActivity
    // included). Sent to another app - a picker, the share sheet, a link that
    // opens elsewhere - the user's coming back is not an open (AppOpenAds).
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: Bundle?) {
        val target = intent.component?.packageName ?: intent.`package`
        if (target != packageName) AppOpenAds.leftForOtherApp()
        super.startActivityForResult(intent, requestCode, options)
    }

    // In-app updates and some Play flows start through an IntentSender.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun startIntentSenderForResult(
        intent: android.content.IntentSender, requestCode: Int, fillInIntent: android.content.Intent?,
        flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?
    ) {
        AppOpenAds.leftForOtherApp()
        super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options)
    }

    override fun onDestroy() {
        // A recreated activity has already put its own questions in; only
        // take back ours, so the object does not keep this one alive.
        if (AppOpenAds.gate === appOpenGate) AppOpenAds.gate = { false }
        AppOpenAds.onResult = null
        nativeAd?.destroy()
        nativeAd = null
        // MediaService.onControl is a companion field holding a lambda that
        // captures this activity. Left set, a destroyed MainActivity and its
        // whole view tree - WebViews included - stay reachable for the life of
        // the process. Only clear it if it is still ours: a recreated activity
        // has already replaced it by now.
        if (MediaService.onControl === mediaControl) MediaService.onControl = null
        // Nothing posted for these tabs may run once they are gone.
        uiHandler.removeCallbacksAndMessages(null)
        // Each tab's WebView belongs to this activity and goes with it. They
        // were left for the garbage collector, so after a recreation - a theme
        // change is one - every old page stayed alive beside its replacement,
        // still holding its share of the renderer's memory: the shortage that
        // gets the renderer killed. Their history is already in TabStore by
        // now; onSaveInstanceState runs before this.
        tabs.tabs.forEach { t ->
            t.webView?.let { w ->
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            }
            t.webView = null
        }
        super.onDestroy()
    }

    private fun setupFindBar() {
        binding.findInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString() ?: ""
                val web = activeWeb() ?: return
                if (q.isEmpty()) { web.clearMatches(); binding.findCount.text = ""; return }
                web.findAllAsync(q)
            }
        })
        binding.findInput.setOnEditorActionListener { _, _, _ ->
            activeWeb()?.findNext(true); true
        }
        binding.findNext.setOnClickListener { activeWeb()?.findNext(true) }
        binding.findPrev.setOnClickListener { activeWeb()?.findNext(false) }
        binding.findClose.setOnClickListener { closeFindBar() }
    }

    private fun setupDeck() {
        tabAdapter = TabAdapter(
            tabs = { shownTabs() },
            // openTab before closeDeck, here and below: openTab takes a picture
            // of the tab being left, and refuses while the deck is still up -
            // closed first, the last frame on screen would be the deck itself.
            onSelect = { tab -> openTab(tab); closeDeck() },
            onClose = { tab -> closeTabByUser(tab) },
            // Lambdas, not values: the deck rebinds on every open, so these
            // stay current through tab switches and accent changes without the
            // adapter being rebuilt.
            isActive = { tab -> tabs.activeTab?.id == tab.id },
            accent = { currentAccent() }
        )
        binding.tabList.layoutManager = GridLayoutManager(this, 2)
        binding.tabList.adapter = tabAdapter
        // Flick a card sideways to close its tab. The throw keeps its
        // momentum: a fast flick flies off from a short drag, a slow drag has
        // to carry the card over a third of its width, and anything less
        // springs back into place. The card tilts and fades as it goes.
        androidx.recyclerview.widget.ItemTouchHelper(object :
            androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                // Long-press and drag a card to put the tab somewhere else.
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                    androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                    androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                    androidx.recyclerview.widget.ItemTouchHelper.RIGHT,
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                    androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                // Only in the full list: a filtered one has gaps in it.
                if (deckFilter.isNotBlank()) return false
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0 || from >= tabs.tabs.size || to >= tabs.tabs.size) return false
                java.util.Collections.swap(tabs.tabs, from, to)
                tabAdapter.notifyItemMoved(from, to)
                scheduleSessionSave()
                return true
            }

            override fun onSelectedChanged(
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int
            ) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    Haptics.confirm(vh?.itemView)
                    vh?.itemView?.animate()?.scaleX(1.04f)?.scaleY(1.04f)?.setDuration(160L)?.start()
                }
            }

            override fun getSwipeThreshold(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder) = 0.35f

            // A flick counts sooner than the default asks.
            override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 0.6f

            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                val tab = shownTabs().getOrNull(vh.bindingAdapterPosition)
                if (tab == null) tabAdapter.notifyDataSetChanged() else closeTabByUser(tab)
            }

            override fun onChildDraw(
                c: android.graphics.Canvas, rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val w = vh.itemView.width.toFloat().coerceAtLeast(1f)
                    val t = (dX / w).coerceIn(-1f, 1f)
                    vh.itemView.alpha = 1f - kotlin.math.abs(t) * 0.8f
                    vh.itemView.rotation = t * 8f
                }
            }

            override fun clearView(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) {
                super.clearView(rv, vh)
                vh.itemView.alpha = 1f
                vh.itemView.rotation = 0f
                vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(200L).start()
            }
        }).attachToRecyclerView(binding.tabList)

        binding.deckClose.setOnClickListener { closeDeck() }
        binding.deckMore.setOnClickListener { showDeckMenu(it) }
        binding.deckNewTab.setOnClickListener { addNewTab(homePage); closeDeck() }

        // Typing filters the cards to the tabs whose title or site matches;
        // Enter still searches the web in a new tab.
        binding.deckSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(cs: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(cs: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                val q = e?.toString()?.trim() ?: ""
                if (q == deckFilter) return
                deckFilter = q
                if (binding.tabList.visibility == View.VISIBLE) tabAdapter.notifyDataSetChanged()
            }
        })
        binding.deckSearch.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (enter) {
                val q = binding.deckSearch.text.toString()
                if (q.isNotBlank()) {
                    addNewTab(UrlHelper.toUrlOrSearch(q, Settings.getEngineUrl(this)))
                    closeDeck()
                    binding.deckSearch.setText("")
                }
                true
            } else false
        }
    }

    private fun openDeck() {
        showBars(animate = false)
        // Always open on the tab view; reset any lingering history/bookmark view.
        historyOpen = false
        bookmarksOpen = false
        binding.historyList.visibility = View.GONE
        binding.bookmarkList.visibility = View.GONE
        binding.downloadList.visibility = View.GONE
        binding.deckEmpty.visibility = View.GONE
        downloadsOpen = false
        binding.tabList.visibility = View.VISIBLE
        val active = tabs.activeTab
        if (active?.webView != null && !deckVisible) captureThumbnail(active) { showDeckNow() }
        else showDeckNow()
    }

    private fun showDeckNow() {
        deckVisible = true
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
        tabAdapter.notifyDataSetChanged()
        val deck = binding.tabDeck
        // It rises into place on a spring rather than appearing.
        deckSpring.cancel()
        placeDeck(0f)
        deck.visibility = View.VISIBLE
        // Smooth from the first frame. The deck - tabs, bookmarks, history or
        // downloads - is drawn once into a layer, and each frame of the rise
        // only fades and moves that layer instead of redrawing every card.
        deck.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // And the spring waits until the list is laid out and bound. Started
        // in the same frame, that work landed on the spring's first step,
        // which then jumped ahead to catch up - the stutter at the start.
        val seq = ++deckOpenSeq
        androidx.core.view.OneShotPreDrawListener.add(deck) {
            if (seq == deckOpenSeq && deckVisible) {
                deckSpring.animate(0f, 1f) {
                    endDeckLayer()
                    // The deck covers the owl now; no drawing under it.
                    if (deckVisible) binding.navOwl.covered = true
                }
            }
        }
    }

    private var deckOpenSeq = 0

    private fun endDeckLayer() {
        binding.tabDeck.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private val deckSpring by lazy {
        Spring(stiffness = 420f, dampingRatio = 0.8f, precision = 0.002f) { placeDeck(it) }
    }

    private fun placeDeck(p: Float) {
        val deck = binding.tabDeck
        deck.alpha = p.coerceIn(0f, 1f)
        deck.translationY = (1f - p) * 28f * resources.displayMetrics.density
        val scale = 0.97f + 0.03f * p
        deck.scaleX = scale
        deck.scaleY = scale
    }

    private fun closeDeck() {
        hideKeyboard()
        if (deckFilter.isNotEmpty()) { deckFilter = ""; binding.deckSearch.setText("") }
        deckSpring.cancel()
        deckOpenSeq++
        endDeckLayer()
        placeDeck(1f)
        binding.tabDeck.visibility = View.GONE
        deckVisible = false
        binding.navOwl.covered = false
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
        // refreshAdSlot() refuses to show the card while the deck is up, and
        // closing the deck is the moment that condition clears. Without this,
        // any refresh that lands while the deck is open leaves the card hidden
        // until the next navigation. Restore-only: it can never hide the card
        // in a state that already allowed it.
        refreshAdSlot()
    }

    // ---------- Jump back in ----------

    /** What home's "Jump back in" card offers (getResume); written on the main thread only. */
    @Volatile private var resumeJson = "{}"
    /** The tab last seen showing a website, by id. */
    private var resumeTabId: Long? = null

    /**
     * Notes the tab on screen if it is showing a website, and rebuilds the
     * card's copy. Called as pages finish and tabs are switched; a Night Owl
     * tab is never offered.
     */
    private fun noteResumable() {
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
    private fun refreshResumeCard() {
        noteResumable()
        val web = activeWeb() ?: return
        if (web.url == homePage) web.evaluateJavascript("window.__refreshResume && window.__refreshResume()", null)
    }

    // ---------- Home after a long break ----------
    //
    // After four hours away the browser opens on its home page - the news,
    // the shortcuts, Jump back in - rather than on whatever page was left
    // open, as Chrome does after a while. The page left is still in its tab,
    // one tap away. Settings > Customize > Open home after 4 hours away.

    private val AWAY_FOR_HOME_MS = 4L * 60L * 60_000L

    private fun showHomeAfterAway() {
        if (!Settings.getBool(this, Settings.HOME_AFTER_AWAY, true)) return
        val current = tabs.activeTab
        if (current == null || current.url == homePage) return
        noteResumable()
        val home = tabs.tabs.lastOrNull { it.url == homePage && it.isPrivate == nightOwl }
        if (home != null) openTab(home) else addNewTab(homePage)
    }

    /** What is typed in the deck's search field, filtering its cards. */
    private var deckFilter = ""

    /** The tabs the deck shows: all, or those whose title or address has [deckFilter] in it. */
    private fun shownTabs(): List<Tab> {
        val q = deckFilter
        if (q.isBlank()) return tabs.tabs
        return tabs.tabs.filter { t ->
            t.title.contains(q, ignoreCase = true) || t.url.contains(q, ignoreCase = true) ||
                PageLabel.title(t.title, t.url).contains(q, ignoreCase = true)
        }
    }

    /** The deck's overflow: close every tab, or every tab but the one on screen. */
    private fun showDeckMenu(anchor: View) {
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
    private fun duplicateTabs(): List<Tab> {
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
    private fun closeTabsQuietly(going: List<Tab>) {
        if (going.isEmpty()) return
        Haptics.confirm(binding.tabList)
        going.sortedBy { it === tabs.activeTab }.forEach { t ->
            if (!tabs.tabs.contains(t)) return@forEach
            rememberClosed(t)
            closeTabFromDeck(t)
        }
        toast("Closed " + going.size + (if (going.size == 1) " duplicate tab" else " duplicate tabs"))
    }

    /**
     * Closes every tab (or every other one), each remembered so Reopen closed
     * tab can bring them back one by one; asked first, since it is many at once.
     */
    private fun confirmCloseTabs(keepActive: Boolean) {
        val keep = if (keepActive) tabs.activeTab else null
        // The tab on screen last: closing it first would bring each next tab
        // to life (a new page) only to close it a moment later.
        val going = tabs.tabs.filter { it !== keep }.sortedBy { it === tabs.activeTab }
        if (going.isEmpty()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
            .setTitle(if (keepActive) "Close other tabs?" else "Close all tabs?")
            .setMessage(going.size.toString() + (if (going.size == 1) " tab" else " tabs") +
                " will close. Menu > Reopen closed tab brings them back.")
            .setPositiveButton("Close") { _, _ ->
                Haptics.confirm(binding.tabList)
                going.forEach { t ->
                    if (!tabs.tabs.contains(t)) return@forEach
                    rememberClosed(t)
                    closeTabFromDeck(t)
                }
                if (!keepActive) closeDeck()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Closed tabs come back ----------
    //
    // A tab closed by hand - flicked away or closed in the deck - is
    // remembered with its address, its title and, for the last few, its
    // back/forward history, so "Undo" right after, or Menu > Reopen closed
    // tab later, brings it back where it was, as in Chrome. Pop-ups closing
    // themselves are not remembered, and Night Owl tabs never are.

    private class ClosedTab(val url: String, val title: String, var state: Bundle?, val index: Int)
    private val closedTabs = ArrayDeque<ClosedTab>()

    private fun rememberClosed(tab: Tab): Boolean {
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
    private fun closeTabByUser(tab: Tab) {
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
    private fun reopenClosedTab(inDeck: Boolean) {
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

    // ---------- Page tools: share, translate, reader view, home screen ----------

    /** The website on screen, or null on home, an error page or a page of the app's own. */
    private fun webPageUrl(): String? {
        val url = activeWeb()?.url ?: tabs.activeTab?.url ?: return null
        if (url == homePage || isErrorPage(url)) return null
        return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun needAPage() = toast("Open a page first")

    /** The text size for [url]: its own (Menu > Zoom) or the one from Settings. */
    private fun zoomFor(url: String?): Int =
        SiteZoom.get(this, url) ?: Settings.getTextScale(this)

    /**
     * Menu > Zoom: a minus, a slider and a plus in 10% steps, the page
     * reflowing under it as it moves, and the size kept for the site. At the
     * bottom of the screen with a light scrim, so the page can be read while
     * it is set. Reset returns the site to the size in Settings.
     */
    private fun showZoom() {
        val web = activeWeb()
        val url = webPageUrl()
        val site = SiteZoom.keyOf(url)
        if (web == null || url == null || site == null) { needAPage(); return }
        val def = Settings.getTextScale(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var pct = web.settings.textZoom.coerceIn(SiteZoom.MIN, SiteZoom.MAX)

        val label = android.widget.TextView(this).apply {
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            minWidth = dp(64)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.navIcon))
        }
        val bar = android.widget.SeekBar(this).apply {
            max = (SiteZoom.MAX - SiteZoom.MIN) / SiteZoom.STEP
            progress = (pct - SiteZoom.MIN) / SiteZoom.STEP
        }
        val ripple = android.util.TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }.resourceId
        fun stepButton(text: String, desc: String) = android.widget.TextView(this).apply {
            this.text = text
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            contentDescription = desc
            setBackgroundResource(ripple)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val minus = stepButton("\u2212", "Zoom out")
        val plus = stepButton("+", "Zoom in")
        fun apply(next: Int, fromBar: Boolean) {
            val v = next.coerceIn(SiteZoom.MIN, SiteZoom.MAX)
            if (v != pct) Haptics.tick(bar)
            pct = v
            label.text = "$v%"
            if (!fromBar) bar.progress = (v - SiteZoom.MIN) / SiteZoom.STEP
            // Still the page it was opened on; the size reflows it live.
            if (activeWeb() === web) web.settings.textZoom = v
            // Night Owl: applied, never written down.
            if (!nightOwl) {
                if (v == def) SiteZoom.forget(this, site) else SiteZoom.set(this, url, v)
            }
        }
        label.text = "$pct%"
        minus.setOnClickListener { apply(pct - SiteZoom.STEP, false) }
        plus.setOnClickListener { apply(pct + SiteZoom.STEP, false) }
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, user: Boolean) {
                if (user) apply(SiteZoom.MIN + p * SiteZoom.STEP, true)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(minus)
            addView(bar, android.widget.LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(plus)
            addView(label)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
            .setTitle(site.removePrefix("www."))
            .setMessage(if (nightOwl) "Not kept after Night Owl." else "Kept for this site.")
            .setView(row)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ -> apply(def, false) }
            .create()
        dialog.window?.let { w ->
            w.setGravity(android.view.Gravity.BOTTOM)
            w.setDimAmount(0.12f)
        }
        dialog.show()
    }

    /**
     * Page info, from a tap on the site's icon, as the lock opens it in
     * Chrome: whether the connection is private and who vouches for the site,
     * what it has been allowed, and whether its ads are blocked - the last
     * two changeable right here.
     */
    private fun showPageInfo() {
        if (searchMode) return
        val url = webPageUrl() ?: return
        val host = try { android.net.Uri.parse(url).host?.ifBlank { null } } catch (e: Exception) { null } ?: return
        val name = host.removePrefix("www.")
        Haptics.tick(binding.siteIcon)
        val text = android.text.SpannableStringBuilder()
        fun block(head: String, body: String?) {
            if (text.isNotEmpty()) text.append("\n\n")
            val start = text.length
            text.append(head)
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, text.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!body.isNullOrBlank()) text.append("\n").append(body)
        }
        if (url.startsWith("https://")) {
            val cert = try { activeWeb()?.certificate } catch (e: Exception) { null }
            val by = cert?.issuedBy?.oName?.ifBlank { null } ?: cert?.issuedBy?.cName?.ifBlank { null }
            val until = cert?.validNotAfterDate?.let {
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(it)
            }
            block("Connection is secure",
                "What you send this site, passwords and card numbers included, is " +
                    "private on the way." +
                    (if (by != null) "\nCertificate from " + by + (if (until != null) ", valid until $until" else "") + "." else ""))
        } else {
            block("Connection is not secure",
                "Don't enter passwords or card details here: anyone on the network could read them.")
        }
        // What this site has been allowed, matched by host: the two prompts
        // record their origin in slightly different forms.
        val origins = SitePermissions.bySite(this).filterKeys {
            SitePermissions.hostOf(it) == name
        }
        val answers = origins.values.flatMap { it.entries }
        if (answers.isNotEmpty()) {
            block("Permissions", answers.joinToString("\n") { (kind, answer) ->
                val what = when (kind) {
                    SitePermissions.LOCATION -> "Location"
                    SitePermissions.CAMERA_MIC -> "Camera and microphone"
                    else -> kind
                }
                what + ": " + if (answer == SitePermissions.ALLOW) "allowed" else "blocked"
            })
        }
        val adsHost = siteForAds()
        if (adsHost != null) {
            block("Ads and trackers",
                if (AdBlockSites.isAllowed(this, adsHost)) "Allowed on this site" else "Blocked on this site")
        }
        if (nightOwl) block("Night Owl", "Nothing from this visit is kept once the Night Owl tabs close.")
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
            .setTitle(name)
            .setMessage(text)
            .setPositiveButton("Done", null)
        if (origins.isNotEmpty()) {
            dialog.setNeutralButton("Reset permissions") { _, _ ->
                origins.forEach { (origin, kinds) ->
                    kinds.keys.forEach { SitePermissions.forget(this, origin, it) }
                }
                toast("$name will ask again")
            }
        }
        if (adsHost != null) {
            val allowed = AdBlockSites.isAllowed(this, adsHost)
            dialog.setNegativeButton(if (allowed) "Block ads" else "Allow ads") { _, _ ->
                if (allowed) AdBlockSites.block(this, adsHost) else AdBlockSites.allow(this, adsHost)
                toast(if (allowed) "Ads blocked on $name" else "Ads allowed on $name")
                // Blocking acts on requests, so the page is fetched again.
                activeWeb()?.reload()
            }
        }
        dialog.show()
    }

    /**
     * Back or Forward held: the tab's pages that way, nearest first, as in
     * Chrome; picking one goes straight there. The app's home is listed as
     * Home rather than by its file address.
     */
    private fun showTabHistory(anchor: View, back: Boolean): Boolean {
        val web = activeWeb() ?: return false
        val list = try { web.copyBackForwardList() } catch (e: Exception) { return false }
        val cur = list.currentIndex
        val steps = if (back) (cur - 1 downTo maxOf(0, cur - 12))
            else (cur + 1 until minOf(list.size, cur + 13))
        if (steps.isEmpty()) return false
        Haptics.tick(anchor)
        val menu = android.widget.PopupMenu(this, anchor)
        var order = 0
        for (i in steps) {
            val item = list.getItemAtIndex(i) ?: continue
            val itemUrl = item.url ?: continue
            val label = when {
                itemUrl == homePage -> "Home"
                isErrorPage(itemUrl) -> "Page didn't load"
                else -> PageLabel.title(item.title ?: "", itemUrl)
            }
            menu.menu.add(0, i, order++, label)
        }
        menu.setOnMenuItemClickListener { item ->
            closeMenuNow()
            // Still the same tab, and the step still valid.
            if (activeWeb() === web && web.canGoBackOrForward(item.itemId - cur)) {
                web.goBackOrForward(item.itemId - cur)
            }
            true
        }
        menu.show()
        return true
    }

    /**
     * The page in the phone's language, through Google Translate's page
     * proxy, which keeps it a working page; on a translated page, the original.
     */
    private fun translatePage() {
        val web = activeWeb() ?: return
        val url = webPageUrl() ?: run { needAPage(); return }
        if (PageTranslate.isTranslated(url)) {
            PageTranslate.originalUrl(url)?.let { loadInto(web, it) }
            return
        }
        val lang = resources.configuration.locales.get(0)?.language?.ifBlank { null } ?: "en"
        loadInto(web, PageTranslate.translatedUrl(url, lang))
    }

    /** The article on its own, in clean type (ReaderView); again, the page as it was. */
    private fun toggleReaderView() {
        val web = activeWeb() ?: return
        if (webPageUrl() == null) { needAPage(); return }
        web.evaluateJavascript(ReaderView.JS) { result ->
            when (result?.trim('"')) {
                "on" -> { showBars(); schedulePageTint(400L) }
                "none" -> toast("Reader view needs an article - there isn't one on this page")
            }
        }
    }

    /** The page as its own icon on the home screen, opening in Search. */
    private fun addToHomeScreen() {
        val url = webPageUrl() ?: run { needAPage(); return }
        val web = activeWeb()
        val title = web?.title?.takeIf { it.isNotBlank() } ?: siteNameOf(url)
        val icon = SiteIcons.cached(url) ?: web?.favicon
        if (!HomeShortcut.pin(this, url, title, icon)) toast("This home screen doesn't take shortcuts")
    }

    // ---------- Default browser ----------

    private val defaultBrowserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (DefaultBrowser.isDefault(this)) toast("Search is now your default browser")
    }

    /** Android's own request to make Search the default browser, or its Default apps screen. */
    private fun askToBeDefaultBrowser() {
        val ask = DefaultBrowser.requestIntent(this)
        try {
            if (ask != null) defaultBrowserLauncher.launch(ask)
            else startActivity(DefaultBrowser.settingsIntent())
        } catch (e: Exception) {
            try { startActivity(DefaultBrowser.settingsIntent()) } catch (e2: Exception) {
                toast("Pick Search under Settings > Apps > Default apps > Browser")
            }
        }
    }

    /**
     * Offered once, on the fourth launch or later, on the home page with
     * nothing else going on - never on a first run and never twice. A browser
     * that is not the default is only ever opened on purpose; as the default,
     * every link tapped in another app comes here.
     */
    private fun maybeOfferDefaultBrowser() {
        if (isFinishing || isDestroyed || offeredThisLaunch) return
        if (nightOwl || searchMode || deckVisible || AdConsent.formShowing) return
        if (binding.menuScrim.visibility == View.VISIBLE) return
        val current = tabs.activeTab?.url
        if (!(current == null || current == homePage)) return
        if (!DefaultBrowser.shouldOffer(this)) return
        offeredThisLaunch = true
        DefaultBrowser.noteOffered(this)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
            .setTitle("Make Search your default browser?")
            .setMessage("Links you tap in other apps will open in Search. You can change this any time in Settings.")
            .setPositiveButton("Set as default") { _, _ -> askToBeDefaultBrowser() }
            .setNegativeButton("Not now", null)
            .show()
    }
    private var offeredThisLaunch = false

    private fun closeTabFromDeck(tab: Tab) {
        val wasActive = tab == tabs.activeTab
        // Resolved before teardown, while the tab is still in the list.
        val fallback = tab.openerId?.let { id ->
            tabs.tabs.firstOrNull { it.id == id && it !== tab }
        }
        tab.webView?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
            tab.webView = null
        }
        tab.thumbnail?.recycle()
        tab.thumbnail = null
        tabs.removeTab(tab)
        if (tabs.count() == 0) { closeDeck(); addNewTab(homePage) }
        else if (wasActive) {
            // Re-attach, don't just re-point. setActive moves a pointer and
            // nothing else, so the container is left holding the view we just
            // destroyed - the blank screen a sign-in pop-up leaves behind when
            // it closes itself. openTab puts a real view back on screen.
            (fallback ?: tabs.tabs.lastOrNull())?.let { openTab(it) }
        }
        tabAdapter.notifyDataSetChanged()
        updateTabCount()
        scheduleSessionSave()
    }

    // ---------- History ----------

    private var historyOpen = false

    /** Shows the deck empty-state with the given icon and message. */
    private fun showDeckEmpty(iconRes: Int, title: String, subtitle: String) {
        binding.deckEmptyIcon.setImageResource(iconRes)
        binding.deckEmptyTitle.text = title
        binding.deckEmptySubtitle.text = subtitle
        binding.deckEmpty.visibility = View.VISIBLE
    }

    private var downloadsOpen = false
    private var downloadsAdapter: DownloadsAdapter? = null

    /**
     * A download in flight changes underneath the list. The rows used to be a
     * single snapshot taken when the list opened, so a download that finished
     * while you watched still read "Downloading 40%" until you backed out and
     * came in again - the browser looked like the last thing to notice its own
     * download had landed.
     *
     * Re-queries itself while the list is on screen and stops on its own once
     * it is not, so nothing has to remember to cancel it.
     */
    private val downloadsPoll = object : Runnable {
        override fun run() {
            if (!downloadsOpen || !deckVisible) return
            refreshDownloads()
            binding.downloadList.postDelayed(this, 1200L)
        }
    }

    private fun showDownloads() {
        if (downloadsAdapter == null) {
            downloadsAdapter = DownloadsAdapter(
                items = emptyList(),
                onOpen = { d -> openDownloadedFile(d) },
                onMore = { d -> showDownloadActions(d) }
            )
            binding.downloadList.layoutManager =
                androidx.recyclerview.widget.LinearLayoutManager(this)
            binding.downloadList.adapter = downloadsAdapter
        }
        binding.tabList.visibility = View.GONE
        binding.historyList.visibility = View.GONE
        binding.bookmarkList.visibility = View.GONE
        downloadsOpen = true
        historyOpen = false
        bookmarksOpen = false
        // First pass on the main thread so the list is populated in the same
        // frame the deck appears; refreshes after this one run off it.
        applyDownloads(Downloads.load(this))
        binding.downloadList.removeCallbacks(downloadsPoll)
        binding.downloadList.postDelayed(downloadsPoll, 1200L)
    }

    /**
     * Removes a download, by whichever route it arrived. A file this app wrote
     * has no DownloadManager entry to remove, so calling remove() on it would
     * delete nothing and report nothing - the row would simply reappear.
     */
    private fun deleteDownload(d: Downloads.Item) {
        if (d.managed) {
            try {
                (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager)
                    .remove(d.id)
            } catch (e: Exception) { /* already gone */ }
            return
        }
        val uri = d.localUri ?: return
        try {
            val parsed = android.net.Uri.parse(uri)
            if (parsed.scheme.equals("content", true)) {
                contentResolver.delete(parsed, null, null)
            } else {
                parsed.path?.let { java.io.File(it).delete() }
            }
        } catch (e: Exception) { /* already gone from disk */ }
        SavedFiles.remove(this, uri)
    }

    private fun refreshDownloads() {
        Thread {
            val items = Downloads.load(this)
            runOnUiThread { applyDownloads(items) }
        }.start()
    }

    private fun applyDownloads(items: List<Downloads.Item>) {
        if (!downloadsOpen || isFinishing || isDestroyed) return
        downloadsAdapter?.submit(items)
        if (items.isEmpty()) {
            showDeckEmpty(R.drawable.menu_history, "No downloads yet",
                "Files you download will show up here.")
            binding.downloadList.visibility = View.GONE
        } else {
            binding.deckEmpty.visibility = View.GONE
            binding.downloadList.visibility = View.VISIBLE
        }
    }

    /**
     * Opens a finished download in whatever app handles it.
     *
     * The old path handed DownloadManager's COLUMN_LOCAL_URI straight to
     * ACTION_VIEW. For anything saved to the public Downloads folder that
     * column is a file:// URI, and handing one to another app has thrown
     * FileUriExposedException since Android 7. It was caught here as a plain
     * Exception, so every tap reported "No app to open this file" however many
     * viewers were installed - PDFs, audio, video, all of it. Nothing could
     * ever be opened, and the message blamed the phone.
     *
     * Two things were missing: a URI another app is allowed to read, and a type
     * worth routing on. DownloadManager issues a content:// URI of its own;
     * where a device still hands back file://, it goes out through FileProvider
     * instead. The stored media type is frequently blank or octet-stream, which
     * matches no viewer, so the file extension decides - that is what puts a
     * PDF in a PDF reader and an mp4 in a video player.
     */
    private fun openDownloadedFile(d: Downloads.Item) {
        if (d.canRetry) { confirmRetryDownload(d); return }
        if (!d.isComplete) {
            toast(if (d.isRunning) "Still downloading\u2026" else "File not available")
            return
        }
        val uri = resolveDownloadUri(d)
        if (uri == null) {
            toast("That file is no longer on this phone")
            return
        }
        val mime = mimeForDownload(d)
        if (isApk(d, mime)) { installApk(d, uri); return }
        // Specific type first - it is what routes a PDF to a PDF reader rather
        // than to a generic file browser. A wildcard type is the fallback for
        // one that nothing claims outright.
        if (startViewer(uri, mime)) return
        if (!mime.equals("*/*", true) && startViewer(uri, "*/*")) return
        toast("No app on this phone opens this kind of file")
    }

    // ---------- A downloaded app (.apk) ----------
    //
    // Handing an APK to Android's installer did nothing at all: from Android 8
    // the installer closes at once for an app that has not declared
    // REQUEST_INSTALL_PACKAGES, and the browser had not. Now it is declared,
    // and before the installer is opened the browser checks it has been
    // allowed to use it (Settings > Install unknown apps, per app); if not,
    // it says why and opens that switch, and carries on with the install
    // when the user comes back with it on. Android 11 restarts the app when
    // that switch is flipped, so what was being installed is kept on disk,
    // not in memory, for a few minutes.

    private val APK_MIME = "application/vnd.android.package-archive"
    private val APK_PREFS = "apk_install"
    private val APK_PENDING_MS = 10L * 60_000L

    private fun isApk(d: Downloads.Item, mime: String): Boolean {
        if (mime.equals(APK_MIME, true)) return true
        val name = android.net.Uri.decode(d.localUri?.substringAfterLast('/')?.substringBefore('?') ?: "")
        return name.endsWith(".apk", true) || d.title.endsWith(".apk", true)
    }

    private fun installApk(d: Downloads.Item, uri: android.net.Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallApps()) {
            askToAllowInstalls(d)
            return
        }
        if (!startViewer(uri, APK_MIME)) toast("Android's installer couldn't open this file")
    }

    private fun canInstallApps(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            try { packageManager.canRequestPackageInstalls() } catch (e: Exception) { false }

    /** The owl's ask: allow the browser to hand apps to Android's installer. */
    private fun askToAllowInstalls(d: Downloads.Item) {
        if (isFinishing || isDestroyed) return
        val view = layoutInflater.inflate(R.layout.dialog_install_apps, null)
        applyOwlArtIn(view)
        view.findViewById<android.widget.TextView>(R.id.installMessage).text =
            "To install " + d.title.ifBlank { "this app" } + ", Android needs your OK for Search " +
            "to open app files. Turn on \u201cAllow from this source\u201d, then come back " +
            "and the install starts. Only install apps from sources you trust."
        val dialog = android.app.AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.OwlDialogAnim
        view.findViewById<android.widget.TextView>(R.id.installNotNow).setOnClickListener {
            Haptics.tap(it)
            dialog.dismiss()
        }
        view.findViewById<android.widget.TextView>(R.id.installAllow).setOnClickListener {
            Haptics.tap(it)
            dialog.dismiss()
            getSharedPreferences(APK_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("id", d.id).putBoolean("managed", d.managed)
                .putString("local", d.localUri).putLong("at", System.currentTimeMillis())
                .apply()
            val opened = try {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + packageName)))
                true
            } catch (e: Exception) {
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                    true
                } catch (e2: Exception) { false }
            }
            if (!opened) {
                clearPendingApk()
                toast("Turn on Install unknown apps for Search in Android's settings")
            }
        }
        dialog.show()
        dialog.window?.let { w ->
            val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Back from the install switch (onResume): with it on, the install carries on. */
    private fun resumePendingApkInstall() {
        val p = getSharedPreferences(APK_PREFS, Context.MODE_PRIVATE)
        if (!p.contains("id")) return
        val at = p.getLong("at", 0L)
        val id = p.getLong("id", -1L)
        val managed = p.getBoolean("managed", true)
        val local = p.getString("local", null)
        // One go: on or off, the question has been answered.
        clearPendingApk()
        if (System.currentTimeMillis() - at !in 0L..APK_PENDING_MS) return
        if (!canInstallApps()) return
        Thread {
            val d = try {
                Downloads.load(this).firstOrNull { it.id == id && it.managed == managed && it.localUri == local }
                    ?: Downloads.load(this).firstOrNull { it.id == id && it.managed == managed }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val uri = d?.takeIf { it.isComplete }?.let { resolveDownloadUri(it) }
                if (d == null || uri == null) toast("That file is no longer on this phone")
                else if (!startViewer(uri, APK_MIME)) toast("Android's installer couldn't open this file")
            }
        }.start()
    }

    private fun clearPendingApk() {
        getSharedPreferences(APK_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** A failed download: offer to fetch it again. */
    private fun confirmRetryDownload(d: Downloads.Item) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Search_Dialog)
            .setTitle("Download failed")
            .setMessage(d.title.ifBlank { "This file" } + " didn't finish. Try it again?")
            .setPositiveButton("Retry") { _, _ -> retryDownload(d) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Fetches a failed download again from where it came from, under the same
     * name and type, through the same free-space check as a new one and with
     * the site's cookies, so a file behind a sign-in still arrives. The failed
     * record goes once the new one is queued; it holds no file.
     */
    private fun retryDownload(d: Downloads.Item) {
        val url = d.remoteUri ?: return
        val free = freeDownloadSpace()
        if (downloadRoom(d.bytesTotal, free) == ROOM_NONE) {
            showNoRoomForDownload(d.title, d.bytesTotal, free)
            return
        }
        try {
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
            val name = d.title.ifBlank { "download" }
            request.setMimeType(downloadMime(name, d.mimeType))
            request.addRequestHeader("User-Agent",
                android.webkit.WebSettings.getDefaultUserAgent(this))
            request.setTitle(name)
            request.setDescription("Downloading\u2026")
            request.setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_DOWNLOADS, name)
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            cookieIo.execute {
                val ok = try {
                    android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                        if (it.isNotBlank()) request.addRequestHeader("Cookie", it)
                    }
                    dm.enqueue(request)
                    try { dm.remove(d.id) } catch (e: Exception) {}
                    true
                } catch (e: Exception) { false }
                runOnUiThread {
                    HealthStats.count(this,
                        if (ok) HealthStats.DOWNLOADS else HealthStats.DOWNLOAD_FAILURES)
                    toast(if (ok) "Downloading " + name else "Download failed")
                    if (ok) refreshDownloads()
                }
            }
        } catch (e: Exception) {
            HealthStats.count(this, HealthStats.DOWNLOAD_FAILURES)
            toast("Download failed")
        }
    }

    private fun startViewer(uri: android.net.Uri, mime: String): Boolean = try {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (e: Exception) {
        false
    }

    /** A URI another app can read, or null if the file is gone. */
    private fun resolveDownloadUri(d: Downloads.Item): android.net.Uri? {
        // DownloadManager's own content:// URI is the first choice: it is
        // readable by another app as soon as the read grant is attached. Only
        // worth asking for a download it actually handled.
        if (d.managed) {
            try {
                val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
                val own = dm.getUriForDownloadedFile(d.id)
                if (own != null && own.scheme.equals("content", true)) return own
            } catch (e: Exception) { /* fall through to the stored path */ }
        }

        val parsed = try {
            android.net.Uri.parse(d.localUri ?: return null)
        } catch (e: Exception) { return null }
        if (parsed.scheme.equals("content", true)) return parsed

        // A file:// path. Wrap it rather than pass it: the raw URI is exactly
        // what the platform refuses to let another app receive.
        val file = java.io.File(parsed.path ?: return null)
        if (!file.exists()) return null
        return try {
            androidx.core.content.FileProvider.getUriForFile(
                this, packageName + ".files", file)
        } catch (e: Exception) { null }
    }

    /**
     * A type a viewer will actually match on. Servers frequently send nothing
     * useful - blank, or octet-stream for a perfectly ordinary PDF - and that
     * is a type no app claims, so the extension wins whenever the stored one
     * says nothing.
     */
    private fun mimeForDownload(d: Downloads.Item): String {
        val declared = d.mimeType?.trim().orEmpty()
        val useful = declared.isNotBlank() &&
            !declared.equals("*/*", true) &&
            !declared.equals("application/octet-stream", true) &&
            !declared.equals("binary/octet-stream", true)
        if (useful) return declared
        val name = (d.localUri?.substringAfterLast('/')?.substringBefore('?') ?: d.title)
        val ext = android.net.Uri.decode(name).substringAfterLast('.', "").lowercase()
        if (ext.isNotBlank()) {
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext)?.let { return it }
        }
        return declared.ifBlank { "*/*" }
    }

    private fun showHistory() {
        val entries = History.load(this)
        val adapter = HistoryAdapter(
            entries = entries,
            onSelect = { entry ->
                addNewTab(entry.url)
                closeDeck()
            },
            onDelete = { entry ->
                History.delete(this, entry.url)
                showHistory()  // rebuild the list without the deleted entry
            }
        )
        binding.historyList.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        binding.tabList.visibility = View.GONE
        binding.bookmarkList.visibility = View.GONE
        if (entries.isEmpty()) {
            showDeckEmpty(R.drawable.menu_history, "No history yet", "Pages you visit will show up here.")
            binding.historyList.visibility = View.GONE
        } else {
            binding.deckEmpty.visibility = View.GONE
            binding.historyList.visibility = View.VISIBLE
        }
        historyOpen = true
        bookmarksOpen = false
    }

    private var bookmarksOpen = false

    private fun showBookmarks() {
        val entries = Bookmarks.load(this).map { History.Entry(it.title, it.url, it.time) }
        val adapter = HistoryAdapter(
            entries = entries,
            onSelect = { entry -> addNewTab(entry.url); closeDeck() },
            onDelete = { entry -> Bookmarks.remove(this, entry.url); showBookmarks() }
        )
        binding.bookmarkList.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.bookmarkList.adapter = adapter
        binding.tabList.visibility = View.GONE
        binding.historyList.visibility = View.GONE
        if (entries.isEmpty()) {
            showDeckEmpty(R.drawable.menu_bookmarks, "No bookmarks yet", "Tap the star on any page to save it here.")
            binding.bookmarkList.visibility = View.GONE
        } else {
            binding.deckEmpty.visibility = View.GONE
            binding.bookmarkList.visibility = View.VISIBLE
        }
        bookmarksOpen = true
        historyOpen = false
    }

    // ---------- Bookmarks ----------

    private fun toggleBookmark() {
        val tab = tabs.activeTab ?: return
        val url = tab.url
        if (url.isBlank() || url.startsWith("file:///android_asset/")) return
        if (Bookmarks.isBookmarked(this, url)) {
            Bookmarks.remove(this, url)
            android.widget.Toast.makeText(this, "Bookmark removed", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            Bookmarks.add(this, tab.title, url)
            android.widget.Toast.makeText(this, "Bookmarked", android.widget.Toast.LENGTH_SHORT).show()
        }
        refreshStar()
    }

    private fun refreshStar() {
        val url = tabs.activeTab?.url ?: ""
        val marked = url.isNotBlank() && Bookmarks.isBookmarked(this, url)
        binding.starBtn.setImageResource(
            if (marked) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
    }

    // ---------- Owl tap (context-aware) ----------
    private val gamesUrl = "https://toolsepulse.co/games"

    private fun openGames() {
        addNewTab(gamesUrl)
    }

    private fun showGamesWelcome() {
        val view = layoutInflater.inflate(R.layout.dialog_games_welcome, null)
        applyOwlArtIn(view)
        val dialog = android.app.AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.window?.attributes?.windowAnimations = R.style.OwlDialogAnim
        view.findViewById<android.widget.TextView>(R.id.gamesLater).setOnClickListener {
            Settings.setBool(this, Settings.GAMES_INTRO_SEEN, true)
            dialog.dismiss()
        }
        view.findViewById<android.widget.TextView>(R.id.gamesGo).setOnClickListener {
            Settings.setBool(this, Settings.GAMES_INTRO_SEEN, true)
            dialog.dismiss()
            openGames()
        }
        dialog.show()
        dialog.window?.let { w ->
            val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showOwlCloseDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_owl_close, null)
        applyOwlArtIn(view)
        val dialog = android.app.AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.window?.attributes?.windowAnimations = R.style.OwlDialogAnim
        view.findViewById<android.widget.TextView>(R.id.owlCloseNo).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<android.widget.TextView>(R.id.owlCloseYes).setOnClickListener {
            dialog.dismiss()
            finishAffinity()
        }
        dialog.show()
        dialog.window?.let { w ->
            val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    // ---------- Downloads ----------

    /**
     * Returns a tab to the page it is actually showing, after a navigation
     * turned out to be a download.
     *
     * The committed history entry is the truth here: a navigation that became a
     * download never commits, so currentItem still names the page the user is
     * looking at, while web.url can briefly report the address being fetched.
     */
    private fun restoreAfterDownload(web: WebView) {
        val tab = tabs.tabs.firstOrNull { it.webView === web } ?: return
        val committed = try {
            web.copyBackForwardList().currentItem?.url
        } catch (e: Exception) { null }
        val real = committed ?: web.url ?: homePage
        tab.url = real
        (web as? BrowserWebView)?.pageHost =
            try { android.net.Uri.parse(real).host } catch (e: Exception) { null }
        if (web === activeWeb()) {
            if (!binding.urlBar.hasFocus()) binding.urlBar.setText(displayUrl(real))
            refreshOmniboxVisibility(real)
        }
    }

    /**
     * Security gate: every download — whether the user tapped it or a site
     * triggered it silently — must be confirmed here before it proceeds.
     * This stops websites from secretly downloading files to the device.
     */
    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long = -1L
    ) {
        val fileName = downloadName(url, contentDisposition, mimeType)
        // Space first: a file that cannot fit is stopped here, with the
        // reason, rather than failing part-way in DownloadManager.
        val free = freeDownloadSpace()
        val room = downloadRoom(contentLength, free)
        if (room == ROOM_NONE) {
            showNoRoomForDownload(fileName, contentLength, free)
            return
        }
        // If the user disabled download confirmation, download straight away -
        // unless the file would take most of the space left, which is worth a
        // word first whatever the setting.
        if (!Settings.getBool(this, Settings.SEC_CONFIRM_DOWNLOADS, true) && room == ROOM_OK) {
            performDownload(url, userAgent, contentDisposition, mimeType)
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_download, null)
        applyOwlArtIn(view)
        view.findViewById<android.widget.TextView>(R.id.dlFileName).text = fileName
        val fmt = { b: Long -> android.text.format.Formatter.formatShortFileSize(this, b) }
        view.findViewById<android.widget.TextView>(R.id.dlSize).text =
            (if (contentLength > 0) fmt(contentLength) else "Size unknown") +
                " \u00B7 to Downloads" +
                (if (free >= 0) " \u00B7 " + fmt(free) + " free" else "")
        if (room == ROOM_TIGHT) {
            view.findViewById<android.widget.TextView>(R.id.dlSpaceWarn).apply {
                text = "This will use most of your free space."
                visibility = View.VISIBLE
            }
            (view.findViewById<android.widget.TextView>(R.id.dlSize).layoutParams
                as? android.widget.LinearLayout.LayoutParams)?.bottomMargin =
                (6 * resources.displayMetrics.density).toInt()
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        // Transparent window so our rounded layout shows cleanly.
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )

        view.findViewById<android.widget.TextView>(R.id.dlConfirm).setOnClickListener {
            dialog.dismiss()
            performDownload(url, userAgent, contentDisposition, mimeType)
        }
        view.findViewById<android.widget.TextView>(R.id.dlCancel).setOnClickListener {
            dialog.dismiss()
            android.widget.Toast.makeText(
                this, "Download cancelled", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        dialog.show()
        // Constrain the dialog to a compact card width.
        dialog.window?.let { w ->
            val dm = resources.displayMetrics
            val width = (dm.widthPixels * 0.86f).toInt()
            w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    // ---------- Space for downloads ----------

    private val ROOM_OK = 0
    private val ROOM_TIGHT = 1
    private val ROOM_NONE = 2

    /**
     * Free space where downloads go (the phone's shared storage), or -1 if it
     * cannot be read. A statfs call: cheap enough for the main thread.
     */
    private fun freeDownloadSpace(): Long = try {
        @Suppress("DEPRECATION")
        android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        try { android.os.StatFs(filesDir.path).availableBytes } catch (e2: Exception) { -1L }
    }

    /**
     * Whether a [size]-byte file fits in [free] bytes. It does not if it would
     * leave less than 200MB, which Android needs to keep running smoothly; it
     * is tight if it takes half the space or leaves under 1GB. An unknown size
     * or free space is taken as fitting - nothing is stopped on a guess.
     */
    private fun downloadRoom(size: Long, free: Long): Int {
        if (size <= 0L || free < 0L) return ROOM_OK
        val reserve = 200L * 1024 * 1024
        if (size > free - reserve) return ROOM_NONE
        if (size * 2 >= free || free - size < 1024L * 1024 * 1024) return ROOM_TIGHT
        return ROOM_OK
    }

    private fun showNoRoomForDownload(fileName: String, size: Long, free: Long) {
        val fmt = { b: Long -> android.text.format.Formatter.formatShortFileSize(this, b) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Search_Dialog)
            .setTitle("Not enough storage")
            .setMessage(fileName + " is " + fmt(size) + ", and this phone has " +
                fmt(free.coerceAtLeast(0L)) + " free. Free up some space, then try again.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Storage settings") { _, _ ->
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                } catch (e: Exception) { /* no such screen on this phone */ }
            }
            .show()
    }

    private fun performDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        // On Android 9 and below, writing to public Downloads needs the runtime
        // WRITE_EXTERNAL_STORAGE grant. Request it and resume after grant.
        // Android 10+ (API 29+) doesn't need it, so skip the check there.
        if (android.os.Build.VERSION.SDK_INT <= 28) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingDownload = PendingDownload(url, userAgent, contentDisposition, mimeType)
                storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        // DownloadManager fetches over http and https and nothing else. A blob:
        // or data: address is the page's own in-memory data, which no other
        // process can fetch at all - so it is read out through the page and
        // written here instead of handed over. Without this, every export out
        // of a web app ended in "Download failed".
        if (url.startsWith("data:", true)) {
            toast("Saving\u2026")
            val name = downloadName(url, contentDisposition, mimeType)
            Thread { saveDataDownload(url, name, mimeType) }.start()
            return
        }
        if (url.startsWith("blob:", true)) {
            toast("Saving\u2026")
            requestBlobDownload(url, downloadName(url, contentDisposition, mimeType), mimeType)
            return
        }
        try {
            val fileName = downloadName(url, contentDisposition, mimeType)
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
            // Not the declared type: a host that says application/octet-stream
            // for an mp3 has the file registered as binary, and no player
            // claims it. downloadMime keeps a real type and replaces a generic
            // one with whatever the extension says.
            request.setMimeType(downloadMime(fileName, mimeType))
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            // Some hosts refuse a request that arrives with no referer. Read
            // here: a WebView answers on the main thread only.
            activeWeb()?.url?.let {
                if (it.startsWith("http")) request.addRequestHeader("Referer", it)
            }
            request.setTitle(fileName)
            request.setDescription("Downloading…")
            request.setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            request.setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_DOWNLOADS, fileName
            )
            // Stated outright: on any network, roaming included. A large file
            // is fetched by DownloadManager in its own process, so it neither
            // holds this app's memory nor stops when the app is closed, and it
            // resumes by itself after the network drops.
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            val referer = activeWeb()?.url
            // DownloadManager fetches in its own process, with none of the
            // WebView's state. Without the session cookie, anything behind a
            // sign-in hands back the sign-in page instead of the file, and the
            // download "succeeds" - you get a saved HTML page named report.pdf.
            //
            // getCookie() waits on Chromium's cookie thread, so it is asked on
            // the cookie thread's queue here rather than on the main thread,
            // and the enqueue follows it there.
            cookieIo.execute {
                val cookie = try {
                    android.webkit.CookieManager.getInstance().getCookie(url)
                } catch (e: Exception) { null }
                val ok = try {
                    cookie?.let { if (it.isNotBlank()) request.addRequestHeader("Cookie", it) }
                    dm.enqueue(request)
                    true
                } catch (e: Exception) { false }
                runOnUiThread {
                    if (ok) {
                        HealthStats.count(this, HealthStats.DOWNLOADS)
                        toast("Downloading $fileName")
                        return@runOnUiThread
                    }
                    // DownloadManager would not take it (switched off on this
                    // phone, or refusing the address): the app fetches it
                    // itself, streamed to Downloads, rather than giving up.
                    ensureNotifPermission()
                    toast("Downloading $fileName")
                    HttpSave.start(this, url, userAgent, referer, cookie, fileName,
                        downloadMime(fileName, mimeType) ?: "application/octet-stream") { saved ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            HealthStats.count(this,
                                if (saved) HealthStats.DOWNLOADS else HealthStats.DOWNLOAD_FAILURES)
                            toast(if (saved) "Saved to Downloads: $fileName" else "Download failed")
                            if (downloadsOpen) refreshDownloads()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            HealthStats.count(this, HealthStats.DOWNLOAD_FAILURES)
            android.widget.Toast.makeText(
                this, "Download failed", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun downloadFailed() {
        runOnUiThread {
            HealthStats.count(this, HealthStats.DOWNLOAD_FAILURES)
            toast("Couldn't save the file")
        }
    }

    /**
     * The owl, summoned for one file.
     *
     * Deliberately not a PopupMenu. Deleting a download is the one thing in
     * this app that destroys something the user cannot get back -
     * DownloadManager.remove takes the file off the disk, not just the row
     * off the list - and the menu this replaces did it on a single tap, from
     * a long-press nothing advertised. Naming the file and making the choice
     * explicit is the confirmation that was missing; the owl is the app's own
     * voice rather than a system menu's.
     */
    private fun showDownloadActions(d: Downloads.Item) {
        val view = layoutInflater.inflate(R.layout.dialog_download_actions, null)
        applyOwlArtIn(view)
        // Middle ellipsis, not the end. The extension is the most useful part
        // of a long filename - it is what tells you the thing is an mp3 - and
        // trimming from the right is exactly what throws it away.
        view.findViewById<android.widget.TextView>(R.id.dlaName).text =
            d.title.ifBlank { "this file" }

        val dialog = android.app.AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.window?.attributes?.windowAnimations = R.style.OwlDialogAnim

        view.findViewById<android.widget.TextView>(R.id.dlaShare).setOnClickListener {
            dialog.dismiss()
            shareDownload(d)
        }
        view.findViewById<android.widget.TextView>(R.id.dlaDelete).setOnClickListener {
            dialog.dismiss()
            deleteDownload(d)
            refreshDownloads()
            toast("Gone")
        }
        view.findViewById<android.widget.TextView>(R.id.dlaCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.let { w ->
            val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Hands a finished download to whatever the user picks.
     *
     * The same two things that broke opening a file break sharing one, for
     * the same reasons: another app cannot read a file:// URI - that has
     * thrown FileUriExposedException since Android 7 - and the stored media
     * type is frequently blank or octet-stream, which no share target claims.
     * resolveDownloadUri and mimeForDownload already answer both, so this
     * leans on them rather than growing a second, slightly different answer
     * that will drift from the first.
     */
    private fun shareDownload(d: Downloads.Item) {
        if (!d.isComplete) {
            toast(if (d.isRunning) "Still downloading\u2026" else "File not available")
            return
        }
        val uri = resolveDownloadUri(d)
        if (uri == null) {
            toast("That file is no longer on this phone")
            return
        }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeForDownload(d)
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            // Without this the chooser lists apps that then cannot open what
            // they were handed, which reads as the other app being broken.
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(android.content.Intent.createChooser(send, "Share file"))
        } catch (e: Exception) {
            toast("Nothing on this phone can share that")
        }
    }

    /**
     * Types that mean "a file" and nothing more.
     *
     * Most download hosts send one of these for everything they serve, so a
     * declared type from this list carries no information and must never be
     * allowed to overrule an extension the name already has. Treating
     * application/octet-stream as authoritative is exactly what renamed an
     * mp3 to .bin.
     */
    private fun isGenericMime(m: String?): Boolean {
        val t = m?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return t.isEmpty() || t == "*/*" ||
            t == "application/octet-stream" || t == "binary/octet-stream" ||
            t == "application/unknown" || t == "application/force-download" ||
            t == "application/download" || t == "application/x-download"
    }

    /**
     * The filename a server stated in Content-Disposition.
     *
     * Parsed here rather than through the platform, whose own regex anchors
     * filename= to the end of the header: a server sending filename= followed
     * by anything at all - a second parameter, a trailing semicolon - yields
     * no name from it and falls through to the address instead.
     *
     * filename* is preferred where both are present. It is the only form that
     * can carry non-ASCII, so it is the accurate one whenever a title has an
     * accent, an apostrophe or a non-Latin script in it.
     */
    private fun dispositionName(cd: String?): String {
        val h = cd ?: return ""
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
            .find(h)?.let { m ->
                val charset = m.groupValues[1].trim().ifBlank { "UTF-8" }
                val raw = m.groupValues[2].trim().trim('"')
                val decoded = try {
                    java.net.URLDecoder.decode(raw, charset)
                } catch (e: Exception) {
                    try { java.net.URLDecoder.decode(raw, "UTF-8") }
                    catch (e2: Exception) { raw }
                }
                if (decoded.isNotBlank()) return decoded
            }
        Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(h)?.let {
                if (it.groupValues[1].isNotBlank()) return it.groupValues[1]
            }
        Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(h)?.let {
                val v = it.groupValues[1].trim().trim('"')
                if (v.isNotBlank()) return v
            }
        return ""
    }

    /**
     * Safe to hand to setDestinationInExternalPublicDir, which throws on a
     * name containing a path separator. Parentheses and spaces are left alone
     * - they are legal, and stripping them mangles titles for no reason.
     */
    private fun sanitizeName(n: String): String {
        var s = n.substringAfterLast('/').substringAfterLast('\\').trim()
        s = s.replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_")
        s = s.trim('.', ' ')
        if (s.length > 120) {
            val ext = s.substringAfterLast('.', "")
            val stem = s.substringBeforeLast('.', s).take(110)
            s = if (ext.isBlank()) stem else stem + "." + ext
        }
        return s
    }

    /**
     * Whether the tail after the final dot is an extension or just part of
     * the name. "mp3" is; "com)" - from Shallipopi-Laho-(TrendySongz.com).mp3
     * with its real extension already lost - is not.
     */
    private fun looksLikeExt(name: String): Boolean {
        val e = name.substringAfterLast('.', "")
        return e.length in 1..5 && e.isNotEmpty() && e.all { it.isLetterOrDigit() }
    }

    /**
     * A filename that keeps what the server called the file and carries an
     * extension the phone can act on.
     *
     * In order of trust: the name the server stated, the last piece of the
     * address, then the platform's guess - which is last because of what it
     * does to a generic content type, and which is still needed for data:
     * URLs, where there is no name anywhere else.
     */
    private fun downloadName(url: String, cd: String?, mime: String?): String {
        val fromUrl = sanitizeName(try {
            android.net.Uri.parse(url).lastPathSegment.orEmpty()
        } catch (e: Exception) { "" })

        var name = sanitizeName(dispositionName(cd))
        if (name.isBlank()) name = fromUrl
        if (name.isBlank()) {
            name = sanitizeName(try {
                android.webkit.URLUtil.guessFileName(url, cd, mime)
            } catch (e: Exception) { "" })
            // .bin is guessFileName's way of saying it does not know. Dropped
            // so the recovery below gets a chance rather than being handed a
            // name that already looks finished.
            if (name.endsWith(".bin", true)) name = name.dropLast(4)
        }
        if (name.isBlank()) name = "download_" + System.currentTimeMillis()

        val map = android.webkit.MimeTypeMap.getSingleton()
        val declared = mime?.substringBefore(';')?.trim()?.lowercase()

        // A REAL declared type that disagrees with the extension on the name
        // wins, and the extension is swapped for the one that type maps to.
        // This is the part guessFileName gets right - a .php that serves audio
        // should land as .mp3 - and it is kept. What is not kept is doing it
        // for a generic type, and cutting the base name at the first dot
        // instead of the last.
        if (looksLikeExt(name) && declared != null && !isGenericMime(declared)) {
            val fromName = map.getMimeTypeFromExtension(
                name.substringAfterLast('.').lowercase())
            if (fromName != null && !fromName.equals(declared, true)) {
                val want = map.getExtensionFromMimeType(declared)
                if (!want.isNullOrBlank()) {
                    name = name.substringBeforeLast('.') + "." + want
                }
            }
        }

        if (!looksLikeExt(name)) {
            val urlExt =
                if (looksLikeExt(fromUrl)) fromUrl.substringAfterLast('.') else ""
            val mimeExt = declared
                ?.takeIf { !isGenericMime(it) }
                ?.let { map.getExtensionFromMimeType(it) }
                .orEmpty()
            val ext = urlExt.ifBlank { mimeExt }
            if (ext.isNotBlank()) name = name + "." + ext
        }
        return name
    }

    /**
     * The type to register the file under.
     *
     * A real declared type is kept. A generic one is replaced by whatever the
     * extension maps to, so an mp3 served as application/octet-stream is
     * stored as audio/mpeg - which is what decides whether a music player
     * will open it and whether the media scanner indexes it at all.
     */
    private fun downloadMime(name: String, declared: String?): String? {
        val clean = declared?.substringBefore(';')?.trim()
        if (!isGenericMime(clean)) return clean
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return clean
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: clean
    }

    /**
     * Only the page that minted a blob: URL can read it, so the page is asked
     * to: fetch it and hand the bytes back.
     *
     * In pieces of about 2MB, each base64 across the bridge and written to the
     * file as it arrives (blobFileBegin, blobFileChunk, blobFileEnd), read
     * off the response as a stream so the page never makes a second copy of
     * the file either. It used to come across whole, as one data: URL, which
     * took several times the file's size in memory - capped at 16MB for that
     * reason, so any larger export simply failed. Now a file of several GB
     * saves with no more memory than one piece, with a progress notification
     * while it is written.
     */
    private fun requestBlobDownload(url: String, name: String, mime: String?) {
        val web = activeWeb() ?: run { downloadFailed(); return }
        val token = java.util.UUID.randomUUID().toString()
        blobSaves[token] = BlobSave(name, mime)
        ensureNotifPermission()
        web.evaluateJavascript(BLOB_STREAM_JS + "(" +
            JSONObject.quote(url) + "," + JSONObject.quote(token) + ");", null)
    }

    private val BLOB_STREAM_JS = "(function(u,t){var A=window.SearchApp,CH=2097152;" +
        "function fail(){try{A.blobFileFailed(t);}catch(e){}}" +
        "function b64(parts,done){var fr=new FileReader();fr.onload=function(){var s=fr.result;" +
        "done(s.substring(s.indexOf(',')+1));};fr.onerror=fail;fr.readAsDataURL(new Blob(parts));}" +
        "try{fetch(u).then(function(r){if(!r.ok)throw 0;" +
        "var len=r.headers.get('Content-Length')||'-1',type=r.headers.get('Content-Type')||'';" +
        "if(!r.body||!r.body.getReader){return r.blob().then(function(b){" +
        "if(!A.blobFileBegin(t,String(b.size),b.type||type))return;var off=0;(function next(){" +
        "if(off>=b.size){A.blobFileEnd(t);return;}b64([b.slice(off,off+CH)],function(s){" +
        "if(A.blobFileChunk(t,s)){off+=CH;setTimeout(next,0);}});})();});}" +
        "if(!A.blobFileBegin(t,String(len),type)){try{r.body.cancel();}catch(e){}return;}" +
        "var rd=r.body.getReader(),parts=[],size=0;" +
        "function flush(then){if(!size){then();return;}var p=parts;parts=[];size=0;" +
        "b64(p,function(s){if(A.blobFileChunk(t,s))then();else{try{rd.cancel();}catch(e){}}});}" +
        "(function pump(){rd.read().then(function(x){" +
        "if(x.done){flush(function(){A.blobFileEnd(t);});return;}" +
        "parts.push(x.value);size+=x.value.byteLength;if(size>=CH)flush(pump);else pump();" +
        "}).catch(fail);})();" +
        "}).catch(fail);}catch(e){fail();}})"

    /**
     * A data: address, decoded a piece at a time straight into the file. The
     * address itself is already in memory (the page handed it over whole),
     * but decoding it whole as well doubled that.
     */
    private fun saveDataDownload(dataUrl: String, name: String, mime: String?) {
        val comma = dataUrl.indexOf(',')
        if (comma < 5) { downloadFailed(); return }
        val header = dataUrl.substring(5, comma)
        val type = mime?.takeIf { it.isNotBlank() }
            ?: header.substringBefore(';').ifBlank { "application/octet-stream" }
        val sink = DownloadSink.open(this, name, type) ?: run { downloadFailed(); return }
        try {
            if (header.contains("base64", true)) {
                var body = dataUrl.substring(comma + 1)
                if (body.any { it.isWhitespace() }) body = body.filterNot { it.isWhitespace() }
                // A multiple of four characters, so every piece decodes on its own.
                val step = 4 * 1024 * 1024
                var i = 0
                while (i < body.length) {
                    val j = minOf(i + step, body.length)
                    sink.write(android.util.Base64.decode(body.substring(i, j), android.util.Base64.DEFAULT))
                    i = j
                }
            } else {
                sink.write(java.net.URLDecoder.decode(dataUrl.substring(comma + 1), "UTF-8").toByteArray())
            }
            val uri = if (sink.written > 0) sink.finish() else { sink.abort(); null }
            if (uri == null) { downloadFailed(); return }
            SavedFiles.add(this, sink.name, uri, sink.mime, sink.written)
            runOnUiThread { toast("Saved to Downloads: " + sink.name) }
        } catch (t: Throwable) {
            sink.abort()
            downloadFailed()
        }
    }

    // ---------- UI wiring ----------

    private fun setupUrlBar() {
        // Start non-focusable; only enterSearchMode() enables focus.
        binding.urlBar.isFocusable = false
        binding.urlBar.isFocusableInTouchMode = false
        // Enter search mode on an explicit tap only. The bar is non-focusable at
        // rest (see enterSearchMode/exitSearchMode), so system/incidental focus
        // during tab switches or page loads can never trigger it. This is the
        // permanent fix for the stray-cursor / wrong-tab-open bugs.
        binding.urlBar.setOnClickListener {
            if (!searchMode) enterSearchMode()
        }
        // The site's icon is the page's info, as the lock is in Chrome.
        binding.siteIcon.setOnClickListener { showPageInfo() }
        Motion.pressable(binding.siteIcon, 0.85f)
        // A long press on the address, as in Chrome: copy it, share it, paste
        // and go, or edit it. The clipboard is only read if Paste and go is
        // picked, so Android's "pasted" notice never appears for nothing.
        binding.urlBar.setOnLongClickListener { v ->
            if (searchMode) return@setOnLongClickListener false
            val url = webPageUrl()
            val menu = android.widget.PopupMenu(this, v)
            if (url != null) {
                menu.menu.add(0, 1, 0, "Copy link")
                menu.menu.add(0, 2, 1, "Share\u2026")
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (cm?.primaryClipDescription?.hasMimeType("text/*") == true) menu.menu.add(0, 3, 2, "Paste and go")
            menu.menu.add(0, 4, 3, if (url != null) "Edit address" else "Search")
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> url?.let { copyText("Link", it) }
                    2 -> url?.let { shareLink(it, activeWeb()?.title ?: "") }
                    3 -> readClipboardText()?.let { go(it) }
                    4 -> enterSearchMode()
                }
                true
            }
            menu.show()
            true
        }
        // Empties the field without leaving search mode, so the next query can be
        // typed straight away. The watcher hides this button once the box is bare.
        binding.clearBtn.setOnClickListener {
            binding.urlBar.setText("")
            binding.urlBar.requestFocus()
        }
        // Same two entry points the home page field offers, so both fields behave
        // identically no matter which one the user reached.
        binding.micBtn.setOnClickListener { launchVoiceSearch() }
        binding.scanBtn.setOnClickListener { launchScan() }
        binding.urlBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(cs: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(cs: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (searchMode) {
                    fetchSuggests(e?.toString() ?: "")
                    updateSearchFieldActions()
                }
            }
        })

        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (enter) { go(binding.urlBar.text.toString()); true } else false
        }
    }

    /**
     * The bottom bar - Home, Bookmarks, the raised New tab button, Tabs and
     * Menu - and the gear at the top. Icons only, so each says what it is in
     * a long-press tooltip and to screen readers through its content
     * description. Back, Forward and Reload are the first row of the menu.
     *
     * Every button first closes an open find bar, as the system Back does:
     * the find bar is drawn over the deck and the page, so leaving it up
     * would put its controls on top of whatever the button opened.
     */
    private fun setupBars() {
        setupTabSwipe()
        setupMenuDrag()
        binding.navHome.setOnClickListener { Haptics.tap(it); leaveTransientUi(); navHome() }
        binding.navBookmarks.setOnClickListener {
            Haptics.tap(it); leaveTransientUi(); openDeck(); showBookmarks()
        }
        // Search, in the tab on screen - exactly what a tap on the search box
        // does. It opened a new tab for every search, which only filled the
        // tab list with tabs nobody went back to.
        binding.navNewTab.setOnClickListener {
            Haptics.owlRelease(it)
            binding.navOwl.pop()
            if (findActive) closeFindBar()
            if (!searchMode) enterSearchMode()
        }
        binding.navTabs.setOnClickListener { Haptics.tap(it); leaveTransientUi(); openDeck() }
        // Long-pressing Tabs also opens a new tab, as it does in Chrome.
        // (A long press brings its own vibration.)
        binding.navTabs.setOnLongClickListener {
            leaveTransientUi(); addNewTab(homePage); true
        }
        binding.navMenu.setOnClickListener {
            Haptics.tap(it)
            if (binding.menuScrim.visibility == View.VISIBLE) closeMenu()
            else { if (findActive) closeFindBar(); openMenu() }
        }
        binding.settingsBtn.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        binding.menuBack.setOnClickListener { closeMenuNow(); navBack() }
        binding.menuForward.setOnClickListener {
            closeMenuNow(); activeWeb()?.takeIf { it.canGoForward() }?.goForward()
        }
        // Held, as in Chrome: the tab's pages that way, to jump straight to one.
        binding.menuBack.setOnLongClickListener { v -> showTabHistory(v, back = true) }
        binding.menuForward.setOnLongClickListener { v -> showTabHistory(v, back = false) }
        binding.menuRefresh.setOnClickListener {
            closeMenuNow()
            val web = activeWeb()
            // What the button showed when the menu opened, not what is true a
            // moment later: a tap on Stop never turns into a reload.
            if (binding.menuRefresh.contentDescription == "Stop") web?.stopLoading()
            else web?.reload()
        }
        listOf(binding.navHome, binding.navBookmarks, binding.navNewTab, binding.navMenu,
            binding.settingsBtn, binding.menuBack, binding.menuForward, binding.menuRefresh
        ).forEach { b ->
            androidx.core.view.ViewCompat.setTooltipText(b, b.contentDescription)
        }
        androidx.core.view.ViewCompat.setTooltipText(binding.navTabs, "Tabs")
        // Each button gives under the finger and springs back (Motion).
        listOf(binding.navHome, binding.navBookmarks, binding.navTabs, binding.navMenu,
            binding.settingsBtn, binding.starBtn, binding.menuBack, binding.menuForward,
            binding.menuRefresh).forEach { Motion.pressable(it, 0.88f) }
        // The owl squashes and the phone clicks as the finger lands; held, it fluffs up.
        Motion.pressable(binding.navNewTab, 0.93f) { down ->
            binding.navOwl.press(down)
            if (down) Haptics.owlPress(binding.navNewTab)
        }
        // false: the "Search" tooltip still shows, with its own vibration.
        binding.navNewTab.setOnLongClickListener { binding.navOwl.fluff(); false }
        refreshNav()
    }

    /** Find bar and search sheet give way before a bar button acts. */
    private fun leaveTransientUi() {
        if (findActive) closeFindBar()
        if (searchMode) exitSearchMode()
    }

    /** Back for the page: the same steps as the system Back. */
    private fun navBack() {
        if (findActive) { closeFindBar(); return }
        if (searchMode) { exitSearchMode(); return }
        val web = activeWeb()
        when {
            web?.canGoBack() == true -> web.goBack()
            tabs.activeTab?.openerId != null ->
                tabs.activeTab?.let { foldBackPopup(it, "closed with Back") }
        }
    }

    /** Home, or back to the top of it when already there. */
    private fun navHome() {
        val url = tabs.activeTab?.url
        if (url == null || url == homePage) {
            activeWeb()?.evaluateJavascript(
                "window.scrollTo({top: 0, behavior: 'smooth'})", null)
        } else {
            // Through loadInto, which grants the home page's bridge its
            // privilege; a bare loadUrl would leave the news feed blank.
            loadInto(activeWeb(), homePage)
            tabs.activeTab?.let { expectPageStart(it) }
        }
    }

    private fun navIconColor(): Int =
        androidx.core.content.ContextCompat.getColor(this, R.color.navIcon)

    // The New tab button's paint, rebuilt only when the accent changes.
    private var newTabAccent = 0

    /**
     * Puts the bars in step with the tab on screen: Home filled in the accent,
     * with its dot, while home is showing; the menu's Back and Forward dimmed
     * when there is nowhere to go. Called on every load, history change and
     * tab switch, and when the accent changes.
     */
    private fun refreshNav() {
        if (!::binding.isInitialized) return
        val web = activeWeb()
        val tab = tabs.activeTab
        val neutral = android.content.res.ColorStateList.valueOf(
            if (bottomOnDark) android.graphics.Color.WHITE else navIconColor())
        val accent = currentAccent()
        val accentTint = android.content.res.ColorStateList.valueOf(accent)
        val onHome = tab == null || tab.url == homePage
        binding.navHomeIcon.setImageResource(
            if (onHome) R.drawable.nav_home_active else R.drawable.nav_home)
        binding.navHomeIcon.imageTintList = if (onHome) accentTint else neutral
        binding.navHomeDot.backgroundTintList = accentTint
        binding.navHomeDot.visibility = if (onHome) View.VISIBLE else View.INVISIBLE
        binding.navBookmarks.imageTintList = neutral
        binding.navTabsIcon.imageTintList = neutral
        binding.navTabsCount.backgroundTintList = accentTint
        binding.navMenu.imageTintList = neutral
        binding.settingsBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            if (topOnDark) android.graphics.Color.WHITE else navIconColor())
        setNavEnabled(binding.menuBack, web?.canGoBack() == true || tab?.openerId != null)
        setNavEnabled(binding.menuForward, web?.canGoForward() == true)
        if (accent != newTabAccent) paintNewTabButton(accent)
    }

    /**
     * The New tab button: a circle running from a lighter to a deeper shade of
     * the accent, sitting in the bar's arc, whose ring carries a trace of the
     * same accent. On Android 9 and up its shadow is in the accent rather than
     * grey.
     */
    private fun paintNewTabButton(accent: Int) {
        newTabAccent = accent
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent, hsv)
        val light = android.graphics.Color.HSVToColor(floatArrayOf(
            hsv[0], (hsv[1] * 0.80f).coerceIn(0f, 1f), (hsv[2] * 1.10f).coerceIn(0f, 1f)))
        val deep = android.graphics.Color.HSVToColor(floatArrayOf(
            hsv[0], (hsv[1] * 1.15f).coerceIn(0f, 1f), (hsv[2] * 0.82f).coerceIn(0f, 1f)))
        binding.navNewTab.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(light, deep)
        ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        binding.navNewTab.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        binding.navSheet.accent = accent
        // The loading ring around the button runs the same accent.
        binding.loadRing.setAccent(accent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.navNewTab.outlineSpotShadowColor = accent
            binding.navNewTab.outlineAmbientShadowColor = accent
        }
    }

    // ---------- Swipe between tabs ----------
    //
    // A sideways swipe across the bottom bar moves between tabs, as on
    // Chrome's toolbar. The page goes with the finger and the neighbouring
    // tab's picture comes in beside it. On release a spring finishes the move,
    // carrying the finger's speed: through to the neighbour if the page went
    // over a third of the way or was flicked that way, back otherwise. Past
    // the first or last tab the page only gives a little, like rubber.
    // Swiping left brings in the tab after this one, as in Chrome.

    private var swipeNeighbor: Tab? = null
    /** -1: moving to the next tab, +1: to the previous one, 0: not decided. */
    private var swipeDir = 0
    private val swipeSpring by lazy {
        Spring(stiffness = 340f, dampingRatio = 0.9f, precision = 0.5f) { placeSwipe(it) }
    }

    private fun setupTabSwipe() {
        binding.bottomBar.onSwipeStart = { beginSwipe() }
        binding.bottomBar.onSwipe = { dx ->
            swipeSpring.cancel()
            placeSwipe(followSwipe(dx))
        }
        binding.bottomBar.onSwipeEnd = { dx, vx -> finishSwipe(followSwipe(dx), vx) }
    }

    private fun beginSwipe(): Boolean {
        if (searchMode || deckVisible || swipeSpring.isRunning || activeWeb() == null) return false
        if (binding.menuScrim.visibility == View.VISIBLE) return false
        swipeDir = 0
        swipeNeighbor = null
        // The deck's picture of this tab, taken while it is still in place.
        tabs.activeTab?.let { captureThumbnail(it) }
        return true
    }

    private fun followSwipe(dx: Float): Float {
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

    private fun placeSwipe(x: Float) {
        val w = binding.webArea.width.toFloat()
        binding.webContainer.translationX = x
        binding.adSlot.translationX = x
        binding.tabPeek.translationX = if (x < 0f) x + w else x - w
    }

    private fun finishSwipe(x: Float, vx: Float) {
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

    private fun endSwipe() {
        swipeSpring.cancel()
        placeSwipe(0f)
        binding.tabPeek.visibility = View.GONE
        binding.tabPeek.setImageDrawable(null)
        swipeNeighbor = null
        swipeDir = 0
    }

    // ---------- Pull to refresh ----------
    //
    // Pulling a web page down past its top brings a refresh disc down after
    // it. The disc follows the finger one to one at first and then ever more
    // stiffly, like stretched rubber; let go past the trigger and it holds and
    // spins while the page reloads, let go short of it and it springs back up.
    // Driven by the page's own overscroll (BrowserWebView), so it only answers
    // once the page really is at its top - not while a list inside it scrolls.

    private var pullRaw = 0f
    private var pullRefreshing = false
    private var pullSpin: android.animation.ObjectAnimator? = null
    private var pullStartedAt = 0L
    private val pullTimeout = Runnable { endPullRefresh() }
    private val pullSpring by lazy { Spring(stiffness = 420f, dampingRatio = 0.72f, precision = 0.5f) { placePull(it) } }
    private val pullTrigger get() = 84f * resources.displayMetrics.density
    private val pullReach get() = 140f * resources.displayMetrics.density
    private val pullHold get() = 64f * resources.displayMetrics.density

    private fun pullBy(px: Int) {
        if (pullRefreshing || searchMode || deckVisible) return
        val url = tabs.activeTab?.url
        if (url == null || url == homePage) return
        pullSpring.cancel()
        pullRaw += px
        placePull(rubber(pullRaw))
    }

    // 1:1 at the start, approaching pullReach and never passing it.
    private fun rubber(raw: Float): Float = pullReach * (1f - kotlin.math.exp(-raw / pullReach))

    private fun placePull(y: Float) {
        val disc = binding.pullIndicator
        val size = disc.height.takeIf { it > 0 } ?: (40f * resources.displayMetrics.density).toInt()
        if (y <= 0.5f && !pullRefreshing) {
            disc.visibility = View.GONE
            return
        }
        if (disc.visibility != View.VISIBLE) {
            disc.imageTintList = android.content.res.ColorStateList.valueOf(newTabAccent)
            disc.visibility = View.VISIBLE
        }
        disc.translationY = y - size
        val armed = (y / pullTrigger).coerceIn(0f, 1f)
        disc.alpha = 0.4f + 0.6f * armed
        disc.scaleX = 0.7f + 0.3f * armed
        disc.scaleY = disc.scaleX
        if (!pullRefreshing) disc.rotation = y * 1.6f
    }

    private fun releasePull() {
        if (pullRaw <= 0f) return
        val y = rubber(pullRaw)
        pullRaw = 0f
        if (pullRefreshing) return
        if (y < pullTrigger) {
            pullSpring.animate(y, 0f)
            return
        }
        pullRefreshing = true
        pullStartedAt = android.os.SystemClock.uptimeMillis()
        // A small tick in the hand as the refresh takes, as Chrome gives.
        Haptics.confirm(binding.pullIndicator)
        pullSpring.animate(y, pullHold)
        val disc = binding.pullIndicator
        pullSpin = android.animation.ObjectAnimator.ofFloat(disc, View.ROTATION, disc.rotation, disc.rotation + 360f).apply {
            duration = 700L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
        activeWeb()?.reload()
        // A reload that never reports back still lets the disc go.
        uiHandler.removeCallbacks(pullTimeout)
        uiHandler.postDelayed(pullTimeout, 10_000L)
    }

    /** The reload has landed - or the tab changed. The disc goes back up. */
    private fun endPullRefresh(now: Boolean = false) {
        pullRaw = 0f
        if (!pullRefreshing) {
            if (now) { pullSpring.cancel(); placePull(0f) }
            return
        }
        // At least a moment of spinning, or a fast reload reads as a flicker.
        val shown = android.os.SystemClock.uptimeMillis() - pullStartedAt
        if (!now && shown < 450L) {
            uiHandler.postDelayed({ endPullRefresh() }, 450L - shown)
            return
        }
        pullRefreshing = false
        uiHandler.removeCallbacks(pullTimeout)
        pullSpin?.cancel()
        pullSpin = null
        if (now) {
            pullSpring.cancel()
            placePull(0f)
        } else pullSpring.animate(pullSpring.value, 0f)
    }

    private fun setNavEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.38f
    }

    private fun setupToolbar() {

        setupBars()
        // Re-read rather than trusting what openMenu painted: a page can
        // finish loading, or a tab be restored, between the menu opening and
        // this row being pressed, and a row that says one thing while doing
        // another is worse than either.
        binding.menuHome.setOnClickListener {
            val url = tabs.activeTab?.url
            val onHome = (url == null || url == homePage)
            closeMenuNow()
            // Quitting is destructive and keeps its confirmation. Going home is
            // not, so it just happens - and through loadInto, which is what
            // grants the home page's JS bridge its privilege; a bare loadUrl
            // would leave it unprivileged and the news feed blank.
            if (onHome) showOwlCloseDialog() else loadInto(activeWeb(), homePage)
        }
        applyAccentTints()

        // Menu scrim tap closes the menu
        binding.menuScrim.setOnClickListener { closeMenu() }

        setupMenuGroups()

        // Menu items
        binding.menuNewTab.setOnClickListener {
            closeMenuNow(); addNewTab(homePage)
        }
        binding.menuNightOwl.setOnClickListener {
            closeMenuNow()
            if (nightOwl) exitNightOwl() else enterNightOwl()
        }
        binding.menuDesktop.setOnClickListener {
            val current = activeWeb()?.url
            if (current == null || current == homePage) {
                closeMenuNow()
                android.widget.Toast.makeText(this,
                    "Open a page first — nothing to switch to desktop",
                    android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val on = !Settings.getBool(this, Settings.DESKTOP_MODE, false)
            Settings.setBool(this, Settings.DESKTOP_MODE, on)
            closeMenuNow()
            applyDesktopMode(on)
            android.widget.Toast.makeText(this,
                if (on) "Desktop site on" else "Desktop site off",
                android.widget.Toast.LENGTH_SHORT).show()
        }
        binding.menuHistory.setOnClickListener {
            closeMenuNow(); openDeck(); showHistory()
        }
        binding.menuDownloads.setOnClickListener {
            closeMenuNow()
            openDownloads()
        }
        binding.menuFind.setOnClickListener {
            closeMenuNow()
            openFindBar()
        }
        binding.menuZoom.setOnClickListener {
            closeMenuNow()
            showZoom()
        }
        binding.menuPrint.setOnClickListener {
            closeMenuNow()
            printPage()
        }
        binding.menuReopen.setOnClickListener {
            closeMenuNow()
            reopenClosedTab(inDeck = false)
        }
        binding.menuShare.setOnClickListener {
            closeMenuNow()
            val url = webPageUrl() ?: run { needAPage(); return@setOnClickListener }
            shareLink(url, activeWeb()?.title ?: "")
        }
        binding.menuTranslate.setOnClickListener {
            closeMenuNow()
            translatePage()
        }
        binding.menuReader.setOnClickListener {
            closeMenuNow()
            toggleReaderView()
        }
        binding.menuAddHome.setOnClickListener {
            closeMenuNow()
            addToHomeScreen()
        }
        binding.menuSiteAds.setOnClickListener {
            closeMenuNow()
            val host = siteForAds() ?: return@setOnClickListener
            val allowed = AdBlockSites.isAllowed(this, host)
            if (allowed) AdBlockSites.block(this, host) else AdBlockSites.allow(this, host)
            val name = AdBlockSites.normalize(host) ?: host
            toast(if (allowed) "Ads blocked on $name" else "Ads allowed on $name")
            // Blocking acts on requests, so the page is fetched again under
            // the new rule.
            activeWeb()?.reload()
        }
        binding.menuSupport.setOnClickListener {
            closeMenuNow()
            startActivity(android.content.Intent(this, SupportActivity::class.java))
        }
        binding.menuGames.setOnClickListener {
            closeMenuNow()
            if (Settings.getBool(this, Settings.GAMES_INTRO_SEEN, false)) {
                openGames()
            } else {
                showGamesWelcome()
            }
        }

        binding.starBtn.setOnClickListener { toggleBookmark() }
    }

    // ---------- In-app updates (Google Play) ----------
    private fun checkForUpdate(fromUser: Boolean) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                val available = info.updateAvailability() ==
                    com.google.android.play.core.install.model.UpdateAvailability.UPDATE_AVAILABLE
                val allowsFlexible = info.isUpdateTypeAllowed(
                    com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE)
                if (available && allowsFlexible) {
                    try {
                        appUpdateManager.startUpdateFlowForResult(
                            info,
                            updateLauncher,
                            com.google.android.play.core.appupdate.AppUpdateOptions.newBuilder(
                                com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE
                            ).build()
                        )
                    } catch (e: Exception) { /* ignore */ }
                } else if (fromUser) {
                    android.widget.Toast.makeText(this,
                        "You're on the latest version", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                if (fromUser) android.widget.Toast.makeText(this,
                    "Couldn't check for updates", android.widget.Toast.LENGTH_SHORT).show()
            }
    }

    // Public entry point for the Settings "Check for updates" row.
    fun checkForUpdateFromSettings() = checkForUpdate(fromUser = true)

    // ---------- In-app review (Google Play) ----------
    /**
     * Asks Play to show its review dialog, if this is a fair moment to.
     *
     * Deliberately has no caller anywhere the user can reach. Play's policy
     * forbids a "Rate us" button and forbids gating the prompt behind a
     * question like "enjoying the app?" - showing the real dialog only to
     * people who answered yes is review gating, which is a violation rather
     * than a funnel. So this fires on its own or not at all.
     *
     * Nothing may be branched on the outcome. The listener below is called
     * whether the user wrote a review, dismissed the sheet, or never saw one
     * because Play's quota suppressed it - the API reports none of that, by
     * design, so that apps cannot treat reviewers differently.
     */
    private fun maybeAskForReview() {
        if (isFinishing || isDestroyed) return
        // Never during a private session, and never over something else -
        // the ad consent message included.
        if (nightOwl || searchMode || deckVisible || AdConsent.formShowing) return
        val current = tabs.activeTab?.url
        if (!(current == null || current == homePage)) return
        if (!Reviews.eligible(this)) return
        // One ask per launch: the default-browser offer stands aside.
        offeredThisLaunch = true

        // FakeReviewManager runs the whole flow and shows nothing, which is
        // the only way to prove the plumbing works: the real dialog cannot be
        // summoned on demand, since quota decides whether it appears.
        val manager = if (BuildConfig.DEBUG) {
            com.google.android.play.core.review.testing.FakeReviewManager(this)
        } else {
            com.google.android.play.core.review.ReviewManagerFactory.create(this)
        }

        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) return@addOnCompleteListener
            if (isFinishing || isDestroyed) return@addOnCompleteListener
            manager.launchReviewFlow(this, request.result).addOnCompleteListener {
                // Recorded on the attempt, not on a result, because there is
                // no result to wait for. If quota swallowed the dialog this
                // spends the window on nothing - which is the trade for never
                // pestering someone who did see it and closed it.
                Reviews.noteAsked(this)
                if (BuildConfig.DEBUG) toast("Review flow ran (fake in debug)")
            }
        }
    }

    // ---------- Media control notification ----------
    private var mediaActive = false

    private fun onMediaState(state: String, title: String, host: String) {
        when (state) {
            "playing", "paused" -> {
                ensureNotifPermission()
                val intent = android.content.Intent(this, MediaService::class.java).apply {
                    action = MediaService.ACTION_UPDATE
                    putExtra(MediaService.EXTRA_TITLE, if (title.isBlank()) "Media" else title)
                    putExtra(MediaService.EXTRA_HOST, host)
                    putExtra(MediaService.EXTRA_PLAYING, state == "playing")
                }
                try {
                    androidx.core.content.ContextCompat.startForegroundService(this, intent)
                    mediaActive = true
                } catch (e: Exception) { /* ignore */ }
            }
            "none" -> stopMediaService()
        }
    }

    private fun stopMediaService() {
        if (!mediaActive) return
        mediaActive = false
        try {
            startService(android.content.Intent(this, MediaService::class.java).apply {
                action = MediaService.ACTION_STOP
            })
        } catch (e: Exception) { /* ignore */ }
    }

    private fun launchScan() {
        val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue?.trim()
                if (!value.isNullOrEmpty()) go(value)
            }
            .addOnCanceledListener { /* user closed the scanner */ }
            .addOnFailureListener { e ->
                android.widget.Toast.makeText(this,
                    "Scanner unavailable", android.widget.Toast.LENGTH_SHORT).show()
            }
    }

    private fun launchVoiceSearch() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to search")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this,
                "Voice search not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens a Play Store listing (play.google.com/store/apps/details?id=...)
     * in the Play Store app. False when [url] is not one, or there is no Play
     * Store on the phone - the listing then opens as a web page.
     */
    private fun openPlayListing(url: String): Boolean {
        val uri = try { android.net.Uri.parse(url) } catch (e: Exception) { return false }
        if (uri.host?.lowercase() != "play.google.com" || uri.path != "/store/apps/details") return false
        val id = uri.getQueryParameter("id")?.takeIf { Shortcuts.PLAY_ID.matches(it) } ?: return false
        return try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=$id"))
                .setPackage("com.android.vending")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Hands a non-web link to whichever app on the phone claims it.
     *
     * intent:// URLs are unpacked rather than passed straight through, and
     * stripped of the parts that would turn a link into a way to reach inside
     * another app - or back into this one. A page gets to name an action; it
     * does not get to name a component. Any URI grant a page tried to attach is
     * dropped for the same reason.
     *
     * Nothing here uses resolveActivity: under package visibility it reports
     * "nothing handles this" for apps we cannot see, while startActivity would
     * have worked. Trying and catching is both simpler and more accurate.
     *
     * Returns true only when a web fallback is being loaded into [from], which
     * is the one outcome that leaves something to show in that tab.
     */
    private fun openExternal(url: String, from: WebView?): Boolean {
        val isIntentUri = url.startsWith("intent://", ignoreCase = true)
        val intent = try {
            if (isIntentUri) {
                android.content.Intent.parseUri(
                    url, android.content.Intent.URI_INTENT_SCHEME
                ).apply {
                    component = null
                    selector = null
                    addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                    // A web page must not be able to open this app's own screens.
                    if (`package` == packageName) `package` = null
                }
            } else {
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            }
        } catch (e: Exception) {
            return false
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.flags = intent.flags and
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION.inv() and
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv()
        try {
            startActivity(intent)
            return false
        } catch (e: Exception) { /* no app claims it - fall through */ }

        // intent:// can name a web page to use when the app isn't installed.
        val fallback = try {
            if (isIntentUri) android.content.Intent.parseUri(
                url, android.content.Intent.URI_INTENT_SCHEME
            ).getStringExtra("browser_fallback_url") else null
        } catch (e: Exception) { null }
        if (!fallback.isNullOrBlank() &&
            (fallback.startsWith("http://") || fallback.startsWith("https://"))
        ) {
            // Into the tab the link was followed in, which is not always the
            // one on screen, and after the callback that asked - see
            // loadAfterCallback for why never during it.
            loadAfterCallback(from ?: activeWeb(), fallback)
            return true
        }
        // Say so plainly. The old path showed "this site can't be reached",
        // which blamed the site for a missing app.
        android.widget.Toast.makeText(
            this, "No app on this phone opens that kind of link",
            android.widget.Toast.LENGTH_SHORT).show()
        return false
    }

    /**
     * Pulls a web link out of an incoming intent, if any:
     *  - ACTION_VIEW carries the link in the intent data (tapped links).
     *  - ACTION_SEND carries it in EXTRA_TEXT (shared links).
     * Returns the URL string, or null if the intent has no usable link.
     */
    private fun urlFromIntent(intent: android.content.Intent?): String? {
        if (intent == null) return null
        when (intent.action) {
            android.content.Intent.ACTION_VIEW -> {
                val d = intent.dataString?.trim()
                if (!d.isNullOrEmpty()) return d
            }
            android.content.Intent.ACTION_SEND -> {
                val t = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.trim()
                if (!t.isNullOrEmpty()) return t
            }
        }
        return null
    }

    /**
     * What the user typed, or what another app handed over, turned into an
     * address to load. Shared so a link arriving by intent is treated exactly
     * like one typed into the bar, rather than skipping these rules.
     */
    private fun resolveInput(input: String): String {
        var url = UrlHelper.toUrlOrSearch(input, Settings.getEngineUrl(this))
        if (httpsOnly() && url.startsWith("http://")) {
            val host = try {
                android.net.Uri.parse(url).host
            } catch (e: Exception) { null }
            // A host the user has already said yes to stays as typed.
            if (host == null || !httpAllowed.contains(host)) {
                val https = "https://" + url.removePrefix("http://")
                // Recorded so that if https fails, the failure is met with the
                // warning and a way through, not "site can't be reached".
                upgradedFrom[https] = url
                if (host != null) {
                    upgradedAt[host] = android.os.SystemClock.uptimeMillis()
                }
                url = https
            }
        }
        return url
    }

    private fun go(input: String) {
        tabs.activeTab?.fromApp = false
        if (searchMode) exitSearchMode()
        // An empty bar has nothing to go to. It used to load a blank search.
        if (input.isBlank()) { hideKeyboard(); return }
        loadInto(activeWeb(), resolveInput(input))
        hideKeyboard()
        activeWeb()?.requestFocus()
    }

    private fun activeWeb(): WebView? = tabs.activeTab?.webView

    /** The open-tab count, on the badge at the Tabs button's corner. */
    private fun updateTabCount() {
        val n = tabs.count()
        // A circle for one digit and a pill for two; past 99 there is no
        // room, so it says so the way Chrome does.
        binding.navTabsCount.text = if (n > 99) ":D" else n.toString()
        binding.navTabs.contentDescription = if (n == 1) "1 tab" else "$n tabs"
    }

    // Everything that carries the accent on the home page carries it here too:
    // the tab count, and the mic and scan glyphs, which home draws in var(--accent).
    /**
     * The accent chosen for the owl, or the default when the stored value is
     * unparseable. Extracted so the tab deck and the chrome cannot disagree
     * about what the accent is.
     */
    private fun currentAccent(): Int = try {
        android.graphics.Color.parseColor(Settings.getHomeAccent(this))
    } catch (e: Exception) {
        android.graphics.Color.parseColor("#8B6BD8")
    }

    private fun applyAccentTints() {
        val accent = currentAccent()

        val tint = android.content.res.ColorStateList.valueOf(accent)
        binding.micBtn.imageTintList = tint
        binding.scanBtn.imageTintList = tint
        // The deck's "+ New" and "Done" too; they were a fixed blue.
        Settings.accentInk(this).let { ink ->
            binding.deckNewTab.setTextColor(ink)
            binding.deckClose.setTextColor(ink)
        }
        // Selected Home wears the accent, so the bars follow it too.
        refreshNav()
        applyArtAccent(accent)
        pushAccentToPage(activeWeb())
    }

    private var owlAccent = 0

    /**
     * Moves the owl's body colour onto the accent by rotating hue, rather than
     * tinting. A tint list would paint every pixel one colour and collapse the
     * mark into a silhouette - eyes, beak and shading gone.
     *
     * Only the purple family moves. The eye discs and pupils are near-white and
     * near-black, so their hue carries no colour to rotate, and the beak sits
     * far enough off the body hue to fall outside the band. Saturation and
     * value are left alone, so the two-tone shading is preserved exactly.
     */
    private var owlArt: android.graphics.Bitmap? = null

    /**
     * Hands the accent to whichever asset page is loaded. Values are rebuilt
     * from the parsed colour rather than passed through, so nothing out of
     * settings reaches a page as script. A no-op on real sites, which do not
     * define the hook.
     */
    private fun pushAccentToPage(web: android.webkit.WebView?) {
        val accent = currentAccent()
        val target = FloatArray(3)
        android.graphics.Color.colorToHSV(accent, target)
        val base = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.parseColor("#8B6BD8"), base)
        var shift = target[0] - base[0]
        if (shift > 180f) shift -= 360f
        if (shift < -180f) shift += 360f
        val hex = String.format("#%06X", 0xFFFFFF and accent)
        web?.evaluateJavascript(
            "window.__setAccent && window.__setAccent('$hex', ${shift.toInt()})", null)
    }

    private fun applyArtAccent(accent: Int) {
        if (accent == owlAccent) return
        owlAccent = accent
        owlArt = recolouredToAccent(R.drawable.ic_owl, accent)
        owlArt?.let { binding.homeBtn.setImageBitmap(it) }
        // The settings glyph and the reload ring were recoloured here while
        // they lived in the bar.
        // The menu's own glyphs are handled by applyMenuAccent, which tints
        // rather than hue-shifts because they are single tone.
        applyMenuAccent(accent)
    }

    // Menu glyphs are single-tone vectors, so a tint is exactly right here -
    // there is no second colour for it to flatten. The rows carry ids but the
    // icons inside them do not, so the panel is walked instead.
    private fun applyMenuAccent(accent: Int) {
        val tint = android.content.res.ColorStateList.valueOf(accent)
        fun walk(v: View) {
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            } else if (v is android.widget.ImageView && v.tag != "chevron") {
                v.imageTintList = tint
            }
        }
        walk(binding.menuPanel)
    }

    /**
     * Swaps every owl in an inflated tree for the recoloured one. Matched on
     * constant state rather than id: all four dialogs draw the same resource,
     * and drawables loaded from one resource share it.
     */
    private fun applyOwlArtIn(root: View) {
        applyAccentIn(root)
        val art = owlArt ?: return
        val ref = androidx.core.content.ContextCompat
            .getDrawable(this, R.drawable.ic_owl)?.constantState ?: return
        fun walk(v: View) {
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            } else if (v is android.widget.ImageView && v.drawable?.constantState == ref) {
                v.setImageBitmap(art)
            }
        }
        walk(root)
    }

    /**
     * The owl's dialogs in the accent the user picked: the filled button (deep
     * ink and white text on light, light ink and dark text on dark, so it
     * reads either way) and the text buttons beside it. They were a fixed
     * purple whatever the accent.
     */
    private fun applyAccentIn(root: View) {
        val ink = Settings.accentInk(this)
        val brand = 0xFF5E44A0.toInt()
        val filled = androidx.core.content.ContextCompat
            .getDrawable(this, R.drawable.dialog_btn_filled)?.constantState
        fun walk(v: View) {
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
                return
            }
            val t = v as? android.widget.TextView ?: return
            if (filled != null && t.background?.constantState == filled) {
                t.backgroundTintList = android.content.res.ColorStateList.valueOf(ink)
                t.setTextColor(if (isDarkTheme()) 0xFF141416.toInt() else android.graphics.Color.WHITE)
            } else if (t.currentTextColor == brand) {
                t.setTextColor(ink)
            }
        }
        walk(root)
    }

    /**
     * Draws a drawable that has no bitmap of its own into one.
     *
     * Four times its intrinsic size deliberately. This runs so the accent
     * pass has PIXELS to hue-shift, and whatever is rendered here is the
     * resolution the icon ends up being displayed at - a 24dp vector has a
     * 96px intrinsic size at xxxhdpi while the button it goes in draws 36dp,
     * which is 144px. Rendering at intrinsic size would put the icon straight
     * back to being upscaled, which is the whole thing this replaced.
     */
    private fun rasterise(d: android.graphics.drawable.Drawable): android.graphics.Bitmap? {
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        val bmp = android.graphics.Bitmap.createBitmap(
            w * 4, h * 4, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w * 4, h * 4)
        d.draw(android.graphics.Canvas(bmp))
        return bmp
    }

    private fun recolouredToAccent(resId: Int, accent: Int): android.graphics.Bitmap? {
        val art = androidx.core.content.ContextCompat.getDrawable(this, resId)
            ?: return null
        // A vector has no bitmap to read. Before this it fell straight through
        // the null branch below and the icon quietly stopped taking the accent.
        val src = (art as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: rasterise(art) ?: return null

        val target = FloatArray(3)
        android.graphics.Color.colorToHSV(accent, target)
        val base = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.parseColor("#8B6BD8"), base)

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        for (i in px.indices) {
            val c = px[i]
            if (android.graphics.Color.alpha(c) == 0) continue
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.15f) continue
            var delta = hsv[0] - base[0]
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            if (kotlin.math.abs(delta) > 45f) continue
            hsv[0] = ((target[0] + delta) % 360f + 360f) % 360f
            px[i] = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(c), hsv)
        }
        val out = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    }

}
