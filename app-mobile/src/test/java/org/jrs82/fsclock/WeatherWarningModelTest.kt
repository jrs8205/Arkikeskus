package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Test

/** [WeatherWarning.AwarenessType]-parsinta ja taaksepäin yhteensopiva konstruktori. */
class WeatherWarningModelTest {

    @Test fun parsesForestFireByCode() {
        assertEquals(WeatherWarning.AwarenessType.FOREST_FIRE,
            WeatherWarning.AwarenessType.fromParam("8; forest-fire"))
    }

    @Test fun parsesHighTemperatureByCode() {
        assertEquals(WeatherWarning.AwarenessType.HIGH_TEMPERATURE,
            WeatherWarning.AwarenessType.fromParam("5; high-temperature"))
    }

    @Test fun parsesWindByCode() {
        assertEquals(WeatherWarning.AwarenessType.WIND,
            WeatherWarning.AwarenessType.fromParam("1; wind"))
    }

    @Test fun fallsBackToKeywordWhenNoCode() {
        assertEquals(WeatherWarning.AwarenessType.THUNDERSTORM,
            WeatherWarning.AwarenessType.fromParam("thunderstorm"))
    }

    @Test fun unknownForNullOrEmpty() {
        assertEquals(WeatherWarning.AwarenessType.UNKNOWN, WeatherWarning.AwarenessType.fromParam(null))
        assertEquals(WeatherWarning.AwarenessType.UNKNOWN, WeatherWarning.AwarenessType.fromParam(""))
    }

    @Test fun legacyConstructorDefaultsNewFields() {
        val w = WeatherWarning("Hellevaroitus", "kuvaus", "Uusimaa",
            0L, 1L, WeatherWarning.Level.YELLOW, "id-1", false)
        assertEquals(WeatherWarning.AwarenessType.UNKNOWN, w.awarenessType)
        assertEquals("", w.severity)
        assertEquals("", w.web)
        assertEquals(0L, w.effectiveMs)
    }
}
