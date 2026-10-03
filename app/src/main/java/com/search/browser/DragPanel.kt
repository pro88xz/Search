package com.search.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * A panel that can be dragged down to dismiss it - the menu.
 *
 * A downward drag is taken from the rows only once it is clearly vertical and
 * [canDrag] agrees (the menu's list is scrolled to its top, so the drag is not
 * a scroll of the list). Every move is reported as it happens, and the release
 * comes with the finger's speed, so the caller can follow the finger exactly
 * and let a spring decide: away, or back into place.
 */
class DragPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var canDrag: () -> Boolean = { true }
    /** How far the finger has moved down since the drag began, in px (may go negative). */
    var onDrag: (Float) -> Unit = {}
    /** Released: distance down, and speed down in px per second. */
    var onRelease: (Float, Float) -> Unit = { _, _ -> }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    // In screen coordinates: the panel itself moves under the finger, so its
    // own coordinates would understate the speed.
    private fun track(ev: MotionEvent) {
        val t = tracker ?: return
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(ev.rawX, ev.rawY)
        t.addMovement(copy)
        copy.recycle()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                track(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!dragging && dy > slop && dy > abs(dx) * 1.2f && canDrag()) {
                    dragging = true
                    startY = ev.rawY
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
        if (!dragging) return super.onTouchEvent(ev)
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onDrag(ev.rawY - startY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                var vy = 0f
                val t = tracker
                if (t != null && ev.actionMasked == MotionEvent.ACTION_UP) {
                    t.computeCurrentVelocity(1000, maxFling)
                    vy = t.yVelocity
                }
                tracker?.recycle()
                tracker = null
                dragging = false
                onRelease(ev.rawY - startY, vy)
            }
        }
        return true
    }
}
