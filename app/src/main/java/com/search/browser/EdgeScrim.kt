package com.search.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * The status bar strip, or the navigation bar strip, painted in the colours of
 * the bar beside it while the bars are slid away (MainActivity.hideBars).
 *
 * The top bar paints the status bar strip itself, and the bottom bar the strip
 * under Android's buttons. Sliding either away used to take its strip with it,
 * leaving the window's plain background - a white flash under the clock and
 * the buttons each time the bars hid or came back. These hold the strip in the
 * bar's colours instead, and the bars slide under them, as Chrome's toolbar
 * slides under its status bar.
 */
class EdgeScrim(context: Context) : View(context) {

    /** Left, middle and right colours (a bar's paintColours), drawn as a gentle gradient. */
    var colours: IntArray = intArrayOf(0, 0, 0)
        set(v) {
            if (field.contentEquals(v)) return
            field = v.copyOf()
            shaderFor = -1
            invalidate()
        }

    private val paint = Paint()
    private var shaderFor = -1

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Above the bars, with no shadow of its own.
        outlineProvider = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        if (w <= 0 || height <= 0) return
        val c = colours
        if (c.size >= 3 && (c[0] != c[1] || c[1] != c[2])) {
            if (shaderFor != w) {
                paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, c,
                    floatArrayOf(1f / 6f, 0.5f, 5f / 6f), Shader.TileMode.CLAMP)
                shaderFor = w
            }
        } else {
            paint.shader = null
            paint.color = c.getOrElse(0) { 0 }
        }
        canvas.drawRect(0f, 0f, w.toFloat(), height.toFloat(), paint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderFor = -1
    }
}
