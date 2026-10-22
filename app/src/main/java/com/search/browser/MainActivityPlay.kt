package com.search.browser



// MainActivity, continued: In-app updates (Google Play), In-app review (Google Play).
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- In-app updates (Google Play) ----------

internal fun MainActivity.checkForUpdate(fromUser: Boolean) {
    appUpdateManager.appUpdateInfo
        .addOnSuccessListener { info ->
            val available = info.updateAvailability() ==
                com.google.android.play.core.install.model.UpdateAvailability.UPDATE_AVAILABLE
            val allowsFlexible = info.isUpdateTypeAllowed(
                com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE)
            if (available && allowsFlexible) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        updateLauncher,
                        com.google.android.play.core.appupdate.AppUpdateOptions.newBuilder(
                            com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE
                        ).build()
                    )
                } catch (e: Exception) { /* ignore */ }
            } else if (fromUser) {
                android.widget.Toast.makeText(this,
                    "You're on the latest version", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        .addOnFailureListener {
            if (fromUser) android.widget.Toast.makeText(this,
                "Couldn't check for updates", android.widget.Toast.LENGTH_SHORT).show()
        }
}

// Public entry point for the Settings "Check for updates" row.
fun MainActivity.checkForUpdateFromSettings() = checkForUpdate(fromUser = true)

// ---------- In-app review (Google Play) ----------

/**
 * Asks Play to show its review dialog, if this is a fair moment to.
 *
 * Deliberately has no caller anywhere the user can reach. Play's policy
 * forbids a "Rate us" button and forbids gating the prompt behind a
 * question like "enjoying the app?" - showing the real dialog only to
 * people who answered yes is review gating, which is a violation rather
 * than a funnel. So this fires on its own or not at all.
 *
 * Nothing may be branched on the outcome. The listener below is called
 * whether the user wrote a review, dismissed the sheet, or never saw one
 * because Play's quota suppressed it - the API reports none of that, by
 * design, so that apps cannot treat reviewers differently.
 */
internal fun MainActivity.maybeAskForReview() {
    if (isFinishing || isDestroyed) return
    // Never during a private session, and never over something else -
    // the ad consent message included.
    if (nightOwl || searchMode || deckVisible || AdConsent.formShowing) return
    val current = tabs.activeTab?.url
    if (!(current == null || current == homePage)) return
    if (!Reviews.eligible(this)) return
    // One ask per launch: the default-browser offer stands aside.
    offeredThisLaunch = true

    // FakeReviewManager runs the whole flow and shows nothing, which is
    // the only way to prove the plumbing works: the real dialog cannot be
    // summoned on demand, since quota decides whether it appears.
    val manager = if (BuildConfig.DEBUG) {
        com.google.android.play.core.review.testing.FakeReviewManager(this)
    } else {
        com.google.android.play.core.review.ReviewManagerFactory.create(this)
    }

    manager.requestReviewFlow().addOnCompleteListener { request ->
        if (!request.isSuccessful) return@addOnCompleteListener
        if (isFinishing || isDestroyed) return@addOnCompleteListener
        manager.launchReviewFlow(this, request.result).addOnCompleteListener {
            // Recorded on the attempt, not on a result, because there is
            // no result to wait for. If quota swallowed the dialog this
            // spends the window on nothing - which is the trade for never
            // pestering someone who did see it and closed it.
            Reviews.noteAsked(this)
            if (BuildConfig.DEBUG) toast("Review flow ran (fake in debug)")
        }
    }
}
