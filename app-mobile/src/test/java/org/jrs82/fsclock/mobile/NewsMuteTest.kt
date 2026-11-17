package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
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
        title: String = "Otsikko",
        topics: String = "world",
    ) = ForeignArticle(
        title = title,
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
    fun `isArticleMuted osuu englanninkieliseen otsikkoon`() {
        val a = art("u", "Original rumor here", topics = "world")
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

    // ---- aihepaketit ja alkuperäiskieliset (englanninkieliset) otsikot ----

    private fun mutedBy(packId: String, title: String) =
        NewsProfile.isArticleMuted(art("u", title, topics = "world"), emptyList(), listOf(pack(packId)))

    @Test
    fun `politiikkapaketti mykistaa englanninkielisen politiikkaotsikon`() {
        assertTrue(mutedBy("politiikka", "Prime minister calls snap election after budget defeat"))
        assertTrue(mutedBy("politiikka", "Parliament backs the government's new border law"))
        assertTrue(mutedBy("politiikka", "Opposition lawmakers walk out of the Senate"))
    }

    @Test
    fun `talouspaketti mykistaa englanninkielisen talousotsikon`() {
        assertTrue(mutedBy("talous", "Inflation eases as central bank holds interest rates"))
        assertTrue(mutedBy("talous", "Stocks slide on Wall Street after weak earnings"))
        assertTrue(mutedBy("talous", "Retailer files for bankruptcy as unemployment climbs"))
    }

    @Test
    fun `rikospaketti mykistaa englanninkielisen rikos- ja onnettomuusotsikon`() {
        assertTrue(mutedBy("rikokset", "Police arrest suspect after stabbing in city centre"))
        assertTrue(mutedBy("rikokset", "Man sentenced to 12 years for murder"))
        assertTrue(mutedBy("rikokset", "Two dead in motorway collision"))
    }

    @Test
    fun `urheilu- ja viihdepaketti osuvat englanninkieliseen otsikkoon ilman aihetagia`() {
        assertTrue(mutedBy("urheilu", "Olympics organisers unveil medal design"))
        assertTrue(mutedBy("urheilu", "Premier League club sacks head coach"))
        assertTrue(mutedBy("viihde", "Celebrity gossip site shuts down after 20 years"))
    }

    @Test
    fun `englanninkielinen sanasto osuu vain kokonaiseen sanaan`() {
        assertFalse(mutedBy("politiikka", "Team selection surprises fans")) // selection ⊃ election
        assertFalse(mutedBy("politiikka", "Nurses administer first doses of new vaccine")) // administer ⊃ minister
        assertFalse(mutedBy("rikokset", "Crimea bridge reopens to traffic")) // Crimea ⊃ crime
        assertFalse(mutedBy("urheilu", "Public transport fares to rise next year")) // transport ⊃ sport
        assertFalse(mutedBy("urheilu", "Conflict over water rights deepens")) // conflict ⊃ nfl
        assertFalse(mutedBy("talous", "Stockholm hosts climate summit")) // Stockholm ⊃ stock
    }

    @Test
    fun `englanninkielinen sanasto osuu omistusmuotoon ja yhdysmerkkiin`() {
        assertTrue(mutedBy("politiikka", "President’s speech draws protests"))
        assertTrue(mutedBy("politiikka", "Anti-government rally fills the capital"))
    }

    @Test
    fun `suomenkielinen otsikko mykistyy edelleen osittaisella osumalla`() {
        assertTrue(mutedBy("politiikka", "Pääministerin ehdotus jakaa hallituspuolueita"))
        assertTrue(mutedBy("rikokset", "Henkirikoksesta epäilty vangittiin"))
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
    fun `aihepakettien nimet tulevat resursseista ja id pysyy prefs-avaimena`() {
        assertEquals(R.string.news_pack_sport, pack("urheilu").labelRes)
        assertEquals(R.string.news_pack_crime, pack("rikokset").labelRes)
        val labels = NewsProfile.MUTE_PACKS.map { it.labelRes }
        assertEquals(labels.size, labels.toSet().size)
        // id:t ovat tallennettujen kytkimien avaimia → eivät saa muuttua käännöksen myötä
        assertEquals(
            listOf("urheilu", "politiikka", "viihde", "talous", "rikokset"),
            NewsProfile.MUTE_PACKS.map { it.id },
        )
    }

    @Test
    fun `rikkonainen mute-words json ei kaada`() {
        val p = FakeSharedPreferences()
        p.edit().putString("news_mute_words", "{ei kelvollinen").apply()
        assertTrue(NewsProfile.muteWords(p).isEmpty())
    }
}
