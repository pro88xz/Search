package com.search.browser

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * The search widget for the home screen, as Chrome's: the owl, a pill that
 * opens search in the browser, a microphone and the QR scanner - each a
 * launcher-shortcut action MainActivity already handles (handleShortcut).
 * Nothing on a timer; it is drawn once and only taps it.
 */
class SearchWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_search)
        views.setOnClickPendingIntent(R.id.widgetPill, action(context, MainActivity.ACTION_SEARCH, 1))
        views.setOnClickPendingIntent(R.id.widgetOwl, action(context, MainActivity.ACTION_SEARCH, 1))
        views.setOnClickPendingIntent(R.id.widgetText, action(context, MainActivity.ACTION_SEARCH, 1))
        views.setOnClickPendingIntent(R.id.widgetMic, action(context, MainActivity.ACTION_VOICE, 2))
        views.setOnClickPendingIntent(R.id.widgetScan, action(context, MainActivity.ACTION_SCAN, 3))
        ids.forEach { id ->
            try { manager.updateAppWidget(id, views) } catch (e: Exception) { /* the host went away */ }
        }
    }

    private fun action(c: Context, act: String, code: Int): PendingIntent =
        PendingIntent.getActivity(c, code,
            Intent(c, MainActivity::class.java).setAction(act)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        /** Settings > Add the search widget: Android's own "add to home screen" (Android 8+). */
        fun requestPin(c: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 26) return false
            return try {
                val m = c.getSystemService(AppWidgetManager::class.java) ?: return false
                if (!m.isRequestPinAppWidgetSupported) false
                else m.requestPinAppWidget(ComponentName(c, SearchWidget::class.java), null, null)
            } catch (e: Exception) { false }
        }
    }
}
