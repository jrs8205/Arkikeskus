package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Sähkösivun hintalista: rivit, laatat, tuntihuomautus ja Siirry nykyhetkeen -painikkeen suunta. */
class ElectricityPriceListTest {

    private val quarterMs = ElectricityHourly.QUARTER_MS
    private val hourMs = ElectricityHourly.HOUR_MS

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()
    private fun q(iso: String, snt: Double): ElectricityData.Quarter = ElectricityData.quarter(ms(iso), snt)

    /** Päivän [date] kaikki vartit Suomen ajan keskiyöstä seuraavaan keskiyöhön (kellonsiirtopäivänä 92 tai 100). */
    private fun fullDay(date: String): List<ElectricityData.Quarter> {
        val day = LocalDate.parse(date)
        val start = day.atStartOfDay(ElectricityTime.HELSINKI).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(ElectricityTime.HELSINKI).toInstant().toEpochMilli()
        return (start until end step quarterMs).mapIndexed { i, t -> ElectricityData.quarter(t, 5.0 + i % 7) }
    }

    @Test
    fun `vartit saavat aikavalin, naytetyn hinnan ja nyt-merkinnan`() {
        val rows = electricityPriceRows(
            listOf(q("2026-11-17T06:30:00Z", 10.0), q("2026-11-17T06:45:00Z", -1.0)),
            quarterMs, vat = true, nowMs = ms("2026-11-17T06:31:00Z"),
        )
        assertEquals(listOf("08.30–08.45", "08.45–09.00"), rows.map { it.rangeText })
        assertEquals(12.55, rows[0].displayPrice, 1e-9)
        assertEquals(-1.0, rows[1].displayPrice, 1e-9)
        assertEquals(10.0, rows[0].rawPrice, 1e-9)
        assertEquals(listOf(true, false), rows.map { it.isNow })
        assertEquals(ms("2026-11-17T06:30:00Z"), rows[0].timestamp)
    }

    @Test
    fun `paivan viimeinen vartti paattyy keskiyohon`() {
        val rows = electricityPriceRows(listOf(q("2026-11-17T21:45:00Z", 5.0)), quarterMs, vat = false, nowMs = null)
        assertEquals("23.45–00.00", rows.single().rangeText)
    }

    @Test
    fun `tuntitilassa rivi kattaa koko tunnin`() {
        val rows = electricityPriceRows(listOf(q("2026-11-17T06:00:00Z", 5.0)), hourMs, vat = false, nowMs = null)
        assertEquals("08.00–09.00", rows.single().rangeText)
    }

    @Test
    fun `huomenna ilman nykyhetkea mikaan rivi ei ole nyt`() {
        val rows = electricityPriceRows(
            listOf(q("2026-11-18T06:30:00Z", 5.0), q("2026-11-18T06:45:00Z", 6.0)),
            quarterMs, vat = false, nowMs = null,
        )
        assertTrue(rows.none { it.isNow })
    }

    @Test
    fun `talviaikaan siirryttaessa vain toistuvan tunnin jalkimmainen kierros saa tahden`() {
        val rows = electricityPriceRows(
            listOf(q("2026-10-25T00:00:00Z", 5.0), q("2026-10-25T01:00:00Z", 6.0)),
            quarterMs, vat = false, nowMs = null,
        )
        assertEquals(listOf("03.00–03.15", "03.00*–03.15"), rows.map { it.rangeText })
    }

    @Test
    fun `syksyn kellonsiirtopaiva tuntitilassa 25 rivia ja tahti vain toistuvan tunnin jalkimmaisella kierroksella`() {
        val day = ElectricityHourly.daySlots(fullDay("2026-10-25"), hourly = true, vat = false)
        assertEquals(hourMs, day.slotMs)
        val rows = electricityPriceRows(day.slots, day.slotMs, vat = false, nowMs = null)
        assertEquals(25, rows.size)
        assertEquals(listOf(4), rows.indices.filter { rows[it].rangeText.contains('*') })
        assertEquals(
            listOf("02.00–03.00", "03.00–04.00", "03.00*–04.00", "04.00–05.00"),
            rows.subList(2, 6).map { it.rangeText },
        )
        assertEquals("00.00–01.00", rows.first().rangeText)
        assertEquals("23.00–00.00", rows.last().rangeText)
    }

    @Test
    fun `kevaan kellonsiirtopaiva tuntitilassa 23 rivia ja siirtotunti paattyy alkunsa ajassa`() {
        val day = ElectricityHourly.daySlots(fullDay("2026-03-29"), hourly = true, vat = false)
        assertEquals(hourMs, day.slotMs)
        val rows = electricityPriceRows(day.slots, day.slotMs, vat = false, nowMs = null)
        assertEquals(23, rows.size)
        assertTrue(rows.none { it.rangeText.contains('*') })
        assertEquals(listOf("01.00–02.00", "02.00–03.00", "04.00–05.00"), rows.subList(1, 4).map { it.rangeText })
        assertEquals("23.00–00.00", rows.last().rangeText)
    }

