package com.search.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.search.browser.MainActivity.BlobSave
import com.search.browser.MainActivity.Companion.OPENER_STALL_MS
import com.search.browser.MainActivity.Companion.POPUP_RECHECK_MS
import com.search.browser.MainActivity.Companion.POPUP_STALL_MS
import com.search.browser.MainActivity.Companion.cookieIo
import com.search.browser.MainActivity.PendingDownload
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

// MainActivity, continued: Saving images, Downloads, Space for downloads, A downloaded app (.apk), Risky downloads, Video downloader, Offline pages.
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- Saving images ----------

internal fun MainActivity.saveImage(url: String) {
    if (android.os.Build.VERSION.SDK_INT <= 28) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingImageSave = url
            storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
    }
    when {
        url.startsWith("data:") -> {
            toast("Saving image\u2026")
            Thread { saveDataImage(url) }.start()
        }
        url.startsWith("http://") || url.startsWith("https://") -> {
            toast("Saving image\u2026")
            val ua = activeWeb()?.settings?.userAgentString
            val referer = activeWeb()?.url
            Thread { saveRemoteImage(url, ua, referer) }.start()
        }
        // Only the page can read a blob:, so we ask it to (see requestBlobSave).
        url.startsWith("blob:") -> {
            toast("Saving image\u2026")
            requestBlobSave(url)
        }
        else -> toast("Can't save this image")
    }
}

/**
 * Fetched here rather than handed to DownloadManager for two reasons: the
 * cookies and referer below are what separate a real image from a saved 403
 * page, and writing through MediaStore ourselves is what makes the file
 * actually appear in the gallery.
 */
internal fun MainActivity.saveRemoteImage(url: String, ua: String?, referer: String?) {
    var conn: HttpURLConnection? = null
    try {
        val c = URL(url).openConnection() as HttpURLConnection
        conn = c
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        ua?.let { c.setRequestProperty("User-Agent", it) }
        android.webkit.CookieManager.getInstance().getCookie(url)?.let {
            c.setRequestProperty("Cookie", it)
        }
        referer?.let { if (it.startsWith("http")) c.setRequestProperty("Referer", it) }
        c.connect()
        if (c.responseCode !in 200..299) { imageSaveFailed(); return }
        val mime = c.contentType?.substringBefore(';')?.trim()
        // An "image" can be anything a server says it is, and it is read
        // whole into memory here: past 64MB it is refused rather than
        // risking the app being killed for memory part-way through.
        val max = 64L * 1024 * 1024
        if (c.contentLengthLong > max) { imageSaveFailed(); return }
        val bytes = c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) { imageSaveFailed(); return }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        if (bytes.isEmpty()) { imageSaveFailed(); return }
        writeImage(bytes, if (mime.isNullOrBlank()) "image/jpeg" else mime)
    } catch (e: Throwable) {
        // OutOfMemoryError included: a failed save, never a dead app.
        imageSaveFailed()
    } finally {
        try { conn?.disconnect() } catch (e: Exception) {}
    }
}

/**
 * The page fetches its own blob, turns it into a data: URL and hands that
 * back over the bridge. Capped at 16MB: base64 inflates by a third and the
 * whole string crosses the bridge in one piece.
 *
 * A site with a strict connect-src CSP can refuse the fetch. That path ends
 * in saveBlobFailed, so it reports rather than hanging.
 */
internal fun MainActivity.requestBlobSave(url: String) {
    val web = activeWeb() ?: run { imageSaveFailed(); return }
    val token = java.util.UUID.randomUUID().toString()
    blobSaveToken = token
    val js = "(function(u,t){try{" +
        "fetch(u).then(function(r){if(!r.ok)throw 0;return r.blob();})" +
        ".then(function(b){if(b.size>16777216)throw 0;" +
        "var fr=new FileReader();" +
        "fr.onloadend=function(){SearchApp.saveBlobImage(t,fr.result);};" +
        "fr.onerror=function(){SearchApp.saveBlobFailed(t);};" +
        "fr.readAsDataURL(b);})" +
        ".catch(function(){SearchApp.saveBlobFailed(t);});" +
        "}catch(e){SearchApp.saveBlobFailed(t);}})(" +
        JSONObject.quote(url) + "," + JSONObject.quote(token) + ");"
    web.evaluateJavascript(js, null)
}

internal fun MainActivity.saveDataImage(url: String) {
    try {
        val comma = url.indexOf(',')
        if (comma < 0) { imageSaveFailed(); return }
        val header = url.substring(5, comma)
        val mime = header.substringBefore(';').ifBlank { "image/png" }
        val body = url.substring(comma + 1)
        val bytes = if (header.contains("base64"))
            android.util.Base64.decode(body, android.util.Base64.DEFAULT)
        else java.net.URLDecoder.decode(body, "UTF-8").toByteArray()
        if (bytes.isEmpty()) { imageSaveFailed(); return }
        writeImage(bytes, mime)
    } catch (e: Throwable) {
        imageSaveFailed()
    }
}

internal fun MainActivity.writeImage(bytes: ByteArray, mime: String) {
    val ext = when {
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        else -> "jpg"
    }
    val name = "IMG_" + System.currentTimeMillis() + "." + ext
    try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                put(
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/Search"
                )
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: run { imageSaveFailed(); return }
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                ), "Search"
            )
            if (!dir.exists()) dir.mkdirs()
            val f = java.io.File(dir, name)
            java.io.FileOutputStream(f).use { it.write(bytes) }
            // Without the scan the file is on disk but no gallery lists it,
            // which reads to the user as another failed save.
            android.media.MediaScannerConnection.scanFile(
                this, arrayOf(f.absolutePath), arrayOf(mime), null
            )
        }
        runOnUiThread { toast("Image saved to Pictures/Search") }
    } catch (e: Exception) {
        imageSaveFailed()
    }
}

internal fun MainActivity.imageSaveFailed() {
    runOnUiThread { toast("Couldn't save image") }
}

/**
 * Print or save as PDF, through Android's print screen, which offers
 * "Save as PDF" on every phone - as Chrome's Share > Print does. The page
 * is laid out for paper by the page itself (its print stylesheet).
 */
internal fun MainActivity.printPage() {
    val web = activeWeb() ?: return
    val url = tabs.activeTab?.url
    if (url == null || url == homePage || isErrorPage(url)) { toast("Open a page to print it"); return }
    val pm = getSystemService(android.content.Context.PRINT_SERVICE) as? android.print.PrintManager ?: return
    val name = (web.title?.takeIf { it.isNotBlank() } ?: UrlHelper.siteOf(url) ?: "Page").take(80)
    try {
        pm.print(name, web.createPrintDocumentAdapter(name),
            android.print.PrintAttributes.Builder().build())
    } catch (e: Exception) {
        toast("Couldn't print this page")
    }
}

/**
 * A site that asks for a user name and password itself (HTTP sign-in: a
 * router's admin page, a NAS, an office intranet). WebView's default is
 * to refuse without asking, which left these pages blank with no way in.
 * The answer goes to exactly one of proceed or cancel.
 */
internal fun MainActivity.askHttpAuth(handler: android.webkit.HttpAuthHandler, host: String, realm: String) {
    if (isFinishing || isDestroyed) { handler.cancel(); return }
    val d = resources.displayMetrics.density
    val pad = (20 * d).toInt()
    val user = android.widget.EditText(this).apply {
        hint = "User name"
        isSingleLine = true
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }
    val pass = android.widget.EditText(this).apply {
        hint = "Password"
        isSingleLine = true
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(pad, (8 * d).toInt(), pad, 0)
        addView(user); addView(pass)
    }
    var answered = false
    fun answer(ok: Boolean) {
        if (answered) return
        answered = true
        if (ok) handler.proceed(user.text.toString(), pass.text.toString()) else handler.cancel()
    }
    try {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
            .setTitle("Sign in to $host")
            .setMessage(if (realm.isBlank()) "This site asks for a user name and password."
                else "The site says: \u201c" + realm.take(120) + "\u201d")
            .setView(box)
            .setPositiveButton("Sign in") { _, _ -> answer(true) }
            .setNegativeButton("Cancel") { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) }
            .show()
    } catch (e: Exception) {
        answer(false)
    }
}

