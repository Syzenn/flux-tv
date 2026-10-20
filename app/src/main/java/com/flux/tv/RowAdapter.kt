package com.flux.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Une rangée de l'accueil : soit des chaînes, soit des applis. */
class Row(
    val key: String,
    val title: String,
    val channels: List<Channel> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    /** Rangée posée sur une étagère en verre dépoli (Applis, Favoris). */
    val onShelf: Boolean = false,
) {
    val isApps get() = apps.isNotEmpty()
    val isFavorites get() = key == "fav"
    val size get() = if (isApps) apps.size else channels.size
}

/** Liste verticale de rangées. */
class RowAdapter(
    private val tileCb: TileAdapter.Listener,
    private val appCb: AppAdapter.Listener,
) : RecyclerView.Adapter<RowAdapter.VH>() {

    var rows: List<Row> = emptyList()
        private set
    private var favorites: Set<Int> = emptySet()

    private val pool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 48)
        setMaxRecycledViews(AppAdapter.VIEW_TYPE, 24)
    }

    init {
        setHasStableIds(true)
    }

    class VH(v: View, tileCb: TileAdapter.Listener, appCb: AppAdapter.Listener) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val shelf: FrameLayout = v.findViewById(R.id.shelf)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        val tiles = TileAdapter(tileCb)
        val apps = AppAdapter(appCb)
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
        val h = VH(v, tileCb, appCb)
        h.list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false).apply {
            initialPrefetchItemCount = 6
        }
        h.list.setRecycledViewPool(pool)
        h.list.itemAnimator = null
        h.list.setHasFixedSize(true)
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = rows[position]
        h.title.text = row.title

        // Les tuiles qui défilent sont coupées proprement sur les bords arrondis de la rangée.
        if (row.onShelf) {
            h.shelf.background = ContextCompat.getDrawable(h.itemView.context, R.drawable.shelf_bg)
            h.shelf.outlineProvider = ViewOutlineProvider.BACKGROUND
        } else {
            h.shelf.background = null
            h.shelf.outlineProvider = ViewOutlineProvider.BOUNDS
        }
        h.shelf.clipToOutline = true

        if (row.isApps) {
            if (h.list.adapter !== h.apps) h.list.adapter = h.apps
            h.apps.submit(row.apps)
        } else {
            if (h.list.adapter !== h.tiles) h.list.adapter = h.tiles
            h.tiles.submit(row.channels, favorites)
        }
    }
}
