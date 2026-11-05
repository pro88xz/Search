package com.search.browser

import android.content.Context

/**
 * What each site has been allowed or refused, remembered per origin.
 *
 * The point of remembering is that the user is asked once per site rather than
 * on every page load - a prompt that reappears constantly gets dismissed
 * without being read, which is no better than granting silently.
 */
object SitePermissions {

    private const val PREFS = "site_permissions"

    const val CAMERA_MIC = "cammic"
    const val LOCATION = "location"
    /** Pop-ups the site may open by itself (ALLOW), past the pop-up blocker. */
    const val POPUPS = "popups"

    const val UNSET = 0
    const val ALLOW = 1
    const val DENY = 2

    private fun key(origin: String, kind: String) = kind + "|" + origin

    fun get(c: Context, origin: String, kind: String): Int {
        val v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(key(origin, kind), UNSET)
        // A permission the site is using now is not an unused one.
        if (v == ALLOW) noteUsed(c, origin)
        return v
    }

    fun set(c: Context, origin: String, kind: String, value: Int) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(key(origin, kind), value).apply()
        if (value == ALLOW) noteUsed(c, origin)
    }

    // ---------- Unused sites lose their permissions ----------
    //
    // Chrome takes back what a site was allowed once the site has gone unused
    // for 60 days (unused_site_permissions_manager.cc), so a grant given once
    // years ago does not stay open for good. Search does the same for camera
    // and microphone, location and pop-ups. "Used" is the last time the site
    // asked for something it was allowed, kept per origin in a file of its
    // own so count() and bySite() see only the answers.

    private const val USED = "site_permissions_used"
    const val UNUSED_DAYS = 60
    private const val DAY_MS = 86_400_000L

    private fun noteUsed(c: Context, origin: String) {
        if (origin.isBlank()) return
        c.getSharedPreferences(USED, Context.MODE_PRIVATE)
            .edit().putLong(origin, System.currentTimeMillis()).apply()
    }

    /**
     * Which grants to take back and which sites to start the clock for, from
     * the stored answers ("kind|origin" to answer) and the last-used times.
     * A grant from before this was recorded has no time: its clock starts now
     * rather than it being removed on the first check after an update.
     */
    internal class Unused(val revoke: List<String>, val stamp: List<String>)

    internal fun findUnused(
        answers: Map<String, *>, used: Map<String, *>, now: Long, days: Int = UNUSED_DAYS
    ): Unused {
        val revoke = mutableListOf<String>()
        val stamp = mutableSetOf<String>()
        answers.forEach { (k, v) ->
            if (v != ALLOW) return@forEach
            val origin = k.substringAfter('|', "")
            if (origin.isBlank()) return@forEach
            val last = used[origin] as? Long
            if (last == null) stamp += origin
            else if (now - last > days * DAY_MS) revoke += k
        }
        return Unused(revoke, stamp.toList())
    }

    /**
     * Takes back the grants of sites unused for [UNUSED_DAYS] days. Returns the
     * sites that lost something, for telling the user. The answers they had
     * refused stay refused.
     */
    fun revokeUnused(c: Context): List<String> {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val usedPrefs = c.getSharedPreferences(USED, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val found = findUnused(prefs.all, usedPrefs.all, now)
        if (found.stamp.isNotEmpty()) {
            val e = usedPrefs.edit()
            found.stamp.forEach { e.putLong(it, now) }
            e.apply()
        }
        if (found.revoke.isEmpty()) return emptyList()
        val e = prefs.edit()
        found.revoke.forEach { e.remove(it) }
        e.apply()
        val sites = found.revoke.map { it.substringAfter('|') }.distinct()
        val u = usedPrefs.edit()
        sites.forEach { u.remove(it) }
        u.apply()
        return sites
    }

    fun count(c: Context): Int =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.size

    /**
     * Every site with a remembered answer, as origin to (kind to answer),
     * sites in alphabetical order of their host.
     */
    fun bySite(c: Context): Map<String, Map<String, Int>> {
        val out = sortedMapOf<String, MutableMap<String, Int>>(compareBy(
            { hostOf(it) }, { it }))
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val kind = k.substringBefore('|')
            val origin = k.substringAfter('|', "")
            val answer = v as? Int ?: return@forEach
            if (origin.isBlank() || answer == UNSET) return@forEach
            out.getOrPut(origin) { mutableMapOf() }[kind] = answer
        }
        return out
    }

    /** Forgets one answer, so that site asks again. */
    fun forget(c: Context, origin: String, kind: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(origin, kind)).apply()
    }

    /** "example.com" for "https://www.example.com", for showing. */
    fun hostOf(origin: String): String = try {
        android.net.Uri.parse(origin).host?.removePrefix("www.")?.ifBlank { null } ?: origin
    } catch (e: Exception) { origin }

    /** Forgets every decision, so each site asks again. */
    fun clearAll(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        c.getSharedPreferences(USED, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
