package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.9.0 joukkoliikenneominaisuuksien puhtaiden apureiden yksikkötestit:
 * häiriöiden severity-järjestys ([TransitAlert]) ja koko päivän aikataulun tunti-ryhmittely
 * ([fullDayHourHeaders]). Ei Androidia — vain logiikka. Tunti-testit käyttävät 3600 s askelia,
 * jotka vaihtavat tunnin missä tahansa täystunti-aikavyöhykkeessä → riippumattomia koneen TZ:stä.
 */
class TransitFeaturesTest {

    private fun alert(severity: String, header: String = "H", desc: String = "D") =
        TransitAlert(header, desc, severity, "DETOUR", 0L, 0L)

    @Test
    fun `severityRank mappaa tasot oikein`() {
        assertEquals(3, alert("SEVERE").severityRank())
        assertEquals(2, alert("WARNING").severityRank())
        assertEquals(1, alert("INFO").severityRank())
        assertEquals(0, alert("UNKNOWN_SEVERITY").severityRank())
        assertEquals(0, alert("").severityRank())
        // Kirjainkoko ei vaikuta.
        assertEquals(2, alert("warning").severityRank())
    }

    @Test
    fun `displayText suosii otsikkoa, muuten kuvaus`() {
        assertEquals("Otsikko", TransitAlert("Otsikko", "Kuvaus", "INFO", "", 0L, 0L).displayText())
        assertEquals("Kuvaus", TransitAlert("", "Kuvaus", "INFO", "", 0L, 0L).displayText())
    }

    @Test
    fun `maxSeverityRank palauttaa vakavimman ja 0 tyhjalle`() {
        assertEquals(0, TransitAlert.maxSeverityRank(emptyList()))
        assertEquals(0, TransitAlert.maxSeverityRank(null))
        assertEquals(
            3,
            TransitAlert.maxSeverityRank(listOf(alert("INFO"), alert("SEVERE"), alert("WARNING"))),
        )
        assertEquals(
            2,
            TransitAlert.maxSeverityRank(listOf(alert("INFO"), alert("WARNING"))),
        )
    }

    @Test
    fun `fullDayHourHeaders tyhjasta listasta on tyhja`() {
        assertTrue(fullDayHourHeaders(emptyList()).isEmpty())
    }

    @Test
    fun `sama tunti tuottaa otsikon vain kerran`() {
        val t = 1_700_000_000L
        val headers = fullDayHourHeaders(listOf(t, t, t))
        assertEquals(3, headers.size)
        // Pelkkä kaksinumeroinen tunti — UI muotoilee otsikon kielen mukaan (transit_hour_header).
        assertTrue(headers[0]!!.matches(Regex("\\d{2}")))
        assertNull(headers[1])
        assertNull(headers[2])
    }

    @Test
    fun `tunnin vaihtuessa tulee uusi otsikko joka kohtaan`() {
        val t = 1_700_000_000L
        val headers = fullDayHourHeaders(listOf(t, t + 3600, t + 7200))
        assertEquals(3, headers.size)
        headers.forEach { assertTrue(it != null && it.matches(Regex("\\d{2}"))) }
    }

    // --- Häiriöt ja muutokset -sivu ---

    private fun disruption(
        header: String, route: String = "", mode: String = "", stop: String = "",
        severity: String = "INFO", start: Long = 0L, end: Long = 0L,
    ) = TransitAlert(header, "", severity, "DETOUR", start, end, route, mode, stop)

    @Test
    fun `isActiveAt kunnioittaa alku- ja loppuaikaa seka 0-rajoja`() {
        val ranged = TransitAlert("h", "d", "INFO", "", 100L, 200L)
        assertFalse(ranged.isActiveAt(50L))
        assertTrue(ranged.isActiveAt(150L))
        assertFalse(ranged.isActiveAt(250L))
        // 0 = ei rajaa kyseiseen suuntaan.
        assertTrue(TransitAlert("h", "d", "INFO", "", 0L, 0L).isActiveAt(999_999L))
        val startOnly = TransitAlert("h", "d", "INFO", "", 100L, 0L)
        assertFalse(startOnly.isActiveAt(50L))
        assertTrue(startOnly.isActiveAt(150L))
    }

