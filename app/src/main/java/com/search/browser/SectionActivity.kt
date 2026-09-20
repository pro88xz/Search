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
    }

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_section)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.sectionRoot)) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        content = findViewById(R.id.sectionContent)
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
            else -> { title.text = "Coming soon"; addNote("This section is coming soon.") }
        }
    }

    // ---- Security section ----

    private fun buildSecurity() {
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
            "Prevent cross-site tracking cookies.",
            Settings.SEC_BLOCK_3P_COOKIES, false
        )
        addToggle(
            "Confirm every download",
            "Ask before any file downloads, so nothing saves without your OK.",
            Settings.SEC_CONFIRM_DOWNLOADS, true
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
                android.app.AlertDialog.Builder(this)
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
        addToggle(
            "Block ads and trackers",
            "Blocks common ad networks and trackers for faster, cleaner browsing.",
            Settings.ADBLOCK_ENABLED, false
        )
        addNote("Reload open pages after changing this for it to take full effect.")
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
        val group = android.widget.RadioGroup(this)
        group.setPadding(dp(16), 0, dp(16), dp(12))
        content.addView(group)

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
            group.addView(rb)
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
            group.addView(block)
        }
        if (checkId != -1) group.check(checkId)
    }

    private fun applyTextSize(label: String, pct: Int) {
        Settings.setTextScale(this, pct)
        android.widget.Toast.makeText(this,
            "Text size: $label", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun buildCustomize() {
        addNote("Accent color")
        val accents = listOf(
            "#2B6CF0" to "Blue", "#8B6BD8" to "Purple", "#2FB170" to "Green",
            "#F5A623" to "Amber", "#E5556E" to "Rose", "#0E0E10" to "Ink"
        )
        val curAccent = Settings.getHomeAccent(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(20), dp(4), dp(20), dp(12))
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
            row.addView(sw)
        }
        content.addView(row)

        addDivider()

        addToggle(
            "Show shortcut tiles",
            "Display the quick links (Google, YouTube, etc.) on the home page.",
            Settings.HOME_SHOW_TILES, true
        )
        addNote("Open a new tab to see your changes.")
    }

    private fun addDivider() {
        val div = TextView(this)
        div.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        div.setBackgroundColor(0x22808080)
        (div.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = dp(8); bottomMargin = dp(8) }
        content.addView(div)
    }

    private fun buildSite() {
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
            "Stop videos and audio from playing automatically.",
            Settings.SITE_BLOCK_AUTOPLAY, true
        )
        addToggle(
            "Data saver (block images)",
            "Skip loading images for faster browsing and less data use.",
            Settings.SITE_BLOCK_IMAGES, false
        )
        addNote("Reload open pages for changes to take effect.")
    }

    // ---- UI builders ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addToggle(title: String, desc: String, key: String, default: Boolean) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20), dp(14), dp(20), dp(14))
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

        row.addView(textCol)
        row.addView(sw)
        content.addView(row)

        // divider
        val div = TextView(this)
        div.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        )
        div.setBackgroundColor(0x22808080)
        content.addView(div)
    }

    /** A tappable row that reads like the toggles above it, minus the switch. */
    private fun addAction(title: String, desc: String, onTap: () -> Unit) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(14), dp(20), dp(14))
        col.isClickable = true
        col.setOnClickListener { onTap() }

        val t = TextView(this)
        t.text = title
        t.textSize = 16f
        t.setTextColor(resolveTextColor())

        val d = TextView(this)
        d.text = desc
        d.textSize = 13f
        d.setTextColor(0xFF8A8A8F.toInt())
        d.setPadding(0, dp(2), 0, 0)

        col.addView(t)
        col.addView(d)
        content.addView(col)

        val div = TextView(this)
        div.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        div.setBackgroundColor(0x22808080)
        content.addView(div)
    }

    private fun addNote(text: String) {
        val t = TextView(this)
        t.text = text
        t.textSize = 15f
        t.setTextColor(0xFF8A8A8F.toInt())
        t.setPadding(dp(20), dp(16), dp(20), dp(16))
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
