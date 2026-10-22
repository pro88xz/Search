package com.search.browser

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.applyNightMode(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Settings.applyAccent(this)
        setContentView(R.layout.activity_settings)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.settingsRoot)) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupEngines()
        setupTheme()
        setupClearData()
        setupAbout()

        findViewById<android.widget.ImageButton>(R.id.settingsBack)
            .setOnClickListener { finish() }

        setupSectionRows()
        setupDefaultBrowser()
        setupWidgetRow()
        paintAccent(findViewById(R.id.settingsRoot), Settings.accentInk(this))
    }

    /**
     * The icons and the section labels in the accent the user picked, as on
     * every other screen. They were a fixed purple whatever the choice. The
     * red Clear browsing data row keeps its red.
     */
    private fun paintAccent(v: android.view.View, ink: Int) {
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) paintAccent(v.getChildAt(i), ink)
            return
        }
        if (v !is TextView || v.id == R.id.clearData) return
        val tint = android.content.res.ColorStateList.valueOf(ink)
        if (v is RadioButton) { v.buttonTintList = tint; return }
        if (v.currentTextColor == getColor(R.color.settingsIconTint)) v.setTextColor(ink)
        // The leading icon only: the chevron at the end stays grey.
        v.compoundDrawablesRelative[0]?.let { icon ->
            icon.mutate().setTintList(tint)
            v.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Customize with a new accent: this screen takes it too.
        if (shownAccent != null && shownAccent != Settings.getHomeAccent(this)) recreate()
        shownAccent = Settings.getHomeAccent(this)
        // Gone once Search is the default; back if another browser took it.
        findViewById<android.view.View>(R.id.defaultCard).visibility =
            if (DefaultBrowser.isDefault(this)) android.view.View.GONE else android.view.View.VISIBLE
        // The mode in use, under the row's name.
        val row = findViewById<android.widget.TextView>(R.id.rowMode)
        val sub = BrowsingMode.title(BrowsingMode.get(this))
        val text = android.text.SpannableStringBuilder("Browsing experience\n").append(sub)
        text.setSpan(android.text.style.RelativeSizeSpan(0.82f), text.length - sub.length, text.length, 0)
        text.setSpan(android.text.style.ForegroundColorSpan(0xFF8A8A8F.toInt()), text.length - sub.length, text.length, 0)
        row.text = text
        row.contentDescription = "Browsing experience, " + sub
    }

    private val defaultLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (DefaultBrowser.isDefault(this)) toast("Search is now your default browser")
    }

    private fun setupWidgetRow() {
        val supported = android.os.Build.VERSION.SDK_INT >= 26 && try {
            getSystemService(android.appwidget.AppWidgetManager::class.java)?.isRequestPinAppWidgetSupported == true
        } catch (e: Exception) { false }
        findViewById<android.view.View>(R.id.widgetCard).visibility =
            if (supported) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<TextView>(R.id.rowWidget).setOnClickListener {
            if (!SearchWidget.requestPin(this)) {
                toast("Long-press your home screen, then Widgets > Search")
            }
        }
    }

    private fun setupDefaultBrowser() {
        findViewById<TextView>(R.id.rowDefault).setOnClickListener {
            val ask = DefaultBrowser.requestIntent(this)
            try {
                if (ask != null) defaultLauncher.launch(ask) else startActivity(DefaultBrowser.settingsIntent())
            } catch (e: Exception) {
                try { startActivity(DefaultBrowser.settingsIntent()) } catch (e2: Exception) {
                    toast("Pick Search under Settings > Apps > Default apps > Browser")
                }
            }
        }
    }
    private var shownAccent: String? = null

    private fun setupEngines() {
        val group = findViewById<RadioGroup>(R.id.engineGroup)
        val current = Settings.getEngineName(this)
        val iconPx = (22 * resources.displayMetrics.density).toInt()
        Settings.ENGINES.keys.forEach { name ->
            val rb = RadioButton(this)
            rb.id = android.view.View.generateViewId()
            rb.text = name
            rb.textSize = 16f
            rb.setPadding(8, 20, 8, 20)
            rb.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
            rb.setOnClickListener {
                Settings.setEngine(this, name)
                Toast.makeText(this, "Search engine: $name", Toast.LENGTH_SHORT).show()
            }
            group.addView(rb)
            if (name == current) group.check(rb.id)
            loadEngineIcon(rb, name, iconPx)
        }
    }

    /** Loads an engine's favicon (crisp, requested at 2x) onto its radio button
     *  as a leading icon. Fails silently: on any error the button stays text-only. */
    private fun loadEngineIcon(rb: RadioButton, name: String, sizePx: Int) {
        val domain = Settings.ENGINE_DOMAINS[name] ?: return
        Thread {
            try {
                val iconBase = "https://" + "icons.duckduckgo.com" + "/ip3/"
                val url = java.net.URL(iconBase + domain + ".ico")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 6000; conn.readTimeout = 6000
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                // Read the dimensions without allocating, pick a sample size,
                // then decode straight down to roughly the size drawn. A favicon
                // can arrive many times larger than the row it lands in.
                val bounds = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (sample < 32 &&
                    bounds.outWidth / (sample * 2) >= sizePx &&
                    bounds.outHeight / (sample * 2) >= sizePx
                ) sample *= 2
                val bmp = android.graphics.BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                if (bmp != null) {
                    val d = android.graphics.drawable.BitmapDrawable(resources, bmp)
                    d.setBounds(0, 0, sizePx, sizePx)
                    runOnUiThread {
                        rb.setCompoundDrawables(d, null, null, null)
                    }
                }
            } catch (_: Exception) { /* keep text-only */ }
        }.start()
    }

    private fun setupTheme() {
        val group = findViewById<RadioGroup>(R.id.themeGroup)
        val labels = listOf("Follow system", "Light", "Dark")
        val modes = listOf(Settings.THEME_SYSTEM, Settings.THEME_LIGHT, Settings.THEME_DARK)
        val current = Settings.getTheme(this)
        labels.forEachIndexed { i, label ->
            val rb = RadioButton(this)
            rb.id = android.view.View.generateViewId()
            rb.text = label
            rb.textSize = 16f
            rb.setPadding(8, 20, 8, 20)
            rb.setOnClickListener {
                Settings.setTheme(this, modes[i])
                // AppCompat recreates the open screens in the new theme.
                Settings.applyNightMode(this)
            }
            group.addView(rb)
            if (modes[i] == current) group.check(rb.id)
        }
    }

    /**
     * Opens Browsing data, where each kind of stored data is shown with its
     * size and cleared on its own; clearing everything at once is still there,
     * at the bottom (BrowsingData.clearAll, the same job this row used to do).
     */
    private fun setupClearData() {
        findViewById<TextView>(R.id.clearData).setOnClickListener {
            openSection(SectionActivity.SEC_DATA)
        }
        findViewById<TextView>(R.id.rowHealth).setOnClickListener {
            openSection(SectionActivity.SEC_HEALTH)
        }
    }

    private fun setupAbout() {
        val about = findViewById<TextView>(R.id.aboutText)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "1.0" }
        about.text = "Search Browser\nVersion $version"
        findViewById<TextView>(R.id.rowCheckUpdate).setOnClickListener { checkForUpdate() }
        findViewById<TextView>(R.id.rowRate).setOnClickListener { openPlayListing() }
    }

    private val updateLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { /* user accepted or dismissed the Play update UI */ }

    private fun checkForUpdate() {
        val mgr = com.google.android.play.core.appupdate.AppUpdateManagerFactory.create(this)
        mgr.appUpdateInfo
            .addOnSuccessListener { info ->
                val available = info.updateAvailability() ==
                    com.google.android.play.core.install.model.UpdateAvailability.UPDATE_AVAILABLE
                val allowed = info.isUpdateTypeAllowed(
                    com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE)
                if (available && allowed) {
                    try {
                        mgr.startUpdateFlowForResult(
                            info,
                            updateLauncher,
                            com.google.android.play.core.appupdate.AppUpdateOptions.newBuilder(
                                com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE
                            ).build()
                        )
                    } catch (e: Exception) {
                        toast("Couldn't start update")
                    }
                } else {
                    toast("You're on the latest version")
                }
            }
            .addOnFailureListener { toast("Couldn't check for updates") }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun openPlayListing() {
        val pkg = packageName
        try {
            startActivity(android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=$pkg"))
                .setPackage("com.android.vending"))
        } catch (e: Exception) {
            // Play Store app not available — fall back to the web listing.
            try {
                startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            } catch (e2: Exception) {
                toast("Couldn't open the Play Store")
            }
        }
    }

    private fun openSection(section: String) {
        val i = android.content.Intent(this, SectionActivity::class.java)
        i.putExtra(SectionActivity.EXTRA_SECTION, section)
        startActivity(i)
    }

    private fun setupSectionRows() {
        findViewById<android.widget.TextView>(R.id.rowSecurity)
            .setOnClickListener { openSection(SectionActivity.SEC_SECURITY) }
        findViewById<android.widget.TextView>(R.id.rowAdblock)
            .setOnClickListener { openSection(SectionActivity.SEC_ADBLOCK) }
        findViewById<android.widget.TextView>(R.id.rowSiteSettings)
            .setOnClickListener { openSection(SectionActivity.SEC_SITE) }
        findViewById<android.widget.TextView>(R.id.rowAccessibility)
            .setOnClickListener { openSection(SectionActivity.SEC_ACCESSIBILITY) }
        findViewById<android.widget.TextView>(R.id.rowCustomize)
            .setOnClickListener { openSection(SectionActivity.SEC_CUSTOMIZE) }
        findViewById<android.widget.TextView>(R.id.rowMode)
            .setOnClickListener { openSection(SectionActivity.SEC_MODE) }
        findViewById<android.widget.TextView>(R.id.rowPrivacy)
            .setOnClickListener {
                // The hosted policy is the real document; the bundled page is
                // kept as the offline copy behind it rather than deleted.
                openPage("https://mebs.app/privacy/search", "file:///android_asset/privacy.html")
            }
        findViewById<android.widget.TextView>(R.id.rowTerms)
            .setOnClickListener { openPage("file:///android_asset/terms.html") }
        findViewById<android.widget.TextView>(R.id.rowLicenses)
            .setOnClickListener { openPage("file:///android_asset/licenses.html") }

        // Google's form to change or withdraw ad consent, or to opt out of the
        // sale or sharing of personal information. Offered only to users UMP
        // says need it, and not to supporters, who are shown no ads at all.
        val adChoices = findViewById<android.widget.TextView>(R.id.rowAdChoices)
        val offer = AdConsent.privacyChoicesRequired(this) &&
            !Settings.getBool(this, Settings.IS_SUPPORTER, false)
        adChoices.visibility = if (offer) android.view.View.VISIBLE else android.view.View.GONE
        adChoices.setOnClickListener {
            AdConsent.showPrivacyChoices(this) { error ->
                if (error != null) toast("Couldn't open ad privacy choices. Check your connection and try again.")
            }
        }
    }

    private fun openPage(url: String, fallback: String? = null) {
        val i = android.content.Intent(this, LegalActivity::class.java)
        i.putExtra("url", url)
        fallback?.let { i.putExtra("fallback", it) }
        startActivity(i)
    }
}