    @Test
    fun `filterDisruptions moodi - valittu vaatii tasmayksen, yleiset vain Kaikki-nakymassa`() {
        val list = listOf(
            disruption("Bussi 63 peruttu", route = "63", mode = "BUS", stop = "Posti"),
            disruption("Ratikka 9", route = "9", mode = "TRAM", stop = "Kamppi"),
            disruption("Terminaali siirtyy"),  // yleinen, mode=""
        )
        assertEquals(3, filterDisruptions(list, null, false, "", 0L).size)        // Kaikki
        val bus = filterDisruptions(list, "BUS", false, "", 0L)
        assertEquals(1, bus.size)
        assertEquals("63", bus[0].routeShortName)                                 // yleinen EI mukana
    }

    @Test
    fun `filterDisruptions tekstihaku osuu linjaan, pysakkiin ja otsikkoon`() {
        val list = listOf(
            disruption("Bussi 63 peruttu", route = "63", mode = "BUS", stop = "Posti"),
            disruption("Ratikka 9", route = "9", mode = "TRAM", stop = "Kamppi"),
        )
        assertEquals("9", filterDisruptions(list, null, false, "kamppi", 0L).single().routeShortName)
        assertEquals("63", filterDisruptions(list, null, false, "63", 0L).single().routeShortName)
        assertEquals("63", filterDisruptions(list, null, false, "peruttu", 0L).single().routeShortName)
        assertTrue(filterDisruptions(list, null, false, "metro", 0L).isEmpty())
    }

    @Test
    fun `filterDisruptions yksi merkki osuu vain linjaan ei proosaan`() {
        val list = listOf(
            disruption("Lähijuna A Leppävaaraan", route = "A", mode = "RAIL", stop = "Pasila"),
            disruption("Bussin 30 reitti muuttuu Arabiassa", route = "30", mode = "BUS", stop = "Arabia"),
        )
        // "a" osuu vain linjaan A (tarkka/alkuosa), EI bussiin 30 vaikka sen otsikko/pysäkki sisältää a-kirjaimen.
        val res = filterDisruptions(list, null, false, "a", 0L)
        assertEquals(1, res.size)
        assertEquals("A", res.single().routeShortName)
        // ≥2 merkkiä avaa myös vapaan tekstihaun (pysäkki).
        assertEquals("30", filterDisruptions(list, null, false, "arabia", 0L).single().routeShortName)
    }

    @Test
    fun `filterDisruptions voimassa-suodatin pudottaa menneet`() {
        val list = listOf(
            disruption("vanha", route = "1", mode = "BUS", start = 10L, end = 20L),
            disruption("nyt", route = "2", mode = "BUS", start = 10L, end = 100L),
        )
        assertEquals(2, filterDisruptions(list, null, false, "", 50L).size)       // activeOnly pois
        val active = filterDisruptions(list, null, true, "", 50L)
        assertEquals(1, active.size)
        assertEquals("2", active[0].routeShortName)
    }

    // --- Aluekohtainen live-sijainti (Waltti/Tampere EI tarjoa GraphQL vehiclePositions -kenttää,
    //     vain HSL → live-sijainti GraphQL:n kautta vain HSL-alueella). ---

    @Test
    fun `forGtfsId tunnistaa alueen gtfsId-prefiksista`() {
        assertEquals(TransitRegion.TAMPERE, TransitRegion.forGtfsId("tampere:0015"))
        assertEquals(TransitRegion.HSL, TransitRegion.forGtfsId("HSL:1234"))
        assertEquals(TransitRegion.HSL, TransitRegion.forGtfsId(null))
        assertEquals(TransitRegion.HSL, TransitRegion.forGtfsId(""))
    }

