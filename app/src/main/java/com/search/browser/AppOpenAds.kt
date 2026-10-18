package com.search.browser

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd

/**
 * The app open ad: a full-screen ad the browser opens through - the splash,
 * then the ad, then home - never one that appears over home once it is up.
 *
 * This object only keeps an ad ready and knows what counts as an open;
 * MainActivity decides the moment: on a launch it keeps the splash up until
 * the ad has loaded and shows it over the splash (MainActivity.splashAd);
 * coming back to the browser it puts up the same splash picture and does
 * the same (MainActivity.warmOpenAd). Closing the ad uncovers home. Only a
 * request that fails lets the browser open without one.
 *
 * One is asked for on every open, so the next open has one ready. A failed
 * request is never the end of it - asked again after 30 s, 2, 5 and 15
 * minutes, and on every open after that (30 s apart at least) - except
 * after a set-up fault (codes 1, 8), which the same request would only
 * repeat. An ad is used within four hours of loading, as AdMob asks, or
 * thrown away and another asked for.
 *
 * Not asked for or shown in Night Owl, for supporters, without consent or
 * before the SDK has started ([gate], from MainActivity). Not shown when the
 * browser was opened to do something specific - a link or text from another
 * app, a shortcut or the widget ([skipThisOpen]) - nor on coming back from
 * somewhere the browser itself sent the user (a picker, the share sheet,
 * another app: [leftForOtherApp]), nor from the ad's own click.
 *
 * Opens are told apart from moving between the browser's own screens by
 * counting started activities - the ad's own screen included, so closing the
 * ad is never mistaken for an open: an open is the count going from 0 to 1.
 */
object AppOpenAds {

    const val NO_OPEN = 0
    const val COLD_OPEN = 1
    const val WARM_OPEN = 2

