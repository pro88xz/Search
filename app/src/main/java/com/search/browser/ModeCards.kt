package com.search.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The three browsing modes as cards to choose from - each with a miniature
 * of its home page, its name, a line about it and a check - one under the
 * other, a single choice. Used by the owl's introduction and by Settings >
 * Browsing experience, so the choice looks the same in both places.
 *
 * Selection is shown three ways, never by colour alone: the accent border,
 * a filled check, and the word "Selected"; TalkBack hears a radio button
 * that is checked or not.
 */
class ModeCards(
    private val context: Context,
    initial: String,
    private val onPick: (String) -> Unit
) {
    private val d = context.resources.displayMetrics.density
    private val accent = try { Color.parseColor(Settings.getHomeAccent(context)) } catch (e: Exception) { 0xFF8B6BD8.toInt() }
    private val night = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    // Ink accent on a dark card would vanish; it turns light there.
    private val mark = if (night && Color.luminance(accent) < 0.08f) 0xFFF4F3F7.toInt() else accent
    private val surface = context.getColor(R.color.feedCard)
    private val fg = context.getColor(R.color.feedFg)
    private val muted = context.getColor(R.color.feedMuted)
    private val hair = context.getColor(R.color.feedHair)

    private var selected = if (initial in BrowsingMode.ALL) initial else BrowsingMode.EXPLORE
    private val cards = LinkedHashMap<String, Card>()

    val view: LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private inner class Card(val mode: String) {
        val root = LinearLayout(context)
        val badge = TextView(context)
        val check = View(context)
    }

    init {
        BrowsingMode.ALL.forEachIndexed { i, mode ->
            val card = build(mode)
            cards[mode] = card
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.topMargin = dp(12)
            view.addView(card.root, lp)
        }
        paint()
    }

    /** The card for [mode], to animate in (the introduction staggers them). */
    fun cardView(mode: String): View? = cards[mode]?.root

    fun select(mode: String) {
        if (mode !in BrowsingMode.ALL || mode == selected) return
        selected = mode
        paint()
    }

    private fun build(mode: String): Card {
        val c = Card(mode)
        val root = c.root
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(dp(14), dp(14), dp(16), dp(14))
        root.minimumHeight = dp(132)
        root.isClickable = true
        root.isFocusable = true
        val ripple = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        root.foreground = context.getDrawable(ripple.resourceId)

        val preview = Preview(context, mode)
        root.addView(preview, LinearLayout.LayoutParams(dp(64), dp(104)))

        val text = LinearLayout(context)
        text.orientation = LinearLayout.VERTICAL
        val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tlp.marginStart = dp(16)
        tlp.marginEnd = dp(12)
        root.addView(text, tlp)

        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(context)
        title.text = BrowsingMode.title(mode)
        title.textSize = 18f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.setTextColor(fg)
        head.addView(title)
        c.badge.text = "Selected"
        c.badge.textSize = 11.5f
        c.badge.setTypeface(c.badge.typeface, android.graphics.Typeface.BOLD)
        c.badge.setPadding(dp(8), dp(2), dp(8), dp(2))
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        blp.marginStart = dp(8)
        head.addView(c.badge, blp)
        text.addView(head)

        val tag = TextView(context)
        tag.text = BrowsingMode.tagline(mode)
        tag.textSize = 14.5f
        tag.setTextColor(fg)
        tag.setPadding(0, dp(4), 0, 0)
        text.addView(tag)

        val det = TextView(context)
        det.text = BrowsingMode.detail(mode)
        det.textSize = 13f
        det.setTextColor(muted)
        det.setLineSpacing(0f, 1.15f)
        det.setPadding(0, dp(4), 0, 0)
        text.addView(det)

        root.addView(c.check, LinearLayout.LayoutParams(dp(24), dp(24)))

        root.contentDescription = BrowsingMode.title(mode) + ". " + BrowsingMode.tagline(mode) +
            " " + BrowsingMode.detail(mode)
        root.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.RadioButton::class.java.name
                info.isCheckable = true
                info.isChecked = selected == mode
            }
        }
        root.setOnClickListener {
            Haptics.tick(it)
            val changed = selected != mode
            select(mode)
            if (changed) it.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED)
            onPick(mode)
        }
        return c
    }

    private fun paint() {
        cards.values.forEach { c ->
            val on = c.mode == selected
            c.root.background = GradientDrawable().apply {
                cornerRadius = 20f * d
                setColor(surface)
                if (on) setStroke(dp(2), mark) else setStroke(dp(1), hair)
            }
            c.badge.visibility = if (on) View.VISIBLE else View.GONE
            c.badge.setTextColor(if (Color.luminance(mark) > 0.6f) 0xFF16151B.toInt() else Color.WHITE)
            c.badge.background = GradientDrawable().apply {
                cornerRadius = 99f * d
                setColor(mark)
            }
            c.check.background = CheckDrawable(on)
            c.root.isSelected = on
        }
    }

    /** A radio circle: a ring when not chosen, a filled circle with a tick when chosen. */
    private inner class CheckDrawable(private val on: Boolean) : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = b.width() / 2f
            val cx = b.exactCenterX(); val cy = b.exactCenterY()
            if (on) {
                p.style = Paint.Style.FILL; p.color = mark
                canvas.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d
                p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                p.color = if (Color.luminance(mark) > 0.6f) 0xFF16151B.toInt() else Color.WHITE
                val path = android.graphics.Path()
                path.moveTo(cx - r * 0.42f, cy + r * 0.02f)
                path.lineTo(cx - r * 0.1f, cy + r * 0.34f)
                path.lineTo(cx + r * 0.46f, cy - r * 0.3f)
                canvas.drawPath(path, p)
            } else {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = muted
                p.alpha = 140
                canvas.drawCircle(cx, cy, r - p.strokeWidth, p)
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * A miniature of the mode's home page, drawn: the wordmark, the search
     * box, shortcuts and a story card for Explore; the box, one row of
     * shortcuts and a small card for Focus; the box alone, larger, for Zen.
     */
    private inner class Preview(ctx: Context, private val mode: String) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val pageBg = if (night) 0xFF0E0E10.toInt() else 0xFFF5F5F5.toInt()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        private fun box(l: Float, t: Float, rr: Float, b: Float, rad: Float, color: Int, stroke: Boolean = false) {
            r.set(l, t, rr, b)
            p.style = Paint.Style.FILL; p.color = color
            canvas?.drawRoundRect(r, rad, rad, p)
            if (stroke) {
                p.style = Paint.Style.STROKE; p.strokeWidth = d * 0.8f; p.color = hair
                canvas?.drawRoundRect(r, rad, rad, p)
            }
        }
        private var canvas: Canvas? = null

        private fun tint(c: Int, a: Float): Int =
            Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

        override fun onDraw(c: Canvas) {
            canvas = c
            val w = width.toFloat(); val h = height.toFloat()
            // The phone.
            box(d, d, w - d, h - d, 12f * d, pageBg, stroke = true)
            val in0 = 7f * d
            val cw = w - 2 * in0
            fun pill(y: Float, hh: Float, frac: Float) {
                val pw = cw * frac
                box((w - pw) / 2, y, (w + pw) / 2, y + hh, hh / 2, surface, stroke = true)
            }
            fun word(y: Float, frac: Float, hh: Float) {
                val pw = cw * frac
                box((w - pw) / 2, y, (w + pw) / 2, y + hh, hh / 2, tint(mark, 0.85f))
            }
            fun tiles(y: Float, n: Int) {
                val s = 7f * d
                val gap = (cw - n * s) / (n + 1)
                for (i in 0 until n) {
                    val x = in0 + gap + i * (s + gap)
                    box(x, y, x + s, y + s, 2.2f * d, tint(mark, 0.32f))
                }
            }
            when (mode) {
                BrowsingMode.FOCUS -> {
                    word(h * 0.24f, 0.42f, 4f * d)
                    pill(h * 0.24f + 9f * d, 9f * d, 0.84f)
                    tiles(h * 0.24f + 24f * d, 4)
                    // The compact Jump back in card.
                    box(in0 + 2 * d, h * 0.24f + 38f * d, w - in0 - 2 * d, h * 0.24f + 49f * d, 3.5f * d, surface, stroke = true)
                    box(in0 + 5 * d, h * 0.24f + 41f * d, in0 + 10 * d, h * 0.24f + 46f * d, 1.5f * d, tint(mark, 0.4f))
                    box(in0 + 13 * d, h * 0.24f + 42.5f * d, w - in0 - 9 * d, h * 0.24f + 44.5f * d, d, tint(muted, 0.45f))
                }
                BrowsingMode.ZEN -> {
                    word(h * 0.36f, 0.5f, 5f * d)
                    pill(h * 0.36f + 11f * d, 12f * d, 0.92f)
                }
                else -> {
                    word(8f * d, 0.42f, 4f * d)
                    pill(16f * d, 9f * d, 0.84f)
                    tiles(30f * d, 4)
                    tiles(41f * d, 4)
                    // A story card: its picture, its headline.
                    val top = 54f * d
                    box(in0, top, w - in0, h - in0, 4f * d, surface, stroke = true)
                    r.set(in0, top, w - in0, top + (h - in0 - top) * 0.55f)
                    p.style = Paint.Style.FILL
                    p.shader = android.graphics.LinearGradient(r.left, r.top, r.right, r.bottom,
                        tint(mark, 0.55f), tint(mark, 0.2f), android.graphics.Shader.TileMode.CLAMP)
                    c.save()
                    val clip = android.graphics.Path()
                    clip.addRoundRect(RectF(in0, top, w - in0, h - in0), floatArrayOf(4f * d, 4f * d, 4f * d, 4f * d, 0f, 0f, 0f, 0f), android.graphics.Path.Direction.CW)
                    c.clipPath(clip)
                    c.drawRect(r, p)
                    c.restore()
                    p.shader = null
                    val ly = r.bottom + 4f * d
                    box(in0 + 4 * d, ly, w - in0 - 6 * d, ly + 2.2f * d, d, tint(fg, 0.5f))
                    box(in0 + 4 * d, ly + 5 * d, w - in0 - 18 * d, ly + 7.2f * d, d, tint(fg, 0.3f))
                }
            }
            canvas = null
        }
    }

    private fun dp(v: Int) = (v * d).toInt()
}
