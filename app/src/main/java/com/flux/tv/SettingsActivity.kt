package com.flux.tv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Réglages, façon Apple TV : grande icône à gauche, liste à droite. */
class SettingsActivity : FluxActivity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_settings)
        Ambience(findViewById(R.id.bgBase), findViewById(R.id.glow))
        list = findViewById(R.id.list)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        findViewById<TextView>(R.id.version).text = "Flux TV ${version ?: ""}".trim()

        build()
        list.post { list.getChildAt(1)?.requestFocus() }
    }

    private fun build() {
        list.removeAllViews()

        section("Lecture")
        row(
            R.drawable.ic_play,
            "Lecture matérielle",
            "La vidéo passe directement du décodeur à l'écran. Plus fluide sur la plupart des boîtiers ; désactive-la si l'image ou le son décroche.",
            onOff(prefs.tunneling),
        ) {
            prefs.tunneling = !prefs.tunneling
            build()
            focusRowTitled("Lecture matérielle")
        }
        row(
            R.drawable.ic_speed,
            "Mémoire tampon",
            "Rapide : la chaîne démarre en 1 s. Stable : un peu plus lent, mais moins de coupures si ta connexion est irrégulière.",
            if (prefs.stableBuffer) "Stable" else "Rapide",
        ) {
            prefs.stableBuffer = !prefs.stableBuffer
            build()
            focusRowTitled("Mémoire tampon")
        }

        if (prefs.isLoggedIn) {
            section("Chaînes")
            row(
                R.drawable.ic_refresh,
                "Actualiser la liste",
                "${Repo.channels.size} chaînes, ${Repo.categories.size} catégories",
                null,
            ) { refreshChannels() }
            row(
                R.drawable.ic_star,
                "Vider les favoris",
                "${prefs.favorites.size} chaîne(s) en favori",
                null,
            ) { confirmClearFavorites() }
        }

        section("Source des chaînes")
        if (prefs.isLoggedIn) {
            row(
                R.drawable.ic_power,
                "Changer de source",
                "Actuellement : ${prefs.sourceLabel}",
                null,
            ) { confirmLogout() }
        } else {
            row(
                R.drawable.ic_login,
                "Ajouter des chaînes",
                "Chaînes gratuites en un clic, lien M3U, ou codes Xtream de ton abonnement.",
                "Aucune",
            ) {
                startActivity(
                    Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_FROM_SETTINGS, true)
                )
            }
        }
    }

    private fun onOff(b: Boolean) = if (b) "Activée" else "Désactivée"

    private fun section(title: String) {
        val tv = TextView(this).apply {
            text = title.uppercase()
            setTextColor(getColor(R.color.white_60))
            textSize = 12f
            letterSpacing = 0.12f
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, if (list.childCount == 0) 0 else pad * 3, pad, pad)
        }
        list.addView(tv)
    }

    private fun row(
        @DrawableRes icon: Int,
        title: String,
        subtitle: String,
        value: String?,
        onClick: () -> Unit,
    ) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, list, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.subtitle).apply {
            text = subtitle
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.value).apply {
            text = value ?: ""
            visibility = if (value == null) View.GONE else View.VISIBLE
        }
        v.tag = title
        v.setOnClickListener { onClick() }
        list.addView(v)
    }

    private fun focusRowTitled(title: String) {
        list.post { list.findViewWithTag<View>(title)?.requestFocus() }
    }

    private fun refreshChannels() {
        if (refreshing) return
        refreshing = true
        toast("Mise à jour de la liste…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Repo.download(this@SettingsActivity, prefs) }
            }
            refreshing = false
            result.fold(
                onSuccess = { data ->
                    if (data.channels.isNotEmpty()) Repo.set(data)
                    toast("${data.channels.size} chaînes à jour")
                    build()
                    focusRowTitled("Actualiser la liste")
                },
                onFailure = { e -> toast("Échec de la mise à jour : ${e.message}") },
            )
        }
    }

    private fun confirmClearFavorites() {
        AlertDialog.Builder(this)
            .setTitle("Vider les favoris ?")
            .setMessage("Toutes les chaînes seront retirées de tes favoris.")
            .setPositiveButton("Vider") { _, _ ->
                prefs.favorites = emptyList()
                build()
                focusRowTitled("Vider les favoris")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Changer de source ?")
            .setMessage("Les chaînes actuelles seront retirées de l'accueil. Tes applis restent là, et tu pourras choisir une autre source.")
            .setPositiveButton("Changer") { _, _ ->
                prefs.logout()
                prefs.skipLogin = true
                Repo.clear(this)
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .putExtra(LoginActivity.EXTRA_FROM_SETTINGS, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
