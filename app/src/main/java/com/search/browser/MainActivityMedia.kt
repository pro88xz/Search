package com.search.browser

import android.os.Build
import android.view.View
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import org.json.JSONObject

// MainActivity, continued: Listen to this page, Media control notification, HTML5 fullscreen video, Picture in picture.
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- HTML5 fullscreen video ----------

internal fun MainActivity.enterFullscreen(view: View, cb: WebChromeClient.CustomViewCallback?) {
    // Some players fire this twice. The second must be refused, or the first
    // view is orphaned and the page can never come back out of fullscreen.
    if (fullscreenView != null) { cb?.onCustomViewHidden(); return }

    fullscreenView = view
    fullscreenCallback = cb
    savedOrientation = requestedOrientation

    // Hosted in the activity content root, which is the PARENT of our app
    // root - so it covers the inset padding, the top bar and the toolbar
    // without any of them needing to know fullscreen exists. Clickable so
    // player taps cannot fall through to the chrome underneath.
    val root = findViewById<android.widget.FrameLayout>(android.R.id.content)
    val holder = android.widget.FrameLayout(this).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        isClickable = true
        isFocusable = true
    }
    holder.addView(
        view,
        android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.view.Gravity.CENTER
        )
    )
    root.addView(
        holder,
        android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
    )
    fullscreenContainer = holder

    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setSystemBarsVisible(false)
    requestedOrientation =
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    // A portrait video (a short, a phone-shot clip) is turned upright
    // again rather than shown sideways in a landscape screen. The page
    // knows the video's size; landscape stays when it does not say.
    activeWeb()?.evaluateJavascript(
        "(function(){var e=document.fullscreenElement||document.webkitFullscreenElement;" +
            "var v=e&&(e.tagName==='VIDEO'?e:e.querySelector('video'));" +
            "return v&&v.videoWidth?v.videoWidth+'x'+v.videoHeight:'';})()"
    ) { r ->
        val size = r?.trim('"')?.split('x')
        val w = size?.getOrNull(0)?.toIntOrNull() ?: 0
        val h = size?.getOrNull(1)?.toIntOrNull() ?: 0
        if (fullscreenView === view && w > 0 && h > w) {
            requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
}

internal fun MainActivity.exitFullscreen() {
    val holder = fullscreenContainer ?: return
    val root = findViewById<android.widget.FrameLayout>(android.R.id.content)
    holder.removeAllViews()
    root.removeView(holder)
    fullscreenContainer = null
    fullscreenView = null

    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setSystemBarsVisible(true)
    requestedOrientation = savedOrientation

    // Last, deliberately: the player tears its surface down here, and doing
    // that before the view is detached leaves a black frame on some devices.
    fullscreenCallback?.onCustomViewHidden()
    fullscreenCallback = null
}

// ---------- Picture in picture ----------

internal fun MainActivity.maybeEnterPip() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
    if (inPip) return
    val v = fullscreenView ?: return
    if (!packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
    val w = v.width
    val h = v.height
    if (w <= 0 || h <= 0) return
    // The system rejects anything outside roughly 1:2.39 to 2.39:1 by
    // throwing, so clamp rather than hand it something it will refuse.
    val ratio = (w.toFloat() / h.toFloat()).coerceIn(0.42f, 2.38f)
    try {
        enterPictureInPictureMode(
            android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational((ratio * 1000).toInt(), 1000))
                .build()
        )
    } catch (e: Exception) {
        // A device can refuse PiP for its own reasons; staying put is fine.
    }
}

internal fun MainActivity.setSystemBarsVisible(show: Boolean) {
    val c = androidx.core.view.WindowCompat
        .getInsetsController(window, window.decorView)
    if (show) {
        c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    } else {
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
            .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
}

// ---------- Listen to this page ----------

internal fun MainActivity.listenToPage() {
    val web = activeWeb() ?: return
    if (webPageUrl() == null) { needAPage(); return }
    if (listening && listenWeb === web) { toggleListenPause(); return }
    try {
        web.evaluateJavascript(LISTEN_JS) { raw ->
            if (isFinishing || isDestroyed) return@evaluateJavascript
            val o = try { JSONObject(raw ?: "") } catch (e: Exception) { null }
            val arr = o?.optJSONArray("parts")
            val parts = ArrayList<String>()
            val title = o?.optString("title", "")?.trim().orEmpty()
            if (title.isNotEmpty()) parts.add(title)
            if (arr != null) for (i in 0 until arr.length()) {
                val p = arr.optString(i, "").trim()
                if (p.isNotEmpty() && p != title) parts.add(p)
            }
            val chunks = splitForSpeech(parts)
            if (chunks.size <= (if (title.isNotEmpty()) 1 else 0)) {
                toast("There's nothing on this page to read aloud")
                return@evaluateJavascript
            }
            listenLang = o?.optString("lang", "")?.trim()?.ifBlank { null }
            listenChunks = chunks
            listenWeb = web
            withTts { speakFrom(0) }
        }
    } catch (e: Exception) {
        toast("This page can't be read aloud")
    }
}

/** Paragraphs, with any too long for one utterance cut at sentence ends. */
internal fun MainActivity.splitForSpeech(parts: List<String>): List<String> {
    val max = try { android.speech.tts.TextToSpeech.getMaxSpeechInputLength() } catch (e: Throwable) { 4000 }
    val limit = (max - 100).coerceIn(500, 3900)
    val out = ArrayList<String>()
    for (p in parts) {
        if (p.length <= limit) { out.add(p); continue }
        var rest = p
        while (rest.length > limit) {
            val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', ';'), limit).takeIf { it > limit / 3 }
                ?: rest.lastIndexOf(' ', limit).takeIf { it > limit / 3 } ?: limit
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
    }
    return out
}

internal fun MainActivity.withTts(then: () -> Unit) {
    if (tts != null && ttsReady) { then(); return }
    if (ttsFailed) {
        toast("This phone has no text-to-speech voice. Add one in Android's settings.")
        return
    }
    pendingListen = then
    if (tts != null) return
    tts = android.speech.tts.TextToSpeech(applicationContext) { status ->
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        val i = id?.removePrefix("listen-")?.toIntOrNull() ?: return
                        runOnUiThread { if (listening && !listenPaused) listenIndex = i }
                    }
                    override fun onDone(id: String?) {
                        val i = id?.removePrefix("listen-")?.toIntOrNull() ?: return
                        runOnUiThread {
                            if (listening && !listenPaused && i >= listenChunks.size - 1) stopListening()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {}
                })
                val go = pendingListen
                pendingListen = null
                go?.invoke()
            } else {
                ttsFailed = true
                try { tts?.shutdown() } catch (e: Exception) {}
                tts = null
                pendingListen = null
                toast("This phone has no text-to-speech voice. Add one in Android's settings.")
            }
        }
    }
}

