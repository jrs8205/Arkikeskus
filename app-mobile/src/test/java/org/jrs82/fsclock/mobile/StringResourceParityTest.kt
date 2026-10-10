package org.jrs82.fsclock.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Suomi ei saa regressoitua englanniksi: jokainen käännettävä avain on fi- ja sv-kansioissa samoilla
 *  argumenteilla, eikä fi- tai sv-kansiossa ole avaimia, joita values/ (en) ei tunne. Luetaan tiedostot
 *  suoraan, koska testit ovat puhtaita JVM-testejä (ei Robolectricia). */
class StringResourceParityTest {
    private val resDirs = listOf(File("src/main/res"), File("core/res"))
    private val entry = Regex("<(string|plurals)\\s+name=\"([^\"]+)\"([^>]*)>([\\s\\S]*?)</\\1>")
    private val arg = Regex("%(\\d+)\\$[-0-9.]*[sdfx]")

    private data class Entry(val args: Set<String>, val translatable: Boolean, val body: String, val file: String)

    private fun parse(dir: File): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        dir.listFiles { f -> f.name.contains("strings") && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                entry.findAll(f.readText()).forEach { m ->
                    val (kind, name, attrs, body) = m.destructured
                    out["$kind:$name"] = Entry(
                        args = arg.findAll(body).map { it.groupValues[1] }.toSet(),
                        translatable = !attrs.contains("translatable=\"false\""),
                        body = body.trim(),
                        file = f.name,
                    )
                }
            }
        return out
    }

    @Test
    fun everyTranslatableKeyExistsInFiAndSvWithSameArgs() {
        val problems = ArrayList<String>()
        for (res in resDirs) {
            val en = parse(File(res, "values"))
            assertTrue("values/ tyhja: $res", en.isNotEmpty())
            for (loc in listOf("values-fi", "values-sv")) {
                val tr = parse(File(res, loc))
                for ((k, e) in en) {
                    if (!e.translatable) {
                        if (k in tr) problems.add("$res/$loc: $k on translatable=false, poista kaannos")
                        continue
                    }
                    val t = tr[k]
                    when {
                        t == null -> problems.add("$res/$loc: puuttuu $k (${e.file})")
                        t.args != e.args -> problems.add("$res/$loc: $k argumentit ${t.args} != en ${e.args}")
                    }
                }
                for (k in tr.keys) if (k !in en) problems.add("$res/$loc: ylimaarainen $k")
            }
        }
        assertTrue(problems.joinToString("\n", prefix = "${problems.size} ongelmaa:\n"), problems.isEmpty())
    }

    @Test
    fun finnishIsNotAnEnglishCopy() {
        // Sama teksti fi:ssa ja en:ssa on lahes aina kaantamatta jaanyt rivi. Sallitaan lyhyet, numerot,
        // yksikot, brandit (isolla alkava yksi sana / lyhenne) ja URL:t.
        val allowed = Regex("[\\d\\s%°.,:/–\\-+()]+|[A-ZÄÖÅ][A-Za-zÄÖÅäöå0-9.\\-]{0,20}|[A-Z]{2,6}|https?://.*|.*\\bHealth Connect\\b.*|Open-Meteo.*|MET Norway.*|Wi-Fi|Bluetooth|GPS|RSS|Atom|OK|Tampere|Nysse|HSL|FMI|Ruuvi.*|%\\d\\$[sd]")
        val problems = ArrayList<String>()
        for (res in resDirs) {
            val en = parse(File(res, "values"))
            val fi = parse(File(res, "values-fi"))
            for ((k, e) in en) {
                val f = fi[k] ?: continue
                if (e.body.length > 3 && e.body == f.body && !allowed.matches(e.body) && !formatOnly(e.body)) {
                    problems.add("$res: $k = \"${e.body.take(50)}\" (${e.file})")
                }
            }
        }
        assertTrue(problems.joinToString("\n", prefix = "fi = en (${problems.size}):\n"), problems.isEmpty())
    }

    /** Pelkkiä muotoiluargumentteja, yksiköitä ja min/avg/max-sanoja: sama kaikilla kielillä on oikein. */
    private fun formatOnly(body: String): Boolean =
        body.replace(Regex("%\\d+\\$[-0-9.]*[sdfx]|[\\s°C·•.,:/–\\-()]+|\\b(min|avg|max)\\b|%"), "").isEmpty()

    @Test
    fun pluralsHaveOneAndOtherInEveryLocale() {
        val problems = ArrayList<String>()
        for (res in resDirs) for (loc in listOf("values", "values-fi", "values-sv")) {
            for ((k, e) in parse(File(res, loc))) {
                if (!k.startsWith("plurals:")) continue
                if (!e.body.contains("quantity=\"one\"") || !e.body.contains("quantity=\"other\"")) {
                    problems.add("$res/$loc: $k ilman one+other")
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
