package com.search.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Restarts Search: ends the browser's process and opens it again in a new
 * one, whose tabs come back from disk (SessionStore).
 *
 * For pages that stay stuck after the page was ended - the hang is then in
 * the app's own process, which only a new process clears - and for Android 9
 * and below, where a page cannot be ended on its own.
 *
 * It runs in a process of its own (":restart" in the manifest) because the
 * process being ended cannot open the browser again itself: whatever it
 * starts in itself goes with it, and since Android 10 a relaunch left to
 * AlarmManager is a start from the background, which Android refuses. This
 * activity is on screen when it opens the browser, which Android allows. It
 * draws nothing and is gone at once. Nothing else runs in its process: the
 * app has no Application class, and ads and every other library start in
 * MainActivity, in the main process, as before.
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launch)
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_PID = "pid"

        /** Called from the browser's process, once its tabs are on disk. */
        fun restart(c: Context) {
            c.startActivity(Intent(c, RestartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PID, Process.myPid()))
        }
    }
}