    @Test
    fun `live-vehiclePositions tosi vain HSL-alueella`() {
        assertTrue(TransitRegion.HSL.liveVehiclePositions)
        assertFalse(TransitRegion.TAMPERE.liveVehiclePositions)
    }

    @Test
    fun `liveViaMqtt tosi vain Tampereella`() {
        // Tampere: live MQTT GTFS-RT:stä; HSL: live GraphQL:stä, ei MQTT-erikoispolkua.
        assertTrue(TransitRegion.TAMPERE.liveViaMqtt)
        assertFalse(TransitRegion.HSL.liveViaMqtt)
    }

    private fun emptyTimeline() =
        TripTimeline("4", "Hiedanranta", "BUS", emptyList(), emptyList(), -1, false)

    @Test
    fun `tripBannerText HSL ilman ajoneuvoa kertoo ettei vuoro ole viela liikkeella`() {
        val msg = tripBannerText(emptyTimeline(), "BUS", TransitRegion.HSL)
        assertEquals(TransitText.Res(R.string.transit_banner_trip_not_started), msg)
    }

    @Test
    fun `tripBannerText Tampere ilman ajoneuvoa hakee live-sijaintia MQTT-polulla`() {
        // Tampereella trip-näkymässä on nyt MQTT-live → kun GraphQL-dataa ei ole, neutraali
        // "haetaan" eikä valhe "ei ole saatavilla" (live ON saatavilla) tai "ei ole vielä liikkeellä".
        val msg = tripBannerText(emptyTimeline(), "BUS", TransitRegion.TAMPERE)
        assertNotEquals(TransitText.Res(R.string.transit_banner_trip_not_started), msg)
        assertNotEquals(TransitText.Res(R.string.transit_banner_trip_no_live_region), msg)
        assertEquals(TransitText.Res(R.string.transit_banner_fetching_live), msg)
    }

    @Test
    fun `routeBannerText Tampere ilman ajoneuvoja hakee live-sijaintia MQTT-polulla`() {
        // Tampereen linjanäkymässä on nyt MQTT-live (B3) → kun GraphQL-dataa ei ole, neutraali
        // "haetaan" eikä valhe "ei ole saatavilla" (live ON saatavilla) tai "ei vuoroja liikkeellä".
        val msg = routeBannerText(emptyTimeline(), TransitRegion.TAMPERE)
        assertNotEquals(TransitText.Res(R.string.transit_banner_route_none), msg)
        assertNotEquals(TransitText.Res(R.string.transit_banner_route_no_live_region), msg)
        assertEquals(TransitText.Res(R.string.transit_banner_route_fetching), msg)
    }

    @Test
    fun `routeBannerText HSL ilman ajoneuvoja sailyttaa nykyviestin`() {
        val msg = routeBannerText(emptyTimeline(), TransitRegion.HSL)
        assertEquals(TransitText.Res(R.string.transit_banner_route_none), msg)
    }

    // --- Kielirakenteet (i18n): banneri on resurssi-id + argumentit, ei suomenkielinen merkkijono ---

    private fun stop(id: String, name: String) = TimelineStop(id, name, "", 0L, false)

    private fun timelineWithVehicle(vehicleAt: Int, board: Int, incoming: Boolean) = TripTimeline(
        "550", "Itäkeskus", "BUS",
        listOf(stop("HSL:1", "Kamppi"), stop("HSL:2", "Ruoholahti"), stop("HSL:3", "Lauttasaari"),
            stop("HSL:4", "Koivusaari")),
        listOf(vehicleAt), board, incoming,
    )

    @Test
    fun `tripBannerText pysakkien maara on monikko ja sisaltaa kulkuvalineen ja sijainnin`() {
        val msg = tripBannerText(timelineWithVehicle(0, 3, incoming = false), "BUS", TransitRegion.HSL)
        val word = TransitText.Res(R.string.transit_vehicle_bus)
        val pos = TransitText.Res(R.string.transit_pos_at, listOf("Kamppi"))
        assertEquals(TransitText.Plural(R.plurals.transit_banner_stops_away, 3, listOf(word, 3, pos)), msg)
    }

