package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Häiriö voi koskea useaa linjaa ja pysäkkiä (Digitransitin `entities`). Vanha `route`/`stop` antoi vain
 * yhden, jolloin suosikki-ilmoitus ja suodattimet ohittivat häiriön (18.11.2026: 12/19 HSL-häiriöstä).
 */
class TransitMultiEntityAlertTest {

    /** Sama rakenne kuin Digitransitin vastaus 18.11.2026 (Route-, Stop- ja StopOnRoute-kohteet). */
    private val json = """
        [
          {"alertHeaderText":"Pysäkki Kyytimäki (E5140) pois käytöstä","alertDescriptionText":"d1",
           "alertSeverityLevel":"WARNING","alertEffect":"DETOUR","effectiveStartDate":100,"effectiveEndDate":null,
           "entities":[
             {"__typename":"Route","shortName":"162","mode":"BUS"},
             {"__typename":"Route","shortName":"213","mode":"BUS"},
             {"__typename":"Route","shortName":"213N","mode":"BUS"},
             {"__typename":"Route","shortName":"242","mode":"BUS"},
             {"__typename":"Route","shortName":"I","mode":"RAIL"},
             {"__typename":"Stop","gtfsId":"HSL:2611201","name":"Kyytimäki"},
             {"__typename":"StopOnRoute","stop":{"gtfsId":"HSL:2611202","name":"Kyytimäki"},"route":{"shortName":"213","mode":"BUS"}},
             {"__typename":"Unknown"}
           ]},
          {"alertHeaderText":"Yleistiedote","alertDescriptionText":"d2","alertSeverityLevel":"INFO",
           "alertEffect":"OTHER_EFFECT","effectiveStartDate":50,"effectiveEndDate":0,"entities":[{"__typename":"Unknown"}]},
          {"alertHeaderText":"","alertDescriptionText":"","entities":[]}
        ]
    """.trimIndent()

    private fun parsed() = DigitransitApi.parseServiceAlerts(JSONArray(json))

    @Test fun parsesEveryRouteAndStopOnce() {
        val alerts = parsed()
        assertEquals(2, alerts.size) // tyhjä tiedote ohitetaan
        val a = alerts.first { it.header.startsWith("Pysäkki") }
        assertEquals(listOf("162", "213", "213N", "242", "I"), a.routeShortNames)
        assertEquals(listOf("BUS", "BUS", "BUS", "BUS", "RAIL"), a.routeModes)
        assertEquals(listOf("HSL:2611201", "HSL:2611202"), a.stopGtfsIds)
        assertEquals(listOf("Kyytimäki", "Kyytimäki"), a.stopNames)
        // Yksittäiset kentät = ensimmäiset (näyttö, jossa tilaa yhdelle).
        assertEquals("162", a.routeShortName)
        assertEquals("BUS", a.mode)
        assertEquals("HSL:2611201", a.stopGtfsId)
        assertEquals(0L, a.endEpochSec) // JSON null → ei loppua
    }

    @Test fun generalAlertHasNoRoutesOrStops() {
        val g = parsed().first { it.header == "Yleistiedote" }
        assertTrue(g.routeShortNames.isEmpty())
        assertTrue(g.stopNames.isEmpty())
        assertEquals("", g.routeShortName)
    }

    @Test fun favouriteLineAnywhereInTheListNotifies() {
        val a = parsed().first { it.header.startsWith("Pysäkki") }
        // 242 ei ole ensimmäinen linja: vanhalla route-kentällä tämä jäi ilmoittamatta.
        val r = HslAlertNotifier.selectNewAlerts(listOf(a), setOf("242"), emptySet(), emptySet(), 1_000L)
        assertEquals(listOf(a), r.toNotify)
    }

    @Test fun favouriteStopAnywhereInTheListNotifies() {
        val a = parsed().first { it.header.startsWith("Pysäkki") }
        val r = HslAlertNotifier.selectNewAlerts(listOf(a), emptySet(), emptySet(), emptySet(), 1_000L, setOf("HSL:2611202"))
        assertEquals(listOf(a), r.toNotify)
    }

    @Test fun notificationTitleListsRoutes() {
        val a = parsed().first { it.header.startsWith("Pysäkki") }
        assertEquals("162, 213, 213N, …", HslAlertNotifier.routesLabel(a))
        assertEquals(
            TransitText.Res(R.string.transit_hsl_alert_title_line, listOf("162, 213, 213N, …")),
            HslAlertNotifier.notificationText(listOf(a)).title,
        )
    }

    @Test fun alertKeyChangesWhenAffectedStopsChange() {
        val a = parsed().first { it.header.startsWith("Pysäkki") }
        val fewer = TransitAlert(a.header, a.description, a.severity, a.effect, a.startEpochSec, a.endEpochSec,
            a.routeShortNames, a.routeModes, a.stopNames.take(1), a.stopGtfsIds.take(1))
        assertNotEquals(HslAlertNotifier.alertKey(a), HslAlertNotifier.alertKey(fewer))
        assertEquals(HslAlertNotifier.alertKey(a), HslAlertNotifier.alertKey(parsed().first { it.header.startsWith("Pysäkki") }))
    }