    @Test
    fun `syksyn kellonsiirtoyon vartit paattyvat alkunsa ajassa`() {
        val day = ElectricityHourly.daySlots(fullDay("2026-10-25"), hourly = false, vat = false)
        val rows = electricityPriceRows(day.slots, day.slotMs, vat = false, nowMs = null)
        assertEquals(100, rows.size)
        // Indeksit 14..20 = 00.30Z..02.00Z: toistuvan tunnin ensimmäisen kierroksen loppu ja jälkimmäinen kierros.
        assertEquals(
            listOf("03.30–03.45", "03.45–04.00", "03.00*–03.15", "03.15*–03.30", "03.30*–03.45", "03.45*–04.00", "04.00–04.15"),
            rows.subList(14, 21).map { it.rangeText },
        )
        assertEquals((16..19).toList(), rows.indices.filter { rows[it].rangeText.contains('*') })
    }

    @Test
    fun `kevaan kellonsiirtoyon vartit paattyvat alkunsa ajassa`() {
        val day = ElectricityHourly.daySlots(fullDay("2026-03-29"), hourly = false, vat = false)
        val rows = electricityPriceRows(day.slots, day.slotMs, vat = false, nowMs = null)
        assertEquals(92, rows.size)
        // Indeksit 10..12 = 00.30Z..01.00Z: kello siirtyy 03.00 → 04.00.
        assertEquals(listOf("02.30–02.45", "02.45–03.00", "04.00–04.15"), rows.subList(10, 13).map { it.rangeText })
        assertTrue(rows.none { it.rangeText.contains('*') })
    }

    @Test
    fun `paivan keskihinta lasketaan naytetyista varttihinnoista`() {
        val avg = electricityDayAverage(listOf(q("2026-11-17T06:30:00Z", 10.0), q("2026-11-17T06:45:00Z", -2.0)), vat = true)
        assertEquals(5.275, avg, 1e-9)
    }

    @Test
    fun `tanaan kolme laattaa, huomenna ilman keskihintaa`() {
        assertEquals(listOf(SummaryTile.CHEAPEST, SummaryTile.AVERAGE, SummaryTile.MOST_EXPENSIVE), electricitySummaryTiles(0))
        assertEquals(listOf(SummaryTile.CHEAPEST, SummaryTile.MOST_EXPENSIVE), electricitySummaryTiles(1))
    }

    @Test
    fun `tuntihuomautus kun tunti valittu mutta paiva naytetaan vartteina`() {
        val quarters = listOf(q("2026-11-17T06:30:00Z", 5.0))
        val hours = listOf(q("2026-11-17T06:00:00Z", 5.0))
        assertTrue(electricityHourlyFallback(true, ElectricityHourly.DaySlots(quarters, quarterMs)))
        assertFalse(electricityHourlyFallback(true, ElectricityHourly.DaySlots(hours, hourMs)))
        assertFalse(electricityHourlyFallback(false, ElectricityHourly.DaySlots(quarters, quarterMs)))
        assertFalse(electricityHourlyFallback(true, ElectricityHourly.DaySlots(emptyList(), quarterMs)))
    }

    @Test
    fun `paivalla on naytettavia hintoja vasta kun huominen ulottuu iltaan`() {
        val night = listOf(q("2026-11-17T22:00:00Z", 5.0))       // 18.11. klo 00.00 (CET-ylivuoto)
        val evening = night + q("2026-11-18T18:00:00Z", 6.0)      // 18.11. klo 20.00
        assertFalse(electricityDayHasPrices(dayOffset = 1, quarters = night))
        assertTrue(electricityDayHasPrices(dayOffset = 1, quarters = evening))
        assertFalse(electricityDayHasPrices(dayOffset = 1, quarters = null))
        assertFalse(electricityDayHasPrices(dayOffset = 0, quarters = emptyList()))
        assertTrue(electricityDayHasPrices(dayOffset = 0, quarters = night))
    }

    @Test
    fun `nyt-merkinta paivittyy seuraavan jakson alussa`() {
        assertEquals(14 * 60_000L, nextSlotBoundaryDelayMs(ms("2026-11-17T06:31:00Z"), quarterMs))
        assertEquals(15 * 60_000L, nextSlotBoundaryDelayMs(ms("2026-11-17T06:30:00Z"), quarterMs))
        assertEquals(29 * 60_000L, nextSlotBoundaryDelayMs(ms("2026-11-17T06:31:00Z"), hourMs))
    }

    @Test
    fun `siirry nykyhetkeen ei nay kun kuluva rivi puuttuu tai on nakyvissa`() {
        assertNull(jumpToNowDirection(nowIndex = -1, firstVisible = 0, lastVisible = 5))
        assertNull(jumpToNowDirection(nowIndex = 3, firstVisible = 0, lastVisible = 5))
        assertNull(jumpToNowDirection(nowIndex = 2, firstVisible = 2, lastVisible = 8))
        assertNull(jumpToNowDirection(nowIndex = 8, firstVisible = 2, lastVisible = 8))
    }

    @Test
    fun `siirry nykyhetkeen osoittaa kuluvan rivin suuntaan`() {
        assertEquals(JumpDirection.UP, jumpToNowDirection(nowIndex = 1, firstVisible = 2, lastVisible = 8))
        assertEquals(JumpDirection.DOWN, jumpToNowDirection(nowIndex = 9, firstVisible = 2, lastVisible = 8))
    }
}
