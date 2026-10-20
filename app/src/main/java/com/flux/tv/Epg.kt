package com.flux.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guide des programmes, gardé en mémoire 10 minutes. À utiliser depuis le thread principal. */
object Epg {
    private const val TTL_MS = 10 * 60 * 1000L
    private val cache = HashMap<Int, Pair<Long, List<EpgEntry>>>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

    fun cached(id: Int): List<EpgEntry>? {
        val e = cache[id] ?: return null
        return if (System.currentTimeMillis() - e.first > TTL_MS) null else e.second
    }

    suspend fun get(api: XtreamApi, id: Int): List<EpgEntry> {
        cached(id)?.let { return it }
        val list = withContext(Dispatchers.IO) {
            runCatching { api.shortEpg(id) }.getOrDefault(emptyList())
        }
        cache[id] = Pair(System.currentTimeMillis(), list)
        return list
    }

    /** Programme en cours et suivant. */
    fun nowAndNext(list: List<EpgEntry>): Pair<EpgEntry?, EpgEntry?> {
        val now = System.currentTimeMillis() / 1000
        val i = list.indexOfFirst { it.start <= now && it.end > now }
        val cur = list.getOrNull(i)
        val next = if (i >= 0) list.getOrNull(i + 1) else list.firstOrNull { it.start > now }
        return Pair(cur, next)
    }

    fun hhmm(seconds: Long): String = timeFormat.format(Date(seconds * 1000))

    /** Une ligne pour l'accueil, ex. "En ce moment · 20:45 – 21:40 · Le journal". */
    fun headline(list: List<EpgEntry>): String {
        val (cur, next) = nowAndNext(list)
        return when {
            cur != null -> "En ce moment  ·  ${hhmm(cur.start)} – ${hhmm(cur.end)}  ·  ${cur.title}"
            next != null -> "À ${hhmm(next.start)}  ·  ${next.title}"
            else -> ""
        }
    }
}
