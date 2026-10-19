package com.search.browser

/**
 * Focus and Zen's wash, in the accent the user picked (Settings > Customize
 * > Accent colour): each accent with a companion colour chosen to flatter
 * it - Purple the owl's amber, Blue aqua, Green lime, Amber rose, Rose
 * lavender, Ink steel - in a light and a dark version.
 *
 * The one place these colours are decided. home.html is handed them
 * (getConfig "wash", __setAccent) and paints the page from them; the bars
 * over that page (MainActivity.homeWash) and the modes' previews (ModeCards)
 * take the same ones. So page and bar meet without a seam whatever the
 * accent, and nothing can drift because there is only one copy.
 *
 *  - top: the page's top edge, left to right (lavender-like accent tint, a
 *    blend, the companion) at 1/6, 1/2 and 5/6 - the three colours the top
 *    bar paints too.
 *  - bottom: what the page settles into below, and the bottom bar's colour.
 *  - glow1, glow2: richer accent and companion, the glows that give Focus its
 *    bloom and drift in Zen. glow3: a deeper accent, Zen's third glow.
 *
 * Any other accent (an older stored value) gets one worked out from its hue.
 * Kept free of Android types beyond org.json, so it can be tested on the JVM.
 */
object HomeWash {

    class Palette(
        val top: IntArray,
        val bottom: Int,
        val glow1: Int,
        val glow2: Int,
        val glow3: Int
    )

    private fun p(v: Int, m: Int, a: Int, b: Int, g1: Int, g2: Int, g3: Int) =
        Palette(intArrayOf(c(v), c(m), c(a)), c(b), c(g1), c(g2), c(g3))

    private fun c(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)

    /** Light and dark, per accent (Settings.ACCENTS). */
    private val SETS: Map<String, Pair<Palette, Palette>> = mapOf(
        // Purple: the owl - violet, lilac, the amber of its eyes; indigo night.
        "#8B6BD8" to (p(0xD4C2F7, 0xE8DBF7, 0xFBE2C6, 0xE1DAFB, 0xB896F6, 0xFCCE9E, 0x9B7BE8) to
            p(0x2B1A5C, 0x271B4A, 0x3B2034, 0x150F33, 0x4A2896, 0x804A22, 0x3A2A8C)),
        // Blue: sky to aqua, an ocean.
        "#2B6CF0" to (p(0xC3D6FC, 0xDCE6FB, 0xC6F0EA, 0xDDE8FD, 0x8FB3F8, 0x8FE0D6, 0x6F8FF0) to
            p(0x10275A, 0x13234A, 0x0E3A40, 0x091530, 0x1D4BB0, 0x0F6E6A, 0x2A3FA0)),
        // Green: mint to lime, a garden in the sun.
        "#2FB170" to (p(0xBFEBD5, 0xDCF2E5, 0xEEF2C4, 0xDEF4EA, 0x86DCB0, 0xDDE89A, 0x4FC48D) to
            p(0x0E3A27, 0x12301F, 0x2C3410, 0x08190F, 0x17744A, 0x5A6614, 0x1E5E44)),
        // Amber: apricot to rose, a sunset.
        "#F5A623" to (p(0xFCDDAE, 0xFBE9D3, 0xF9CBD0, 0xFDEEDF, 0xF8C47A, 0xF2A7B4, 0xF0A050) to
            p(0x3F2810, 0x33231A, 0x3F1A24, 0x1A110A, 0x8A5414, 0x7A2C42, 0x6A3E10)),
        // Rose: blush to lavender.
        "#E5556E" to (p(0xF8C3CF, 0xF5DAE4, 0xDCCBF7, 0xF7E2EB, 0xF09AAE, 0xB9A0F2, 0xE06A85) to
            p(0x45172A, 0x381A2C, 0x2A1D4A, 0x1A0C14, 0x8C2442, 0x4A3290, 0x6E1E38)),
        // Ink: silver to steel, graphite at night.
        "#0E0E10" to (p(0xD6D8DE, 0xE4E5E9, 0xCBD3DE, 0xE9EAEE, 0xB5B8C2, 0xA9B8CC, 0x8E929E) to
            p(0x1E1F24, 0x1A1A1E, 0x1B2028, 0x0E0E10, 0x34363E, 0x2A3442, 0x26272D))
    )

    /** The accent as stored, tidied: "#RRGGBB", upper case; the default purple for anything unreadable. */
    fun normalise(accent: String?): String {
        val s = accent?.trim()?.uppercase() ?: return "#8B6BD8"
        return if (Regex("^#[0-9A-F]{6}$").matches(s)) s else "#8B6BD8"
    }

    fun palette(accent: String?, dark: Boolean): Palette {
        val key = normalise(accent)
        val set = SETS[key]
        if (set != null) return if (dark) set.second else set.first
        return derived(key, dark)
    }

    /** A wash for an accent with no palette of its own, from its hue: tint, blend and a neighbour 35 degrees on. */
    private fun derived(hex: String, dark: Boolean): Palette {
        val rgb = hex.substring(1).toInt(16)
        val hsl = toHsl(rgb)
        val h = hsl[0]
        val s = hsl[1].coerceIn(0.25f, 0.85f)
        val h2 = (h + 35f) % 360f
        return if (!dark) Palette(
            intArrayOf(hsl(h, s * 0.62f, 0.86f), hsl(h, s * 0.45f, 0.91f), hsl(h2, s * 0.55f, 0.88f)),
            hsl(h, s * 0.5f, 0.93f), hsl(h, s * 0.75f, 0.76f), hsl(h2, s * 0.7f, 0.78f), hsl(h, s * 0.8f, 0.66f))
        else Palette(
            intArrayOf(hsl(h, s * 0.6f, 0.2f), hsl(h, s * 0.45f, 0.17f), hsl(h2, s * 0.5f, 0.18f)),
            hsl(h, s * 0.55f, 0.09f), hsl(h, s * 0.65f, 0.34f), hsl(h2, s * 0.55f, 0.3f), hsl(h, s * 0.6f, 0.28f))
    }

    private fun toHsl(rgb: Int): FloatArray {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return floatArrayOf(h, s, l)
    }

    private fun hsl(h: Float, s: Float, l: Float): Int {
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 0.5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        val hh = h / 360f
        val r = (hue(hh + 1f / 3f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = (hue(hh) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = (hue(hh - 1f / 3f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    private fun json(pl: Palette): org.json.JSONObject = org.json.JSONObject()
        .put("v", hex(pl.top[0])).put("m", hex(pl.top[1])).put("a", hex(pl.top[2]))
        .put("b", hex(pl.bottom)).put("g1", hex(pl.glow1)).put("g2", hex(pl.glow2)).put("g3", hex(pl.glow3))

    /** Both versions for [accent], as home.html takes them: {"accent", "light": {...}, "dark": {...}}, colours as #RRGGBB. */
    fun json(accent: String?): org.json.JSONObject {
        val key = normalise(accent)
        return org.json.JSONObject()
            .put("accent", key)
            .put("light", json(palette(key, false)))
            .put("dark", json(palette(key, true)))
    }
}
