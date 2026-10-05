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

    const val UNSET = 0
    const val ALLOW = 1
    const val DENY = 2

    private fun key(origin: String, kind: String) = kind + "|" + origin

    fun get(c: Context, origin: String, kind: String): Int =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(key(origin, kind), UNSET)

    fun set(c: Context, origin: String, kind: String, value: Int) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(key(origin, kind), value).apply()
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
    }
}
