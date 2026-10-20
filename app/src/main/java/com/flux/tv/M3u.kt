package com.flux.tv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Lecture des playlists M3U (liste de chaînes gratuites, playlist de box, lien d'abonnement). */
object M3u {

    /** Chaînes gratuites diffusées publiquement en France, recensées par le projet libre iptv-org. */
    const val FREE_FRANCE = "https://iptv-org.github.io/iptv/countries/fr.m3u"

    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

    fun isFreeFrance(url: String) = url == FREE_FRANCE

    /** À appeler hors du thread principal. */
    fun download(url: String): String {
        var current = url.trim()
        // On suit les redirections nous-mêmes, y compris de https vers http.
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 45_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: throw IOException("redirection sans adresse")
                    current = URL(URL(current), next).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("le serveur a répondu $code")
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("trop de redirections")
    }

    fun parse(text: String): Repo.Data {
        val categories = LinkedHashMap<String, Category>()
        val channels = ArrayList<Channel>()
        val seen = HashSet<Int>()

        var name: String? = null
        var logo = ""
        var group = ""
        var userAgent = ""
        var referrer = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"].orEmpty().trim()
                    group = attrs["group-title"].orEmpty().split(';').first().trim()
                    // Le nom est après la virgule qui suit le dernier attribut.
                    val afterAttrs = line.lastIndexOf('"').coerceAtLeast(0)
                    val comma = line.indexOf(',', afterAttrs)
                    name = if (comma >= 0) line.substring(comma + 1).trim() else attrs["tvg-name"]
                    userAgent = ""
                    referrer = ""
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                    userAgent = line.substringAfter('=').trim()
                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ->
                    referrer = line.substringAfter('=').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val title = name?.takeIf { it.isNotBlank() } ?: continue
                    val id = line.hashCode()
                    if (seen.add(id)) {
                        val cat = group.ifBlank { "Autres chaînes" }
                        categories.getOrPut(cat) { Category(cat, cat) }
                        channels.add(
                            Channel(
                                id = id,
                                name = title,
                                icon = logo,
                                categoryId = cat,
                                url = line,
                                userAgent = userAgent,
                                referrer = referrer,
                            )
                        )
                    }
                    name = null
                }
            }
        }
        // Catégories triées par nom, « Autres » à la fin.
        val sorted = categories.values.sortedWith(compareBy({ it.name == "Autres chaînes" }, { it.name.lowercase() }))
        return Repo.Data(sorted, channels)
    }
}
