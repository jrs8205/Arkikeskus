package org.jrs82.fsclock.mobile

import android.content.Context
import org.json.JSONArray
import java.text.Normalizer

/**
 * Offline-kuntalista kaupunkihakuun (jatkolista #1). Lukee `assets/kunnat.json`:n (Suomen ~308 voimassa
 * olevaa kuntaa — nimet Tilastokeskuksen virallisesta kuntaluokituksesta, koordinaatit Maanmittauslaitoksen
 * geokoodauksesta) kerran muistiin ja tekee VÄLITTÖMÄN normalisoidun (ä/ö-riippumattoman) prefix/contains-haun.
 * Antaa paikkahaulle heti ehdotukset ilman verkkoa; MML täydentää osoitteilla/kylillä.
 */
object KuntaList {

    data class Kunta(val name: String, val lat: Double, val lon: Double)

    @Volatile private var cache: List<Kunta>? = null

    private fun load(context: Context): List<Kunta> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val list = try {
                val json = context.applicationContext.assets.open("kunnat.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONArray(json)
                ArrayList<Kunta>(arr.length()).apply {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(Kunta(o.getString("n"), o.getDouble("la"), o.getDouble("lo")))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("KuntaList", "kunnat.json lataus epäonnistui", e)
                emptyList()
            }
            cache = list
            return list
        }
    }

    /** Normalisointi: trim + diakriitit pois (ä→a, ö→o) + pieniksi → haku ei välitä aksenteista/koosta. */
    fun normalize(s: String): String =
        Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .lowercase()

    /** Välitön haku assets-listasta. Prefix-osumat ensin (aakkosjärjestys), sitten contains-osumat. */
    fun search(context: Context, query: String, limit: Int): List<Kunta> =
        filter(load(context), query, limit)

    /** Puhdas suodatuslogiikka (yksikkötestattava ilman Androidia). */
    fun filter(all: List<Kunta>, query: String, limit: Int): List<Kunta> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val starts = ArrayList<Kunta>()
        val contains = ArrayList<Kunta>()
        for (k in all) {
            val n = normalize(k.name)
            when {
                n.startsWith(q) -> starts.add(k)
                n.contains(q) -> contains.add(k)
            }
        }
        starts.sortBy { it.name }
        contains.sortBy { it.name }
        val out = ArrayList<Kunta>(limit)
        out.addAll(starts)
        out.addAll(contains)
        return if (out.size > limit) out.subList(0, limit) else out
    }
}
