package com.search.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The bottom bar's surface. A sheet with rounded top corners whose top edge
 * rises in an arc around the New tab button, so the button sits inside the bar
 * as one shape rather than on top of it.
 *
 * The arc is a circle around the button, a pale ring wide, and it meets the
 * straight edge through a wide concave curve either side, so the edge flows up
 * and over the button with no corner anywhere. Measured off the design: with
 * the button D across, the ring is 0.11D, the button's centre is 0.36D below
 * the straight edge, and the blend curves have a radius of about D.
 *
 * Drawn here rather than as a drawable because the shadow has to follow that
 * outline, and an elevation shadow can only follow a shape with a dip in it
 * from Android 11. The shadow is a paint shadow instead, which hardware
 * drawing supports on any shape from Android 9; before that the view draws in
 * software, which for a strip this size costs nothing that shows.
 */
class NavSheetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density

    /** From the top of this view to the straight part of the sheet's top edge. */
    var edgeTop = 30f * dp
        set(v) { field = v; rebuild() }

    /**
     * Whether the bit outside each rounded corner is painted in the page
     * background. Over a website the page stops at the sheet's straight edge
     * (MainActivity.applyBarOverlap), so nothing of it is behind the corners,
     * and they show the page background as the top bar's do. Over the home
     * page, which runs on under the corners, they are left open onto it.
     *
     * The corners are rounded either way: the bar is one shape on every page.
     */
    var fillCorners = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The page background, for [fillCorners]. */
    private val backdrop = Paint().apply { color = context.getColor(R.color.appBackground) }

    /**
     * The navigation bar's height. The sheet paints on down behind it, so the
     * bar and the strip under Android's buttons are one surface. That strip
     * showed the app's background with Android's own shade over it - in dark
     * mode a second, different dark under the bar. Only the painting goes
     * there; this view and the buttons stay above the navigation bar.
     */
    var bottomInset = 0
        set(v) { if (field != v) { field = v; rebuild() } }

    /** The New tab button: its radius, and how far its centre sits below the edge. */
    var buttonRadius = 29f * dp
        set(v) { field = v; rebuild() }
    var buttonDepth = 21f * dp
        set(v) { field = v; rebuild() }

    private val ring = 6.5f * dp
    private val corner = 28f * dp
    private val blend = 55f * dp

    private val dark = (resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** The sheet's colour. Night Owl tints it. */
    var surfaceColor: Int = context.getColor(R.color.barSurface)
        set(v) { field = v; repaint() }

    /** The accent, which the ring around the button carries a little of. */
    var accent: Int = Color.parseColor("#8B6BD8")
        set(v) { field = v; repaint() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }

    private val sheet = Path()
    private val edge = Path()
    private val box = RectF()
    private var cx = 0f
    private var cy = 0f
    private var bump = 0f

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null)
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
        cx = w / 2f
        cy = edgeTop + buttonDepth
        bump = buttonRadius + ring
        // The blend circles sit on the straight edge, either side, and touch
        // the bump circle: their centres are blend above the edge and
        // bump + blend from the bump's centre.
        val reach = bump + blend
        val drop = buttonDepth + blend
        val dx = kotlin.math.sqrt((reach * reach - drop * drop).coerceAtLeast(0f))
        // Where each blend meets the bump, as an angle on the blend circle
        // (screen angles: 0 right, 90 down).
        val meet = Math.toDegrees(kotlin.math.atan2(drop, dx).toDouble()).toFloat()

        val top = edgeTop
        // Left side up to the corner, then along to the first blend.
        edge.moveTo(0f, top + corner)
        box.set(0f, top, 2f * corner, top + 2f * corner)
        edge.arcTo(box, 180f, 90f, false)
        edge.lineTo(cx - dx, top)
        // Up the left blend, over the button, down the right blend.
        box.set(cx - dx - blend, top - 2f * blend, cx - dx + blend, top)
        edge.arcTo(box, 90f, meet - 90f, false)
        box.set(cx - bump, cy - bump, cx + bump, cy + bump)
        edge.arcTo(box, 180f + meet, 180f - 2f * meet, false)
        box.set(cx + dx - blend, top - 2f * blend, cx + dx + blend, top)
        edge.arcTo(box, 180f - meet, meet - 90f, false)
        // Along to the right corner and down.
        edge.lineTo(w - corner, top)
        box.set(w - 2f * corner, top, w, top + 2f * corner)
        edge.arcTo(box, 270f, 90f, false)

        sheet.addPath(edge)
        sheet.lineTo(w, h + bottomInset)
        sheet.lineTo(0f, h + bottomInset)
        sheet.close()
        invalidate()
    }

    private fun repaint() {
        fill.color = surfaceColor
        // A soft shadow, lifted slightly, so the sheet and its arc float over
        // the page as one piece.
        fill.setShadowLayer(14f * dp, 0f, -1f * dp,
            if (dark) Color.argb(110, 0, 0, 0) else Color.argb(26, 60, 48, 112))
        ringPaint.color = mix(surfaceColor, accent, if (dark) 0.16f else 0.07f)
        line.color = if (dark) Color.argb(18, 255, 255, 255) else mix(surfaceColor, accent, 0.12f)
        invalidate()
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    override fun onDraw(canvas: Canvas) {
        if (sheet.isEmpty) return
        if (fillCorners) {
            // First, so the sheet's edge and shadow fall over them.
            val w = width.toFloat()
            canvas.drawRect(0f, edgeTop, corner, edgeTop + corner, backdrop)
            canvas.drawRect(w - corner, edgeTop, w, edgeTop + corner, backdrop)
        }
        canvas.drawPath(sheet, fill)
        canvas.drawCircle(cx, cy, bump, ringPaint)
        // Last, so the hairline also traces the ring where the edge runs over it.
        canvas.drawPath(edge, line)
    }

    // The arc covers the bottom of the page, so a tap on it must not fall
    // through to the page below; the rest of this view is open to the page.
    // Nothing here is a click, so there is no performClick to call.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        val inArc = kotlin.math.hypot(event.x - cx, event.y - cy) <= bump
        return inArc || event.y >= edgeTop || super.onTouchEvent(event)
    }
}
