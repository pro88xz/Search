package com.search.browser
import androidx.activity.enableEdgeToEdge
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
    private val storagePermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pd = pendingDownload
        pendingDownload = null
        if (granted && pd != null) {
            performDownload(pd.url, pd.userAgent, pd.contentDisposition, pd.mimeType)
        } else if (!granted) {
            android.widget.Toast.makeText(this,
                "Storage permission is needed to download files",
                android.widget.Toast.LENGTH_SHORT).show()
        }
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

    private fun setupSuggestOverlay() {
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
            insets
        }
        setupSuggestOverlay()
        lastSiteSig = siteSettingsSignature()

        onBackPressedDispatcher.addCallback(this, object :
            androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = activeWeb()
                when {
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
        MediaService.onControl = { action ->
            runOnUiThread {
                activeWeb()?.evaluateJavascript(
                    "window.__searchMediaControl && window.__searchMediaControl('" + action + "');", null)
            }
        }
        // Quietly check Play for a newer version on launch; prompts only if one
        // exists. Guarded to run once per process so an activity recreation
        // doesn't re-trigger the update prompt.
        if (!didLaunchUpdateCheck) {
            didLaunchUpdateCheck = true
            checkForUpdate(fromUser = false)
        }

        // Only create the initial home tab on a genuine fresh start.
        // Prevents losing your open tab if the activity is recreated (e.g. returning from Settings).
        if (tabs.count() == 0) {
            // If launched by a tapped/shared link, open that; otherwise home.
            val incoming = urlFromIntent(intent)
            val startUrl = incoming ?: homePage
            val first = tabs.createTab(startUrl)
            openTab(first, startUrl)
        } else {
            // Re-attach the existing active tab's view.
            tabs.activeTab?.let { openTab(it) }
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

    inner class SearchAppBridge {
        @JavascriptInterface
        fun submit(query: String) { runOnUiThread { go(query) } }
        @JavascriptInterface
        fun open(url: String) { runOnUiThread { activeWeb()?.loadUrl(url) } }
        @JavascriptInterface
        fun focusSearch() { runOnUiThread { enterSearchMode() } }
        @JavascriptInterface
        fun homeFieldVisible(visible: Boolean) {
            runOnUiThread { applyHomeCompact(!visible, animate = true) }
        }
        @JavascriptInterface
        fun shareUrl(url: String, title: String) { runOnUiThread { shareLink(url, title) } }
        @JavascriptInterface
        fun startVoice() { runOnUiThread { launchVoiceSearch() } }
        @JavascriptInterface
        fun startScan() { runOnUiThread { launchScan() } }
        @JavascriptInterface
        fun mediaState(state: String, title: String, host: String) {
            runOnUiThread { onMediaState(state, title, host) }
        }

        @JavascriptInterface
        fun retry() {
            runOnUiThread {
                val target = lastFailedUrl
                if (target != null) activeWeb()?.loadUrl(target)
                else activeWeb()?.reload()
            }
        }
        @JavascriptInterface
        fun cacheFavicon(domain: String, dataUrl: String) {
            if (domain.isBlank() || dataUrl.isBlank()) return
            getSharedPreferences("favicon_cache", Context.MODE_PRIVATE)
                .edit().putString(domain, dataUrl).apply()
        }

        @JavascriptInterface
        fun getCachedFavicon(domain: String): String {
            return getSharedPreferences("favicon_cache", Context.MODE_PRIVATE)
                .getString(domain, "") ?: ""
        }

        @JavascriptInterface
        fun getRecentSites(): String {
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
                val domain = host.removePrefix("www.")
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
            val bg = Settings.getHomeBackground(this@MainActivity)
            val accent = Settings.getHomeAccent(this@MainActivity)
            val tiles = Settings.getBool(this@MainActivity, Settings.HOME_SHOW_TILES, true)
            return "{\"background\":\"$bg\",\"accent\":\"$accent\",\"tiles\":$tiles,\"nightOwl\":$nightOwl}"
        }
        @JavascriptInterface
        fun suggest(query: String, requestId: Int) {
            Thread {
                val items = buildSuggestions(query.trim())
                runOnUiThread { pushSuggestions(requestId, items) }
            }.start()
        }
        @JavascriptInterface
        fun getFeed(requestId: Int) {
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

    private fun matchesQuery(title: String, url: String, query: String): Boolean {
        if (query.isEmpty()) return true
        val q = query.lowercase()
        return title.lowercase().contains(q) || url.lowercase().contains(q)
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

    // Second line for history/bookmark rows: the address, minus the noise.
    private fun suggestSub(url: String): String = url
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .trimEnd('/')

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
            databaseEnabled = true
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
            // Block pop-ups: disallow auto-opening windows when enabled.
            val blockPopups = Settings.getBool(
                this@MainActivity, Settings.SEC_BLOCK_POPUPS, true)
            javaScriptCanOpenWindowsAutomatically = !blockPopups
            setSupportMultipleWindows(!blockPopups)

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

        web.addJavascriptInterface(SearchAppBridge(), "SearchApp")

        web.webViewClient = object : WebViewClient() {
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
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // Location: honor the Site setting.
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                val allow = Settings.getBool(this@MainActivity, Settings.SITE_LOCATION, true)
                callback?.invoke(origin, allow, false)
            }

            // Camera & microphone: honor the Site setting.
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                val allow = Settings.getBool(this@MainActivity, Settings.SITE_CAMERA_MIC, true)
                runOnUiThread {
                    if (allow) request?.grant(request.resources) else request?.deny()
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
                // Validate the transport BEFORE creating any tab, so a malformed
                // window.open() can't leave an orphan about:blank tab in the list.
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popupTab = tabs.createTab("about:blank")
                popupTab.title = "Opening\u2026"
                val popupWeb = newWebView()
                popupTab.webView = popupWeb
                transport.webView = popupWeb
                resultMsg.sendToTarget()
                openTab(popupTab)
                return true
            }
            // When a popup finishes (window.close), fall back to the previous tab.
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
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        web.setFindListener { activeIndex, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                binding.findCount.text = if (numberOfMatches > 0)
                    "${activeIndex + 1}/$numberOfMatches" else "0/0"
            }
        }

        web.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            lastWebScroll = android.os.SystemClock.uptimeMillis()
            positionAdSlot(v as android.webkit.WebView, scrollY)
        }
        return web
    }

    private fun displayUrl(url: String?): String =
        if (url == null || url == homePage) "" else url

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

        val widths = homeRowWidths()
        homeBarRow().forEach { it.visibility = View.VISIBLE; it.translationY = 0f }
        binding.urlBarContainer.visibility = View.VISIBLE
        binding.urlBarContainer.translationY = 0f

        // Start from wherever a cancelled transition left the row, so reversing
        // mid-way continues from the current position instead of jumping.
        val from = (binding.homeBtn.layoutParams.width.toFloat() /
            widths[0].coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
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
        android.util.Log.i("AdFeed", "requesting unit=$feedAdUnit")
        com.google.android.gms.ads.AdLoader.Builder(this, feedAdUnit)
            .forNativeAd { ad ->
                if (isFinishing || isDestroyed) { ad.destroy(); return@forNativeAd }
                android.util.Log.i("AdFeed", "loaded headline=" + ad.headline +
                    " advertiser=" + ad.advertiser + " store=" + ad.store +
                    " cta=" + ad.callToAction + " icon=" + (ad.icon != null) +
                    " media=" + (ad.mediaContent != null) +
                    " video=" + (ad.mediaContent?.hasVideoContent() ?: false) +
                    " ratio=" + (ad.mediaContent?.aspectRatio ?: 0f))
                nativeAd?.destroy()
                nativeAd = ad
                bindFeedAd(ad)
            }
            .withAdListener(object : com.google.android.gms.ads.AdListener() {
                override fun onAdFailedToLoad(e: com.google.android.gms.ads.LoadAdError) {
                    // Code 3 is no-fill, which a new unit does for hours and is
                    // not a wiring fault. 0 internal, 1 invalid request (bad unit
                    // id or app id mismatch), 2 network.
                    android.util.Log.w("AdFeed", "failed code=" + e.code +
                        " msg=" + e.message + " domain=" + e.domain +
                        " cause=" + e.cause)
                    binding.adSlot.visibility = View.GONE
                }

                override fun onAdImpression() {
                    android.util.Log.i("AdFeed", "impression recorded")
                }

                override fun onAdClicked() {
                    android.util.Log.i("AdFeed", "click recorded")
                }
            })
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
    private fun positionAdSlot(web: android.webkit.WebView, scrollY: Int) {
        if (binding.adSlot.visibility != View.VISIBLE) return
        val content = (web.contentHeight * web.scale).toInt()
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
        if (visible) binding.adSlot.post { positionAdSlot(web, web.scrollY) }
    }

    // Home page only, and never over the search sheet or the tab deck.
    private fun refreshAdSlot() {
        val url = tabs.activeTab?.url
        val onHome = (url == null || url == homePage)
        val show = nativeAd != null && onHome && !searchMode && !deckVisible
        val changed = (binding.adSlot.visibility == View.VISIBLE) != show
        binding.adSlot.visibility = if (show) View.VISIBLE else View.GONE
        if (changed) binding.adSlot.post { syncAdSlotReserve() }
    }

    override fun onDestroy() {
        nativeAd?.destroy()
        nativeAd = null
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
    }

    private fun closeTabFromDeck(tab: Tab) {
        val wasActive = tab == tabs.activeTab
        tab.webView?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
            tab.webView = null
        }
        tab.thumbnail?.recycle()
        tab.thumbnail = null
        tabs.removeTab(tab)
        if (tabs.count() == 0) { closeDeck(); addNewTab(homePage) }
        else if (wasActive) tabs.tabs.lastOrNull()?.let { tabs.setActive(it) }
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
    private fun showDownloads() {
        val items = Downloads.load(this)
        val adapter = DownloadsAdapter(
            items = items,
            onOpen = { d -> openDownloadedFile(d) },
            onDelete = { d ->
                try {
                    (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager).remove(d.id)
                } catch (_: Exception) {}
                showDownloads()
            }
        )
        binding.downloadList.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.downloadList.adapter = adapter
        binding.tabList.visibility = View.GONE
        binding.historyList.visibility = View.GONE
        binding.bookmarkList.visibility = View.GONE
        if (items.isEmpty()) {
            showDeckEmpty(R.drawable.menu_history, "No downloads yet", "Files you download will show up here.")
            binding.downloadList.visibility = View.GONE
        } else {
            binding.deckEmpty.visibility = View.GONE
            binding.downloadList.visibility = View.VISIBLE
        }
        downloadsOpen = true
        historyOpen = false
        bookmarksOpen = false
    }

    /** Opens a completed download with the appropriate app; toasts if not ready. */
    private fun openDownloadedFile(d: Downloads.Item) {
        if (!d.isComplete || d.localUri == null) {
            android.widget.Toast.makeText(this,
                if (d.isRunning) "Still downloading…" else "File not available",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(d.localUri), d.mimeType ?: "*/*")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "No app to open this file",
                android.widget.Toast.LENGTH_SHORT).show()
        }
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
        try {
            val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
            request.setMimeType(mimeType)
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
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
        // Rebuilt from the parsed colour rather than passed through, so nothing
        // from settings reaches the page as script.
        val hex = String.format("#%06X", 0xFFFFFF and accent)
        activeWeb()?.evaluateJavascript(
            "window.__setAccent && window.__setAccent('$hex')", null)
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
    private fun applyArtAccent(accent: Int) {
        if (accent == owlAccent) return
        owlAccent = accent
        recolouredToAccent(R.drawable.ic_owl, accent)
            ?.let { binding.homeBtn.setImageBitmap(it) }
        recolouredToAccent(R.drawable.ic_settings, accent)
            ?.let { binding.settingsBtn.setImageBitmap(it) }
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
