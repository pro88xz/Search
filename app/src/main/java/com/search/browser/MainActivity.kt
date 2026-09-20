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
    }

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
        android.app.AlertDialog.Builder(this)
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
            Settings.getTextScale(this)
        ).joinToString("|")
    }
    private var suggestAdapter: SuggestAdapter? = null
    private var suggestSeq = 0
    // The list's own bottom padding from the layout, kept so the keyboard
    // inset can be added on top of it rather than replacing it.
    private var suggestBasePad = -1

    private fun setupSuggestOverlay() {
        if (suggestBasePad < 0) suggestBasePad = binding.suggestOverlay.paddingBottom
        suggestAdapter = SuggestAdapter(emptyList(), { item ->
            if (suggestListMoving()) return@SuggestAdapter
            val kind = item.optString("kind")
            val title = item.optString("title")
            val url = item.optString("url")
            exitSearchMode()
            if (kind == "web" || url.isBlank()) go(title) else activeWeb()?.loadUrl(url)
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

    private fun styleUrlBar(mode: Int) {
        val d = resources.displayMetrics.density
        val pill = mode != FIELD_NORMAL
        val top = binding.urlBar.paddingTop
        val bottom = binding.urlBar.paddingBottom
        binding.urlBar.setBackgroundResource(
            if (pill) R.drawable.urlbar_search_bg else R.drawable.urlbar_bg)
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
            binding.urlBar.setPaddingRelative((14 * d).toInt(), top, (38 * d).toInt(), bottom)
        }

        val lp = binding.urlBarContainer.layoutParams
            as? android.widget.LinearLayout.LayoutParams ?: return
        lp.marginStart = ((if (pill) 6 else 4) * d).toInt()
        lp.marginEnd = ((if (pill) 6 else 4) * d).toInt()
        lp.height = (when (mode) {
            FIELD_SEARCH -> 50
            FIELD_COMPACT -> 44
            else -> 40
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
        binding.urlBar.setPaddingRelative(
            (26 * d).toInt(), top, ((if (empty) 92 else 46) * d).toInt(), bottom)
    }

    private fun enterSearchMode() {
        if (searchMode) return
        searchMode = true
        // The bar is non-focusable at rest; make it typable now that the user
        // has deliberately entered search mode. This is the ONLY place focus is
        // enabled, so system/incidental focus can never trigger search mode.
        binding.urlBar.isFocusable = true
        binding.urlBar.isFocusableInTouchMode = true
        binding.homeBtn.visibility = View.GONE
        binding.reloadBtn.visibility = View.GONE
        binding.tabCountBtn.visibility = View.GONE
        binding.settingsBtn.visibility = View.GONE
        binding.starBtn.visibility = View.GONE
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
        binding.suggestOverlay.visibility = View.VISIBLE
        fetchSuggests(binding.urlBar.text.toString())
    }

    private fun exitSearchMode() {
        if (!searchMode) return
        searchMode = false
        styleUrlBarForSearch(false)
        binding.suggestOverlay.visibility = View.GONE
        suggestAdapter?.submit(emptyList())
        binding.homeBtn.visibility = View.VISIBLE
        binding.reloadBtn.visibility = View.VISIBLE
        binding.tabCountBtn.visibility = View.VISIBLE
        binding.settingsBtn.visibility = View.VISIBLE
        binding.starBtn.visibility = View.VISIBLE
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
        val sig = siteSettingsSignature()
        if (sig == lastSiteSig) return  // nothing changed -> don't touch anything
        lastSiteSig = sig

        val zoom = Settings.getTextScale(this)
        val js = Settings.getBool(this, Settings.SITE_JAVASCRIPT, true)
        val blockImg = Settings.getBool(this, Settings.SITE_BLOCK_IMAGES, false)
        val blockAutoplay = Settings.getBool(this, Settings.SITE_BLOCK_AUTOPLAY, true)
        tabs.tabs.forEach { tab ->
            tab.webView?.settings?.apply {
                textZoom = zoom
                javaScriptEnabled = js
                blockNetworkImage = blockImg
                mediaPlaybackRequiresUserGesture = blockAutoplay
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
        android.webkit.CookieManager.getInstance().flush()
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
            val openerIndex: Int
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
                openerIndex = list.indexOfFirst { it.id == t.openerId }
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
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)

            // Under edge-to-edge the keyboard never resizes the window - it just
            // arrives as an inset. Nothing read it, so the tail of the suggestion
            // list rendered behind the keyboard and its last rows could not be
            // scrolled to. Pad the list by whatever the keyboard covers beyond
            // what the root already gives back for the navigation bar; with
            // clipToPadding=false that becomes scrollable room, not dead space.
            // Applied here rather than from a listener on the list itself,
            // because a ViewGroup with its own listener stops dispatching insets
            // down to its children.
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val extra = (ime - bars.bottom).coerceAtLeast(0)
            val base = if (suggestBasePad >= 0) suggestBasePad
                else binding.suggestOverlay.paddingBottom
            binding.suggestOverlay.setPadding(
                binding.suggestOverlay.paddingLeft,
                binding.suggestOverlay.paddingTop,
                binding.suggestOverlay.paddingRight,
                base + extra
            )
            insets
        }
        setupSuggestOverlay()
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
                    else -> finish()
                }
            }
        })

        tabs.onNeedFreeze = { tab -> freezeTab(tab) }

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
            addNewTab(homePage)
            go(incoming)
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
            runOnUiThread { activeWeb()?.loadUrl(url) }
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

        @JavascriptInterface
        fun retry() {
            if (!privileged) return
            runOnUiThread {
                val target = lastFailedUrl
                if (target != null) activeWeb()?.loadUrl(target)
                else activeWeb()?.reload()
            }
        }
        @JavascriptInterface
        fun cacheFavicon(domain: String, dataUrl: String) {
            if (!privileged) return
            if (domain.isBlank() || dataUrl.isBlank()) return
            getSharedPreferences("favicon_cache", Context.MODE_PRIVATE)
                .edit().putString(domain, dataUrl).apply()
        }

        @JavascriptInterface
        fun getCachedFavicon(domain: String): String {
            if (!privileged) return ""
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
            val out = StringBuilder("[")
            var count = 0
            for (e in entries) {
                if (count >= 8) break
                val host = try {
                    android.net.Uri.parse(e.url).host ?: continue
                } catch (ex: Exception) { continue }
                // Mobile hosts stripped too, or labelFromDomain takes the first
                // label of m.youtube.com and titles the tile "M" - and the dedup
                // below counts m.youtube.com and youtube.com as two sites.
                val domain = host.removePrefix("www.")
                    .removePrefix("mobile.").removePrefix("m.")
                if (domain.isBlank() || !seen.add(domain)) continue
                if (count > 0) out.append(",")
                val safeUrl = e.url.replace("\\", "\\\\").replace("\"", "\\\"")
                out.append("{\"domain\":\"").append(domain).append("\",")
                out.append("\"url\":\"").append(safeUrl).append("\"}")
                count++
            }
            out.append("]")
            return out.toString()
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
        fun suggest(query: String, requestId: Int) {
            if (!privileged) return
            Thread {
                val items = buildSuggestions(query.trim())
                runOnUiThread { pushSuggestions(requestId, items) }
            }.start()
        }
        @JavascriptInterface
        fun getFeed(requestId: Int) {
            if (!privileged) return
            Thread {
                val json = NewsFeed.fetch(this@MainActivity)
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

    private fun pushSuggestions(requestId: Int, items: List<JSONObject>) {
        val web = activeWeb() ?: return
        val payload = JSONArray(items).toString()
        val js = "window.__onSuggest && window.__onSuggest(" + requestId + ", JSON.parse(" +
            JSONObject.quote(payload) + "));"
        web.evaluateJavascript(js, null)
    }

    // ---------- WebView creation / lifecycle ----------

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun newWebView(): WebView {
        val web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        web.settings.apply {
            javaScriptEnabled = Settings.getBool(this@MainActivity, Settings.SITE_JAVASCRIPT, true)
            domStorageEnabled = true
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
                // Web content and our own asset pages: load here as normal.
                if (scheme == "http" || scheme == "https" || scheme == "file" ||
                    scheme == "about" || scheme == "data" || scheme == "blob"
                ) return false
                // A navigation must never be a way to run script.
                if (scheme == "javascript") return true
                // Only a top-level navigation may leave the app. Without this a
                // hidden iframe could throw the user into another app on its own.
                if (!request.isForMainFrame) return true
                openExternal(uri.toString())
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
                    lastFailedUrl = request.url?.toString()
                    if (hasNetwork()) {
                        // Online but the site failed (bad address, host down, refused):
                        // show the "can't reach site" page with the failed URL.
                        val enc = try {
                            java.net.URLEncoder.encode(lastFailedUrl ?: "", "UTF-8")
                        } catch (e: Exception) { "" }
                        view?.loadUrl("file:///android_asset/error.html?u=$enc")
                    } else {
                        // Genuinely no connectivity: show the offline page.
                        view?.loadUrl("file:///android_asset/offline.html")
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // First, and before this document's script runs: decide whether
                // it may reach the private half of the bridge. Only pages
                // shipped inside the app may.
                (view?.tag as? SearchAppBridge)?.privileged =
                    url != null && url.startsWith("file:///android_asset/")
                if (searchMode && url != null && url != homePage) exitSearchMode()
                super.onPageStarted(view, url, favicon)
                if (view == tabs.activeTab?.webView) {
                    if (!binding.urlBar.hasFocus()) binding.urlBar.setText(displayUrl(url))
                    updateNavButtons()
                    refreshOmniboxVisibility(url)
                }
                url?.let { tabs.activeTab?.url = it }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                // Restated here as well, so a page that arrives by a route
                // which skips the start callback - a restored tab, say - still
                // ends up with the right answer.
                (view?.tag as? SearchAppBridge)?.privileged =
                    url != null && url.startsWith("file:///android_asset/")
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
                tabs.activeTab?.let { t ->
                    t.title = view?.title ?: t.title
                    t.url = url ?: t.url
                }
                // Record the visited page in history (never in Night Owl mode).
                if (url != null && !nightOwl) {
                    History.add(this@MainActivity, view?.title ?: "", url)
                }
                if (view == tabs.activeTab?.webView) {
                    updateNavButtons(); refreshStar(); refreshOmniboxVisibility(url)
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

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view == tabs.activeTab?.webView) {
                    binding.progressBar.progress = newProgress
                    binding.progressBar.visibility =
                        if (newProgress in 1..99) View.VISIBLE else View.GONE
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
                        this@MainActivity, Settings.SEC_BLOCK_POPUPS, true)) return false
                // Validate the transport BEFORE creating any tab, so a malformed
                // window.open() can't leave an orphan about:blank tab in the list.
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val opener = tabs.activeTab
                val popupTab = tabs.createTab("about:blank")
                popupTab.openerId = opener?.id
                popupTab.title = "Opening\u2026"
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
                val closing = tabs.tabs.firstOrNull { it.webView == window }
                if (closing != null) closeTabFromDeck(closing)
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

    override fun onPictureInPictureModeChanged(
        isInPip: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        inPip = isInPip
        // Closed from the PiP window rather than expanded back into the app:
        // the activity is on its way to stopped, so tear the player down now
        // instead of leaving a fullscreen view behind for the next launch.
        if (!isInPip &&
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

        android.app.AlertDialog.Builder(this)
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
    private fun displayUrl(url: String?): String =
        if (url == null || url.startsWith("file:///android_asset/")) "" else url

    private fun captureThumbnail(tab: Tab, onDone: (() -> Unit)? = null) {
        val web = tab.webView
        if (deckVisible || web == null || web.width <= 0 || web.height <= 0 ||
            !web.isAttachedToWindow
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

    private fun softwareCapture(tab: Tab) {
        if (deckVisible) return
        val web = tab.webView ?: return
        if (web.width <= 0 || web.height <= 0) return
        try {
            val full = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.RGB_565)
            web.draw(Canvas(full))
            storeScaled(tab, full)
        } catch (e: Exception) { /* skip */ }
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

    private fun openTab(tab: Tab, loadUrl: String? = null) {
        binding.webContainer.removeAllViews()
        if (tab.webView == null) {
            val web = newWebView()
            tab.webView = web
            val restored = tab.savedState?.let { web.restoreState(it) != null } ?: false
            if (!restored) web.loadUrl(loadUrl ?: tab.url)
        } else if (loadUrl != null) {
            tab.webView!!.loadUrl(loadUrl)
        }
        binding.webContainer.addView(tab.webView)
        tabs.setActive(tab)
        tabs.markLive(tab)
        binding.urlBar.setText(displayUrl(tab.url))
        updateNavButtons()
        updateTabCount()
        refreshStar()
        refreshOmniboxVisibility(tab.url)
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
    // 54dp bar changes, so the page cannot jump or reflow while scrolling.
    private var homeBarAnim: android.animation.Animator? = null
    private var homeBarSeq = 0
    // Where the row actually is: 1f icons full, 0f fully collapsed. Kept here
    // because the row's width cannot answer it - snapHomeBar restores every
    // width to full and encodes the collapsed state in visibility instead.
    private var homeBarProgress = 1f
    private var homeRowWidths: List<Int>? = null

    private fun homeBarRow(): List<View> = listOf(
        binding.homeBtn, binding.reloadBtn, binding.tabCountBtn, binding.settingsBtn)

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

        val settled = binding.homeBtn.visibility == (if (compact) View.GONE else View.VISIBLE)
        if (!animate || settled) { snapHomeBar(compact); return }

        // Collapsing: the pill takes its final look and height now, while it is
        // still fully transparent, so the only thing the eye follows is the
        // crossfade. Expanding does the reverse, restyling once alpha reaches 0.
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
        val anim = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = ((if (compact) 240 else 440) * span).toLong().coerceAtLeast(90L)
            interpolator = android.view.animation.PathInterpolator(
                if (compact) 0.3f else 0.05f, 0f, 0f, 1f)
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
                binding.reloadBtn.visibility = View.VISIBLE
                binding.tabCountBtn.visibility = View.VISIBLE
                binding.settingsBtn.visibility = View.VISIBLE
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
        refreshAdSlot()
        // Desktop mode is meaningless on the home page — reset it when we land
        // home so the next site opens as a normal mobile page.
        if (isHome && Settings.getBool(this, Settings.DESKTOP_MODE, false)) {
            Settings.setBool(this, Settings.DESKTOP_MODE, false)
            applyDesktopMode(false)
        }
    }

    private fun freezeTab(tab: Tab) {
        val web = tab.webView ?: return
        // Never freeze a tab that opened a pop-up still on screen. Freezing
        // destroys the WebView, and with it the window.opener handle the pop-up
        // needs to hand its result back - which is what a sign-in callback rides
        // on. Only reachable with several tabs live at once.
        if (tabs.tabs.any { it.openerId == tab.id }) return
        softwareCapture(tab)
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

    private fun enterNightOwl() {
        nightOwl = true
        // Isolate the private session: no disk cache, no form/password saving.
        activeWeb()?.settings?.apply {
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            saveFormData = false
        }
        // Don't persist cookies created during Night Owl.
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        // Visual indicator.
        binding.nightOwlBadge.visibility = View.VISIBLE
        applyNightOwlChrome(true)
        // Fresh private tab.
        addNewTab(homePage)
        android.widget.Toast.makeText(this,
            "Night Owl on — private browsing", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun exitNightOwl() {
        nightOwl = false
        // Wipe session data created during Night Owl.
        android.webkit.CookieManager.getInstance().removeSessionCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        activeWeb()?.clearCache(true)
        binding.nightOwlBadge.visibility = View.GONE
        applyNightOwlChrome(false)
        // If we're on the home page, reload it so it drops the private empty-state
        // and shows the normal tiles/feed again immediately.
        val current = activeWeb()?.url
        if (current == null || current == homePage) activeWeb()?.loadUrl(homePage)
        android.widget.Toast.makeText(this,
            "Night Owl off", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun applyNightOwlChrome(on: Boolean) {
        val topBar = binding.homeBtn.parent as? View
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)

        // Detect dark mode.
        val isDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (on) {
            // Subtle purple wash matched to the theme.
            val tint = if (isDark) "#231A3A" else "#ECE7F5"
            topBar?.setBackgroundColor(android.graphics.Color.parseColor(tint))
            binding.rootView.setBackgroundColor(android.graphics.Color.parseColor(tint))
            // Icons: light icons on dark tint, dark icons on light tint.
            controller.isAppearanceLightStatusBars = !isDark
        } else {
            topBar?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
            binding.rootView.setBackgroundColor(tv.data)
            controller.isAppearanceLightStatusBars = !isDark
        }
    }

    private fun openDownloads() {
        openDeck()
        showDownloads()
    }

    private fun openMenu() {
        // Reflect current Night Owl state in the menu label.
        (binding.menuNightOwl.getChildAt(1) as? android.widget.TextView)?.text =
            if (nightOwl) "Exit Night Owl" else "Night Owl"
        binding.menuScrim.visibility = View.VISIBLE
        binding.menuScrim.alpha = 0f
        binding.menuScrim.animate().alpha(1f).setDuration(150).start()
        val panel = binding.menuPanel
        panel.post {
            panel.pivotX = panel.width.toFloat()
            panel.pivotY = 0f
            panel.scaleX = 0.85f
            panel.scaleY = 0.85f
            panel.alpha = 0f
            panel.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .setDuration(220)
                .start()
        }
    }

    private fun closeMenu() {
        val panel = binding.menuPanel
        panel.pivotX = panel.width.toFloat()
        panel.pivotY = 0f
        panel.animate()
            .scaleX(0.9f).scaleY(0.9f).alpha(0f)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .setDuration(130)
            .start()
        binding.menuScrim.animate().alpha(0f).setDuration(130)
            .withEndAction {
                binding.menuScrim.visibility = View.GONE
                panel.scaleX = 1f; panel.scaleY = 1f; panel.alpha = 1f
            }.start()
    }

    /** Instant close (no fade) for when a menu item's action follows immediately,
     *  so the panel doesn't linger see-through over the page during the action. */
    private fun closeMenuNow() {
        binding.menuScrim.animate().cancel()
        binding.menuPanel.animate().cancel()
        binding.menuScrim.visibility = View.GONE
        binding.menuScrim.alpha = 1f
        binding.menuPanel.scaleX = 1f
        binding.menuPanel.scaleY = 1f
        binding.menuPanel.alpha = 1f
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

    private val feedAdUnit = "ca-app-pub-9121922395304175/6184493298"

    private var nativeAd: com.google.android.gms.ads.nativead.NativeAd? = null

    private var lastWebScroll = 0L

    private fun initAds() {
        // Gestures are mirrored onto the page so it scrolls under the card;
        // only the tap that halts a fling is withheld.
        binding.adSlot.page = { activeWeb() }
        binding.adSlot.blocked = {
            android.os.SystemClock.uptimeMillis() - lastWebScroll < 300L
        }
        // Initialization does disk work and can take a moment, so it runs off the
        // main thread per the SDK guide - otherwise it lands squarely in cold
        // start. Loading is bounced back to the main thread, where it must run.
        Thread {
            com.google.android.gms.ads.MobileAds.initialize(this) {
                runOnUiThread { if (!isFinishing && !isDestroyed) loadFeedAd() }
            }
        }.start()
    }

    private fun loadFeedAd() {
        com.google.android.gms.ads.AdLoader.Builder(this, feedAdUnit)
            .forNativeAd { ad ->
                if (isFinishing || isDestroyed) { ad.destroy(); return@forNativeAd }
                nativeAd?.destroy()
                nativeAd = ad
                bindFeedAd(ad)
            }
            .withAdListener(object : com.google.android.gms.ads.AdListener() {
                override fun onAdFailedToLoad(e: com.google.android.gms.ads.LoadAdError) {
                    // Code 3 is no-fill, which a new unit does for hours and is
                    // not a wiring fault. 0 internal, 1 invalid request (bad unit
                    // id or app id mismatch), 2 network.
                    binding.adSlot.visibility = View.GONE
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

    private fun positionAdSlot(web: android.webkit.WebView, scrollY: Int) {
        if (binding.adSlot.visibility != View.VISIBLE) return
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
    private fun syncAdSlotReserve() {
        val web = activeWeb() ?: return
        val scale = web.scale.takeIf { it > 0f } ?: 1f
        val visible = binding.adSlot.visibility == View.VISIBLE
        val h = binding.adSlot.height
        val css = if (visible && h > 0) (h / scale).toInt() else 0
        web.evaluateJavascript("window.__setAdSlot && window.__setAdSlot($css)", null)
        if (visible) {
            binding.adSlot.post { positionAdSlot(web, web.scrollY) }
            // The reserve above lengthens the document, and contentHeight only
            // catches up after the page re-lays out - which has not happened by
            // the time the post above runs. One late pass settles the card. It
            // stays parked until then, so the wait costs nothing on screen.
            binding.adSlot.postDelayed({ positionAdSlot(web, web.scrollY) }, 250L)
        }
    }

    // Home page only, and never over the search sheet or the tab deck.
    private fun refreshAdSlot() {
        val url = tabs.activeTab?.url
        val onHome = (url == null || url == homePage)
        val show = nativeAd != null && onHome && !searchMode && !deckVisible
        val changed = (binding.adSlot.visibility == View.VISIBLE) != show
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
            onSelect = { tab -> closeDeck(); openTab(tab) },
            onClose = { tab -> closeTabFromDeck(tab) }
        )
        binding.tabList.layoutManager = GridLayoutManager(this, 2)
        binding.tabList.adapter = tabAdapter

        binding.deckClose.setOnClickListener { closeDeck() }
        binding.deckNewTab.setOnClickListener { closeDeck(); addNewTab(homePage) }


        binding.deckSearch.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (enter) {
                val q = binding.deckSearch.text.toString()
                if (q.isNotBlank()) {
                    closeDeck()
                    addNewTab(UrlHelper.toUrlOrSearch(q, Settings.getEngineUrl(this)))
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
        tabAdapter.notifyDataSetChanged()
        binding.tabDeck.visibility = View.VISIBLE
    }

    private fun closeDeck() {
        hideKeyboard()
        binding.tabDeck.visibility = View.GONE
        deckVisible = false
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

    private fun toggleHistory() {
        if (historyOpen) hideHistory() else showHistory()
    }

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
                onDelete = { d ->
                    try {
                        (getSystemService(DOWNLOAD_SERVICE)
                            as android.app.DownloadManager).remove(d.id)
                    } catch (_: Exception) {}
                    refreshDownloads()
                }
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
        // readable by another app as soon as the read grant is attached.
        try {
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            val own = dm.getUriForDownloadedFile(d.id)
            if (own != null && own.scheme.equals("content", true)) return own
        } catch (e: Exception) { /* fall through to the stored path */ }

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
                closeDeck()
                addNewTab(entry.url)
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

    private fun toggleBookmarks() {
        if (bookmarksOpen) hideBookmarks() else showBookmarks()
    }

    private fun showBookmarks() {
        val entries = Bookmarks.load(this).map { History.Entry(it.title, it.url, it.time) }
        val adapter = HistoryAdapter(
            entries = entries,
            onSelect = { entry -> closeDeck(); addNewTab(entry.url) },
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

    private fun onOwlTapped() {
        val current = activeWeb()?.url
        val onHome = (current == null || current == homePage)
        if (onHome) showOwlCloseDialog() else showOwlHomeDialog()
    }

    private fun showOwlHomeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_owl_home, null)
        applyOwlArtIn(view)
        val dialog = android.app.AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.window?.attributes?.windowAnimations = R.style.OwlDialogAnim
        view.findViewById<android.widget.TextView>(R.id.owlStay).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<android.widget.TextView>(R.id.owlGoHome).setOnClickListener {
            dialog.dismiss()
            activeWeb()?.loadUrl(homePage)
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
        val fileName = try {
            android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (e: Exception) { "file" }

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
            val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
            request.setMimeType(mimeType)
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            // DownloadManager fetches in its own process, with none of the
            // WebView's state. Without the session cookie, anything behind a
            // sign-in hands back the sign-in page instead of the file, and the
            // download "succeeds" - you get a saved HTML page named report.pdf.
            // Some hosts also refuse a request that arrives with no referer.
            android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                if (it.isNotBlank()) request.addRequestHeader("Cookie", it)
            }
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
            dm.enqueue(request)
            android.widget.Toast.makeText(
                this, "Downloading $fileName", android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this, "Download failed", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun downloadFailed() {
        runOnUiThread { toast("Couldn't save the file") }
    }

    /** A filename carrying an extension that matches the type, whatever the
     *  address happens to say - a blob: address carries no name at all. */
    private fun downloadName(url: String, cd: String?, mime: String?): String {
        val guessed = try {
            android.webkit.URLUtil.guessFileName(url, cd, mime)
        } catch (e: Exception) { "" }
        val base = guessed.ifBlank { "download_" + System.currentTimeMillis() }
        if (base.substringAfterLast('.', "").isNotBlank()) return base
        val ext = mime?.let {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        }
        return if (ext.isNullOrBlank()) base else base + "." + ext
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
            }
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

    private fun setupToolbar() {
        binding.reloadBtn.setOnClickListener { activeWeb()?.reload() }
        binding.homeBtn.setOnClickListener { onOwlTapped() }
        binding.tabCountBtn.setOnClickListener { openDeck() }
        applyAccentTints()
        binding.settingsBtn.setOnClickListener { openMenu() }

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
        binding.menuBookmarks.setOnClickListener {
            closeMenuNow(); openDeck(); showBookmarks()
        }
        binding.menuSettings.setOnClickListener {
            closeMenuNow()
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
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
     */
    private fun openExternal(url: String): Boolean {
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
            return true
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
            activeWeb()?.loadUrl(fallback)
            return true
        }
        // Say so plainly. The old path showed "this site can't be reached",
        // which blamed the site for a missing app.
        android.widget.Toast.makeText(
            this, "No app on this phone opens that kind of link",
            android.widget.Toast.LENGTH_SHORT).show()
        return true
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

    private fun go(input: String) {
        if (searchMode) exitSearchMode()
        var url = UrlHelper.toUrlOrSearch(input, Settings.getEngineUrl(this))
        // HTTPS-only mode: upgrade insecure http links.
        if (Settings.getBool(this, Settings.SEC_HTTPS_ONLY, true) &&
            url.startsWith("http://")) {
            url = "https://" + url.removePrefix("http://")
        }
        activeWeb()?.loadUrl(url)
        hideKeyboard()
        activeWeb()?.requestFocus()
    }

    private fun activeWeb(): WebView? = tabs.activeTab?.webView

    private fun updateNavButtons() { /* system back handles page-back */ }

    private fun updateTabCount() { binding.tabCountBtn.text = tabs.count().toString() }

    // Everything that carries the accent on the home page carries it here too:
    // the tab count, and the mic and scan glyphs, which home draws in var(--accent).
    private fun applyAccentTints() {
        val accent = try {
            android.graphics.Color.parseColor(Settings.getHomeAccent(this))
        } catch (e: Exception) {
            android.graphics.Color.parseColor("#8B6BD8")
        }
        binding.tabCountBtn.setTextColor(accent)
        val tint = android.content.res.ColorStateList.valueOf(accent)
        binding.micBtn.imageTintList = tint
        binding.scanBtn.imageTintList = tint
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
        val accent = try {
            android.graphics.Color.parseColor(Settings.getHomeAccent(this))
        } catch (e: Exception) {
            android.graphics.Color.parseColor("#8B6BD8")
        }
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
        recolouredToAccent(R.drawable.ic_settings, accent)
            ?.let { binding.settingsBtn.setImageBitmap(it) }
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

    private fun recolouredToAccent(resId: Int, accent: Int): android.graphics.Bitmap? {
        val art = androidx.core.content.ContextCompat.getDrawable(this, resId)
        val src = (art as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null

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