    @Test fun alertKeyDoesNotDependOnEntityOrder() {
        // Digitransit palauttaa saman häiriön kohteet hausta toiseen eri järjestyksessä (havaittu 18.11.):
        // pelkkä järjestyksen vaihto ei saa tuottaa uutta ilmoitusta.
        val a = TransitAlert("h", "d", "INFO", "", 0L, 0L,
            listOf("162", "213", "242"), listOf("BUS", "BUS", "BUS"),
            listOf("Kyytimäki", "Kauklahti", "Espoonlahti"), listOf("HSL:1", "HSL:2", "HSL:3"))
        val shuffled = TransitAlert("h", "d", "INFO", "", 0L, 0L,
            listOf("242", "162", "213"), listOf("BUS", "BUS", "BUS"),
            listOf("Kyytimäki", "Espoonlahti", "Kauklahti"), listOf("HSL:1", "HSL:3", "HSL:2"))
        assertEquals(HslAlertNotifier.alertKey(a), HslAlertNotifier.alertKey(shuffled))
        val seen = setOf(HslAlertNotifier.alertKey(a))
        val r = HslAlertNotifier.selectNewAlerts(listOf(shuffled), setOf("242"), emptySet(), seen, 1_000L)
        assertTrue(r.toNotify.isEmpty())
    }

    @Test fun parsedEntitiesHaveStableOrder() {
        val reordered = """
            [{"alertHeaderText":"h","alertDescriptionText":"d","entities":[
              {"__typename":"Stop","gtfsId":"HSL:3","name":"C"},
              {"__typename":"Route","shortName":"I","mode":"RAIL"},
              {"__typename":"Route","shortName":"213N","mode":"BUS"},
              {"__typename":"Stop","gtfsId":"HSL:1","name":"A"},
              {"__typename":"Route","shortName":"55","mode":"BUS"},
              {"__typename":"Route","shortName":"213","mode":"BUS"},
              {"__typename":"Route","shortName":"9","mode":"TRAM"}
            ]}]
        """.trimIndent()
        val a = DigitransitApi.parseServiceAlerts(JSONArray(reordered)).single()
        assertEquals(listOf("9", "55", "213", "213N", "I"), a.routeShortNames)
        assertEquals(listOf("TRAM", "BUS", "BUS", "BUS", "RAIL"), a.routeModes) // moodit seuraavat linjoja
        assertEquals(listOf("HSL:1", "HSL:3"), a.stopGtfsIds)
        assertEquals(listOf("A", "C"), a.stopNames) // nimet seuraavat id:itä
    }

    @Test fun disruptionFiltersSeeEveryRoute() {
        val a = parsed().first { it.header.startsWith("Pysäkki") }
        // Moodi: junalinja I on viides linja → Juna-suodatin löytää häiriön.
        assertEquals(listOf(a), filterDisruptions(listOf(a), "RAIL", false, "", 0L))
        // Linjahaku osuu myöhempään linjaan.
        assertEquals(listOf(a), filterDisruptions(listOf(a), null, false, "242", 0L))
        assertTrue(filterDisruptions(listOf(a), null, false, "550", 0L).isEmpty())
        assertTrue(a.matchesLine(setOf("213N")))
        assertFalse(a.matchesLine(setOf("550")))
    }

    @Test fun cardStopLineShowsFirstStopAndCount() {
        val a = parsed().first { it.header.startsWith("Pysäkki") }
        assertEquals("Kyytimäki", alertStopsSummary(a)) // sama nimi molempiin suuntiin → yksi
        val many = TransitAlert("h", "d", "INFO", "", 0L, 0L, emptyList(), emptyList(),
            listOf("Kurkela", "Kuovinkatu", "Keskikatu"), listOf("HSL:1", "HSL:2", "HSL:3"))
        assertEquals("Kurkela +2", alertStopsSummary(many))
        assertEquals("", alertStopsSummary(TransitAlert("h", "d", "INFO", "", 0L, 0L)))
    }

    @Test fun legacyConstructorKeepsSingleValues() {
        val a = TransitAlert("h", "d", "INFO", "", 0L, 0L, "63", "BUS", "Kamppi", "HSL:1")
        assertEquals(listOf("63"), a.routeShortNames)
        assertEquals(listOf("BUS"), a.routeModes)
        assertEquals(listOf("Kamppi"), a.stopNames)
        assertEquals(listOf("HSL:1"), a.stopGtfsIds)
        val modeOnly = TransitAlert("h", "d", "INFO", "", 0L, 0L, "", "BUS", "")
        assertEquals("BUS", modeOnly.mode)
        assertTrue(modeOnly.routeShortNames.isEmpty())
    }
}
