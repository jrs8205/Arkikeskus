package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.jrs82.fsclock.WarningDetails
import org.jrs82.fsclock.WeatherWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FmiWarningFilterTest {

    private fun w(type: WeatherWarning.AwarenessType, area: String, on: Long, ex: Long,
                  level: WeatherWarning.Level = WeatherWarning.Level.YELLOW,
                  details: WarningDetails = WarningDetails.EMPTY) =
        WeatherWarning(type.name, "kuvaus", area, on, ex, level, "id-$area-$type", false,
            type, "", "", "", 0L, "FMI", "", details)

    // 2025-06-27 00:00 EEST (Helsinki) = 1750971600000; käytä kiinteitä ms-arvoja
    private val day0 = 1_750_971_600_000L          // pe 27.6. 00:00 EEST
    private val dayMs = 24L * 60 * 60 * 1000
    private val fi = Locale("fi", "FI")

    private fun today() = DayOption(DayKind.TODAY, "pe 27.6.", day0, day0 + dayMs)

    @Test fun fiveDaysFromNow() {
        val days = daysFrom(day0 + 10 * 60 * 60 * 1000, "EEE d.M.", fi) // 27.6. klo 10
        assertEquals(5, days.size)
        assertEquals(DayKind.TODAY, days[0].kind)
        assertEquals(DayKind.TOMORROW, days[1].kind)
        assertEquals(DayKind.DATE, days[2].kind)
        assertEquals("su 29.6.", days[2].dateText)
        assertEquals(day0, days[0].startMs)
        assertEquals(day0 + dayMs, days[0].endMs)
    }

    @Test fun dateLabelFollowsLanguage() {
        val now = day0 + 10 * 60 * 60 * 1000
        assertEquals("Sun 29 Jun", daysFrom(now, "EEE d MMM", Locale.UK)[2].dateText)
        assertEquals("sön 29.6.", daysFrom(now, "EEE d.M.", Locale("sv", "FI"))[2].dateText)
    }

    @Test fun chipAndInlineLabels() {
        val days = daysFrom(day0 + 10 * 60 * 60 * 1000, "EEE d.M.", fi)
        assertEquals(WarningUiText.Res(R.string.common_today), days[0].chipLabel())
        assertEquals(WarningUiText.Res(R.string.common_tomorrow), days[1].chipLabel())
        assertEquals(WarningUiText.Raw("Su 29.6."), days[2].chipLabel())
        assertEquals(WarningUiText.Res(R.string.warning_day_today_inline), days[0].inlineLabel())
        assertEquals(WarningUiText.Res(R.string.warning_day_tomorrow_inline), days[1].inlineLabel())
        assertEquals(WarningUiText.Raw("su 29.6."), days[2].inlineLabel())
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
        val out = warningsFor(all, today(), "Uusimaa")
        assertEquals(2, out.size) // RAIN + HIGH_TEMPERATURE Uudellamaalla
        assertTrue(out.all { it.areaDesc == "Uusimaa" })
    }

    @Test fun kokoSuomiMergesRegionsByType() {
        val all = listOf(
            w(WeatherWarning.AwarenessType.RAIN, "Uusimaa", day0, day0 + dayMs),
            w(WeatherWarning.AwarenessType.RAIN, "Pirkanmaa", day0, day0 + dayMs),
        )
        val out = warningsFor(all, today(), null)
        assertEquals(1, out.size) // yksi Sadevaroitus-kortti
        assertTrue(out[0].areaDesc.contains("Uusimaa") && out[0].areaDesc.contains("Pirkanmaa"))
    }

    @Test fun emptyWhenNoneOnDay() {
        val all = listOf(w(WeatherWarning.AwarenessType.RAIN, "Uusimaa", day0 + 3*dayMs, day0 + 4*dayMs))
        assertEquals(0, warningsFor(all, today(), null).size)
    }

    @Test fun mergeUsesMaxLevelAndUnionTime() {
        val all = listOf(
            w(WeatherWarning.AwarenessType.RAIN, "Uusimaa", day0, day0 + dayMs,
              level = WeatherWarning.Level.YELLOW),
            w(WeatherWarning.AwarenessType.RAIN, "Pirkanmaa", day0 + 2 * 3600_000L, day0 + 2 * dayMs,
              level = WeatherWarning.Level.ORANGE),
        )
        val out = warningsFor(all, today(), null)
        assertEquals(1, out.size)
        assertEquals(WeatherWarning.Level.ORANGE, out[0].level)   // max rank
        assertEquals(day0, out[0].onsetMs)                        // min onset
        assertEquals(day0 + 2 * dayMs, out[0].expiresMs)          // max expires
    }

    @Test fun mergePrefersMostExtremePhysicalValue() {
        val all = listOf(
            w(WeatherWarning.AwarenessType.LOW_TEMPERATURE, "Lappi", day0, day0 + dayMs,
              details = WarningDetails(40, -25.0, "celsius", "a")),
            w(WeatherWarning.AwarenessType.LOW_TEMPERATURE, "Kainuu", day0, day0 + dayMs,
              details = WarningDetails(40, -32.0, "celsius", "b")),
            w(WeatherWarning.AwarenessType.LOW_TEMPERATURE, "Uusimaa", day0, day0 + dayMs,
              details = WarningDetails(40, Double.NaN, "", "c")),
        )
        val out = warningsFor(all, today(), null).single()
        assertEquals(-32.0, out.details.physicalValue, 0.0)
    }
}