internal fun MainActivity.toast(msg: String) {
    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.capturedIsSharp(domain: String, dataUrl: String): Boolean {
    val key = domain + ":" + dataUrl.length
    sharpMemo[key]?.let { return it }
    val sharp = try {
        val bytes = android.util.Base64.decode(dataUrl.substringAfter(','), android.util.Base64.DEFAULT)
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        minOf(o.outWidth, o.outHeight) >= 96
    } catch (e: Exception) { false }
    sharpMemo[key] = sharp
    return sharp
}

internal fun MainActivity.storeFavicon(host: String, bmp: android.graphics.Bitmap) {
    try {
        val domain = host.removePrefix("www.")
            .removePrefix("mobile.").removePrefix("m.")
        if (domain.isBlank()) return
        val bytes = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
        bmp.recycle()
        val dataUrl = "data:image/png;base64," + android.util.Base64.encodeToString(
            bytes.toByteArray(), android.util.Base64.NO_WRAP)
        // A 64x64 PNG is a couple of kB; anything far past that is not an
        // icon and is not worth carrying across the bridge.
        // Room for a real 256px icon, which the 40k cap would have
        // thrown away - the very icons worth keeping were the ones being
        // rejected. Paired with a smaller cache below, since all of this
        // is held in SharedPreferences and loaded into memory at once.
        if (dataUrl.length > 60000) return
        val prefs = getSharedPreferences("favicon_cache", Context.MODE_PRIVATE)
        val order = (prefs.getString("__order", "") ?: "")
            .split(",").filter { it.isNotBlank() && it != domain }
            .toMutableList()
        order.add(domain)
        val edit = prefs.edit().putString(domain, dataUrl)
        while (order.size > 40) {
            edit.remove(order.removeAt(0))
        }
        edit.putString("__order", order.joinToString(",")).apply()
    } catch (e: Exception) { /* an icon is not worth a crash */ }
}

/** Re-draws the home tiles in place, with no page reload and no flash. */
internal fun MainActivity.refreshHomeTiles() {
    activeWeb()?.evaluateJavascript(
        "window.__renderTiles && window.__renderTiles()", null)
}

internal fun MainActivity.showAddShortcutDialog() {
    val d = resources.displayMetrics.density
    val input = android.widget.EditText(this).apply {
        hint = "example.com"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine()
    }
    val box = android.widget.FrameLayout(this).apply {
        setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        addView(input)
    }
    com.google.android.material.dialog.MaterialAlertDialogBuilder(
        this, R.style.Theme_Search_Dialog)
        .setTitle("Add shortcut")
        .setView(box)
        .setPositiveButton("Add") { _, _ ->
            val typed = input.text.toString().trim()
            // A phrase is a search, not a site. Without this the tile
            // would be a frozen search results page.
            if (typed.isBlank() || typed.contains(' ')) return@setPositiveButton
            val url = UrlHelper.toUrlOrSearch(typed, Settings.getEngineUrl(this))
            val dom = Shortcuts.domainOf(url)
            if (dom.isBlank()) return@setPositiveButton
            val label = dom.substringBefore('.')
                .replaceFirstChar { it.uppercase() }
            Shortcuts.addCustom(this, label, url)
            refreshHomeTiles()
        }
        .setNegativeButton("Cancel", null)
        .show()
    input.requestFocus()
}

internal fun MainActivity.showRemoveShortcutDialog(label: String, url: String) {
    com.google.android.material.dialog.MaterialAlertDialogBuilder(
        this, R.style.Theme_Search_Dialog)
        .setTitle("Remove " + label.ifBlank { "this shortcut" } + "?")
        .setMessage("It comes off the home page. Your history and bookmarks are not touched.")
        .setPositiveButton("Remove") { _, _ ->
            val dom = Shortcuts.keyOf(url)
            // One the user added is simply forgotten. A built-in or a site
            // from history is generated every render, so the only way to
            // keep it gone is to remember that it was removed.
            val wasCustom = Shortcuts.loadCustom(this)
                .any { Shortcuts.keyOf(it.url) == dom }
            if (wasCustom) Shortcuts.removeCustom(this, url)
            else Shortcuts.hide(this, dom)
            refreshHomeTiles()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/**
 * What the address bar shows at rest: the site, not the address.
 *
 * "google.com" rather than "https://www.google.com/search?q=moviebox&oq=",
 * which is what every other browser does and what makes the bar readable
 * at a glance. Nothing is lost - enterSearchMode fills the bar with the
 * full url and selects it as soon as it is focused, so copying and editing
 * the real address are unchanged.
 *
 * http:// is kept on purpose. Hiding the scheme would make an insecure
 * page look exactly like a secure one, and there is no lock in this bar to
 * carry that distinction. HTTPS-only is on by default and plain http
 * already shows an interstitial first, so arriving on one is deliberate -
 * but the bar should not then imply otherwise. A non-standard port is kept
 * for the same reason: it is part of what you are looking at.
 *
 * Anything that is not a web page is returned untouched. about:, intent:
 * and custom schemes have no host to extract, and rewriting them would
 * hide what the page really is.
 */
internal fun MainActivity.displayUrl(url: String?): String {
    // A page kept for offline reading says so, and where it came from.
    OfflinePages.idOf(url)?.let { id ->
        return "Offline" + (OfflinePages.siteOf(id)?.takeIf { it.isNotBlank() }?.let { " \u00B7 $it" } ?: "")
    }
    if (url == null || url.startsWith("file:///android_asset/")) return ""
    val uri = try {
        android.net.Uri.parse(url)
    } catch (e: Exception) {
        return url
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return url
    val host = uri.host ?: return url
    if (host.isEmpty()) return url
    val shown = host.removePrefix("www.") +
        (if (uri.port > 0) ":" + uri.port else "")
    return if (scheme == "http") "http://" + shown else shown
}

internal fun MainActivity.captureThumbnail(tab: Tab, onDone: (() -> Unit)? = null) {
    val web = tab.webView
    if (deckVisible || web == null || web.width <= 0 || web.height <= 0 ||
        !web.isAttachedToWindow || binding.webContainer.translationX != 0f
    ) { onDone?.invoke(); return }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        try {
            // Allocate the destination at reduced resolution; PixelCopy
            // scales the full-size source rect down into it. Clamp to >=1px
            // so a tiny view never produces a zero-sized bitmap.
            val dstW = (web.width / thumbCaptureDivisor).coerceAtLeast(1)
            val dstH = (web.height / thumbCaptureDivisor).coerceAtLeast(1)
            val source = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.RGB_565)
            val location = IntArray(2)
            web.getLocationInWindow(location)
            val rect = android.graphics.Rect(
                location[0], location[1],
                location[0] + web.width, location[1] + web.height
            )
            PixelCopy.request(
                window, rect, source,
                { result ->
                    if (result == PixelCopy.SUCCESS && !deckVisible) storeScaled(tab, source)
                    else source.recycle()
                    onDone?.invoke()
                },
                Handler(Looper.getMainLooper())
            )
            return
        } catch (e: Exception) { /* fall through */ }
    }
    onDone?.invoke()
}

internal fun MainActivity.storeScaled(tab: Tab, full: Bitmap) {
    try {
        val ratio = full.height.toFloat() / full.width.toFloat()
        val targetH = (thumbWidthPx * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(full, thumbWidthPx, targetH, true)
        if (scaled != full) full.recycle()
        tab.thumbnail?.recycle()
        tab.thumbnail = scaled
    } catch (e: Exception) { if (!full.isRecycled) full.recycle() }
}

/** The tab a WebView belongs to - not necessarily the one on screen. */
internal fun MainActivity.tabOf(web: WebView?): Tab? =
    if (web == null) null else tabs.tabs.firstOrNull { it.webView === web }

/** scheme://host[:port] of [url], the key site settings are kept under; null for anything not a web page. */
internal fun MainActivity.originOf(url: String?): String? {
    val u = try { android.net.Uri.parse(url ?: return null) } catch (e: Exception) { return null }
    val scheme = u.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return null
    val host = u.host?.lowercase() ?: return null
    return scheme + "://" + host + (if (u.port > 0) ":" + u.port else "")
}

/**
 * A pop-up the blocker refused: said once in a while (a page that keeps
 * trying is told once, not every time), with the way to let this site
 * open them - as Chrome's "Pop-ups blocked" does. The window asked for
 * cannot be brought back, so the site is asked to try again.
 */
internal fun MainActivity.offerPopupAllow(view: WebView?, origin: String?) {
    if (origin == null || view == null) return
    uiHandler.post {
        if (isFinishing || isDestroyed || view !== activeWeb()) return@post
        val now = android.os.SystemClock.uptimeMillis()
        if (now - popupNoticeAt < 15_000L) return@post
        popupNoticeAt = now
        val host = try { android.net.Uri.parse(origin).host?.removePrefix("www.") } catch (e: Exception) { null } ?: return@post
        try {
            com.google.android.material.snackbar.Snackbar
                .make(binding.webArea, "Pop-up blocked", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("Allow on this site") {
                    SitePermissions.set(this, origin, SitePermissions.POPUPS, SitePermissions.ALLOW)
                    toast("Pop-ups allowed on " + host + ". Try again.")
                }
                .setActionTextColor(currentAccent())
                .show()
        } catch (e: Exception) { /* no window to show it in */ }
    }
}

/**
 * Closes a tab a page opened, if nothing ever loaded in it. Run after the
 * callback that decided its first navigation was not ours to load.
 */
internal fun MainActivity.closeIfNeverLoaded(web: WebView?) {
    val target = web ?: return
    uiHandler.post {
        val tab = tabOf(target) ?: return@post
        // about:blank is what onCreateWindow names the tab, and
        // onPageStarted replaces it as soon as a page starts loading.
        if (tab.openerId != null && tab.url == "about:blank") closeTabFromDeck(tab)
    }
}

/**
 * Throws away a WebView whose renderer has gone, and puts the tab's page
 * back in a new one if it is the tab on screen. Background tabs are left
 * frozen and reload when next opened, as any frozen tab does.
 *
 * The dead view is only removed and destroyed - WebView's own instruction,
 * since anything else called on it may reach the renderer that is gone.
 */
internal fun MainActivity.replaceDeadWebView(dead: WebView) {
    val tab = tabOf(dead)
    if (tab != null && tab === tabs.activeTab && fullscreenView != null) {
        // The player's callback belongs to the dead page. Dropped rather
        // than called, then fullscreen is unwound as usual.
        fullscreenCallback = null
        exitFullscreen()
    }
    (dead.parent as? ViewGroup)?.removeView(dead)
    dead.destroy()
    if (tab == null) return
    tab.webView = null
    // State saved by an earlier freeze is older than the page that just
    // died. The address onPageStarted last recorded is where the tab was.
    tab.savedState = null
    // Unless the user ended it for hanging, or it has now taken the
    // renderer down twice in a row - a page too heavy for the phone,
    // killed for memory each time it loads. Reloaded either way it would
    // only do it again, taking every tab with it. It is rebuilt on a page
    // that says so instead, and goes back only when Try again is tapped.
    val now = android.os.SystemClock.uptimeMillis()
    val crashLoop = now - tab.lastRendererLoss < 20_000L
    tab.lastRendererLoss = now
    val ended = tab.id == stoppedTabId
    if (ended) stoppedTabId = null
    if ((ended || crashLoop) &&
        (tab.url.startsWith("http://") || tab.url.startsWith("https://"))
    ) {
        DebugLog.add { "tab#" + tab.id + (if (ended) " ended by the user" else
            " lost its renderer twice in 20s") + "; not reloading it" }
        tab.url = stoppedPage(tab.url, crashed = !ended)
        tab.diskStateHash = 0
    }
    if (tab === tabs.activeTab) {
        // Outside the callback, which WebView is still running for the
        // other tabs this renderer served.
        uiHandler.post {
            if (!isFinishing && !isDestroyed && tab === tabs.activeTab &&
                tab.webView == null
            ) {
                // Ended by the user: if the page put up in its place does
                // not start either, the hang was never in the renderer,
                // and the prompt comes back offering a restart. Longer
                // than for Home, as a new renderer has to start first.
                if (ended) expectPageStart(tab, 6000L)
                openTab(tab)
            }
        }
    }
}

/**
 * A pop-up that has been to another site and come back to its opener's is
 * a sign-in that has returned: the provider is done, and the page now
 * showing is the site's own callback. A working one hands the result to
 * its opener and closes within a second. One that is still there a few
 * seconds later, with nothing on it to use, has stalled - the opener
 * could not be reached, or the close was refused - and that is the blank
 * page the user was left on, signed in but stuck. It is folded back into
 * the tab that opened it, as foldBackPopup describes.
 *
 * Only while the pop-up is the tab on screen, and only if the page really
 * is empty: a site that carries on in the pop-up with a form or a page of
 * its own is left alone.
 */
internal fun MainActivity.watchReturnedPopup(popup: Tab, url: String?) {
    if (!popup.signIn || !popup.leftOpenerSite) return
    val site = UrlHelper.siteOf(url) ?: return
    if (site != popup.openerSite) return
    val web = popup.webView ?: return
    DebugLog.add { "pop-up tab#" + popup.id + " is back on " + site + "; watching for a stall" }
    checkStalledPopup(popup, web, popup.loadToken, emptyLooks = 0, attempt = 0)
}

/**
 * One look at a returned pop-up. It is folded back only after two looks,
 * [POPUP_STALL_MS] and then [POPUP_RECHECK_MS] apart, both finding a page
 * that has finished loading and has nothing on it: a callback that is
 * still exchanging its code over a slow connection gets the second look's
 * worth of time, and one that is still loading is never judged at all.
 * Any new load in the pop-up, or leaving it, ends the watch; coming back
 * to it starts a new one (openTab).
 */
internal fun MainActivity.checkStalledPopup(popup: Tab, web: WebView, token: Int, emptyLooks: Int, attempt: Int) {
    val delay = if (emptyLooks == 0 && attempt == 0) POPUP_STALL_MS else POPUP_RECHECK_MS
    uiHandler.postDelayed({
        if (isFinishing || isDestroyed) return@postDelayed
        if (popup.loadToken != token || popup.webView !== web) return@postDelayed
        if (popup !== tabs.activeTab || !tabs.tabs.contains(popup)) return@postDelayed
        if (web.progress < 100) {
            if (attempt < 6) checkStalledPopup(popup, web, token, emptyLooks, attempt + 1)
            return@postDelayed
        }
        web.evaluateJavascript(PAGE_EMPTINESS_JS) { result ->
            if (popup.loadToken != token || popup.webView !== web ||
                !tabs.tabs.contains(popup)
            ) return@evaluateJavascript
            val empty = isEmptyPage(result)
            DebugLog.add { "pop-up tab#" + popup.id + " look " + (emptyLooks + 1) + ": " +
                result + (if (empty) " - empty" else " - has content, left alone") }
            if (!empty) return@evaluateJavascript
            if (emptyLooks >= 1) foldBackPopup(popup, "stalled on its callback page")
            else checkStalledPopup(popup, web, token, emptyLooks + 1, attempt + 1)
        }
    }, delay)
}

/**
 * Records where a pop-up has been: whether it has left its opener's site
 * (for the identity provider) and whether anything it loaded was part of
 * a sign-in. Fed from shouldOverrideUrlLoading, which sees redirects, and
 * from onPageStarted, which sees what committed.
 */
internal fun MainActivity.notePopupNavigation(view: WebView?, url: String?) {
    val t = tabOf(view) ?: return
    if (t.openerId == null || url == null) return
    val site = UrlHelper.siteOf(url)
    if (site != null && t.openerSite != null && site != t.openerSite) t.leftOpenerSite = true
    if (!t.signIn && (UrlHelper.looksLikeSignIn(url) || UrlHelper.looksLikePayment(url))) {
        // A payment (PayPal, a wallet, a card check) is the same hand-off
        // as a sign-in: away to the provider and back to the shop, and
        // it gets the same watch for a blank page on the way back.
        t.signIn = true
        DebugLog.add { "pop-up tab#" + t.id + " is a sign-in or payment (" + DebugLog.url(url) + ")" }
    }
}

/** A sign-in pop-up that went to its provider and is back on the opener's site. */
internal fun MainActivity.returnedFromSignIn(popup: Tab): Boolean =
    popup.signIn && popup.leftOpenerSite && popup.openerSite != null &&
        UrlHelper.siteOf(popup.url) == popup.openerSite

/** Nothing to press and next to nothing to read: "Signing you in..." at most. */
internal fun MainActivity.isEmptyPage(json: String?): Boolean = try {
    val o = JSONObject(json ?: "")
    o.optInt("controls", 1) == 0 && o.optInt("text", 999) < 80
} catch (e: Exception) { false }

/**
 * Closes a sign-in pop-up and puts the user back on the page that opened
 * it - and, when the sign-in came back without that page ever hearing of
 * it, reloads that page.
 *
 * The session a sign-in leaves behind is a cookie or local storage on the
 * site, so a fresh load of the site is signed in. That is the "close it
 * and open the site again" users were having to do by hand. The reload is
 * skipped when the opener has already started a load of its own since the
 * pop-up opened, because then it did hear, and is on its way.
 */
internal fun MainActivity.foldBackPopup(popup: Tab, why: String) {
    if (!tabs.tabs.contains(popup)) return
    val opener = tabs.tabs.firstOrNull { it.id == popup.openerId && it !== popup }
    val returned = returnedFromSignIn(popup)
    // Moved on: started a load of its own since the pop-up opened, or is
    // part-way through one now - which a reload would cancel.
    val openerBusy = (opener?.webView?.progress ?: 100) < 100
    val openerMovedOn = opener != null && (openerBusy ||
        (popup.openerLoadsAtOpen >= 0 && opener.loadsStarted != popup.openerLoadsAtOpen))
    DebugLog.add { "folding pop-up tab#" + popup.id + " back into tab#" + opener?.id +
        " (" + why + "); returned=" + returned + " openerMovedOn=" + openerMovedOn }
    val openerWasLive = opener?.webView != null
    closeTabFromDeck(popup)
    if (opener == null || !returned) return
    if (openerMovedOn) {
        // It heard, and started loading. Make sure it got somewhere.
        watchOpenerAfterSignIn(opener)
        return
    }
    // A frozen opener needs nothing more: it loads afresh whenever it is
    // next opened, which closeTabFromDeck has just done if the pop-up was
    // the tab on screen.
    if (!openerWasLive) return
    val web = opener.webView ?: return
    loadInto(web, web.url ?: opener.url)
}

/**
 * After a sign-in pop-up has returned, the page that opened it should show
 * the result - by loading a new page, or by redrawing itself. One that
 * has done neither a few seconds later, and is showing nothing at all, is
 * stuck on the way, and a fresh load of it is signed in. A page that
 * moved on, or that has anything on it, is left exactly as it is.
 */
internal fun MainActivity.watchOpenerAfterSignIn(opener: Tab) {
    val web = opener.webView ?: return
    val token = opener.loadToken
    DebugLog.add { "watching tab#" + opener.id + " after its sign-in pop-up returned" }
    uiHandler.postDelayed({
        if (isFinishing || isDestroyed) return@postDelayed
        if (opener.webView !== web || !tabs.tabs.contains(opener)) return@postDelayed
        web.evaluateJavascript(PAGE_EMPTINESS_JS) { result ->
            val empty = isEmptyPage(result)
            DebugLog.add { "tab#" + opener.id + " after sign-in: " + result +
                (if (empty) " - blank, reloading" else " - fine") }
            if (empty && opener.webView === web && tabs.tabs.contains(opener) &&
                opener.loadToken == token && web.progress >= 100
            ) loadInto(web, web.url ?: opener.url)
        }
    }, OPENER_STALL_MS)
}

// ---------- Offline pages ----------

internal fun MainActivity.savePageOffline() {
    val url = webPageUrl() ?: run { needAPage(); return }
    if (nightOwl) { toast("Night Owl keeps nothing. Turn it off to save pages offline."); return }
    val web = activeWeb() ?: return
    val ua = web.settings.userAgentString
    val withImages = !imagesBlocked()
    toast("Saving for offline…")
    try {
        web.evaluateJavascript(OfflinePages.JS) { raw ->
            if (isFinishing || isDestroyed) return@evaluateJavascript
            val page = OfflinePages.parseCapture(raw)
            if (page == null) { toast("This page couldn't be saved"); return@evaluateJavascript }
            Thread {
                val imgs = if (withImages) try {
                    OfflinePages.fetchImages(page.imgs, ua, url) { u ->
                        try { android.webkit.CookieManager.getInstance().getCookie(u) } catch (e: Exception) { null }
                    }
                } catch (e: Throwable) { List(page.imgs.size) { null } } else List(page.imgs.size) { null }
                val entry = try { OfflinePages.save(this, page, imgs) } catch (e: Throwable) { null }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (entry == null) { toast("Couldn't save the page. Is the phone's storage full?"); return@runOnUiThread }
                    try {
                        com.google.android.material.snackbar.Snackbar
                            .make(binding.webArea, "Saved for offline reading",
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            .setAction("View all") { showOfflinePages() }
                            .setActionTextColor(currentAccent())
                            .show()
                    } catch (e: Exception) { toast("Saved for offline reading") }
                }
            }.start()
        }
    } catch (e: Exception) {
        toast("This page couldn't be saved")
    }
}

internal fun MainActivity.showOfflinePages() {
    Thread {
        val list = try { OfflinePages.list(this) } catch (e: Exception) { emptyList() }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (list.isEmpty()) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
                    .setTitle("No offline pages yet")
                    .setMessage("On any page, open Menu > Page tools > Save offline. The page stays " +
                        "readable here with no connection.")
                    .setPositiveButton("OK", null)
                    .show()
                return@runOnUiThread
            }
            val fmt = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            val labels = list.map { e ->
                e.title.ifBlank { e.site } + "\n" + e.site + " · " + fmt.format(java.util.Date(e.savedAt))
            }.toTypedArray<CharSequence>()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
                .setTitle("Offline pages")
                .setItems(labels) { _, which ->
                    list.getOrNull(which)?.let { leaveTransientUi(); addNewTab(OfflinePages.urlFor(it.id)) }
                }
                .setNeutralButton("Remove all") { _, _ -> confirmRemoveOfflinePages(list.size) }
                .setNegativeButton("Close", null)
                .show()
        }
    }.start()
}

internal fun MainActivity.confirmRemoveOfflinePages(n: Int) {
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
        .setTitle("Remove all offline pages?")
        .setMessage(n.toString() + (if (n == 1) " page" else " pages") + " will be removed from this phone.")
        .setPositiveButton("Remove") { _, _ ->
            Thread { try { OfflinePages.deleteAll(this) } catch (e: Exception) {} }.start()
            toast("Offline pages removed")
        }
        .setNegativeButton("Cancel", null)
        .show()
}

// ---------- Risky downloads ----------

/**
 * What is worth a word before [url] is saved as [name]: an app or a
 * program, and a file sent without https, which anyone on the way could
 * have changed. Settings > Security > Warn about risky downloads.
 */
internal fun MainActivity.downloadRisks(url: String, name: String): List<String> {
    if (!Settings.getBool(this, Settings.SEC_RISKY_DOWNLOADS, true)) return emptyList()
    val out = ArrayList<String>()
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext in RISKY_EXT) {
        out.add(if (ext.startsWith("apk") || ext == "xapk")
            "Apps from the web can harm your phone. Install only from sites you trust."
        else "Programs and scripts can harm your devices. Open this only if you trust the site.")
    }
    if (url.startsWith("http://", true)) {
        out.add("This file comes over an insecure connection and could be changed on the way.")
    }
    return out
}

// ---------- A downloaded app (.apk) ----------

internal fun MainActivity.isApk(d: Downloads.Item, mime: String): Boolean {
    if (mime.equals(APK_MIME, true)) return true
    val name = android.net.Uri.decode(d.localUri?.substringAfterLast('/')?.substringBefore('?') ?: "")
    return name.endsWith(".apk", true) || d.title.endsWith(".apk", true)
}

internal fun MainActivity.installApk(d: Downloads.Item, uri: android.net.Uri) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallApps()) {
        askToAllowInstalls(d)
        return
    }
    if (!startViewer(uri, APK_MIME)) toast("Android's installer couldn't open this file")
}

