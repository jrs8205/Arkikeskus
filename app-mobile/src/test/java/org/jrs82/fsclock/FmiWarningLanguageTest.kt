package org.jrs82.fsclock

import org.jrs82.fsclock.mobile.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FMI GeoServerin info_fi/info_sv/info_en: valitun kielen teksti ja putoaminen suomeen. */
class FmiWarningLanguageTest {

    // 1. feature: info_sv tyhjä, info_en olemassa. 2. feature: info_sv JSON-null, info_en puuttuu.
    private val json = """
    {"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{
        "warning_context":"wind","severity":"level-2","physical_value":21,"physical_unit":"m/s",
        "reference":"http://gml.fmi.fi/static/2025/FI/county.xml#county.1",
        "effective_from":"2026-10-09T12:00:00Z","effective_until":"2026-10-09T20:00:00Z",
        "info_fi":"Tuulivaroitus maa-alueille: puuskat 21 m/s.",
        "info_sv":"",
        "info_en":"Wind warning for land areas: gusts 21 m/s."}},
      {"type":"Feature","properties":{
        "warning_context":"sea-wave-height","severity":"level-3",
        "effective_from":"2026-10-09T12:00:00Z","effective_until":"2026-10-09T20:00:00Z",
        "info_fi":"Aallokkovaroitus: merkitsev&auml; aallonkorkeus 4 metri&auml;.",
        "info_sv":null}}
    ]}
    """.trimIndent()

    private fun keyFor(tag: String) = AppLocale.fmiInfoKey(AppLocale.resolve(listOf(tag)))

    @Test fun englishTextChosenWhenPresent() {
        val list = FmiWarningsClient().parse(json, keyFor("en-GB"))
        assertEquals("Wind warning for land areas: gusts 21 m/s.", list[0].description)
        val details = FmiWarningDetailsClient().parse(json, keyFor("en-GB"))
        assertEquals("Wind warning for land areas: gusts 21 m/s.", details[0].detailText)
    }

    @Test fun emptySwedishFallsBackToFinnish() {
        val list = FmiWarningsClient().parse(json, keyFor("sv-FI"))
        assertEquals("Tuulivaroitus maa-alueille: puuskat 21 m/s.", list[0].description)
        val details = FmiWarningDetailsClient().parse(json, keyFor("sv-FI"))
        assertEquals("Tuulivaroitus maa-alueille: puuskat 21 m/s.", details[0].detailText)
    }

    @Test fun nullOrMissingFieldFallsBackToFinnish() {
        // info_sv = null (ei saa näkyä "null") ja info_en puuttuu kokonaan
        val sv = FmiWarningsClient().parse(json, "info_sv")[1]
        assertEquals("Aallokkovaroitus: merkitsevä aallonkorkeus 4 metriä.", sv.description)
        val en = FmiWarningsClient().parse(json, "info_en")[1]
        assertEquals("Aallokkovaroitus: merkitsevä aallonkorkeus 4 metriä.", en.description)
    }

    @Test fun finnishKeyReadsFinnish() {
        val list = FmiWarningsClient().parse(json, keyFor("fi"))
        assertTrue(list[0].description.startsWith("Tuulivaroitus"))
        assertEquals("Tuulivaroitus maa-alueille: puuskat 21 m/s.",
            FmiWarningDetailsClient().parse(json, "")[0].detailText)
    }

    @Test fun languageChangeMakesCacheStale() {
        val min = 12L * 60_000L
        val now = 10_000_000L
        // sama kieli, tuore → ei haeta
        assertFalse(WarningsRepository.isStale(now, now - 1_000L, "info_fi", "info_fi", min))
        // kieli vaihtui → haetaan heti, vaikka välimuisti on tuore
        assertTrue(WarningsRepository.isStale(now, now - 1_000L, "info_fi", "info_sv", min))
        // vanha tai ei koskaan haettu → haetaan
        assertTrue(WarningsRepository.isStale(now, now - min, "info_fi", "info_fi", min))
        assertTrue(WarningsRepository.isStale(now, 0L, "info_fi", "info_fi", min))
    }
}
