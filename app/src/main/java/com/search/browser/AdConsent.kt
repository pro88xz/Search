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
 * Consent is asked for only where Google requires it before ads: the EEA,
 * the UK and Switzerland ([regulated]). There, no ad is requested until
 * UMP's canRequestAds() says so - asking before consent is settled, or after
 * a form failed or went unanswered, is exactly what Google restricts AdMob
 * accounts for; a refusal still allows limited, non-personalised ads, and
 * canRequestAds() already accounts for that. Everywhere else ads are
 * requested at once ([adsAllowed]), never waiting on the consent check:
 * the check still runs on every launch, silently, as Google asks - it is
 * what notices someone who has travelled into a regulated region, and what
 * the US state regulations message (an opt-out, not a prior consent) hangs
 * its "Ad privacy choices" entry on.
 */
object AdConsent {

    /** The startup form is up, so nothing else should open over it. */
    @Volatile
    var formShowing = false
        private set

    /**
     * Refreshes the consent status, shows the AdMob message if this user has
     * to answer one, and then reports whether ads may be requested. [onDone]
     * is called once, on the main thread. [onFormNeeded] is called just
     * before a form is put up - only when the user really has to answer one -
     * so a screen held over the app (the splash, waiting for the app open
     * ad) can make way for it.
     */
    fun gather(activity: Activity, onFormNeeded: (() -> Unit)? = null, onDone: (Boolean) -> Unit) {
        // Anything thrown here would leave the caller waiting on an answer
        // that never comes (MainActivity.consentInFlight): answered instead
        // from what is already known.
        try {
            gatherNow(activity, onFormNeeded, onDone)
        } catch (t: Throwable) {
            formShowing = false
            onDone(try { adsAllowed(activity) } catch (e: Throwable) { false })
        }
    }

    private fun gatherNow(activity: Activity, onFormNeeded: (() -> Unit)?, onDone: (Boolean) -> Unit) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(activity, params, {
            if (activity.isFinishing || activity.isDestroyed) return@requestConsentInfoUpdate
            // A form comes up only when an answer is required; for everyone
            // else the call below returns at once, and nothing should treat
            // the app as covered by a form in the meantime.
            if (info.consentStatus == ConsentInformation.ConsentStatus.REQUIRED) {
                formShowing = true
                onFormNeeded?.invoke()
            }
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                // Called whether a form was shown or not, and on failure.
                formShowing = false
                // A form that could not be shown where one is required - no
                // message published in AdMob, Privacy & messaging, for this
                // region, say - leaves no ads at all, and used to say nothing.
                if (!adsAllowed(activity)) {
                    HealthStats.noteAdGate(activity, if (error != null)
                        "consent form failed: " + error.errorCode + " " + error.message
                        else "consent does not allow ads yet")
                }
                onDone(adsAllowed(activity))
            }
        }, { error ->
            // The status could not be refreshed - offline, or the consent
            // server blocked by a private DNS. The answer given on an earlier
            // launch is still on the device, and decides; with none, where
            // the phone is (regulated) does.
            if (!adsAllowed(activity)) {
                HealthStats.noteAdGate(activity,
                    "consent check failed: " + error.errorCode + " " + error.message)
            }
            onDone(adsAllowed(activity))
        })
    }

    fun canRequestAds(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).canRequestAds()

    /**
     * May an ad be requested now? Yes once UMP allows it; yes outside the
     * regions that require consent, without waiting for the consent check;
     * in those regions, only with consent.
     */
    fun adsAllowed(context: Context): Boolean {
        val info = UserMessagingPlatform.getConsentInformation(context)
        if (info.canRequestAds()) return true
        return !regulated(context)
    }

    /**
     * Is this user where consent is required before ads? UMP's own answer
     * from the last consent check (it places the user by IP) when there is
     * one: REQUIRED or OBTAINED is a regulated region, NOT_REQUIRED is not.
     * Before the first check has ever answered - a fresh install, or a
     * consent server that a private DNS blocks - the phone's own signals
     * decide, cautiously: the mobile network's country, the SIM's country
     * and the time zone. Any one of them in or towards the region counts it
     * as regulated, so an ad is never requested there without consent; a
     * mistake the other way only means that one launch waits for the check.
     */
    fun regulated(context: Context): Boolean {
        when (UserMessagingPlatform.getConsentInformation(context).consentStatus) {
            ConsentInformation.ConsentStatus.REQUIRED,
            ConsentInformation.ConsentStatus.OBTAINED -> return true
            ConsentInformation.ConsentStatus.NOT_REQUIRED -> return false
        }
        val tm = try {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        } catch (e: Exception) { null }
        val countries = listOfNotNull(
            try { tm?.networkCountryIso } catch (e: Exception) { null },
            try { tm?.simCountryIso } catch (e: Exception) { null })
            .map { it.trim().uppercase(java.util.Locale.ROOT) }.filter { it.length == 2 }
        if (countries.any { it in CONSENT_COUNTRIES }) return true
        val zone = try { java.util.TimeZone.getDefault().id } catch (e: Exception) { "" }
        if (zone.startsWith("Europe/") || zone in EUROPEAN_ATLANTIC_ZONES) return true
        // With neither a country nor a time zone to go on, it is not known: treated as regulated.
        return countries.isEmpty() && zone.isEmpty()
    }

    /**
     * Where Google's EU User Consent Policy applies: the 27 EU countries,
     * Iceland, Liechtenstein and Norway (the EEA), the UK and Switzerland,
     * plus EU territories with country codes of their own and the UK's
     * neighbours with GDPR-equivalent law.
     */
    private val CONSENT_COUNTRIES = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU",
        "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        "IS", "LI", "NO", "GB", "UK", "CH",
        "AX", "GF", "GP", "MQ", "RE", "YT", "MF", "GI", "JE", "GG", "IM")

    /** Parts of the region whose time zones are not under Europe/. */
    private val EUROPEAN_ATLANTIC_ZONES = setOf(
        "Atlantic/Reykjavik", "Atlantic/Canary", "Atlantic/Madeira", "Atlantic/Azores",
        "Atlantic/Faroe", "Atlantic/Jan_Mayen", "Arctic/Longyearbyen", "Africa/Ceuta",
        "Asia/Nicosia", "Asia/Famagusta", "Indian/Reunion", "Indian/Mayotte",
        "America/Guadeloupe", "America/Martinique", "America/Cayenne", "America/Marigot")

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
