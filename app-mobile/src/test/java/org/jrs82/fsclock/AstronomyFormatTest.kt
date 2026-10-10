package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.TimeZone

/** Kellonajat ja päivän pituus ovat pelkkiä numeroita kielestä riippumatta. */
class AstronomyFormatTest {

    @Test fun formatMinutes_isZeroPaddedClock() {
        assertEquals("01:05", Astronomy.formatMinutes(65))
        assertEquals("23:59", Astronomy.formatMinutes(23 * 60 + 59))
    }

    @Test fun formatDuration_wrapsNegativeOverMidnight() {
        assertEquals("18 h 30 min", Astronomy.formatDuration(18 * 60 + 30))
        assertEquals("23 h 50 min", Astronomy.formatDuration(-10))
    }

    @Test fun calculate_givesMoonPhaseWithinCycle() {
        val sm = Astronomy.calculate(Date(1_780_000_000_000L), 60.17, 24.94, TimeZone.getTimeZone("Europe/Helsinki"))
        assertTrue(sm.moon.phase >= 0.0 && sm.moon.phase < 1.0)
        assertTrue(sm.moon.illumination in 0.0..1.0)
    }
}
