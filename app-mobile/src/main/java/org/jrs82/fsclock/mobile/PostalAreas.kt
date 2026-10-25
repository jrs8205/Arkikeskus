package org.jrs82.fsclock.mobile

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Postinumeroalueiden nimet offline-taulukosta `assets/postinumeroalueet.tsv` (Tilastokeskus, Paavo, CC BY 4.0;
 * päivitys `tools/postinumeroalueet.py`). Androidin Geocoder ei anna Suomessa yleensä kaupunginosaa, mutta
 * postinumero tulee aina: 01620 → Martinlaakso, sama nimi kuin MML:llä. Ladataan muistiin ensimmäisellä käytöllä.
 */
internal object PostalAreas {

    /** Alueen virallinen nimi ja sen kunta. Aineistossa vain Maarianhaminalla on kaksi kuntanimeä
     *  ("Maarianhamina - Mariehamn"); esim. Mustasaarella ei ole Korsholm-nimeä. */
    data class Area(val name: String, val municipalities: List<String>)

    /** Lähdemaininta (CC BY 4.0): Asetukset → Tietoja → Paikannimet. */
    const val SOURCE_URL =
        "https://stat.fi/fi/palvelut/tilastodatapalvelut/paikkatietoaineistot/postinumeroalueittainen-paikkatieto-paavo"

    private const val TAG = "PostalAreas"
    private const val ASSET = "postinumeroalueet.tsv"

    private val ABBREVIATIONS = mapOf("kk" to "Kirkonkylä", "kk." to "Kirkonkylä", "as" to "Asemanseutu")

    @Volatile private var cache: Map<String, Area>? = null

    /** Lukee assetin: kutsu taustasäikeestä. */
    fun lookup(context: Context, postalCode: String?): Area? {
        val code = postalCode?.trim().orEmpty()
        if (code.isEmpty()) return null
        return load(context)[code]
    }

    private fun load(context: Context): Map<String, Area> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val areas = try {
                context.applicationContext.assets.open(ASSET).bufferedReader(Charsets.UTF_8)
                    .useLines { parse(it) }
            } catch (e: Exception) {
                Log.w(TAG, "$ASSET lataus epäonnistui", e)
                emptyMap()
            }
            cache = areas
            return areas
        }
    }

    /** Rivit "postinumero<TAB>nimi<TAB>kunta"; kaksikielinen kunta muodossa "Maarianhamina - Mariehamn".
     *  Kommentit (#), tyhjät ja vajaat rivit ohitetaan. */
    internal fun parse(lines: Sequence<String>): Map<String, Area> {
        val out = HashMap<String, Area>(4_096)
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val code = parts[0].trim()
            val name = parts[1].trim()
            val municipalities = parts[2].split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
            if (code.length != 5 || !code.all(Char::isDigit) || name.isEmpty() || municipalities.isEmpty()) continue
            out[code] = Area(name, municipalities)
        }
        return out
    }

    /** Alueen nimi kunnan [city] kaupunginosaksi: kunnan nimi alusta pois ("Turku Keskus" → "Keskus"),
     *  lyhenteet auki. Null, jos alue on eri kunnassa kuin [city] tai nimi on pelkkä kunta. */
    internal fun districtFor(area: Area, city: String): String? {
        if (area.municipalities.none { it.equals(city, ignoreCase = true) }) return null
        val prefix = area.municipalities.firstOrNull { m ->
            area.name.length > m.length && area.name.startsWith(m, ignoreCase = true) && area.name[m.length] in " -,"
        }
        val rest = if (prefix != null) area.name.substring(prefix.length).trim(' ', '-', ',') else area.name
        val name = ABBREVIATIONS[rest.lowercase(Locale.ROOT)] ?: rest
        if (name.isEmpty() || area.municipalities.any { it.equals(name, ignoreCase = true) }) return null
        return name.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