    @Test
    fun `tripBannerText lahestyva ajoneuvo kayttaa matkalla-muotoa ja raitiovaunun nimea`() {
        // Lähestyttäessä efektiivinen indeksi on relIdx-1 → 3 - (1-1) = 3 pysäkkiä.
        val msg = tripBannerText(timelineWithVehicle(1, 3, incoming = true), "TRAM", TransitRegion.HSL)
        val word = TransitText.Res(R.string.transit_vehicle_tram)
        val pos = TransitText.Res(R.string.transit_pos_en_route, listOf("Ruoholahti"))
        assertEquals(TransitText.Plural(R.plurals.transit_banner_stops_away, 3, listOf(word, 3, pos)), msg)
    }

    @Test
    fun `tripBannerText ohitettu ja omalla pysakilla`() {
        val word = TransitText.Res(R.string.transit_vehicle_bus)
        assertEquals(
            TransitText.Res(R.string.transit_banner_passed_now, listOf(word, "Lauttasaari")),
            tripBannerText(timelineWithVehicle(2, 1, incoming = false), "BUS", TransitRegion.HSL),
        )
        assertEquals(
            TransitText.Res(R.string.transit_banner_at_your_stop, listOf(word, "Ruoholahti")),
            tripBannerText(timelineWithVehicle(1, 1, incoming = false), "BUS", TransitRegion.HSL),
        )
    }

    @Test
    fun `routeBannerText liikkeella olevien vuorojen maara on monikko`() {
        val tl = TripTimeline("4", "Hiedanranta", "BUS", emptyList(), listOf(1, 3), -1, false)
        assertEquals(
            TransitText.Plural(R.plurals.transit_banner_route_running, 2, listOf(2)),
            routeBannerText(tl, TransitRegion.HSL),
        )
        assertEquals(TransitText.Plural(R.plurals.transit_banner_route_running, 1, listOf(1)), routeRunningText(1))
    }

    @Test
    fun `kulkuvalineen subjektisana valitaan moodista ja tuntematon on bussi`() {
        assertEquals(R.string.transit_vehicle_train, transitVehicleRes("RAIL"))
        assertEquals(R.string.transit_vehicle_metro, transitVehicleRes("SUBWAY"))
        assertEquals(R.string.transit_vehicle_ferry, transitVehicleRes("FERRY"))
        assertEquals(R.string.transit_vehicle_bus, transitVehicleRes(null))
    }

    @Test
    fun `alueiden nimet ovat resursseja (sv HRT ja Tammerfors)`() {
        assertEquals(R.string.transit_region_hsl, TransitRegion.HSL.labelRes)
        assertEquals(R.string.transit_region_tampere, TransitRegion.TAMPERE.labelRes)
    }

    @Test
    fun `liikennetiedotteiden tyypeilla on omat resurssinimet`() {
        val ids = TrafficNotice.Kind.values().map { it.titleRes }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(R.string.transit_traffic_roadworks, TrafficNotice.Kind.ROAD_WORK.titleRes)
    }

    @Test
    fun `hairio osuu suosikkipysakkiin gtfsIdlla vaikka nimi on eri kielella`() {
        val a = TransitAlert("h", "d", "INFO", "", 0L, 0L, "", "", "Järnvägstorget", "HSL:1020201")
        assertTrue(a.matchesStop(setOf("HSL:1020201"), setOf("Rautatientori")))
        assertFalse(a.matchesStop(setOf("HSL:9"), setOf("Rautatientori")))
        // Vanha suosikki ilman id-osumaa löytyy yhä nimellä.
        assertTrue(a.matchesStop(emptySet(), setOf("Järnvägstorget")))
    }
}
