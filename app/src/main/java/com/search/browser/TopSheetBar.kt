package com.search.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * The top bar's surface, so the two bars read as one piece of chrome.
 *
 * The bottom bar is a white sheet with a hairline along its edge and a soft
 * shadow. The top bar was a flat grey band - grey because it showed the
 * window background through a transparent background. This gives it the
 * same surface, the same hairline and the same shadow.
 *
 * Square, like the bottom bar: both bars meet the page with a straight edge
 * on every page. They had rounded corners for a while, which covered a
 * site's corners or left a wedge beside them; square covers nothing.
 *
 * While searching it is [flat]: in the search page's colour ([flatColor]),
 * with no shadow and no line, so the search field and the card of
 * suggestions sit on one background, as in Chrome. On the home page, while
 * its search box is pinned to the top, it fades into the page's background
 * ([blend]), so the box sits on the page as it does further down.
 *
 * The surface also runs up behind the status bar ([topInset]). Stopping below
 * it made the bar read as a white tongue laid on the page.
 *
 * Painted in onDraw, which runs before the children, so the owl, the address
 * bar and the buttons sit on top of the sheet. A view rather than a
 * background so the shadow can fall below it, over the page.
 */
class TopSheetBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dp = resources.displayMetrics.density

    /**
     * The status bar's height, from the window insets. Only the painting goes
     * up there: the children still lay out below it, so nothing sits under the
     * clock, and the root keeps its padding so every overlay stays clear of
     * the status bar exactly as before.
     */
    var topInset: Int = 0
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    private val dark = (resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** No shadow and no line, in [flatColor]: the search page's top. */
    var flat = false
        set(v) {
            if (field == v) return
            field = v
            repaint()
        }

    /** The colour while [flat]: the search page's, behind its cards. */
    var flatColor: Int = context.getColor(R.color.searchPageBg)
        set(v) { field = v; repaint() }

    /** The sheet's colour. Night Owl tints it, as it tints the bottom sheet. */
    var surfaceColor: Int = context.getColor(R.color.barSurface)
        set(v) { field = v; repaint() }

    /** The home page's background, which [blend] fades toward. */
    private val pageBackground = context.getColor(R.color.appBackground)

    /**
     * 0 is the bar as it is; 1 is the home page's background, with the line
     * and shadow gone: the bar while home's search box is pinned to the top.
     * Follows the box in and out (MainActivity.setHomeBarProgress).
     */
    var blend = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (field == c) return
            field = c
            repaint()
        }

    private val accent = Color.parseColor("#8B6BD8")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    private var lineShows = true

    init {
        // A LinearLayout with no background is told it has nothing to draw.
        setWillNotDraw(false)
        repaint()
    }

    private fun repaint() {
        // What is left of the bar's own look: none of it while flat or fully
        // blended into the home page.
        val own = if (flat) 0f else 1f - blend
        fill.color = if (flat) flatColor else mix(surfaceColor, pageBackground, blend)
        // The bottom bar's shadow, mirrored: same blur and colour, cast down.
        // Only from Android 9, where hardware drawing supports a paint shadow
        // on any shape. NavSheetView falls back to a software layer below
        // that, which costs nothing for a strip with no children - this bar
        // holds the address field, and rendering all of it in software to gain
        // a shadow on an old phone is the wrong trade. The hairline still
        // draws there, so the edge still reads.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (own <= 0f) fill.clearShadowLayer()
            else fill.setShadowLayer(14f * dp, 0f, 1f * dp,
                if (dark) Color.argb((110 * own).toInt(), 0, 0, 0)
                else Color.argb((26 * own).toInt(), 60, 48, 112))
        }
        val base = if (dark) Color.argb(18, 255, 255, 255)
            else mix(surfaceColor, accent, 0.12f)
        line.color = base
        line.alpha = (Color.alpha(base) * own).toInt()
        lineShows = own > 0f
        invalidate()
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // From the top of the status bar strip rather than the top of this
        // view, so the surface reaches the top of the screen.
        canvas.drawRect(0f, -topInset.toFloat(), w, h, fill)
        if (lineShows) canvas.drawLine(0f, h, w, h, line)
    }
}
