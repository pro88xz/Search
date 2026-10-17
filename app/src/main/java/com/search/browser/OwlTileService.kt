package com.search.browser

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * A Night Owl tile for the Quick Settings shade: one tap opens a private tab,
 * from anywhere. The shade collapses and the browser comes up in Night Owl
 * (MainActivity.handleShortcut).
 */
class OwlTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val open = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_NIGHT_OWL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // From Android 14 an Intent here throws for an app targeting it.
                startActivityAndCollapse(PendingIntent.getActivity(this, 7, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(open)
            }
        } catch (e: Exception) { /* the shade refused; nothing to undo */ }
    }
}
