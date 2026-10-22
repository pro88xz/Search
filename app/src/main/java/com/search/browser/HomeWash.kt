package com.search.browser

/**
 * Focus and Zen's wash, in the accent the user picked (Settings > Customize
 * > Accent colour), light and dark.
 *
 * Not a table of colours but a system: every colour is a role - a lightness
 * and a chroma in OKLCH, the space where equal steps look equal - on a hue
 * taken from the accent. So every accent gets the same sky, and an accent
 * nobody planned for gets as good a one as the six in Settings.
 *
 *  - Hues. The accent's own, and a companion 55 degrees along the wheel
 *    toward the owl's amber: violet leans to pink, blue to violet, green to
 *    lime, amber to rose, rose to apricot. One short arc, so neighbouring
 *    colours mix cleanly even in sRGB (the bars' gradients are sRGB) - no
 *    grey or brown passage between them. Accents with next to no colour
 *    (Ink, greys) take a cool steel instead.
 *  - Light: a pastel sky. top: the page's top edge, left to right - accent,
 *    the arc's middle, companion - at 1/6, 1/2 and 5/6, the three colours the
 *    top bar paints too. field: what the sky opens into below the edge.
 *    bottom: what it deepens to at the foot, the bottom bar's colour.
 *  - Dark: night on half the arc, turned away from the hues that go brown or
 *    olive when dark (35-130 degrees). An accent in that range keeps a
 *    near-neutral ground - charcoal, not brown - and its lights are lifted
 *    until they read as light (gold), not as mud.
 *  - glow1, glow2: the accent's and the companion's lights - Focus's bloom
 *    and drift in Zen. glow3: a deeper, cooler accent, Zen's third light.
 *  - Chroma follows the accent's own, within limits, and every colour is
 *    brought inside sRGB by lowering its chroma alone, so its lightness and
 *    hue - what the system decided - survive.
 *
 * The one place these colours are decided. home.html is handed them
 * (getConfig "wash", __setAccent) and paints the page from them; the bars
 * over that page (MainActivity.homeWash) and the modes' previews (ModeCards)
 * take the same ones, so page and bar meet without a seam whatever the
 * accent. Kept free of Android types beyond org.json, so it runs on the JVM.
 */
object HomeWash {

    class Palette(
        val top: IntArray,
        val field: Int,
        val bottom: Int,
        val glow1: Int,
        val glow2: Int,
        val glow3: Int
    )

    /** The hue the companion leans toward: the owl's amber. */
    private const val WARM = 60.0
    /** Accent to companion, in degrees: light, and the shorter arc of dark. */
    private const val SPAN = 55.0
    private const val SPAN_DARK = 30.0
    /** Below this OKLCH chroma an accent counts as having no hue of its own. */
    private const val NEUTRAL_C = 0.04
    /** The steel such an accent is given instead. */
    private const val STEEL = 265.0

    /** The accent as stored, tidied: "#RRGGBB", upper case; the default purple for anything unreadable. */
    fun normalise(accent: String?): String {
        val s = accent?.trim()?.uppercase() ?: return "#8B6BD8"
        return if (Regex("^#[0-9A-F]{6}$").matches(s)) s else "#8B6BD8"
    }