internal fun MainActivity.canInstallApps(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        try { packageManager.canRequestPackageInstalls() } catch (e: Exception) { false }

/** The owl's ask: allow the browser to hand apps to Android's installer. */
internal fun MainActivity.askToAllowInstalls(d: Downloads.Item) {
    if (isFinishing || isDestroyed) return
    val view = layoutInflater.inflate(R.layout.dialog_install_apps, null)
    applyOwlArtIn(view)
    view.findViewById<android.widget.TextView>(R.id.installMessage).text =
        "To install " + d.title.ifBlank { "this app" } + ", Android needs your OK for Search " +
        "to open app files. Turn on \u201cAllow from this source\u201d, then come back " +
        "and the install starts. Only install apps from sources you trust."
    val dialog = android.app.AlertDialog.Builder(this).setView(view).create()
    dialog.window?.setBackgroundDrawable(
        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    dialog.window?.attributes?.windowAnimations = R.style.OwlDialogAnim
    view.findViewById<android.widget.TextView>(R.id.installNotNow).setOnClickListener {
        Haptics.tap(it)
        dialog.dismiss()
    }
    view.findViewById<android.widget.TextView>(R.id.installAllow).setOnClickListener {
        Haptics.tap(it)
        dialog.dismiss()
        getSharedPreferences(APK_PREFS, Context.MODE_PRIVATE).edit()
            .putLong("id", d.id).putBoolean("managed", d.managed)
            .putString("local", d.localUri).putLong("at", System.currentTimeMillis())
            .apply()
        val opened = try {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + packageName)))
            true
        } catch (e: Exception) {
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                true
            } catch (e2: Exception) { false }
        }
        if (!opened) {
            clearPendingApk()
            toast("Turn on Install unknown apps for Search in Android's settings")
        }
    }
    dialog.show()
    dialog.window?.let { w ->
        val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/** Back from the install switch (onResume): with it on, the install carries on. */
internal fun MainActivity.resumePendingApkInstall() {
    val p = getSharedPreferences(APK_PREFS, Context.MODE_PRIVATE)
    if (!p.contains("id")) return
    val at = p.getLong("at", 0L)
    val id = p.getLong("id", -1L)
    val managed = p.getBoolean("managed", true)
    val local = p.getString("local", null)
    // One go: on or off, the question has been answered.
    clearPendingApk()
    if (System.currentTimeMillis() - at !in 0L..APK_PENDING_MS) return
    if (!canInstallApps()) return
    Thread {
        val d = try {
            Downloads.load(this).firstOrNull { it.id == id && it.managed == managed && it.localUri == local }
                ?: Downloads.load(this).firstOrNull { it.id == id && it.managed == managed }
        } catch (e: Exception) { null }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val uri = d?.takeIf { it.isComplete }?.let { resolveDownloadUri(it) }
            if (d == null || uri == null) toast("That file is no longer on this phone")
            else if (!startViewer(uri, APK_MIME)) toast("Android's installer couldn't open this file")
        }
    }.start()
}

