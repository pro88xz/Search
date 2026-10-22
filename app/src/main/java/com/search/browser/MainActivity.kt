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
        internal val cookieIo = java.util.concurrent.Executors.newSingleThreadExecutor()

        // History is rewritten whole on every page load; one thread keeps
        // those writes in order and off the main thread.
        private val historyIo = java.util.concurrent.Executors.newSingleThreadExecutor()

        /**
         * One thread for suggestions: each keystroke used to start a thread of
         * its own with a network request in it, so fast typing had a dozen
         * requests in flight at once, most of them for queries already typed
         * past. A query superseded before its turn is skipped.
         */
        internal val suggestIo = java.util.concurrent.Executors.newSingleThreadExecutor()

        /**
         * How long a sign-in pop-up may sit on the site's own page, back from
         * the provider and showing nothing, before it is taken to have
         * stalled. A callback page that works closes itself well inside this.
         */
        internal const val POPUP_STALL_MS = 3500L

        /** The second look at a returned pop-up, before it is judged stalled. */
        internal const val POPUP_RECHECK_MS = 4000L

        /**
         * How long the page behind a returned sign-in gets to show something
         * before it is reloaded. Longer than the pop-up's allowance: a heavy
         * site can spend a few seconds drawing nothing while it starts up.
         */
        internal const val OPENER_STALL_MS = 6000L

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
    internal val uiHandler = Handler(Looper.getMainLooper())

    // ---- Native search-page mode ----
    internal var searchMode = false
    // True when the home page is scrolled past its in-page search pill, so the
    // native top bar shows the pill instead of the icon row.
    internal var homeCompact = false
    private var lastFailedUrl: String? = null

    // Tracks the site-settings signature last applied, so we only reload when it changed.
    private var lastSiteSig: String = ""

    // True while the window is the small floating PiP one.
    internal var inPip = false

    // ---- HTML5 fullscreen video ----
    internal var fullscreenView: View? = null
    internal var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    internal var fullscreenContainer: android.widget.FrameLayout? = null
    internal var savedOrientation =
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // Voice search launcher (RecognizerIntent -> go()).
    internal val voiceLauncher = registerForActivityResult(
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
    internal fun ensureNotifPermission() {
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
    internal data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )
    internal var pendingDownload: PendingDownload? = null
    // Same deal for a long-pressed image on API <= 28.
    internal var pendingImageSave: String? = null
    // One-shot ticket for a blob: save. Minted when the user taps Save and
    // spent on first use - the bridge is on every page, so without this any
    // site could write to the gallery unprompted.
    @Volatile internal var blobSaveToken: String? = null
    // Same one-shot idea for a blob: download, one ticket per file being
    // saved. The bridge is reachable from every page, so without a ticket any
    // site could drop a file into the user's Downloads folder unprompted.
    internal class BlobSave(val name: String, val mime: String?) {
        @Volatile var sink: DownloadSink? = null
        @Volatile var total = -1L
        @Volatile var progress: SaveProgress? = null
        @Volatile var checkedAt = 0L
    }
    internal val blobSaves = java.util.concurrent.ConcurrentHashMap<String, BlobSave>()
    internal val storagePermLauncher = registerForActivityResult(
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

    /**
     * The file picker for a page's file input. WebView's own intent took the
     * first accepted type only and ignored "multiple": a shop's upload of
     * several product photos let one be picked, and an input that accepts
     * ".csv,.xlsx" (a bulk product upload) was handed ".csv" as a type, which
     * matches no file, so everything was greyed out. Now: several files when
     * the input takes several; real media types (any image, video/mp4) as
     * themselves, several at once if there are several; and anything given
     * as a file extension opens the picker on every file, the site checking
     * what it gets as it always does.
     */
    private fun fileChooserIntent(params: WebChromeClient.FileChooserParams?): android.content.Intent {
        val accept = params?.acceptTypes.orEmpty()
            .flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val types = LinkedHashSet<String>()
        var any = accept.isEmpty()
        for (a in accept) {
            if (a.contains('/') && !a.startsWith('.')) types.add(a) else any = true
        }
        val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE)
        when {
            any || types.isEmpty() -> i.type = "*/*"
            types.size == 1 -> i.type = types.first()
            else -> {
                i.type = "*/*"
                i.putExtra(android.content.Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            }
        }
        if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            i.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return i
    }

    internal fun hasNetwork(): Boolean {
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
            // Wi-Fi to mobile data and back: Save data on mobile data follows.
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities
            ) {
                runOnUiThread { if (!isFinishing && !isDestroyed) applyDataSaverNow() }
            }
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

    internal var loadWatchTab: Tab? = null
    internal var loadWatchHost = ""
    internal var loadStartedAt = 0L
    internal var loadCommitted = false
    internal var loadInterrupted = false
    private val loadStatusTick = Runnable { updateLoadStatus() }

    /** The address a load broken by a network change was last retried at. */
    private var networkRetryUrl: String? = null

    /** When a renderer was last lost; one loss reaches every tab it served. */
    internal var lastRendererLoss = 0L

    internal fun updateLoadStatus() {
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

    /** What lastSiteSig last applied for pictures; Save data on mobile data changes it between networks. */
    internal var lastImagesBlocked: Boolean? = null

    internal var suggestAdapter: SuggestAdapter? = null
    @Volatile internal var suggestSeq = 0

    // The suggestion backdrop's own bottom padding from the layout, kept so
    // the keyboard inset can be added on top of it rather than replacing it.
    // Each overlay's own bottom padding, before reachUnderNavBar adds to it.
    internal val navPadBase = HashMap<View, Int>()

    internal var suggestBasePad = -1

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
    internal val FIELD_NORMAL = 0
    internal val FIELD_COMPACT = 1
    internal val FIELD_SEARCH = 2

    // The pill's height in each state, in dp. Named rather than written into
    // the when below, because the collapse animation has to travel between two
    // of them instead of jumping.
    internal val FIELD_H_NORMAL = 48f
    // Pinned to the top of the home page, and searching: 44 in the 58dp bar,
    // so the box sits centred with 7dp of air above and below, as Chrome's
    // does, rather than filling the bar and crowding the status bar and any
    // camera cutout above it. The buttons inside stay centred.
    internal val FIELD_H_COMPACT = 44f

    /**
     * How far the search pill travels as it slides up into the bar, in dp.
     * Small on purpose: this is a dock, not an entrance.
     */
    internal val SLIDE_DP = 12f
    private val FIELD_H_SEARCH = 44f

    /** The style styleUrlBar last gave the field. */
    internal var fieldMode = FIELD_NORMAL

    internal fun styleUrlBar(mode: Int) {
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

    // ---------- The site's icon in the search field ----------

    /** Rounded corners, as a launcher draws a site's icon. */
    internal object SiteIconOutline : android.view.ViewOutlineProvider() {
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
    internal val searchEase = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

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
    internal val httpAllowed = mutableSetOf<String>()
    /** https addresses produced here, mapped back to the http they came from. */
    internal val upgradedFrom = HashMap<String, String>()
    /** When each host was last upgraded, to break a redirect loop. */
    internal val upgradedAt = HashMap<String, Long>()
    internal var pendingInsecure: String? = null

    // Night Owl (private browsing) mode state.
    internal var nightOwl = false

    override fun onResume() {
        super.onResume()
        // The browser is on top again, so no ad is: the splash picture laid
        // behind one comes off even if the ad's "closed" never arrived -
        // left up, it covered the browser for good, and Back (which it
        // holds while it is up) did nothing: the app looked frozen.
        if (launchCover != null && coverAdShowing) hideLaunchCover()
        // The Download video button may have been switched off in Settings.
        updateVideoFab()
        // Night Owl's screenshot and recents protection may have changed in
        // Settings; and Night Owl locked on the way out asks to be unlocked.
        applyNightOwlSecure()
        if (nightOwlLocked) {
            if (!nightOwl || !nightOwlLockOn()) { nightOwlLocked = false; hideNightOwlLock() }
            else {
                showNightOwlLock()
                // Asked by itself once per lock: a prompt cancelled (which can
                // pause and resume this screen) is not asked again on its own
                // - Unlock on the lock asks again.
                if (!unlocking && !lockPrompted) { lockPrompted = true; promptUnlock() }
            }
        }
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
        if (!tintBarsPref) plainBars(tabs.activeTab?.url, animate = false)
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
        if (Settings.getBool(this, Settings.IS_SUPPORTER, false)) dropExtraAds()
        val sig = siteSettingsSignature()
        if (sig == lastSiteSig) return  // nothing changed -> don't touch anything
        lastSiteSig = sig

        val js = Settings.getBool(this, Settings.SITE_JAVASCRIPT, true)
        val blockImg = imagesBlocked()
        lastImagesBlocked = blockImg
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

    internal val tabStore: TabStore by viewModels()

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

    internal val sessionSave = Runnable { saveSession(full = false) }

    /**
     * Collects the session on the main thread, which WebView.saveState needs,
     * and hands it to SessionStore to write. [full] refreshes every live tab's
     * history - for when the app is being left. Otherwise only the tab on
     * screen, which is the one that moved, plus any tab with none on disk yet.
     */
    internal fun saveSession(full: Boolean) {
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

    internal lateinit var binding: ActivityMainBinding
    internal val homePage = "file:///android_asset/home.html"
    internal val tabs = TabManager(maxLiveTabs = 3)
    internal lateinit var tabAdapter: TabAdapter

    internal val thumbWidthPx = 400
    // Capture the deck thumbnail at reduced resolution to cut the
    // main-thread bitmap allocation (full-screen was ~5MB per open).
    // PixelCopy scales the source rect into this smaller dest bitmap.
    internal val thumbCaptureDivisor = 2
    internal var deckVisible = false
    // Held true to keep the system splash on its final frame briefly so the
    // launch animation lands cleanly before the browser appears.
    internal var keepSplash = true
    internal val appUpdateManager by lazy {
        com.google.android.play.core.appupdate.AppUpdateManagerFactory.create(this)
    }
    internal val updateLauncher = registerForActivityResult(
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
                    // Night Owl locked: nothing under the lock is reachable;
                    // Back leaves, as it would from a lock screen.
                    nightOwlLocked -> { moveTaskToBack(true) }
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
                    backInTab(web) -> {}
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
                    // An error page with no page of its own behind it (the
                    // tab was opened straight onto an address that failed):
                    // Back goes home rather than out of the browser.
                    web != null && isStandIn(web.url) -> loadInto(web, homePage)
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
        /**
         * The mode the home page in this WebView says it is showing
         * (homeShown), or null before it has said - the bars follow what the
         * page really shows (homeWash), not what it was last told.
         */
        @Volatile var shownMode: String? = null
        /** Whether that page is drawn dark (its prefers-color-scheme), or null before it has said. */
        @Volatile var shownDark: Boolean? = null
        /** The accent its wash is painted in, or null before it has said. */
        @Volatile var shownAccent: String? = null

        /**
         * home.html: the mode it is showing and whether it is drawn dark, on
         * load and on every change of either, for the bars.
         */
        @JavascriptInterface
        fun homeShown(mode: String, dark: Boolean, accent: String) {
            if (!privileged) return
            if (mode != BrowsingMode.EXPLORE && mode != BrowsingMode.FOCUS && mode != BrowsingMode.ZEN) return
            shownMode = mode
            shownDark = dark
            shownAccent = HomeWash.normalise(accent)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (activeWeb()?.tag === this@SearchAppBridge && tabs.activeTab?.url == homePage &&
                    !searchMode) plainBars(homePage, animate = true)
            }
        }

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
        /** saved.html: one offline page's JSON, or "" (OfflinePages). Asset pages only. */
        @JavascriptInterface
        fun getOfflinePage(id: String): String {
            if (!privileged) return ""
            return OfflinePages.read(this@MainActivity, id)
        }
        @JavascriptInterface
        fun deleteOfflinePage(id: String) {
            if (!privileged) return
            OfflinePages.delete(this@MainActivity, id)
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

        /** The error pages' own Go back: the same steps as Back (backInTab), never round in a circle. */
        @JavascriptInterface
        fun goBack() {
            if (!privileged) return
            runOnUiThread { navBack() }
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
            // Battery Saver on: Zen's wash holds still.
            val powerSave = try {
                (getSystemService(POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
            } catch (e: Exception) { false }
            // Focus and Zen's colours in this accent, light and dark (HomeWash).
            val wash = HomeWash.json(accent).toString()
            // The wallpaper chosen in Customize, if any (HomeWallpapers).
            val wallpaper = HomeWallpapers.json(HomeWallpapers.current(this@MainActivity))
            return "{\"background\":\"$bg\",\"accent\":\"$accent\",\"tiles\":$tiles,\"nightOwl\":$nightOwl," +
                "\"mode\":\"$mode\",\"zenTiles\":$zen,\"powerSave\":$powerSave,\"wash\":$wash,\"wallpaper\":$wallpaper}"
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

        /** home.html: the reader is nearing further ad place [k] ([widthCss] wide). */
        @JavascriptInterface
        fun extraAdWanted(k: Int, widthCss: Int) {
            if (!privileged) return
            val from = this
            runOnUiThread { onExtraAdWanted(from, k, widthCss) }
        }

        /** home.html: the open further ad place nearest the view, in CSS px; k -1 for none. */
        @JavascriptInterface
        fun extraAdSlotAt(k: Int, top: Int, left: Int, width: Int, height: Int) {
            if (!privileged) return
            val from = this
            runOnUiThread { onExtraSlotReported(from, k, top, left, width, height) }
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
    internal fun matchesQuery(title: String, url: String, query: String): Boolean {
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
    internal fun siteNameOf(url: String): String = try {
        val host = (android.net.Uri.parse(url).host ?: "").lowercase()
            .removePrefix("www.").removePrefix("mobile.").removePrefix("m.")
        val name = host.substringBefore('.')
        if (name.isBlank()) host else name.replaceFirstChar { it.uppercase() }
    } catch (e: Exception) {
        ""
    }

    private fun suggestSub(url: String): String = siteNameOf(url)

    internal var lastSuggestScroll = 0L

    /**
     * True while the list is still settling. A tap that halts a fling would
     * otherwise also open whatever row happened to be under the thumb, which is
     * the one mistake in this list the user cannot undo.
     */
    internal fun suggestListMoving(): Boolean =
        android.os.SystemClock.uptimeMillis() - lastSuggestScroll < 250L

    // Recognises a stored results page, so a past search can be offered as the
    // query that was typed rather than as the engine's URL - on every engine
    // in Settings, not only the ones whose query is called "q" (PageLabel).
    private fun searchQueryOf(url: String): String? = PageLabel.searchOf(url)?.first

    internal fun buildSuggestions(query: String): List<JSONObject> {
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
    internal fun newWebView(): WebView {
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

            // Data saver: block images when enabled (always, or on mobile data).
            blockNetworkImage = imagesBlocked()

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
                // A tapped link loses its tracking tags before it loads
                // (LinkCleaner): only a tap, never a redirect, so a site's own
                // hops are left alone; ad click ids are kept.
                if ((scheme == "https" || scheme == "http") && request.isForMainFrame &&
                    request.hasGesture() && !request.isRedirect && cleanLinksOn() &&
                    // Never in a pop-up: that is a sign-in or a payment
                    // talking to the page that opened it, left exactly as sent.
                    tabOf(view)?.openerId == null) {
                    val clean = LinkCleaner.clean(uri.toString())
                    if (clean != null) {
                        loadAfterCallback(view, clean)
                        return true
                    }
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
            // Only ever called on API 26+, where the callback exists.
            @androidx.annotation.RequiresApi(26)
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
                        scheduleVideoScan(1200L)
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
                // A new document: what the last one showed no longer counts.
                (view?.tag as? SearchAppBridge)?.let { it.shownMode = null; it.shownDark = null; it.shownAccent = null }
                // The page being read aloud has gone: so does the reading.
                if (view != null && view === listenWeb && listening) stopListening()
                (view as? BrowserWebView)?.notePageSite(url)
                // The site's own zoom (Menu > Zoom), or the size from Settings.
                view?.settings?.let { s -> zoomFor(url).let { z -> if (s.textZoom != z) s.textZoom = z } }
                if (searchMode && url != null && url != homePage && view != null &&
                    view === activeWeb()) exitSearchMode()
                super.onPageStarted(view, url, favicon)
                if (view == tabs.activeTab?.webView) {
                    // A new page arrives with the bars, as in Chrome.
                    showBars()
                    // The last page's videos are not this one's.
                    setFoundVideos(emptyList(), null)
                    checkLookalike(url)
                    // Plain bars for home and pages that are not to be tinted;
                    // a site's colours arrive once it has painted.
                    // Leaving Focus or Zen's home takes its wash off the bars too.
                    if (!tintAllowed(url) || binding.topBar.seamless) plainBars(url, animate = true)
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
                    scheduleVideoScan(700L)
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
                // sign-in pop-up is one. So is one opened within a few
                // seconds of a tap on the page: payment and sign-in kits
                // (PayPal, card checks, wallets) often open theirs once a
                // request has answered, a moment after the click, when the
                // gesture flag has already gone - Chrome allows those too.
                // A site the user has allowed pop-ups on is let through. A
                // site opening windows by itself is refused, so ad pop-ups
                // stay blocked, and the user is told, with a way to allow it.
                val tapWeb = view as? BrowserWebView
                val tapped = isUserGesture || tapWeb?.touching == true ||
                    (tapWeb != null && android.os.SystemClock.uptimeMillis() - tapWeb.lastTouchUp < POPUP_TAP_GRACE_MS)
                val popupOrigin = originOf(view?.url)
                val siteAllows = popupOrigin != null && SitePermissions.get(
                    this@MainActivity, popupOrigin, SitePermissions.POPUPS) == SitePermissions.ALLOW
                if (!tapped && !siteAllows && Settings.getBool(
                        this@MainActivity, Settings.SEC_BLOCK_POPUPS, true)) {
                    DebugLog.add { "pop-up refused: no user gesture, from tab#" + tabOf(view)?.id }
                    offerPopupAllow(view, popupOrigin)
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
                val intent = fileChooserIntent(params)
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

    internal var barsHidden = false
    // Read once per resume rather than on every scroll event (refreshScrollPrefs).
    internal var hideBarsPref = true
    // Off unless chosen in Settings > Customize: the bars keep the app's own
    // surface on every site.
    internal var tintBarsPref = false
    internal var touchExploration = false

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
        pullRefreshPref = Settings.getBool(this, Settings.PULL_REFRESH, true)
        swipeTabsPref = Settings.getBool(this, Settings.SWIPE_TABS, true)
        touchExploration = (getSystemService(ACCESSIBILITY_SERVICE) as?
            android.view.accessibility.AccessibilityManager)?.isTouchExplorationEnabled == true
        if (!hideBarsPref || touchExploration) showBars(animate = false)
    }
    internal var barScrollAcc = 0
    internal var barsTopH = 0
    /** Whether the page runs under both bars (setPageUnderBars): while they are away or coming back. */
    internal var pageUnderBars = false
    /** When the bars last started to move (uptime). */
    internal var barsMovedAt = 0L
    internal var pageTyping = false

    // The status bar and navigation bar strips, held in the bars' colours
    // while the bars are away (EdgeScrim). Made on first use.
    internal var statusScrim: EdgeScrim? = null
    internal var navScrim: EdgeScrim? = null

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

    internal var topTint: IntArray? = null
    internal var bottomTint: IntArray? = null
    internal var tintAnim: android.animation.ValueAnimator? = null
    internal val tintSample = Runnable { samplePageTint() }
    /** A second look a moment after a page finishes: hero pictures and dark sections paint late. */
    private val tintLate = Runnable { samplePageTint() }
    /** Tries while something is briefly in the way (the menu, a bar moving), so a page is never left untinted. */
    internal var tintRetries = 0
    /** The page the bars' colour was taken from; it holds until the page changes or is back at its top. */
    internal var tintedUrl: String? = null

    // Text and icons on a bar the page has turned dark go light, as on
    // Chrome's toolbar: the address, its pill, the star, the gear and the
    // bottom bar's icons, and the status and navigation bar icons with them.
    internal var topOnDark = false
    private var bottomOnDark = false
    internal var urlTextDefault: android.content.res.ColorStateList? = null

    internal fun applyBarForeground() {
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
    internal var fieldPaneFor: Int? = null

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

    // ---------- Saving images ----------

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
    internal val sharpMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
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
    internal fun loadInto(web: WebView?, url: String) {
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
    internal fun loadAfterCallback(web: WebView?, url: String) {
        val target = web ?: return
        uiHandler.post { if (isLiveWeb(target)) loadInto(target, url) }
    }

    /** How long after a tap a pop-up still counts as the user's (Chrome's window is about this). */
    private val POPUP_TAP_GRACE_MS = 5_000L

    internal var popupNoticeAt = 0L

    /**
     * How much of a page there is to use: visible text, and visible controls
     * someone could press or type into. Read in one pass, as a JSON object.
     */
    internal val PAGE_EMPTINESS_JS = """
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

    internal var unresponsiveDialog: android.app.AlertDialog? = null
    internal var hangQuietUntil = 0L
    /** The tab the user ended for not responding, until it is rebuilt. */
    internal var stoppedTabId: Long? = null
    internal val HANG_WAIT_MS = 10_000L
    /** When the user last ended the renderer for a hang (uptime), or 0. */
    internal var rendererEndedAt = 0L
    /** Pages stuck again this soon after an end are not stuck in the renderer. */
    internal val ENDED_RECENTLY_MS = 90_000L

    internal fun endStuckPages(view: WebView?, renderer: Any?, restart: Boolean) {
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

    // ---------- Memory ----------

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
        memoryShort = true
        try { tabs.trimLive(1) } finally { memoryShort = false }
        // What only saves a moment: icons are on disk too, and a closed tab
        // can still come back by its address without its history.
        SiteIcons.trimMemory()
        closedTabs.forEach { it.state = null }
    }

    // Swaps the native top bar between the icon row (home / reload / tabs /
    // settings) and a single full-width search pill. Nothing is resized and the
    // WebView is never re-laid-out; only child visibility inside the existing
    // 58dp bar changes, so the page cannot jump or reflow while scrolling.
    internal var homeBarAnim: android.animation.Animator? = null
    internal var homeBarSeq = 0
    // Where the row actually is: 1f icons full, 0f fully collapsed. Kept here
    // because the row's width cannot answer it - snapHomeBar restores every
    // width to full and encodes the collapsed state in visibility instead.
    internal var homeBarProgress = 1f
    internal var homeRowWidths: List<Int>? = null

    // Natural widths live in the layout params, which keep their fixed dp even
    // while the view is collapsed to zero, so they survive the transition.
    internal fun homeRowWidths(): List<Int> {
        var w = homeRowWidths
        if (w == null) {
            w = homeBarRow().map { it.layoutParams.width }
            if (w.all { it > 0 }) homeRowWidths = w
        }
        return w
    }

    internal fun refreshOmniboxVisibility(url: String?) {
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
    internal var homeBarOverlap = -1
    internal var barOverlapHome: Boolean? = null
    /** How far the bottom bar overlaps the page while it is in place (setPageUnderBars). */
    internal var barRestOverlap = 0

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
    /** True only while a real memory shortage is being answered (onTrimMemory). */
    internal var memoryShort = false

    /**
     * Hosts visited while Night Owl was on, so leaving can clear those and
     * leave every other site alone. In memory only - a list of privately
     * visited sites written to disk would defeat the point of the mode.
     */
    private val nightOwlHosts = linkedSetOf<String>()

    internal fun enterNightOwl(openUrl: String = homePage) {
        nightOwl = true
        nightOwlHosts.clear()
        applyNightOwlSecure()
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
        dropExtraAds()
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
    internal fun exitNightOwl() {
        nightOwl = false
        nightOwlLocked = false
        hideNightOwlLock()
        applyNightOwlSecure()
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

    private fun openDownloads() {
        openDeck()
        showDownloads()
    }

    internal var menuOpenSeq = 0

    // ---------- Menu groups ----------
    //
    // Similar actions share one row, so the menu is six rows under Back,
    // Forward and Reload and their hairline instead of seventeen; tapping a
    // row opens a strip of its actions under it - swiped sideways when there
    // are more than fit - and closes any other.

    internal val menuGroups by lazy {
        listOf(
            binding.menuGroupTabs to binding.menuStripTabs,
            binding.menuGroupPage to binding.menuStripPage,
            binding.menuGroupLibrary to binding.menuStripLibrary,
            binding.menuGroupShare to binding.menuStripShare,
            binding.menuGroupMore to binding.menuStripMore)
    }

    // ---------- Menu motion ----------
    //
    // The menu opens and closes on a spring, growing from the Menu button's
    // corner with a little give. It can also be dragged down to close: the
    // panel follows the finger, the dimming lightens with it, and on release
    // it drops away or bounces back - by how far it went, or how fast.

    internal val menuSpring by lazy {
        Spring(stiffness = 460f, dampingRatio = 0.72f, precision = 0.002f) { placeMenu(it) }
    }
    internal val menuDragSpring by lazy {
        Spring(stiffness = 420f, dampingRatio = 0.86f, precision = 0.5f) { dragMenu(it) }
    }

    // ---------- Find in page ----------
    internal var findActive = false

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
        // The further ads' card takes gestures exactly as the first one does.
        binding.adSlotExtra.page = { activeWeb() }
        binding.adSlotExtra.blocked = binding.adSlot.blocked
        // Placed once it has a height (it has none the moment a card goes in);
        // a card that measures other than its place is given a place its size.
        binding.adSlotExtra.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && v.visibility == View.VISIBLE) {
                v.post { if (!isFinishing && !isDestroyed) extraCardResized() }
            }
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
            // A start that throws - Android System WebView being updated or
            // missing is the known case - took the whole app down from this
            // thread, and, marked started, was never tried again. Now it is
            // let go, and tried again on the next resume or reconnect
            // (resumeFeedAd / retryAdsAfterReconnect see adsStarted false).
            try {
                com.google.android.gms.ads.MobileAds.initialize(this) {
                    runOnUiThread { onAdsSdkReady() }
                }
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) android.util.Log.d("Ads", "initialize threw: $t")
                runOnUiThread { adsStarted = false }
                return@Thread
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
                // Should the SDK never report that it has finished starting,
                // the feed ad does not wait for it for ever: with no mediation
                // an ad may be asked for without that report.
                uiHandler.postDelayed({ onAdsSdkReady() }, 10_000L)
            }
        }.start()
    }

    /** The SDK has finished starting (or has been given long enough): the feed ads may be asked for. */
    private fun onAdsSdkReady() {
        if (isFinishing || isDestroyed || adsReady) return
        adsReady = true
        // Not gated on Night Owl here: loadFeedAd makes that call,
        // so initialization finishing DURING a private session is
        // handled by the same test as every other path in.
        loadFeedAd()
        // Further ad places the reader neared while it started.
        if (extraLoading < 0) extraNext.run()
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
        // A request that throws before it is out would leave adLoading set for
        // good - no feed ad again this session, and nothing to say why.
        try {
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
        } catch (t: Throwable) {
            adLoading = false
            if (BuildConfig.DEBUG) android.util.Log.d("Ads", "feed ad request threw: $t")
            if (!replace && adFailures < AD_RETRY_MS.size && !isFinishing && !isDestroyed) {
                uiHandler.postDelayed(adRetry, AD_RETRY_MS[adFailures])
                adFailures++
            }
        }
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
        val view = inflateAdCard(ad, binding.adSlot, register = true)
        binding.adSlot.removeAllViews()
        binding.adSlot.addView(view)
        // A new card fades in afresh once its place has opened to its size.
        parkAdSlot()
        refreshAdSlot()
    }

    /**
     * The feed ad's card for [ad], laid out for [parent]. [register] false
     * fills it only to measure it (the further ads' places are opened to the
     * card's height before the card is shown); the ad is handed to the SDK
     * only by a card that is shown.
     */
    private fun inflateAdCard(
        ad: com.google.android.gms.ads.nativead.NativeAd,
        parent: android.view.ViewGroup,
        register: Boolean
    ): com.google.android.gms.ads.nativead.NativeAdView {
        val view = layoutInflater.inflate(
            R.layout.native_ad_feed_card, parent, false)
            as com.google.android.gms.ads.nativead.NativeAdView
        val headline = view.findViewById<android.widget.TextView>(R.id.adHeadline)
        headline.text = ad.headline
        view.headlineView = headline

        // Full width, as tall as the creative's shape (AdMediaFrame clamps it).
        val frame = view.findViewById<AdMediaFrame>(R.id.adMediaFrame)
        frame.ratio = ad.mediaContent?.aspectRatio ?: (16f / 9f)
        val media = view.findViewById<com.google.android.gms.ads.nativead.MediaView>(R.id.adMedia)
        media.setImageScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
        if (register) ad.mediaContent?.let { media.mediaContent = it }
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

        if (register) view.setNativeAd(ad)
        return view
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
        // The further ads' card rides on every pass that places this one.
        positionExtraAd(web, scrollY)
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
        refreshExtraAd()
    }

    // ---------- The further feed ads ----------
    //
    // Up to four more native ads in the feed, for readers who scroll that
    // far: home.html keeps a place after the 11th, 19th, 27th and 35th story
    // (one in each batch it loads), shut until its ad has come. The ad for a
    // place is asked for only as the reader nears it; one card
    // (binding.adSlotExtra), laid over whichever open place is nearest the
    // view, shows that place's ad - places are eight stories apart, so two
    // are never on screen together. Their own ad unit, so they are reported
    // apart from the first ad. Everything about the first ad is untouched;
    // the same gates hold here: no Night Owl, no supporters, consent where it
    // is required, Explore only.

    private val extraAdUnit = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/2247696110"
        else "ca-app-pub-9121922395304175/1757838096"
    private val EXTRA_PLACES = 4
    private val extraAds = arrayOfNulls<com.google.android.gms.ads.nativead.NativeAd>(4)
    private val extraAdAt = LongArray(4)
    /** Places the page has asked an ad for and not had one yet, nearest first. */
    private val extraWanted = java.util.TreeSet<Int>()
    private var extraLoading = -1
    private var extraFailedAt = 0L
    /** A set-up fault (1, 8): the same request would only fail again this session. */
    private var extraBroken = false
    private var extraFrom: android.webkit.WebView? = null
    private var extraActive = -1
    private var extraBound = -1
    private var extraTopCss = -1
    private var extraLeftCss = 0
    private var extraWidthCss = 0
    private var extraHCss = 0
    private var extraCardShown = false
    private val extraNext = Runnable { extraWanted.pollFirst()?.let { loadExtraAd(it) } }

    /** Every reason not to ask, but the SDK still starting (the asks wait for it: extraNext). */
    private fun extraAdsWelcome(): Boolean =
        !extraBroken && !nightOwl && !nightOwlLaunchPending &&
            browsingMode == BrowsingMode.EXPLORE &&
            !Settings.getBool(this, Settings.IS_SUPPORTER, false) && AdConsent.adsAllowed(this)

    private fun extraAdsAllowed(): Boolean = adsReady && extraAdsWelcome()

    private fun onExtraAdWanted(from: SearchAppBridge, k: Int, widthCss: Int) {
        if (isFinishing || isDestroyed || k !in 0 until EXTRA_PLACES) return
        val web = activeWeb() ?: return
        if (web.tag !== from || !extraAdsWelcome()) return
        if (widthCss > 0) extraWidthCss = widthCss
        val have = extraAds[k]
        if (have != null && android.os.SystemClock.elapsedRealtime() - extraAdAt[k] < 60L * 60_000L) {
            // Already here (home was loaded again): its place opens at once.
            sendExtraHeight(k)
            return
        }
        extraWanted.add(k)
        if (adsReady && extraLoading < 0) extraNext.run()
    }

    private fun loadExtraAd(k: Int) {
        if (extraLoading >= 0 || isFinishing || isDestroyed || !extraAdsAllowed()) return
        val now = android.os.SystemClock.elapsedRealtime()
        // After a failed request, the next waits the same 30 s the first ad's do.
        val wait = AD_MIN_GAP_MS - (now - extraFailedAt)
        if (extraFailedAt > 0L && wait > 0L) {
            extraWanted.add(k)
            uiHandler.removeCallbacks(extraNext)
            uiHandler.postDelayed(extraNext, wait)
            return
        }
        extraLoading = k
        try {
            com.google.android.gms.ads.AdLoader.Builder(this, extraAdUnit)
                .forNativeAd { ad ->
                    extraLoading = -1
                    if (isFinishing || isDestroyed || !extraAdsAllowed()) { ad.destroy(); return@forNativeAd }
                    val old = extraAds[k]
                    extraAds[k] = ad
                    extraAdAt[k] = android.os.SystemClock.elapsedRealtime()
                    if (extraBound == k) bindExtraAd(k)
                    if (old != null && old !== ad) old.destroy()
                    sendExtraHeight(k)
                    extraNext.run()
                }
                .withAdListener(object : com.google.android.gms.ads.AdListener() {
                    override fun onAdFailedToLoad(e: com.google.android.gms.ads.LoadAdError) {
                        extraLoading = -1
                        extraFailedAt = android.os.SystemClock.elapsedRealtime()
                        if (e.code == 1 || e.code == 8) { extraBroken = true; extraWanted.clear(); return }
                        // The place stays shut; the next one the reader nears is
                        // tried after the pause, never in a tight loop.
                        if (extraWanted.isNotEmpty()) {
                            uiHandler.removeCallbacks(extraNext)
                            uiHandler.postDelayed(extraNext, AD_MIN_GAP_MS)
                        }
                        if (BuildConfig.DEBUG) android.util.Log.d("Ads", "further ad failed \u00b7 code " + e.code + " \u00b7 " + e.message)
                    }
                })
                .withNativeAdOptions(
                    com.google.android.gms.ads.nativead.NativeAdOptions.Builder()
                        .setVideoOptions(
                            com.google.android.gms.ads.VideoOptions.Builder()
                                .setStartMuted(true).build())
                        .build())
                .build()
                .loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
        } catch (t: Throwable) {
            // Nothing about an ad may take the browser down.
            extraLoading = -1
        }
    }

    /** Opens place [k] to its card's height: measured on a card filled for the purpose, never shown. */
    @Suppress("DEPRECATION") // getScale(): see positionAdSlot.
    private fun sendExtraHeight(k: Int) {
        val ad = extraAds[k] ?: return
        val web = activeWeb() ?: return
        if (!isLiveWeb(web) || web.url != homePage) return
        val scale = web.scale.takeIf { it > 0f } ?: resources.displayMetrics.density
        val wCss = if (extraWidthCss > 0) extraWidthCss else adSlotWidthCss
        if (wCss <= 0) return
        val css = try {
            val card = inflateAdCard(ad, binding.adSlotExtra, register = false)
            card.measure(
                View.MeasureSpec.makeMeasureSpec(Math.round(wCss * scale), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            Math.ceil((card.measuredHeight / scale).toDouble()).toInt()
        } catch (e: Exception) { 0 }
        if (css <= 0) return
        web.evaluateJavascript("window.__setExtraAdSlot && window.__setExtraAdSlot($k,$css);", null)
    }

    private fun onExtraSlotReported(from: SearchAppBridge, k: Int, top: Int, left: Int, width: Int, height: Int) {
        if (isFinishing || isDestroyed) return
        val web = activeWeb() ?: return
        if (web.tag !== from) return
        extraFrom = web
        extraActive = if (k in 0 until EXTRA_PLACES) k else -1
        extraTopCss = top
        extraLeftCss = left
        if (width > 0) extraWidthCss = width
        extraHCss = height
        if (extraActive >= 0 && extraAds[extraActive] != null && extraBound != extraActive) bindExtraAd(extraActive)
        refreshExtraAd()
        fitExtraWidth(web)
        if (isLiveWeb(web)) positionExtraAd(web, web.scrollY)
    }

    private fun bindExtraAd(k: Int) {
        val ad = extraAds[k] ?: return
        val view = inflateAdCard(ad, binding.adSlotExtra, register = true)
        binding.adSlotExtra.removeAllViews()
        binding.adSlotExtra.addView(view)
        extraBound = k
        parkExtraAd()
    }

    @Suppress("DEPRECATION") // getScale(): see positionAdSlot.
    private fun fitExtraWidth(web: android.webkit.WebView) {
        if (extraWidthCss <= 0 || !isLiveWeb(web)) return
        val scale = web.scale.takeIf { it > 0f } ?: resources.displayMetrics.density
        val w = Math.round(extraWidthCss * scale)
        val lp = binding.adSlotExtra.layoutParams ?: return
        if (lp.width != w) {
            lp.width = w
            binding.adSlotExtra.layoutParams = lp
        }
    }

    private fun parkExtraAd() {
        binding.adSlotExtra.translationY = adSlotParkedY()
        binding.adSlotExtra.animate().cancel()
        binding.adSlotExtra.alpha = 0f
        extraCardShown = false
    }

    /** The page the further ads' card was last asked about. */
    private var extraPage: Pair<android.webkit.WebView?, String?>? = null

    /** Home on screen in Explore, no sheet or deck over it, and a card for the place nearest the view. */
    private fun refreshExtraAd() {
        val url = tabs.activeTab?.url
        val web = activeWeb()
        val shown = web?.url
        val onHome = (url == null || url == homePage) && (shown == null || shown == homePage)
        // Another home tab, or home loaded again: its places are its own, and
        // it is asked where its open one is (as __adSlotWhere for the first ad).
        val page = web to shown
        if (onHome && web != null && page != extraPage) {
            extraPage = page
            if (extraFrom !== web) { extraActive = -1; extraTopCss = -1 }
            if (isLiveWeb(web)) web.evaluateJavascript("window.__extraAdWhere && window.__extraAdWhere();", null)
        } else if (!onHome) extraPage = null
        val show = onHome && !searchMode && !deckVisible && !nightOwl &&
            browsingMode == BrowsingMode.EXPLORE && extraActive >= 0 &&
            extraBound == extraActive && extraAds[extraActive] != null
        val visible = binding.adSlotExtra.visibility == View.VISIBLE
        if (show == visible) return
        if (show) parkExtraAd()
        binding.adSlotExtra.visibility = if (show) View.VISIBLE else View.GONE
        if (show && web != null) binding.adSlotExtra.post { if (isLiveWeb(web)) positionExtraAd(web, web.scrollY) }
    }

    /** As positionAdSlot, for the further ads' card over the place the page last named. */
    @Suppress("DEPRECATION") // getScale(): see positionAdSlot.
    private fun positionExtraAd(web: android.webkit.WebView, scrollY: Int) {
        val card = binding.adSlotExtra
        if (card.visibility != View.VISIBLE) return
        if (web !== activeWeb() || web.url != homePage || extraFrom !== web || extraTopCss < 0 ||
            extraActive < 0 || extraBound != extraActive) {
            parkExtraAd()
            return
        }
        if (web.contentHeight <= 0) { parkExtraAd(); return }
        val scale = web.scale.takeIf { it > 0f } ?: return
        val frame = card.parent as? View ?: return
        web.getLocationInWindow(adLocWeb)
        frame.getLocationInWindow(adLocSlot)
        card.translationX = (adLocWeb[0] - adLocSlot[0]) + extraLeftCss * scale
        card.translationY = (adLocWeb[1] - adLocSlot[1]) + extraTopCss * scale - scrollY
        val h = card.height
        val ready = h > 0 && extraHCss * scale >= h - 3f * resources.displayMetrics.density
        if (ready && !extraCardShown) {
            extraCardShown = true
            card.animate().cancel()
            if (Motion.animationsOn()) {
                card.animate().alpha(1f).setDuration(220L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            } else card.alpha = 1f
        } else if (!ready && extraCardShown) {
            extraCardShown = false
            card.animate().cancel()
            card.alpha = 0f
        }
    }

    @Suppress("DEPRECATION") // getScale(): see positionAdSlot.
    private fun extraCardResized() {
        val web = activeWeb() ?: return
        if (!isLiveWeb(web)) return
        val k = extraBound
        val h = binding.adSlotExtra.height
        val scale = web.scale.takeIf { it > 0f } ?: return
        if (k >= 0 && k == extraActive && h > 0 && web.url == homePage) {
            val css = Math.ceil((h / scale).toDouble()).toInt()
            if (kotlin.math.abs(css - extraHCss) > 2) {
                web.evaluateJavascript("window.__setExtraAdSlot && window.__setExtraAdSlot($k,$css);", null)
            }
        }
        positionExtraAd(web, web.scrollY)
    }

    /** Night Owl, a supporter's purchase, the end: the further ads go. */
    private fun dropExtraAds() {
        uiHandler.removeCallbacks(extraNext)
        extraWanted.clear()
        binding.adSlotExtra.removeAllViews()
        binding.adSlotExtra.visibility = View.GONE
        for (i in extraAds.indices) { extraAds[i]?.destroy(); extraAds[i] = null }
        extraBound = -1
        extraActive = -1
        extraCardShown = false
    }

    // ---------- Browsing modes (BrowsingMode) ----------

    @Volatile internal var browsingMode = BrowsingMode.EXPLORE
    @Volatile private var zenShortcuts = false
    private var launchedForTask = false
    internal var announcing = false

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
        plainBars(tabs.activeTab?.url, animate = true)
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
            coverAdShowing = true
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
        // Left the app with Night Owl on: locked until the screen lock says
        // it is the owner. Not for a screen of this app (Settings), not for
        // a file being picked for a page, not while unlocking itself.
        if (nightOwl && !unlocking && !isChangingConfigurations && filePathCallback == null &&
            AppOpenAds.inBackground() && nightOwlLockOn()) {
            nightOwlLocked = true
            lockPrompted = false
            showNightOwlLock()
        }
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
        for (i in extraAds.indices) { extraAds[i]?.destroy(); extraAds[i] = null }
        // MediaService.onControl is a companion field holding a lambda that
        // captures this activity. Left set, a destroyed MainActivity and its
        // whole view tree - WebViews included - stay reachable for the life of
        // the process. Only clear it if it is still ours: a recreated activity
        // has already replaced it by now.
        if (MediaService.onControl === mediaControl) MediaService.onControl = null
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        tts = null
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
                if (deckFilter.isNotBlank() || deckGroup != null) return false
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
        renderDeckGroups()
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
        deckGroup = null
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
    @Volatile internal var resumeJson = "{}"
    /** The tab last seen showing a website, by id. */
    internal var resumeTabId: Long? = null

    // ---------- Home after a long break ----------
    //
    // After four hours away the browser opens on its home page - the news,
    // the shortcuts, Jump back in - rather than on whatever page was left
    // open, as Chrome does after a while. The page left is still in its tab,
    // one tap away. Settings > Customize > Open home after 4 hours away.

    private val AWAY_FOR_HOME_MS = 4L * 60L * 60_000L

    // ---------- Smart tab groups ----------
    //
    // Over the tab cards, a chip for every site with two or more tabs open -
    // "All 9", "Bbc 3", "Wikipedia 2" - so a long row of tabs sorts itself by
    // where they are. A chip shows just that site's tabs; holding one offers
    // to close them all at once (each remembered, so Reopen closed tab brings
    // them back). Nothing to set up and nothing stored: the groups are the
    // tabs as they are. Home and new tabs belong to no group.

    /** The site group the deck is showing, or null for every tab. */
    internal var deckGroup: String? = null

    /** What is typed in the deck's search field, filtering its cards. */
    internal var deckFilter = ""

    /**
     * Closes every tab (or every other one), each remembered so Reopen closed
     * tab can bring them back one by one; asked first, since it is many at once.
     */
    internal fun confirmCloseTabs(keepActive: Boolean) {
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

    internal class ClosedTab(val url: String, val title: String, var state: Bundle?, val index: Int)
    internal val closedTabs = ArrayDeque<ClosedTab>()

    // ---------- Offline pages ----------
    //
    // Menu > Page tools > Save offline keeps the page's reading content, with
    // its pictures inside it, to read with no connection (OfflinePages); Menu
    // > History & downloads > Offline pages lists them, newest first, and one
    // opens in a new tab (saved.html). With Data saver on, or Save data on
    // mobile data while on it, only the text is kept - the pictures would be
    // the data it is there to save. Never in Night Owl, which keeps nothing.

    // ---------- Risky downloads ----------

    /** Files that run as programs or install as apps: what a bad download usually is. */
    internal val RISKY_EXT = setOf(
        "apk", "apks", "xapk", "apkm", "exe", "msi", "bat", "cmd", "com", "scr", "pif",
        "vbs", "vbe", "js", "jse", "wsf", "jar", "sh", "ps1", "dll", "appx", "msix",
        "reg", "lnk", "hta", "cpl"
    )

    // ---------- Look-alike sites ----------
    //
    // A site whose name is spelled in another alphabet (an "xn--" name) can
    // pass for a familiar one - Cyrillic а for Latin a - which is how many
    // phishing pages pass themselves off as a bank or a shop. The address bar
    // shows such names as they really are (xn--...), and the first visit to
    // one says so, with the way back. Once per site per session.
    // Settings > Security > Warn about look-alike sites.

    internal val lookalikeWarned = HashSet<String>()

    // ---------- Night Owl: lock and hide ----------
    //
    // Lock Night Owl tabs when you leave (Settings > Security > Night Owl), as
    // Chrome locks Incognito: leaving the app with Night Owl on puts a lock
    // over it, and coming back asks for the phone's own unlock - fingerprint,
    // face, PIN, pattern - before anything under it shows. "Close Night Owl
    // tabs" on the lock ends the private session instead. Only when the phone
    // has a screen lock to ask for. On Android 13 and up the recent-apps
    // picture is blank while it is on. Hide Night Owl from screenshots and
    // recent apps sets FLAG_SECURE while Night Owl is on.

    internal var nightOwlLocked = false
    internal var unlocking = false
    private var lockPrompted = false
    internal var lockView: View? = null

    internal val unlockLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        unlocking = false
        if (r.resultCode == android.app.Activity.RESULT_OK) unlockNightOwl()
    }

    private fun applyNightOwlSecure() {
        if (!::binding.isInitialized) return
        val hide = nightOwl && Settings.getBool(this, Settings.NIGHT_OWL_SECURE, false)
        try {
            if (hide) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!(nightOwl && nightOwlLockOn()))
        } catch (e: Exception) { /* window gone */ }
    }

    private fun showNightOwlLock() {
        if (lockView != null || !::binding.isInitialized) return
        val d = resources.displayMetrics.density
        val dark = isDarkTheme()
        val fg = if (dark) 0xFFF4F3F7.toInt() else 0xFF1D1828.toInt()
        val muted = if (dark) 0xFFB9B3CC.toInt() else 0xFF6E6880.toInt()
        val accent = currentAccent()
        val col = android.widget.LinearLayout(this)
        col.orientation = android.widget.LinearLayout.VERTICAL
        col.gravity = android.view.Gravity.CENTER
        col.setBackgroundColor(if (dark) 0xFF120D24.toInt() else 0xFFECE7F5.toInt())
        col.isClickable = true
        col.isFocusable = true
        col.elevation = 80f * d
        col.setPadding((32 * d).toInt(), 0, (32 * d).toInt(), 0)
        val owl = android.widget.ImageView(this)
        val art = owlArt
        if (art != null) owl.setImageBitmap(art) else owl.setImageResource(R.drawable.ic_owl)
        owl.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        col.addView(owl, android.widget.LinearLayout.LayoutParams((96 * d).toInt(), (96 * d).toInt()))
        fun text(t: String, size: Float, color: Int, bold: Boolean, top: Int): android.widget.TextView {
            val v = android.widget.TextView(this)
            v.text = t
            v.textSize = size
            v.setTextColor(color)
            v.gravity = android.view.Gravity.CENTER
            if (bold) v.setTypeface(v.typeface, android.graphics.Typeface.BOLD)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (top * d).toInt()
            v.layoutParams = lp
            return v
        }
        col.addView(text("Night Owl is locked", 22f, fg, true, 20))
        col.addView(text("Unlock to see your Night Owl tabs.", 15f, muted, false, 8))
        val unlock = text("Unlock", 16f, if (android.graphics.Color.luminance(accent) > 0.6f)
            android.graphics.Color.BLACK else android.graphics.Color.WHITE, true, 28)
        unlock.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 26f * d
            setColor(accent)
        }
        unlock.setPadding((32 * d).toInt(), (14 * d).toInt(), (32 * d).toInt(), (14 * d).toInt())
        unlock.setOnClickListener { promptUnlock() }
        col.addView(unlock)
        val close = text("Close Night Owl tabs", 15f, muted, true, 10)
        close.setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        close.setOnClickListener { closeNightOwlFromLock() }
        col.addView(close)
        val root = window.decorView as? android.view.ViewGroup ?: return
        root.addView(col, android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        lockView = col
    }

    // ---------- Listen to this page ----------
    //
    // Menu > Page tools > Listen reads the page aloud through the phone's own
    // text-to-speech voice, as Chrome's "Listen to this page" does: the
    // article if there is one (Reader view's choice), otherwise the page's
    // readable text, paragraph by paragraph, in the page's language where the
    // phone has a voice for it. A pill above the bar pauses, resumes and
    // stops it; it goes on while you use other tabs, and stops when the page
    // it is reading goes away.

    internal var tts: android.speech.tts.TextToSpeech? = null
    internal var ttsReady = false
    internal var ttsFailed = false
    internal var pendingListen: (() -> Unit)? = null
    internal var listenChunks: List<String> = emptyList()
    internal var listenIndex = 0
    internal var listening = false
    internal var listenPaused = false
    internal var listenWeb: WebView? = null
    internal var listenLang: String? = null
    internal var listenBarWired = false

    internal val LISTEN_JS = """
(function(){
try{
var best=null,score=0,cs=document.querySelectorAll('article,main,[role=main],[itemprop=articleBody],section,div');
for(var i=0;i<cs.length&&i<4000;i++){var e=cs[i],ps=e.querySelectorAll('p');if(ps.length<3)continue;
var len=0;for(var j=0;j<ps.length;j++){var t=ps[j].textContent.length;if(t>40)len+=t;}
var ln=0,as=e.querySelectorAll('a');for(var k=0;k<as.length;k++)ln+=as[k].textContent.length;
var s=len-ln*1.5;if(e.tagName==='ARTICLE'||e.getAttribute('itemprop')==='articleBody')s*=1.25;
if(s>score*1.1||(s>score&&best&&best.contains(e))){score=s;best=e;}}
var h=document.querySelector('h1');var title=((h&&h.textContent)||document.title||'').trim();
var parts=[],total=0,junk='nav,aside,footer,header,form,button,[class*=share],[class*=social],[class*=related],[class*=newsletter],[class*=comment],[class*=promo],[class*=advert],[aria-hidden=true]';
function push(t){t=(t||'').replace(/\s+/g,' ').trim();if(t.length<2||total>80000)return;parts.push(t);total+=t.length;}
if(best&&score>=600){
var els=best.querySelectorAll('h2,h3,h4,p,li,blockquote,figcaption');
for(var n=0;n<els.length&&parts.length<400;n++){var el=els[n];
if(el.closest(junk))continue;if(el.tagName==='LI'&&el.querySelector('p'))continue;
if(el.offsetParent===null&&el.getClientRects().length===0)continue;push(el.innerText||el.textContent);}
}else{
var lines=(document.body?document.body.innerText:'').split('\n');
for(var m=0;m<lines.length&&parts.length<300;m++){var l=lines[m].trim();if(l.length>=40)push(l);}
}
return {title:title,lang:(document.documentElement.lang||''),parts:parts};
}catch(e){return null;}
})()
""".trimIndent()

    // ---------- Default browser ----------

    internal val defaultBrowserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (DefaultBrowser.isDefault(this)) toast("Search is now your default browser")
    }

    internal var offeredThisLaunch = false

    internal fun closeTabFromDeck(tab: Tab) {
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
        if (deckVisible) renderDeckGroups()
        tabAdapter.notifyDataSetChanged()
        updateTabCount()
        scheduleSessionSave()
    }

    // ---------- History ----------

    internal var historyOpen = false

    internal var downloadsOpen = false
    internal var downloadsAdapter: DownloadsAdapter? = null

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
    internal val downloadsPoll = object : Runnable {
        override fun run() {
            if (!downloadsOpen || !deckVisible) return
            refreshDownloads()
            binding.downloadList.postDelayed(this, 1200L)
        }
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

    internal val APK_MIME = "application/vnd.android.package-archive"
    internal val APK_PREFS = "apk_install"
    internal val APK_PENDING_MS = 10L * 60_000L

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
        binding.deckGroups.visibility = View.GONE
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

    internal var bookmarksOpen = false

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
        binding.deckGroups.visibility = View.GONE
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

    // ---------- Owl tap (context-aware) ----------
    internal val gamesUrl = "https://toolsepulse.co/games"

    // ---------- Space for downloads ----------

    internal val ROOM_OK = 0
    internal val ROOM_TIGHT = 1
    internal val ROOM_NONE = 2

    internal val BLOB_STREAM_JS = "(function(u,t){var A=window.SearchApp,CH=2097152;" +
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
                    1 -> url?.let { copyText("Link", shareable(it)) }
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
    internal fun leaveTransientUi() {
        if (findActive) closeFindBar()
        if (searchMode) exitSearchMode()
    }

    /** Back for the page: the same steps as the system Back. */
    internal fun navBack() {
        if (findActive) { closeFindBar(); return }
        if (searchMode) { exitSearchMode(); return }
        val web = activeWeb()
        when {
            backInTab(web) -> {}
            tabs.activeTab?.openerId != null ->
                tabs.activeTab?.let { foldBackPopup(it, "closed with Back") }
            web != null && isStandIn(web.url) -> loadInto(web, homePage)
        }
    }

    /**
     * The browser's own pages standing in for one that did not load: offline,
     * can't reach the site, not secure, and the page ended for not responding.
     */
    private fun isStandIn(url: String?): Boolean =
        url != null && (url.startsWith("file:///android_asset/offline.html") ||
            url.startsWith("file:///android_asset/error.html") ||
            url.startsWith("file:///android_asset/insecure.html"))

    /**
     * Back within the tab on screen; false when it has nowhere to go back to.
     *
     * From a stand-in page ([isStandIn]) Back skips the address that failed
     * as well. A failed load leaves two entries: the address, and the
     * stand-in put up in its place. Back to the address loaded it again, it
     * failed again, the stand-in came back - Back went round in a circle and
     * the browser would not leave the page. Now it goes to the last page that
     * did load, and with none, home.
     */
    private fun backInTab(web: WebView?): Boolean {
        if (web == null) return false
        val steps = backSteps(web)
        if (steps <= 0) return false
        if (steps == 1) web.goBack() else web.goBackOrForward(-steps)
        return true
    }

    private fun backSteps(web: WebView): Int {
        val list = try { web.copyBackForwardList() } catch (e: Exception) {
            return if (web.canGoBack()) 1 else 0
        }
        val cur = list.currentIndex
        if (cur <= 0) return 0
        val here = list.getItemAtIndex(cur)?.url
        if (!isStandIn(here)) return 1
        // What failed: the address the stand-in names, and the tab's record.
        val failed = listOfNotNull(tabOf(web)?.failedUrl, standInTarget(here)).map { addressKey(it) }.toSet()
        var i = cur - 1
        while (i >= 0) {
            val item = list.getItemAtIndex(i)
            val u = item?.url
            val gone = u == null || isStandIn(u) || u == "about:blank" ||
                u.startsWith("data:text/html,chromewebdata") ||
                addressKey(u) in failed || (item.originalUrl?.let { addressKey(it) in failed } == true)
            if (!gone) break
            i--
        }
        return if (i < 0) 0 else cur - i
    }

    /** The u= address a stand-in page carries, if any. */
    private fun standInTarget(url: String?): String? = try {
        android.net.Uri.parse(url ?: return null).getQueryParameter("u")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    /** An address without its scheme, www., case of host or trailing slash, to compare loads by. */
    private fun addressKey(url: String): String {
        val noScheme = url.substringAfter("://", url)
        val host = noScheme.substringBefore('/').substringBefore('?').lowercase(java.util.Locale.ROOT).removePrefix("www.")
        val rest = noScheme.substring(noScheme.substringBefore('/').substringBefore('?').length).substringBefore('#')
        return host + rest.trimEnd('/')
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
    internal var newTabAccent = 0

    /**
     * Puts the bars in step with the tab on screen: Home filled in the accent,
     * with its dot, while home is showing; the menu's Back and Forward dimmed
     * when there is nowhere to go. Called on every load, history change and
     * tab switch, and when the accent changes.
     */
    internal fun refreshNav() {
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

    internal var swipeNeighbor: Tab? = null
    /** -1: moving to the next tab, +1: to the previous one, 0: not decided. */
    internal var swipeDir = 0
    internal val swipeSpring by lazy {
        Spring(stiffness = 340f, dampingRatio = 0.9f, precision = 0.5f) { placeSwipe(it) }
    }

    internal fun placeSwipe(x: Float) {
        val w = binding.webArea.width.toFloat()
        binding.webContainer.translationX = x
        // The feed ad cards ride with the page. Placed as on a scroll, which
        // goes by where the page now sits: setting x alone dropped the card's
        // offset into the feed's column, and a swipe that sprang back left
        // it that far to the side until the page next scrolled.
        activeWeb()?.let { web -> if (isLiveWeb(web)) positionAdSlot(web, web.scrollY) }
        binding.tabPeek.translationX = if (x < 0f) x + w else x - w
    }

    // ---------- Pull to refresh ----------
    //
    // Pulling a web page down past its top brings a refresh disc down after
    // it. The disc follows the finger one to one at first and then ever more
    // stiffly, like stretched rubber; let go past the trigger and it holds and
    // spins while the page reloads, let go short of it and it springs back up.
    // Driven by the page's own overscroll (BrowserWebView), so it only answers
    // once the page really is at its top - not while a list inside it scrolls.

    internal var pullRaw = 0f
    internal var pullRefreshing = false
    internal var pullSpin: android.animation.ObjectAnimator? = null
    internal var pullStartedAt = 0L
    internal val pullTimeout = Runnable { endPullRefresh() }
    internal val pullSpring by lazy { Spring(stiffness = 420f, dampingRatio = 0.72f, precision = 0.5f) { placePull(it) } }
    internal val pullTrigger get() = 84f * resources.displayMetrics.density
    internal val pullReach get() = 140f * resources.displayMetrics.density
    internal val pullHold get() = 64f * resources.displayMetrics.density

    /** Settings > Customize > Pull to refresh and Swipe the bottom bar (refreshScrollPrefs). */
    internal var pullRefreshPref = true
    internal var swipeTabsPref = true

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
        binding.menuVideo.setOnClickListener {
            closeMenuNow()
            downloadVideoFromMenu()
        }
        binding.menuListen.setOnClickListener {
            closeMenuNow()
            listenToPage()
        }
        binding.menuSaveOffline.setOnClickListener {
            closeMenuNow()
            savePageOffline()
        }
        binding.menuOfflinePages.setOnClickListener {
            closeMenuNow()
            showOfflinePages()
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

    // ---------- Media control notification ----------
    internal var mediaActive = false

    // ---------- Video downloader ----------
    //
    // A web page with a video that is a plain file (VideoFinder) gets a button
    // above the bottom bar, "Download video", which shrinks to its icon after
    // a few seconds. One video goes through the same confirmation as any
    // other download; several are offered in a list first. Menu > Page tools
    // has the same, and says why when there is nothing to save. Never for
    // YouTube. Looked for when a page finishes, when a video starts playing
    // (many players only set their source then) and twice more a little later.
    // Never on home, so the button is never near the ad cards.

    internal var foundVideos: List<VideoFinder.Found> = emptyList()
    /** The address the WebView showed when [foundVideos] were found. */
    internal var foundVideosFor: String? = null
    internal var videoScans = 0
    internal val videoScan = Runnable { scanVideos(null) }
    internal val videoFabShrink = Runnable { setVideoFabExtended(false) }
    internal var videoFabStyled = false

    internal var owlAccent = 0

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
    internal var owlArt: android.graphics.Bitmap? = null

}
