package com.flux.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Rangée horizontale d'applis, avec les mêmes tuiles que les chaînes. */
class AppAdapter(private val cb: Listener) : RecyclerView.Adapter<AppAdapter.VH>() {

    interface Listener {
        fun onAppFocused(app: AppEntry, tile: View)
        fun onAppClick(app: AppEntry)
    }

    private var items: List<AppEntry> = emptyList()

    init {
        setHasStableIds(true)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: View = v.findViewById(R.id.card)
        val logo: ImageView = v.findViewById(R.id.logo)
        val initials: TextView = v.findViewById(R.id.initials)
        val star: View = v.findViewById(R.id.star)
        val name: TextView = v.findViewById(R.id.name)
    }

    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = items[position].pkg.hashCode().toLong()

    // Type différent des tuiles de chaînes, car les rangées partagent leurs vues recyclées.
    override fun getItemViewType(position: Int) = VIEW_TYPE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
        val h = VH(v)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) cb.onAppClick(items[p])
        }
        v.setOnFocusChangeListener { _, hasFocus ->
            val s = if (hasFocus) 1.14f else 1f
            h.card.animate().scaleX(s).scaleY(s)
                .translationY(if (hasFocus) -6f else 0f)
                .setDuration(170).setInterpolator(DecelerateInterpolator()).start()
            h.card.translationZ = if (hasFocus) 24f else 0f
            v.translationZ = if (hasFocus) 24f else 0f
            h.name.animate().alpha(if (hasFocus) 1f else 0f).setDuration(170).start()
            val p = h.bindingAdapterPosition
            if (hasFocus && p != RecyclerView.NO_POSITION) cb.onAppFocused(items[p], v)
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val app = items[position]
        h.name.text = app.label
        h.star.visibility = View.GONE

        val focused = h.itemView.isFocused
        h.card.scaleX = if (focused) 1.14f else 1f
        h.card.scaleY = h.card.scaleX
        h.name.alpha = if (focused) 1f else 0f

        val art = app.banner ?: app.icon
        h.logo.setImageDrawable(art)
        h.initials.text = TileAdapter.initialsOf(app.label)
        h.initials.visibility = if (art == null) View.VISIBLE else View.GONE
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
        const val VIEW_TYPE = 1
    }
}
