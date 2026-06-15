package org.jrs82.fsclock.mobile

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Automaattinen, laitekohtainen uutisprofiili (ei tiliä/kirjautumista). Oppii hiljaa klikeistä +
 * lukuajasta, painottaa aihetta + lähdettä, vanhat painot vaimenevat (14 vrk puoliintumisaika).
 * Tallennus SharedPreferencesiin → tulee automaattisesti varmuuskopioon (BackupManager).
 * Maltillinen: matala paino + 25 % löytöosuus uudelleenjärjestyksessä (ks. [rerank]).
 *
 * Avaimet (eivät ala "auto_backup_" → mukana backupissa):
 *  news_profile_v1  = JSON painokartta {"t:tech":3.0,"s:BBC World":2.0,...}
 *  news_profile_decay_ms = viimeisin vaimennusaika
 *  news_onboarding_done  = alkukysely näytetty
 *  news_visible_cats     = näkyvät Ulkomaat-kategoriat (CSV); puuttuu = kaikki
 */
object NewsProfile {
    private const val KEY_WEIGHTS = "news_profile_v1"
    private const val KEY_DECAY_MS = "news_profile_decay_ms"
    private const val KEY_ONBOARDED = "news_onboarding_done"

    private const val HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000 // painot puoliintuvat 2 viikossa
    private const val MAX_READ_MS = 5L * 60 * 1000             // luettu-ajan katto (jätetty puhelin)
    private const val MIN_READ_MS = 5L * 1000                  // alle = pomppu, ei pistettä
    private const val PREF_WEIGHT = 0.15                       // personoinnin paino vs. tuoreus (maltillinen, tuoreus voittaa)
    private const val DROP_BELOW = 0.05                        // pudota mitättömät painot

    // Custom Tabsiin avattu juttu odottaa paluuta (lukuajan mittaus). Per prosessi.
    @Volatile private var pendingTopics: String? = null
    @Volatile private var pendingSource: String? = null
    @Volatile private var pendingOpenMs: Long = 0L

    // ---- painojen luku/tallennus + vaimennus ----
    private fun load(prefs: SharedPreferences): MutableMap<String, Double> {
        val map = HashMap<String, Double>()
        val raw = prefs.getString(KEY_WEIGHTS, null) ?: return map
        try {
            val o = JSONObject(raw)
            for (k in o.keys()) map[k] = o.optDouble(k, 0.0)
        } catch (e: Exception) { /* korruptoitunut → tyhjä */ }
        return map
    }

    private fun save(prefs: SharedPreferences, map: Map<String, Double>) {
        val o = JSONObject()
        for ((k, v) in map) if (v >= DROP_BELOW) o.put(k, v)
        prefs.edit().putString(KEY_WEIGHTS, o.toString()).apply()
    }

