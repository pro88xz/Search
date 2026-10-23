package com.search.browser

/**
 * The wallpapers home can wear (Settings > Customize your Search > Wallpaper).
 *
 * Each is a scene rendered from code at 4K (2160x3840) - light mixed in
 * linear sRGB, gradients through OKLab, fractal noise for the land, sea and
 * sky - so there is no AI in them and nothing soft: depth comes from layers
 * fading into haze, perspective and light. Shipped in assets/wallpapers as
 * WebP at 2160 and 1440 wide (home picks the smaller one when the screen is
 * no taller than it, which keeps a low-end phone's memory in hand) and a
 * thumbnail for the picker, each with a fine grain that keeps 8-bit
 * gradients from banding.
 *
 * Every wallpaper's top and bottom rows are flat colours, [top] and
 * [bottom], so the bars over home (MainActivity.homeWash) can take them and
 * meet the page with no seam however the image is cropped. [dark] says which
 * ink reads on it - home's words and labels follow the wallpaper, not the
 * phone's theme.
 */
object HomeWallpapers {

    class Wallpaper(val id: String, val name: String, val dark: Boolean, val top: Int, val bottom: Int)

    /** In the picker's order. */
    val ALL = listOf(
        Wallpaper("owl_night", "Owl Night", true, 0xFF0E0A26.toInt(), 0xFF1E1829.toInt()),
        Wallpaper("dawn_hills", "Dawn Hills", false, 0xFFBBA8EA.toInt(), 0xFF66557A.toInt()),
        Wallpaper("cloud_sea", "Above the Clouds", false, 0xFFA9B8F0.toInt(), 0xFF9E9DC7.toInt()),
        Wallpaper("silk", "Silk", false, 0xFFE0D5FA.toInt(), 0xFFE4D7FD.toInt()),
        Wallpaper("ocean_dusk", "Ocean Dusk", true, 0xFF1A1238.toInt(), 0xFF251C27.toInt()),
        Wallpaper("aurora", "Aurora", true, 0xFF040916.toInt(), 0xFF092723.toInt()),
        Wallpaper("bokeh", "City Lights", true, 0xFF0C081A.toInt(), 0xFF180E30.toInt()),
        Wallpaper("nebula", "Nebula", true, 0xFF020205.toInt(), 0xFF403372.toInt())
    )

    fun byId(id: String?): Wallpaper? = ALL.firstOrNull { it.id == id }

    /** The one chosen in Settings, or null for none (home's own background). */
    fun current(c: android.content.Context): Wallpaper? = byId(Settings.getHomeWallpaper(c))

    private fun hex(c: Int): String = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)

    /**
     * As home.html takes it: {"id","dark","top","bottom","lift"}, or null.
     * [lift] is how far below the top of the screen the page starts, in
     * pixels (MainActivity.wallpaperLift): home lays the wallpaper out from
     * the top of the screen, and the top bar paints the part above the page
     * ([topSlice]).
     */
    fun json(w: Wallpaper?, lift: Int = 0): String =
        if (w == null) "null"
        else org.json.JSONObject().put("id", w.id).put("dark", w.dark)
            .put("top", hex(w.top)).put("bottom", hex(w.bottom)).put("lift", lift).toString()

    /**
     * The part of [w] behind the top bar and the status bar, [width] by
     * [lift] pixels, cut from the wallpaper laid out as home.html lays it
     * out: covering [width] by [span] (the top of the screen to the bottom
     * of the page), centred across and anchored at the top. [large] is the
     * 4K file, as home picks it. Only those rows are decoded. Null if it
     * cannot be cut; the bar keeps its flat colour then, as before.
     */
    fun topSlice(c: android.content.Context, w: Wallpaper, width: Int, lift: Int, span: Int,
                 large: Boolean): android.graphics.Bitmap? {
        if (width <= 0 || lift <= 0 || span < lift) return null
        return try {
            c.assets.open("wallpapers/" + w.id + (if (large) "" else "-1440") + ".webp").use { input ->
                val decoder = (if (android.os.Build.VERSION.SDK_INT >= 31)
                    android.graphics.BitmapRegionDecoder.newInstance(input)
                else @Suppress("DEPRECATION")
                    android.graphics.BitmapRegionDecoder.newInstance(input, false)) ?: return null
                try {
                    val iw = decoder.width
                    val ih = decoder.height
                    val s = maxOf(width.toFloat() / iw, span.toFloat() / ih)
                    // The image's columns and rows under the bar.
                    val left = (iw - width / s) / 2f
                    val rect = android.graphics.Rect(
                        kotlin.math.floor(left).toInt().coerceAtLeast(0), 0,
                        kotlin.math.ceil(left + width / s).toInt().coerceAtMost(iw),
                        kotlin.math.ceil(lift / s).toInt().coerceIn(1, ih))
                    // Half the pixels where the slice is drawn at half size or less.
                    val sample = if (s <= 0.5f) 2 else 1
                    val region = decoder.decodeRegion(rect, android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    }) ?: return null
                    val out = android.graphics.Bitmap.createBitmap(width, lift, android.graphics.Bitmap.Config.ARGB_8888)
                    val m = android.graphics.Matrix()
                    m.setScale(s * sample, s * sample)
                    m.postTranslate((rect.left - left) * s, 0f)
                    android.graphics.Canvas(out).drawBitmap(region, m,
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    region.recycle()
                    out
                } finally {
                    decoder.recycle()
                }
            }
        } catch (t: Throwable) {
            null
        }
    }
}
