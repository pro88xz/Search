package com.search.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * The top bar's surface, so the two bars read as one piece of chrome.
 *
 * The bottom bar is a white sheet with a 28dp edge, a hairline tracing it and
 * a soft shadow. The top bar was a flat grey band with square ends - grey
 * because it showed the window background through a transparent background.
 * This gives it the same surface and the same hairline.
 *
 * Its bottom corners are rounded, mirroring the bottom bar's top ones, on
 * every page. They used to curve inward instead, the two deep ends falling
 * 28dp below the bar over the top of the page - which cut into every website:
 * Amazon's logo lost its first letter, and a site's header looked broken at
 * both edges. Now the page starts below the bar and nothing of it is covered.
 * The bit outside each curve is painted ([corners]): on a website in the
 * website's own colour at that edge, so the page seems to run on behind the
 * curve; on the home page and in search in the page background, the colour
 * both have. Never left to whatever is behind the bar, which Night Owl tints
 * the same as the bar.
 *
 * While searching it is [flat]: no curve, no shadow, no line, so the bar,
 * the search field and the suggestions under it read as one surface.
 *
 * The surface also runs up behind the status bar ([topInset]). Stopping below
 * it made the bar read as a white tongue laid on the page.
 *
 * Why a view and not a rounded drawable: the hairline has to trace the curve,
 * and a background cannot - it is a rectangle drawn under everything, which is
 * also why Night Owl sets [surfaceColor] here instead of a background colour.
 *
 * Painted in onDraw, which runs before the children, so the owl, the address
 * bar and the buttons sit on top of the sheet.
 */
class TopSheetBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dp = resources.displayMetrics.density

    /** Matches the radius the bottom sheet rounds its top corners by. */
    private val corner = 28f * dp

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
            rebuild()
        }

    private val dark = (resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Square, with no shadow and no line: the search page's top. */
    var flat = false
        set(v) {
            if (field == v) return
            field = v
            rebuild()
            repaint()
        }

    /** The sheet's colour. Night Owl tints it, as it tints the bottom sheet. */
    var surfaceColor: Int = context.getColor(R.color.barSurface)
        set(v) { field = v; repaint() }

    private val accent = Color.parseColor("#8B6BD8")

    /** The page background: the corners' colour when no website is showing. */
    val pageBackground = context.getColor(R.color.appBackground)

    /** What is painted outside each rounded corner (see the class notes). */
    val corners = CornerColors(this, pageBackground)
    private val cornerPaint = Paint()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }

    private val sheet = Path()
    private val edge = Path()
    private val box = RectF()

    init {
        // A LinearLayout with no background is told it has nothing to draw.
        setWillNotDraw(false)
        repaint()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        sheet.reset()
        edge.reset()
        if (w <= 0f || h <= 0f) return
        // Closed over the top of the status bar strip rather than the top of
        // this view, so the surface reaches the top of the screen.
        val top = -topInset.toFloat()
        if (flat) {
            sheet.addRect(0f, top, w, h, Path.Direction.CW)
            invalidate()
            return
        }
        // Round the left corner, along the bottom, round the right corner:
        // all inside this view, so none of it lies over the page.
        edge.moveTo(0f, h - corner)
        box.set(0f, h - 2f * corner, 2f * corner, h)
        edge.arcTo(box, 180f, -90f, false)
        edge.lineTo(w - corner, h)
        box.set(w - 2f * corner, h - 2f * corner, w, h)
        edge.arcTo(box, 90f, -90f, false)
        sheet.addPath(edge)
        sheet.lineTo(w, top)
        sheet.lineTo(0f, top)
        sheet.close()
        invalidate()
    }

    private fun repaint() {
        fill.color = surfaceColor
        // The bottom bar's shadow, mirrored: same blur and colour, cast down.
        // Only from Android 9, where hardware drawing supports a paint shadow
        // on any shape. NavSheetView falls back to a software layer below
        // that, which costs nothing for a strip with no children - this bar
        // holds the address field, and rendering all of it in software to gain
        // a shadow on an old phone is the wrong trade. The hairline still
        // draws there, so the edge still reads.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (flat) fill.clearShadowLayer()
            else fill.setShadowLayer(14f * dp, 0f, 1f * dp,
                if (dark) Color.argb(110, 0, 0, 0) else Color.argb(26, 60, 48, 112))
        }
        line.color = if (dark) Color.argb(18, 255, 255, 255)
            else mix(surfaceColor, accent, 0.12f)
        invalidate()
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sheet.isEmpty) return
        if (flat) {
            canvas.drawPath(sheet, fill)
            return
        }
        // The corners first, so the sheet's edge and shadow fall over them.
        val w = width.toFloat()
        val h = height.toFloat()
        cornerPaint.color = corners.left
        canvas.drawRect(0f, h - corner, corner, h, cornerPaint)
        cornerPaint.color = corners.right
        canvas.drawRect(w - corner, h - corner, w, h, cornerPaint)
        canvas.drawPath(sheet, fill)
        canvas.drawPath(edge, line)
    }
}
