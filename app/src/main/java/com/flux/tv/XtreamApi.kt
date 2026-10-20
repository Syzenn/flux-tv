package com.flux.tv

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

data class Category(val id: String, val name: String)

data class Channel(
    val id: Int,
    val name: String,
    val icon: String,
    val categoryId: String,
    /** Adresse directe du flux (playlists M3U). Vide pour Xtream, où on la construit. */
    val url: String = "",
    val userAgent: String = "",
    val referrer: String = "",
) {
    /** Nom sans les préfixes du type "FR | " ou "FR: ". */
    val displayName: String by lazy { cleanName(name) }
}

data class EpgEntry(val title: String, val start: Long, val end: Long)

private val PREFIX = Regex("^\\s*[|\\[(]?\\s*[A-Z]{2,3}\\s*[\\])]?\\s*[|:]\\s*")

private val QUALITY = Regex("""\s*\((\d{3,4}[pi]|\d+K|SD|HD|FHD|UHD)\)""", RegexOption.IGNORE_CASE)
private val TAGS = Regex("""\s*\[[^\]]*]""")

fun cleanName(raw: String): String {
    val cleaned = raw.replace(PREFIX, "").replace(QUALITY, "").replace(TAGS, "").trim()
    return cleaned.ifEmpty { raw.trim() }
}

class XtreamApi(server: String, private val user: String, private val pass: String) {

    private val base = normalize(server)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(params: String): String {
        val url = URL("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}$params")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 45_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("le serveur a répondu $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun login(): Boolean {
        val o = JSONObject(get(""))
        val info = o.optJSONObject("user_info") ?: return false
        return info.optInt("auth", 0) == 1
    }

    fun categoriesRaw(): String = get("&action=get_live_categories")

    fun streamsRaw(): String = get("&action=get_live_streams")

    fun shortEpg(streamId: Int): List<EpgEntry> {
        val o = JSONObject(get("&action=get_short_epg&stream_id=$streamId&limit=3"))
        val arr = o.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<EpgEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val start = e.optLong("start_timestamp", 0L)
            val end = e.optLong("stop_timestamp", 0L)
            if (start == 0L || end == 0L) continue
            out.add(EpgEntry(decode(e.optString("title", "")), start, end))
        }
        return out.sortedBy { it.start }
    }

    fun streamUrl(streamId: Int, hls: Boolean): String =
        "$base/live/$user/$pass/$streamId.${if (hls) "m3u8" else "ts"}"

    companion object {
        fun normalize(s: String): String {
            var u = s.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            if (u.endsWith("/player_api.php")) u = u.removeSuffix("/player_api.php")
            return u
        }

        private fun decode(b64: String): String = try {
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            b64
        }

        fun parseCategories(json: String): List<Category> {
            val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
            val out = ArrayList<Category>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Category(o.optString("category_id"), o.optString("category_name").trim()))
            }
            return out
        }

        fun parseStreams(json: String): List<Channel> {
            val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
            val out = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("stream_id", -1)
                if (id < 0) continue
                out.add(
                    Channel(
                        id = id,
                        name = o.optString("name").trim(),
                        icon = o.optString("stream_icon").let { if (it == "null") "" else it.trim() },
                        categoryId = o.optString("category_id"),
                    )
                )
            }
            return out
        }
    }
}
