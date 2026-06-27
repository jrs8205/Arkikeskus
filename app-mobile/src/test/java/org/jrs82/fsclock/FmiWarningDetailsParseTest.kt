package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmiWarningDetailsParseTest {

    private val json = """
    {"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{
        "warning_context":"hot-weather","actualization_probability":40,
        "physical_value":27,"physical_unit":"celsius","physical_direction":null,
        "effective_from":"2026-06-27T12:01:21.479Z","effective_until":"2026-06-27T20:00:00Z",
        "info_fi":"Hellevaroitus: L&auml;hivuorokauden aikana on odotettavissa tukalaa hellett&auml;.",
        "severity":"level-2"}},
      {"type":"Feature","properties":{
        "warning_context":"rain","actualization_probability":30,
        "physical_value":20,"physical_unit":"mm/h","physical_direction":null,
        "effective_from":"2026-06-28T02:00:00Z","effective_until":"2026-06-28T10:00:00Z",
        "info_fi":"Sadevaroitus: Aamuy&ouml;st&auml; alkaen voi sataa rankasti, yli 20 mm tunnissa.",
        "severity":"level-2"}}
    ]}
    """.trimIndent()

    @Test fun decodesFinnishEntities() {
        assertEquals("Lähivuorokauden ää", FmiWarningDetailsClient.decodeEntities("L&auml;hivuorokauden &auml;&auml;"))
        assertEquals("ö å &", FmiWarningDetailsClient.decodeEntities("&ouml; &aring; &amp;"))
        assertEquals("a b", FmiWarningDetailsClient.decodeEntities("a&nbsp;b"))
    }

    @Test fun formatsPhysicalByUnit() {
        assertEquals("Lämpötila jopa 27 °C", FmiWarningDetailsClient.formatPhysical(27.0, "celsius"))
        assertEquals("Sademäärä jopa 20 mm/h", FmiWarningDetailsClient.formatPhysical(20.0, "mm/h"))
        assertEquals("Tuulen puuskat jopa 15 m/s", FmiWarningDetailsClient.formatPhysical(15.0, "m/s"))
        assertEquals("UV-indeksi 6", FmiWarningDetailsClient.formatPhysical(6.0, "index"))
        assertEquals("", FmiWarningDetailsClient.formatPhysical(Double.NaN, "celsius"))
    }

    @Test fun parsesFeatures() {
        val list = FmiWarningDetailsClient().parse(json)
        assertEquals(2, list.size)
        val hot = list[0]
        assertEquals("hot-weather", hot.context)
        assertEquals(40, hot.probabilityPct)
        assertEquals("Lämpötila jopa 27 °C", hot.physicalText)
        assertTrue(hot.detailText.contains("tukalaa hellettä"))
        assertTrue(hot.fromMs > 0L && hot.untilMs > hot.fromMs)
    }
}
