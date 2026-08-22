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
import org.json.JSONObject

class SuggestAdapter(
    private var items: List<JSONObject>,
    private val onPick: (JSONObject) -> Unit,
    private val onFill: (JSONObject) -> Unit = {}
) : RecyclerView.Adapter<SuggestAdapter.VH>() {

    // The text currently in the box, so the completion can be weighted.
    private var query: String = ""

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v.findViewById(R.id.sugRow)
        val icon: ImageView = v.findViewById(R.id.sugIcon)
        val text: TextView = v.findViewById(R.id.sugText)
        val sub: TextView = v.findViewById(R.id.sugSub)
        val fill: ImageButton = v.findViewById(R.id.sugFill)
    }

    fun submit(newItems: List<JSONObject>, q: String = "") {
        items = newItems
        query = q
        notifyDataSetChanged()
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val title = item.optString("title")
        holder.text.text = weighted(title)

        val sub = item.optString("sub")
        val showSub = sub.isNotBlank() && !sub.equals(title, ignoreCase = true)
        holder.sub.text = sub
        holder.sub.visibility = if (showSub) View.VISIBLE else View.GONE

        holder.icon.setImageResource(when (item.optString("kind")) {
            "history" -> R.drawable.ic_sug_history
            "bookmark" -> R.drawable.ic_sug_bookmark
            else -> R.drawable.ic_sug_web
        })

        holder.row.setOnClickListener { onPick(item) }
        holder.fill.setOnClickListener { onFill(item) }
    }

    override fun getItemCount(): Int = items.size
}
