package com.search.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.RecyclerView

/** What a card in the tab deck stands for. */
sealed class DeckItem {
    class TabItem(val tab: Tab) : DeckItem()
    class GroupItem(val group: TabGroup, val tabs: List<Tab>) : DeckItem()
}

/** A frame as tall as it is wide times [ratio]: the deck's pictures keep their shape at any width. */
class RatioFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var ratio = 1.18f
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec,
            MeasureSpec.makeMeasureSpec((w * ratio).toInt(), MeasureSpec.EXACTLY))
    }
}

/**
 * A page's picture filling its frame from the top: scaled to cover, centred
 * across and cropped at the bottom - the top of a page is the part that says
 * what it is. Fitted again whenever its size or picture changes (a turn of
 * the phone, a change in the deck's columns), not once after the first
 * layout. With [crop] off, the image sits at its own size in the middle - a
 * site's icon standing in for a page not pictured yet.
 */
class TopCropImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : androidx.appcompat.widget.AppCompatImageView(context, attrs) {
    var crop = true
        set(value) {
            field = value
            scaleType = if (value) ScaleType.MATRIX else ScaleType.CENTER
            fit()
        }

    init { scaleType = ScaleType.MATRIX }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        fit()
        return changed
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        fit()
    }

    private fun fit() {
        // Also reached from the superclass constructor, before [crop] is set.
        if (!crop || scaleType != ScaleType.MATRIX) return
        val d = drawable ?: return
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (w <= 0f || h <= 0f || dw <= 0f || dh <= 0f) return
        val s = maxOf(w / dw, h / dh)
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate((w - dw * s) / 2f, 0f)
        imageMatrix = m
    }
}

/**
 * The tab deck's cards: a tab - its site's icon, title and close button
 * over a picture of the page - or a tab group, its colour, name and size
 * over its first four tabs. [items] is a snapshot, taken by [refresh], so
 * positions hold still while a card is dragged.
 */
