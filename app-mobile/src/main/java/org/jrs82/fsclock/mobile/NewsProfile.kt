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

    /** Alkukyselyn / asetusten kategoriavalinta → näkyvät kategoriat (HARD-filter: feed näyttää vain
     *  valitut). Tyhjä valinta = KAIKKI näkyvät (ei jätetä tyhjää feediä). Sama valinta koskee sekä
     *  Kotimaita että Ulkomaita (jaetut news_cat_show_-avaimet). */
    fun applyCategorySelection(prefs: SharedPreferences, selected: List<String>, allTags: List<String>) {
        val showAll = selected.isEmpty()
        val ed = prefs.edit()
        for (tag in allTags) ed.putBoolean(catVisibleKey(tag), showAll || tag in selected)
        ed.apply()
    }

    /** Onko jutun jokin aihe näkyvien kategorioiden joukossa — "Kaikki"-näkymän hard-filter. */
    fun topicVisible(topics: String, visible: Set<String>): Boolean {
        for (t in topics.split(",")) if (t.trim() in visible) return true
        return false
    }

    // ---- mykistyssuodatin (mute): vapaat sanat (otsikko) + aihepaketit (topic-tagi + sanasto) ----
    // Käyttäjän "verkkokauppa-tyylinen rajaa-pois" -suodatin. Piilottaa jutut KAIKISTA backend-
    // uutisnäkymistä (Kotimaat/Ulkomaat + etusivun kortit). Asiakaspuolella, ei backendiä.
    // Avaimet eivät ala "auto_backup_" → mukana varmuuskopiossa (BackupManager).
    private const val KEY_MUTE_ENABLED = "news_mute_enabled"
    private const val KEY_MUTE_WORDS = "news_mute_words"
    private fun mutePackKey(id: String) = "news_mute_pack_$id"

    /** Valmis aihepaketti: osuu jutun topic-tagiin (esim. sport) JA laajaan otsikkosanastoon →
     *  saa kiinni myös ne aihejutut joiden otsikossa ei lue aiheen nimeä. */
    data class MutePack(
        val id: String,
        val label: String,
        val topicTags: Set<String>,
        val keywords: Set<String>,
    )

    /** Aloituspaketit (helppo laajentaa). Sanastot pieninä kirjaimina (vertailu on case-insensitive). */
    val MUTE_PACKS: List<MutePack> = listOf(
        MutePack(
            "urheilu", "Urheilu", setOf("sport"),
            setOf(
                "urheilu", "jääkiekko", "jalkapallo", "koripallo", "lentopallo", "salibandy",
                "formula", "f1", "ralli", "hiihto", "yleisurheilu", "golf", "tennis", "ottelu",
                "liiga", "mm-kisat", "olympia", "maajoukkue", "valmentaja", "maaottelu", "huuhkajat",
            ),
        ),
        MutePack(
            "politiikka", "Politiikka", emptySet(),
            setOf(
                "politiikka", "poliittinen", "hallitus", "eduskunta", "puolue", "ministeri",
                "pääministeri", "presidentti", "vaalit", "kansanedustaja", "oppositio", "lakiesitys",
                "budjettiriihi", "valtiovarain",
            ),
        ),
        MutePack(
            "viihde", "Viihde & julkkikset", setOf("entertainment"),
            setOf(
                "viihde", "julkkis", "juoru", "horoskooppi", "realitysarja", "tähti", "kuninkaallinen",
                "missikisat", "tubettaja", "somettaja",
            ),
        ),
        MutePack(
            "talous", "Talous", emptySet(),
            setOf(
                "talous", "pörssi", "osake", "inflaatio", "korko", "konkurssi", "työttömyys",
                "lakko", "osinko", "bruttokansantuote", "tulos",
            ),
        ),
        MutePack(
            "rikokset", "Rikokset & onnettomuudet", emptySet(),
            setOf(
                "poliisi", "rikos", "epäilty", "murha", "henkirikos", "pidätys", "käräjäoikeus",
                "tuomio", "huumeet", "väkivalta", "onnettomuus", "kolari", "puukotus",
            ),
        ),
    )

    fun isMuteEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_MUTE_ENABLED, true)
    fun setMuteEnabled(prefs: SharedPreferences, on: Boolean) =
        prefs.edit().putBoolean(KEY_MUTE_ENABLED, on).apply()

    /** Mykistyssanat (alkuperäisessä kirjoitusasussa näytettäväksi; vertailu tehdään case-insensitive). */
    fun muteWords(prefs: SharedPreferences): List<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(prefs.getString(KEY_MUTE_WORDS, "[]"))
            for (i in 0 until arr.length()) {
                val w = arr.optString(i).trim()
                if (w.isNotEmpty()) out.add(w)
            }
        } catch (e: Exception) { /* rikkonainen → tyhjä */ }
        return out
    }

    private fun saveMuteWords(prefs: SharedPreferences, words: List<String>) {
        val arr = JSONArray()
        for (w in words) arr.put(w)
        prefs.edit().putString(KEY_MUTE_WORDS, arr.toString()).apply()
    }

    /** Lisää sana (trim + duplikaattisuoja case-insensitive). Palauttaa true jos lisättiin. */
    fun addMuteWord(prefs: SharedPreferences, word: String): Boolean {
        val w = word.trim()
        if (w.isEmpty()) return false
        val cur = muteWords(prefs)
        if (cur.any { it.equals(w, ignoreCase = true) }) return false
        saveMuteWords(prefs, cur + w)
        return true
    }

    fun removeMuteWord(prefs: SharedPreferences, word: String) {
        val cur = muteWords(prefs)
        val next = cur.filterNot { it.equals(word, ignoreCase = true) }
        if (next.size != cur.size) saveMuteWords(prefs, next)
    }

    fun isPackActive(prefs: SharedPreferences, id: String): Boolean =
        prefs.getBoolean(mutePackKey(id), false)

    fun setPackActive(prefs: SharedPreferences, id: String, on: Boolean) =
        prefs.edit().putBoolean(mutePackKey(id), on).apply()

    fun activePacks(prefs: SharedPreferences): List<MutePack> =
        MUTE_PACKS.filter { isPackActive(prefs, it.id) }

    /** Osuuko otsikkoon jokin sana (case-insensitive, osittainen). Pure. */
    fun isTitleMuted(title: String, words: Collection<String>): Boolean {
        if (title.isBlank() || words.isEmpty()) return false
        val t = title.lowercase()
        for (w in words) {
            val lw = w.trim().lowercase()
            if (lw.isNotEmpty() && t.contains(lw)) return true
        }
        return false
    }

    /** Mykistyykö juttu: aktiivisen paketin topic-tagi ∈ jutun aiheet, TAI mykistyssana/paketin
     *  sanasto osuu otsikkoon (suomennettu tai alkuperäinen). Pure. */
    fun isArticleMuted(a: ForeignArticle, words: Collection<String>, packs: List<MutePack>): Boolean {
        if (packs.isNotEmpty()) {
            val tags = a.topics.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            for (p in packs) if (p.topicTags.isNotEmpty() && tags.any { it in p.topicTags }) return true
        }
        val all = HashSet<String>(words)
        for (p in packs) all.addAll(p.keywords)
        if (all.isEmpty()) return false
        if (isTitleMuted(a.titleFi, all)) return true
        if (a.titleOrig.isNotBlank() && a.titleOrig != a.titleFi && isTitleMuted(a.titleOrig, all)) return true
        return false
    }

    /** Suodattaa mykistetyt jutut. Kytkin pois / ei sanoja+paketteja → palauttaa listan sellaisenaan. */
    fun applyMute(prefs: SharedPreferences, items: List<ForeignArticle>): List<ForeignArticle> {
        if (!isMuteEnabled(prefs)) return items
        val words = muteWords(prefs)
        val packs = activePacks(prefs)
        if (words.isEmpty() && packs.isEmpty()) return items
        return items.filterNot { isArticleMuted(it, words, packs) }
    }

    /** Montako juttua suodatin piilottaisi annetusta listasta (statusriviä varten). */
    fun mutedCount(prefs: SharedPreferences, items: List<ForeignArticle>): Int =
        items.size - applyMute(prefs, items).size

    /** Onko suodattimessa yhtään aktiivista ehtoa (sana tai paketti) — ikonin korostus + badge. */
    fun hasActiveMute(prefs: SharedPreferences): Boolean =
        isMuteEnabled(prefs) && (muteWords(prefs).isNotEmpty() || activePacks(prefs).isNotEmpty())

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