    private const val TAG = "AppOpenAds"
    private val UNIT = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/9257395921"
        else "ca-app-pub-9121922395304175/3558362744"
    private const val EXPIRY_MS = 4L * 60L * 60_000L
    private const val MIN_GAP_MS = 30_000L
    private val RETRY_MS = longArrayOf(30_000L, 120_000L, 300_000L, 900_000L)
    /** A trip out to another app, sent there by the browser, counts as one only this long. */
    private const val ROUND_TRIP_MS = 30L * 60_000L

    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())
    private var installed = false

    /** May an app open ad be asked for and shown right now? (MainActivity) */
    @Volatile var gate: () -> Boolean = { false }
    /**
     * A request has just finished: true with an ad loaded, false when it
     * failed. MainActivity waits on this behind the splash.
     */
    var onResult: ((Boolean) -> Unit)? = null

    private var ad: AppOpenAd? = null
    private var loadedAt = 0L
    private var loading = false
    private var lastAttemptAt = 0L
    private var failures = 0
    private var lastError = -1
    private var showing = false
    private var showingAt = 0L

    private var started = 0
    private var opens = 0
    private var pendingOpen = NO_OPEN
    private var skipOpen = false
    private var leftAt = 0L
    private var clickedAway = false

    private val retry = Runnable { load() }

    /** Once, from MainActivity.onCreate: starts watching opens. */
    fun install(application: Application) {
        app = application.applicationContext
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) opened(activity)
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // In the background: the next open starts with a clean slate
                // (a skip asked for during this one is spent).
                if (started == 0) { skipOpen = false; pendingOpen = NO_OPEN }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * The browser came to the front. A skip asked for just before (the
     * intent that opened it, handled in onCreate or onNewIntent) stands.
     */
    private fun opened(first: Activity) {
        val now = SystemClock.elapsedRealtime()
        if (showing) {
            if (first.javaClass.name.startsWith("com.google.android.gms.ads.")) {
                // Back to an ad still on screen (left with Home while it was
                // up): not an open - closing it must not bring another.
                skipOpen = true
            } else if (now - showingAt > 30_000L) {
                // The browser itself came first, so the ad's screen is gone.
                // Its closing callback never came (torn down with the app in
                // the background), and "showing" stayed set: no app open ad
                // was asked for or shown again until the app was restarted.
                showing = false
            }
        }
        val back = leftAt > 0L && now - leftAt < ROUND_TRIP_MS
        if (back || clickedAway) skipOpen = true
        leftAt = 0L
        clickedAway = false
        // Asked for on every open, so the next one has an ad ready.
        if (ad == null || expired()) load(fromOpen = true)
        pendingOpen = if (opens == 0) COLD_OPEN else WARM_OPEN
        opens++
    }

    /** The open MainActivity has not dealt with yet (taken once, in onResume): cold, warm or none. */
    fun takeOpen(): Int {
        val p = pendingOpen
        pendingOpen = NO_OPEN
        return p
    }

    /** This open was to do something specific (a link, shared text, a shortcut): no ad. */
    fun skipThisOpen() { skipOpen = true }

    /** May this open have an ad at all (not a link, not a return from the share sheet)? */
    fun wantedThisOpen(): Boolean = !skipOpen

    /** The browser is sending the user to another app (a picker, share, a link elsewhere). */
    fun leftForOtherApp() { leftAt = SystemClock.elapsedRealtime() }

    /** An ad is loaded, fresh and not already on screen. */
    fun readyNow(): Boolean = installed && !showing && ad != null && !expired()

    /** The SDK has started: ask for one if there is none. */
    fun sdkReady() {
        if (ad == null && !loading) load(fromOpen = true)
    }

    /**
     * Makes sure an ad is ready or on its way: true if one is loaded or a
     * request is out (its answer comes to [onResult]), false if none can be
     * asked for right now.
     */
    fun requestNow(): Boolean {
        if (readyNow()) return true
        load(fromOpen = true)
        return loading
    }

    /**
     * Shows the ad over [activity] now. [onShowing] when it is on screen (the
     * splash may go: home is drawn behind it); [onDone] once it is closed
     * (true) or could not be shown (false) - always exactly once.
     */
    fun showNow(activity: Activity, onShowing: () -> Unit, onDone: (Boolean) -> Unit) {
        val a = ad
        if (a == null || !readyNow() || skipOpen || !gate()) { onDone(false); return }
        showing = true
        showingAt = SystemClock.elapsedRealtime()
        var finished = false
        fun done(shown: Boolean) { if (!finished) { finished = true; onDone(shown) } }
        a.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                HealthStats.count(app, HealthStats.AO_SHOWN)
                onShowing()
            }
            override fun onAdDismissedFullScreenContent() {
                showing = false
                ad = null
                // The next open's ad, asked for now.
                load()
                done(true)
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                showing = false
                ad = null
                HealthStats.noteAppOpenError(app, e.code, "could not show: " + e.message)
                load()
                done(false)
            }
            override fun onAdClicked() {
                // Off to the advertiser; coming back from there is not an open.
                clickedAway = true
            }
        }
        try {
            a.show(activity)
        } catch (t: Throwable) {
            showing = false
            ad = null
            if (BuildConfig.DEBUG) android.util.Log.d(TAG, "show threw: $t")
            done(false)
        }
    }

    private fun expired() = ad != null && SystemClock.elapsedRealtime() - loadedAt > EXPIRY_MS

    private fun load(fromOpen: Boolean = false) {
        handler.removeCallbacks(retry)
        if (!installed || loading || showing) return
        if (ad != null && !expired()) return
        if (!gate()) return
        val now = SystemClock.elapsedRealtime()
        // An open asks again only after a set-up fault has been fixed (a new
        // install), and never sooner than 30 s after the last ask.
        if (fromOpen && (lastError == 1 || lastError == 8)) return
        if (lastAttemptAt > 0L && now - lastAttemptAt < MIN_GAP_MS) {
            if (fromOpen) handler.postDelayed(retry, MIN_GAP_MS - (now - lastAttemptAt))
            return
        }
        if (fromOpen && lastError != -1) failures = 0
        ad = null
        loading = true
        lastAttemptAt = now
        HealthStats.count(app, HealthStats.AO_REQUESTS)
        try {
            AppOpenAd.load(app, UNIT, AdRequest.Builder().build(), object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(loaded: AppOpenAd) {
                    loading = false
                    failures = 0
                    lastError = -1
                    ad = loaded
                    loadedAt = SystemClock.elapsedRealtime()
                    HealthStats.count(app, HealthStats.AO_FILLED)
                    onResult?.invoke(true)
                }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    loading = false
                    lastError = e.code
                    HealthStats.count(app, HealthStats.AO_FAILED)
                    HealthStats.noteAppOpenError(app, e.code, e.message)
                    if (BuildConfig.DEBUG) android.util.Log.d(TAG, "failed · code " + e.code + " · " + e.message)
                    if (e.code != 1 && e.code != 8 && failures < RETRY_MS.size) {
                        handler.postDelayed(retry, RETRY_MS[failures])
                    }
                    failures++
                    onResult?.invoke(false)
                }
            })
        } catch (t: Throwable) {
            // Nothing about an ad may take the browser down.
            loading = false
            if (BuildConfig.DEBUG) android.util.Log.d(TAG, "load threw: $t")
            onResult?.invoke(false)
        }
    }
}
