package com.search.browser

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether any screen of the app is started, so Night Owl's lock can tell the
 * user leaving the app from a move between its own screens (Settings, a file
 * picker coming back). This used to live in AppOpenAds, which watched opens
 * for the app open ad; that ad is gone and this is all of it that remains.
 */
object AppVisibility {

    private var installed = false
    private var started = 0

    /** Once, from MainActivity.onCreate. */
    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** No screen of the app is started: the user has left it. */
    fun inBackground(): Boolean = installed && started == 0
}