internal fun MainActivity.speakFrom(i: Int) {
    val t = tts ?: return
    if (i !in listenChunks.indices) { stopListening(); return }
    try {
        val loc = listenLang?.let { java.util.Locale.forLanguageTag(it) }
        if (loc != null && t.isLanguageAvailable(loc) >= android.speech.tts.TextToSpeech.LANG_AVAILABLE) {
            t.language = loc
        }
        t.stop()
        for (k in i until listenChunks.size) {
            t.speak(listenChunks[k],
                if (k == i) android.speech.tts.TextToSpeech.QUEUE_FLUSH else android.speech.tts.TextToSpeech.QUEUE_ADD,
                null, "listen-$k")
        }
    } catch (e: Exception) {
        toast("Couldn't read this page aloud")
        stopListening()
        return
    }
    listening = true
    listenPaused = false
    listenIndex = i
    showListenBar()
}

internal fun MainActivity.toggleListenPause() {
    if (!listening) return
    if (listenPaused) speakFrom(listenIndex)
    else {
        try { tts?.stop() } catch (e: Exception) {}
        listenPaused = true
        showListenBar()
    }
}

internal fun MainActivity.stopListening() {
    try { tts?.stop() } catch (e: Exception) {}
    listening = false
    listenPaused = false
    listenWeb = null
    binding.listenBar.visibility = View.GONE
}

internal fun MainActivity.showListenBar() {
    val bar = binding.listenBar
    if (!listenBarWired) {
        listenBarWired = true
        binding.listenToggle.setOnClickListener { v -> Haptics.tap(v); toggleListenPause() }
        binding.listenStop.setOnClickListener { v -> Haptics.tap(v); stopListening() }
    }
    val accent = android.content.res.ColorStateList.valueOf(currentAccent())
    binding.listenToggle.imageTintList = accent
    binding.listenStop.imageTintList = accent
    binding.listenToggle.setImageResource(if (listenPaused) R.drawable.listen_play else R.drawable.listen_pause)
    binding.listenToggle.contentDescription = if (listenPaused) "Resume reading" else "Pause reading"
    binding.listenLabel.text = if (listenPaused) "Paused" else "Listening"
    bar.visibility = View.VISIBLE
}

/** The page as its own icon on the home screen, opening in Search. */
internal fun MainActivity.addToHomeScreen() {
    val url = webPageUrl() ?: run { needAPage(); return }
    val web = activeWeb()
    val title = web?.title?.takeIf { it.isNotBlank() } ?: siteNameOf(url)
    val icon = SiteIcons.cached(url) ?: web?.favicon
    if (!HomeShortcut.pin(this, url, title, icon)) toast("This home screen doesn't take shortcuts")
}

// ---------- Media control notification ----------

internal fun MainActivity.onMediaState(state: String, title: String, host: String) {
    when (state) {
        "playing", "paused" -> {
            // Many players only take their source once they start.
            if (state == "playing" && foundVideos.isEmpty()) scheduleVideoScan(400L)
            ensureNotifPermission()
            val intent = android.content.Intent(this, MediaService::class.java).apply {
                action = MediaService.ACTION_UPDATE
                putExtra(MediaService.EXTRA_TITLE, if (title.isBlank()) "Media" else title)
                putExtra(MediaService.EXTRA_HOST, host)
                putExtra(MediaService.EXTRA_PLAYING, state == "playing")
            }
            try {
                androidx.core.content.ContextCompat.startForegroundService(this, intent)
                mediaActive = true
            } catch (e: Exception) { /* ignore */ }
        }
        "none" -> stopMediaService()
    }
}
