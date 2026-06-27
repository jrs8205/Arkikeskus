package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherWarningLevel5dTest {
    @Test fun fmiSeverityToLevel() {
        assertEquals(WeatherWarning.Level.GREEN, WeatherWarning.Level.fromFmiSeverity("level-1"))
        assertEquals(WeatherWarning.Level.YELLOW, WeatherWarning.Level.fromFmiSeverity("level-2"))
        assertEquals(WeatherWarning.Level.ORANGE, WeatherWarning.Level.fromFmiSeverity("level-3"))
        assertEquals(WeatherWarning.Level.RED, WeatherWarning.Level.fromFmiSeverity("level-4"))
        assertEquals(WeatherWarning.Level.UNKNOWN, WeatherWarning.Level.fromFmiSeverity(null))
        assertEquals(WeatherWarning.Level.UNKNOWN, WeatherWarning.Level.fromFmiSeverity("nonsense"))
    }
    @Test fun greenFields() {
        assertEquals("Vihreä", WeatherWarning.Level.GREEN.fiName)
        assertEquals(0, WeatherWarning.Level.GREEN.rank())
    }
    @Test fun uvType() {
        assertEquals("UV", WeatherWarning.AwarenessType.UV.fiName)
    }
}
