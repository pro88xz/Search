package com.search.browser

import android.os.Build
import android.view.View
import android.view.ViewGroup

// MainActivity, continued: Night Owl: lock and hide, Look-alike sites, Default browser.
// Moved out of MainActivity.kt unchanged, as extension functions on it; the
// state they use is still declared there.

// ---------- Look-alike sites ----------

internal fun MainActivity.checkLookalike(url: String?) {
    if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return
    if (!Settings.getBool(this, Settings.SEC_LOOKALIKE, true)) return
    val host = try { android.net.Uri.parse(url).host?.lowercase() } catch (e: Exception) { null } ?: return
    if (host.split('.').none { it.startsWith("xn--") }) return
    if (!lookalikeWarned.add(host)) return
    val shown = try { java.net.IDN.toUnicode(host) } catch (e: Exception) { host }
    uiHandler.postDelayed({
        if (isFinishing || isDestroyed) return@postDelayed
        val now = try { android.net.Uri.parse(activeWeb()?.url ?: "").host?.lowercase() } catch (e: Exception) { null }
        if (now != host) return@postDelayed
        try {
            com.google.android.material.snackbar.Snackbar
                .make(binding.webArea, "Check the address: " + shown + " is written with letters " +
                    "from another alphabet (" + host + "). Sites that copy a familiar name do this.",
                    9000)
                .setAction("Go back") { navBack() }
                .setActionTextColor(currentAccent())
                .show()
        } catch (e: Exception) { /* no window to show it in */ }
    }, 700L)
}

// ---------- Night Owl: lock and hide ----------

internal fun MainActivity.deviceSecure(): Boolean = try {
    (getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
} catch (e: Exception) { false }

internal fun MainActivity.nightOwlLockOn(): Boolean =
    Settings.getBool(this, Settings.NIGHT_OWL_LOCK, false) && deviceSecure()

internal fun MainActivity.hideNightOwlLock() {
    val v = lockView ?: return
    lockView = null
    (v.parent as? android.view.ViewGroup)?.removeView(v)
}

/** The phone's own unlock: BiometricPrompt (fingerprint, face or the PIN) from Android 11, its PIN screen before. */
internal fun MainActivity.promptUnlock() {
    if (unlocking || !nightOwlLocked || isFinishing || isDestroyed) return
    if (!deviceSecure()) { unlockNightOwl(); return }
    if (Build.VERSION.SDK_INT >= 30) {
        unlocking = true
        try {
            val auth = android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("Unlock Night Owl")
                .setSubtitle("Your Night Owl tabs are locked")
                .setAllowedAuthenticators(auth)
                .build()
            prompt.authenticate(android.os.CancellationSignal(), mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?
                    ) {
                        unlocking = false
                        unlockNightOwl()
                    }
                    // Cancelled or failed for good: still locked, and
                    // Unlock asks again.
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        unlocking = false
                    }
                })
        } catch (e: Exception) {
            unlocking = false
            confirmCredential()
        }
    } else confirmCredential()
}

@Suppress("DEPRECATION")
internal fun MainActivity.confirmCredential() {
    val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
    val i = try {
        km?.createConfirmDeviceCredentialIntent("Unlock Night Owl", "Your Night Owl tabs are locked")
    } catch (e: Exception) { null }
    if (i == null) { unlockNightOwl(); return }
    unlocking = true
    try { unlockLauncher.launch(i) } catch (e: Exception) { unlocking = false }
}

internal fun MainActivity.unlockNightOwl() {
    nightOwlLocked = false
    val v = lockView ?: return
    lockView = null
    if (Motion.animationsOn()) {
        v.animate().alpha(0f).setDuration(200L)
            .withEndAction { (v.parent as? android.view.ViewGroup)?.removeView(v) }.start()
    } else (v.parent as? android.view.ViewGroup)?.removeView(v)
}

/** "Close Night Owl tabs" on the lock: the private session ends, nothing in it shown. */
internal fun MainActivity.closeNightOwlFromLock() {
    nightOwlLocked = false
    hideNightOwlLock()
    val private = tabs.tabs.filter { it.isPrivate }
    if (nightOwl) exitNightOwl()
    private.sortedBy { it === tabs.activeTab }.forEach { t ->
        if (tabs.tabs.contains(t)) closeTabFromDeck(t)
    }
}

// ---------- Default browser ----------

/** Android's own request to make Search the default browser, or its Default apps screen. */
internal fun MainActivity.askToBeDefaultBrowser() {
    val ask = DefaultBrowser.requestIntent(this)
    try {
        if (ask != null) defaultBrowserLauncher.launch(ask)
        else startActivity(DefaultBrowser.settingsIntent())
    } catch (e: Exception) {
        try { startActivity(DefaultBrowser.settingsIntent()) } catch (e2: Exception) {
            toast("Pick Search under Settings > Apps > Default apps > Browser")
        }
    }
}

/**
 * Offered once, on the fourth launch or later, on the home page with
 * nothing else going on - never on a first run and never twice. A browser
 * that is not the default is only ever opened on purpose; as the default,
 * every link tapped in another app comes here.
 */
internal fun MainActivity.maybeOfferDefaultBrowser() {
    if (isFinishing || isDestroyed || offeredThisLaunch) return
    if (nightOwl || searchMode || deckVisible || AdConsent.formShowing) return
    if (binding.menuScrim.visibility == View.VISIBLE) return
    val current = tabs.activeTab?.url
    if (!(current == null || current == homePage)) return
    if (!DefaultBrowser.shouldOffer(this)) return
    offeredThisLaunch = true
    DefaultBrowser.noteOffered(this)
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Search_Dialog)
        .setTitle("Make Search your default browser?")
        .setMessage("Links you tap in other apps will open in Search. You can change this any time in Settings.")
        .setPositiveButton("Set as default") { _, _ -> askToBeDefaultBrowser() }
        .setNegativeButton("Not now", null)
        .show()
}
