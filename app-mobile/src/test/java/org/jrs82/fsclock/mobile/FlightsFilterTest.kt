package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class FlightsFilterTest {
    private fun f(dir: FlightDir, apt: String, fno: String, sch: Long, cs: List<String> = emptyList()) =
        Flight(dir, apt, fno, sch, null, null, "", "", "X", "X", null, null, null, null, null, cs)

    private val data = FlightsData(
        updatedMs = 0L,
        dep = listOf(f(FlightDir.DEP, "HEL", "AY1731", 300), f(FlightDir.DEP, "HEL", "AY100", 100), f(FlightDir.DEP, "OUL", "AY500", 200)),
        arr = listOf(f(FlightDir.ARR, "HEL", "AY432", 150, cs = listOf("JL6877"))),
    )

    @Test fun boardSuodattaaKentanJaSuunnanJaJarjestaa() {
        val r = FlightsFilter.board(data, "HEL", FlightDir.DEP, 0L)
        assertEquals(listOf("AY100", "AY1731"), r.map { it.flightNo }) // nouseva sch
    }

    @Test fun boardPiilottaaVanhatSaapuneetMuttaPitaaMyohastyneet() {
        val now = 1_000_000_000_000L
        fun done(fno: String, sch: Long, act: Long) =
            Flight(FlightDir.DEP, "HEL", fno, sch, null, act, "", "", "X", "X", null, null, null, null, null, emptyList())
        val d = FlightsData(0L, arr = emptyList(), dep = listOf(
            done("OLD_DONE", now - 120 * 60_000L, now - 90 * 60_000L),  // lähtenyt 90 min sitten -> piiloon
            done("RECENT_DONE", now - 20 * 60_000L, now - 10 * 60_000L), // lähtenyt 10 min sitten -> näkyy
            f(FlightDir.DEP, "HEL", "PENDING", now - 70 * 60_000L),      // myöhässä, ei lähtenyt -> näkyy aina
        ))
        val r = FlightsFilter.board(d, "HEL", FlightDir.DEP, now)
        assertEquals(listOf("PENDING", "RECENT_DONE"), r.map { it.flightNo })
    }

    @Test fun boardJarjestaaAikataulunMukaanEiArvionMukaan() {
        // DELAYED: aikataulu aikaisin (100), arvio myöhään (10000); ONTIME: aikataulu myöhemmin (200).
        val delayed = Flight(FlightDir.DEP, "HEL", "DELAYED", 100L, 10_000L, null, "", "", "X", "X", null, null, null, null, null, emptyList())
        val ontime = Flight(FlightDir.DEP, "HEL", "ONTIME", 200L, null, null, "", "", "X", "X", null, null, null, null, null, emptyList())
        val d = FlightsData(0L, arr = emptyList(), dep = listOf(ontime, delayed))
        val r = FlightsFilter.board(d, "HEL", FlightDir.DEP, 0L)
        // Aikataulun mukaan: DELAYED (100) ennen ONTIMEa (200), vaikka DELAYED:n arvio (10000) on myöhemmin.
        assertEquals(listOf("DELAYED", "ONTIME"), r.map { it.flightNo })
    }

    @Test fun searchKattaaKaikkiKentatJaSuunnatJaCodeshare() {
        assertEquals(1, FlightsFilter.search(data, "AY500").size)       // toinen kenttä
        assertEquals("AY432", FlightsFilter.search(data, "ay 432")[0].flightNo) // ci + välilyönti
        assertEquals("AY432", FlightsFilter.search(data, "JL6877")[0].flightNo) // codeshare
        assertEquals(0, FlightsFilter.search(data, "").size)
    }

    @Test fun airportsWithCountsLaskee() {
        val c = FlightsFilter.airportsWithCounts(data)
        assertEquals(3, c["HEL"])  // 2 dep + 1 arr
        assertEquals(1, c["OUL"])
    }
}
