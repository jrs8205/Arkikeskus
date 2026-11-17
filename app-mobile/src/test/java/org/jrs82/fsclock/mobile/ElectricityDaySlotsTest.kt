package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tuntitila koskee vain päivää, jonka jokaisella tunnilla on kaikki neljä varttia. Vajaa päivä
 * näytetään vartteina, jotta vajaan tunnin tunnetut vartit eivät katoa näkyvistä ja laskennasta
 * (halvin, kallein, hintajana, "sähkö on halpaa" -huomio).
 */
class ElectricityDaySlotsTest {

    private val zone = ZoneId.of("Europe/Helsinki")
    private val quarterMs = 15L * 60_000L

    /** Päivän kaikki vartit keskiyöstä keskiyöhön; [price] saa Suomen ajan tunnin ja vartin järjestysnumeron. */
    private fun day(date: LocalDate, price: (hour: Int, index: Int) -> Double = { _, _ -> 1.0 }): List<ElectricityData.Quarter> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val out = ArrayList<ElectricityData.Quarter>()
        var ms = start
        while (ms < end) {
            val q = ElectricityData.quarter(ms, 0.0)
            q.sntPerKwh = price(q.hour, q.minute / 15)
            out.add(q)
            ms += quarterMs
        }
        return out
    }

    private val normalDay = LocalDate.of(2026, 11, 16)
    private fun at(hour: Int, minute: Int) = normalDay.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Katselmoinnin aineisto: muut vartit 1 snt, tunnin 18 kolme varttia 40 snt, klo 18.45 puuttuu. */
    private fun dayWithIncompleteExpensiveHour() =
        day(normalDay) { hour, _ -> if (hour == 18) 40.0 else 1.0 }.filterNot { it.hour == 18 && it.minute == 45 }

    // ---- daySlots ----

    @Test
    fun `taysi paiva naytetaan tunteina kun asetus on paalla`() {
        val slots = ElectricityHourly.daySlots(day(normalDay), hourly = true, vat = false)
        assertTrue(slots.hourly)
        assertEquals(ElectricityHourly.HOUR_MS, slots.slotMs)
        assertEquals((0..23).toList(), slots.slots.map { it.hour })
    }

    @Test
    fun `asetus pois nayttaa vartit sellaisinaan`() {
        val quarters = day(normalDay)
        val slots = ElectricityHourly.daySlots(quarters, hourly = false, vat = false)
        assertFalse(slots.hourly)
        assertEquals(ElectricityHourly.QUARTER_MS, slots.slotMs)
        assertSame(quarters, slots.slots)
    }

    @Test
    fun `paiva jolta puuttuu yksi vartti naytetaan vartteina`() {
        val quarters = dayWithIncompleteExpensiveHour()
        val slots = ElectricityHourly.daySlots(quarters, hourly = true, vat = false)
        assertFalse(slots.hourly)
        assertSame(quarters, slots.slots)
        // Vajaan tunnin tunnetut kalliit vartit ovat edelleen mukana.
        assertEquals(40.0, slots.slots.maxOf { it.sntPerKwh }, 0.0)
    }

    @Test
    fun `paiva jolta puuttuu kokonainen tunti naytetaan vartteina`() {
        val quarters = day(normalDay).filterNot { it.hour == 7 }
        assertFalse(ElectricityHourly.daySlots(quarters, hourly = true, vat = false).hourly)
    }

    @Test
    fun `tyhja paiva ei tuota jaksoja`() {
        assertTrue(ElectricityHourly.daySlots(emptyList(), hourly = true, vat = false).slots.isEmpty())
    }

    @Test
    fun `talviaikaan siirtymisen 25 tunnin paiva naytetaan tunteina`() {
        val slots = ElectricityHourly.daySlots(day(LocalDate.of(2026, 10, 25)), hourly = true, vat = false)
        assertTrue(slots.hourly)
        assertEquals(25, slots.slots.size)
    }

    @Test
    fun `kesaaikaan siirtymisen 23 tunnin paiva naytetaan tunteina`() {
        val slots = ElectricityHourly.daySlots(day(LocalDate.of(2027, 3, 28)), hourly = true, vat = false)
        assertTrue(slots.hourly)
        assertEquals(23, slots.slots.size)
    }

    // ---- halvan sähkön huomio ----

    private val allDay = MobileThemeController.CHEAP_MODE_ALL_DAY
    private val restOfDay = MobileThemeController.CHEAP_MODE_REMAINING_DAY
    private val nowOnly = MobileThemeController.CHEAP_MODE_CURRENT

    private fun cheap(today: List<ElectricityData.Quarter>, mode: String, hourly: Boolean, nowMs: Long): Boolean =
        allBelowThreshold(cheapNoticeSlots(today, mode, hourly, vat = false, nowMs = nowMs), threshold = 5.0, vat = false)

    @Test
    fun `tuntitila ei vaita paivaa halvaksi kun vajaan tunnin tunnetut vartit ovat kalliita`() {
        val today = dayWithIncompleteExpensiveHour()
        assertFalse(cheap(today, allDay, hourly = true, nowMs = at(10, 0)))
        // Sama tulos kuin varttitilassa: asetus ei saa muuttaa väitettä.
        assertFalse(cheap(today, allDay, hourly = false, nowMs = at(10, 0)))
    }

    @Test
    fun `tuntitila ei vaita loppupaivaa halvaksi kun vajaa kallis tunti on viela edessa`() {
        val today = dayWithIncompleteExpensiveHour()
        assertFalse(cheap(today, restOfDay, hourly = true, nowMs = at(10, 0)))
    }

    @Test
    fun `loppupaiva on halpa kun kallis tunti on jo ohi`() {
        val today = dayWithIncompleteExpensiveHour()
        assertTrue(cheap(today, restOfDay, hourly = true, nowMs = at(19, 30)))
        assertTrue(cheap(today, restOfDay, hourly = false, nowMs = at(19, 30)))
    }

    @Test
    fun `taysi halpa paiva on halpa tuntitilassa`() {
        val today = day(normalDay)
        assertTrue(cheap(today, allDay, hourly = true, nowMs = at(10, 0)))
        assertEquals(24, cheapNoticeSlots(today, allDay, true, false, at(10, 0)).size)
    }

    @Test
    fun `tuntitilassa tunnin keskihinta ratkaisee taydella paivalla`() {
        // Tunti 18: vartit 2, 2, 2, 12 → keskiarvo 4,5 alittaa rajan 5, vaikka yksi vartti ylittää sen.
        val today = day(normalDay) { hour, index -> if (hour == 18 && index == 3) 12.0 else if (hour == 18) 2.0 else 1.0 }
        assertTrue(cheap(today, allDay, hourly = true, nowMs = at(10, 0)))
        assertFalse(cheap(today, allDay, hourly = false, nowMs = at(10, 0)))
    }

    @Test
    fun `nyt-tila tarkastelee kuluvaa jaksoa`() {
        val today = day(normalDay) { hour, _ -> if (hour == 18) 40.0 else 1.0 }
        assertEquals(1, cheapNoticeSlots(today, nowOnly, true, false, at(10, 20)).size)
        assertTrue(cheap(today, nowOnly, hourly = true, nowMs = at(10, 20)))
        assertFalse(cheap(today, nowOnly, hourly = true, nowMs = at(18, 20)))
    }

    @Test
    fun `ilman hintoja huomiota ei nayteta`() {
        assertFalse(cheap(emptyList(), allDay, hourly = true, nowMs = at(10, 0)))
    }
}
