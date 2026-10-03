package com.search.browser

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Ad consent, through Google's User Messaging Platform.
 *
 * The messages themselves - GDPR for the EEA, the UK and Switzerland, and the
 * US state regulations message - are set up in AdMob under Privacy & messaging.
 * UMP decides from the user's location which one applies, if any, shows it,
 * and remembers the answer on the device.
 *
 * The feed ad is only ever requested once [canRequestAds] says so. Asking
 * before consent is settled, or after a form failed or went unanswered, in a
 * region that requires one is exactly what Google restricts AdMob accounts
 * for. A refusal still allows limited, non-personalised ads, and
 * canRequestAds() already accounts for that.
 */
object AdConsent {

    /** The startup form is up, so nothing else should open over it. */
    @Volatile
    var formShowing = false
        private set

    /**
     * Refreshes the consent status, shows the AdMob message if this user has
     * to answer one, and then reports whether ads may be requested. [onDone]
     * is called once, on the main thread.
     */
    fun gather(activity: Activity, onDone: (Boolean) -> Unit) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(activity, params, {
            if (activity.isFinishing || activity.isDestroyed) return@requestConsentInfoUpdate
            formShowing = true
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                // Called whether a form was shown or not, and on failure.
                formShowing = false
                onDone(info.canRequestAds())
            }
        }, {
            // The status could not be refreshed - offline, say. The answer
            // given on an earlier launch is still on the device, and decides.
            onDone(info.canRequestAds())
        })
    }

    fun canRequestAds(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).canRequestAds()

    /**
     * Whether the law requires a way to change or withdraw consent for this
     * user: in the EEA, the UK and Switzerland, and in the US states covered by
     * the AdMob message. Everyone else gets no entry at all. Known once
     * [gather] has run on some launch; until then it reads as not required.
     */
    fun privacyChoicesRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Google's privacy options form: change or withdraw consent for
     * personalised ads, or opt out of the sale or sharing of personal
     * information. [onDone] gets an error message if it could not be shown.
     */
    fun showPrivacyChoices(activity: Activity, onDone: (String?) -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error -> onDone(error?.message) }
    }
}
