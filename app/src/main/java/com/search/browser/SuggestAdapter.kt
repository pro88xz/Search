package com.search.browser

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONObject

/**
 * Suggestions in two shapes, because they arrive in two shapes.
 *
 * A web suggestion is a query string and nothing else - "food near me" - and a
 * full-width row for one of those wastes most of a line and lets four fill the
 * screen. Those become chips, several to a line, which is what every current
 * browser does and what made this sheet look a generation old.
 *
 * A history or bookmark hit is different: it has a title AND the site it came
 * from, and a pill cannot carry two lines. Those stay as rows.
 *
 * Every chip lives in ONE list position. Chips must wrap and a
 * LinearLayoutManager cannot wrap, so rather than replace the layout manager,
 * the whole ChipGroup occupies a single item and does its own flowing.
 */
class SuggestAdapter(
    items: List<JSONObject>,
    private val onPick: (JSONObject) -> Unit,
    private val onFill: (JSONObject) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_CHIPS = 0
        const val TYPE_ROW = 1
    }

    // The text currently in the box, so the completion can be weighted.
    private var query: String = ""
    private var chips: List<JSONObject> = emptyList()
    private var rows: List<JSONObject> = emptyList()

    init { split(items) }

    /**
     * A suggestion is a query when it has nothing to say about where it leads:
     * either the engine offered it, or it is a past search, which buildSuggestions
     * marks by clearing "sub". Anything with a subtitle is a place, and needs
     * the room to show it.
     */
    private fun isQuery(o: JSONObject): Boolean =
        o.optString("kind") == "web" || o.optString("sub").isBlank()

    private fun split(items: List<JSONObject>) {
        chips = items.filter { isQuery(it) }
        rows = items.filterNot { isQuery(it) }
    }

    fun submit(newItems: List<JSONObject>, q: String = "") {
        query = q
        split(newItems)
        notifyDataSetChanged()
    }

    class ChipsVH(v: View) : RecyclerView.ViewHolder(v) {
        val group: ChipGroup = v as ChipGroup
    }

    class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v.findViewById(R.id.sugRow)
        val icon: ImageView = v.findViewById(R.id.sugIcon)
        val text: TextView = v.findViewById(R.id.sugText)
        val sub: TextView = v.findViewById(R.id.sugSub)
        val fill: ImageButton = v.findViewById(R.id.sugFill)
    }

    /**
     * Weights the part of a suggestion the user has NOT typed, so the eye lands
     * on what each row adds rather than re-reading the query on every line.
     * Falls back to plain text when the query does not occur in the title.
     */
    private fun weighted(title: String): CharSequence {
        val q = query.trim()
        if (q.isEmpty() || title.isEmpty()) return title
        val at = title.lowercase().indexOf(q.lowercase())
        if (at < 0) return title
        val sp = SpannableString(title)
        val end = at + q.length
        if (at > 0) {
            sp.setSpan(StyleSpan(Typeface.BOLD), 0, at, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (end < title.length) {
            sp.setSpan(StyleSpan(Typeface.BOLD), end, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun hasChips() = chips.isNotEmpty()

    override fun getItemCount(): Int = (if (hasChips()) 1 else 0) + rows.size

    override fun getItemViewType(position: Int): Int =
        if (position == 0 && hasChips()) TYPE_CHIPS else TYPE_ROW

    private fun rowAt(position: Int): JSONObject =
        rows[if (hasChips()) position - 1 else position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CHIPS)
            ChipsVH(inf.inflate(R.layout.item_suggest_chips, parent, false))
        else
            RowVH(inf.inflate(R.layout.item_suggestion, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ChipsVH) {
            // Rebuilt rather than recycled: the set changes on every keystroke
            // and there are only ever a handful.
            holder.group.removeAllViews()
            val inf = LayoutInflater.from(holder.group.context)
            chips.forEach { item ->
                val chip = inf.inflate(R.layout.item_suggest_chip, holder.group, false) as Chip
                chip.text = weighted(item.optString("title"))
                chip.setOnClickListener { onPick(item) }
                holder.group.addView(chip)
            }
            return
        }

        val h = holder as RowVH
        val item = rowAt(position)
        val title = item.optString("title")
        h.text.text = weighted(title)

        val sub = item.optString("sub")
        val showSub = sub.isNotBlank() && !sub.equals(title, ignoreCase = true)
        h.sub.text = sub
        h.sub.visibility = if (showSub) View.VISIBLE else View.GONE

        h.icon.setImageResource(when (item.optString("kind")) {
            "history" -> R.drawable.ic_sug_history
            "bookmark" -> R.drawable.ic_sug_bookmark
            else -> R.drawable.ic_sug_web
        })

        h.row.setOnClickListener { onPick(item) }
        h.fill.setOnClickListener { onFill(item) }
    }
}
