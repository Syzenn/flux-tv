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
                finish()
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
