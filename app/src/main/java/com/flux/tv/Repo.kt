package com.flux.tv

import android.content.Context
import java.io.File

/** Chaînes en mémoire + cache disque, pour un démarrage instantané. */
object Repo {
    class Data(val categories: List<Category>, val channels: List<Channel>)

    var categories: List<Category> = emptyList()
        private set
    var channels: List<Channel> = emptyList()
        private set
    var byId: Map<Int, Channel> = emptyMap()
        private set
    var byCategory: Map<String, List<Channel>> = emptyMap()
        private set

    /** Liste dans laquelle on zappe depuis le lecteur. */
    var playlist: List<Int> = emptyList()

    val hasData get() = channels.isNotEmpty()

    private fun catsFile(ctx: Context) = File(ctx.filesDir, "cats.json")
    private fun streamsFile(ctx: Context) = File(ctx.filesDir, "streams.json")
    private fun playlistFile(ctx: Context) = File(ctx.filesDir, "playlist.m3u")

    /** À appeler hors du thread principal. */
    fun readCache(ctx: Context, prefs: Prefs): Data? {
        if (prefs.isM3u) {
            return try {
                val f = playlistFile(ctx)
                if (!f.exists()) null else M3u.parse(f.readText()).takeIf { it.channels.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }
        return try {
            val c = catsFile(ctx)
            val s = streamsFile(ctx)
            if (!c.exists() || !s.exists()) return null
            val data = Data(XtreamApi.parseCategories(c.readText()), XtreamApi.parseStreams(s.readText()))
            if (data.channels.isEmpty()) null else data
        } catch (e: Exception) {
            null
        }
    }

    /** Télécharge tout depuis la source configurée. À appeler hors du thread principal. */
    fun download(ctx: Context, prefs: Prefs): Data {
        if (prefs.isM3u) {
            val raw = M3u.download(prefs.m3uUrl)
            val data = M3u.parse(raw)
            if (data.channels.isNotEmpty()) playlistFile(ctx).writeText(raw)
            return data
        }
        val api = prefs.api()
        val catsRaw = api.categoriesRaw()
        val streamsRaw = api.streamsRaw()
        val data = Data(XtreamApi.parseCategories(catsRaw), XtreamApi.parseStreams(streamsRaw))
        if (data.channels.isNotEmpty()) {
            catsFile(ctx).writeText(catsRaw)
            streamsFile(ctx).writeText(streamsRaw)
        }
        return data
    }

    /** Augmente à chaque mise à jour de la liste, pour que l'accueil sache qu'il doit se redessiner. */
    var version = 0
        private set

    /** À appeler sur le thread principal. */
    fun set(data: Data) {
        version++
        channels = data.channels
        byId = data.channels.associateBy { it.id }
        byCategory = data.channels.groupBy { it.categoryId }
        categories = data.categories.filter { byCategory.containsKey(it.id) }
    }

    fun clear(ctx: Context) {
        catsFile(ctx).delete()
        streamsFile(ctx).delete()
        playlistFile(ctx).delete()
        set(Data(emptyList(), emptyList()))
        playlist = emptyList()
    }
}
