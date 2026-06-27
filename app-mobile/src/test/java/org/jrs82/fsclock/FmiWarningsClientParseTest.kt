package org.jrs82.fsclock

import org.junit.Assert.assertEquals
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
        val list = FmiWarningsClient().parse(json)
        assertEquals(3, list.size)
        val hot = list[0]
        assertEquals("Hellevaroitus", hot.event)
        assertEquals(WeatherWarning.AwarenessType.HIGH_TEMPERATURE, hot.awarenessType)
        assertEquals(WeatherWarning.Level.YELLOW, hot.level)
        assertEquals("Uusimaa", hot.areaDesc)
        assertEquals(40, hot.details.probabilityPct)
        assertEquals("Lämpötila jopa 27 °C", hot.details.physicalText)
        assertTrue(hot.onsetMs > 0L && hot.expiresMs > hot.onsetMs)

        val uv = list[1]
        assertEquals("UV-tiedote", uv.event)
        assertEquals(WeatherWarning.AwarenessType.UV, uv.awarenessType)
        assertEquals("UV-indeksi 6", uv.details.physicalText)

        val sea = list[2]
        assertEquals("Huomautus veneilijöille", sea.event)
        assertTrue(sea.marine)
        assertEquals(WeatherWarning.Level.ORANGE, sea.level)
        assertEquals("Varsinais-Suomi", sea.areaDesc)
    }
}
