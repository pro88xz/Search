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
    // Same one-shot idea for a blob: download. The bridge is reachable from
    // every page, so without a ticket any site could drop a file into the
    // user's Downloads folder unprompted.
    @Volatile private var blobDownloadToken: String? = null
    private var blobDownloadName: String = "download"
    private var blobDownloadMime: String? = null
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
                    Array(cd.itemCount) { i -> cd.getItemAt(i).uri }
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
                    }
                }
            }
            override fun onLost(network: android.net.Network) {
                runOnUiThread { if (!hasNetwork()) wasOffline = true }
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
            Settings.getTextScale(this)
        ).joinToString("|")
    }
    private var suggestAdapter: SuggestAdapter? = null
    private var suggestSeq = 0
    // The suggestion backdrop's own bottom padding from the layout, kept so
    // the keyboard inset can be added on top of it rather than replacing it.
    private var suggestBasePad = -1

    private fun setupSuggestOverlay() {
        if (suggestBasePad < 0) suggestBasePad = binding.suggestBackdrop.paddingBottom
        suggestAdapter = SuggestAdapter(emptyList(), { item ->
            if (suggestListMoving()) return@SuggestAdapter
            val kind = item.optString("kind")
            val title = item.optString("title")
            val url = item.optString("url")
            exitSearchMode()
            if (kind == "web" || url.isBlank()) go(title) else loadInto(activeWeb(), url)
        }, { item ->
            // The arrow loads a suggestion into the box instead of running it, so
            // a near-miss can be edited rather than retyped. The box's own text
            // watcher refreshes the list, so nothing is fetched twice here.
            if (suggestListMoving()) return@SuggestAdapter
            val fill = item.optString("fill")
                .ifBlank { item.optString("url") }
                .ifBlank { item.optString("title") }
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
        Thread {
            val items = buildSuggestions(query.trim())
            runOnUiThread {
                if (id == suggestSeq && searchMode) suggestAdapter?.submit(items, query.trim())
            }
        }.start()
    }

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
    private val FIELD_H_COMPACT = 52f

    /**
     * How far the search pill travels as it slides up into the bar, in dp.
     * Small on purpose: this is a dock, not an entrance.
     */
    private val SLIDE_DP = 12f
    private val FIELD_H_SEARCH = 56f

    private fun styleUrlBar(mode: Int) {
        val d = resources.displayMetrics.density
        val pill = mode != FIELD_NORMAL
        val top = binding.urlBar.paddingTop
        val bottom = binding.urlBar.paddingBottom
        binding.urlBar.setBackgroundResource(
            if (pill) R.drawable.urlbar_search_bg else R.drawable.urlbar_bg)
        // The pill's edge is drawn over the whole frame, after the mic, scan
        // and clear buttons, so nothing inside the pill can cut its curve.
        binding.urlBarContainer.foreground =
            if (pill) androidx.core.content.ContextCompat.getDrawable(
                this, R.drawable.urlbar_search_outline) else null
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
            // 48dp star + its 8dp margin. Was 38 for a 30dp star.
            binding.urlBar.setPaddingRelative((14 * d).toInt(), top, (56 * d).toInt(), bottom)
        }

        val lp = binding.urlBarContainer.layoutParams
            as? android.widget.LinearLayout.LayoutParams ?: return
        // The field reaches as far as it can: about 9dp of air to the owl
        // (whose picture is inset in its square) and 10dp to the gear's icon
        // (centred in its 44dp button), the same either side. With the gear
        // away while searching, 8dp to the screen edge. The old 6dp margins
        // added to those insets left gaps twice as wide as the ones the
        // field sits between on the home page.
        lp.marginStart = (4 * d).toInt()
        lp.marginEnd = ((if (mode == FIELD_SEARCH) 4 else 0) * d).toInt()
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
        binding.urlBar.setPaddingRelative(
            (26 * d).toInt(), top, ((if (empty) 120 else 60) * d).toInt(), bottom)
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
            .setInterpolator(searchEase).start()
    }

    /**
     * The reverse, and deliberately quicker - leaving should feel like getting
     * out of the way, not like a second performance.
     *
     * The rows are cleared at the end rather than the start, or the sheet
     * would empty itself and then fade an empty panel out.
     */
    private fun animateSearchOut() {
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

    // ---- Search mode's curve ----
    //
    // While searching, the top of the screen - status bar to field - turns to
    // a band of searchTopBg, and the grey sheet of suggestions curves up into
    // it (search_sheet_bg). The band is the top bar's and the root's own
    // background, so it reaches up behind the status bar; what they were
    // painted before is kept and put back exactly when search closes. Night
    // Owl keeps its wash: that is the band then, and nothing is repainted.

    private var searchBandAnim: android.animation.ValueAnimator? = null
    private var searchBandNow = 0
    // The top bar's and the root's colours from before search; null while
    // search has not taken them over.
    private var bandSavedTop: Int? = null
    private var bandSavedRoot: Int? = null
    private var bandRootWasBare = false

    private fun baseBackground(): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
        return tv.data
    }

    private fun paintSearchBand(color: Int) {
        searchBandNow = color
        binding.topBar.setBackgroundColor(color)
        binding.rootView.setBackgroundColor(color)
    }

    /** The sheet's curved corners are filled in the band's colour. */
    private fun setSheetCornerColor(color: Int) {
        val layers = binding.suggestBackdrop.background?.mutate() as?
            android.graphics.drawable.LayerDrawable ?: return
        (layers.findDrawableByLayerId(R.id.sheetBand) as?
            android.graphics.drawable.GradientDrawable)?.setColor(color)
    }

    private fun applySearchBand(on: Boolean) {
        searchBandAnim?.cancel()
        searchBandAnim = null
        val topBg = binding.topBar.background
        val rootBg = binding.rootView.background
        if (on) {
            if (nightOwl) {
                (topBg as? android.graphics.drawable.ColorDrawable)?.let {
                    setSheetCornerColor(it.color)
                }
                return
            }
            val band = getColor(R.color.searchTopBg)
            setSheetCornerColor(band)
            if (bandSavedTop == null) {
                // Only plain colours are taken over; anything else is left be.
                val top = topBg as? android.graphics.drawable.ColorDrawable ?: return
                if (rootBg != null && rootBg !is android.graphics.drawable.ColorDrawable) return
                bandSavedTop = top.color
                bandRootWasBare = rootBg == null
                bandSavedRoot = rootBg?.color
                searchBandNow = baseBackground()
            }
            animateSearchBand(searchBandNow, band, 220L, null)
        } else {
            val savedTop = bandSavedTop ?: return
            val restore: () -> Unit = {
                bandSavedTop = null
                if (nightOwl) {
                    // Night Owl came on meanwhile: its wash, not the old colours.
                    applyNightOwlChrome(true)
                } else {
                    binding.topBar.setBackgroundColor(savedTop)
                    val savedRoot = bandSavedRoot
                    if (bandRootWasBare || savedRoot == null) {
                        binding.rootView.background = null
                    } else {
                        binding.rootView.setBackgroundColor(savedRoot)
                    }
                }
            }
            if (nightOwl) { restore(); return }
            animateSearchBand(searchBandNow, baseBackground(), 200L, restore)
        }
    }

    private fun animateSearchBand(from: Int, to: Int, duration: Long, end: (() -> Unit)?) {
        val anim = android.animation.ValueAnimator.ofArgb(from, to)
        anim.duration = duration
        anim.interpolator = searchEase
        anim.addUpdateListener { paintSearchBand(it.animatedValue as Int) }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(a: android.animation.Animator) {
                if (searchBandAnim === a) searchBandAnim = null
                if (!cancelled) end?.invoke()
            }
        })
        searchBandAnim = anim
        anim.start()
    }

    private fun enterSearchMode() {
        if (searchMode) return
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
        binding.urlBar.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.urlBar, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        binding.suggestBackdrop.visibility = View.VISIBLE
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
        applySearchBand(true)
        animateSearchIn(fieldWasShowing)
        fetchSuggests(binding.urlBar.text.toString())
    }

    private fun exitSearchMode() {
        if (!searchMode) return
        searchMode = false
        styleUrlBarForSearch(false)
        animateSearchOut()
        applySearchBand(false)
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
        applyAccentTints()
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

        val zoom = Settings.getTextScale(this)
        val js = Settings.getBool(this, Settings.SITE_JAVASCRIPT, true)
        val blockImg = Settings.getBool(this, Settings.SITE_BLOCK_IMAGES, false)
        val blockAutoplay = Settings.getBool(this, Settings.SITE_BLOCK_AUTOPLAY, true)
        val safeBrowsing = Settings.getBool(this, Settings.SEC_SAFE_BROWSING, true)
        val block3p = Settings.getBool(this, Settings.SEC_BLOCK_3P_COOKIES, false)
        tabs.tabs.forEach { tab ->
            tab.webView?.settings?.apply {
                textZoom = zoom
                javaScriptEnabled = js
                blockNetworkImage = blockImg
                mediaPlaybackRequiresUserGesture = blockAutoplay
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = safeBrowsing
                }
            }
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
    override fun onPause() {
        super.onPause()
        unregisterNetworkWatch()
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
            val signIn: Boolean = false
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
                signIn = t.signIn
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
        // Release the splash ~1.2s after the animation so it settles, then enters.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            keepSplash = false
        }, 1500L)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Apply the saved theme preference before inflating.
        when (Settings.getTheme(this)) {
            Settings.THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate
                .setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            Settings.THEME_DARK -> androidx.appcompat.app.AppCompatDelegate
                .setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            else -> androidx.appcompat.app.AppCompatDelegate
                .setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Edge-to-edge (mandatory on Android 16 / SDK 36): pad the root by the
        // system-bar insets so the top bar sits below the status bar and content
        // stays above the navigation bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
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
            // And the bottom bar steps out of the way while typing into the
            // page, as it does in Chrome, so the keyboard does not cost the
            // page its height twice.
            val bar = if (pageTyping) View.GONE else View.VISIBLE
            if (binding.bottomBar.visibility != bar) binding.bottomBar.visibility = bar
            // The suggestion list ends where the keyboard starts: padded by
            // whatever the keyboard covers beyond what the root already gives
            // back for the navigation bar. With clipToPadding=false that is
            // scrollable room, not dead space. On the backdrop, not the card,
            // so the card stops at the keyboard rather than growing a band.
            val base = if (suggestBasePad >= 0) suggestBasePad
                else binding.suggestBackdrop.paddingBottom
            val extra = if (pageTyping) 0 else (ime - bars.bottom).coerceAtLeast(0)
            binding.suggestBackdrop.setPadding(
                binding.suggestBackdrop.paddingLeft,
                binding.suggestBackdrop.paddingTop,
                binding.suggestBackdrop.paddingRight,
                base + extra
            )
            insets
        }
        setupSuggestOverlay()
        // The page changes height when the keyboard comes and goes, and with
        // it the bottom bar. The feed ad card is placed against the page's
        // bottom and only re-placed on scroll, so it is re-seated here too.
        binding.webArea.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                activeWeb()?.let { w ->
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

        setupUrlBar()
        setupToolbar()
        setupDeck()
        setupFindBar()
        initAds()
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
        }

        // A recreation must not re-handle the intent that launched the app, or
        // every rotation would reopen the link it was started with.
        val fresh = savedInstanceState == null && tabStore.snapshot == null
        if (!restoreTabs(savedInstanceState)) {
            // If launched by a tapped/shared link, open that; otherwise home.
            val startUrl = (if (fresh) urlFromIntent(intent) else null) ?: homePage
            val first = tabs.createTab(startUrl)
            openTab(first, startUrl)
        }
        updateTabCount()

    }

    // Handles links tapped/shared while the app is already open (singleTop
    // delivers them here instead of a fresh onCreate). Opens the link in a
    // new tab via the normal go() path.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = urlFromIntent(intent)
        if (incoming != null) {
            // Straight into the new tab. This used to open home and then
            // navigate away from it, which left the home page sitting in the
            // new tab's history - so back, from a link someone had just shared
            // into the browser, went to the home page instead of leaving.
            addNewTab(resolveInput(incoming))
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
            runOnUiThread { loadInto(activeWeb(), url) }
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
                val target = lastFailedUrl
                if (target != null) loadInto(activeWeb(), target)
                else activeWeb()?.reload()
            }
        }
        @JavascriptInterface
        fun getCachedFavicon(domain: String): String {
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

        @JavascriptInterface
        fun saveBlobFile(token: String?, dataUrl: String?) {
            if (token == null || token != blobDownloadToken) return
            blobDownloadToken = null
            val name = blobDownloadName
            val mime = blobDownloadMime
            if (dataUrl == null || !dataUrl.startsWith("data:")) {
                downloadFailed(); return
            }
            Thread { saveDataDownload(dataUrl, name, mime) }.start()
        }

        @JavascriptInterface
        fun saveBlobFileFailed(token: String?) {
            if (token == null || token != blobDownloadToken) return
            blobDownloadToken = null
            downloadFailed()
        }

        @JavascriptInterface
        fun getRecentSites(): String {
            // The browsing history. This is the call the gate exists for.
            if (!privileged) return "[]"
            // Return up to 8 most-recent unique domains from history as JSON.
            val entries = History.load(this@MainActivity)
            val seen = LinkedHashSet<String>()
            // Built with the JSON library rather than by hand. The hand-rolled
            // version escaped the url but not the domain, and neither against
            // control characters, so one odd address in the history produced
            // JSON the home page could not parse - and the tiles silently came
            // back empty with nothing to say why.
            val out = JSONArray()
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
            return "{\"background\":\"$bg\",\"accent\":\"$accent\",\"tiles\":$tiles,\"nightOwl\":$nightOwl}"
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
                    NewsFeed.fetch(this@MainActivity)
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
    // query that was typed rather than as the engine's URL.
    private fun searchQueryOf(url: String): String? = try {
        val u = android.net.Uri.parse(url)
        val host = u.host ?: ""
        val engine = listOf("google.", "bing.", "duckduckgo.", "search.yahoo.",
            "ecosia.", "startpage.", "search.brave.").any { host.contains(it) }
        if (engine) u.getQueryParameter("q")?.takeIf { it.isNotBlank() } else null
    } catch (e: Exception) {
        null
    }

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
            val shown = q ?: title
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

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun newWebView(): WebView {
        val web = BrowserWebView(this)
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
            mediaPlaybackRequiresUserGesture =
                Settings.getBool(this@MainActivity, Settings.SITE_BLOCK_AUTOPLAY, true)

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
                // Ad/tracker blocking (when enabled in settings).
                return AdBlocker.check(this@MainActivity, request)
                    ?: super.shouldInterceptRequest(view, request)
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
                if (view != null) replaceDeadWebView(view)
                return true
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
                if (t != null && url != null && url != t.url) {
                    t.url = url
                    if (view === activeWeb()) {
                        if (!binding.urlBar.hasFocus()) binding.urlBar.setText(displayUrl(url))
                        refreshStar()
                        refreshAdSlot()
                    }
                }
                if (view != null && view === activeWeb()) refreshNav()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // First, and before this document's script runs: decide whether
                // it may reach the private half of the bridge. Only pages
                // shipped inside the app may.
                (view?.tag as? SearchAppBridge)?.privileged =
                    url != null && url.startsWith("file:///android_asset/")
                if (searchMode && url != null && url != homePage && view != null &&
                    view === activeWeb()) exitSearchMode()
                super.onPageStarted(view, url, favicon)
                if (view == tabs.activeTab?.webView) {
                    if (!binding.urlBar.hasFocus()) binding.urlBar.setText(displayUrl(url))
                    refreshOmniboxVisibility(url)
                }
                // The tab this page is loading in, which is not necessarily
                // the one on screen. This used to write to the active tab
                // whatever the source: while a sign-in pop-up was showing, the
                // page behind it moving on after the sign-in landed in the
                // pop-up's tab, and the tab that really changed kept its old
                // address - the one it would be rebuilt from if its WebView
                // were frozen or lost.
                url?.let { u -> tabOf(view)?.url = u }
                tabOf(view)?.let { t ->
                    t.loadsStarted++
                    t.loadToken++
                    notePopupNavigation(view, url)
                    DebugLog.add { "tab#" + t.id + " start " + DebugLog.url(url) }
                }
                refreshNav()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
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
                // Cosmetic ad-hiding: hide common ad containers when blocking is on.
                if (AdBlocker.isEnabled(this@MainActivity)) {
                    view?.evaluateJavascript(AdBlocker.hideCss(), null)
                }
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
                    DebugLog.add { "tab#" + t.id + " finish " + DebugLog.url(url) }
                    if (t.openerId != null) watchReturnedPopup(t, url)
                }
                // Record the visited page in history (never in Night Owl mode).
                if (url != null && !nightOwl) {
                    // Off the main thread: it reads, edits and rewrites the
                    // whole 200-entry list as JSON, on every page load.
                    val title = view?.title ?: ""
                    val ctx = applicationContext
                    historyIo.execute { History.add(ctx, title, url) }
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
                    if (newProgress >= 100) endPullRefresh()
                    // The ring around the New tab button, not a line over the page.
                    binding.loadRing.setProgress(newProgress)
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
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            // A download usually begins life as an ordinary navigation, so
            // onPageStarted has already written this address into the tab and
            // the address bar before the server's Content-Disposition turns it
            // into a download. The page itself never changes, so the tab is put
            // back where it was - otherwise a download address is left sitting
            // in the bar over the home page, belonging to nothing on screen.
            restoreAfterDownload(web)
            startDownload(url, userAgent, contentDisposition, mimeType)
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

        web.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            lastWebScroll = android.os.SystemClock.uptimeMillis()
            positionAdSlot(v as android.webkit.WebView, scrollY)
        }
        return web
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
                    if (href.isNullOrBlank()) false else { showTargetMenu(null, href); true }
                }
                // Plain text and input fields fall through to the system, or we
                // would break text selection everywhere to add this menu.
                else -> false
            }
        }
    }

    private fun showTargetMenu(image: String?, link: String?) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()

        if (!link.isNullOrBlank()) {
            labels.add("Open link in new tab"); acts.add { addNewTab(link) }
            labels.add("Copy link address"); acts.add { copyText("Link", link) }
            labels.add("Share link"); acts.add { shareLink(link, "") }
        }
        if (!image.isNullOrBlank()) {
            labels.add("Save image"); acts.add { saveImage(image) }
            labels.add("Open image in new tab"); acts.add { addNewTab(image) }
            // A data: or blob: address is meaningless outside this page, so it
            // is not offered for copy or share - a link nobody can open is a
            // worse answer than no menu entry.
            if (image.startsWith("http")) {
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
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) { imageSaveFailed(); return }
            writeImage(bytes, if (mime.isNullOrBlank()) "image/jpeg" else mime)
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
                val dom = Shortcuts.domainOf(url)
                // One the user added is simply forgotten. A built-in or a site
                // from history is generated every render, so the only way to
                // keep it gone is to remember that it was removed.
                val wasCustom = Shortcuts.loadCustom(this)
                    .any { Shortcuts.domainOf(it.url) == dom }
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
        if (tab === tabs.activeTab) {
            // Outside the callback, which WebView is still running for the
            // other tabs this renderer served.
            uiHandler.post {
                if (!isFinishing && !isDestroyed && tab === tabs.activeTab &&
                    tab.webView == null
                ) openTab(tab)
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

    private var unresponsiveDialog: android.app.AlertDialog? = null
    // Set when the user chose to wait, so the prompt is not put straight back
    // up by the next unresponsive report for the same hang.
    private var waitingOnHang = false

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun onPageUnresponsive(view: WebView, renderer: android.webkit.WebViewRenderProcess?) {
        DebugLog.add { "page not responding in tab#" + tabOf(view)?.id }
        if (view !== activeWeb() || waitingOnHang || unresponsiveDialog != null) return
        if (isFinishing || isDestroyed) return
        unresponsiveDialog = android.app.AlertDialog.Builder(this)
            .setTitle("This page isn't responding")
            .setMessage("You can wait for it, or close it and reload. Reloading restarts every open page.")
            .setNegativeButton("Wait") { _, _ -> waitingOnHang = true }
            .setPositiveButton("Reload") { _, _ ->
                DebugLog.add { "user ended the unresponsive page" }
                // Ending the renderer comes back through onRenderProcessGone,
                // which rebuilds the tabs. If there is no renderer to end,
                // a plain reload is the next best thing.
                val ended = try { renderer?.terminate() == true } catch (e: Exception) { false }
                if (!ended && isLiveWeb(view)) view.reload()
            }
            .setOnDismissListener { unresponsiveDialog = null }
            .show()
    }

    private fun onPageResponsive(view: WebView) {
        waitingOnHang = false
        if (view === activeWeb()) {
            unresponsiveDialog?.dismiss()
            unresponsiveDialog = null
        }
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
        if (!low && !background) return
        DebugLog.add { "memory trim level " + level + ": freezing background tabs" }
        tabs.trimLive(1)
    }

    private fun openTab(tab: Tab, loadUrl: String? = null) {
        endPullRefresh(now = true)
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
        tabs.setActive(tab)
        tabs.markLive(tab)
        // The loading ring shows this tab's load, not the one just left.
        binding.loadRing.jumpTo(tab.webView?.progress ?: 0)
        binding.urlBar.setText(displayUrl(tab.url))
        updateTabCount()
        refreshStar()
        refreshOmniboxVisibility(tab.url)
        // A sign-in pop-up that came back while another tab was showing is
        // only judged while on screen, so the watch starts again here.
        if (tab.openerId != null && returnedFromSignIn(tab)) watchReturnedPopup(tab, tab.url)
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
     * The views that give up their width and fade as the bar collapses.
     *
     * Empty on purpose. The owl used to be in here, which is why it vanished on
     * scroll; now it stays exactly where it is through the whole transition and
     * the pill slides up to meet it. That is the difference between the bar
     * swapping states and the search box arriving.
     *
     * Empty is safe rather than merely tolerated: homeRowWidths caches an empty
     * list, and every forEachIndexed over the row becomes a no-op, so nothing
     * in the collapse machinery can reach the owl at all.
     */
    private fun homeBarRow(): List<View> = emptyList()

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
        // And it arrives rather than appearing. t is 1 for the icon row and 0
        // for the pill, so the pill starts SLIDE_DP below its resting place and
        // reaches it exactly as it reaches full opacity. The bar sets
        // clipChildren="false" for this: the compact pill is 52dp inside a 58dp
        // bar, leaving 3dp of room, so the travel would otherwise be sheared
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
        styleUrlBar(if (compact) FIELD_COMPACT else FIELD_NORMAL)
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
        binding.urlBarContainer.visibility =
            if (isHome && !homeCompact) View.INVISIBLE else View.VISIBLE
        refreshNav()
        refreshAdSlot()
        // Desktop mode is meaningless on the home page — reset it when we land
        // home so the next site opens as a normal mobile page.
        if (isHome && Settings.getBool(this, Settings.DESKTOP_MODE, false)) {
            Settings.setBool(this, Settings.DESKTOP_MODE, false)
            applyDesktopMode(false)
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

    private fun enterNightOwl() {
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
        addNewTab(homePage)
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
            val cookies = android.webkit.CookieManager.getInstance()
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
        // By id, not by binding.homeBtn.parent. The owl now sits inside a
        // FrameLayout so its tab badge can be positioned against it, and the
        // old expression would quietly resolve to that 48dp wrapper - putting
        // Night Owl's wash on a box behind the owl instead of on the bar.
        val topBar: View = binding.topBar
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)

        // Detect dark mode.
        val isDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (on) {
            // Subtle purple wash matched to the theme.
            val tint = if (isDark) "#231A3A" else "#ECE7F5"
            topBar.setBackgroundColor(android.graphics.Color.parseColor(tint))
            binding.navSheet.surfaceColor = android.graphics.Color.parseColor(tint)
            binding.rootView.setBackgroundColor(android.graphics.Color.parseColor(tint))
            // Icons: light icons on dark tint, dark icons on light tint.
            controller.isAppearanceLightStatusBars = !isDark
        } else {
            topBar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
        // The first row carries both of the jobs the owl's long press used to,
        // and says which one it is about to do. On the home page there is
        // nothing to go home to, so it offers the way out instead.
        val menuUrl = tabs.activeTab?.url
        val menuOnHome = (menuUrl == null || menuUrl == homePage)
        (binding.menuHome.getChildAt(0) as? android.widget.ImageView)
            ?.setImageResource(
                if (menuOnHome) R.drawable.menu_close else R.drawable.menu_home)
        (binding.menuHome.getChildAt(1) as? android.widget.TextView)?.text =
            if (menuOnHome) "Close Search" else "Take me home"
        // Reflect current Night Owl state in the menu label.
        (binding.menuNightOwl.getChildAt(1) as? android.widget.TextView)?.text =
            if (nightOwl) "Exit Night Owl" else "Night Owl"
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
    }

    // ---------- Tab deck ----------

    // ---------- Find in page ----------
    private var findActive = false

    private fun openFindBar() {
        val web = activeWeb() ?: return
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
        binding.adSlot.blocked = {
            android.os.SystemClock.uptimeMillis() - lastWebScroll < 300L
        }
        // Supporters paid for the ads to be gone, so the SDK is never started
        // rather than started and never asked - and with no ads there is no
        // consent to ask for. Initialisation makes network calls of its own;
        // "gone" should mean gone.
        if (Settings.getBool(this, Settings.IS_SUPPORTER, false)) return
        // Consent comes first: where the law requires an answer, the AdMob
        // message is shown and the SDK is not even started until there is one.
        AdConsent.gather(this) { allowed ->
            if (allowed && !isFinishing && !isDestroyed) startAdsSdk()
        }
    }

    // Initialization does disk work and can take a moment, so it runs off the
    // main thread per the SDK guide - otherwise it lands squarely in cold
    // start. Loading is bounced back to the main thread, where it must run.
    private fun startAdsSdk() {
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
        }.start()
    }

    /**
     * Asks for the one feed ad this session shows.
     *
     * Safe to call from anywhere: every reason not to ask is tested here rather
     * than at the call sites, so no caller has to remember them.
     */
    private fun loadFeedAd() {
        // Night Owl: no request at all. The card being hidden is not the point
        // - an ad request carries device signals for targeting, and a private
        // session is exactly when those should not be sent.
        if (nightOwl) return
        // Supporters bought the feed back. Tested before the request rather
        // than at bind time: an ad request carries device signals for targeting
        // whether or not the card is ever shown.
        if (Settings.getBool(this, Settings.IS_SUPPORTER, false)) return
        if (!adsReady || adLoading) return
        // Never without consent where it is required. Checked here as well as
        // at startup because this is reached from other paths - leaving Night
        // Owl - and the answer can change in Settings, Ad privacy choices.
        if (!AdConsent.canRequestAds(this)) return
        // One ad per session. Without this, leaving Night Owl a second time
        // would ask again for a card that is already on screen.
        if (nativeAd != null) return
        adLoading = true
        com.google.android.gms.ads.AdLoader.Builder(this, feedAdUnit)
            .forNativeAd { ad ->
                adLoading = false
                if (isFinishing || isDestroyed) { ad.destroy(); return@forNativeAd }
                // Night Owl was turned on while this was in flight. The request
                // is already out and cannot be recalled, but the card does not
                // appear and nothing is kept.
                if (nightOwl) { ad.destroy(); return@forNativeAd }
                nativeAd?.destroy()
                nativeAd = ad
                bindFeedAd(ad)
            }
            .withAdListener(object : com.google.android.gms.ads.AdListener() {
                override fun onAdFailedToLoad(e: com.google.android.gms.ads.LoadAdError) {
                    // Code 3 is no-fill, which a new unit does for hours and is
                    // not a wiring fault. 0 internal, 1 invalid request (bad unit
                    // id or app id mismatch), 2 network.
                    adLoading = false
                    binding.adSlot.visibility = View.GONE
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

    /**
     * Every asset is handed to the SDK through NativeAdView, which is what makes
     * impressions and clicks countable. Drawing these values anywhere else - into
     * the page, for instance - renders the same pixels and earns nothing.
     */
    private fun bindFeedAd(ad: com.google.android.gms.ads.nativead.NativeAd) {
        val view = layoutInflater.inflate(
            R.layout.native_ad_feed_card, binding.adSlot, false)
            as com.google.android.gms.ads.nativead.NativeAdView

        // An explicit outline: deriving one from the background drawable is not
        // reliable here, and without it the media runs square over the corners.
        val card = view.findViewById<View>(R.id.adCard)
        val radius = 20f * resources.displayMetrics.density
        card.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        card.clipToOutline = true

        val headline = view.findViewById<android.widget.TextView>(R.id.adHeadline)
        headline.text = ad.headline
        view.headlineView = headline

        val media = view.findViewById<com.google.android.gms.ads.nativead.MediaView>(R.id.adMedia)
        // Square thumbnail, cropped to fill, so any creative ratio reads the same.
        val frame = view.findViewById<View>(R.id.adMediaFrame)
        val thumbR = 12f * resources.displayMetrics.density
        frame.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, thumbR)
            }
        }
        frame.clipToOutline = true
        // 120dp tall is the floor for video inventory, but the creative's own
        // ratio decides the width: vertical video does serve here, and cropping
        // 9:16 into a square throws most of the frame away. Clamped either side
        // so the text column keeps usable room.
        val tall = 120f * resources.displayMetrics.density
        val ratio = ad.mediaContent?.aspectRatio ?: 1f
        val wide = if (ratio > 0f) tall * ratio else tall
        frame.layoutParams.width = wide.coerceIn(tall * 0.75f, tall * 1.25f).toInt()
        media.setImageScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
        ad.mediaContent?.let { media.mediaContent = it }
        view.mediaView = media

        val advertiser = view.findViewById<android.widget.TextView>(R.id.adAdvertiser)
        val who = ad.advertiser ?: ad.store ?: ""
        advertiser.text = who
        advertiser.visibility = if (who.isBlank()) View.GONE else View.VISIBLE
        view.advertiserView = advertiser

        val cta = view.findViewById<android.widget.TextView>(R.id.adCta)
        cta.text = ad.callToAction
        cta.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
        view.callToActionView = cta

        val icon = view.findViewById<android.widget.ImageView>(R.id.adIcon)
        val art = ad.icon?.drawable
        if (art != null) {
            icon.setImageDrawable(art)
            icon.visibility = View.VISIBLE
            view.iconView = icon
        } else icon.visibility = View.GONE

        view.setNativeAd(ad)
        binding.adSlot.removeAllViews()
        binding.adSlot.addView(view)
        refreshAdSlot()
    }

    /**
     * The reserved gap is the last stretch of the document, so at full scroll it
     * occupies exactly the bottom of the viewport - which is where the card
     * already sits. Anywhere earlier, push it down by whatever scrolling is
     * left, and it rides up into the gap as the end of the feed arrives.
     */
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

    private fun positionAdSlot(web: android.webkit.WebView, scrollY: Int) {
        // Belt as well as braces: the two posted callers check this before
        // calling, and this covers the scroll listener and anything added
        // later without either having to remember.
        if (!isLiveWeb(web)) return
        if (binding.adSlot.visibility != View.VISIBLE) return
        // Only ever against the home page on screen. A pass posted for one
        // page, or a scroll from a tab in the background, measured whatever
        // that WebView held - and seated the card over a site or a search.
        if (web !== activeWeb() || web.url != homePage) {
            binding.adSlot.translationY = adSlotParkedY()
            return
        }
        val content = (web.contentHeight * web.scale).toInt()
        // contentHeight reads 0 while the page lays out. Taken at face value it
        // gives maxScroll 0, so remaining is 0, so the card seats itself at the
        // bottom of the viewport mid-load and then jumps away once the real
        // height lands. Stay parked instead until there is a document to
        // measure against; a late correction is invisible from up there.
        if (content <= 0) {
            binding.adSlot.translationY = adSlotParkedY()
            return
        }
        val maxScroll = (content - web.height).coerceAtLeast(0)
        // contentHeight * scale is a rounded estimate, so the last few pixels
        // never arrive; settle the card once it is within a hair of seated.
        val slack = 12f * resources.displayMetrics.density
        val remaining = (maxScroll - scrollY).coerceAtLeast(0).toFloat()
        binding.adSlot.translationY = if (remaining <= slack) 0f else remaining
    }

    // Tells the page how much room to leave. Sent after layout, since the card's
    // height is not known until it has measured with a creative in it.
    @Suppress("DEPRECATION") // See positionAdSlot: same getScale() call.
    private fun syncAdSlotReserve() {
        val web = activeWeb() ?: return
        val scale = web.scale.takeIf { it > 0f } ?: 1f
        val visible = binding.adSlot.visibility == View.VISIBLE
        val h = binding.adSlot.height
        val css = if (visible && h > 0) (h / scale).toInt() else 0
        web.evaluateJavascript("window.__setAdSlot && window.__setAdSlot($css)", null)
        if (visible) {
            // isLiveWeb before each, and outside positionAdSlot, so that even
            // the argument expression web.scrollY is not evaluated against a
            // WebView that has been destroyed in the meantime.
            binding.adSlot.post {
                if (isLiveWeb(web)) positionAdSlot(web, web.scrollY)
            }
            // The reserve above lengthens the document, and contentHeight only
            // catches up after the page re-lays out - which has not happened by
            // the time the post above runs. One late pass settles the card. It
            // stays parked until then, so the wait costs nothing on screen.
            //
            // This 250ms is the SIGTRAP window: freezeTab can destroy `web`
            // inside it, and the reads in positionAdSlot would then abort the
            // process rather than fail.
            binding.adSlot.postDelayed({
                if (isLiveWeb(web)) positionAdSlot(web, web.scrollY)
            }, 250L)
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
            !deckVisible && !nightOwl
        val changed = (binding.adSlot.visibility == View.VISIBLE) != show
        // A different page under the card - another home tab, or home loaded
        // again - starts it parked too, rather than where the last one had it.
        val page = web to shown
        if (show && page != adSlotPage) binding.adSlot.translationY = adSlotParkedY()
        adSlotPage = if (show) page else null
        // THE FLICKER. translationY 0 means "seated at the bottom of the
        // viewport", which is exactly where a fully scrolled page shows the
        // card. Switching the slot on before positionAdSlot has run therefore
        // paints it on screen for a frame, then shoves it down out of the way -
        // read as a flash on app open, because that is when the ad arrives.
        // Park it first; positionAdSlot brings it to wherever it belongs.
        if (show && changed) binding.adSlot.translationY = adSlotParkedY()
        binding.adSlot.visibility = if (show) View.VISIBLE else View.GONE
        // Re-sent whenever the card is up, not only on a change: a home reload
        // (accent change, private toggle, tab switch) drops the page's CSS
        // reserve back to 0 without the slot's visibility ever changing, and
        // the card then overlaps the last feed card.
        if (changed || show) binding.adSlot.post { syncAdSlotReserve() }
    }

    override fun onDestroy() {
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
            tabs = tabs.tabs,
            // openTab before closeDeck, here and below: openTab takes a picture
            // of the tab being left, and refuses while the deck is still up -
            // closed first, the last frame on screen would be the deck itself.
            onSelect = { tab -> openTab(tab); closeDeck() },
            onClose = { tab -> closeTabFromDeck(tab) },
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
            androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0,
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                    androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) = false

            override fun getSwipeThreshold(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder) = 0.35f

            // A flick counts sooner than the default asks.
            override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 0.6f

            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                val tab = tabs.tabs.getOrNull(vh.bindingAdapterPosition)
                if (tab == null) tabAdapter.notifyDataSetChanged() else closeTabFromDeck(tab)
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
            }
        }).attachToRecyclerView(binding.tabList)

        binding.deckClose.setOnClickListener { closeDeck() }
        binding.deckNewTab.setOnClickListener { addNewTab(homePage); closeDeck() }

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
                deckSpring.animate(0f, 1f) { endDeckLayer() }
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
        deckSpring.cancel()
        deckOpenSeq++
        endDeckLayer()
        placeDeck(1f)
        binding.tabDeck.visibility = View.GONE
        deckVisible = false
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
        // refreshAdSlot() refuses to show the card while the deck is up, and
        // closing the deck is the moment that condition clears. Without this,
        // any refresh that lands while the deck is open leaves the card hidden
        // until the next navigation. Restore-only: it can never hide the card
        // in a state that already allowed it.
        refreshAdSlot()
    }

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
        // Specific type first - it is what routes a PDF to a PDF reader rather
        // than to a generic file browser. A wildcard type is the fallback for
        // one that nothing claims outright.
        if (startViewer(uri, mime)) return
        if (!mime.equals("*/*", true) && startViewer(uri, "*/*")) return
        toast("No app on this phone opens this kind of file")
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

    private fun hideBookmarks() {
        binding.bookmarkList.visibility = View.GONE
        binding.deckEmpty.visibility = View.GONE
        binding.tabList.visibility = View.VISIBLE
        bookmarksOpen = false
    }

    private fun hideHistory() {
        binding.historyList.visibility = View.GONE
        binding.deckEmpty.visibility = View.GONE
        binding.tabList.visibility = View.VISIBLE
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
        mimeType: String?
    ) {
        // If the user disabled download confirmation, download straight away.
        if (!Settings.getBool(this, Settings.SEC_CONFIRM_DOWNLOADS, true)) {
            performDownload(url, userAgent, contentDisposition, mimeType)
            return
        }
        val fileName = downloadName(url, contentDisposition, mimeType)

        val view = layoutInflater.inflate(R.layout.dialog_download, null)
        applyOwlArtIn(view)
        view.findViewById<android.widget.TextView>(R.id.dlFileName).text = fileName

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
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            // DownloadManager fetches in its own process, with none of the
            // WebView's state. Without the session cookie, anything behind a
            // sign-in hands back the sign-in page instead of the file, and the
            // download "succeeds" - you get a saved HTML page named report.pdf.
            //
            // getCookie() waits on Chromium's cookie thread, so it is asked on
            // the cookie thread's queue here rather than on the main thread,
            // and the enqueue follows it there.
            cookieIo.execute {
                val ok = try {
                    android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                        if (it.isNotBlank()) request.addRequestHeader("Cookie", it)
                    }
                    dm.enqueue(request)
                    true
                } catch (e: Exception) { false }
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        if (ok) "Downloading $fileName" else "Download failed",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this, "Download failed", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun downloadFailed() {
        runOnUiThread { toast("Couldn't save the file") }
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
     * to: fetch it, turn it into a data: URL and hand that back.
     *
     * Capped at 16MB because the whole thing crosses the bridge in one piece,
     * and base64 inflates it by a third on the way. A larger file is refused
     * outright rather than risking an out-of-memory kill on a modest phone -
     * a clear "too large" beats the browser dying mid-save.
     */
    private fun requestBlobDownload(url: String, name: String, mime: String?) {
        val web = activeWeb() ?: run { downloadFailed(); return }
        val token = java.util.UUID.randomUUID().toString()
        blobDownloadToken = token
        blobDownloadName = name
        blobDownloadMime = mime
        val js = "(function(u,t){try{" +
            "fetch(u).then(function(r){if(!r.ok)throw 0;return r.blob();})" +
            ".then(function(b){if(b.size>16777216)throw 0;" +
            "var fr=new FileReader();" +
            "fr.onloadend=function(){SearchApp.saveBlobFile(t,fr.result);};" +
            "fr.onerror=function(){SearchApp.saveBlobFileFailed(t);};" +
            "fr.readAsDataURL(b);})" +
            ".catch(function(){SearchApp.saveBlobFileFailed(t);});" +
            "}catch(e){SearchApp.saveBlobFileFailed(t);}})(" +
            JSONObject.quote(url) + "," + JSONObject.quote(token) + ");"
        web.evaluateJavascript(js, null)
    }

    private fun saveDataDownload(dataUrl: String, name: String, mime: String?) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) { downloadFailed(); return }
            val header = dataUrl.substring(5, comma)
            val body = dataUrl.substring(comma + 1)
            val bytes = if (header.contains("base64"))
                android.util.Base64.decode(body, android.util.Base64.DEFAULT)
            else java.net.URLDecoder.decode(body, "UTF-8").toByteArray()
            if (bytes.isEmpty()) { downloadFailed(); return }
            val type = mime?.takeIf { it.isNotBlank() }
                ?: header.substringBefore(';').ifBlank { "application/octet-stream" }
            writeDownloadBytes(bytes, type, name)
        } catch (e: Exception) {
            downloadFailed()
        }
    }

    /**
     * Writes into the public Downloads folder, the same place DownloadManager
     * puts everything else, so a file saved this way is where the user will
     * look for it rather than somewhere only this app knows about.
     */
    private fun writeDownloadBytes(bytes: ByteArray, mime: String, name: String) {
        // Where it ended up, so it can be listed and opened later.
        var savedUri: String? = null
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: run { downloadFailed(); return }
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                savedUri = uri.toString()
            } else {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                // Never overwrite a file already sitting there.
                val stem = name.substringBeforeLast('.', name)
                val ext = name.substringAfterLast('.', "")
                var f = java.io.File(dir, name)
                var n = 1
                while (f.exists()) {
                    val alt = stem + "(" + n + ")" + if (ext.isBlank()) "" else "." + ext
                    f = java.io.File(dir, alt)
                    n++
                }
                java.io.FileOutputStream(f).use { it.write(bytes) }
                // Without the scan the file is on disk but nothing lists it,
                // which reads to the user as another failed save.
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(f.absolutePath), arrayOf(mime), null)
                savedUri = android.net.Uri.fromFile(f).toString()
            }
            SavedFiles.add(this, name, savedUri, mime, bytes.size.toLong())
            runOnUiThread { toast("Saved to Downloads: " + name) }
        } catch (e: Exception) {
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
        binding.navHome.setOnClickListener { leaveTransientUi(); navHome() }
        binding.navBookmarks.setOnClickListener {
            leaveTransientUi(); openDeck(); showBookmarks()
        }
        binding.navNewTab.setOnClickListener { leaveTransientUi(); addNewTab(homePage) }
        binding.navTabs.setOnClickListener { leaveTransientUi(); openDeck() }
        // Long-pressing Tabs also opens a new tab, as it does in Chrome.
        binding.navTabs.setOnLongClickListener {
            leaveTransientUi(); addNewTab(homePage); true
        }
        binding.navMenu.setOnClickListener {
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
        binding.menuRefresh.setOnClickListener { closeMenuNow(); activeWeb()?.reload() }
        listOf(binding.navHome, binding.navBookmarks, binding.navNewTab, binding.navMenu,
            binding.settingsBtn, binding.menuBack, binding.menuForward, binding.menuRefresh
        ).forEach { b ->
            androidx.core.view.ViewCompat.setTooltipText(b, b.contentDescription)
        }
        androidx.core.view.ViewCompat.setTooltipText(binding.navTabs, "Tabs")
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
        val neutral = android.content.res.ColorStateList.valueOf(navIconColor())
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
        binding.settingsBtn.imageTintList = neutral
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
        // Debug builds add "Share debug log" here; release builds have none.
        DebugTools.install(this, binding.menuRows, { tabs.maxLiveTabs }) { closeMenuNow() }
        binding.menuDownloads.setOnClickListener {
            closeMenuNow()
            openDownloads()
        }
        binding.menuFind.setOnClickListener {
            closeMenuNow()
            openFindBar()
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
            } else if (v is android.widget.ImageView) {
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
