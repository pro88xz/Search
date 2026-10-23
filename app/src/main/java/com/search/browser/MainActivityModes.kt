package com.search.browser

import android.view.ViewGroup

// MainActivity, continued: Browsing modes (BrowsingMode).
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- Browsing modes (BrowsingMode) ----------

/**
 * Once after the upgrade that brought the modes: "Search has 3 modes now".
 * Only on home, once it is up, with nothing else in front - not while
 * typing, searching, in the tab deck, during the consent message or an
 * app open ad (the window has no focus then) or another dialog.
 */
internal fun MainActivity.maybeAnnounceModes() {
    if (announcing || isFinishing || isDestroyed) return
    if (!BrowsingMode.announcementDue(this)) return
    if (keepSplash || launchCover != null || searchMode || deckVisible || findActive || nightOwl) return
    if (AdConsent.formShowing || !hasWindowFocus()) return
    if (activeWeb()?.url != homePage) return
    announcing = true
    BrowsingMode.markAnnounced(this)
    HealthStats.count(this, HealthStats.MODE_ANNOUNCED)
    // The owl dialogs' own design (as Play Games' welcome): the owl, the
    // accent, the pill buttons, the owl's entrance.
    val view = layoutInflater.inflate(R.layout.dialog_modes_new, null)
    applyOwlArtIn(view)
    val dialog = android.app.AlertDialog.Builder(this).setView(view).create()
    dialog.window?.setBackgroundDrawable(
        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    dialog.window?.attributes?.windowAnimations = R.style.OwlDialogAnim
    view.findViewById<android.widget.TextView>(R.id.modesGotIt).setOnClickListener {
        Haptics.tap(it)
        dialog.dismiss()
    }
    view.findViewById<android.widget.TextView>(R.id.modesChoose).setOnClickListener {
        Haptics.tap(it)
        dialog.dismiss()
        startActivity(android.content.Intent(this, SectionActivity::class.java)
            .putExtra(SectionActivity.EXTRA_SECTION, SectionActivity.SEC_MODE))
    }
    dialog.setOnDismissListener { announcing = false }
    dialog.show()
    dialog.window?.let { w ->
        val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
