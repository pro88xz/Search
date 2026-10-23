package com.search.browser

import android.view.View

// MainActivity, continued: The session on disk, Launcher shortcuts (res/xml/shortcuts.xml), History, Bookmarks.
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- The session on disk ----------

/** A save a moment from now, so a burst of navigations is one write. */
internal fun MainActivity.scheduleSessionSave() {
    uiHandler.removeCallbacks(sessionSave)
    uiHandler.postDelayed(sessionSave, 1500L)
}

/**
 * Brings back the tabs saved on disk, for a launch with nothing in memory
 * or instance state. The tab that was on screen opens; the others wait,
 * frozen, with their history still on disk until they are opened.
 */
internal fun MainActivity.restoreSessionFromDisk(): Boolean {
    val session = SessionStore.load(this) ?: return false
    val failed = SessionStore.beginRestore(this)
    if (failed >= 2) {
        // Two launches in a row died restoring this session. Start fresh.
        SessionStore.clear(this)
        SessionStore.restoreSettled(this)
        return false
    }
    // One did: bring back the addresses, but not the saved histories.
    val useState = session.stateUsable && failed == 0
    session.tabs.forEach { s ->
        val t = tabs.createTab(s.url)
        t.title = s.title
        t.signIn = s.signIn
        t.diskKey = s.key
        if (useState && s.hasState) {
            t.diskStateFile = SessionStore.stateFile(this, s.key)
            t.diskStateHash = 1
        }
        t.openerLoadsAtOpen = -1
    }
    session.tabs.forEachIndexed { i, s ->
        if (s.openerIndex >= 0) {
            tabs.tabs[i].openerId = tabs.tabs.getOrNull(s.openerIndex)?.id
        }
    }
    restoreGroups(session.groups, session.tabs.map { it.group })
    openTab(tabs.tabs.getOrNull(session.active) ?: tabs.tabs.first())
    // Settled once the app is up; onPause settles it sooner.
    uiHandler.postDelayed({ SessionStore.restoreSettled(this) }, 8000L)
    return true
}

/**
 * Rebuilds the tab groups saved alongside the tabs just restored, which are
 * all of [tabs] in order: [memberOf] gives each tab's group as a position in
 * [saved], -1 for none.
 */
internal fun MainActivity.restoreGroups(saved: List<SessionStore.Group>, memberOf: List<Int>) {
    if (saved.isEmpty()) return
    val made = saved.map { g -> tabs.newGroup(g.name, g.color) }
    tabs.tabs.forEachIndexed { i, t ->
        t.groupId = made.getOrNull(memberOf.getOrElse(i) { -1 })?.id
    }
    tabs.pruneGroups()
}

// ---------- History ----------

/** Shows the deck empty-state with the given icon and message. */
internal fun MainActivity.showDeckEmpty(iconRes: Int, title: String, subtitle: String) {
    binding.deckEmptyIcon.setImageResource(iconRes)
    binding.deckEmptyTitle.text = title
    binding.deckEmptySubtitle.text = subtitle
    binding.deckEmpty.visibility = View.VISIBLE
}

internal fun MainActivity.showDownloads() {
    if (downloadsAdapter == null) {
        downloadsAdapter = DownloadsAdapter(
            items = emptyList(),
            onOpen = { d -> openDownloadedFile(d) },
            onMore = { d -> showDownloadActions(d) }
        )
        binding.downloadList.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.downloadList.adapter = downloadsAdapter
    }
    binding.tabList.visibility = View.GONE
    binding.historyList.visibility = View.GONE
    binding.bookmarkList.visibility = View.GONE
    downloadsOpen = true
    renderDeckHeader()
    historyOpen = false
    bookmarksOpen = false
    // First pass on the main thread so the list is populated in the same
    // frame the deck appears; refreshes after this one run off it.
    applyDownloads(Downloads.load(this))
    binding.downloadList.removeCallbacks(downloadsPoll)
    binding.downloadList.postDelayed(downloadsPoll, 1200L)
}

/**
 * Removes a download, by whichever route it arrived. A file this app wrote
 * has no DownloadManager entry to remove, so calling remove() on it would
 * delete nothing and report nothing - the row would simply reappear.
 */
