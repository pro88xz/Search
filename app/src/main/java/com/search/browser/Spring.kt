package com.search.browser

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A damped spring, run frame by frame on the main thread.
 *
 * Every gesture in the browser hands over to one of these when the finger
 * lets go: the movement continues from exactly where the finger left it,
 * carrying the finger's speed, and comes to rest with a little give instead of
 * playing a fixed curve over a fixed time. A flick therefore travels further
 * and faster than a slow drag, as it would with a real object.
 *
 * Stiffness and damping ratio mean what they do in Android's SpringForce
 * (unit mass): 1 settles without overshoot, lower lets it swing past and come
 * back. [precision] is the distance from the target, in the caller's units,
 * that counts as arrived.
 */
class Spring(
    private val stiffness: Float,
    private val dampingRatio: Float,
    private val precision: Float,
    private val onUpdate: (Float) -> Unit
) {
    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f
        private set
    val isRunning: Boolean get() = running

    private var running = false
    private var lastFrame = 0L
    private var onEnd: (() -> Unit)? = null
    private val frame = Choreographer.FrameCallback { step(it) }

    /**
     * Runs from [from] to [to], starting at [startVelocity] (units per second).
     * [end] is called once, on arrival - not if the spring is cancelled or
     * sent somewhere else first.
     */
    fun animate(from: Float, to: Float, startVelocity: Float = 0f, end: (() -> Unit)? = null) {
        value = from
        velocity = startVelocity
        target = to
        onEnd = end
        if (!running) {
            running = true
            lastFrame = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    /** Stops where it is. Its end callback is dropped. */
    fun cancel() {
        onEnd = null
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    private fun step(now: Long) {
        if (!running) return
        if (lastFrame == 0L) {
            // The first frame only sets the clock; there is no interval yet.
            lastFrame = now
            onUpdate(value)
            Choreographer.getInstance().postFrameCallback(frame)
            return
        }
        // A frame that came late is not allowed to throw the spring across
        // the screen: at most 50ms is simulated, in 4ms steps, which keeps a
        // stiff spring stable even on a dropped frame.
        var dt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrame = now
        val damping = 2f * dampingRatio * sqrt(stiffness)
        while (dt > 0f) {
            val h = if (dt < STEP) dt else STEP
            val accel = -stiffness * (value - target) - damping * velocity
            velocity += accel * h
            value += velocity * h
            dt -= h
        }
        if (abs(value - target) < precision && abs(velocity) < precision * 10f) {
            value = target
            velocity = 0f
            running = false
            onUpdate(value)
            val end = onEnd
            onEnd = null
            end?.invoke()
            return
        }
        onUpdate(value)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private companion object {
        const val STEP = 0.004f
    }
}
