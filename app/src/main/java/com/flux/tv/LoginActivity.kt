package com.flux.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : FluxActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        // Ouvert depuis les Réglages : on affiche toujours le formulaire.
        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        if (prefs.isLoggedIn || (prefs.skipLogin && !fromSettings)) {
            openHome()
            return
        }

        setContentView(R.layout.activity_login)
        Ambience(findViewById(R.id.bgBase), findViewById(R.id.glow))
        val server = findViewById<EditText>(R.id.server)
        val username = findViewById<EditText>(R.id.username)
        val password = findViewById<EditText>(R.id.password)
        val button = findViewById<Button>(R.id.connect)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val error = findViewById<TextView>(R.id.error)
        val later = findViewById<Button>(R.id.later)
        later.text = if (fromSettings) "Annuler" else "Plus tard, ouvrir mes applis"
        later.setOnClickListener {
            if (fromSettings) {
                // Rien derrière nous (on vient de changer de source) : on retourne à l'accueil.
                if (isTaskRoot) openHome() else finish()
            } else {
                prefs.skipLogin = true
                openHome()
            }
        }

        fun showError(msg: String) {
            error.text = msg
            error.visibility = View.VISIBLE
        }

        fun submit() {
            val s = server.text.toString().trim()
            val u = username.text.toString().trim()
            val p = password.text.toString()
            if (s.isEmpty() || u.isEmpty() || p.isEmpty()) {
                showError("Remplis les trois champs.")
                return
            }
            error.visibility = View.GONE
            button.isEnabled = false
            button.text = ""
            progress.visibility = View.VISIBLE

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { XtreamApi(s, u, p).login() }
                }
                progress.visibility = View.GONE
                button.isEnabled = true
                button.text = "Se connecter"

                result.fold(
                    onSuccess = { ok ->
                        if (ok) {
                            Repo.clear(this@LoginActivity)
                            prefs.m3uUrl = ""
                            prefs.server = XtreamApi.normalize(s)
                            prefs.username = u
                            prefs.password = p
                            openHome()
                        } else {
                            showError("Identifiant ou mot de passe refusé par le serveur.")
                        }
                    },
                    onFailure = { e ->
                        showError("Connexion impossible : ${e.message ?: "vérifie l'adresse du serveur"}")
                    },
                )
            }
        }

        /** Teste une playlist M3U, la garde en cache, puis ouvre l'accueil. */
        fun usePlaylist(url: String) {
            error.visibility = View.GONE
            progress.visibility = View.VISIBLE
            button.isEnabled = false
            button.text = ""
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        Repo.clear(this@LoginActivity)
                        prefs.logout()
                        prefs.m3uUrl = url
                        Repo.download(this@LoginActivity, prefs)
                    }
                }
                progress.visibility = View.GONE
                button.isEnabled = true
                button.text = "Se connecter"
                val data = result.getOrNull()
                if (data != null && data.channels.isNotEmpty()) {
                    Repo.set(data)
                    openHome()
                } else {
                    prefs.m3uUrl = ""
                    showError(
                        if (data != null) "Cette playlist ne contient aucune chaîne."
                        else "Impossible de charger la playlist : ${result.exceptionOrNull()?.message ?: "erreur réseau"}"
                    )
                }
            }
        }

        findViewById<Button>(R.id.free).setOnClickListener { usePlaylist(M3u.FREE_FRANCE) }
        findViewById<Button>(R.id.m3u).setOnClickListener {
            val field = EditText(this).apply {
                hint = "http://…/playlist.m3u"
                setSingleLine()
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
            val pad = (20 * resources.displayMetrics.density).toInt()
            val box = android.widget.FrameLayout(this).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(field)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Lien de la playlist M3U")
                .setMessage("Colle ou tape l'adresse donnée par ta box ou ton fournisseur.")
                .setView(box)
                .setPositiveButton("Valider") { _, _ ->
                    val url = field.text.toString().trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) usePlaylist(url)
                    else showError("L'adresse doit commencer par http:// ou https://")
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        button.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        server.requestFocus()
    }

    private fun openHome() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_FROM_SETTINGS = "from_settings"
    }
}
