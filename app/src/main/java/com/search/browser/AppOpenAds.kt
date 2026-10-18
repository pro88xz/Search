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
 * The app open ad: a full-screen ad as the browser is opened - from the
 * launcher, or come back to from another app - over the browser's own
 * loading moment, dismissed back to it.
 *
 * One is asked for on every open, so the next open has one ready, and it is
 * shown at an open when it is ready then, or arrives within [OPEN_WINDOW_MS]
 * of it before the user has touched anything: never in the middle of using
 * the browser. A failed request is never the end of it - asked again after
 * 30 s, 2, 5 and 15 minutes, and on every open after that (30 s apart at
 * least) - except after a set-up fault (codes 1, 8), which the same request
 * would only repeat. An ad is used within four hours of loading, as AdMob
 * asks, or thrown away and another asked for.
 *
 * Not shown, and not asked for: in Night Owl, for supporters, without
 * consent, before the SDK has started, and while the consent form is up
 * ([gate], from MainActivity). Not shown either when the browser was opened
 * to do something specific - a link or text from another app, a launcher
 * shortcut or the widget ([skipThisOpen]) - or on coming back from somewhere
 * the browser itself sent the user (a file picker, the share sheet, another
 * app for a link: [leftForOtherApp]), or from the ad's own click.
 *
 * Opens are told apart from moving between the browser's own screens by
 * counting started activities: an open is the count going from 0 to 1.
 */
object AppOpenAds {

    private const val TAG = "AppOpenAds"
    private val UNIT = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/9257395921"
        else "ca-app-pub-9121922395304175/3558362744"
    private const val EXPIRY_MS = 4L * 60L * 60_000L
    private const val OPEN_WINDOW_MS = 4_000L
    private const val MIN_GAP_MS = 30_000L
    private val RETRY_MS = longArrayOf(30_000L, 120_000L, 300_000L, 900_000L)
    /** A trip out to another app, sent there by the browser, counts as one only this long. */
    private const val ROUND_TRIP_MS = 30L * 60_000L

    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())
    private var installed = false

    /** May an app open ad be asked for and shown right now? (MainActivity) */
    @Volatile var gate: () -> Boolean = { false }
    /** May it be shown over [activity] right now - the browser up and settled? */
    @Volatile var canShowOver: (Activity) -> Boolean = { false }

    private var ad: AppOpenAd? = null
    private var loadedAt = 0L
    private var loading = false
    private var lastAttemptAt = 0L
    private var failures = 0
    private var lastError = -1
    private var showing = false

    private var started = 0
    private var current: java.lang.ref.WeakReference<Activity>? = null
    private var openAt = 0L
    private var touched = false
    private var skipOpen = false
    private var leftAt = 0L
    private var clickedAway = false

    private val retry = Runnable { load() }
    private val tryShow = Runnable { maybeShow() }

    /** Once, from MainActivity.onCreate: starts watching opens. */
    fun install(application: Application) {
        app = application.applicationContext
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (isAdActivity(activity)) return
                current = java.lang.ref.WeakReference(activity)
                started++
                if (started == 1) opened()
            }
            override fun onActivityResumed(activity: Activity) {
                if (!isAdActivity(activity)) current = java.lang.ref.WeakReference(activity)
            }
            override fun onActivityStopped(activity: Activity) {
                if (isAdActivity(activity)) return
                started = (started - 1).coerceAtLeast(0)
                // In the background: the next open starts with a clean slate
                // (a skip asked for during this one is spent).
                if (started == 0) { skipOpen = false; touched = false; openAt = 0L }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    // The ad's own screen (com.google.android.gms.ads.AdActivity) is not one
    // of the browser's: it coming and going is not an open.
    private fun isAdActivity(a: Activity) = a.javaClass.name.startsWith("com.google.android.gms.ads")

    /**
     * The browser came to the front. A skip asked for just before (the
     * intent that opened it, handled in onCreate or onNewIntent) stands.
     */
    private fun opened() {
        val now = SystemClock.elapsedRealtime()
        openAt = now
        touched = false
        val back = leftAt > 0L && now - leftAt < ROUND_TRIP_MS
        if (back || clickedAway) skipOpen = true
        leftAt = 0L
        clickedAway = false
        // Asked for on every open, so the next one has an ad ready.
        if (ad == null || expired()) load(fromOpen = true)
        // After the intent that opened the app has been handled (skipThisOpen)
        // and the first frame is up.
        handler.removeCallbacks(tryShow)
        handler.postDelayed(tryShow, 350L)
    }

    /** This open was to do something specific (a link, shared text, a shortcut): no ad over it. */
    fun skipThisOpen() { skipOpen = true }

    /** The browser is sending the user to another app (a picker, share, a link elsewhere). */
    fun leftForOtherApp() { leftAt = SystemClock.elapsedRealtime() }

    /** The user touched the browser: from here on this open is in use, and no ad interrupts it. */
    fun userInteracted() { touched = true }

    /** Something changed that may let a waiting ad show (the splash left, the SDK started). */
    fun poke() {
        handler.removeCallbacks(tryShow)
        handler.post(tryShow)
    }

    /** The SDK has started (or consent changed): ask for one if there is none. */
    fun sdkReady() {
        if (ad == null && !loading) load(fromOpen = true)
        poke()
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
                    // Arrived within the open's first moments: it may still show.
                    maybeShow()
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
                }
            })
        } catch (t: Throwable) {
            // Nothing about an ad may take the browser down.
            loading = false
            if (BuildConfig.DEBUG) android.util.Log.d(TAG, "load threw: $t")
        }
    }

    private fun maybeShow() {
        if (!installed || showing) return
        val a = ad ?: return
        if (expired()) { ad = null; load(); return }
        val now = SystemClock.elapsedRealtime()
        // Only at an open: within its first moments, before any touch.
        if (openAt == 0L || touched || now - openAt > OPEN_WINDOW_MS) return
        if (skipOpen) return
        val activity = current?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (!gate() || !canShowOver(activity)) return
        showing = true
        openAt = 0L
        a.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                HealthStats.count(app, HealthStats.AO_SHOWN)
            }
            override fun onAdDismissedFullScreenContent() {
                showing = false
                ad = null
                // The next open's ad, asked for now.
                load()
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                showing = false
                ad = null
                HealthStats.noteAppOpenError(app, e.code, "could not show: " + e.message)
                load()
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
        }
    }
}
