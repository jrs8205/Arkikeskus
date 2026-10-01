package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ElectricityAveragesTest {
    private val hour = 3600_000L
    private val monthEnd = 1_790_802_000_000L // 1.10.2026 klo 00.00 Suomen aikaa

    @Test fun endedMonth_cacheSavedAfterMonthEnd_isFinal() {
        assertTrue(ElectricityAverages.cacheUsable(true, monthEnd + hour, monthEnd, monthEnd + 400 * 24 * hour))
        assertTrue(ElectricityAverages.cacheUsable(true, monthEnd, monthEnd, monthEnd + hour))
    }

    @Test fun endedMonth_cacheSavedMidMonth_isNotFinal() {
        // Kesken kuun tallennettu keskiarvo kattaa vain alkukuun → ei saa jäädä pysyväksi kuun päätyttyä.
        assertFalse(ElectricityAverages.cacheUsable(true, monthEnd - 10 * 24 * hour, monthEnd, monthEnd + hour))
        assertFalse(ElectricityAverages.cacheUsable(true, monthEnd - 1, monthEnd, monthEnd + 5 * hour))
    }

    @Test fun currentMonth_cacheIsUsableFor12Hours() {
        val saved = monthEnd - 20 * 24 * hour
        assertTrue(ElectricityAverages.cacheUsable(false, saved, monthEnd, saved + 11 * hour))
        assertFalse(ElectricityAverages.cacheUsable(false, saved, monthEnd, saved + 12 * hour))
    }

    @Test fun cacheSavedInTheFuture_isNotUsable() {
        // Kelloa siirretty taaksepäin tallennuksen jälkeen.
        assertFalse(ElectricityAverages.cacheUsable(true, monthEnd + 2 * hour, monthEnd, monthEnd + hour))
        assertFalse(ElectricityAverages.cacheUsable(false, monthEnd - hour, monthEnd, monthEnd - 2 * hour))
    }

    private val zone = ZoneId.of("Europe/Helsinki")

    /** Tunnin neljä varttia samalla hinnalla; kentät kuten ElectricityClient ne täyttää. */
    private fun hourOf(startMs: Long, snt: Double): List<ElectricityData.Quarter> = (0..3).map { i ->
        val z = Instant.ofEpochMilli(startMs + i * 15L * 60_000L).atZone(zone)
        ElectricityData.Quarter().apply {
            timestamp = z.toInstant().toEpochMilli()
            year = z.year; month = z.monthValue; dayOfMonth = z.dayOfMonth
            this.hour = z.hour; minute = z.minute
            sntPerKwh = snt
        }
    }

    @Test fun hourlyAverage_countsRepeatedAutumnHourAsTwoHours() {
        // 25.10.2026: tunti 03 kesäaikaa (1 c) ja 03 talviaikaa (3 c) + tunti 04 (5 c) → kolme tuntia, ka 3.
        val summer03 = LocalDateTime.of(2026, 10, 25, 3, 0).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val quarters = hourOf(summer03, 1.0) + hourOf(summer03 + hour, 3.0) + hourOf(summer03 + 2 * hour, 5.0)
        assertEquals(listOf(3, 3, 4), listOf(quarters[0].hour, quarters[4].hour, quarters[8].hour))
        val avg = ElectricityAverages.hourlyAverage(quarters, 2026, 10)
        assertEquals(3, avg.sampleCount)
        assertEquals(3.0, avg.avgSntPerKwh, 1e-9)
    }

    @Test fun hourlyAverage_weightsHoursNotQuarters_andFiltersMonth() {
        val oct1 = LocalDateTime.of(2026, 10, 1, 0, 0).atZone(zone).toInstant().toEpochMilli()
        // Tunti jossa vain yksi (tunti)hinta + tunti jossa neljä varttia + syyskuun tunti, joka ei kuulu mukaan.
        val quarters = hourOf(oct1, 2.0).take(1) + hourOf(oct1 + hour, 6.0) + hourOf(oct1 - hour, 100.0)
        val avg = ElectricityAverages.hourlyAverage(quarters, 2026, 10)
        assertEquals(2, avg.sampleCount)
        assertEquals(4.0, avg.avgSntPerKwh, 1e-9)
        assertNull(ElectricityAverages.hourlyAverage(quarters, 2026, 11))
    }

    private fun month(m: Int, avg: Double, hours: Int) = ElectricityAverages.MonthAverage(2025, m, avg, hours)

    @Test fun yearFromMonths_weightsByHours() {
        val months = Array(12) { month(it + 1, 2.0, 100) }
        months[11] = month(12, 8.0, 300)
        val year = ElectricityAverages.yearFromMonths(2025, months)
        assertEquals(1400, year.sampleCount)
        assertEquals((11 * 2.0 * 100 + 8.0 * 300) / 1400, year.avgSntPerKwh, 1e-9)
    }

    @Test fun yearFromMonths_nullWhenAnyMonthMissing() {
        // Puuttuva joulukuu (esim. uusintahaku epäonnistui) → ei vuosiarvoa, jotta sitä ei tallenneta lopullisena.
        val months = arrayOfNulls<ElectricityAverages.MonthAverage>(12)
        for (i in 0 until 11) months[i] = month(i + 1, 2.0, 100)
        assertNull(ElectricityAverages.yearFromMonths(2025, months))
        months[11] = month(12, 8.0, 300)
        months[5] = null
        assertNull(ElectricityAverages.yearFromMonths(2025, months))
    }
}