internal fun MainActivity.clearPendingApk() {
    getSharedPreferences(APK_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}

/** A failed download: offer to fetch it again. */
internal fun MainActivity.confirmRetryDownload(d: Downloads.Item) {
    com.google.android.material.dialog.MaterialAlertDialogBuilder(
        this, R.style.Theme_Search_Dialog)
        .setTitle("Download failed")
        .setMessage(d.title.ifBlank { "This file" } + " didn't finish. Try it again?")
        .setPositiveButton("Retry") { _, _ -> retryDownload(d) }
        .setNegativeButton("Cancel", null)
        .show()
}

/**
 * Fetches a failed download again from where it came from, under the same
 * name and type, through the same free-space check as a new one and with
 * the site's cookies, so a file behind a sign-in still arrives. The failed
 * record goes once the new one is queued; it holds no file.
 */
internal fun MainActivity.retryDownload(d: Downloads.Item) {
    val url = d.remoteUri ?: return
    val free = freeDownloadSpace()
    if (downloadRoom(d.bytesTotal, free) == ROOM_NONE) {
        showNoRoomForDownload(d.title, d.bytesTotal, free)
        return
    }
    try {
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
        val name = d.title.ifBlank { "download" }
        request.setMimeType(downloadMime(name, d.mimeType))
        request.addRequestHeader("User-Agent",
            android.webkit.WebSettings.getDefaultUserAgent(this))
        request.setTitle(name)
        request.setDescription("Downloading\u2026")
        request.setNotificationVisibility(
            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(
            android.os.Environment.DIRECTORY_DOWNLOADS, name)
        request.setAllowedOverMetered(true)
        request.setAllowedOverRoaming(true)
        val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        cookieIo.execute {
            val ok = try {
                android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                    if (it.isNotBlank()) request.addRequestHeader("Cookie", it)
                }
                dm.enqueue(request)
                try { dm.remove(d.id) } catch (e: Exception) {}
                true
            } catch (e: Exception) { false }
            runOnUiThread {
                HealthStats.count(this,
                    if (ok) HealthStats.DOWNLOADS else HealthStats.DOWNLOAD_FAILURES)
                toast(if (ok) "Downloading " + name else "Download failed")
                if (ok) refreshDownloads()
            }
        }
    } catch (e: Exception) {
        HealthStats.count(this, HealthStats.DOWNLOAD_FAILURES)
        toast("Download failed")
    }
}

internal fun MainActivity.startViewer(uri: android.net.Uri, mime: String): Boolean = try {
    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    })
    true
} catch (e: Exception) {
    false
}

/** A URI another app can read, or null if the file is gone. */
internal fun MainActivity.resolveDownloadUri(d: Downloads.Item): android.net.Uri? {
    // DownloadManager's own content:// URI is the first choice: it is
    // readable by another app as soon as the read grant is attached. Only
    // worth asking for a download it actually handled.
    if (d.managed) {
        try {
            val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val own = dm.getUriForDownloadedFile(d.id)
            if (own != null && own.scheme.equals("content", true)) return own
        } catch (e: Exception) { /* fall through to the stored path */ }
    }

    val parsed = try {
        android.net.Uri.parse(d.localUri ?: return null)
    } catch (e: Exception) { return null }
    if (parsed.scheme.equals("content", true)) return parsed

    // A file:// path. Wrap it rather than pass it: the raw URI is exactly
    // what the platform refuses to let another app receive.
    val file = java.io.File(parsed.path ?: return null)
    if (!file.exists()) return null
    return try {
        androidx.core.content.FileProvider.getUriForFile(
            this, packageName + ".files", file)
    } catch (e: Exception) { null }
}

/**
 * A type a viewer will actually match on. Servers frequently send nothing
 * useful - blank, or octet-stream for a perfectly ordinary PDF - and that
 * is a type no app claims, so the extension wins whenever the stored one
 * says nothing.
 */
internal fun MainActivity.mimeForDownload(d: Downloads.Item): String {
    val declared = d.mimeType?.trim().orEmpty()
    val useful = declared.isNotBlank() &&
        !declared.equals("*/*", true) &&
        !declared.equals("application/octet-stream", true) &&
        !declared.equals("binary/octet-stream", true)
    if (useful) return declared
    val name = (d.localUri?.substringAfterLast('/')?.substringBefore('?') ?: d.title)
    val ext = android.net.Uri.decode(name).substringAfterLast('.', "").lowercase()
    if (ext.isNotBlank()) {
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext)?.let { return it }
    }
    return declared.ifBlank { "*/*" }
}

// ---------- Downloads ----------

/**
 * Returns a tab to the page it is actually showing, after a navigation
 * turned out to be a download.
 *
 * The committed history entry is the truth here: a navigation that became a
 * download never commits, so currentItem still names the page the user is
 * looking at, while web.url can briefly report the address being fetched.
 */
internal fun MainActivity.restoreAfterDownload(web: WebView) {
    val tab = tabs.tabs.firstOrNull { it.webView === web } ?: return
    val committed = try {
        web.copyBackForwardList().currentItem?.url
    } catch (e: Exception) { null }
    val real = committed ?: web.url ?: homePage
    tab.url = real
    (web as? BrowserWebView)?.pageHost =
        try { android.net.Uri.parse(real).host } catch (e: Exception) { null }
    if (web === activeWeb()) {
        if (!binding.urlBar.hasFocus()) binding.urlBar.setText(displayUrl(real))
        refreshOmniboxVisibility(real)
    }
}