class DeckAdapter(private val host: Host) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Host {
        fun items(): List<DeckItem>
        fun isActive(tab: Tab): Boolean
        fun accent(): Int
        fun groupColor(g: TabGroup): Int
        fun selecting(): Boolean
        fun isSelected(tab: Tab): Boolean
        fun title(tab: Tab): String
        /** The site's icon if it is at hand; [onLoaded] is called when it arrives later. */
        fun icon(tab: Tab, onLoaded: () -> Unit): Bitmap?
        /** A stand-in for a page with no icon: the letter shown, or 0 for the home glyph. */
        fun letter(tab: Tab): Char
        fun onTabClick(tab: Tab)
        fun onTabClose(tab: Tab)
        fun onTabLongPress(tab: Tab, card: View)
        fun onGroupClick(g: TabGroup)
        fun onGroupLongPress(g: TabGroup, card: View)
    }

    var items: List<DeckItem> = emptyList()
        private set

    fun refresh() {
        items = host.items()
        notifyDataSetChanged()
    }

    /** Moves a card, for a drag; the caller puts the tabs in the new order. */
    fun move(from: Int, to: Int) {
        val list = items.toMutableList()
        list.add(to, list.removeAt(from))
        items = list
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = if (items[position] is DeckItem.GroupItem) 1 else 0

    class TabVH(v: View) : RecyclerView.ViewHolder(v) {
        val header: LinearLayout = v.findViewById(R.id.tabHeader)
        val icon: ImageView = v.findViewById(R.id.tabIcon)
        val letter: TextView = v.findViewById(R.id.tabLetter)
        val title: TextView = v.findViewById(R.id.tabTitle)
        val close: ImageButton = v.findViewById(R.id.closeTab)
        val check: ImageView = v.findViewById(R.id.tabCheck)
        val thumbBox: View = v.findViewById(R.id.tabThumbBox)
        val thumb: TopCropImageView = v.findViewById(R.id.tabThumb)
    }

    class Tile(val frame: FrameLayout, val image: TopCropImageView, val label: TextView)

    class GroupVH(v: View, val tiles: List<Tile>) : RecyclerView.ViewHolder(v) {
        val dot: View = v.findViewById(R.id.groupDot)
        val name: TextView = v.findViewById(R.id.groupName)
        val count: TextView = v.findViewById(R.id.groupCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        // Rounded corners clip in code: android:clipToOutline in a layout only
        // works from Android 12.
        if (viewType == 0) {
            val h = TabVH(inflater.inflate(R.layout.item_tab, parent, false))
            h.itemView.clipToOutline = true
            h.thumbBox.clipToOutline = true
            return h
        }
        val v = inflater.inflate(R.layout.item_tab_group, parent, false)
        v.clipToOutline = true
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val grid = v.findViewById<LinearLayout>(R.id.groupTiles)
        val tiles = ArrayList<Tile>(4)
        repeat(2) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            repeat(2) {
                val frame = FrameLayout(ctx)
                frame.clipToOutline = true
                val image = TopCropImageView(ctx)
                image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val label = TextView(ctx)
                label.gravity = android.view.Gravity.CENTER
                label.textSize = 15f
                label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
                label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                frame.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
                row.addView(frame, lp)
                tiles.add(Tile(frame, image, label))
            }
        }
        return GroupVH(v, tiles)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items.getOrNull(position)) {
            is DeckItem.TabItem -> bindTab(holder as TabVH, item.tab)
            is DeckItem.GroupItem -> bindGroup(holder as GroupVH, item)
            null -> Unit
        }
    }

    private fun bindTab(h: TabVH, tab: Tab) {
        val ctx = h.itemView.context
        val d = ctx.resources.displayMetrics.density
        val active = host.isActive(tab)
        val selecting = host.selecting()
        val selected = selecting && host.isSelected(tab)
        val accent = host.accent()
        val onAccent = if (android.graphics.Color.luminance(accent) > 0.6f) 0xFF16151B.toInt() else 0xFFFFFFFF.toInt()
        val ink = ContextCompat.getColor(ctx, R.color.feedFg)

        // The tab on screen: its header in the accent and a ring round the
        // card, as Samsung Internet and Chrome mark it. A chosen card, while
        // choosing, takes the ring instead.
        h.itemView.background = card(ctx, when {
            selected -> accent
            active && !selecting -> accent
            else -> null
        })
        h.itemView.elevation = (if (active) 4f else 1.5f) * d
        h.header.background = if (active && !selecting) {
            GradientDrawable().apply {
                setColor(accent)
                cornerRadii = floatArrayOf(18 * d, 18 * d, 18 * d, 18 * d, 0f, 0f, 0f, 0f)
            }
        } else null
        val fg = if (active && !selecting) onAccent else ink
        h.title.setTextColor(fg)
        h.title.text = host.title(tab)
        h.close.imageTintList = android.content.res.ColorStateList.valueOf(fg)
        h.close.visibility = if (selecting) View.INVISIBLE else View.VISIBLE
        h.close.contentDescription = "Close " + host.title(tab)

        h.check.visibility = if (selecting) View.VISIBLE else View.GONE
        if (selecting) {
            h.check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) setColor(accent) else setStroke((2 * d).toInt(), ContextCompat.getColor(ctx, R.color.sugIcon))
            }
            h.check.setImageResource(if (selected) R.drawable.deck_check else 0)
            h.check.imageTintList = android.content.res.ColorStateList.valueOf(onAccent)
        }

        bindIcon(h.icon, h.letter, tab, active && !selecting, accent, onAccent) {
            val i = items.indexOfFirst { it is DeckItem.TabItem && it.tab === tab }
            if (i >= 0) notifyItemChanged(i)
        }

        h.thumbBox.background = GradientDrawable().apply {
            cornerRadius = 15 * d
            setColor(ContextCompat.getColor(ctx, R.color.menuTileBg))
        }
        val pic = tab.thumbnail?.takeIf { !it.isRecycled }
        if (pic != null) showPicture(h.thumb, pic) else showStandIn(h.thumb, tab)

        h.itemView.contentDescription = host.title(tab) + when {
            selecting -> if (selected) ", chosen" else ""
            active -> ", current tab"
            else -> ""
        }
        h.itemView.setOnClickListener { host.onTabClick(tab) }
        h.itemView.setOnLongClickListener { v -> host.onTabLongPress(tab, v); true }
        h.close.setOnClickListener { host.onTabClose(tab) }
    }

    private fun bindGroup(h: GroupVH, item: DeckItem.GroupItem) {
        val ctx = h.itemView.context
        val d = ctx.resources.displayMetrics.density
        val g = item.group
        val color = host.groupColor(g)
        val holdsActive = item.tabs.any { host.isActive(it) }
        h.itemView.background = card(ctx, color, width = if (holdsActive) 2.5f else 1.6f)
        h.itemView.elevation = (if (holdsActive) 4f else 1.5f) * d
        h.dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        h.name.text = g.name.ifBlank { "Group" }
        h.name.setTextColor(ContextCompat.getColor(ctx, R.color.feedFg))
        h.count.text = item.tabs.size.toString()
        h.count.setTextColor(color)
        h.count.background = GradientDrawable().apply {
            cornerRadius = 11 * d
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x26))
        }
        val accent = host.accent()
        h.tiles.forEachIndexed { i, tile ->
            val tab = item.tabs.getOrNull(i)
            tile.frame.background = GradientDrawable().apply {
                cornerRadius = 11 * d
                setColor(ContextCompat.getColor(ctx, R.color.menuTileBg))
            }
            tile.label.background = null
            tile.label.text = ""
            if (tab == null) {
                showPicture(tile.image, null)
                return@forEachIndexed
            }
            val more = item.tabs.size - 4
            if (i == 3 && more > 0) {
                // The fourth tile says how many more there are.
                showPicture(tile.image, tab.thumbnail?.takeIf { !it.isRecycled })
                tile.label.text = "+" + (more + 1)
                tile.label.setTextColor(0xFFFFFFFF.toInt())
                tile.label.background = GradientDrawable().apply {
                    cornerRadius = 11 * d
                    setColor(0x99000000.toInt())
                }
                return@forEachIndexed
            }
            val pic = tab.thumbnail?.takeIf { !it.isRecycled }
            showPicture(tile.image, pic)
            if (pic == null) {
                tile.label.text = host.letter(tab).takeIf { it != 0.toChar() }?.toString() ?: ""
                tile.label.setTextColor(accent)
            }
        }
        h.itemView.contentDescription = g.name + " tab group, " + item.tabs.size +
            (if (item.tabs.size == 1) " tab" else " tabs")
        h.itemView.setOnClickListener { host.onGroupClick(g) }
        h.itemView.setOnLongClickListener { v -> host.onGroupLongPress(g, v); true }
    }

    /** The card behind a tab or group: rounded, with a hairline or, in [ring], a coloured edge. */
    private fun card(ctx: Context, ring: Int?, width: Float = 2.5f): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 18 * d
            setColor(ContextCompat.getColor(ctx, R.color.settingsCard))
            if (ring != null) setStroke((width * d).toInt(), ring)
            else setStroke(maxOf(1, d.toInt()), ContextCompat.getColor(ctx, R.color.feedHair))
        }
    }

    private fun bindIcon(
        icon: ImageView, letter: TextView, tab: Tab, onHeader: Boolean, accent: Int, onAccent: Int,
        onLoaded: () -> Unit
    ) {
        val d = icon.resources.displayMetrics.density
        val bmp = host.icon(tab, onLoaded)
        if (bmp != null) {
            icon.setImageBitmap(bmp)
            icon.imageTintList = null
            icon.background = null
            letter.text = ""
            letter.background = null
            return
        }
        val c = host.letter(tab)
        if (c == 0.toChar()) {
            // Home: the house, in the accent (or on it).
            icon.setImageResource(R.drawable.menu_home)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(if (onHeader) onAccent else accent)
            letter.text = ""
            letter.background = null
            return
        }
        icon.setImageDrawable(null)
        letter.text = c.toString()
        letter.setTextColor(if (onHeader) accent else onAccent)
        letter.background = GradientDrawable().apply {
            cornerRadius = 5 * d
            setColor(if (onHeader) onAccent else accent)
        }
    }

    /**
     * In place of a page not pictured yet (a tab not opened since the app
     * started, a new tab): its site's icon, or home's house, small and
     * quiet in the middle.
     */
    private fun showStandIn(view: TopCropImageView, tab: Tab) {
        val ctx = view.context
        val px = (34 * ctx.resources.displayMetrics.density).toInt()
        val home = host.letter(tab) == 0.toChar()
        val icon: Bitmap? = if (home) {
            ContextCompat.getDrawable(ctx, R.drawable.menu_home)?.mutate()?.let { d ->
                d.setTint(ContextCompat.getColor(ctx, R.color.sugIcon))
                d.toBitmap(px, px)
            }
        } else {
            host.icon(tab) {}?.let { Bitmap.createScaledBitmap(it, px, px, true) }
        }
        view.crop = false
        view.setImageBitmap(icon)
        view.alpha = if (icon != null && home) 0.7f else 1f
    }

    /** A page's picture, filling [view] from the top (TopCropImageView). */
    private fun showPicture(view: TopCropImageView, bmp: Bitmap?) {
        view.alpha = 1f
        view.crop = true
        if (bmp == null) view.setImageDrawable(null) else view.setImageBitmap(bmp)
    }
}
