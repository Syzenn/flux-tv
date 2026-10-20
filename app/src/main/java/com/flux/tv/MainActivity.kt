package com.flux.tv

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
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

class MainActivity : FluxActivity(), TileAdapter.Listener, AppAdapter.Listener {

    private lateinit var prefs: Prefs
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

    private var apps: List<AppEntry> = emptyList()
    private var appsKey = ""
    private var refreshing = false
    private var shownVersion = -1
    private var shownFavorites: List<Int> = emptyList()
    private var shownLoggedIn = false
    private var firstFocusDone = false
    private var focusedKey = ""
    private var focusedRow = -1
    private var epgJob: Job? = null
    private val colorCache = HashMap<String, Int>()

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
        if (!prefs.isLoggedIn && !prefs.skipLogin) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
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

        rowAdapter = RowAdapter(this, this)
        rowsView.layoutManager = LinearLayoutManager(this)
        rowsView.itemAnimator = null
        rowsView.setItemViewCacheSize(4)
        rowsView.adapter = rowAdapter

        findViewById<View>(R.id.btnRefresh).setOnClickListener { refresh(userAsked = true) }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        heroTitle.text = "Bienvenue"
        updateClock()
        start()
    }

    override fun onResume() {
        super.onResume()
        if (!::clock.isInitialized) return
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)

        // Une appli a pu être installée ou supprimée pendant notre absence.
        if (firstFocusDone) reloadApps()

        // Les réglages ont pu changer la liste, les favoris ou la connexion.
        if (shownVersion >= 0 &&
            (Repo.version != shownVersion || prefs.favorites != shownFavorites || prefs.isLoggedIn != shownLoggedIn)
        ) {
            rebuildKeepingFocus()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(clockTick)
        super.onPause()
    }

    // ---------- Chargement ----------

    private fun start() {
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) { Apps.load(this@MainActivity) }
            appsKey = apps.joinToString(",") { it.pkg }

            if (!prefs.isLoggedIn) {
                loading.visibility = View.GONE
                showRows()
                return@launch
            }
            if (!Repo.hasData) {
                val cached = withContext(Dispatchers.IO) { Repo.readCache(this@MainActivity) }
                if (cached != null) Repo.set(cached)
            }
            if (Repo.hasData) {
                loading.visibility = View.GONE
                showRows()
                refresh(userAsked = false) // mise à jour discrète en arrière-plan
            } else {
                showRows() // les applis s'affichent pendant le premier chargement des chaînes
                loading.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                refresh(userAsked = false)
            }
        }
    }

    private fun reloadApps() {
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { Apps.load(this@MainActivity) }
            val key = fresh.joinToString(",") { it.pkg }
            if (key != appsKey) {
                apps = fresh
                appsKey = key
                rebuildKeepingFocus()
            }
        }
    }

    private fun refresh(userAsked: Boolean) {
        if (!prefs.isLoggedIn) {
            reloadApps()
            if (userAsked) toast("Applis à jour")
            return
        }
        if (refreshing) return
        refreshing = true
        val hadData = Repo.hasData
        if (userAsked) toast("Mise à jour de la liste…")

        lifecycleScope.launch {
            val api = prefs.api()
            val result = withContext(Dispatchers.IO) {
                runCatching { Repo.download(this@MainActivity, api) }
            }
            refreshing = false
            loading.visibility = View.GONE
            result.fold(
                onSuccess = { data ->
                    if (data.channels.isEmpty()) {
                        if (!hadData) toast("Aucune chaîne trouvée sur ce serveur.")
                        return@fold
                    }
                    Repo.set(data)
                    rebuildKeepingFocus()
                    if (userAsked) toast("${data.channels.size} chaînes à jour")
                },
                onFailure = { e ->
                    if (!hadData || userAsked) toast("Impossible de charger les chaînes : ${e.message ?: "erreur réseau"}")
                    if (rowAdapter.itemCount == 0) {
                        showEmpty("Impossible de charger les chaînes.\nAppuie sur « Actualiser » en haut pour réessayer.")
                        findViewById<View>(R.id.btnRefresh).requestFocus()
                    }
                },
            )
        }
    }

    private fun buildRows(): List<Row> {
        val rows = ArrayList<Row>()
        val appsRow = if (apps.isNotEmpty()) Row("apps", "Applis", apps = apps, onShelf = true) else null

        val favs = if (prefs.isLoggedIn) prefs.favorites.mapNotNull { Repo.byId[it] } else emptyList()
        if (favs.isNotEmpty()) {
            // Avec des favoris, on les met tout en haut : c'est ce qu'on ouvre le plus souvent.
            rows.add(Row("fav", "Favoris", channels = favs, onShelf = true))
            appsRow?.let { rows.add(it) }
        } else {
            appsRow?.let { rows.add(it) }
        }
        if (prefs.isLoggedIn) {
            for (cat in Repo.categories) {
                val list = Repo.byCategory[cat.id] ?: continue
                rows.add(Row("c:${cat.id}", cat.name, channels = list))
            }
        }
        return rows
    }

    private fun recordShown() {
        shownVersion = Repo.version
        shownFavorites = prefs.favorites
        shownLoggedIn = prefs.isLoggedIn
    }

    private fun showRows() {
        val rows = buildRows()
        recordShown()
        rowAdapter.submit(rows, shownFavorites.toSet())
        if (rows.isEmpty()) {
            showEmpty("Rien à afficher pour l'instant.")
            return
        }
        empty.visibility = View.GONE
        if (!firstFocusDone) {
            firstFocusDone = true
            focusTile(0, 0)
        }
    }

    /** Reconstruit les rangées en gardant la sélection au même endroit. */
    private fun rebuildKeepingFocus() {
        if (!firstFocusDone) {
            showRows()
            return
        }
        val rowIndex = currentRowIndex()
        val rowKey = rowAdapter.rows.getOrNull(rowIndex)?.key
        val tilePos = currentTilePos()
        val hadFocusInRows = rowIndex >= 0

        val rows = buildRows()
        recordShown()
        rowAdapter.submit(rows, shownFavorites.toSet())
        if (rows.isEmpty()) {
            showEmpty("Rien à afficher pour l'instant.")
            return
        }
        empty.visibility = View.GONE
        if (!hadFocusInRows) return // la sélection est sur un bouton en haut : on n'y touche pas
        val target = rowKey?.let { rowAdapter.indexOfKey(it) }?.takeIf { it >= 0 } ?: 0
        focusTile(target, tilePos.coerceIn(0, rows[target].size - 1))
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

    // ---------- Tuiles de chaînes ----------

    override fun onTileFocused(channel: Channel, logo: ImageView, tile: View) {
        val key = "c:${channel.id}"
        focusedKey = key
        heroTitle.text = channel.displayName
        heroSub.text = Epg.cached(channel.id)?.let { Epg.headline(it) } ?: ""
        heroHint.text = if (channel.id in prefs.favorites) {
            "Appui long sur OK pour retirer des favoris"
        } else {
            "Appui long sur OK pour ajouter aux favoris"
        }
        pickColor(key, logo.drawable, Ambience.fallback(channel.id))
        liftRowOf(tile)

        epgJob?.cancel()
        if (Epg.cached(channel.id) == null && prefs.isLoggedIn) {
            val api = prefs.api()
            epgJob = lifecycleScope.launch {
                delay(350) // on attend que la sélection se pose avant d'interroger le serveur
                val list = Epg.get(api, channel.id)
                if (focusedKey == key) heroSub.text = Epg.headline(list)
            }
        }
    }

    override fun onTileClick(channels: List<Channel>, position: Int) {
        if (position !in channels.indices) return
        Repo.playlist = channels.map { it.id }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, position))
    }

    override fun onTileLongPress(channel: Channel) {
        val added = prefs.toggleFavorite(channel.id)
        toast(if (added) "${channel.displayName} ajoutée aux favoris" else "${channel.displayName} retirée des favoris")
        rebuildKeepingFocus()
    }

    // ---------- Tuiles d'applis ----------

    override fun onAppFocused(app: AppEntry, tile: View) {
        val key = "a:${app.pkg}"
        focusedKey = key
        epgJob?.cancel()
        heroTitle.text = app.label
        heroSub.text = "Application"
        heroHint.text = if (prefs.isLoggedIn) {
            "OK pour ouvrir"
        } else {
            "OK pour ouvrir  ·  Ajoute un abonnement Xtream dans Réglages pour voir tes chaînes ici"
        }
        pickColor(key, app.banner ?: app.icon, Ambience.fallback(app.pkg.hashCode()))
        liftRowOf(tile)
    }

    override fun onAppClick(app: AppEntry) {
        try {
            startActivity(app.launch)
        } catch (e: Exception) {
            val fallback = packageManager.getLeanbackLaunchIntentForPackage(app.pkg)
                ?: packageManager.getLaunchIntentForPackage(app.pkg)
            if (fallback != null) startActivity(fallback) else toast("Impossible d'ouvrir ${app.label}")
        }
    }

    // ---------- Mise en scène ----------

    /** La rangée sélectionnée remonte en haut, comme sur Apple TV. */
    private fun liftRowOf(tile: View) {
        val rowIndex = rowsView.findContainingViewHolder(tile)?.bindingAdapterPosition ?: -1
        if (rowIndex >= 0 && rowIndex != focusedRow) {
            focusedRow = rowIndex
            val scroller = object : LinearSmoothScroller(this) {
                override fun getVerticalSnapPreference() = SNAP_TO_START
                override fun calculateSpeedPerPixel(dm: DisplayMetrics) = 55f / dm.densityDpi
            }
            scroller.targetPosition = rowIndex
            rowsView.layoutManager?.startSmoothScroll(scroller)
        }
    }

    /** Le fond prend la couleur dominante du logo ou de l'icône sélectionnée. */
    private fun pickColor(key: String, art: Drawable?, fallback: Int) {
        colorCache[key]?.let {
            ambience.setColor(it)
            return
        }
        val bmp = art?.let { d ->
            runCatching {
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
                val scale = 96f / maxOf(w, h)
                d.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }.getOrNull()
        }
        if (bmp == null) {
            ambience.setColor(fallback)
            return
        }
        Palette.from(bmp).maximumColorCount(16).generate { p ->
            val raw = p?.vibrantSwatch?.rgb ?: p?.dominantSwatch?.rgb
            val c = raw?.let { Ambience.richen(it) } ?: fallback
            colorCache[key] = c
            if (focusedKey == key) ambience.setColor(c)
        }
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::ambience.isInitialized) ambience.cancel()
        super.onDestroy()
    }
}
