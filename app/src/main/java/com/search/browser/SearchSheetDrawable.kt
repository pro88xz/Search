package com.search.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * The search sheet's background: the grey page of suggestions, with the top
 * bar's curve carried on over it.
 *
 * [TopSheetBar] curves its bottom edge inward, the two deep ends falling 28dp
 * below the bar over the page. The search sheet starts right at the bar's
 * bottom and is drawn above the whole browser layer, so in search mode it
 * covered those ends and the curve went flat. Lifting the bar above the sheet
 * means moving it out of its layout, which is what broke it before.
 *
 * So the sheet draws the curve itself instead, exactly where the bar's lies
 * underneath it: the same 28dp ends in the bar's colour, the same hairline
 * and the same soft shadow. The bar stays where it is, untouched.
 *
 * The line and shadow follow TopSheetBar's own values; a change to one wants
 * the same change here.
 */
class SearchSheetDrawable(context: Context) : Drawable() {

    private val dp = context.resources.displayMetrics.density

    /** TopSheetBar's corner. */
    private val corner = 28f * dp

    private val dark = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    private val accent = Color.parseColor("#8B6BD8")

    private val page = Paint().apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.searchPageBg)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }

    /** The top bar's colour, which the curve's ends are painted in. */
    var surfaceColor: Int = context.getColor(R.color.barSurface)
        set(v) {
            if (field == v) return
            field = v
            repaint()
        }

    private val sheet = Path()
    private val edge = Path()
    private val box = RectF()
    private var alphaValue = 255

    init {
        repaint()
    }

    private fun repaint() {
        fill.color = surfaceColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            fill.setShadowLayer(14f * dp, 0f, 1f * dp,
                if (dark) Color.argb(110, 0, 0, 0) else Color.argb(26, 60, 48, 112))
        }
        line.color = if (dark) Color.argb(18, 255, 255, 255)
            else mix(surfaceColor, accent, 0.12f)
        applyAlpha()
        invalidateSelf()
    }

    private fun applyAlpha() {
        page.alpha = alphaValue
        fill.alpha = alphaValue
        if (alphaValue < 255) {
            line.alpha = (Color.alpha(line.color) * alphaValue) / 255
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild(bounds)
    }

    private fun rebuild(b: Rect) {
        sheet.reset()
        edge.reset()
        if (b.isEmpty) return
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        // TopSheetBar's edge, with its bottom at this sheet's top.
        edge.moveTo(l, t + corner)
        box.set(l, t, l + 2f * corner, t + 2f * corner)
        edge.arcTo(box, 180f, 90f, false)
        edge.lineTo(r - corner, t)
        box.set(r - 2f * corner, t, r, t + 2f * corner)
        edge.arcTo(box, 270f, 90f, false)
        // Closed well above the top, which is clipped away, so only the ends
        // and the shadow under the edge are drawn.
        sheet.addPath(edge)
        sheet.lineTo(r, t - 3f * corner)
        sheet.lineTo(l, t - 3f * corner)
        sheet.close()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val save = canvas.save()
        canvas.clipRect(b)
        canvas.drawRect(b, page)
        if (!sheet.isEmpty) {
            canvas.drawPath(sheet, fill)
            canvas.drawPath(edge, line)
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        if (alphaValue == alpha) return
        alphaValue = alpha
        repaint()
    }

    override fun getAlpha(): Int = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {
        page.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        line.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
