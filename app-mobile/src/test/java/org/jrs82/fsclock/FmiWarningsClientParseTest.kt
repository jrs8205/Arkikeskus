package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FmiWarningsClientParseTest {
    private val json = """
    {"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{
        "warning_context":"hot-weather","actualization_probability":40,
        "physical_value":27,"physical_unit":"celsius",
        "effective_from":"2026-06-28T08:00:00Z","effective_until":"2026-06-28T20:00:00Z",
        "info_fi":"Hellevaroitus: tukalaa hellett&auml;.","severity":"level-2",
        "reference":"http://gml.fmi.fi/static/2025/FI/county.xml#county.1"}},
      {"type":"Feature","properties":{
        "warning_context":"uv-note","actualization_probability":40,
        "physical_value":6,"physical_unit":"index",
        "effective_from":"2026-06-28T08:00:00Z","effective_until":"2026-06-28T13:00:00Z",
        "info_fi":"UV-tiedote: UV-indeksin arvo on 6.","severity":"level-2",
        "reference":"http://gml.fmi.fi/static/2025/FI/county.xml#county.1"}},
      {"type":"Feature","properties":{
        "warning_context":"sea-thunder-storm","actualization_probability":30,
        "effective_from":"2026-06-28T02:00:00Z","effective_until":"2026-06-28T14:00:00Z",
        "info_fi":"Huomautus veneilij&ouml;ille.","severity":"level-3",
        "reference":"http://gml.fmi.fi/static/2025/FI/county.xml#county.2"}}
    ]}
    """.trimIndent()

    @Test fun parsesFeaturesToWarnings() {
        val list = FmiWarningsClient().parse(json, "info_fi")
        assertEquals(3, list.size)
        val hot = list[0]
        // event on konetunniste; näyttönimi resurssista
        assertEquals("hot-weather", hot.event)
        assertEquals(R.string.warning_event_heat, FmiWarningsClient.eventFor(hot.event).nameRes)
        assertEquals(WeatherWarning.AwarenessType.HIGH_TEMPERATURE, hot.awarenessType)
        assertEquals(WeatherWarning.Level.YELLOW, hot.level)
        assertEquals("Uusimaa", hot.areaDesc)
        assertEquals(40, hot.details.probabilityPct)
        assertEquals(27.0, hot.details.physicalValue, 0.0)
        assertEquals("celsius", hot.details.physicalUnit)
        assertEquals("Hellevaroitus: tukalaa hellettä.", hot.description)
        assertTrue(hot.onsetMs > 0L && hot.expiresMs > hot.onsetMs)

        val uv = list[1]
        assertEquals(R.string.warning_event_uv, FmiWarningsClient.eventFor(uv.event).nameRes)
        assertEquals(WeatherWarning.AwarenessType.UV, uv.awarenessType)
        assertEquals("index", uv.details.physicalUnit)

        val sea = list[2]
        assertEquals(R.string.warning_event_boaters, FmiWarningsClient.eventFor(sea.event).nameRes)
        assertTrue(sea.marine)
        assertEquals(WeatherWarning.Level.ORANGE, sea.level)
        assertEquals("Varsinais-Suomi", sea.areaDesc)
    }

    @Test fun senderLeftForUiAndWebFollowsLanguage() {
        val fi = FmiWarningsClient().parse(json, "info_fi")[0]
        // tyhjä lähettäjä → UI näyttää FMI:n nimen käyttäjän kielellä
        assertEquals("", fi.senderName)
        assertEquals("https://www.ilmatieteenlaitos.fi/varoitukset", fi.web)
        assertEquals("https://sv.ilmatieteenlaitos.fi/varningar", FmiWarningsClient().parse(json, "info_sv")[0].web)
        assertEquals("https://en.ilmatieteenlaitos.fi/warnings", FmiWarningsClient().parse(json, "info_en")[0].web)
    }

    @Test fun unknownContextGetsGenericName() {
        val ev = FmiWarningsClient.eventFor("sea-wave-height")
        assertEquals(R.string.warning_event_generic, ev.nameRes)
        assertEquals(WeatherWarning.AwarenessType.UNKNOWN, ev.type)
        assertFalse(ev.marine)
        assertEquals(R.string.warning_event_generic, FmiWarningsClient.eventFor(null).nameRes)
    }
}
