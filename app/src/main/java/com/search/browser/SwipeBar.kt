package com.search.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The bottom bar's frame, which also takes a sideways swipe across the bar:
 * the gesture that moves between tabs.
 *
 * Its buttons keep their taps. Only a drag that is clearly sideways - past
 * touch slop, and well more across than up or down - is taken from them, at
 * which point the button under the finger gets a cancel instead of a click.
 * From then on every move is reported as it happens, so the page can follow
 * the finger exactly, and the release comes with the finger's speed.
 */
class SwipeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Asked as a swipe begins; false refuses it and leaves the touch to the buttons. */
    var onSwipeStart: () -> Boolean = { false }
    /** How far the finger has moved across since the swipe began, in px. */
    var onSwipe: (Float) -> Unit = {}
    /** The finger let go: distance across, and speed across in px per second. */
    var onSwipeEnd: (Float, Float) -> Unit = { _, _ -> }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var swiping = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!swiping && abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.5f && onSwipeStart()) {
                    swiping = true
                    // Measured from here, so the page does not jump by the slop.
                    startX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle()
                tracker = null
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swiping) return false
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onSwipe(ev.x - startX)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = tracker
                var vx = 0f
                if (t != null && ev.actionMasked == MotionEvent.ACTION_UP) {
                    t.computeCurrentVelocity(1000, maxFling)
                    vx = t.xVelocity
                }
                tracker?.recycle()
                tracker = null
                swiping = false
                onSwipeEnd(ev.x - startX, vx)
            }
        }
        return true
    }
}
