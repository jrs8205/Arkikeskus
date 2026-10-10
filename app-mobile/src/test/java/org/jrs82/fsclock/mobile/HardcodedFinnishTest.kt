package org.jrs82.fsclock.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Käyttäjälle näkyvää suomea ei saa jäädä koodiin: etsii literaalit, joissa on ä/ö tai sanaston
 *  suomenkielinen termi. Datatiedostot (kuntien, lentokenttien ja maakuntien API-nimet) sekä
 *  Log-/poikkeusviestit on rajattu pois. */
class HardcodedFinnishTest {
    private val roots = listOf(File("src/main/java"), File("core/java"))

    /** Tiedostot, joiden suomenkieliset literaalit ovat dataa tai konerajapintaa, eivät UI-tekstiä. */
    private val allowedFiles = setOf(
        // Datalistat ja API-vertailut (kuntien, lentokenttien, maakuntien ja pyhien suomenkieliset nimet)
        "KuntaList.kt", "FinaviaAirports.kt", "FmiCounties.java", "FinnishRegions.kt",
        "FinnishHolidays.java", "HolidayNames.kt", "AlmanakkaClient.java", "FmiClient.java",
        "GeoPlace.java", "MmlGeocodingClient.java", "TrafficNoticesClient.java", "NewsProfile.kt",
        "NewsFeedStore.java", "FlightDisplay.kt", "WeatherWarning.java",
        // Tabletin perintöä core/-puussa: ei ajeta mobiilissa (ei manifestissa eikä kutsuta)
        "ClockController.java", "SettingsFragment.java", "CsvExporter.java", "BatteryChartView.java",
        "WeatherData.java", "SystemActivity.java", "SystemFragment.java", "MainActivity.java", "ClockDream.java",
    )
    private val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val finnish = Regex(
        "[äöÄÖ]|\\b(?:ei|tai|hae|haku|tallenna|peruuta|sulje|valitse|poista|avaa|kaikki|klo|tunti|viikko|" +
            "kuukausi|vuosi|hinta|linja|reitti|askel|lenkki|uutiset|asetukset|varoitus|virhe|ladataan|" +
            "yhteys|tiedot|nopeus|kamera|lento|suosikit|seuraava|edellinen|nyt|huomenna|eilen)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val skipLine = Regex(
        "^\\s*(//|\\*|/\\*)|Log\\.[dwiev]\\(|\\bTAG\\b|https?://|Regex\\(|Pattern\\.|throw |Exception\\(|" +
            "error\\(|require\\(|check\\(|assert|\"(?:fi|sv|en|info_fi|info_sv|info_en|suomi|ruotsi)\"|" +
            "@Preview|KEY_|PREF_|putString\\(\"|getString\\(\"|optString\\(\"|getJSON|\\.name ==|" +
            "Europe/Helsinki|@Suppress",
    )

    @Test
    fun noUserFacingFinnishLiteralsInCode() {
        val hits = ArrayList<String>()
        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") && it.name !in allowedFiles }
                .forEach { f ->
                    var inBlockComment = false
                    var openLogParens = 0
                    f.readLines().forEachIndexed { i, rawLine ->
                        val t = rawLine.trim()
                        if (inBlockComment) { if (t.contains("*/")) inBlockComment = false; return@forEachIndexed }
                        if (t.startsWith("/*") && !t.contains("*/")) { inBlockComment = true; return@forEachIndexed }
                        // Rivin loppukommentti pois ennen literaalien hakua ("..." // "Uusimaa itäosa").
                        val line = rawLine.replace(Regex("(?<!:)//.*$"), "")
                        // Monirivinen Log.x( ... ): jatkorivit ohitetaan, kunnes sulut tasapainottuvat.
                        if (openLogParens > 0) {
                            openLogParens += line.count { it == '(' } - line.count { it == ')' }
                            return@forEachIndexed
                        }
                        if (Regex("Log\\.[dwiev]\\(").containsMatchIn(line)) {
                            openLogParens = line.count { it == '(' } - line.count { it == ')' }
                            return@forEachIndexed
                        }
                        if (skipLine.containsMatchIn(line)) return@forEachIndexed
                        literal.findAll(line).forEach { m ->
                            val s = m.groupValues[1]
                            if (s.length >= 3 && finnish.containsMatchIn(s) && s.any { it.isLowerCase() }) {
                                hits.add("${f.path}:${i + 1}: \"$s\"")
                            }
                        }
                    }
                }
        }
        assertTrue(hits.joinToString("\n", prefix = "${hits.size} suomenkielista literaalia koodissa:\n"), hits.isEmpty())
    }
}
