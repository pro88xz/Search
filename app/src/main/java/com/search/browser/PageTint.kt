package com.search.browser

/**
 * The colour maths behind the bars taking on a page's colours
 * (MainActivity.samplePageTint). Kept free of Android types so it can be
 * tested on the JVM.
 *
 * A site's raw colour is never used as it is. A red header or a blue banner
 * painted straight onto the bars reads as a hard block of colour, and the
 * bar's icons can lose against it. Instead each sampled colour is first
 * calmed - its saturation capped - and then only a share of it is mixed into
 * the bar's own surface, so the bar keeps its light or dark character (and
 * its icons their contrast) while carrying a wash of the page: the "ambient"
 * look of Chrome's and Samsung's toolbars, softer.
 */
object PageTint {

    /** How much of the (calmed) page colour the bar takes: nearly all of it, as Chrome's toolbar does. */
    const val SHARE = 0.9f
    /** The most saturation a sampled colour keeps, 0..1: a red site gives a muted red, never a hard one. */
    const val MAX_SATURATION = 0.40f
    /** Below this luminance a bar is dark, and its text and icons turn light. */
    const val DARK_BAR = 0.36

    /**
     * Averages each third (left, middle, right) of a strip of ARGB pixels,
     * [w] wide and [h] tall, skipping see-through ones. Null when a third has
     * nothing to go on.
     */
    fun thirds(px: IntArray, w: Int, h: Int): IntArray? {
        if (w < 3 || h < 1 || px.size < w * h) return null
        val out = IntArray(3)
        for (t in 0 until 3) {
            val x0 = w * t / 3
            val x1 = w * (t + 1) / 3
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in 0 until h) for (x in x0 until x1) {
                val c = px[y * w + x]
                if ((c ushr 24) < 0x80) continue
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            if (n == 0L) return null
            out[t] = argb(255, (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
        return out
    }

    /**
     * A bar's three colours for the page's [thirds] along its edge, or null to
     * leave the bar as it is. The bar follows the page - a dark page gives a
     * dark bar, a white page a white one - but calmed, and the thirds drawn
     * most of the way to their average so the bar reads as one colour with a gentle
     * drift across it. In the dark theme a light page leaves the bar dark:
     * a white bar at night is a torch in the face.
     */
    fun barColours(thirds: IntArray, surface: Int, darkTheme: Boolean): IntArray? {
        if (thirds.size < 3) return null
        val avg = average(thirds)
        if (darkTheme && luminance(calm(avg)) > 0.35) return null
        return IntArray(3) { mix(surface, calm(mix(avg, thirds[it], 0.3f)), SHARE) }
    }

    /** Whether text and icons on a bar painted in [colours] should be light. */
    fun isDark(colours: IntArray): Boolean = luminance(average(colours)) < DARK_BAR

    fun average(c: IntArray): Int {
        if (c.isEmpty()) return 0
        var r = 0; var g = 0; var b = 0
        for (x in c) { r += (x shr 16) and 0xFF; g += (x shr 8) and 0xFF; b += x and 0xFF }
        return argb(255, r / c.size, g / c.size, b / c.size)
    }

    /** [c] with its saturation capped at [MAX_SATURATION], in HSL so lightness is kept. */
    fun calm(c: Int): Int {
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        if (d < 1e-4f) return c
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        if (s <= MAX_SATURATION) return c
        val hue = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f)) / 6f
            g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        return hsl(hue, MAX_SATURATION, l)
    }

    /** Relative luminance (WCAG), 0 black .. 1 white. */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * u).toInt()
        return argb(255, ch(16), ch(8), ch(0))
    }

    /** Each of [a] eased toward [b] by [t]. */
    fun mixAll(a: IntArray, b: IntArray, t: Float): IntArray = IntArray(minOf(a.size, b.size)) { mix(a[it], b[it], t) }

    private fun hsl(h: Float, s: Float, l: Float): Int {
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1f
            if (t > 1) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        return argb(255, (hue(h + 1f / 3f) * 255).toInt(), (hue(h) * 255).toInt(), (hue(h - 1f / 3f) * 255).toInt())
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
