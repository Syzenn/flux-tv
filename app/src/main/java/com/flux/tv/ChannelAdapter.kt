package com.flux.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class ChannelAdapter(
    private val onClick: (Int) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()
    private var favorites: Set<Int> = emptySet()

    init {
        setHasStableIds(true)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val initials: TextView = v.findViewById(R.id.initials)
        val name: TextView = v.findViewById(R.id.name)
        val star: TextView = v.findViewById(R.id.star)
    }

    fun submit(list: List<Channel>, favs: Set<Int>) {
        items = list
        favorites = favs
        notifyDataSetChanged()
    }

    fun updateFavorites(favs: Set<Int>) {
        favorites = favs
        notifyItemRangeChanged(0, itemCount, PAYLOAD_FAV)
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = items[position].id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        val holder = VH(v)

        v.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        v.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onToggleFavorite(items[p])
            true
        }
        v.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                val p = holder.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onToggleFavorite(items[p])
                true
            } else {
                false
            }
        }
        v.setOnFocusChangeListener { view, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1f
            view.animate().scaleX(scale).scaleY(scale).setDuration(110).start()
            view.translationZ = if (hasFocus) 8f else 0f
        }
        return holder
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_FAV)) {
            holder.star.visibility = if (items[position].id in favorites) View.VISIBLE else View.GONE
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.displayName
        holder.star.visibility = if (ch.id in favorites) View.VISIBLE else View.GONE
        holder.initials.text = initialsOf(ch.displayName)
        holder.initials.visibility = View.GONE

        if (ch.icon.isBlank()) {
            holder.logo.dispose()
            holder.logo.setImageDrawable(null)
            holder.initials.visibility = View.VISIBLE
        } else {
            holder.logo.load(ch.icon) {
                crossfade(false)
                listener(
                    onError = { _, _ -> holder.initials.visibility = View.VISIBLE },
                )
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
    }

    companion object {
        private const val PAYLOAD_FAV = "fav"

        fun initialsOf(name: String): String {
            val words = name.split(" ", "-", "_").filter { it.isNotBlank() }
            return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }
        }
    }
}
