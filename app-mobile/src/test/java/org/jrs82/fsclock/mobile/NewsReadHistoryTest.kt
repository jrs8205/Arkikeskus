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
}

/** Muistinvarainen SharedPreferences-fake JVM-yksikkötesteihin (vain merkkijonot tarvitaan tässä). */
private class FakeSharedPreferences : SharedPreferences {
    val map = HashMap<String, Any?>()

    override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun getAll(): MutableMap<String, *> = map
    override fun edit(): SharedPreferences.Editor = FakeEditor(this)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
    private val pending = HashMap<String, Any?>()
    private val removals = HashSet<String>()

    override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { if (key != null) pending[key] = values; return this }
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
    override fun remove(key: String?): SharedPreferences.Editor { if (key != null) removals.add(key); return this }
    override fun clear(): SharedPreferences.Editor { prefs.map.clear(); return this }
    override fun commit(): Boolean { apply(); return true }
    override fun apply() {
        for (k in removals) prefs.map.remove(k)
        prefs.map.putAll(pending)
        pending.clear(); removals.clear()
    }
}
