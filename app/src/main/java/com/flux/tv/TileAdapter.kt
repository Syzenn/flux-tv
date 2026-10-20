package com.flux.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/** Une rangée horizontale de tuiles de chaînes. */
class TileAdapter(private val cb: Listener) : RecyclerView.Adapter<TileAdapter.VH>() {

    interface Listener {
        fun onTileFocused(channel: Channel, logo: ImageView, tile: View)
        fun onTileClick(channels: List<Channel>, position: Int)
        fun onTileLongPress(channel: Channel)
    }

    private var items: List<Channel> = emptyList()
    private var favorites: Set<Int> = emptySet()

    init {
        setHasStableIds(true)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: View = v.findViewById(R.id.card)
        val logo: ImageView = v.findViewById(R.id.logo)
        val initials: TextView = v.findViewById(R.id.initials)
        val star: TextView = v.findViewById(R.id.star)
        val name: TextView = v.findViewById(R.id.name)
    }

    fun submit(list: List<Channel>, favs: Set<Int>) {
        items = list
        favorites = favs
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = items[position].id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
        val h = VH(v)

        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) cb.onTileClick(items, p)
        }
        v.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) cb.onTileLongPress(items[p])
            true
        }
        v.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) cb.onTileLongPress(items[p])
                true
            } else {
                false
            }
        }
        v.setOnFocusChangeListener { _, hasFocus ->
            animateFocus(h, hasFocus)
            val p = h.bindingAdapterPosition
            if (hasFocus && p != RecyclerView.NO_POSITION) cb.onTileFocused(items[p], h.logo, v)
        }
        return h
    }

    private fun animateFocus(h: VH, focused: Boolean) {
        val s = if (focused) 1.14f else 1f
        h.card.animate()
            .scaleX(s).scaleY(s)
            .translationY(if (focused) -6f else 0f)
            .setDuration(170)
            .setInterpolator(DecelerateInterpolator())
            .start()
        h.card.translationZ = if (focused) 24f else 0f
        h.itemView.translationZ = if (focused) 24f else 0f
        h.name.animate().alpha(if (focused) 1f else 0f).setDuration(170).start()
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        h.name.text = ch.displayName
        h.star.visibility = if (ch.id in favorites) View.VISIBLE else View.GONE
        h.initials.text = initialsOf(ch.displayName)
        h.initials.visibility = View.GONE

        val focused = h.itemView.isFocused
        h.card.scaleX = if (focused) 1.14f else 1f
        h.card.scaleY = h.card.scaleX
        h.name.alpha = if (focused) 1f else 0f

        if (ch.icon.isBlank()) {
            h.logo.dispose()
            h.logo.setImageDrawable(null)
            h.initials.visibility = View.VISIBLE
        } else {
            h.logo.load(ch.icon) {
                crossfade(120)
                allowHardware(false) // nécessaire pour lire la couleur du logo
                listener(onError = { _, _ -> h.initials.visibility = View.VISIBLE })
            }
        }
    }

    override fun onViewRecycled(h: VH) {
        h.card.animate().cancel()
        h.card.scaleX = 1f
        h.card.scaleY = 1f
        h.card.translationY = 0f
        h.card.translationZ = 0f
        h.itemView.translationZ = 0f
        h.name.alpha = 0f
    }

    companion object {
        fun initialsOf(name: String): String {
            val words = name.split(" ", "-", "_").filter { it.isNotBlank() }
            return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }
        }
    }
}
