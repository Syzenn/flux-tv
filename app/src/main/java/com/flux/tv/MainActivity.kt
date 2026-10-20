package com.flux.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var categoriesView: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var title: TextView
    private lateinit var empty: TextView
    private lateinit var loading: ProgressBar
    private lateinit var catAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var current: Section? = null
    private var shown: List<Channel> = emptyList()
    private var refreshing = false

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSelect: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        categoriesView = findViewById(R.id.categories)
        grid = findViewById(R.id.grid)
        title = findViewById(R.id.sectionTitle)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)

        catAdapter = CategoryAdapter(
            onFocus = { s -> if (!s.isAction) selectSoon(s) },
            onClick = { s ->
                when (s) {
                    Section.Refresh -> refresh(userAsked = true)
                    Section.Logout -> confirmLogout()
                    else -> {
                        cancelPendingSelect()
                        select(s)
                        focusGrid()
                    }
                }
            },
        )
        categoriesView.layoutManager = LinearLayoutManager(this)
        categoriesView.itemAnimator = null
        categoriesView.adapter = catAdapter

        channelAdapter = ChannelAdapter(
            onClick = { position -> play(position) },
            onToggleFavorite = { ch -> toggleFavorite(ch) },
        )
        grid.layoutManager = GridLayoutManager(this, 5)
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(30)
        grid.adapter = channelAdapter

        start()
    }

    override fun onResume() {
        super.onResume()
        // Les favoris peuvent avoir changé : on rafraîchit l'affichage.
        if (::channelAdapter.isInitialized && Repo.hasData) {
            if (current === Section.Favorites) select(Section.Favorites)
            else channelAdapter.updateFavorites(prefs.favorites.toSet())
        }
    }

    private fun start() {
        if (Repo.hasData) {
            showSections(firstLoad = true)
            return
        }
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) { Repo.readCache(this@MainActivity) }
            if (cached != null) {
                Repo.set(cached)
                loading.visibility = View.GONE
                showSections(firstLoad = true)
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
            val api = prefs.api()
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
                    showSections(firstLoad = !hadData)
                    if (userAsked) toast("${data.channels.size} chaînes à jour")
                },
                onFailure = { e ->
                    if (!hadData) {
                        showEmpty("Impossible de charger les chaînes.\n${e.message ?: ""}\n\nVa sur « Actualiser la liste » pour réessayer.")
                        buildSidebar()
                    } else if (userAsked) {
                        toast("Échec de la mise à jour : ${e.message}")
                    }
                },
            )
        }
    }

    private fun buildSidebar() {
        val sections = ArrayList<Section>()
        sections.add(Section.Favorites)
        sections.add(Section.All)
        Repo.categories.forEach { sections.add(Section.Cat(it)) }
        sections.add(Section.Refresh)
        sections.add(Section.Logout)
        catAdapter.submit(sections, current)
    }

    private fun showSections(firstLoad: Boolean) {
        buildSidebar()
        val target = current ?: if (prefs.favorites.any { Repo.byId.containsKey(it) }) Section.Favorites else Section.All
        // Après une mise à jour, la catégorie affichée a pu disparaître.
        val stillExists = target !is Section.Cat || Repo.byCategory.containsKey(target.category.id)
        select(if (stillExists) target else Section.All)
        if (firstLoad) {
            if (shown.isNotEmpty()) focusGrid() else focusSidebar()
        }
    }

    private fun selectSoon(s: Section) {
        if (s.sameAs(current)) return
        cancelPendingSelect()
        val r = Runnable { select(s) }
        pendingSelect = r
        handler.postDelayed(r, 160)
    }

    private fun cancelPendingSelect() {
        pendingSelect?.let { handler.removeCallbacks(it) }
        pendingSelect = null
    }

    private fun select(s: Section) {
        current = s
        val favs = prefs.favorites
        shown = when (s) {
            Section.Favorites -> favs.mapNotNull { Repo.byId[it] }
            Section.All -> Repo.channels
            is Section.Cat -> Repo.byCategory[s.category.id] ?: emptyList()
            else -> shown
        }
        title.text = when (s) {
            is Section.Cat -> s.category.name
            Section.Favorites -> "Favoris"
            else -> s.label
        }
        catAdapter.setSelected(s)
        channelAdapter.submit(shown, favs.toSet())
        grid.scrollToPosition(0)

        if (shown.isEmpty()) {
            showEmpty(
                if (s === Section.Favorites) {
                    "Aucun favori pour l'instant.\n\nOuvre une catégorie, place-toi sur une chaîne\net fais un appui long sur OK pour l'ajouter ici."
                } else {
                    "Aucune chaîne dans cette catégorie."
                }
            )
        } else {
            empty.visibility = View.GONE
        }
    }

    private fun showEmpty(msg: String) {
        empty.text = msg
        empty.visibility = View.VISIBLE
    }

    private fun focusGrid() {
        grid.post {
            val first = grid.findViewHolderForAdapterPosition(0)?.itemView
            if (first != null) first.requestFocus() else grid.requestFocus()
        }
    }

    private fun focusSidebar() {
        categoriesView.post {
            val i = current?.let { catAdapter.indexOf(it) }?.coerceAtLeast(0) ?: 0
            categoriesView.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus()
        }
    }

    private fun play(position: Int) {
        if (position !in shown.indices) return
        Repo.playlist = shown.map { it.id }
        startActivity(
            Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, position)
        )
    }

    private fun toggleFavorite(ch: Channel) {
        val added = prefs.toggleFavorite(ch.id)
        toast(if (added) "★ ${ch.displayName} ajoutée aux favoris" else "${ch.displayName} retirée des favoris")
        if (current === Section.Favorites) {
            val focused = grid.focusedChild?.let { grid.getChildAdapterPosition(it) } ?: 0
            select(Section.Favorites)
            grid.post {
                val target = focused.coerceAtMost(shown.size - 1)
                if (target >= 0) grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                else focusSidebar()
            }
        } else {
            channelAdapter.updateFavorites(prefs.favorites.toSet())
        }
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
        cancelPendingSelect()
        super.onDestroy()
    }
}