/**
 * Security gate: every download — whether the user tapped it or a site
 * triggered it silently — must be confirmed here before it proceeds.
 * This stops websites from secretly downloading files to the device.
 */
internal fun MainActivity.startDownload(
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
    contentLength: Long = -1L
) {
    val fileName = downloadName(url, contentDisposition, mimeType)
    // Space first: a file that cannot fit is stopped here, with the
    // reason, rather than failing part-way in DownloadManager.
    val free = freeDownloadSpace()
    val room = downloadRoom(contentLength, free)
    if (room == ROOM_NONE) {
        showNoRoomForDownload(fileName, contentLength, free)
        return
    }
    // If the user disabled download confirmation, download straight away -
    // unless the file would take most of the space left, which is worth a
    // word first whatever the setting.
    // Settings > Security > Warn about risky downloads: said even with
    // confirmation off, since it is the one case the question matters.
    val risks = downloadRisks(url, fileName)
    if (!Settings.getBool(this, Settings.SEC_CONFIRM_DOWNLOADS, true) && room == ROOM_OK &&
        risks.isEmpty()) {
        performDownload(url, userAgent, contentDisposition, mimeType)
        return
    }

    val view = layoutInflater.inflate(R.layout.dialog_download, null)
    applyOwlArtIn(view)
    view.findViewById<android.widget.TextView>(R.id.dlFileName).text = fileName
    val fmt = { b: Long -> android.text.format.Formatter.formatShortFileSize(this, b) }
    view.findViewById<android.widget.TextView>(R.id.dlSize).text =
        (if (contentLength > 0) fmt(contentLength) else "Size unknown") +
            " \u00B7 to Downloads" +
            (if (free >= 0) " \u00B7 " + fmt(free) + " free" else "")
    val warnings = ArrayList<String>()
    if (room == ROOM_TIGHT) warnings.add("This will use most of your free space.")
    warnings.addAll(risks)
    if (warnings.isNotEmpty()) {
        view.findViewById<android.widget.TextView>(R.id.dlSpaceWarn).apply {
            text = warnings.joinToString("\n")
            visibility = View.VISIBLE
        }
        (view.findViewById<android.widget.TextView>(R.id.dlSize).layoutParams
            as? android.widget.LinearLayout.LayoutParams)?.bottomMargin =
            (6 * resources.displayMetrics.density).toInt()
    }

    val dialog = android.app.AlertDialog.Builder(this)
        .setView(view)
        .setCancelable(false)
        .create()
    // Transparent window so our rounded layout shows cleanly.
    dialog.window?.setBackgroundDrawable(
        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
    )

    view.findViewById<android.widget.TextView>(R.id.dlConfirm).setOnClickListener {
        dialog.dismiss()
        performDownload(url, userAgent, contentDisposition, mimeType)
    }
    view.findViewById<android.widget.TextView>(R.id.dlCancel).setOnClickListener {
        dialog.dismiss()
        android.widget.Toast.makeText(
            this, "Download cancelled", android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    dialog.show()
    // Constrain the dialog to a compact card width.
    dialog.window?.let { w ->
        val dm = resources.displayMetrics
        val width = (dm.widthPixels * 0.86f).toInt()
        w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

// ---------- Space for downloads ----------

/**
 * Free space where downloads go (the phone's shared storage), or -1 if it
 * cannot be read. A statfs call: cheap enough for the main thread.
 */
internal fun MainActivity.freeDownloadSpace(): Long = try {
    @Suppress("DEPRECATION")
    android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
} catch (e: Exception) {
    try { android.os.StatFs(filesDir.path).availableBytes } catch (e2: Exception) { -1L }
}

/**
 * Whether a [size]-byte file fits in [free] bytes. It does not if it would
 * leave less than 200MB, which Android needs to keep running smoothly; it
 * is tight if it takes half the space or leaves under 1GB. An unknown size
 * or free space is taken as fitting - nothing is stopped on a guess.
 */
internal fun MainActivity.downloadRoom(size: Long, free: Long): Int {
    if (size <= 0L || free < 0L) return ROOM_OK
    val reserve = 200L * 1024 * 1024
    if (size > free - reserve) return ROOM_NONE
    if (size * 2 >= free || free - size < 1024L * 1024 * 1024) return ROOM_TIGHT
    return ROOM_OK
}

internal fun MainActivity.showNoRoomForDownload(fileName: String, size: Long, free: Long) {
    val fmt = { b: Long -> android.text.format.Formatter.formatShortFileSize(this, b) }
    com.google.android.material.dialog.MaterialAlertDialogBuilder(
        this, R.style.Theme_Search_Dialog)
        .setTitle("Not enough storage")
        .setMessage(fileName + " is " + fmt(size) + ", and this phone has " +
            fmt(free.coerceAtLeast(0L)) + " free. Free up some space, then try again.")
        .setPositiveButton("OK", null)
        .setNeutralButton("Storage settings") { _, _ ->
            try {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            } catch (e: Exception) { /* no such screen on this phone */ }
        }
        .show()
}

internal fun MainActivity.performDownload(
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?
) {
    // On Android 9 and below, writing to public Downloads needs the runtime
    // WRITE_EXTERNAL_STORAGE grant. Request it and resume after grant.
    // Android 10+ (API 29+) doesn't need it, so skip the check there.
    if (android.os.Build.VERSION.SDK_INT <= 28) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingDownload = PendingDownload(url, userAgent, contentDisposition, mimeType)
            storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
    }
    // DownloadManager fetches over http and https and nothing else. A blob:
    // or data: address is the page's own in-memory data, which no other
    // process can fetch at all - so it is read out through the page and
    // written here instead of handed over. Without this, every export out
    // of a web app ended in "Download failed".
    if (url.startsWith("data:", true)) {
        toast("Saving\u2026")
        val name = downloadName(url, contentDisposition, mimeType)
        Thread { saveDataDownload(url, name, mimeType) }.start()
        return
    }
    if (url.startsWith("blob:", true)) {
        toast("Saving\u2026")
        requestBlobDownload(url, downloadName(url, contentDisposition, mimeType), mimeType)
        return
    }
    try {
        val fileName = downloadName(url, contentDisposition, mimeType)
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
        // Not the declared type: a host that says application/octet-stream
        // for an mp3 has the file registered as binary, and no player
        // claims it. downloadMime keeps a real type and replaces a generic
        // one with whatever the extension says.
        request.setMimeType(downloadMime(fileName, mimeType))
        userAgent?.let { request.addRequestHeader("User-Agent", it) }
        // Some hosts refuse a request that arrives with no referer. Read
        // here: a WebView answers on the main thread only.
        activeWeb()?.url?.let {
            if (it.startsWith("http")) request.addRequestHeader("Referer", it)
        }
        request.setTitle(fileName)
        request.setDescription("Downloading…")
        request.setNotificationVisibility(
            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        request.setDestinationInExternalPublicDir(
            android.os.Environment.DIRECTORY_DOWNLOADS, fileName
        )
        // Stated outright: on any network, roaming included. A large file
        // is fetched by DownloadManager in its own process, so it neither
        // holds this app's memory nor stops when the app is closed, and it
        // resumes by itself after the network drops.
        request.setAllowedOverMetered(true)
        request.setAllowedOverRoaming(true)
        val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val referer = activeWeb()?.url
        // DownloadManager fetches in its own process, with none of the
        // WebView's state. Without the session cookie, anything behind a
        // sign-in hands back the sign-in page instead of the file, and the
        // download "succeeds" - you get a saved HTML page named report.pdf.
        //
        // getCookie() waits on Chromium's cookie thread, so it is asked on
        // the cookie thread's queue here rather than on the main thread,
        // and the enqueue follows it there.
        cookieIo.execute {
            val cookie = try {
                android.webkit.CookieManager.getInstance().getCookie(url)
            } catch (e: Exception) { null }
            val ok = try {
                cookie?.let { if (it.isNotBlank()) request.addRequestHeader("Cookie", it) }
                dm.enqueue(request)
                true
            } catch (e: Exception) { false }
            runOnUiThread {
                if (ok) {
                    HealthStats.count(this, HealthStats.DOWNLOADS)
                    toast("Downloading $fileName")
                    return@runOnUiThread
                }
                // DownloadManager would not take it (switched off on this
                // phone, or refusing the address): the app fetches it
                // itself, streamed to Downloads, rather than giving up.
                ensureNotifPermission()
                toast("Downloading $fileName")
                HttpSave.start(this, url, userAgent, referer, cookie, fileName,
                    downloadMime(fileName, mimeType) ?: "application/octet-stream") { saved ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        HealthStats.count(this,
                            if (saved) HealthStats.DOWNLOADS else HealthStats.DOWNLOAD_FAILURES)
                        toast(if (saved) "Saved to Downloads: $fileName" else "Download failed")
                        if (downloadsOpen) refreshDownloads()
                    }
                }
            }
        }
    } catch (e: Exception) {
        HealthStats.count(this, HealthStats.DOWNLOAD_FAILURES)
        android.widget.Toast.makeText(
            this, "Download failed", android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

internal fun MainActivity.downloadFailed() {
    runOnUiThread {
        HealthStats.count(this, HealthStats.DOWNLOAD_FAILURES)
        toast("Couldn't save the file")
    }
}

/**
 * The owl, summoned for one file.
 *
 * Deliberately not a PopupMenu. Deleting a download is the one thing in
 * this app that destroys something the user cannot get back -
 * DownloadManager.remove takes the file off the disk, not just the row
 * off the list - and the menu this replaces did it on a single tap, from
 * a long-press nothing advertised. Naming the file and making the choice
 * explicit is the confirmation that was missing; the owl is the app's own
 * voice rather than a system menu's.
 */
internal fun MainActivity.showDownloadActions(d: Downloads.Item) {
    val view = layoutInflater.inflate(R.layout.dialog_download_actions, null)
    applyOwlArtIn(view)
    // Middle ellipsis, not the end. The extension is the most useful part
    // of a long filename - it is what tells you the thing is an mp3 - and
    // trimming from the right is exactly what throws it away.
    view.findViewById<android.widget.TextView>(R.id.dlaName).text =
        d.title.ifBlank { "this file" }

    val dialog = android.app.AlertDialog.Builder(this).setView(view).create()
    dialog.window?.setBackgroundDrawable(
        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
    )
    dialog.window?.attributes?.windowAnimations = R.style.OwlDialogAnim

    view.findViewById<android.widget.TextView>(R.id.dlaShare).setOnClickListener {
        dialog.dismiss()
        shareDownload(d)
    }
    view.findViewById<android.widget.TextView>(R.id.dlaDelete).setOnClickListener {
        dialog.dismiss()
        deleteDownload(d)
        refreshDownloads()
        toast("Gone")
    }
    view.findViewById<android.widget.TextView>(R.id.dlaCancel).setOnClickListener {
        dialog.dismiss()
    }
    dialog.show()
    dialog.window?.let { w ->
        val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/**
 * Hands a finished download to whatever the user picks.
 *
 * The same two things that broke opening a file break sharing one, for
 * the same reasons: another app cannot read a file:// URI - that has
 * thrown FileUriExposedException since Android 7 - and the stored media
 * type is frequently blank or octet-stream, which no share target claims.
 * resolveDownloadUri and mimeForDownload already answer both, so this
 * leans on them rather than growing a second, slightly different answer
 * that will drift from the first.
 */
internal fun MainActivity.shareDownload(d: Downloads.Item) {
    if (!d.isComplete) {
        toast(if (d.isRunning) "Still downloading\u2026" else "File not available")
        return
    }
    val uri = resolveDownloadUri(d)
    if (uri == null) {
        toast("That file is no longer on this phone")
        return
    }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mimeForDownload(d)
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        // Without this the chooser lists apps that then cannot open what
        // they were handed, which reads as the other app being broken.
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        startActivity(android.content.Intent.createChooser(send, "Share file"))
    } catch (e: Exception) {
        toast("Nothing on this phone can share that")
    }
}

/**
 * Types that mean "a file" and nothing more.
 *
 * Most download hosts send one of these for everything they serve, so a
 * declared type from this list carries no information and must never be
 * allowed to overrule an extension the name already has. Treating
 * application/octet-stream as authoritative is exactly what renamed an
 * mp3 to .bin.
 */
internal fun MainActivity.isGenericMime(m: String?): Boolean {
    val t = m?.substringBefore(';')?.trim()?.lowercase() ?: return true
    return t.isEmpty() || t == "*/*" ||
        t == "application/octet-stream" || t == "binary/octet-stream" ||
        t == "application/unknown" || t == "application/force-download" ||
        t == "application/download" || t == "application/x-download"
}

/**
 * The filename a server stated in Content-Disposition.
 *
 * Parsed here rather than through the platform, whose own regex anchors
 * filename= to the end of the header: a server sending filename= followed
 * by anything at all - a second parameter, a trailing semicolon - yields
 * no name from it and falls through to the address instead.
 *
 * filename* is preferred where both are present. It is the only form that
 * can carry non-ASCII, so it is the accurate one whenever a title has an
 * accent, an apostrophe or a non-Latin script in it.
 */
internal fun MainActivity.dispositionName(cd: String?): String {
    val h = cd ?: return ""
    Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
        .find(h)?.let { m ->
            val charset = m.groupValues[1].trim().ifBlank { "UTF-8" }
            val raw = m.groupValues[2].trim().trim('"')
            val decoded = try {
                java.net.URLDecoder.decode(raw, charset)
            } catch (e: Exception) {
                try { java.net.URLDecoder.decode(raw, "UTF-8") }
                catch (e2: Exception) { raw }
            }
            if (decoded.isNotBlank()) return decoded
        }
    Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        .find(h)?.let {
            if (it.groupValues[1].isNotBlank()) return it.groupValues[1]
        }
    Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
        .find(h)?.let {
            val v = it.groupValues[1].trim().trim('"')
            if (v.isNotBlank()) return v
        }
    return ""
}

/**
 * Safe to hand to setDestinationInExternalPublicDir, which throws on a
 * name containing a path separator. Parentheses and spaces are left alone
 * - they are legal, and stripping them mangles titles for no reason.
 */
internal fun MainActivity.sanitizeName(n: String): String {
    var s = n.substringAfterLast('/').substringAfterLast('\\').trim()
    s = s.replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_")
    s = s.trim('.', ' ')
    if (s.length > 120) {
        val ext = s.substringAfterLast('.', "")
        val stem = s.substringBeforeLast('.', s).take(110)
        s = if (ext.isBlank()) stem else stem + "." + ext
    }
    return s
}

/**
 * Whether the tail after the final dot is an extension or just part of
 * the name. "mp3" is; "com)" - from Shallipopi-Laho-(TrendySongz.com).mp3
 * with its real extension already lost - is not.
 */
internal fun MainActivity.looksLikeExt(name: String): Boolean {
    val e = name.substringAfterLast('.', "")
    return e.length in 1..5 && e.isNotEmpty() && e.all { it.isLetterOrDigit() }
}

/**
 * A filename that keeps what the server called the file and carries an
 * extension the phone can act on.
 *
 * In order of trust: the name the server stated, the last piece of the
 * address, then the platform's guess - which is last because of what it
 * does to a generic content type, and which is still needed for data:
 * URLs, where there is no name anywhere else.
 */
internal fun MainActivity.downloadName(url: String, cd: String?, mime: String?): String {
    val fromUrl = sanitizeName(try {
        android.net.Uri.parse(url).lastPathSegment.orEmpty()
    } catch (e: Exception) { "" })

    var name = sanitizeName(dispositionName(cd))
    if (name.isBlank()) name = fromUrl
    if (name.isBlank()) {
        name = sanitizeName(try {
            android.webkit.URLUtil.guessFileName(url, cd, mime)
        } catch (e: Exception) { "" })
        // .bin is guessFileName's way of saying it does not know. Dropped
        // so the recovery below gets a chance rather than being handed a
        // name that already looks finished.
        if (name.endsWith(".bin", true)) name = name.dropLast(4)
    }
    if (name.isBlank()) name = "download_" + System.currentTimeMillis()

    val map = android.webkit.MimeTypeMap.getSingleton()
    val declared = mime?.substringBefore(';')?.trim()?.lowercase()

    // A REAL declared type that disagrees with the extension on the name
    // wins, and the extension is swapped for the one that type maps to.
    // This is the part guessFileName gets right - a .php that serves audio
    // should land as .mp3 - and it is kept. What is not kept is doing it
    // for a generic type, and cutting the base name at the first dot
    // instead of the last.
    if (looksLikeExt(name) && declared != null && !isGenericMime(declared)) {
        val fromName = map.getMimeTypeFromExtension(
            name.substringAfterLast('.').lowercase())
        if (fromName != null && !fromName.equals(declared, true)) {
            val want = map.getExtensionFromMimeType(declared)
            if (!want.isNullOrBlank()) {
                name = name.substringBeforeLast('.') + "." + want
            }
        }
    }

    if (!looksLikeExt(name)) {
        val urlExt =
            if (looksLikeExt(fromUrl)) fromUrl.substringAfterLast('.') else ""
        val mimeExt = declared
            ?.takeIf { !isGenericMime(it) }
            ?.let { map.getExtensionFromMimeType(it) }
            .orEmpty()
        val ext = urlExt.ifBlank { mimeExt }
        if (ext.isNotBlank()) name = name + "." + ext
    }
    return name
}

/**
 * The type to register the file under.
 *
 * A real declared type is kept. A generic one is replaced by whatever the
 * extension maps to, so an mp3 served as application/octet-stream is
 * stored as audio/mpeg - which is what decides whether a music player
 * will open it and whether the media scanner indexes it at all.
 */
internal fun MainActivity.downloadMime(name: String, declared: String?): String? {
    val clean = declared?.substringBefore(';')?.trim()
    if (!isGenericMime(clean)) return clean
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isBlank()) return clean
    return android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(ext) ?: clean
}

/**
 * Only the page that minted a blob: URL can read it, so the page is asked
 * to: fetch it and hand the bytes back.
 *
 * In pieces of about 2MB, each base64 across the bridge and written to the
 * file as it arrives (blobFileBegin, blobFileChunk, blobFileEnd), read
 * off the response as a stream so the page never makes a second copy of
 * the file either. It used to come across whole, as one data: URL, which
 * took several times the file's size in memory - capped at 16MB for that
 * reason, so any larger export simply failed. Now a file of several GB
 * saves with no more memory than one piece, with a progress notification
 * while it is written.
 */
internal fun MainActivity.requestBlobDownload(url: String, name: String, mime: String?) {
    val web = activeWeb() ?: run { downloadFailed(); return }
    val token = java.util.UUID.randomUUID().toString()
    blobSaves[token] = BlobSave(name, mime)
    ensureNotifPermission()
    web.evaluateJavascript(BLOB_STREAM_JS + "(" +
        JSONObject.quote(url) + "," + JSONObject.quote(token) + ");", null)
}

/**
 * A data: address, decoded a piece at a time straight into the file. The
 * address itself is already in memory (the page handed it over whole),
 * but decoding it whole as well doubled that.
 */
internal fun MainActivity.saveDataDownload(dataUrl: String, name: String, mime: String?) {
    val comma = dataUrl.indexOf(',')
    if (comma < 5) { downloadFailed(); return }
    val header = dataUrl.substring(5, comma)
    val type = mime?.takeIf { it.isNotBlank() }
        ?: header.substringBefore(';').ifBlank { "application/octet-stream" }
    val sink = DownloadSink.open(this, name, type) ?: run { downloadFailed(); return }
    try {
        if (header.contains("base64", true)) {
            var body = dataUrl.substring(comma + 1)
            if (body.any { it.isWhitespace() }) body = body.filterNot { it.isWhitespace() }
            // A multiple of four characters, so every piece decodes on its own.
            val step = 4 * 1024 * 1024
            var i = 0
            while (i < body.length) {
                val j = minOf(i + step, body.length)
                sink.write(android.util.Base64.decode(body.substring(i, j), android.util.Base64.DEFAULT))
                i = j
            }
        } else {
            sink.write(java.net.URLDecoder.decode(dataUrl.substring(comma + 1), "UTF-8").toByteArray())
        }
        val uri = if (sink.written > 0) sink.finish() else { sink.abort(); null }
        if (uri == null) { downloadFailed(); return }
        SavedFiles.add(this, sink.name, uri, sink.mime, sink.written)
        runOnUiThread { toast("Saved to Downloads: " + sink.name) }
    } catch (t: Throwable) {
        sink.abort()
        downloadFailed()
    }
}

// ---------- Video downloader ----------

internal fun MainActivity.videoButtonOn() = Settings.getBool(this, Settings.VIDEO_BUTTON, true)

internal fun MainActivity.scheduleVideoScan(delay: Long) {
    videoScans = 0
    uiHandler.removeCallbacks(videoScan)
    uiHandler.postDelayed(videoScan, delay)
}

/** Asks the page on screen for its videos; [then] runs once the answer is in. */
internal fun MainActivity.scanVideos(then: (() -> Unit)?) {
    val web = activeWeb()
    val url = web?.url
    if (web == null || url == null || url == homePage ||
        !(url.startsWith("https://") || url.startsWith("http://"))) {
        setFoundVideos(emptyList(), url)
        then?.invoke()
        return
    }
    val host = try { android.net.Uri.parse(url).host } catch (e: Exception) { null }
    if (VideoFinder.pageExcluded(host)) {
        setFoundVideos(emptyList(), url)
        then?.invoke()
        return
    }
    videoScans++
    try {
        web.evaluateJavascript(VideoFinder.JS) { raw ->
            if (isFinishing || isDestroyed || web !== activeWeb() || web.url != url) return@evaluateJavascript
            val found = VideoFinder.parse(raw, url)
            setFoundVideos(found, url)
            then?.invoke()
            if (then == null && found.isEmpty() && videoScans < 3) {
                uiHandler.removeCallbacks(videoScan)
                uiHandler.postDelayed(videoScan, 2500L)
            }
        }
    } catch (e: Exception) {
        then?.invoke()
    }
}

internal fun MainActivity.setFoundVideos(list: List<VideoFinder.Found>, url: String?) {
    val had = foundVideos.isNotEmpty() && foundVideosFor == url
    foundVideos = list
    foundVideosFor = url
    updateVideoFab(announce = list.isNotEmpty() && !had)
}

/** Shows or hides the button for the page on screen; [announce] spells it out again for a moment. */
internal fun MainActivity.updateVideoFab(announce: Boolean = false) {
    val fab = binding.videoFab
    val web = activeWeb()
    val show = foundVideos.isNotEmpty() && web != null && foundVideosFor != null &&
        foundVideosFor == web.url && tabs.activeTab?.url != homePage &&
        videoButtonOn() && !barsHidden && !searchMode && !deckVisible && fullscreenView == null
    if (!show) {
        uiHandler.removeCallbacks(videoFabShrink)
        if (fab.visibility == View.VISIBLE) {
            fab.animate().cancel()
            fab.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(140L)
                .withEndAction { if (fab.alpha == 0f) fab.visibility = View.GONE }.start()
        }
        return
    }
    styleVideoFab()
    val n = foundVideos.size
    fab.contentDescription = if (n == 1) "Download video" else "Download videos, " + n + " found"
    if (fab.visibility != View.VISIBLE || fab.alpha < 1f) {
        fab.animate().cancel()
        if (fab.visibility != View.VISIBLE) {
            fab.alpha = 0f; fab.scaleX = 0.8f; fab.scaleY = 0.8f
            fab.visibility = View.VISIBLE
        }
        fab.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }
    if (announce) {
        setVideoFabExtended(true)
        uiHandler.removeCallbacks(videoFabShrink)
        uiHandler.postDelayed(videoFabShrink, 4000L)
    }
}

internal fun MainActivity.styleVideoFab() {
    val fab = binding.videoFab
    val accent = currentAccent()
    val pill = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 22f * resources.displayMetrics.density
        setColor(accent)
    }
    fab.background = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x33FFFFFF), pill, null)
    val icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.menu_downloads)?.mutate()
    icon?.setTint(android.graphics.Color.WHITE)
    fab.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
    if (!videoFabStyled) {
        videoFabStyled = true
        fab.setOnClickListener { v ->
            Haptics.tap(v)
            offerVideoDownload()
        }
    }
}

/** The button with its words ("Download video"), or shrunk to its icon. */
internal fun MainActivity.setVideoFabExtended(extended: Boolean) {
    val fab = binding.videoFab
    val d = resources.displayMetrics.density
    if (animationsOn() && fab.isLaidOut) {
        android.transition.TransitionManager.beginDelayedTransition(binding.webArea,
            android.transition.ChangeBounds().addTarget(fab).setDuration(220L))
    }
    val n = foundVideos.size
    if (extended) {
        fab.text = if (n > 1) "Download video (" + n + ")" else "Download video"
        fab.compoundDrawablePadding = (8 * d).toInt()
        fab.setPaddingRelative((12 * d).toInt(), 0, (16 * d).toInt(), 0)
    } else {
        fab.text = ""
        fab.compoundDrawablePadding = 0
        fab.setPaddingRelative((11 * d).toInt(), 0, (11 * d).toInt(), 0)
    }
}

/** Menu > Page tools > Download video: looks again first, then offers what is there. */
internal fun MainActivity.downloadVideoFromMenu() {
    if (webPageUrl() == null) { needAPage(); return }
    scanVideos { offerVideoDownload() }
}

internal fun MainActivity.offerVideoDownload() {
    val web = activeWeb() ?: return
    val list = if (foundVideosFor == web.url) foundVideos else emptyList()
    if (list.isEmpty()) {
        val host = try { android.net.Uri.parse(web.url ?: "").host } catch (e: Exception) { null }
        toast(when {
            VideoFinder.youTube(host) -> "YouTube's terms don't allow saving its videos"
            VideoFinder.pageExcluded(host) -> "Open the video's own page to save it"
            else -> "No video on this page can be saved. Start it playing, then try again."
        })
        return
    }
    if (list.size == 1) { fetchVideo(list[0]); return }
    val labels = list.mapIndexed { i, f -> VideoFinder.label(f, i) }.toTypedArray<CharSequence>()
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
        .setTitle("Download video")
        .setItems(labels) { _, which -> list.getOrNull(which)?.let { fetchVideo(it) } }
        .setNegativeButton("Cancel", null)
        .show()
}

/**
 * Checks [f] with the server first - what it is and how big - so the
 * confirmation can show its size and a stream's page is turned away with
 * a reason instead of saving as a broken file. Then the usual download.
 */
internal fun MainActivity.fetchVideo(f: VideoFinder.Found) {
    val web = activeWeb()
    val ua = web?.settings?.userAgentString
    val referer = web?.url?.takeIf { it.startsWith("http") }
    toast("Getting the video…")
    Thread {
        val probe = try {
            VideoFinder.probe(f.url, ua, referer) { u ->
                try { android.webkit.CookieManager.getInstance().getCookie(u) } catch (e: Exception) { null }
            }
        } catch (e: Throwable) { null }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (probe != null && !VideoFinder.looksLikeVideo(probe.finalUrl, probe.type)) {
                toast("This video can't be saved: the site plays it in pieces")
                return@runOnUiThread
            }
            val type = probe?.type?.substringBefore(';')?.trim()?.lowercase()
            val mime = type?.takeIf { it.startsWith("video/") } ?: VideoFinder.mimeFor(f)
            startDownload(f.url, ua, VideoFinder.disposition(f, mime), mime, probe?.length ?: -1L)
        }
    }.start()
}

internal fun MainActivity.stopMediaService() {
    if (!mediaActive) return
    mediaActive = false
    try {
        startService(android.content.Intent(this, MediaService::class.java).apply {
            action = MediaService.ACTION_STOP
        })
    } catch (e: Exception) { /* ignore */ }
}

internal fun MainActivity.launchScan() {
    val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this)
    scanner.startScan()
        .addOnSuccessListener { barcode ->
            val value = barcode.rawValue?.trim()
            if (!value.isNullOrEmpty()) go(value)
        }
        .addOnCanceledListener { /* user closed the scanner */ }
        .addOnFailureListener { e ->
            android.widget.Toast.makeText(this,
                "Scanner unavailable", android.widget.Toast.LENGTH_SHORT).show()
        }
}

