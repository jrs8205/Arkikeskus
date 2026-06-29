package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinaviaAirportsTest {
    @Test fun helOnEnsimmainen() {
        assertEquals("HEL", FinaviaAirports.ALL.first().iata)
    }
    @Test fun nimiLoytyyJaFallback() {
        assertEquals("Helsinki-Vantaa", FinaviaAirports.name("HEL"))
        assertEquals("XXX", FinaviaAirports.name("XXX")) // tuntematon → koodi
        assertTrue(FinaviaAirports.ALL.size >= 16)
    }
}