internal fun MainActivity.deleteDownload(d: Downloads.Item) {
    if (d.managed) {
        try {
            (getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager)
                .remove(d.id)
        } catch (e: Exception) { /* already gone */ }
        return
    }
    val uri = d.localUri ?: return
    try {
        val parsed = android.net.Uri.parse(uri)
        if (parsed.scheme.equals("content", true)) {
            contentResolver.delete(parsed, null, null)
        } else {
            parsed.path?.let { java.io.File(it).delete() }
        }
    } catch (e: Exception) { /* already gone from disk */ }
    SavedFiles.remove(this, uri)
}

internal fun MainActivity.refreshDownloads() {
    Thread {
        // A thread of its own has nothing above it to catch an error, which
        // would end the app: the list stays as it was instead.
        val items = try { Downloads.load(this) } catch (t: Throwable) { return@Thread }
        runOnUiThread { applyDownloads(items) }
    }.start()
}

internal fun MainActivity.applyDownloads(items: List<Downloads.Item>) {
    if (!downloadsOpen || isFinishing || isDestroyed) return
    downloadsAdapter?.submit(items)
    if (items.isEmpty()) {
        showDeckEmpty(R.drawable.menu_history, "No downloads yet",
            "Files you download will show up here.")
        binding.downloadList.visibility = View.GONE
    } else {
        binding.deckEmpty.visibility = View.GONE
        binding.downloadList.visibility = View.VISIBLE
    }
}

/**
 * Opens a finished download in whatever app handles it.
 *
 * The old path handed DownloadManager's COLUMN_LOCAL_URI straight to
 * ACTION_VIEW. For anything saved to the public Downloads folder that
 * column is a file:// URI, and handing one to another app has thrown
 * FileUriExposedException since Android 7. It was caught here as a plain
 * Exception, so every tap reported "No app to open this file" however many
 * viewers were installed - PDFs, audio, video, all of it. Nothing could
 * ever be opened, and the message blamed the phone.
 *
 * Two things were missing: a URI another app is allowed to read, and a type
 * worth routing on. DownloadManager issues a content:// URI of its own;
 * where a device still hands back file://, it goes out through FileProvider
 * instead. The stored media type is frequently blank or octet-stream, which
 * matches no viewer, so the file extension decides - that is what puts a
 * PDF in a PDF reader and an mp4 in a video player.
 */
internal fun MainActivity.openDownloadedFile(d: Downloads.Item) {
    if (d.canRetry) { confirmRetryDownload(d); return }
    if (!d.isComplete) {
        toast(if (d.isRunning) "Still downloading\u2026" else "File not available")
        return
    }
    val uri = resolveDownloadUri(d)
    if (uri == null) {
        toast("That file is no longer on this phone")
        return
    }
    val mime = mimeForDownload(d)
    if (isApk(d, mime)) { installApk(d, uri); return }
    // Specific type first - it is what routes a PDF to a PDF reader rather
    // than to a generic file browser. A wildcard type is the fallback for
    // one that nothing claims outright.
    if (startViewer(uri, mime)) return
    if (!mime.equals("*/*", true) && startViewer(uri, "*/*")) return
    toast("No app on this phone opens this kind of file")
}

// ---------- Bookmarks ----------

internal fun MainActivity.toggleBookmark() {
    val tab = tabs.activeTab ?: return
    val url = tab.url
    if (url.isBlank() || url.startsWith("file:///android_asset/")) return
    if (Bookmarks.isBookmarked(this, url)) {
        Bookmarks.remove(this, url)
        android.widget.Toast.makeText(this, "Bookmark removed", android.widget.Toast.LENGTH_SHORT).show()
    } else {
        Bookmarks.add(this, tab.title, url)
        android.widget.Toast.makeText(this, "Bookmarked", android.widget.Toast.LENGTH_SHORT).show()
    }
    refreshStar()
}

internal fun MainActivity.refreshStar() {
    val url = tabs.activeTab?.url ?: ""
    val marked = url.isNotBlank() && Bookmarks.isBookmarked(this, url)
    binding.starBtn.setImageResource(
        if (marked) R.drawable.ic_star_filled else R.drawable.ic_star_outline
    )
}