internal fun MainActivity.launchVoiceSearch() {
    val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to search")
    }
    try {
        voiceLauncher.launch(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(this,
            "Voice search not available", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens a Play Store listing (play.google.com/store/apps/details?id=...)
 * in the Play Store app. False when [url] is not one, or there is no Play
 * Store on the phone - the listing then opens as a web page.
 */
internal fun MainActivity.openPlayListing(url: String): Boolean {
    val uri = try { android.net.Uri.parse(url) } catch (e: Exception) { return false }
    if (uri.host?.lowercase() != "play.google.com" || uri.path != "/store/apps/details") return false
    val id = uri.getQueryParameter("id")?.takeIf { Shortcuts.PLAY_ID.matches(it) } ?: return false
    return try {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("market://details?id=$id"))
            .setPackage("com.android.vending")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Hands a non-web link to whichever app on the phone claims it.
 *
 * intent:// URLs are unpacked rather than passed straight through, and
 * stripped of the parts that would turn a link into a way to reach inside
 * another app - or back into this one. A page gets to name an action; it
 * does not get to name a component. Any URI grant a page tried to attach is
 * dropped for the same reason.
 *
 * Nothing here uses resolveActivity: under package visibility it reports
 * "nothing handles this" for apps we cannot see, while startActivity would
 * have worked. Trying and catching is both simpler and more accurate.
 *
 * Returns true only when a web fallback is being loaded into [from], which
 * is the one outcome that leaves something to show in that tab.
 */
internal fun MainActivity.openExternal(url: String, from: WebView?): Boolean {
    val isIntentUri = url.startsWith("intent://", ignoreCase = true)
    val intent = try {
        if (isIntentUri) {
            android.content.Intent.parseUri(
                url, android.content.Intent.URI_INTENT_SCHEME
            ).apply {
                component = null
                selector = null
                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                // A web page must not be able to open this app's own screens.
                if (`package` == packageName) `package` = null
            }
        } else {
            android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        }
    } catch (e: Exception) {
        return false
    }
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.flags = intent.flags and
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION.inv() and
        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv()
    try {
        startActivity(intent)
        return false
    } catch (e: Exception) { /* no app claims it - fall through */ }

    // intent:// can name a web page to use when the app isn't installed.
    val fallback = try {
        if (isIntentUri) android.content.Intent.parseUri(
            url, android.content.Intent.URI_INTENT_SCHEME
        ).getStringExtra("browser_fallback_url") else null
    } catch (e: Exception) { null }
    if (!fallback.isNullOrBlank() &&
        (fallback.startsWith("http://") || fallback.startsWith("https://"))
    ) {
        // Into the tab the link was followed in, which is not always the
        // one on screen, and after the callback that asked - see
        // loadAfterCallback for why never during it.
        loadAfterCallback(from ?: activeWeb(), fallback)
        return true
    }
    // Say so plainly. The old path showed "this site can't be reached",
    // which blamed the site for a missing app.
    android.widget.Toast.makeText(
        this, "No app on this phone opens that kind of link",
        android.widget.Toast.LENGTH_SHORT).show()
    return false
}

/**
 * Pulls a web link out of an incoming intent, if any:
 *  - ACTION_VIEW carries the link in the intent data (tapped links).
 *  - ACTION_SEND carries it in EXTRA_TEXT (shared links).
 * Returns the URL string, or null if the intent has no usable link.
 */
internal fun MainActivity.urlFromIntent(intent: android.content.Intent?): String? {
    if (intent == null) return null
    when (intent.action) {
        android.content.Intent.ACTION_VIEW -> {
            val d = intent.dataString?.trim()
            if (!d.isNullOrEmpty()) return d
        }
        android.content.Intent.ACTION_SEND -> {
            val t = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.trim()
            if (!t.isNullOrEmpty()) return t
        }
    }
    return null
}

/**
 * What the user typed, or what another app handed over, turned into an
 * address to load. Shared so a link arriving by intent is treated exactly
 * like one typed into the bar, rather than skipping these rules.
 */
internal fun MainActivity.resolveInput(input: String): String {
    var url = UrlHelper.toUrlOrSearch(input, Settings.getEngineUrl(this))
    if (httpsOnly() && url.startsWith("http://")) {
        val host = try {
            android.net.Uri.parse(url).host
        } catch (e: Exception) { null }
        // A host the user has already said yes to stays as typed.
        if (host == null || !httpAllowed.contains(host)) {
            val https = "https://" + url.removePrefix("http://")
            // Recorded so that if https fails, the failure is met with the
            // warning and a way through, not "site can't be reached".
            upgradedFrom[https] = url
            if (host != null) {
                upgradedAt[host] = android.os.SystemClock.uptimeMillis()
            }
            url = https
        }
    }
    return url
}

internal fun MainActivity.go(input: String) {
    tabs.activeTab?.fromApp = false
    if (searchMode) exitSearchMode()
    // An empty bar has nothing to go to. It used to load a blank search.
    if (input.isBlank()) { hideKeyboard(); return }
    loadInto(activeWeb(), resolveInput(input))
    hideKeyboard()
    activeWeb()?.requestFocus()
}

internal fun MainActivity.activeWeb(): WebView? = tabs.activeTab?.webView

/** The open-tab count, on the badge at the Tabs button's corner. */
internal fun MainActivity.updateTabCount() {
    val n = tabs.count()
    // A circle for one digit and a pill for two; past 99 there is no
    // room, so it says so the way Chrome does.
    binding.navTabsCount.text = if (n > 99) ":D" else n.toString()
    binding.navTabs.contentDescription = if (n == 1) "1 tab" else "$n tabs"
}

// Everything that carries the accent on the home page carries it here too:
// the tab count, and the mic and scan glyphs, which home draws in var(--accent).
/**
 * The accent chosen for the owl, or the default when the stored value is
 * unparseable. Extracted so the tab deck and the chrome cannot disagree
 * about what the accent is.
 */
internal fun MainActivity.currentAccent(): Int = try {
    android.graphics.Color.parseColor(Settings.getHomeAccent(this))
} catch (e: Exception) {
    android.graphics.Color.parseColor("#8B6BD8")
}

internal fun MainActivity.applyAccentTints() {
    val accent = currentAccent()

    val tint = android.content.res.ColorStateList.valueOf(accent)
    binding.micBtn.imageTintList = tint
    binding.scanBtn.imageTintList = tint
    // The deck's "+ New" and "Done" too; they were a fixed blue.
    Settings.accentInk(this).let { ink ->
        binding.deckNewTab.setTextColor(ink)
        binding.deckClose.setTextColor(ink)
    }
    // Selected Home wears the accent, so the bars follow it too.
    refreshNav()
    applyArtAccent(accent)
    pushAccentToPage(activeWeb())
    activeWeb()?.evaluateJavascript("window.__setWallpaper && window.__setWallpaper(" +
        HomeWallpapers.json(HomeWallpapers.current(this)) + ")", null)
}

/**
 * Hands the accent to whichever asset page is loaded. Values are rebuilt
 * from the parsed colour rather than passed through, so nothing out of
 * settings reaches a page as script. A no-op on real sites, which do not
 * define the hook.
 */
internal fun MainActivity.pushAccentToPage(web: android.webkit.WebView?) {
    val accent = currentAccent()
    val target = FloatArray(3)
    android.graphics.Color.colorToHSV(accent, target)
    val base = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.parseColor("#8B6BD8"), base)
    var shift = target[0] - base[0]
    if (shift > 180f) shift -= 360f
    if (shift < -180f) shift += 360f
    val hex = String.format("#%06X", 0xFFFFFF and accent)
    // And Focus and Zen's colours for it, built here (HomeWash), so the
    // page and the bars over it are painted from the same values.
    val wash = HomeWash.json(hex).toString()
    web?.evaluateJavascript(
        "window.__setAccent && window.__setAccent('$hex', ${shift.toInt()}, $wash)", null)
}

internal fun MainActivity.applyArtAccent(accent: Int) {
    if (accent == owlAccent) return
    owlAccent = accent
    owlArt = recolouredToAccent(R.drawable.ic_owl, accent)
    owlArt?.let { binding.homeBtn.setImageBitmap(it) }
    // The settings glyph and the reload ring were recoloured here while
    // they lived in the bar.
    // The menu's own glyphs are handled by applyMenuAccent, which tints
    // rather than hue-shifts because they are single tone.
    applyMenuAccent(accent)
}

// Menu glyphs are single-tone vectors, so a tint is exactly right here -
// there is no second colour for it to flatten. The rows carry ids but the
// icons inside them do not, so the panel is walked instead.
internal fun MainActivity.applyMenuAccent(accent: Int) {
    val tint = android.content.res.ColorStateList.valueOf(accent)
    fun walk(v: View) {
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        } else if (v is android.widget.ImageView && v.tag != "chevron") {
            v.imageTintList = tint
        }
    }
    walk(binding.menuPanel)
}

/**
 * Swaps every owl in an inflated tree for the recoloured one. Matched on
 * constant state rather than id: all four dialogs draw the same resource,
 * and drawables loaded from one resource share it.
 */
internal fun MainActivity.applyOwlArtIn(root: View) {
    applyAccentIn(root)
    val art = owlArt ?: return
    val ref = androidx.core.content.ContextCompat
        .getDrawable(this, R.drawable.ic_owl)?.constantState ?: return
    fun walk(v: View) {
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        } else if (v is android.widget.ImageView && v.drawable?.constantState == ref) {
            v.setImageBitmap(art)
        }
    }
    walk(root)
}

/**
 * The owl's dialogs in the accent the user picked: the filled button (deep
 * ink and white text on light, light ink and dark text on dark, so it
 * reads either way) and the text buttons beside it. They were a fixed
 * purple whatever the accent.
 */
internal fun MainActivity.applyAccentIn(root: View) {
    val ink = Settings.accentInk(this)
    val brand = 0xFF5E44A0.toInt()
    val filled = androidx.core.content.ContextCompat
        .getDrawable(this, R.drawable.dialog_btn_filled)?.constantState
    fun walk(v: View) {
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
            return
        }
        val t = v as? android.widget.TextView ?: return
        if (filled != null && t.background?.constantState == filled) {
            t.backgroundTintList = android.content.res.ColorStateList.valueOf(ink)
            t.setTextColor(if (isDarkTheme()) 0xFF141416.toInt() else android.graphics.Color.WHITE)
        } else if (t.currentTextColor == brand) {
            t.setTextColor(ink)
        }
    }
    walk(root)
}

