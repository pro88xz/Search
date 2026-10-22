package com.search.browser

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

// MainActivity, continued: Menu groups, Menu motion, Long-press context menu, Page tools: share, translate, reader view, home screen, Find in page, Owl tap (context-aware).
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- Long-press context menu ----------

internal fun MainActivity.setupLongPress(web: WebView) {
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

internal fun MainActivity.showTargetMenu(image: String?, link: String?, linkText: String? = null) {
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
        labels.add("Copy link address"); acts.add { copyText("Link", shareable(link)) }
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
internal fun MainActivity.addBackgroundTab(url: String) {
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

internal fun MainActivity.copyText(label: String, text: String) {
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

// ---------- Menu groups ----------

internal fun MainActivity.setupMenuGroups() {
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
internal fun MainActivity.showMenuGroup(strip: View?, animate: Boolean) {
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

internal fun MainActivity.endMenuLayer() {
    binding.menuPanel.setLayerType(View.LAYER_TYPE_NONE, null)
}

// ---------- Menu motion ----------

internal fun MainActivity.placeMenu(p: Float) {
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

internal fun MainActivity.dragMenu(y: Float) {
    binding.menuPanel.translationY = y
    val h = binding.menuPanel.height.toFloat().coerceAtLeast(1f)
    // Only the dimming lightens; the panel itself stays solid.
    binding.menuScrim.background?.alpha = (255f * (1f - y / (h * 1.3f)).coerceIn(0f, 1f)).toInt()
}

internal fun MainActivity.setupMenuDrag() {
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

internal fun MainActivity.closeMenu() {
    menuDragSpring.cancel()
    // An open still waiting for its first frame must not start after this.
    menuOpenSeq++
    menuSpring.animate(menuSpring.value, 0f) { closeMenuNow() }
}

/** Instant close (no fade) for when a menu item's action follows immediately,
 *  so the panel doesn't linger see-through over the page during the action. */
internal fun MainActivity.closeMenuNow() {
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

internal fun MainActivity.addNewTab(loadUrl: String = homePage) {
    val tab = tabs.createTab(loadUrl)
    openTab(tab, loadUrl)
    if (loadUrl == homePage) expectPageStart(tab)
}

// ---------- Find in page ----------

internal fun MainActivity.openFindBar() {
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

internal fun MainActivity.closeFindBar() {
    findActive = false
    binding.findBar.visibility = View.GONE
    activeWeb()?.clearMatches()
    hideKeyboard()
}

// ---------- Page tools: share, translate, reader view, home screen ----------

/** The website on screen, or null on home, an error page or a page of the app's own. */
internal fun MainActivity.webPageUrl(): String? {
    val url = activeWeb()?.url ?: tabs.activeTab?.url ?: return null
    if (url == homePage || isErrorPage(url)) return null
    return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}

internal fun MainActivity.needAPage() = toast("Open a page first")

/** The text size for [url]: its own (Menu > Zoom) or the one from Settings. */
internal fun MainActivity.zoomFor(url: String?): Int =
    SiteZoom.get(this, url) ?: Settings.getTextScale(this)

/**
 * Menu > Zoom: a minus, a slider and a plus in 10% steps, the page
 * reflowing under it as it moves, and the size kept for the site. At the
 * bottom of the screen with a light scrim, so the page can be read while
 * it is set. Reset returns the site to the size in Settings.
 */
internal fun MainActivity.showZoom() {
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
        setTextColor(androidx.core.content.ContextCompat.getColor(this@showZoom, R.color.navIcon))
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
internal fun MainActivity.showPageInfo() {
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
internal fun MainActivity.showTabHistory(anchor: View, back: Boolean): Boolean {
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
internal fun MainActivity.translatePage() {
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
internal fun MainActivity.toggleReaderView() {
    val web = activeWeb() ?: return
    if (webPageUrl() == null) { needAPage(); return }
    web.evaluateJavascript(ReaderView.JS) { result ->
        when (result?.trim('"')) {
            "on" -> { showBars(); schedulePageTint(400L) }
            "none" -> toast("Reader view needs an article - there isn't one on this page")
        }
    }
}

// ---------- Owl tap (context-aware) ----------

internal fun MainActivity.openGames() {
    addNewTab(gamesUrl)
}

internal fun MainActivity.showGamesWelcome() {
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

internal fun MainActivity.showOwlCloseDialog() {
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
