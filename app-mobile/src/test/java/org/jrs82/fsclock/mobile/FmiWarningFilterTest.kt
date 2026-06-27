package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.WeatherWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmiWarningFilterTest {

    private fun w(type: WeatherWarning.AwarenessType, area: String, on: Long, ex: Long,
                  level: WeatherWarning.Level = WeatherWarning.Level.YELLOW) =
        WeatherWarning(type.fiName, "kuvaus", area, on, ex, level, "id-$area-$type", false,
            type, "", "", "", 0L, "FMI", "")

    // 2025-06-27 00:00 EEST (Helsinki) = 1750971600000; käytä kiinteitä ms-arvoja
    private val day0 = 1_750_971_600_000L          // 27.6. 00:00 EEST
    private val dayMs = 24L * 60 * 60 * 1000

    @Test fun fiveDaysFromNow() {
        val days = daysFrom(day0 + 10 * 60 * 60 * 1000) // 27.6. klo 10
        assertEquals(5, days.size)
        assertEquals("Tänään", days[0].label)
        assertEquals("Huomenna", days[1].label)
        assertTrue(days[2].label.isNotEmpty())
        assertEquals(day0, days[0].startMs)
        assertEquals(day0 + dayMs, days[0].endMs)
    }

    @Test fun overlapDetectsActiveDay() {
        val warn = w(WeatherWarning.AwarenessType.RAIN, "Uusimaa", day0 + dayMs + 5*3600_000L, day0 + 2*dayMs)
        // varoitus 28.6. → ei osu 27.6., osuu 28.6.
        assertEquals(false, overlapsDay(warn, day0, day0 + dayMs))
        assertEquals(true, overlapsDay(warn, day0 + dayMs, day0 + 2*dayMs))
    }

    @Test fun ownRegionFiltersAndGroups() {
        val all = listOf(
            w(WeatherWarning.AwarenessType.RAIN, "Uusimaa", day0, day0 + dayMs),
            w(WeatherWarning.AwarenessType.RAIN, "Pirkanmaa", day0, day0 + dayMs),
            w(WeatherWarning.AwarenessType.HIGH_TEMPERATURE, "Uusimaa", day0, day0 + dayMs),
        )
        val out = warningsFor(all, DayOption("Tänään", day0, day0 + dayMs), "Uusimaa")
        assertEquals(2, out.size) // RAIN + HIGH_TEMPERATURE Uudellamaalla
        assertTrue(out.all { it.areaDesc == "Uusimaa" })
    }

    @Test fun kokoSuomiMergesRegionsByType() {
        val all = listOf(
            w(WeatherWarning.AwarenessType.RAIN, "Uusimaa", day0, day0 + dayMs),
            w(WeatherWarning.AwarenessType.RAIN, "Pirkanmaa", day0, day0 + dayMs),
        )
        val out = warningsFor(all, DayOption("Tänään", day0, day0 + dayMs), null)
        assertEquals(1, out.size) // yksi Sadevaroitus-kortti
        assertTrue(out[0].areaDesc.contains("Uusimaa") && out[0].areaDesc.contains("Pirkanmaa"))
    }

    @Test fun emptyWhenNoneOnDay() {
        val all = listOf(w(WeatherWarning.AwarenessType.RAIN, "Uusimaa", day0 + 3*dayMs, day0 + 4*dayMs))
        assertEquals(0, warningsFor(all, DayOption("Tänään", day0, day0 + dayMs), null).size)
    }

    @Test fun mergeUsesMaxLevelAndUnionTime() {
        val all = listOf(
            w(WeatherWarning.AwarenessType.RAIN, "Uusimaa", day0, day0 + dayMs,
              level = WeatherWarning.Level.YELLOW),
            w(WeatherWarning.AwarenessType.RAIN, "Pirkanmaa", day0 + 2 * 3600_000L, day0 + 2 * dayMs,
              level = WeatherWarning.Level.ORANGE),
        )
        val out = warningsFor(all, DayOption("Tänään", day0, day0 + dayMs), null)
        assertEquals(1, out.size)
        assertEquals(WeatherWarning.Level.ORANGE, out[0].level)   // max rank
        assertEquals(day0, out[0].onsetMs)                        // min onset
        assertEquals(day0 + 2 * dayMs, out[0].expiresMs)          // max expires
    }
}
