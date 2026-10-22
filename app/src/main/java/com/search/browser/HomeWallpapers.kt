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
        Wallpaper("nebula", "Nebula", true, 0xFF39234E.toInt(), 0xFF3A3069.toInt())
    )

    fun byId(id: String?): Wallpaper? = ALL.firstOrNull { it.id == id }

    /** The one chosen in Settings, or null for none (home's own background). */
    fun current(c: android.content.Context): Wallpaper? = byId(Settings.getHomeWallpaper(c))

    private fun hex(c: Int): String = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)

    /** As home.html takes it: {"id","dark","top","bottom"}, or null. */
    fun json(w: Wallpaper?): String =
        if (w == null) "null"
        else org.json.JSONObject().put("id", w.id).put("dark", w.dark)
            .put("top", hex(w.top)).put("bottom", hex(w.bottom)).toString()
}
