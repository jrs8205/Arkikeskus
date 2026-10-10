package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningDetailsTest {

    @Test fun emptyHasNothing() {
        assertFalse(WarningDetails.EMPTY.hasAny())
        assertEquals(-1, WarningDetails.EMPTY.probabilityPct)
        assertTrue(WarningDetails.EMPTY.physicalValue.isNaN())
        assertEquals("", WarningDetails.EMPTY.physicalUnit)
        assertEquals("", WarningDetails.EMPTY.detailText)
    }

    @Test fun hasAnyTrueWhenProbability() {
        assertTrue(WarningDetails(40, Double.NaN, "", "").hasAny())
    }

    @Test fun hasAnyTrueWhenPhysical() {
        assertTrue(WarningDetails(-1, 27.0, "celsius", "").hasAny())
    }

    @Test fun physicalNeedsBothValueAndUnit() {
        assertFalse(WarningDetails(-1, 27.0, "", "").hasPhysical())
        assertFalse(WarningDetails(-1, Double.NaN, "celsius", "").hasPhysical())
        assertFalse(WarningDetails(-1, 27.0, null, "").hasAny())
        assertTrue(WarningDetails(-1, 27.0, "celsius", "").hasPhysical())
    }

    @Test fun legacyWarningHasEmptyDetails() {
        val w = WeatherWarning("Hellevaroitus", "k", "Uusimaa",
            0L, 1L, WeatherWarning.Level.YELLOW, "id", false)
        assertFalse(w.details.hasAny())
    }

    @Test fun withDetailsCopiesAndKeepsOtherFields() {
        val w = WeatherWarning("Hellevaroitus", "k", "Uusimaa",
            0L, 1L, WeatherWarning.Level.YELLOW, "id", false)
        val e = w.withDetails(WarningDetails(40, 27.0, "celsius", "pitkä teksti"))
        assertEquals(40, e.details.probabilityPct)
        assertEquals(27.0, e.details.physicalValue, 0.0)
        assertEquals("celsius", e.details.physicalUnit)
        assertEquals("pitkä teksti", e.details.detailText)
        // muut kentät säilyvät
        assertEquals("Hellevaroitus", e.event)
        assertEquals("Uusimaa", e.areaDesc)
        assertEquals(WeatherWarning.Level.YELLOW, e.level)
    }
}
