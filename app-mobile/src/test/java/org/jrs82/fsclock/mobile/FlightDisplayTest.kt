package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class FlightDisplayTest {
    private fun f(status: String, sch: Long, est: Long?) =
        Flight(FlightDir.DEP, "HEL", "AY1", sch, est, null, "", status, "X", "X", null, null, null, null, null, emptyList())

    @Test fun kategoriaTekstistaJaMyohastymisesta() {
        assertEquals(FlightStatusCat.CANCELLED, FlightDisplay.category(f("Peruttu", 0, null)))
        assertEquals(FlightStatusCat.COMPLETED, FlightDisplay.category(f("Lähtenyt", 0, null)))
        assertEquals(FlightStatusCat.COMPLETED, FlightDisplay.category(f("Laskeutunut", 0, null)))
        assertEquals(FlightStatusCat.ATTENTION, FlightDisplay.category(f("Lähtöselvitys", 0, null)))
        // 10 min myöhässä, ei lopputilaa
        assertEquals(FlightStatusCat.DELAYED, FlightDisplay.category(f("Arvioitu", 0, 600_000)))
        assertEquals(FlightStatusCat.ON_TIME, FlightDisplay.category(f("Aikataulussa", 0, null)))
    }
}
