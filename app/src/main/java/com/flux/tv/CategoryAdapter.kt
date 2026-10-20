package com.flux.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CategoryAdapter(
    private val onFocus: (Section) -> Unit,
    private val onClick: (Section) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private var items: List<Section> = emptyList()
    private var selected = -1

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    fun submit(list: List<Section>, current: Section?) {
        items = list
        selected = list.indexOfFirst { it.sameAs(current) }
        notifyDataSetChanged()
    }

    fun setSelected(section: Section) {
        val i = items.indexOfFirst { it.sameAs(section) }
        if (i == selected) return
        val old = selected
        selected = i
        if (old >= 0) notifyItemChanged(old)
        if (i >= 0) notifyItemChanged(i)
    }

    fun indexOf(section: Section) = items.indexOfFirst { it.sameAs(section) }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView
        val holder = VH(v)
        v.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p])
        }
        v.setOnFocusChangeListener { _, hasFocus ->
            val p = holder.bindingAdapterPosition
            if (hasFocus && p != RecyclerView.NO_POSITION) onFocus(items[p])
        }
        return holder
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.text.text = s.label
        holder.text.isActivated = position == selected
        holder.text.alpha = if (s.isAction) 0.65f else 1f
    }
}
