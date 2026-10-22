package com.search.browser

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SECTION = "section"
        const val SEC_SECURITY = "security"
        const val SEC_ADBLOCK = "adblock"
        const val SEC_ACCESSIBILITY = "accessibility"
        const val SEC_CUSTOMIZE = "customize"
        const val SEC_SITE = "site"
        const val SEC_DATA = "data"
        const val SEC_HEALTH = "health"
        const val SEC_MODE = "mode"
    }

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.applyNightMode(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Settings.applyAccent(this)
        setContentView(R.layout.activity_section)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.sectionRoot)) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        content = findViewById(R.id.sectionContent)
        // The same page as Settings itself: cards on the page's background,
        // 16dp in from either edge, each under a label in the accent.
        content.setPadding(dp(16), dp(4), dp(16), dp(28))
        findViewById<android.widget.ImageButton>(R.id.sectionBack)
            .setOnClickListener { finish() }

        val section = intent.getStringExtra(EXTRA_SECTION) ?: SEC_SECURITY
        val title = findViewById<TextView>(R.id.sectionTitle)

        when (section) {
            SEC_SECURITY -> { title.text = "Search Security"; buildSecurity() }
            SEC_ADBLOCK -> { title.text = "Ad blocking"; buildAdblock() }
            SEC_ACCESSIBILITY -> { title.text = "Accessibility"; buildAccessibility() }
            SEC_CUSTOMIZE -> { title.text = "Customize your Search"; buildCustomize() }
            SEC_SITE -> { title.text = "Site settings"; buildSite() }
            SEC_DATA -> { title.text = "Browsing data"; buildData() }
            SEC_HEALTH -> { title.text = "Performance & health"; buildHealth() }
            SEC_MODE -> { title.text = "Browsing experience"; buildMode() }
            else -> { title.text = "Coming soon"; addNote("This section is coming soon.") }
        }
    }

    // ---- Browsing experience: Explore, Focus, Zen ----

    /**
     * The three modes on one screen, the one in use selected. A tap applies
     * it straight away - the home page changes when it is next shown - and
     * says so; nothing else changes: tabs, bookmarks, shortcuts, history and
     * downloads stay exactly as they are.
     */
    private fun buildMode() {
        addNote("Choose how your home page looks. Your tabs, bookmarks, shortcuts, " +
            "history and downloads stay as they are in every mode.")
        val cards = ModeCards(this, BrowsingMode.get(this)) { mode ->
            if (mode == BrowsingMode.get(this)) return@ModeCards
            if (BrowsingMode.set(this, mode)) {
                HealthStats.count(this, HealthStats.MODE_CHANGED)
                toast("Browsing experience changed to " + BrowsingMode.title(mode) + ".")
            } else {
                toast("Couldn't save that. Please try again.")
            }
        }
        content.addView(cards.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        group("Zen")
        addToggle(
            "Shortcuts in Zen",
            "Show your first four shortcuts under the search box in Zen.",
            BrowsingMode.ZEN_SHORTCUTS, false
        )
        addNote("Explore loads Top Stories; Focus and Zen don't fetch news at all, " +
            "so the home page does less work and uses less data.")
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---- Security section ----

    private fun buildSecurity() {
        group("Protection")
        addToggle(
            "HTTPS-only mode",
            "Always try to connect securely and warn on insecure sites.",
            Settings.SEC_HTTPS_ONLY, true
        )
        addToggle(
            "Safe Browsing",
            "Warn about dangerous sites and downloads.",
            Settings.SEC_SAFE_BROWSING, true
        )
        addToggle(
            "Block pop-ups",
            "Stop sites from opening unwanted pop-up windows.",
            Settings.SEC_BLOCK_POPUPS, true
        )
        addToggle(
            "Block third-party cookies",
            "Prevent cross-site tracking cookies. Some checkouts, payment pages and " +
                "sign-ins need them: turn this off if one gets stuck.",
            Settings.SEC_BLOCK_3P_COOKIES, false
        )
        addToggle(
            "Confirm every download",
            "Ask before any file downloads, so nothing saves without your OK.",
            Settings.SEC_CONFIRM_DOWNLOADS, true
        )
        addToggle(
            "Warn about risky downloads",
            "A warning before app and program files (.apk, .exe and the like) and " +
                "files sent over an insecure connection, even with confirmation off.",
            Settings.SEC_RISKY_DOWNLOADS, true
        )
        addToggle(
            "Warn about look-alike sites",
            "Tells you when a site's name uses letters from another alphabet that can " +
                "pass for a familiar name - a common phishing trick.",
            Settings.SEC_LOOKALIKE, true
        )
        group("Night Owl")
        addToggle(
            "Lock Night Owl tabs when you leave",
            "Coming back to Search with Night Owl on asks for your fingerprint, face, " +
                "PIN or pattern first. Needs a screen lock on this phone.",
            Settings.NIGHT_OWL_LOCK, false
        )
        addToggle(
            "Hide Night Owl from screenshots and recent apps",
            "While Night Owl is on, screenshots are blocked and the app shows blank in " +
                "the list of recent apps.",
            Settings.NIGHT_OWL_SECURE, false
        )
        group("Privacy")
        addToggle(
            "Remove tracking from links",
            "Take tracking tags (utm_, fbclid and the like) off links you tap, copy " +
                "or share. The page is the same without them.",
            Settings.CLEAN_LINKS, true
        )
        addToggle(
            "Search suggestions",
            "Suggestions from your search engine as you type. Off, nothing you " +
                "type leaves the phone until you search for it.",
            Settings.SEARCH_SUGGESTIONS, true
        )
        // Answers about camera, microphone and location are remembered per
        // site, so without this there is no way to change your mind.
        addAction(
            "Reset site permissions",
            "Forget which sites you allowed to use the camera, microphone or " +
                "your location. Each will ask again."
        ) {
            val n = SitePermissions.count(this)
            if (n == 0) {
                android.widget.Toast.makeText(
                    this, "No site permissions saved yet",
                    android.widget.Toast.LENGTH_SHORT).show()
            } else {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    this, R.style.Theme_Search_Dialog)
                    .setTitle("Reset site permissions")
                    .setMessage("Forget all remembered answers? Sites will ask again.")
                    .setPositiveButton("Reset") { _, _ ->
                        SitePermissions.clearAll(this)
                        android.widget.Toast.makeText(
                            this, "Site permissions reset",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun buildAdblock() {
        group()
        addToggle(
            "Block ads and trackers",
            "Blocks common ad networks and trackers for faster, cleaner browsing.",
            Settings.ADBLOCK_ENABLED, false
        )
        addNote("Reload open pages after changing this for it to take full effect.")
        val week = PrivacyStats.week(this)
        val all = PrivacyStats.total(this)
        if (all > 0) {
            addNote(java.text.NumberFormat.getIntegerInstance().format(week) + " ads and trackers " +
                "blocked this week, " + java.text.NumberFormat.getIntegerInstance().format(all) +
                " in all - about " + android.text.format.Formatter.formatShortFileSize(this,
                PrivacyStats.bytesSaved(week)) + " of data saved this week.")
        }
        allowedSitesList = group("Sites where ads are allowed")
        fillAllowedSites()
        addNote("Turn blocking off for one site from the menu while you're on it: " +
            "\u201CAllow ads on this site\u201D. Tap a site here to block its ads again.")
    }

    // ---- Ad blocking: sites allowed ----

    private var allowedSitesList: LinearLayout? = null

    private fun fillAllowedSites() {
        val list = allowedSitesList ?: return
        list.removeAllViews()
        val sites = AdBlockSites.all(this).sorted()
        if (sites.isEmpty()) {
            val t = TextView(this)
            t.text = "None. Ads are blocked on every site while blocking is on."
            t.textSize = 14f
            t.setTextColor(0xFF8A8A8F.toInt())
            t.setPadding(dp(18), dp(16), dp(18), dp(16))
            list.addView(t)
            return
        }
        sites.forEach { site ->
            val t = TextView(this)
            t.text = site
            t.textSize = 16f
            t.setTextColor(resolveTextColor())
            t.setPadding(dp(18), dp(15), dp(18), dp(15))
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            t.setBackgroundResource(outValue.resourceId)
            t.setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    this, R.style.Theme_Search_Dialog)
                    .setTitle(site)
                    .setMessage("Block ads on this site again?")
                    .setPositiveButton("Block ads") { _, _ ->
                        AdBlockSites.remove(this, site)
                        fillAllowedSites()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            addInto(list, t)
        }
    }

    private fun buildAccessibility() {
        addNote("Choose how big text appears on web pages. Changes apply right away.")

        val options = listOf(
            Triple("Small", 85, "Fits more on screen \u2014 great for reading a lot at once."),
            Triple("Default", 100, "The standard, balanced size."),
            Triple("Large", 120, "Easier on the eyes \u2014 a comfy bump up."),
            Triple("Extra large", 150, "Big and bold \u2014 best for tired eyes.")
        )
        val current = Settings.getTextScale(this)
        val textColor = resolveTextColor()
        val radios = android.widget.RadioGroup(this)
        radios.setPadding(dp(10), dp(6), dp(10), dp(6))
        group("Text size").addView(radios)
        endGroup()

        var checkId = -1
        options.forEach { (label, pct, desc) ->
            // Each option is a vertical block: radio row + sample preview + description
            val block = LinearLayout(this)
            block.orientation = LinearLayout.VERTICAL
            block.setPadding(dp(8), dp(10), dp(8), dp(10))

            val rb = android.widget.RadioButton(this)
            rb.id = android.view.View.generateViewId()
            rb.text = "$label  ($pct%)"
            rb.textSize = 16f
            rb.setTextColor(textColor)
            radios.addView(rb)
            if (pct == current) checkId = rb.id
            rb.setOnClickListener { applyTextSize(label, pct) }

            // Live sample rendered at this size
            val sample = TextView(this)
            sample.text = "The quick brown owl reads the web."
            sample.textSize = 15f * (pct / 100f)
            sample.setTextColor(textColor)
            sample.setPadding(dp(34), dp(2), 0, dp(2))

            val d = TextView(this)
            d.text = desc
            d.textSize = 12f
            d.setTextColor(0xFF8A8A8F.toInt())
            d.setPadding(dp(34), 0, 0, 0)

            // tapping the sample/desc also selects
            val pick = android.view.View.OnClickListener { rb.isChecked = true; applyTextSize(label, pct) }
            sample.setOnClickListener(pick)
            d.setOnClickListener(pick)

            block.addView(sample)
            block.addView(d)
            radios.addView(block)
        }
        if (checkId != -1) radios.check(checkId)

        group("Zoom")
        addToggle(
            "Force enable zoom",
            "Pinch to zoom on every page, even where a site turns zooming off.",
            Settings.A11Y_FORCE_ZOOM, false
        )
        addNote("Takes effect as pages load.")

        siteZoomList = group("Sites with their own zoom")
        fillSiteZoom()
        addNote("Set from Menu > Zoom on a site. Tap one to return it to the text size above.")
    }

    private var siteZoomList: LinearLayout? = null

    private fun fillSiteZoom() {
        val list = siteZoomList ?: return
        list.removeAllViews()
        val sites = SiteZoom.all(this)
        if (sites.isEmpty()) {
            val t = TextView(this)
            t.text = "None yet."
            t.textSize = 14f
            t.setTextColor(0xFF8A8A8F.toInt())
            t.setPadding(dp(18), dp(16), dp(18), dp(16))
            list.addView(t)
            return
        }
        sites.forEach { (site, pct) ->
            val t = TextView(this)
            t.text = "$site  \u00b7  $pct%"
            t.textSize = 16f
            t.setTextColor(resolveTextColor())
            t.setPadding(dp(18), dp(15), dp(18), dp(15))
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            t.setBackgroundResource(outValue.resourceId)
            t.setOnClickListener {
                SiteZoom.forget(this, site)
                android.widget.Toast.makeText(this, "$site: back to your text size",
                    android.widget.Toast.LENGTH_SHORT).show()
                fillSiteZoom()
            }
            addInto(list, t)
        }
    }

    private fun applyTextSize(label: String, pct: Int) {
        Settings.setTextScale(this, pct)
        android.widget.Toast.makeText(this,
            "Text size: $label", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun buildCustomize() {
        val accents = Settings.ACCENTS.map { it.hex to it.name }
        val curAccent = Settings.getHomeAccent(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(18), dp(16), dp(18), dp(16))
        accents.forEach { (hex, name) ->
            val sw = android.view.View(this)
            val size = dp(40)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = dp(12)
            sw.layoutParams = lp
            val bg = android.graphics.drawable.GradientDrawable()
            bg.shape = android.graphics.drawable.GradientDrawable.OVAL
            bg.setColor(android.graphics.Color.parseColor(hex))
            if (hex.equals(curAccent, true)) bg.setStroke(dp(3), resolveTextColor())
            sw.background = bg
            sw.setOnClickListener {
                Settings.setHomeAccent(this, hex)
                android.widget.Toast.makeText(this, "Accent: $name", android.widget.Toast.LENGTH_SHORT).show()
                recreate()
            }
            sw.contentDescription = name
            row.addView(sw)
        }
        val scroll = android.widget.HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(row)
        group("Accent colour").addView(scroll)
        addNote("Buttons, switches, the owl and the loading ring all take it.")

        buildWallpapers()

        group("Home page")
        addToggle(
            "Show shortcut tiles",
            "Display the quick links (Google, YouTube, etc.) on the home page.",
            Settings.HOME_SHOW_TILES, true
        )
        addToggle(
            "Open home after 4 hours away",
            "Coming back after a break starts on the home page, with Jump back in to " +
                "the page you left. The page stays open in its tab.",
            Settings.HOME_AFTER_AWAY, true
        )
        addNote("Open a new tab to see your changes.")

        group("Bars")
        addToggle(
            "Hide bars while scrolling",
            "Scroll down to give the page the whole screen; scroll up to bring the bars back.",
            Settings.HIDE_BARS_ON_SCROLL, true
        )
        addToggle(
            "Pull to refresh",
            "Pull a page down from its top to reload it.",
            Settings.PULL_REFRESH, true
        )
        addToggle(
            "Swipe the bottom bar to switch tabs",
            "Slide the bar left or right to go to the next tab.",
            Settings.SWIPE_TABS, true
        )
        addToggle(
            "Page colours in the bars",
            "The bars and the search box take on each site's own colour, as Chrome's do.",
            Settings.TINT_BARS, false
        )
        addToggle(
            "Vibration on tap",
            "A light tap in the hand from the bar buttons, the owl and pull to refresh. " +
                "Follows the phone's own touch feedback setting.",
            Settings.HAPTICS, true
        )
        group("Websites")
        addToggle(
            "Download video button",
            "On a page with a video you can save, a button to download it. " +
                "Not for YouTube, whose terms do not allow it.",
            Settings.VIDEO_BUTTON, true
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            addToggle(
                "Dark websites",
                "In dark theme, sites with no dark look of their own are darkened too.",
                Settings.DARK_WEB, false
            )
            addNote("Dark websites applies to pages as they load. Sites that have their own dark look use it either way.")
        }
    }

    /**
     * Home's wallpaper: Plain (home's own background) or one of the scenes
     * in HomeWallpapers, each shown as a small portrait of itself. The one
     * in use wears a ring in the accent. Home takes up a new choice as soon
     * as the browser is back in front (applyAccentTints, __setWallpaper).
     */
    private fun buildWallpapers() {
        val current = Settings.getHomeWallpaper(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(18), dp(16), dp(6), dp(14))
        val choices = listOf<Pair<String, String>>("none" to "Plain") +
            HomeWallpapers.ALL.map { it.id to it.name }
        choices.forEach { (id, name) ->
            val selected = id == current || (id == "none" && HomeWallpapers.byId(current) == null)
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER_HORIZONTAL
            val cellLp = LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT)
            cellLp.marginEnd = dp(12)
            cell.layoutParams = cellLp

            val pic = android.widget.ImageView(this)
            pic.layoutParams = LinearLayout.LayoutParams(dp(64), dp(114))
            pic.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            val shape = android.graphics.drawable.GradientDrawable()
            shape.cornerRadius = dp(12).toFloat()
            if (id == "none") {
                // Plain: home's own grey, light or dark as the phone is.
                val night = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                shape.setColor(if (night) 0xFF0E0E10.toInt() else 0xFFF5F5F5.toInt())
                shape.setStroke(maxOf(1, dp(1) / 2), 0x33808080)
            } else {
                try {
                    assets.open("wallpapers/$id-thumb.webp").use {
                        pic.setImageBitmap(android.graphics.BitmapFactory.decodeStream(it))
                    }
                } catch (e: Exception) { /* a missing thumbnail leaves the cell blank, not the screen */ }
            }
            pic.background = shape
            pic.clipToOutline = true
            if (selected) {
                pic.foreground = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(3), Settings.accentInk(this@SectionActivity))
                }
            }
            cell.addView(pic)

            val label = TextView(this)
            label.text = name
            label.textSize = 12f
            label.maxLines = 1
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            label.gravity = Gravity.CENTER_HORIZONTAL
            label.setTextColor(if (selected) Settings.accentInk(this) else resolveTextColor())
            if (selected) label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
            label.setPadding(0, dp(6), 0, 0)
            cell.addView(label)

            cell.contentDescription = if (selected) "$name, in use" else name
            cell.isClickable = true
            cell.isFocusable = true
            cell.setOnClickListener {
                if (selected) return@setOnClickListener
                Settings.setHomeWallpaper(this, id)
                android.widget.Toast.makeText(this,
                    if (id == "none") "Home: plain background" else "Wallpaper: $name",
                    android.widget.Toast.LENGTH_SHORT).show()
                recreate()
            }
            row.addView(cell)
        }
        val scroll = android.widget.HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(row)
        group("Wallpaper").addView(scroll)
        addNote("Home wears it in Explore, Focus and Zen. Night Owl keeps its own sky.")
    }

    /** Ends the card being filled; whatever comes next starts a new one. */
    private fun addDivider() = endGroup()

    private fun buildSite() {
        group("What sites may do")
        addToggle(
            "JavaScript",
            "Let websites run scripts. Turning this off breaks many sites but boosts privacy.",
            Settings.SITE_JAVASCRIPT, true
        )
        addToggle(
            "Location access",
            "Allow sites to request your location.",
            Settings.SITE_LOCATION, true
        )
        addToggle(
            "Camera & microphone",
            "Allow sites to request camera and mic access.",
            Settings.SITE_CAMERA_MIC, true
        )
        addToggle(
            "Block autoplay",
            "Videos and sound wait until you tap the page.",
            Settings.SITE_BLOCK_AUTOPLAY, true
        )
        addToggle(
            "Data saver (block images)",
            "Skip loading images for faster browsing and less data use.",
            Settings.SITE_BLOCK_IMAGES, false
        )
        addToggle(
            "Save data on mobile data",
            "Skip images only while on mobile data. On Wi-Fi, pages load in full.",
            Settings.DATA_SAVER_MOBILE, false
        )
        addNote("Reload open pages for changes to take effect.")
        sitePermList = group("Permissions by site")
        fillSitePermissions()
        addNote("What each site may use. Tap a site to change it.")
    }

    // ---- Permissions by site ----
    //
    // The permission center: every site that has asked for the camera and
    // microphone or for location, with the answer it got, changeable here -
    // Allow, Block, or Ask every time (forget the answer) - instead of only
    // being able to reset every site at once.

    private var sitePermList: LinearLayout? = null

    private val permKinds = listOf(
        SitePermissions.CAMERA_MIC to "Camera & microphone",
        SitePermissions.LOCATION to "Location",
        SitePermissions.POPUPS to "Pop-ups"
    )

    private fun answerLabel(a: Int?): String = when (a) {
        SitePermissions.ALLOW -> "Allowed"
        SitePermissions.DENY -> "Blocked"
        else -> "Ask"
    }

    private fun fillSitePermissions() {
        val list = sitePermList ?: return
        list.removeAllViews()
        val sites = SitePermissions.bySite(this)
        if (sites.isEmpty()) {
            val t = TextView(this)
            t.text = "No site has asked yet."
            t.textSize = 14f
            t.setTextColor(0xFF8A8A8F.toInt())
            t.setPadding(dp(18), dp(16), dp(18), dp(16))
            list.addView(t)
            return
        }
        sites.forEach { (origin, answers) ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(18), dp(13), dp(18), dp(13))
            row.isClickable = true
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            row.setBackgroundResource(outValue.resourceId)
            row.setOnClickListener { editSite(origin) }

            val t = TextView(this)
            t.text = SitePermissions.hostOf(origin)
            t.textSize = 16f
            t.setTextColor(resolveTextColor())
            val d = TextView(this)
            d.text = permKinds.joinToString("   ") { (kind, name) ->
                name + ": " + answerLabel(answers[kind])
            }
            d.textSize = 13f
            d.setTextColor(0xFF8A8A8F.toInt())
            d.setPadding(0, dp(2), 0, 0)
            row.addView(t)
            row.addView(d)
            addInto(list, row)
        }
    }

    /** One site: pick a permission, then Allow, Block or Ask; or forget the site. */
    private fun editSite(origin: String) {
        val answers = SitePermissions.bySite(this)[origin] ?: emptyMap()
        val items = permKinds.map { (kind, name) -> name + ": " + answerLabel(answers[kind]) } +
            "Forget this site"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Search_Dialog)
            .setTitle(SitePermissions.hostOf(origin))
            .setItems(items.toTypedArray()) { _, which ->
                if (which >= permKinds.size) {
                    permKinds.forEach { (kind, _) -> SitePermissions.forget(this, origin, kind) }
                    fillSitePermissions()
                } else {
                    val (kind, name) = permKinds[which]
                    chooseAnswer(origin, kind, name, answers[kind])
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun chooseAnswer(origin: String, kind: String, name: String, current: Int?) {
        val choices = arrayOf("Allow", "Block", "Ask every time")
        val checked = when (current) {
            SitePermissions.ALLOW -> 0
            SitePermissions.DENY -> 1
            else -> 2
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Search_Dialog)
            .setTitle(name + " \u00B7 " + SitePermissions.hostOf(origin))
            .setSingleChoiceItems(choices, checked) { d, which ->
                when (which) {
                    0 -> SitePermissions.set(this, origin, kind, SitePermissions.ALLOW)
                    1 -> SitePermissions.set(this, origin, kind, SitePermissions.DENY)
                    else -> SitePermissions.forget(this, origin, kind)
                }
                d.dismiss()
                fillSitePermissions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Browsing data section ----
    //
    // Each kind of stored data with its size, cleared on its own, so freeing
    // space does not have to mean signing out of every site. Clearing it all
    // at once is still the last row.

    private val dataIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val sizeViews = mutableMapOf<String, TextView>()

    private fun buildData() {
        addNote("What Search keeps on this phone. Tap one to clear just that.")
        group("Stored on this phone")
        addDataRow("cache", "Cached files",
            "Copies of pages and pictures, so sites open faster. Safe to clear; " +
                "sites just load a little slower the first time.") {
            confirmClear("Clear cached files?",
                "Pages and pictures will be fetched again when you next visit.") {
                BrowsingData.clearCache(this)
            }
        }
        addDataRow("cookies", "Cookies",
            "What sites remember about you, including that you're signed in.") {
            confirmClear("Clear cookies?",
                "You'll be signed out of websites.") {
                BrowsingData.clearCookies()
            }
        }
        addDataRow("site", "Site storage",
            "Data sites save on your phone: offline copies, settings, app data.") {
            confirmClear("Clear site storage?",
                "Sites lose what they saved here, such as offline data and settings.") {
                BrowsingData.clearSiteStorage()
            }
        }
        addDataRow("history", "History",
            "The pages you've visited, and their icons.") {
            confirmClear("Clear history?",
                "Your list of visited pages will be emptied. Bookmarks are kept.") {
                BrowsingData.clearHistory(this)
            }
        }
        addDataRow("downloads", "Downloads",
            "Files you've downloaded. Open Downloads to choose which to remove.") {
            // Never deleted from here: downloads are the user's files, so this
            // opens the Downloads screen, where each one is removed by choice.
            startActivity(android.content.Intent(this, MainActivity::class.java)
                .putExtra(BrowsingData.EXTRA_OPEN, BrowsingData.OPEN_DOWNLOADS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        group()
        addAction("Delete last 15 minutes",
            "Pages from the last 15 minutes come off history and home's " +
                "shortcuts. Cookies and cached files stay.") {
            val n = BrowsingData.deleteRecent(this)
            android.widget.Toast.makeText(this,
                if (n == 0) "Nothing from the last 15 minutes"
                else "Deleted " + n + (if (n == 1) " page" else " pages") + " from the last 15 minutes",
                android.widget.Toast.LENGTH_SHORT).show()
            refreshSizes(300L)
        }
        addAction("Clear all browsing data",
            "History, cookies, cached files and site data. Bookmarks and " +
                "downloads are kept.", danger = true) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.Theme_Search_Dialog)
                .setTitle("Clear browsing data")
                .setMessage(
                    "This clears your history, cookies, cached files and site " +
                    "data. You'll be signed out of websites.\n\nBookmarks are kept."
                )
                .setPositiveButton("Clear") { _, _ ->
                    BrowsingData.clearAll(this)
                    android.widget.Toast.makeText(this, "Browsing data cleared",
                        android.widget.Toast.LENGTH_SHORT).show()
                    refreshSizes(600L)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        refreshSizes(0L)
    }

    private fun confirmClear(title: String, message: String, clear: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Search_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Clear") { _, _ ->
                clear()
                android.widget.Toast.makeText(this, "Cleared",
                    android.widget.Toast.LENGTH_SHORT).show()
                // WebView clears on its own threads; measure once it has.
                refreshSizes(600L)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Re-reads every size off the main thread, after [delayMs]. */
    private fun refreshSizes(delayMs: Long) {
        sizeViews.values.forEach { if (delayMs > 0) it.text = "\u2026" }
        content.postDelayed({
            if (isFinishing || isDestroyed || dataIo.isShutdown) return@postDelayed
            dataIo.execute {
                val sizes = try { BrowsingData.measure(this) } catch (t: Throwable) { return@execute }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    fun show(key: String, bytes: Long) {
                        sizeViews[key]?.text =
                            android.text.format.Formatter.formatShortFileSize(this, bytes)
                    }
                    show("cache", sizes.cache)
                    show("cookies", sizes.cookies)
                    show("site", sizes.siteStorage)
                    show("history", sizes.history)
                    show("downloads", sizes.downloads)
                }
            }
        }, delayMs)
    }

    override fun onDestroy() {
        dataIo.shutdown()
        super.onDestroy()
    }

    /** A tappable row with its size on the right, read in later. */
    private fun addDataRow(key: String, title: String, desc: String, onTap: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(18), dp(15), dp(18), dp(15))
        row.isClickable = true
        row.setOnClickListener { onTap() }
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        row.setBackgroundResource(outValue.resourceId)

        val textCol = LinearLayout(this)
        textCol.orientation = LinearLayout.VERTICAL
        textCol.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val t = TextView(this)
        t.text = title
        t.textSize = 16f
        t.setTextColor(resolveTextColor())

        val d = TextView(this)
        d.text = desc
        d.textSize = 13f
        d.setTextColor(0xFF8A8A8F.toInt())
        d.setPadding(0, dp(2), dp(12), 0)

        textCol.addView(t)
        textCol.addView(d)

        val size = TextView(this)
        size.text = "\u2026"
        size.textSize = 14f
        size.setTextColor(resolveTextColor())
        sizeViews[key] = size

        row.addView(textCol)
        row.addView(size)
        addRow(row)
    }

    // ---- Performance & health section ----

    /**
     * How this version is doing next to the one before it (HealthStats).
     * Counted on this phone only; Share is the one way it leaves.
     */
    private fun buildHealth() {
        addNote("How Search is running on this phone, version by version. Only " +
            "counts and timings are kept - never the sites you visit - and " +
            "nothing is sent anywhere.")
        val versions = HealthStats.versions(this)
        if (versions.isEmpty()) addNote("Nothing measured yet.")
        versions.forEachIndexed { i, v ->
            val card = group("Version " + v.name + (if (i == 0) " (this version)" else ""))
            val lines = HealthStats.lines(v)
            lines.forEachIndexed { n, line ->
                val t = TextView(this)
                t.text = line
                t.textSize = 13f
                t.setTextColor(0xFF8A8A8F.toInt())
                t.setPadding(dp(18), if (n == 0) dp(12) else dp(2), dp(18),
                    if (n == lines.size - 1) dp(12) else dp(2))
                card.addView(t)
            }
            endGroup()
        }
        // Why the home-feed ad or the app open ad did or did not show, in
        // words - for testing, so debug builds only: release builds show no
        // ad numbers at all.
        val adLines = if (BuildConfig.DEBUG) HealthStats.adLines(this) else emptyList()
        if (adLines.isNotEmpty()) {
            val card = group("Ads")
            adLines.forEachIndexed { n, line ->
                val t = TextView(this)
                t.text = line
                t.textSize = 13f
                t.setTextColor(0xFF8A8A8F.toInt())
                t.setPadding(dp(18), if (n == 0) dp(12) else dp(2), dp(18),
                    if (n == adLines.size - 1) dp(12) else dp(2))
                card.addView(t)
            }
            addNote("Code 3 (no fill) is AdMob having no ad to give: usual for a new ad unit, " +
                "or while the app is being reviewed in AdMob. It is not a fault in the app.")
        }
    }

    // ---- UI builders ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- Cards ----
    //
    // Every section is built of the cards the main Settings page uses: a
    // label in the accent, then a rounded card of rows with a hairline
    // between them, on the page's own background. Notes sit under the card
    // they explain. The sections used to be one flat list ruled across with
    // dividers, a different look from the page they were opened from.

    /** The card rows are added to now, or null before the first and after endGroup. */
    private var card: LinearLayout? = null

    /** Starts a new card, under [label] in the accent when one is given. */
    private fun group(label: String? = null): LinearLayout {
        if (label != null) {
            val t = TextView(this)
            t.text = label
            t.textSize = 13f
            t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            t.letterSpacing = 0.02f
            t.setTextColor(Settings.accentInk(this))
            t.setPadding(dp(14), dp(16), dp(14), dp(8))
            content.addView(t)
        }
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setBackgroundResource(R.drawable.settings_card_bg)
        c.elevation = dp(1).toFloat()
        c.clipToOutline = true
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = if (label == null) dp(16) else 0
        lp.bottomMargin = dp(4)
        c.layoutParams = lp
        content.addView(c)
        card = c
        return c
    }

    private fun endGroup() { card = null }

    /** A row into the current card (one is started if there is none), after a hairline. */
    private fun addRow(v: android.view.View) = addInto(card ?: group(), v)

    private fun addInto(list: LinearLayout, v: android.view.View) {
        if (list.childCount > 0) {
            val line = android.view.View(this)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                maxOf(1, (resources.displayMetrics.density * 0.75f).toInt()))
            lp.marginStart = dp(18)
            lp.marginEnd = dp(18)
            line.layoutParams = lp
            line.setBackgroundColor(0x24808080)
            list.addView(line)
        }
        list.addView(v)
    }

    private fun addToggle(title: String, desc: String, key: String, default: Boolean) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(18), dp(15), dp(14), dp(15))
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val textCol = LinearLayout(this)
        textCol.orientation = LinearLayout.VERTICAL
        textCol.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )

        val t = TextView(this)
        t.text = title
        t.textSize = 16f
        t.setTextColor(resolveTextColor())

        val d = TextView(this)
        d.text = desc
        d.textSize = 13f
        d.setTextColor(0xFF8A8A8F.toInt())
        d.setPadding(0, dp(2), 0, 0)

        textCol.addView(t)
        textCol.addView(d)

        val sw = SwitchCompat(this)
        sw.isChecked = Settings.getBool(this, key, default)
        sw.setOnCheckedChangeListener { _, checked ->
            Settings.setBool(this, key, checked)
        }
        // The whole row flips the switch, not only the switch itself.
        row.isClickable = true
        val ripple = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        row.setBackgroundResource(ripple.resourceId)
        row.setOnClickListener { sw.toggle() }

        row.addView(textCol)
        row.addView(sw)
        addRow(row)
    }

    /** A tappable row that reads like the toggles above it, minus the switch. */
    private fun addAction(title: String, desc: String, danger: Boolean = false, onTap: () -> Unit) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(15), dp(18), dp(15))
        col.isClickable = true
        col.setOnClickListener { onTap() }
        val ripple = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        col.setBackgroundResource(ripple.resourceId)

        val t = TextView(this)
        t.text = title
        t.textSize = 16f
        t.setTextColor(if (danger) getColor(R.color.dangerFg) else resolveTextColor())

        val d = TextView(this)
        d.text = desc
        d.textSize = 13f
        d.setTextColor(0xFF8A8A8F.toInt())
        d.setPadding(0, dp(2), 0, 0)

        col.addView(t)
        col.addView(d)
        addRow(col)
    }

    /** A note under the card it explains (or at the top of the page); the next row starts a new card. */
    private fun addNote(text: String) {
        endGroup()
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.setLineSpacing(0f, 1.15f)
        t.setTextColor(0xFF8A8A8F.toInt())
        t.setPadding(dp(14), dp(8), dp(14), dp(6))
        content.addView(t)
    }

    private fun resolveTextColor(): Int {
        // Robust: detect dark mode and return a guaranteed-visible color.
        val isDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (isDark) 0xFFFAFAFA.toInt() else 0xFF0E0E10.toInt()
    }
}