    fun palette(accent: String?, dark: Boolean): Palette {
        val rgb = normalise(accent).substring(1).toInt(16)
        val lch = toOklch(rgb)
        val c0 = lch[1]
        val neutral = c0 < NEUTRAL_C
        // How much colour this accent carries, relative to the default purple's.
        val k = if (neutral) 0.22 else (c0 / 0.16).coerceIn(0.6, 1.15)
        val h1 = if (neutral) STEEL else lch[2]
        val dir = if (neutral) -1.0 else if (signed(h1, WARM) > 8.0) 1.0 else -1.0
        return if (!dark) {
            val h2 = wrap(h1 + dir * SPAN)
            val hm = wrap(h1 + dir * SPAN / 2)
            Palette(
                intArrayOf(oklch(0.885, 0.070 * k, h1), oklch(0.915, 0.058 * k, hm), oklch(0.920, 0.066 * k, h2)),
                field = oklch(0.938, 0.036 * k, hm),
                bottom = oklch(0.905, 0.048 * k, h1),
                glow1 = oklch(0.800, 0.125 * k, h1),
                glow2 = oklch(0.870, 0.100 * k, h2),
                glow3 = oklch(0.720, 0.140 * k, wrap(h1 - dir * 10.0))
            )
        } else {
            // The arc turns the other way if this way leads into brown.
            var dd = dir
            if (brownish(h1 + dd * SPAN_DARK) && !brownish(h1 - dd * SPAN_DARK)) dd = -dd
            val h2 = wrap(h1 + dd * SPAN_DARK)
            val hm = wrap(h1 + dd * SPAN_DARK / 2)
            val h3 = wrap(h1 - dd * 10.0)
            Palette(
                intArrayOf(oklch(0.265, 0.062 * k * ground(h1), h1), oklch(0.255, 0.054 * k * ground(hm), hm),
                    oklch(0.262, 0.060 * k * ground(h2), h2)),
                field = oklch(0.225, 0.046 * k * ground(hm), hm),
                bottom = oklch(0.180, 0.034 * k * ground(h1), h1),
                glow1 = oklch(if (brownish(h1)) 0.66 else 0.50, 0.160 * k, h1),
                glow2 = oklch(if (brownish(h2)) 0.66 else 0.56, 0.130 * k, h2),
                glow3 = oklch(if (brownish(h3)) 0.54 else 0.37, 0.130 * k, h3)
            )
        }
    }

    private fun wrap(h: Double): Double = ((h % 360.0) + 360.0) % 360.0

    /** The shortest turn from [a] to [b], in degrees, -180 to 180. */
    private fun signed(a: Double, b: Double): Double = wrap(b - a + 180.0) - 180.0

    /** Hues that go brown or olive when they are dark. */
    private fun brownish(h: Double): Boolean = wrap(h) in 35.0..130.0

    /** A dark ground's share of its chroma: those hues stay near-neutral. */
    private fun ground(h: Double): Double = if (brownish(h)) 0.35 else 1.0

    // ---------- OKLab (Björn Ottosson, 2020) ----------

    private fun toLinear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun toSrgb(v: Double): Int {
        val c = v.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
        return Math.floor(s * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    /** #RRGGBB as OKLCH: lightness 0-1, chroma, hue in degrees. */
    private fun toOklch(rgb: Int): DoubleArray {
        val r = toLinear((rgb shr 16) and 0xFF)
        val g = toLinear((rgb shr 8) and 0xFF)
        val b = toLinear(rgb and 0xFF)
        val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val okL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val okA = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val okB = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        return doubleArrayOf(okL, Math.hypot(okA, okB), wrap(Math.toDegrees(Math.atan2(okB, okA))))
    }

    /** OKLCH as an opaque colour, its chroma lowered (lightness and hue kept) until it is inside sRGB. */
    private fun oklch(lightness: Double, chroma: Double, hue: Double): Int {
        var c = chroma
        var rgb = DoubleArray(3)
        for (i in 0 until 40) {
            val a = c * Math.cos(Math.toRadians(hue))
            val b = c * Math.sin(Math.toRadians(hue))
            val l = cube(lightness + 0.3963377774 * a + 0.2158037573 * b)
            val m = cube(lightness - 0.1055613458 * a - 0.0638541728 * b)
            val s = cube(lightness - 0.0894841775 * a - 1.2914855480 * b)
            rgb = doubleArrayOf(
                4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
            )
            if (rgb.all { it >= -1e-6 && it <= 1.0 + 1e-6 }) break
            c *= 0.94
        }
        return (0xFF shl 24) or (toSrgb(rgb[0]) shl 16) or (toSrgb(rgb[1]) shl 8) or toSrgb(rgb[2])
    }

    private fun cube(x: Double): Double = x * x * x

    private fun hex(c: Int): String = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)

    private fun json(pl: Palette): org.json.JSONObject = org.json.JSONObject()
        .put("v", hex(pl.top[0])).put("m", hex(pl.top[1])).put("a", hex(pl.top[2]))
        .put("f", hex(pl.field)).put("b", hex(pl.bottom))
        .put("g1", hex(pl.glow1)).put("g2", hex(pl.glow2)).put("g3", hex(pl.glow3))

    /** Both versions for [accent], as home.html takes them: {"accent", "light": {...}, "dark": {...}}, colours as #RRGGBB. */
    fun json(accent: String?): org.json.JSONObject {
        val key = normalise(accent)
        return org.json.JSONObject()
            .put("accent", key)
            .put("light", json(palette(key, false)))
            .put("dark", json(palette(key, true)))
    }
}
