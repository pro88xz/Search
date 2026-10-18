package com.search.browser

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The small taps in the hand that make the chrome feel physical: a light
 * tick for a bar button, a firmer one for the owl, a confirm when something
 * takes (a refresh, a tab closed, a switch to the next tab). Through
 * View.performHapticFeedback, so the phone's own "Touch feedback" setting is
 * honoured, and Settings > Customize > Vibration on tap turns all of it off.
 */
object Haptics {

    @Volatile private var enabled = true

    /** Read on resume (MainActivity.refreshScrollPrefs), not on every tap. */
    fun refresh(c: android.content.Context) {
        enabled = Settings.getBool(c, Settings.HAPTICS, true)
    }

    /** A bar button. */
    fun tap(v: View?) = feel(v, HapticFeedbackConstants.VIRTUAL_KEY)

    /** The lightest: a detent passed, a threshold crossed while dragging. */
    fun tick(v: View?) = feel(v, HapticFeedbackConstants.CLOCK_TICK)

    /** Something took: a refresh, a tab closed, the next tab arrived. */
    fun confirm(v: View?) = feel(v,
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY)

    /** Whether the app's switch allows them, for views whose own (system) feedback it should silence too. */
    val isOn: Boolean get() = enabled

    private var owlPressAt = 0L

    /**
     * The owl, as the system's navigation keys do it: a click as the finger
     * goes down (the owl squashes with it), and a lighter tick as it lifts
     * into a tap. Several fingers or a fast double tap give one click.
     */
    fun owlPress(v: View?) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - owlPressAt < 80L) return
        owlPressAt = now
        feel(v, HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** The release of a tap on the owl; nothing before Android 8.1, where the press is enough. */
    fun owlRelease(v: View?) {
        if (Build.VERSION.SDK_INT >= 27) feel(v, HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
    }

    private fun feel(v: View?, kind: Int) {
        if (!enabled || v == null) return
        try { v.performHapticFeedback(kind) } catch (e: Exception) { /* no vibrator */ }
    }
}
