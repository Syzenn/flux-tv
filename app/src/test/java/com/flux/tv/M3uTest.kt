package com.flux.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {

    private val sample = """
        #EXTM3U x-tvg-url="https://example.org/guide.xml"
        #EXTINF:-1 tvg-id="Info24.fr@SD" tvg-logo="https://example.org/info24.png" group-title="News",Info 24 (1080p)
        https://example.org/info24/index.m3u8
        #EXTINF:-1 tvg-id="Docs.fr" tvg-logo="" group-title="Documentary;Culture",Docs, Monde [Geo-blocked]
        #EXTVLCOPT:http-user-agent=Mozilla/5.0 Test
        #EXTVLCOPT:http-referrer=https://docs.example.org/live
        https://example.org/docs/stream.m3u8?token=abc
        #EXTINF:-1,Sans groupe
        http://example.org/plain.ts
        #EXTINF:-1 group-title="News",Doublon
        https://example.org/info24/index.m3u8
    """.trimIndent()

    @Test
    fun parsesChannelsGroupsAndHeaders() {
        val data = M3u.parse(sample)
        assertEquals(3, data.channels.size) // le doublon d'adresse est ignoré

        val info = data.channels[0]
        assertEquals("Info 24", info.displayName)
        assertEquals("Info", info.categoryId) // traduit en français
        assertEquals("https://example.org/info24.png", info.icon)
        assertEquals("https://example.org/info24/index.m3u8", info.url)

        val docs = data.channels[1]
        assertEquals("Docs, Monde", docs.displayName) // virgule dans le nom conservée
        assertEquals("Documentaires", docs.categoryId) // premier groupe seulement, traduit
        assertEquals("Mozilla/5.0 Test", docs.userAgent)
        assertEquals("https://docs.example.org/live", docs.referrer)

        val plain = data.channels[2]
        assertEquals("Sans groupe", plain.displayName)
        assertEquals("Autres chaînes", plain.categoryId)

        // Catégories principales d'abord, « Autres chaînes » en dernier.
        assertEquals(listOf("Info", "Documentaires", "Autres chaînes"), data.categories.map { it.name })
    }

    /** Vérifie la vraie liste gratuite (s'exécute sur GitHub, qui a accès à internet). */
    @Test
    fun parsesLiveFreeFranceList() {
        val raw = try {
            M3u.download(M3u.FREE_FRANCE)
        } catch (e: Exception) {
            println("FREE_FR ignoré (pas de réseau) : ${e.message}")
            return
        }
        val data = M3u.parse(raw)
        println("FREE_FR chaines=${data.channels.size} categories=${data.categories.size}")
        println("FREE_FR categories: " + data.categories.joinToString(" | ") { "${it.name}(${data.channels.count { c -> c.categoryId == it.id }})" })
        data.channels.take(40).forEach {
            println("FREE_FR ${it.displayName} | ${it.categoryId} | logo=${it.icon.isNotBlank()} ua=${it.userAgent.isNotBlank()} ref=${it.referrer.isNotBlank()} hls=${it.url.contains(".m3u8")}")
        }
        assertTrue("la liste gratuite ne devrait pas être vide", data.channels.isNotEmpty())
    }
}
