package com.search.browser

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/**
 * The search widget for the home screen, as Chrome's: the owl, a pill that
 * opens search in the browser, a microphone and the QR scanner - each a
 * launcher-shortcut action MainActivity already handles (handleShortcut).
 * Nothing on a timer; it is drawn once and only taps it.
 *
 * Two sizes of the same pill: the full one, and a size down for a launcher
 * whose rows are too short for it - on Android 12+ the launcher picks
 * between them as the widget is resized, before that it is picked here from
 * the size the launcher reports.
 */
class SearchWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) {
        update(context, manager, id)
    }

    private fun update(c: Context, manager: AppWidgetManager, id: Int) {
        val views = if (android.os.Build.VERSION.SDK_INT >= 31) {
            RemoteViews(mapOf(
                android.util.SizeF(120f, 0f) to build(c, R.layout.widget_search_compact),
                android.util.SizeF(120f, FULL_MIN_HEIGHT_DP) to build(c, R.layout.widget_search)))
        } else {
            // Portrait height is the larger of the two the launcher reports.
            val h = try { manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) }
                catch (e: Exception) { 0 }
            build(c, if (h in 1 until FULL_MIN_HEIGHT_DP.toInt()) R.layout.widget_search_compact else R.layout.widget_search)
        }
        try { manager.updateAppWidget(id, views) } catch (e: Exception) { /* the host went away */ }
    }

    private fun build(c: Context, layout: Int): RemoteViews {
        val views = RemoteViews(c.packageName, layout)
        // The pill carries Android's background id: a tap anywhere on it
        // opens search, and the launcher grows the browser out of it.
        views.setOnClickPendingIntent(android.R.id.background, action(c, MainActivity.ACTION_SEARCH, 1))
        views.setOnClickPendingIntent(R.id.widgetOwl, action(c, MainActivity.ACTION_SEARCH, 1))
        views.setOnClickPendingIntent(R.id.widgetText, action(c, MainActivity.ACTION_SEARCH, 1))
        views.setOnClickPendingIntent(R.id.widgetMic, action(c, MainActivity.ACTION_VOICE, 2))
        views.setOnClickPendingIntent(R.id.widgetScan, action(c, MainActivity.ACTION_SCAN, 3))
        return views
    }

    private fun action(c: Context, act: String, code: Int): PendingIntent =
        PendingIntent.getActivity(c, code,
            Intent(c, MainActivity::class.java).setAction(act)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        /** The cell height (dp) the full-size pill needs; below it the compact one is used. */
        private const val FULL_MIN_HEIGHT_DP = 64f

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
