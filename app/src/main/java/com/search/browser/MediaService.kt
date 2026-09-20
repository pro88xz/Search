package com.search.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

/**
 * Foreground service hosting a MediaSession + notification so the user can
 * control page media (play/pause) from the notification shade, like Chrome.
 * Playback state is driven by MediaDetect JS via MainActivity; control actions
 * are routed back through onControl.
 */
class MediaService : Service() {

    private lateinit var session: MediaSessionCompat
    private val channelId = "search_media"
    private val notifId = 42

    companion object {
        // Set by MainActivity so the service can drive the WebView's media.
        @Volatile var onControl: ((String) -> Unit)? = null
        const val EXTRA_TITLE = "title"
        const val EXTRA_HOST = "host"
        const val EXTRA_PLAYING = "playing"
        const val ACTION_UPDATE = "com.search.browser.MEDIA_UPDATE"
        const val ACTION_STOP = "com.search.browser.MEDIA_STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "SearchMedia").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { onControl?.invoke("play") }
                override fun onPause() { onControl?.invoke("pause") }
            })
            isActive = true
        }
    }

    // The last state that arrived with real values. A forwarded media-button
    // intent carries a key event and no extras, so without this the
    // notification would rebuild itself as "Media" with no host, paused, every
    // time the user pressed play.
    private var lastTitle = "Media"
    private var lastHost = ""
    private var lastPlaying = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            // Forwarded here by MediaButtonReceiver, which started this service
            // with startForegroundService - so it still has to be promoted
            // within a few seconds or the system takes the app down. Rebuilt
            // from what was last known, because the intent carries nothing.
            promote(lastTitle, lastHost, lastPlaying)
            MediaButtonReceiver.handleIntent(session, intent)
            return START_NOT_STICKY
        }

        lastTitle = intent?.getStringExtra(EXTRA_TITLE) ?: "Media"
        lastHost = intent?.getStringExtra(EXTRA_HOST) ?: ""
        lastPlaying = intent?.getBooleanExtra(EXTRA_PLAYING, false) ?: false
        updateSession(lastTitle, lastHost, lastPlaying)
        promote(lastTitle, lastHost, lastPlaying)
        MediaButtonReceiver.handleIntent(session, intent)
        return START_NOT_STICKY
    }

    private fun promote(title: String, host: String, playing: Boolean) {
        try {
            startForeground(notifId, buildNotification(title, host, playing))
        } catch (e: Exception) {
            // Android 12 and up refuse a foreground start from the background in
            // some states. There is nothing useful to do from here, and letting
            // it throw would take the app with it - which is the failure being
            // fixed, in a different costume.
            stopSelfSafely()
        }
    }

    private fun updateSession(title: String, host: String, playing: Boolean) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, host)
                .build()
        )
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun buildNotification(title: String, host: String, playing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleAction = if (playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(host)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(toggleAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(channelId, "Media controls", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        session.isActive = false
        session.release()
        super.onDestroy()
    }
}
