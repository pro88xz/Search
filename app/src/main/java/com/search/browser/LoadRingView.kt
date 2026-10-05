package com.search.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Page loading, shown as a ring around the bottom bar's New tab button.
 *
 * It replaces the thin line that ran across the top of the page. The ring
 * sits in the pale band [NavSheetView] draws around the button and fills
 * clockwise from the top as the page loads, in the owl's purples: lavender
 * at the start of the arc deepening to the owl's darkest purple at its head.
 *
 * WebView reports progress in jumps (10, 30, 70, 100), so the arc glides to
 * each new value instead of stepping. At 100 it closes the circle and then
 * fades away. It draws nothing and takes no touches, so the button under it
 * works exactly as before.
 */
class LoadRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density

    /** The New tab button's radius; the ring runs just outside it. */
    var buttonRadius = 29f * dp
        set(v) { field = v; requestLayout(); invalidate() }

    private val stroke = 3.6f * dp

    // The owl's purples, light to deep.
    private val owlLight = Color.parseColor("#B48CFF")
    private val owlMid = Color.parseColor("#7B3AF0")
    private val owlDeep = Color.parseColor("#4A16B8")

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = Color.argb(46, 123, 58, 240)
    }

    private val box = RectF()
    private val shaderMatrix = Matrix()
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    /** What is drawn, 0..1 of the circle. */
    private var shown = 0f
    /** Where the page is, 0..1. */
    private var target = 0f
    private var loading = false

    private var sweepAnim: ValueAnimator? = null
    private var fadeAnim: ValueAnimator? = null

    init {
        alpha = 0f
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = buttonRadius + stroke / 2f + 1f * dp
        box.set(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    /**
     * The active tab's progress, as WebView reports it (0..100). Anything from
     * 1 to 99 is loading; 100 finishes the ring and fades it out; 0 hides it.
     */
    fun setProgress(percent: Int) {
        when {
            percent in 1..99 -> {
                val p = percent / 100f
                if (!loading) {
                    loading = true
                    fadeTo(1f)
                    // A new load starts from the top of the ring, not from
                    // wherever the last one finished.
                    if (shown >= 1f || p < shown) setShown(0f)
                } else if (p < shown) {
                    // A new navigation inside an unfinished one.
                    setShown(p)
                }
                glideTo(p, 420L) {}
            }
            percent >= 100 -> {
                if (!loading && visibility != VISIBLE) return
                loading = false
                glideTo(1f, 220L) { if (!loading) fadeTo(0f) }
            }
            else -> hideNow()
        }
    }

    /** Puts the ring straight into a tab's state, with no glide: for tab switches. */
    fun jumpTo(percent: Int) {
        sweepAnim?.cancel()
        if (percent in 1..99) {
            loading = true
            target = percent / 100f
            setShown(target)
            fadeAnim?.cancel()
            visibility = VISIBLE
            alpha = 1f
        } else {
            hideNow()
        }
    }

    private fun hideNow() {
        loading = false
        sweepAnim?.cancel()
        fadeAnim?.cancel()
        alpha = 0f
        visibility = INVISIBLE
        target = 0f
        setShown(0f)
    }

    private fun setShown(v: Float) {
        shown = v.coerceIn(0f, 1f)
        invalidate()
    }

    private fun glideTo(to: Float, duration: Long, end: () -> Unit) {
        target = to
        sweepAnim?.cancel()
        val from = shown
        if (to <= from) { end(); return }
        sweepAnim = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setShown(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) end()
                }
            })
            start()
        }
    }

    private fun fadeTo(to: Float) {
        fadeAnim?.cancel()
        if (to > 0f) visibility = VISIBLE
        val from = alpha
        fadeAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = if (to > 0f) 160L else 260L
            addUpdateListener { alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || to > 0f) return
                    visibility = INVISIBLE
                    setShown(0f)
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        sweepAnim?.cancel()
        fadeAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        canvas.drawCircle(cx, cy, radius, trackPaint)
        if (shown <= 0f) return
        val sweep = 360f * shown
        // The round cap reaches back past the start of the arc, so the
        // gradient starts that far back too; otherwise the cap would pick up
        // the deep end of the gradient where the light end should be.
        val cap = Math.toDegrees((stroke / 2f / radius).toDouble()).toFloat()
        val span = ((sweep + 2f * cap) / 360f).coerceIn(0.02f, 1f)
        val shader = SweepGradient(cx, cy,
            intArrayOf(owlLight, owlMid, owlDeep, owlDeep),
            floatArrayOf(0f, span * 0.55f, span, 1f))
        shaderMatrix.setRotate(-90f - cap, cx, cy)
        shader.setLocalMatrix(shaderMatrix)
        arcPaint.shader = shader
        canvas.drawArc(box, -90f, sweep, false, arcPaint)
    }
}
