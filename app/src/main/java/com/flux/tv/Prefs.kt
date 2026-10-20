package com.flux.tv

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("flux", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    val isLoggedIn: Boolean
        get() = server.isNotBlank() && username.isNotBlank()

    /** Favoris, dans l'ordre où ils ont été ajoutés. */
    var favorites: List<Int>
        get() = (sp.getString("favs", "") ?: "")
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
        set(v) = sp.edit().putString("favs", v.joinToString(",")).apply()

    /** Ajoute ou retire un favori. Renvoie true si la chaîne vient d'être ajoutée. */
    fun toggleFavorite(id: Int): Boolean {
        val list = favorites.toMutableList()
        val added = if (list.remove(id)) false else {
            list.add(id)
            true
        }
        favorites = list
        return added
    }

    /** Format de flux qui marche le mieux avec ce serveur (appris automatiquement). */
    var useHls: Boolean
        get() = sp.getBoolean("hls", false)
        set(v) = sp.edit().putBoolean("hls", v).apply()

    // ---------- Lecture ----------

    /** Décodage vidéo tunnelisé (lecture matérielle). */
    var tunneling: Boolean
        get() = sp.getBoolean("tunneling", false)
        set(v) = sp.edit().putBoolean("tunneling", v).apply()

    /** Mémoire tampon plus grande : démarrage un peu plus lent mais moins de coupures. */
    var stableBuffer: Boolean
        get() = sp.getBoolean("stable_buffer", false)
        set(v) = sp.edit().putBoolean("stable_buffer", v).apply()

    fun api(): XtreamApi = XtreamApi(server, username, password)

    fun logout() {
        sp.edit()
            .remove("server")
            .remove("username")
            .remove("password")
            .remove("hls")
            .apply()
    }
}
