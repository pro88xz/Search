package com.search.browser

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.View

/**
 * The colours painted outside a bar's two rounded corners, left and right.
 *
 * On the home page they are the page background. On a website they are the
 * website's own colour at that edge, read off the screen (MainActivity,
 * sampleBarCorners), so the page seems to run on behind the curve instead
 * of a grey wedge showing beside it - Amazon's navy beside its header, white
 * beside a white page. A new colour fades in over a moment rather than
 * jumping, since it changes as the page scrolls under the bars.
 */
class CornerColors(private val view: View, initial: Int) {

    var left = initial
        private set
    var right = initial
        private set

    private var goalLeft = initial
    private var goalRight = initial
    private var fade: ValueAnimator? = null
    private val argb = ArgbEvaluator()

    fun set(newLeft: Int, newRight: Int, animate: Boolean) {
        if (newLeft == goalLeft && newRight == goalRight) return
        fade?.cancel()
        goalLeft = newLeft
        goalRight = newRight
        if (!animate || !view.isAttachedToWindow) {
            left = newLeft
            right = newRight
            view.invalidate()
            return
        }
        val fromLeft = left
        val fromRight = right
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160L
            addUpdateListener {
                val t = it.animatedFraction
                left = argb.evaluate(t, fromLeft, newLeft) as Int
                right = argb.evaluate(t, fromRight, newRight) as Int
                view.invalidate()
            }
            start()
        }
    }
}
