package com.search.browser

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * The first-run introduction: the Search owl arrives and says hello, and
 * the three browsing modes are offered side by side to pick from at once.
 *
 * Nothing waits for the animation: the cards and the button can be used
 * from the first frame, and with the phone's animations off everything is
 * simply there. "Skip", Back and the button all end it - Skip and Back
 * keep Explore - and it is not shown again (BrowsingMode.markIntroDone).
 * Shown once by MainActivity on a fresh install, after the splash and the
 * consent message, and never over a link the browser was opened for.
 */
class ModeIntroActivity : AppCompatActivity() {

    private var picked = BrowsingMode.EXPLORE
    private lateinit var go: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.applyNightMode(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Settings.applyAccent(this)
        picked = savedInstanceState?.getString("picked")?.takeIf { it in BrowsingMode.ALL }
            ?: BrowsingMode.get(this)
        HealthStats.count(this, HealthStats.MODE_INTRO_VIEWED)

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val fg = getColor(R.color.feedFg)
        val muted = getColor(R.color.feedMuted)
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val page = if (night) 0xFF0E0E10.toInt() else 0xFFF5F5F5.toInt()
        val moving = Motion.animationsOn()

        val root = FrameLayout(this)
        root.setBackgroundColor(page)
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(20), dp(8), dp(20), dp(24))
        scroll.addView(col, FrameLayout.LayoutParams(-1, -2))

        // Skip, at the top right: Explore stays, and the browser opens.
        val skipRow = FrameLayout(this)
        val skip = TextView(this)
        skip.text = "Skip"
        skip.textSize = 15f
        skip.setTextColor(muted)
        skip.gravity = Gravity.CENTER
        skip.minWidth = dp(64)
        skip.minHeight = dp(48)
        skip.setPadding(dp(12), 0, dp(12), 0)
        val rip = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rip, true)
        skip.setBackgroundResource(rip.resourceId)
        skip.setOnClickListener { finishWith(null) }
        skipRow.addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.END))
        col.addView(skipRow, LinearLayout.LayoutParams(-1, -2))

        // The owl itself - the bar's own owl view, large - which says hello.
        val owl = OwlButtonView(this)
        val olp = LinearLayout.LayoutParams(dp(112), dp(112))
        olp.topMargin = dp(4)
        col.addView(owl, olp)
        owl.requestGreet(OwlButtonView.Hello.COLD)

        val hi = TextView(this)
        hi.text = "Hi! Welcome to Search."
        hi.textSize = 26f
        hi.setTypeface(hi.typeface, android.graphics.Typeface.BOLD)
        hi.setTextColor(fg)
        hi.gravity = Gravity.CENTER
        val hlp = LinearLayout.LayoutParams(-2, -2)
        hlp.topMargin = dp(20)
        col.addView(hi, hlp)

        val choose = TextView(this)
        choose.text = "Choose your browsing experience."
        choose.textSize = 18f
        choose.setTextColor(fg)
        choose.gravity = Gravity.CENTER
        val clp = LinearLayout.LayoutParams(-2, -2)
        clp.topMargin = dp(6)
        col.addView(choose, clp)

        val sub = TextView(this)
        sub.text = "Explore more, stay focused, or keep things ultra-simple. " +
            "You can change your mode anytime in Settings."
        sub.textSize = 14f
        sub.setTextColor(muted)
        sub.gravity = Gravity.CENTER
        sub.setLineSpacing(0f, 1.2f)
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.topMargin = dp(8)
        slp.marginStart = dp(8); slp.marginEnd = dp(8)
        col.addView(sub, slp)

        val cards = ModeCards(this, picked) { mode ->
            picked = mode
            go.text = "Use " + BrowsingMode.title(mode)
        }
        val mlp = LinearLayout.LayoutParams(-1, -2)
        mlp.topMargin = dp(24)
        col.addView(cards.view, mlp)

        val spacer = View(this)
        col.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))

        go = TextView(this)
        go.text = "Use " + BrowsingMode.title(picked)
        go.textSize = 16f
        go.setTypeface(go.typeface, android.graphics.Typeface.BOLD)
        go.gravity = Gravity.CENTER
        go.minHeight = dp(54)
        var accent = try { android.graphics.Color.parseColor(Settings.getHomeAccent(this)) }
            catch (e: Exception) { 0xFF8B6BD8.toInt() }
        if (night && android.graphics.Color.luminance(accent) < 0.08f) accent = 0xFFF4F3F7.toInt()
        go.setTextColor(if (android.graphics.Color.luminance(accent) > 0.6f) 0xFF16151B.toInt()
            else android.graphics.Color.WHITE)
        go.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 27f * d
            setColor(accent)
        }
        go.foreground = getDrawable(android.util.TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId)
        go.setOnClickListener { finishWith(picked) }
        Motion.pressable(go, 0.97f)
        val glp = LinearLayout.LayoutParams(-1, -2)
        glp.topMargin = dp(20)
        col.addView(go, glp)

        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Arrival: the owl comes up with a little bounce (0-1.2 s), the
        // greeting follows, the cards slide in - all of it usable at once.
        if (moving && savedInstanceState == null) {
            owl.alpha = 0f; owl.translationY = 36f * d; owl.scaleX = 0.7f; owl.scaleY = 0.7f
            owl.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(700L).setStartDelay(150L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
            listOf(hi, choose, sub).forEachIndexed { i, v ->
                v.alpha = 0f; v.translationY = 12f * d
                v.animate().alpha(1f).translationY(0f).setDuration(420L)
                    .setStartDelay(650L + i * 90L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
            BrowsingMode.ALL.forEachIndexed { i, m ->
                cards.cardView(m)?.let { v ->
                    v.alpha = 0f; v.translationY = 18f * d
                    v.animate().alpha(1f).translationY(0f).setDuration(460L)
                        .setStartDelay(900L + i * 80L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishWith(null) }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("picked", picked)
    }

    /** [mode] chosen, or null for Skip and Back (Explore, or whatever was already set, stays). */
    private fun finishWith(mode: String?) {
        if (mode != null) {
            if (BrowsingMode.set(this, mode)) {
                HealthStats.count(this, HealthStats.MODE_SELECTED)
                android.widget.Toast.makeText(this,
                    "Browsing experience: " + BrowsingMode.title(mode),
                    android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this,
                    "Couldn't save that choice. You can pick it again in Settings.",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        BrowsingMode.markIntroDone(this)
        finish()
        if (Motion.animationsOn()) {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
