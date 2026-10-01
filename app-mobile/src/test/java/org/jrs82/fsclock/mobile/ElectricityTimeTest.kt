package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ElectricityTimeTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // Talviaikaan 25.10.2026 klo 04.00 → 03.00: tunti 03 kahdesti.
    private val autumn0300Summer =
        LocalDateTime.of(2026, 10, 25, 3, 0).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
    private val autumn0300Winter =
        LocalDateTime.of(2026, 10, 25, 3, 0).atZone(zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
    private val quarter = 15L * 60_000L

    @Test fun dayHours_ordinaryDay_has24Rows() {
        val rows = ElectricityTime.dayHours(ms(2026, 10, 1, 7, 16))
        assertEquals((0..23).toList(), rows.map { it.hour })
        assertEquals(ms(2026, 10, 1, 0, 0), rows.first().startMs)
        assertTrue(rows.none { it.repeated })
    }

    @Test fun dayHours_autumnTransition_has25RowsAndMarksSecond03() {
        val rows = ElectricityTime.dayHours(ms(2026, 10, 25, 12, 0))
        assertEquals(25, rows.size)
        assertEquals(listOf(0, 1, 2, 3, 3, 4), rows.take(6).map { it.hour })
        assertEquals(autumn0300Summer, rows[3].startMs)
        assertFalse(rows[3].repeated)
        assertEquals(autumn0300Winter, rows[4].startMs)
        assertTrue(rows[4].repeated)
        assertEquals(1, rows.count { it.repeated })
    }

    @Test fun dayHours_springTransition_has23RowsWithoutHour3() {
        val rows = ElectricityTime.dayHours(ms(2027, 3, 28, 12, 0))
        assertEquals(23, rows.size)
        assertFalse(rows.any { it.hour == 3 })
    }

    @Test fun hourKey_separatesTheTwo03Hours() {
        assertTrue(ElectricityTime.hourKey(autumn0300Summer) != ElectricityTime.hourKey(autumn0300Winter))
        assertEquals(
            ElectricityTime.hourKey(autumn0300Summer),
            ElectricityTime.hourKey(autumn0300Summer + 3 * quarter),
        )
    }

    @Test fun quarterBounds_ordinaryQuarter() {
        val (start, end) = ElectricityTime.quarterBounds(ms(2026, 10, 1, 7, 16))
        assertEquals(7 to 15, start.hour to start.minute)
        assertEquals(7 to 30, end.hour to end.minute)
    }

    @Test fun quarterBounds_lastSummerQuarterEndsAt0300WinterTime() {
        val (start, end) = ElectricityTime.quarterBounds(autumn0300Summer + 3 * quarter + 60_000L)
        assertEquals(3 to 45, start.hour to start.minute)
        assertEquals(3 to 0, end.hour to end.minute)
    }

    @Test fun quarterBounds_springQuarterEndsAt0400() {
        val (start, end) = ElectricityTime.quarterBounds(ms(2027, 3, 28, 2, 50))
        assertEquals(2 to 45, start.hour to start.minute)
        assertEquals(4 to 0, end.hour to end.minute)
    }

    @Test fun dstSuffix_onlyOnRepeatedHour() {
        assertEquals(" (kesäaikaa)", ElectricityTime.dstSuffix(autumn0300Summer + quarter))
        assertEquals(" (talviaikaa)", ElectricityTime.dstSuffix(autumn0300Winter + quarter))
        assertEquals("", ElectricityTime.dstSuffix(ms(2026, 10, 25, 2, 45)))
        assertEquals("", ElectricityTime.dstSuffix(ms(2026, 10, 25, 4, 0)))
        assertEquals("", ElectricityTime.dstSuffix(ms(2026, 10, 1, 3, 15)))
    }
}
