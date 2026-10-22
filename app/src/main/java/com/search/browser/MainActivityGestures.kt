package com.search.browser

import android.view.View

// MainActivity, continued: Pull to refresh.
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- Pull to refresh ----------

internal fun MainActivity.pullBy(px: Int) {
    if (!pullRefreshPref || pullRefreshing || searchMode || deckVisible) return
    val url = tabs.activeTab?.url
    if (url == null || url == homePage) return
    pullSpring.cancel()
    pullRaw += px
    placePull(rubber(pullRaw))
}

// 1:1 at the start, approaching pullReach and never passing it.
internal fun MainActivity.rubber(raw: Float): Float = pullReach * (1f - kotlin.math.exp(-raw / pullReach))

internal fun MainActivity.placePull(y: Float) {
    val disc = binding.pullIndicator
    val size = disc.height.takeIf { it > 0 } ?: (40f * resources.displayMetrics.density).toInt()
    if (y <= 0.5f && !pullRefreshing) {
        disc.visibility = View.GONE
        return
    }
    if (disc.visibility != View.VISIBLE) {
        disc.imageTintList = android.content.res.ColorStateList.valueOf(newTabAccent)
        disc.visibility = View.VISIBLE
    }
    disc.translationY = y - size
    val armed = (y / pullTrigger).coerceIn(0f, 1f)
    disc.alpha = 0.4f + 0.6f * armed
    disc.scaleX = 0.7f + 0.3f * armed
    disc.scaleY = disc.scaleX
    if (!pullRefreshing) disc.rotation = y * 1.6f
}

internal fun MainActivity.releasePull() {
    if (pullRaw <= 0f) return
    val y = rubber(pullRaw)
    pullRaw = 0f
    if (pullRefreshing) return
    if (y < pullTrigger) {
        pullSpring.animate(y, 0f)
        return
    }
    pullRefreshing = true
    pullStartedAt = android.os.SystemClock.uptimeMillis()
    // A small tick in the hand as the refresh takes, as Chrome gives.
    Haptics.confirm(binding.pullIndicator)
    pullSpring.animate(y, pullHold)
    val disc = binding.pullIndicator
    pullSpin = android.animation.ObjectAnimator.ofFloat(disc, View.ROTATION, disc.rotation, disc.rotation + 360f).apply {
        duration = 700L
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        start()
    }
    activeWeb()?.reload()
    // A reload that never reports back still lets the disc go.
    uiHandler.removeCallbacks(pullTimeout)
    uiHandler.postDelayed(pullTimeout, 10_000L)
}

/** The reload has landed - or the tab changed. The disc goes back up. */
internal fun MainActivity.endPullRefresh(now: Boolean = false) {
    pullRaw = 0f
    if (!pullRefreshing) {
        if (now) { pullSpring.cancel(); placePull(0f) }
        return
    }
    // At least a moment of spinning, or a fast reload reads as a flicker.
    val shown = android.os.SystemClock.uptimeMillis() - pullStartedAt
    if (!now && shown < 450L) {
        uiHandler.postDelayed({ endPullRefresh() }, 450L - shown)
        return
    }
    pullRefreshing = false
    uiHandler.removeCallbacks(pullTimeout)
    pullSpin?.cancel()
    pullSpin = null
    if (now) {
        pullSpring.cancel()
        placePull(0f)
    } else pullSpring.animate(pullSpring.value, 0f)
}

internal fun MainActivity.setNavEnabled(v: View, enabled: Boolean) {
    v.isEnabled = enabled
    v.alpha = if (enabled) 1f else 0.38f
}
