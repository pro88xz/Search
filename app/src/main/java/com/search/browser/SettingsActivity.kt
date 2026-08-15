package com.search.browser

import android.app.AlertDialog
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
        Settings.ENGINES.keys.forEach { name ->
            val rb = RadioButton(this)
            rb.id = android.view.View.generateViewId()
            rb.text = name
            rb.textSize = 16f
            rb.setPadding(8, 20, 8, 20)
            rb.setOnClickListener {
                Settings.setEngine(this, name)
                Toast.makeText(this, "Search engine: $name", Toast.LENGTH_SHORT).show()
            }
            group.addView(rb)
            if (name == current) group.check(rb.id)
        }
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
            AlertDialog.Builder(this)
                .setTitle("Clear browsing data")
                .setMessage("This clears your history. Continue?")
                .setPositiveButton("Clear") { _, _ ->
                    History.clear(this)
                    Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
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