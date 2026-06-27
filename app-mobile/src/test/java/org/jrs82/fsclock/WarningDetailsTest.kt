package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningDetailsTest {

    @Test fun emptyHasNothing() {
        assertFalse(WarningDetails.EMPTY.hasAny())
        assertEquals(-1, WarningDetails.EMPTY.probabilityPct)
        assertEquals("", WarningDetails.EMPTY.physicalText)
        assertEquals("", WarningDetails.EMPTY.detailText)
    }

    @Test fun hasAnyTrueWhenProbability() {
        assertTrue(WarningDetails(40, "", "").hasAny())
    }

    @Test fun hasAnyTrueWhenPhysical() {
        assertTrue(WarningDetails(-1, "Lämpötila jopa 27 °C", "").hasAny())
    }

    @Test fun legacyWarningHasEmptyDetails() {
        val w = WeatherWarning("Hellevaroitus", "k", "Uusimaa",
            0L, 1L, WeatherWarning.Level.YELLOW, "id", false)
        assertFalse(w.details.hasAny())
    }

    @Test fun withDetailsCopiesAndKeepsOtherFields() {
        val w = WeatherWarning("Hellevaroitus", "k", "Uusimaa",
            0L, 1L, WeatherWarning.Level.YELLOW, "id", false)
        val e = w.withDetails(WarningDetails(40, "Lämpötila jopa 27 °C", "pitkä teksti"))
        assertEquals(40, e.details.probabilityPct)
        assertEquals("Lämpötila jopa 27 °C", e.details.physicalText)
        assertEquals("pitkä teksti", e.details.detailText)
        // muut kentät säilyvät
        assertEquals("Hellevaroitus", e.event)
        assertEquals("Uusimaa", e.areaDesc)
        assertEquals(WeatherWarning.Level.YELLOW, e.level)
    }
}
