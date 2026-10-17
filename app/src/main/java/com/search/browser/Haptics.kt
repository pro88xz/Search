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

    /** The owl: a touch firmer than a bar button. */
    fun owl(v: View?) = feel(v,
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.KEYBOARD_TAP)

    private fun feel(v: View?, kind: Int) {
        if (!enabled || v == null) return
        try { v.performHapticFeedback(kind) } catch (e: Exception) { /* no vibrator */ }
    }
}
