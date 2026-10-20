package com.flux.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class Row(val key: String, val title: String, val channels: List<Channel>, val isFavorites: Boolean)

/** Liste verticale de rangées : Favoris en premier, puis une rangée par catégorie. */
class RowAdapter(private val cb: TileAdapter.Listener) : RecyclerView.Adapter<RowAdapter.VH>() {

    var rows: List<Row> = emptyList()
        private set
    private var favorites: Set<Int> = emptySet()

    private val pool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 48) }

    init {
        setHasStableIds(true)
    }

    class VH(v: View, cb: TileAdapter.Listener) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val shelf: FrameLayout = v.findViewById(R.id.shelf)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        val tiles = TileAdapter(cb)
    }

    fun submit(list: List<Row>, favs: Set<Int>) {
        rows = list
        favorites = favs
        notifyDataSetChanged()
    }

    fun indexOfKey(key: String) = rows.indexOfFirst { it.key == key }

    override fun getItemCount() = rows.size

    override fun getItemId(position: Int) = rows[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        val h = VH(v, cb)
        h.list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false).apply {
            initialPrefetchItemCount = 6
        }
        h.list.setRecycledViewPool(pool)
        h.list.itemAnimator = null
        h.list.setHasFixedSize(true)
        h.list.adapter = h.tiles
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = rows[position]
        h.title.text = row.title
        h.shelf.background = if (row.isFavorites) {
            ContextCompat.getDrawable(h.itemView.context, R.drawable.shelf_bg)
        } else {
            null
        }
        h.tiles.submit(row.channels, favorites)
    }
}
