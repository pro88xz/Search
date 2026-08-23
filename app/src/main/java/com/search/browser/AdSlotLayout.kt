package com.search.browser

/**
 * Holder for the feed ad, which floats over the page rather than sitting in it.
 *
 * Two problems come with that. A plain overlay eats every gesture starting on
 * the card, so the page will not scroll there - handled by mirroring each event
 * onto the page as well. And the card's children never learn that a scroll has
 * begun: nothing intercepts above them, and a vertical swipe inside a 180dp
 * media view never leaves its bounds, so the asset view completes its click on
 * finger-up even though the page scrolled. Handled by cancelling the children
 * explicitly once travel passes touch slop.
 *
 * [blocked] covers the remaining case, the tap that halts a fling. An
 * accidental ad click is invalid traffic, and enough of it costs the account,
 * not just the revenue - so every ambiguous gesture resolves to "not a click".
 */
class AdSlotLayout @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : android.widget.FrameLayout(context, attrs) {

    var blocked: () -> Boolean = { false }
    var page: () -> android.view.View? = { null }

    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var takeGesture = false
    private var cancelled = false
    private var downX = 0f
    private var downY = 0f

    private fun mirror(ev: android.view.MotionEvent) {
        val p = page() ?: return
        val here = IntArray(2)
        val there = IntArray(2)
        getLocationOnScreen(here)
        p.getLocationOnScreen(there)
        val copy = android.view.MotionEvent.obtain(ev)
        copy.offsetLocation((here[0] - there[0]).toFloat(), (here[1] - there[1]).toFloat())
        p.dispatchTouchEvent(copy)
        copy.recycle()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            // Latched once: a gesture must not change owner part-way through.
            takeGesture = !blocked()
            cancelled = false
            downX = ev.x
            downY = ev.y
        }
        if (!takeGesture) return false

        mirror(ev)

        if (!cancelled && ev.actionMasked == android.view.MotionEvent.ACTION_MOVE &&
            (kotlin.math.abs(ev.x - downX) > slop ||
                kotlin.math.abs(ev.y - downY) > slop)
        ) {
            cancelled = true
            val c = android.view.MotionEvent.obtain(ev)
            c.action = android.view.MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(c)
            c.recycle()
        }

        // Once cancelled the children are out of the gesture; keep consuming so
        // nothing reaches them again, while the page carries on scrolling.
        if (cancelled) return true
        return super.dispatchTouchEvent(ev)
    }
}
