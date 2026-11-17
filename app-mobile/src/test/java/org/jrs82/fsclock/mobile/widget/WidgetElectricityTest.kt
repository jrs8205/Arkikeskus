package org.jrs82.fsclock.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetElectricityTest {
    private val quarterMs = 15L * 60_000L
    private val q0 = 1_700_000_100_000L - (1_700_000_100_000L % quarterMs)
    private val list = listOf(
        WidgetElectricity.QuarterPrice(q0, 1.5),
        WidgetElectricity.QuarterPrice(q0 + quarterMs, 2.25),
        WidgetElectricity.QuarterPrice(q0 + 2 * quarterMs, -0.5),
    )

    @Test fun encodeDecode_roundTrip() {
        val back = WidgetElectricity.decode(WidgetElectricity.encode(list))
        assertEquals(3, back.size)
        assertEquals(q0, back[0].timestamp)
        assertEquals(1.5, back[0].snt, 0.0)
        assertEquals(q0 + 2 * quarterMs, back[2].timestamp)
        assertEquals(-0.5, back[2].snt, 0.0)
    }

    @Test fun decode_invalidOrEmpty_isEmptyList() {
        assertTrue(WidgetElectricity.decode("").isEmpty())
        assertTrue(WidgetElectricity.decode("garbage").isEmpty())
        assertTrue(WidgetElectricity.decode("[]").isEmpty())
    }

    @Test fun currentPrice_picksQuarterCoveringNow() {
        assertEquals(2.25, WidgetElectricity.currentPrice(list, q0 + quarterMs + 10 * 60_000L), 0.0)
    }

    @Test fun currentPrice_atExactQuarterStart_usesNewQuarter() {
        assertEquals(2.25, WidgetElectricity.currentPrice(list, q0 + quarterMs), 0.0)
        assertEquals(-0.5, WidgetElectricity.currentPrice(list, q0 + 2 * quarterMs), 0.0)
    }

    @Test fun currentPrice_lastMillisecondOfQuarter_stillOldQuarter() {
        assertEquals(1.5, WidgetElectricity.currentPrice(list, q0 + quarterMs - 1), 0.0)
    }

    @Test fun currentPrice_outsideList_isNaN() {
        assertTrue(WidgetElectricity.currentPrice(list, q0 - 1).isNaN())
        assertTrue(WidgetElectricity.currentPrice(list, q0 + 3 * quarterMs).isNaN())
        assertTrue(WidgetElectricity.currentPrice(emptyList(), q0).isNaN())
    }

    // 1.10.2026 klo 07.16 Suomen aikaa (UTC+3); päivä alkaa 30.9. klo 21.00 UTC.
    private val now = 1_790_828_160_000L
    private val dayStart = 1_790_802_000_000L
    private val dayMs = 24L * 60 * 60_000L

    @Test fun dayStart_isHelsinkiMidnight() {
        assertEquals(dayStart, WidgetElectricity.dayStart(now))
        assertEquals(dayStart, WidgetElectricity.dayStart(dayStart))
        assertEquals(dayStart - dayMs, WidgetElectricity.dayStart(dayStart - 1))
    }

    @Test fun dayRange_usesOnlyTodaysQuarters() {
        val quarters = listOf(
            WidgetElectricity.QuarterPrice(dayStart - quarterMs, 30.0),   // eilen 23.45
            WidgetElectricity.QuarterPrice(dayStart, 1.053),              // tänään 00.00
            WidgetElectricity.QuarterPrice(dayStart + 30 * quarterMs, 6.83),
            WidgetElectricity.QuarterPrice(dayStart + dayMs - quarterMs, 3.0), // tänään 23.45
            WidgetElectricity.QuarterPrice(dayStart + dayMs, -5.0),       // huomenna 00.00
        )
        val range = WidgetElectricity.dayRange(quarters, now)!!
        assertEquals(1.053, range.first, 0.0)
        assertEquals(6.83, range.second, 0.0)
    }

    @Test fun dayRange_nullWhenNoQuartersForToday() {
        assertNull(WidgetElectricity.dayRange(emptyList(), now))
        assertNull(
            WidgetElectricity.dayRange(listOf(WidgetElectricity.QuarterPrice(dayStart + dayMs, 2.0)), now),
        )
    }

    private fun quarters(fromMs: Long, toMs: Long, price: (Long) -> Double) =
        generateSequence(fromMs) { it + quarterMs }.takeWhile { it < toMs }
            .map { WidgetElectricity.QuarterPrice(it, price(it)) }.toList()

    @Test fun dayRange_nullForListStartingMidDay() {
        // Sovelluspäivitystä edeltävä cache alkoi tuntia ennen tallennusta: aamun ääriarvot puuttuvat,
        // joten loppupäivän väliä 2–4 ei saa esittää koko päivän asteikkona.
        val old = quarters(now - 4 * quarterMs, dayStart + dayMs) { if (it < now + 8 * quarterMs) 2.0 else 4.0 }
        assertNull(WidgetElectricity.dayRange(old, now))
    }

    @Test fun dayRange_nullWhenOnlyEarlyMorningOverflow() {
        // Keskiyön jälkeen ennen workerin ajoa cachessa voi olla päivästä vain 00.00–00.45.
        val overflow = quarters(dayStart, dayStart + 4 * quarterMs) { 1.0 }
        assertNull(WidgetElectricity.dayRange(overflow, dayStart + quarterMs))
    }

    @Test fun dayRange_nullWhenEndOfDayMissing() {
        val partial = quarters(dayStart, dayStart + dayMs - quarterMs) { 1.0 }
        assertNull(WidgetElectricity.dayRange(partial, now))
    }

    @Test fun dayRange_fullDay_alsoOnClockChangeDays() {
        val zone = java.time.ZoneId.of("Europe/Helsinki")
        fun start(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
        // 25.10.2026 = 25 h (100 varttia), 28.3.2027 = 23 h (92 varttia).
        for ((from, to, count) in listOf(
            Triple(start(2026, 10, 25), start(2026, 10, 26), 100),
            Triple(start(2027, 3, 28), start(2027, 3, 29), 92),
            Triple(dayStart, dayStart + dayMs, 96),
        )) {
            val day = quarters(from, to) { (it - from) / quarterMs.toDouble() }
            assertEquals(count, day.size)
            val range = WidgetElectricity.dayRange(day, from + 12 * 60 * 60_000L)!!
            assertEquals(0.0, range.first, 0.0)
            assertEquals((count - 1).toDouble(), range.second, 0.0)
        }
    }

    @Test fun nextQuarterBoundary_roundsUpToNextQuarter() {
        assertEquals(q0 + quarterMs, WidgetElectricity.nextQuarterBoundary(q0 + 1))
        assertEquals(q0 + quarterMs, WidgetElectricity.nextQuarterBoundary(q0 + quarterMs - 1))
        assertEquals(q0 + quarterMs, WidgetElectricity.nextQuarterBoundary(q0 + 7 * 60_000L))
    }

    @Test fun nextQuarterBoundary_onBoundary_isFollowingQuarter() {
        assertEquals(q0 + quarterMs, WidgetElectricity.nextQuarterBoundary(q0))
    }

    // ---- tunnin keskihinta (asetus "Näytä tunnin keskihinta") ----

    private val hourMs = 4 * quarterMs
    private val h0 = q0 - (q0 % hourMs)

    @Test fun todaySlots_withVat_hourPriceIsAverageOfShownQuarterPrices() {
        // ALV vain positiivisiin vartteihin: tunnin 03 vartit -1, -1, 3, 3 → (-1 - 1 + 3,765 + 3,765) / 4.
        val prices = listOf(-1.0, -1.0, 3.0, 3.0)
        val h3 = dayStart + 3 * hourMs
        val day = quarters(dayStart, dayStart + dayMs) {
            if (it >= h3 && it < h3 + hourMs) prices[((it - h3) / quarterMs).toInt()] else 2.0
        }
        val slots = WidgetElectricity.todaySlots(day, now, hourly = true, vat = true)
        val slot = slots.list.single { it.timestamp == h3 }
        val shown = org.jrs82.fsclock.mobile.ElectricityVat.apply(slot.snt, true)
        assertEquals((-2.0 + 2 * 3.0 * 1.255) / 4.0, shown, 1e-9)
    }

    @Test fun currentPrice_withHourSlots_coversWholeHour() {
        val slots = listOf(WidgetElectricity.QuarterPrice(h0, 7.0), WidgetElectricity.QuarterPrice(h0 + hourMs, 9.0))
        assertEquals(7.0, WidgetElectricity.currentPrice(slots, h0 + 40 * 60_000L, hourMs), 0.0)
        assertEquals(9.0, WidgetElectricity.currentPrice(slots, h0 + hourMs, hourMs), 0.0)
        assertTrue(WidgetElectricity.currentPrice(slots, h0 + 2 * hourMs, hourMs).isNaN())
    }

    @Test fun dayRange_withHourSlots_coversFullDay() {
        val day = quarters(dayStart, dayStart + dayMs) { if (it < dayStart + 12 * hourMs) 2.0 else 6.0 }
        val slots = WidgetElectricity.todaySlots(day, now, hourly = true, vat = false)
        assertEquals(24, slots.list.size)
        assertEquals(2.0 to 6.0, WidgetElectricity.dayRange(slots.list, now, slots.slotMs))
    }

    // ---- tuntitila koskee vain täyttä kuluvaa päivää ----

    @Test fun todaySlots_fullDay_givesHourSlotsForTodayOnly() {
        // Lista kattaa kuluvan päivän ja osan huomisesta, kuten widgetin välimuistissa.
        val list = quarters(dayStart, dayStart + dayMs + 6 * hourMs) { if (it < dayStart + dayMs) 2.0 else 9.0 }
        val slots = WidgetElectricity.todaySlots(list, now, hourly = true, vat = false)
        assertEquals(hourMs, slots.slotMs)
        assertEquals(24, slots.list.size)
        assertEquals(2.0 to 2.0, WidgetElectricity.dayRange(slots.list, now, slots.slotMs))
        assertEquals(2.0, WidgetElectricity.currentPrice(slots.list, now, slots.slotMs), 0.0)
    }

    @Test fun todaySlots_incompleteDay_keepsQuarters() {
        // Kuluvalta päivältä puuttuu yksi vartti: tuntitilaa ei käytetä, eikä tunnettuja vartteja hukata.
        val missing = dayStart + 18 * hourMs + 3 * quarterMs
        val list = quarters(dayStart, dayStart + dayMs) { if (it >= dayStart + 18 * hourMs && it < dayStart + 19 * hourMs) 40.0 else 1.0 }
            .filterNot { it.timestamp == missing }
        val slots = WidgetElectricity.todaySlots(list, now, hourly = true, vat = false)
        assertEquals(quarterMs, slots.slotMs)
        assertEquals(list, slots.list)
        assertEquals(40.0, slots.list.maxOf { it.snt }, 0.0)
    }

    @Test fun todaySlots_settingOff_keepsQuarters() {
        val list = quarters(dayStart, dayStart + dayMs) { 3.0 }
        val slots = WidgetElectricity.todaySlots(list, now, hourly = false, vat = false)
        assertEquals(quarterMs, slots.slotMs)
        assertEquals(list, slots.list)
    }

    @Test fun dayRange_withHourSlots_nullWhenLastHourMissing() {
        val day = quarters(dayStart, dayStart + dayMs - hourMs) { 3.0 }
        val slots = WidgetElectricity.todaySlots(day, now, hourly = true, vat = false)
        assertEquals(quarterMs, slots.slotMs) // vajaa päivä → vartit
        assertNull(WidgetElectricity.dayRange(slots.list, now, slots.slotMs))
    }
}
