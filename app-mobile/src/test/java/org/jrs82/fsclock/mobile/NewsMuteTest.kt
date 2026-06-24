package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uutissuodattimen (mykistetyt sanat + aihepaketit) yksikkötestit. Testataan NewsProfilen julkista
 * sopimusta hand-written fake-SharedPreferencesilla — ei mockia, ei Androidia (android-testing: fakes
 * over mocks, sama malli kuin NewsReadHistoryTest).
 */
class NewsMuteTest {

    private fun art(
        url: String,
        titleFi: String = "Otsikko",
        titleOrig: String = titleFi,
        topics: String = "world",
    ) = ForeignArticle(
        titleFi = titleFi,
        titleOrig = titleOrig,
        url = url,
        imageUrl = null,
        source = "Src",
        topics = topics,
        lang = "fi",
        publishedAtMs = 0L,
        relatedCount = 0,
    )

    private fun pack(id: String) = NewsProfile.MUTE_PACKS.first { it.id == id }

    // ---- isTitleMuted (pure) ----

    @Test
    fun `isTitleMuted osuu osittain ja kirjainkoosta riippumatta`() {
        assertTrue(NewsProfile.isTitleMuted("Villi HUHU leviää", listOf("huhu")))
        assertTrue(NewsProfile.isTitleMuted("Marius voitti", listOf("marius")))
    }

    @Test
    fun `isTitleMuted ei osu kun sanaa ei ole`() {
        assertFalse(NewsProfile.isTitleMuted("Tavallinen uutinen", listOf("huhu")))
    }

    @Test
    fun `isTitleMuted tyhja otsikko tai tyhja lista ei osu`() {
        assertFalse(NewsProfile.isTitleMuted("", listOf("huhu")))
        assertFalse(NewsProfile.isTitleMuted("Otsikko", emptyList()))
    }

    // ---- isArticleMuted (pure) ----

    @Test
    fun `isArticleMuted osuu paketin topic-tagiin ilman otsikkosanaa`() {
        val a = art("u", "Leijonat kaatoi Ruotsin jatkoajalla", topics = "sport")
        assertTrue(NewsProfile.isArticleMuted(a, emptyList(), listOf(pack("urheilu"))))
    }

    @Test
    fun `isArticleMuted osuu paketin sanastoon otsikossa vaikka topic ei tasmaa`() {
        val a = art("u", "Jääkiekko täytti areenan", topics = "world")
        assertTrue(NewsProfile.isArticleMuted(a, emptyList(), listOf(pack("urheilu"))))
    }

    @Test
    fun `isArticleMuted osuu vapaaseen otsikkosanaan`() {
        val a = art("u", "Uusi huhu kiertää", topics = "world")
        assertTrue(NewsProfile.isArticleMuted(a, listOf("huhu"), emptyList()))
    }

    @Test
    fun `isArticleMuted osuu alkuperaiseen otsikkoon`() {
        val a = art("u", "Suomennettu otsikko", titleOrig = "Original rumor here", topics = "world")
        assertTrue(NewsProfile.isArticleMuted(a, listOf("rumor"), emptyList()))
    }

    @Test
    fun `isArticleMuted ei osu kun mikaan ei tasmaa`() {
        val a = art("u", "Sää lämpenee viikonloppuna", topics = "world")
        assertFalse(NewsProfile.isArticleMuted(a, listOf("huhu"), listOf(pack("urheilu"))))
    }

    // ---- applyMute / prefs ----

    @Test
    fun `applyMute ilman ehtoja ei suodata`() {
        val p = FakeSharedPreferences()
        val items = listOf(art("1", "Huhu leviää"), art("2", "Sää"))
        assertEquals(items, NewsProfile.applyMute(p, items))
    }

    @Test
    fun `applyMute kytkin pois ei suodata vaikka sanoja on`() {
        val p = FakeSharedPreferences()
        NewsProfile.addMuteWord(p, "huhu")
        NewsProfile.setMuteEnabled(p, false)
        val items = listOf(art("1", "Huhu leviää"), art("2", "Sää"))
        assertEquals(items, NewsProfile.applyMute(p, items))
    }

    @Test
    fun `applyMute suodattaa vapaalla sanalla ja laskee maaran`() {
        val p = FakeSharedPreferences()
        NewsProfile.addMuteWord(p, "huhu")
        val items = listOf(art("1", "Huhu leviää"), art("2", "Sää lämpenee"))
        assertEquals(listOf("2"), NewsProfile.applyMute(p, items).map { it.url })
        assertEquals(1, NewsProfile.mutedCount(p, items))
    }

    @Test
    fun `applyMute suodattaa aktiivisella aihepaketilla`() {
        val p = FakeSharedPreferences()
        NewsProfile.setPackActive(p, "urheilu", true)
        val items = listOf(
            art("1", "Maajoukkue voitti", topics = "sport"),
            art("2", "Sää lämpenee", topics = "world"),
        )
        assertEquals(listOf("2"), NewsProfile.applyMute(p, items).map { it.url })
    }

    // ---- sanojen hallinta ----

    @Test
    fun `addMuteWord trimmaa ja estaa duplikaatin case-insensitive`() {
        val p = FakeSharedPreferences()
        assertTrue(NewsProfile.addMuteWord(p, "  Huhu  "))
        assertFalse(NewsProfile.addMuteWord(p, "huhu"))
        assertEquals(listOf("Huhu"), NewsProfile.muteWords(p))
    }

    @Test
    fun `removeMuteWord poistaa case-insensitive`() {
        val p = FakeSharedPreferences()
        NewsProfile.addMuteWord(p, "Huhu")
        NewsProfile.removeMuteWord(p, "huhu")
        assertTrue(NewsProfile.muteWords(p).isEmpty())
    }

    @Test
    fun `tyhjaa sanaa ei lisata`() {
        val p = FakeSharedPreferences()
        assertFalse(NewsProfile.addMuteWord(p, "   "))
        assertTrue(NewsProfile.muteWords(p).isEmpty())
    }

    @Test
    fun `hasActiveMute heijastaa kytkinta ja sisaltoa`() {
        val p = FakeSharedPreferences()
        assertFalse(NewsProfile.hasActiveMute(p))
        NewsProfile.addMuteWord(p, "huhu")
        assertTrue(NewsProfile.hasActiveMute(p))
        NewsProfile.setMuteEnabled(p, false)
        assertFalse(NewsProfile.hasActiveMute(p))
    }

    @Test
    fun `pack-kytkin tallentuu`() {
        val p = FakeSharedPreferences()
        assertFalse(NewsProfile.isPackActive(p, "urheilu"))
        NewsProfile.setPackActive(p, "urheilu", true)
        assertTrue(NewsProfile.isPackActive(p, "urheilu"))
        assertEquals(listOf("urheilu"), NewsProfile.activePacks(p).map { it.id })
    }

    @Test
    fun `rikkonainen mute-words json ei kaada`() {
        val p = FakeSharedPreferences()
        p.edit().putString("news_mute_words", "{ei kelvollinen").apply()
        assertTrue(NewsProfile.muteWords(p).isEmpty())
    }
}