    /** Vaimentaa painot kuluneen ajan mukaan ja päivittää aikaleiman. Muokkaa karttaa paikan päällä. */
    private fun decay(prefs: SharedPreferences, map: MutableMap<String, Double>) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_DECAY_MS, now)
        val elapsed = now - last
        if (elapsed > 0 && map.isNotEmpty()) {
            val factor = Math.pow(0.5, elapsed.toDouble() / HALF_LIFE_MS)
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val nv = e.value * factor
                if (nv < DROP_BELOW) it.remove() else e.setValue(nv)
            }
        }
        prefs.edit().putLong(KEY_DECAY_MS, now).apply()
    }

    private fun addPoints(prefs: SharedPreferences, topics: String, source: String, points: Double) {
        if (points <= 0.0) return
        val map = load(prefs)
        decay(prefs, map)
        for (t in topics.split(",")) {
            val tag = t.trim()
            if (tag.isNotEmpty()) map["t:$tag"] = (map["t:$tag"] ?: 0.0) + points
        }
        if (source.isNotBlank()) map["s:$source"] = (map["s:$source"] ?: 0.0) + points
        save(prefs, map)
    }

    /** Juttu avattu (kortin napautus). */
    fun recordClick(prefs: SharedPreferences, topics: String, source: String) =
        addPoints(prefs, topics, source, 1.0)

    /** Juttu avattiin selaimeen → muista, jotta paluuaika voidaan mitata lukuajaksi. */
    fun markOpened(topics: String, source: String) {
        pendingTopics = topics
        pendingSource = source
        pendingOpenMs = System.currentTimeMillis()
    }

    /** Kutsutaan kun sovellus palaa etualalle: kirjaa karkean lukuajan (5–30 s → +2, >30 s → +4). */
    fun recordPendingRead(prefs: SharedPreferences) {
        val topics = pendingTopics ?: return
        val source = pendingSource ?: ""
        val elapsed = System.currentTimeMillis() - pendingOpenMs
        pendingTopics = null
        pendingSource = null
        if (elapsed < MIN_READ_MS || elapsed > MAX_READ_MS) return
        addPoints(prefs, topics, source, if (elapsed > 30_000L) 4.0 else 2.0)
    }

    /** Painokartan tilannekuva (vaimennus sovellettu + tallennettu). Kutsu kerran per syötteen lataus. */
    fun snapshot(prefs: SharedPreferences): Map<String, Double> {
        val map = load(prefs)
        decay(prefs, map)
        save(prefs, map)
        return map
    }

    fun scoreWith(snapshot: Map<String, Double>, topics: String, source: String): Double {
        var s = 0.0
        for (t in topics.split(",")) {
            val tag = t.trim()
            if (tag.isNotEmpty()) s += snapshot["t:$tag"] ?: 0.0
        }
        if (source.isNotBlank()) s += snapshot["s:$source"] ?: 0.0
        return s
    }

    // ---- onboarding ----
    fun isOnboarded(prefs: SharedPreferences) = prefs.getBoolean(KEY_ONBOARDED, false)
    fun setOnboarded(prefs: SharedPreferences) = prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()

    /** Alkukyselyn valitut aiheet → aloituspainot (lämmin startti). Asettaa myös onboarded-lipun. */
    fun seedTopics(prefs: SharedPreferences, topics: List<String>) {
        val map = load(prefs)
        for (t in topics) if (t.isNotBlank()) map["t:$t"] = (map["t:$t"] ?: 0.0) + 3.0
        save(prefs, map)
        prefs.edit().putLong(KEY_DECAY_MS, System.currentTimeMillis()).putBoolean(KEY_ONBOARDED, true).apply()
    }

    // ---- kategorianäkyvyys (Ulkomaat-sirut) — per-kategoria boolean-pref, oletus näkyvä ----
    // (Asetusten valikko käyttää tätä avainta PrefSwitchRow:lla → menee automaattisesti backupiin.)
    fun catVisibleKey(tag: String) = "news_cat_show_$tag"

    fun visibleCats(prefs: SharedPreferences, all: List<String>): Set<String> =
        all.filter { prefs.getBoolean(catVisibleKey(it), true) }.toSet()

    // ---- luetut-historia (Ulkomaat): piilota luetut syötteistä + "Luetut"-välilehti ----
    // JSON-lista uusin-luettu-ensin, katto 100 (vanhin tippuu). Koko jutun metadata talletetaan,
    // jotta luettu näkyy "Luetut"-listassa vaikka olisi kiertynyt pois API-syötteestä. Avain ei
    // ala "auto_backup_" → mukana varmuuskopiossa (BackupManager).
    private const val KEY_READ = "news_read_v1"
    private const val READ_CAP = 100

    private fun readArray(prefs: SharedPreferences): JSONArray =
        try { JSONArray(prefs.getString(KEY_READ, "[]")) } catch (e: Exception) { JSONArray() }

    /** Merkitsee jutun luetuksi: kärkeen (uusin), poistaa vanhan duplikaatin, katkaisee 100:aan. */
    fun markRead(prefs: SharedPreferences, a: ForeignArticle) {
        if (a.url.isBlank()) return
        val arr = readArray(prefs)
        val out = JSONArray()
        out.put(
            JSONObject()
                .put("u", a.url)
                .put("t", a.titleFi)
                .put("o", a.titleOrig)
                .put("i", a.imageUrl ?: "")
                .put("s", a.source)
                .put("p", a.topics)
                .put("l", a.lang)
                .put("m", a.publishedAtMs)
                .put("r", a.relatedCount),
        )
        var count = 1
        for (i in 0 until arr.length()) {
            if (count >= READ_CAP) break
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("u") == a.url) continue // vanha duplikaatti pois (siirtyi kärkeen)
            out.put(o)
            count++
        }
        prefs.edit().putString(KEY_READ, out.toString()).apply()
    }

    /** Luettujen URL:t — syötteen suodatusta varten (piilota luetut kaikista näkymistä). */
    fun readUrls(prefs: SharedPreferences): Set<String> {
        val arr = readArray(prefs)
        val set = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i)?.optString("u") ?: ""
            if (u.isNotEmpty()) set.add(u)
        }
        return set
    }

    /** Luetut jutut "Luetut"-välilehdelle, uusin-luettu ensin. */
    fun readArticles(prefs: SharedPreferences): List<ForeignArticle> {
        val arr = readArray(prefs)
        val out = ArrayList<ForeignArticle>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("u")
            if (url.isEmpty()) continue
            out.add(
                ForeignArticle(
                    titleFi = o.optString("t"),
                    titleOrig = o.optString("o").ifEmpty { o.optString("t") },
                    url = url,
                    imageUrl = o.optString("i").ifEmpty { null },
                    source = o.optString("s"),
                    topics = o.optString("p"),
                    lang = o.optString("l").ifEmpty { "en" },
                    publishedAtMs = o.optLong("m", 0L),
                    relatedCount = o.optInt("r", 0),
                ),
            )
        }
        return out
    }

    /**
     * Maltillinen uudelleenjärjestys: AIKAPOHJAINEN tuoreus dominoi, personointi vain pieni nudge.
     * Pisteet = tuoreus(ikä) + 0,15 × profiiliosuma. Tuore juttu voittaa aina selvästi vanhemman →
     * järjestys pysyy lähes kronologisena (ei iän sekoittumista), suosikkiaiheet nousevat hieman
     * samanikäisten joukosta. Tyhjä profiili → palautetaan sellaisenaan. recencyMsOf = julkaisuaika ms.
     */
    fun <T> rerank(items: List<T>, prefScoreOf: (T) -> Double, recencyMsOf: (T) -> Long): List<T> {
        val n = items.size
        if (n <= 2) return items
        val pref = DoubleArray(n) { prefScoreOf(items[it]) }
        val maxPref = pref.maxOrNull() ?: 0.0
        if (maxPref <= 0.0) return items // ei profiilisignaalia → tuoreusjärjestys (API on jo uusin-ensin)
        val now = System.currentTimeMillis()
        // Aikapohjainen tuoreuspiste DOMINOI (tuore juttu voittaa aina selvästi vanhemman);
        // personointi on vain pieni lisä, joka nudgaa samanikäisten kesken → järjestys pysyy
        // lähes kronologisena, ei iän sekoittumista.
        return items.indices.sortedByDescending { i ->
            val ms = recencyMsOf(items[i])
            val ageMin = if (ms <= 0L) 1e9 else (now - ms).coerceAtLeast(0L) / 60000.0
            val recency = 1.0 / (1.0 + ageMin / 30.0) // 0min→1.0, 30min→0.5, 60min→0.33, 3h→0.14
            recency + PREF_WEIGHT * (pref[i] / maxPref)
        }.map { items[it] }
    }
}
