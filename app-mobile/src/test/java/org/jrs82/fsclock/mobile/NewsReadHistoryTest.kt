package org.jrs82.fsclock.mobile

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NewsProfilen "Luetut"-historian (news_read_v1) regressiotestit: katto 100, dedup + kärkeen-siirto,
 * rikkonainen JSON ei kaada. Testataan julkista sopimusta (markRead/readUrls/readArticles) hand-written
 * fake-SharedPreferencesilla — ei mockia, ei Androidia (android-testing: fakes over mocks).
 */
class NewsReadHistoryTest {

    private fun art(url: String, title: String = "T", ms: Long = 0L) = ForeignArticle(
        titleFi = title,
        titleOrig = title,
        url = url,
        imageUrl = null,
        source = "Src",
        topics = "world",
        lang = "en",
        publishedAtMs = ms,
        relatedCount = 0,
    )

    @Test
    fun `markRead tekee jutusta luetun ja palauttaa sen karjessa`() {
        val p = FakeSharedPreferences()
        NewsProfile.markRead(p, art("https://a", "Eka"))
        assertTrue("https://a" in NewsProfile.readUrls(p))
        val read = NewsProfile.readArticles(p)
        assertEquals(1, read.size)
        assertEquals("Eka", read[0].titleFi)
        assertEquals("https://a", read[0].url)
    }

    @Test
    fun `duplikaatti siirtyy karkeen eika kahdennu`() {
        val p = FakeSharedPreferences()
        NewsProfile.markRead(p, art("https://a"))
        NewsProfile.markRead(p, art("https://b"))
        NewsProfile.markRead(p, art("https://a")) // luetaan a uudelleen
        val read = NewsProfile.readArticles(p)
        assertEquals(2, read.size)
        assertEquals("https://a", read[0].url) // uusin-luettu kärkeen
        assertEquals("https://b", read[1].url)
    }

    @Test
    fun `luettujen katto on 100 ja vanhin tippuu`() {
        val p = FakeSharedPreferences()
        for (i in 1..105) NewsProfile.markRead(p, art("https://u$i"))
        val read = NewsProfile.readArticles(p)
        assertEquals(100, read.size)
        assertEquals("https://u105", read[0].url) // uusin kärjessä
        assertFalse(read.any { it.url == "https://u1" }) // vanhimmat pudonneet
    }

    @Test
    fun `rikkonainen json antaa tyhjan tuloksen eika kaada`() {
        val p = FakeSharedPreferences()
        p.edit().putString("news_read_v1", "{ei kelvollinen").apply()
        assertTrue(NewsProfile.readUrls(p).isEmpty())
        assertTrue(NewsProfile.readArticles(p).isEmpty())
    }

    @Test
    fun `tyhja url ei lisaa luettua`() {
        val p = FakeSharedPreferences()
        NewsProfile.markRead(p, art(""))
        assertTrue(NewsProfile.readUrls(p).isEmpty())
    }

    // ---- 2.10.0: alkukyselyn hard-filter (kategoriavalinta → näkyvät kategoriat) ----

    @Test
    fun `topicVisible osuu kun jokin jutun aihe on valittu`() {
        assertTrue(NewsProfile.topicVisible("tech,mobile", setOf("tech")))
        assertTrue(NewsProfile.topicVisible("world", setOf("world", "sport")))
        assertFalse(NewsProfile.topicVisible("sport", setOf("tech", "world")))
        assertFalse(NewsProfile.topicVisible("", setOf("tech")))
    }

    @Test
    fun `applyCategorySelection asettaa vain valitut nakyviin`() {
        val p = FakeSharedPreferences()
        val all = listOf("world", "tech", "sport", "culture")
        NewsProfile.applyCategorySelection(p, listOf("tech", "world"), all)
        assertEquals(setOf("tech", "world"), NewsProfile.visibleCats(p, all))
        assertFalse(p.getBoolean(NewsProfile.catVisibleKey("sport"), true))
    }

    @Test
    fun `tyhja valinta nayttaa kaikki kategoriat`() {
        val p = FakeSharedPreferences()
        val all = listOf("world", "tech", "sport")
        NewsProfile.applyCategorySelection(p, emptyList(), all)
        assertEquals(all.toSet(), NewsProfile.visibleCats(p, all))
    }

    // ---- 2.10.0: etusivun uutiskorttien jaettu hard-filter (hardFilterCategories) ----

    private fun artTopic(url: String, topics: String) = art(url).copy(topics = topics)

    @Test
    fun `hardFilterCategories nayttaa vain valittujen kategorioiden jutut`() {
        val items = listOf(
            artTopic("https://1", "tech"),
            artTopic("https://2", "sport"),
            artTopic("https://3", "tech,world"),
            artTopic("https://4", "culture"),
        )
        val out = hardFilterCategories(items, setOf("tech"), allTagCount = 4)
        assertEquals(listOf("https://1", "https://3"), out.map { it.url })
    }

    @Test
    fun `hardFilterCategories tyhja valinta palauttaa kaikki`() {
        val items = listOf(artTopic("https://1", "tech"), artTopic("https://2", "sport"))
        assertEquals(items, hardFilterCategories(items, emptySet(), allTagCount = 4))
    }

    @Test
    fun `hardFilterCategories kaikki valittu ei suodata`() {
        val items = listOf(artTopic("https://1", "tech"), artTopic("https://2", "sport"))
        // visible.size == allTagCount → "kaikki valittu" → ei suodatusta (näytä kaikki).
        assertEquals(items, hardFilterCategories(items, setOf("a", "b", "c", "d"), allTagCount = 4))
    }
}
