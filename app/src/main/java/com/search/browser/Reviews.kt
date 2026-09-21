package com.search.browser

import android.content.Context

/**
 * When it is fair to ask someone for a Play review.
 *
 * Kept out of MainActivity because these are product decisions, not wiring,
 * and they are the part worth arguing about. The Play API itself is three
 * calls; everything that makes it tolerable is here.
 *
 * Play's own guidance, which the numbers below follow:
 *
 *  - Ask people who have had enough of the app to have a view. Someone eight
 *    launches and three days in has used it more than once on purpose.
 *  - Ask rarely. Play quota-limits the dialog anyway, so asking often does
 *    not ask often - it asks nothing, silently.
 *  - Never interrupt. The caller checks that the home page is up and nothing
 *    else is happening before any of this is consulted.
 *  - Never put it behind a button, and never pre-qualify people with "are
 *    you enjoying the app?". That second one is review gating and is against
 *    policy, however well-intentioned.
 */
object Reviews {

    /** Launches before the first ask. Two or three is someone still deciding. */
    private const val MIN_LAUNCHES = 8

    /** And enough calendar time that those launches were not one evening. */
    private const val MIN_DAYS_INSTALLED = 3L

    /**
     * Between asks. Long, deliberately: Play reports nothing about whether
     * the dialog appeared or what was done with it, so a shorter window would
     * mean re-asking people who already answered.
     */
    private const val MIN_DAYS_BETWEEN_ASKS = 90L

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private const val KEY_LAUNCHES = "review_launches"
    private const val KEY_FIRST_RUN = "review_first_run_at"
    private const val KEY_LAST_ASKED = "review_last_asked_at"

    /** Once per process, from the launch path. A rotation is not a launch. */
    fun noteLaunch(c: Context) {
        val now = System.currentTimeMillis()
        if (Settings.getLong(c, KEY_FIRST_RUN, 0L) == 0L) {
            Settings.setLong(c, KEY_FIRST_RUN, now)
        }
        Settings.setLong(c, KEY_LAUNCHES, Settings.getLong(c, KEY_LAUNCHES, 0L) + 1L)
    }

    fun eligible(c: Context): Boolean {
        val now = System.currentTimeMillis()

        if (Settings.getLong(c, KEY_LAUNCHES, 0L) < MIN_LAUNCHES) return false

        val first = Settings.getLong(c, KEY_FIRST_RUN, 0L)
        if (first == 0L) return false
        // A clock moved backwards - a manual change, a timezone database
        // update - would otherwise make every window look enormous or
        // negative. Treat the future as "not yet" rather than trusting it.
        if (first > now) return false
        if (now - first < MIN_DAYS_INSTALLED * DAY_MS) return false

        val asked = Settings.getLong(c, KEY_LAST_ASKED, 0L)
        if (asked != 0L) {
            if (asked > now) return false
            if (now - asked < MIN_DAYS_BETWEEN_ASKS * DAY_MS) return false
        }
        return true
    }

    /**
     * Recorded when the flow is launched, not when anything is submitted -
     * there is no submission to hear about. See MIN_DAYS_BETWEEN_ASKS.
     */
    fun noteAsked(c: Context) {
        Settings.setLong(c, KEY_LAST_ASKED, System.currentTimeMillis())
    }
}
