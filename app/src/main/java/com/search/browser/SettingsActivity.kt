package com.search.browser

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
    }

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
                applyTheme(modes[i])
            }
            group.addView(rb)
            if (modes[i] == current) group.check(rb.id)
        }
    }

    private fun applyTheme(mode: Int) {
        when (mode) {
            Settings.THEME_LIGHT ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            Settings.THEME_DARK ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun setupClearData() {
        findViewById<TextView>(R.id.clearData).setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.Theme_Search_Dialog)
                .setTitle("Clear browsing data")
                // The row said "Clear browsing data" and the code cleared the
                // history list and nothing else - cookies, cached files and
                // site data all stayed. Anyone handing the phone on, or
                // clearing up after themselves, was told a job had been done
                // that had not been. Now it does the whole job, and says which
                // job it is doing before it starts.
                .setMessage(
                    "This clears your history, cookies, cached files and site " +
                    "data. You'll be signed out of websites.\n\nBookmarks are kept."
                )
                .setPositiveButton("Clear") { _, _ ->
                    clearBrowsingData()
                    Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Everything a browser accumulates about where you have been. */
    private fun clearBrowsingData() {
        History.clear(this)
        getSharedPreferences("favicon_cache", MODE_PRIVATE).edit().clear().apply()
        try {
            val cookies = android.webkit.CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
        } catch (e: Exception) { /* nothing stored */ }
        try {
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) { /* nothing stored */ }
        try {
            // clearFormData is deprecated and does nothing: WebView stopped
            // storing form data in API 26. Saved http-auth credentials are
            // still real, so that one stays.
            android.webkit.WebViewDatabase.getInstance(this)
                .clearHttpAuthUsernamePassword()
        } catch (e: Exception) { /* nothing stored */ }
        try {
            // clearCache empties the cache shared by the whole app, so a
            // throwaway instance reaches it from here, where no page is open.
            val w = android.webkit.WebView(this)
            w.clearCache(true)
            w.destroy()
        } catch (e: Exception) { /* WebView unavailable */ }
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
        findViewById<android.widget.TextView>(R.id.rowPrivacy)
            .setOnClickListener { openPage("file:///android_asset/privacy.html") }
        findViewById<android.widget.TextView>(R.id.rowTerms)
            .setOnClickListener { openPage("file:///android_asset/terms.html") }
    }

    private fun openPage(url: String) {
        val i = android.content.Intent(this, LegalActivity::class.java)
        i.putExtra("url", url)
        startActivity(i)
    }
}