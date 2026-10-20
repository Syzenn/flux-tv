package com.flux.tv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TileAdapter.Listener {

    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var ambience: Ambience
    private lateinit var rowsView: RecyclerView
    private lateinit var rowAdapter: RowAdapter
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroHint: TextView
    private lateinit var greeting: TextView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView

    private var refreshing = false
    private var focusedId = -1
    private var focusedRow = -1
    private var epgJob: Job? = null
    private val colorCache = HashMap<Int, Int>()

    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val dateFormat = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        api = prefs.api()
        setContentView(R.layout.activity_main)

        ambience = Ambience(findViewById(R.id.bgBase), findViewById(R.id.glow))
        rowsView = findViewById(R.id.rows)
        heroTitle = findViewById(R.id.heroTitle)
        heroSub = findViewById(R.id.heroSub)
        heroHint = findViewById(R.id.heroHint)
        greeting = findViewById(R.id.greeting)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        loading = findViewById(R.id.loading)
        empty = findViewById(R.id.empty)

        rowAdapter = RowAdapter(this)
        rowsView.layoutManager = LinearLayoutManager(this)
        rowsView.itemAnimator = null
        rowsView.setItemViewCacheSize(4)
        rowsView.adapter = rowAdapter

        findViewById<View>(R.id.btnRefresh).setOnClickListener { refresh(userAsked = true) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { openSettings() }

        heroTitle.text = "Bienvenue"
        updateClock()
        start()
    }

    override fun onResume() {
        super.onResume()
        if (::clock.isInitialized) {
            handler.removeCallbacks(clockTick)
            handler.post(clockTick)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(clockTick)
        super.onPause()
    }

    // ---------- Chargement ----------

    private fun start() {
        if (Repo.hasData) {
            showRows(firstLoad = true)
            return
        }
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) { Repo.readCache(this@MainActivity) }
            if (cached != null) {
                Repo.set(cached)
                loading.visibility = View.GONE
                showRows(firstLoad = true)
                refresh(userAsked = false) // mise à jour discrète en arrière-plan
            } else {
                refresh(userAsked = false)
            }
        }
    }

    private fun refresh(userAsked: Boolean) {
        if (refreshing) return
        refreshing = true
        val hadData = Repo.hasData
        if (!hadData) {
            loading.visibility = View.VISIBLE
            empty.visibility = View.GONE
        }
        if (userAsked) toast("Mise à jour de la liste…")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Repo.download(this@MainActivity, api) }
            }
            refreshing = false
            loading.visibility = View.GONE
            result.fold(
                onSuccess = { data ->
                    if (data.channels.isEmpty()) {
                        if (!hadData) showEmpty("Aucune chaîne trouvée sur ce serveur.")
                        return@fold
                    }
                    Repo.set(data)
                    if (hadData) rebuildKeepingFocus() else showRows(firstLoad = true)
                    if (userAsked) toast("${data.channels.size} chaînes à jour")
                },
                onFailure = { e ->
                    if (!hadData) {
                        showEmpty("Impossible de charger les chaînes.\n${e.message ?: ""}\n\nAppuie sur « Actualiser » en haut pour réessayer.")
                        findViewById<View>(R.id.btnRefresh).requestFocus()
                    } else if (userAsked) {
                        toast("Échec de la mise à jour : ${e.message}")
                    }
                },
            )
        }
    }

    private fun buildRows(): List<Row> {
        val rows = ArrayList<Row>()
        val favs = prefs.favorites.mapNotNull { Repo.byId[it] }
        if (favs.isNotEmpty()) rows.add(Row("fav", "Favoris", favs, isFavorites = true))
        for (cat in Repo.categories) {
            val list = Repo.byCategory[cat.id] ?: continue
            rows.add(Row("c:${cat.id}", cat.name, list, isFavorites = false))
        }
        return rows
    }

    private fun showRows(firstLoad: Boolean) {
        val rows = buildRows()
        rowAdapter.submit(rows, prefs.favorites.toSet())
        if (rows.isEmpty()) {
            showEmpty("Aucune chaîne pour l'instant.")
            return
        }
        empty.visibility = View.GONE
        updateHint(rows.first().isFavorites)
        if (firstLoad) focusTile(0, 0)
    }

    /** Reconstruit les rangées en gardant la sélection au même endroit. */
    private fun rebuildKeepingFocus() {
        val rowIndex = currentRowIndex()
        val rowKey = rowAdapter.rows.getOrNull(rowIndex)?.key
        val tilePos = currentTilePos()

        val rows = buildRows()
        rowAdapter.submit(rows, prefs.favorites.toSet())
        if (rows.isEmpty()) {
            showEmpty("Aucune chaîne pour l'instant.")
            return
        }
        empty.visibility = View.GONE
        val target = rowKey?.let { rowAdapter.indexOfKey(it) }?.takeIf { it >= 0 } ?: 0
        val size = rows[target].channels.size
        focusTile(target, tilePos.coerceIn(0, size - 1))
    }

    private fun currentRowIndex(): Int {
        val child = rowsView.focusedChild ?: return -1
        return rowsView.getChildAdapterPosition(child)
    }

    private fun currentTilePos(): Int {
        val rowChild = rowsView.focusedChild ?: return 0
        val list = rowChild.findViewById<RecyclerView>(R.id.rowList) ?: return 0
        val tile = list.focusedChild ?: return 0
        return list.getChildAdapterPosition(tile).coerceAtLeast(0)
    }

    private fun focusTile(rowIndex: Int, tilePos: Int, attempt: Int = 0) {
        if (attempt == 0) {
            focusedRow = rowIndex
            (rowsView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(rowIndex, 0)
        }
        // On attend que la liste soit dessinée avant de placer la sélection.
        rowsView.postDelayed({
            val row = rowsView.findViewHolderForAdapterPosition(rowIndex) as? RowAdapter.VH
            if (row == null) {
                if (attempt < 8) focusTile(rowIndex, tilePos, attempt + 1)
                return@postDelayed
            }
            if (attempt == 0 || row.list.findViewHolderForAdapterPosition(tilePos) == null) {
                (row.list.layoutManager as LinearLayoutManager).scrollToPosition(tilePos)
            }
            row.list.postDelayed({
                val tile = row.list.findViewHolderForAdapterPosition(tilePos)?.itemView
                if (tile != null) tile.requestFocus()
                else if (attempt < 8) focusTile(rowIndex, tilePos, attempt + 1)
            }, 16)
        }, 16)
    }

    private fun showEmpty(msg: String) {
        empty.text = msg
        empty.visibility = View.VISIBLE
    }

    // ---------- Tuiles ----------

    override fun onTileFocused(channel: Channel, logo: ImageView, tile: View) {
        focusedId = channel.id
        heroTitle.text = channel.displayName
        heroSub.text = Epg.cached(channel.id)?.let { Epg.headline(it) } ?: ""
        updateHint(channel.id in prefs.favorites)
        pickColor(channel, logo)

        // La rangée sélectionnée remonte en haut, comme sur Apple TV.
        val rowIndex = rowsView.findContainingViewHolder(tile)?.bindingAdapterPosition ?: -1
        if (rowIndex >= 0 && rowIndex != focusedRow) {
            focusedRow = rowIndex
            smoothScrollToRow(rowIndex)
        }

        epgJob?.cancel()
        if (Epg.cached(channel.id) == null) {
            epgJob = lifecycleScope.launch {
                delay(350) // on attend que le doigt se pose avant d'interroger le serveur
                val list = Epg.get(api, channel.id)
                if (focusedId == channel.id) heroSub.text = Epg.headline(list)
            }
        }
    }

    private fun updateHint(isFavorite: Boolean) {
        heroHint.text = if (isFavorite) {
            "Appui long sur OK pour retirer des favoris"
        } else {
            "Appui long sur OK pour ajouter aux favoris"
        }
    }

    private fun smoothScrollToRow(index: Int) {
        val scroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
            override fun calculateSpeedPerPixel(dm: DisplayMetrics) = 55f / dm.densityDpi
        }
        scroller.targetPosition = index
        rowsView.layoutManager?.startSmoothScroll(scroller)
    }

    /** Couleur de la lueur : tirée du logo de la chaîne, sinon une couleur fixe par chaîne. */
    private fun pickColor(channel: Channel, logo: ImageView) {
        colorCache[channel.id]?.let {
            ambience.setColor(it)
            return
        }
        val bmp = (logo.drawable as? BitmapDrawable)?.bitmap
        val unusable = bmp == null || bmp.isRecycled ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE)
        if (unusable) {
            ambience.setColor(Ambience.fallback(channel.id))
            return
        }
        Palette.from(bmp!!).maximumColorCount(16).generate { p ->
            val raw = p?.vibrantSwatch?.rgb ?: p?.dominantSwatch?.rgb
            val c = raw?.let { Ambience.richen(it) } ?: Ambience.fallback(channel.id)
            colorCache[channel.id] = c
            if (focusedId == channel.id) ambience.setColor(c)
        }
    }

    override fun onTileClick(channels: List<Channel>, position: Int) {
        if (position !in channels.indices) return
        Repo.playlist = channels.map { it.id }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, position))
    }

    override fun onTileLongPress(channel: Channel) {
        val added = prefs.toggleFavorite(channel.id)
        toast(if (added) "★  ${channel.displayName} ajoutée aux favoris" else "${channel.displayName} retirée des favoris")
        rebuildKeepingFocus()
    }

    // ---------- En-tête ----------

    private fun updateClock() {
        val now = Date()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greeting.text = when (hour) {
            in 5..11 -> "BONJOUR"
            in 12..17 -> "BON APRÈS-MIDI"
            in 18..21 -> "BONSOIR"
            else -> "BONNE NUIT"
        }
        clock.text = clockFormat.format(now)
        date.text = dateFormat.format(now).uppercase(Locale.FRANCE)
    }

    private fun openSettings() {
        AlertDialog.Builder(this)
            .setTitle("Réglages")
            .setItems(arrayOf("Actualiser la liste des chaînes", "Se déconnecter")) { _, which ->
                when (which) {
                    0 -> refresh(userAsked = true)
                    1 -> confirmLogout()
                }
            }
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Se déconnecter ?")
            .setMessage("Tes favoris sont conservés.")
            .setPositiveButton("Se déconnecter") { _, _ ->
                prefs.logout()
                Repo.clear(this)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::ambience.isInitialized) ambience.cancel()
        super.onDestroy()
    }
}
