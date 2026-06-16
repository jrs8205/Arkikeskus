package org.jrs82.fsclock.mobile

import org.json.JSONArray
import org.jrs82.fsclock.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Yksi Uutiskeskus-uutispalvelun (Cloudflare Worker) kortti: valmiiksi suomennettu otsikko
 * + alkukielinen otsikko + meta. Itse artikkeli pysyy alkuperäisellä sivulla (avataan Custom Tabsiin).
 */
data class ForeignArticle(
    val titleFi: String,
    val titleOrig: String,
    val url: String,
    val imageUrl: String?,
    val source: String,
    val topics: String,
    val lang: String,
    val publishedAtMs: Long,
    val relatedCount: Int,
)

/**
 * "Ulkomaat"-uutisten JSON-asiakas. Hakee Uutiskeskus-API:sta valmiiksi suomennetut otsikot +
 * kategoriat (käännös + dedup tehdään palvelimella ilmaiseksi). Julkinen API, ei avainta.
 * Per-kategoria välimuisti (10 min), jotta kategorian vaihto edestakaisin on nopea.
 */
object ForeignNewsClient {
    // MVP: workers.dev. Vaihdetaan myöhemmin custom-domainiin (uutiset.arkikeskus.com).
    private const val BASE_URL = "https://uutiskeskus.jarsi.workers.dev"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BODY = 2_000_000
    private const val CACHE_TTL_MS = 10 * 60_000L

    private class Entry(val items: List<ForeignArticle>, val timestamp: Long)
    private val cache = HashMap<String, Entry>()

    /** topic = null/tyhjä → "kaikki" (lomitettu). domestic=true → Kotimaat (?domestic=1, suomalaiset
     *  lähteet); false → Ulkomaat. Palauttaa välimuistin ellei [forced]. */
    fun fetch(topic: String?, limit: Int, forced: Boolean, domestic: Boolean = false): List<ForeignArticle> {
        val key = (if (domestic) "d|" else "f|") + (topic ?: "") + "|" + limit
        synchronized(cache) {
            val e = cache[key]
            if (!forced && e != null && System.currentTimeMillis() - e.timestamp < CACHE_TTL_MS) return e.items
        }
        val sb = StringBuilder(BASE_URL).append("/api/articles?limit=").append(limit)
        if (!topic.isNullOrBlank()) sb.append("&topic=").append(topic)
        if (domestic) sb.append("&domestic=1")
        val items = request(sb.toString())
        if (items.isNotEmpty()) {
            synchronized(cache) { cache[key] = Entry(items, System.currentTimeMillis()) }
        }
        return items
    }

    private fun request(urlStr: String): List<ForeignArticle> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Arkikeskus/" + BuildConfig.VERSION_NAME + " (Android)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return emptyList()
            val bytes = conn.inputStream.readBytes()
            val trimmed = if (bytes.size > MAX_BODY) bytes.copyOf(MAX_BODY) else bytes
            parse(trimmed.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(json: String): List<ForeignArticle> {
        val arr = JSONArray(json)
        val out = ArrayList<ForeignArticle>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = if (o.isNull("url")) "" else o.optString("url", "")
            if (url.isBlank()) continue
            // optString palauttaa "null"-merkkijonon JSON-nullille → tarkista isNull erikseen.
            val titleOrig = if (o.isNull("title")) "" else o.optString("title", "")
            val titleFiRaw = if (o.isNull("title_fi")) "" else o.optString("title_fi", "")
            val titleFi = titleFiRaw.ifBlank { titleOrig }
            val image = if (o.isNull("image_url")) null else o.optString("image_url", "").ifBlank { null }
            out.add(
                ForeignArticle(
                    titleFi = titleFi,
                    titleOrig = titleOrig.ifBlank { titleFi },
                    url = url,
                    imageUrl = image,
                    source = if (o.isNull("source")) "" else o.optString("source", ""),
                    topics = if (o.isNull("topics")) "" else o.optString("topics", ""),
                    lang = if (o.isNull("lang")) "en" else o.optString("lang", "en"),
                    publishedAtMs = o.optLong("published_at", 0L),
                    relatedCount = o.optInt("related_count", 0),
                ),
            )
        }
        return out
    }
}
