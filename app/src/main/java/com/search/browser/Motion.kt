package com.search.browser

import android.view.MotionEvent
import android.view.View

/**
 * Press feedback for the chrome's buttons: a button sinks a little under the
 * finger (scale 0.9, 90ms, eased in) and springs back past its size and
 * settles when let go (an overshoot of 1.6, 320ms), the way a physical key
 * gives. The touch is only watched, never taken, so taps, long presses and
 * the ripple all work as before. Nothing moves with the phone's animations
 * off.
 */
object Motion {

    private val pressIn = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)
    private val release = android.view.animation.OvershootInterpolator(1.6f)

    /** [onPress]: told when the finger comes down (true) and leaves or is taken (false). */
    fun pressable(v: View, depth: Float = 0.9f, onPress: ((Boolean) -> Unit)? = null) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onPress?.invoke(true)
                    if (animationsOn()) {
                        view.animate().cancel()
                        view.animate().scaleX(depth).scaleY(depth)
                            .setDuration(90L).setInterpolator(pressIn).start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onPress?.invoke(false)
                    view.animate().cancel()
                    if (animationsOn()) {
                        view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(320L).setInterpolator(release).start()
                    } else { view.scaleX = 1f; view.scaleY = 1f }
                }
            }
            false
        }
    }

    fun animationsOn(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            android.animation.ValueAnimator.areAnimatorsEnabled()
        else true
}