/**
 * Draws a drawable that has no bitmap of its own into one.
 *
 * Four times its intrinsic size deliberately. This runs so the accent
 * pass has PIXELS to hue-shift, and whatever is rendered here is the
 * resolution the icon ends up being displayed at - a 24dp vector has a
 * 96px intrinsic size at xxxhdpi while the button it goes in draws 36dp,
 * which is 144px. Rendering at intrinsic size would put the icon straight
 * back to being upscaled, which is the whole thing this replaced.
 */
internal fun MainActivity.rasterise(d: android.graphics.drawable.Drawable): android.graphics.Bitmap? {
    val w = d.intrinsicWidth
    val h = d.intrinsicHeight
    if (w <= 0 || h <= 0) return null
    val bmp = android.graphics.Bitmap.createBitmap(
        w * 4, h * 4, android.graphics.Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, w * 4, h * 4)
    d.draw(android.graphics.Canvas(bmp))
    return bmp
}

internal fun MainActivity.recolouredToAccent(resId: Int, accent: Int): android.graphics.Bitmap? {
    val art = androidx.core.content.ContextCompat.getDrawable(this, resId)
        ?: return null
    // A vector has no bitmap to read. Before this it fell straight through
    // the null branch below and the icon quietly stopped taking the accent.
    val src = (art as? android.graphics.drawable.BitmapDrawable)?.bitmap
        ?: rasterise(art) ?: return null

    val target = FloatArray(3)
    android.graphics.Color.colorToHSV(accent, target)
    val base = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.parseColor("#8B6BD8"), base)

    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val hsv = FloatArray(3)
    for (i in px.indices) {
        val c = px[i]
        if (android.graphics.Color.alpha(c) == 0) continue
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.15f) continue
        var delta = hsv[0] - base[0]
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        if (kotlin.math.abs(delta) > 45f) continue
        hsv[0] = ((target[0] + delta) % 360f + 360f) % 360f
        px[i] = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(c), hsv)
    }
    val out = android.graphics.Bitmap.createBitmap(
        w, h, android.graphics.Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

internal fun MainActivity.hideKeyboard() {
    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
}
