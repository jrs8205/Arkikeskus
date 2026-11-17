package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/** Tunnin keskihinta: varttihinnoista lasketut tuntihinnat asetuksen "Näytä tunnin keskihinta" mukaan. */
class ElectricityHourlyTest {

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun hour(startIso: String, vararg snt: Double): List<ElectricityData.Quarter> {
        val start = ms(startIso)
        return snt.mapIndexed { i, p -> ElectricityData.quarter(start + i * 15 * 60_000L, p) }
    }

    private fun shown(q: ElectricityData.Quarter, vat: Boolean) = ElectricityVat.apply(q.sntPerKwh, vat)

    @Test
    fun `tunnin hinta on neljan vartin keskiarvo`() {
        val slots = ElectricityHourly.hourSlots(hour("2026-11-16T18:00:00+02:00", 2.0, 4.0, 6.0, 8.0), vat = false)
        assertEquals(1, slots.size)
        assertEquals(5.0, slots[0].sntPerKwh, 1e-9)
    }

    @Test
    fun `tunnin alku ja Suomen ajan kentat tulevat tunnin ensimmaisesta vartista`() {
        val slot = ElectricityHourly.hourSlots(hour("2026-11-16T18:00:00+02:00", 1.0, 1.0, 1.0, 1.0), vat = false).single()
        assertEquals(ms("2026-11-16T18:00:00+02:00"), slot.timestamp)
        assertEquals(listOf(16, 18, 0), listOf(slot.dayOfMonth, slot.hour, slot.minute))
    }

    @Test
    fun `verollinen tuntihinta on naytettyjen varttihintojen keskiarvo`() {
        // ALV lisätään vain positiivisiin vartteihin, joten keskiarvo lasketaan näytetyistä hinnoista.
        val quarters = hour("2026-11-16T03:00:00+02:00", -1.0, -1.0, 3.0, 3.0)
        val slot = ElectricityHourly.hourSlots(quarters, vat = true).single()

        val expected = (-1.0 + -1.0 + 3.0 * 1.255 + 3.0 * 1.255) / 4.0
        assertEquals(expected, shown(slot, vat = true), 1e-9)
    }

    @Test
    fun `negatiivinen tuntikeskiarvo naytetaan ilman veroa`() {
        val slot = ElectricityHourly.hourSlots(hour("2026-11-16T03:00:00+02:00", -4.0, -4.0, -4.0, 1.0), vat = true).single()
        val expected = (-12.0 + 1.255) / 4.0
        assertTrue(expected < 0)
        assertEquals(expected, shown(slot, vat = true), 1e-9)
    }

    @Test
    fun `veroton tuntihinta ei muutu verolaskennasta`() {
        val slot = ElectricityHourly.hourSlots(hour("2026-11-16T03:00:00+02:00", -1.0, -1.0, 3.0, 3.0), vat = false).single()
        assertEquals(1.0, shown(slot, vat = false), 1e-9)
    }

    @Test
    fun `vajaa tunti jatetaan pois`() {
        val quarters = hour("2026-11-16T18:00:00+02:00", 1.0, 2.0, 3.0, 4.0) +
            hour("2026-11-16T19:00:00+02:00", 9.0, 9.0, 9.0) // viimeinen vartti puuttuu
        val slots = ElectricityHourly.hourSlots(quarters, vat = false)
        assertEquals(listOf(18), slots.map { it.hour })
    }

    @Test
    fun `tunnit palautetaan aikajarjestyksessa`() {
        val quarters = hour("2026-11-16T19:00:00+02:00", 2.0, 2.0, 2.0, 2.0) +
            hour("2026-11-16T18:00:00+02:00", 1.0, 1.0, 1.0, 1.0)
        assertEquals(listOf(18, 19), ElectricityHourly.hourSlots(quarters, vat = false).map { it.hour })
    }

    @Test
    fun `talviaikaan siirryttaessa toistuva tunti on kaksi eri tuntia`() {
        // 25.10.2026 klo 03 on kahdesti: ensin kesäaikaa (+03:00), sitten talviaikaa (+02:00).
        val quarters = hour("2026-10-25T03:00:00+03:00", 1.0, 1.0, 1.0, 1.0) +
            hour("2026-10-25T03:00:00+02:00", 5.0, 5.0, 5.0, 5.0)
        val slots = ElectricityHourly.hourSlots(quarters, vat = false)
        assertEquals(2, slots.size)
        assertEquals(listOf(3, 3), slots.map { it.hour })
        assertEquals(listOf(1.0, 5.0), slots.map { it.sntPerKwh })
    }

    @Test
    fun `kokonainen paiva antaa 24 tuntia`() {
        val day = (0..23).flatMap { h -> hour(String.format("2026-11-16T%02d:00:00+02:00", h), h.toDouble(), h.toDouble(), h.toDouble(), h.toDouble()) }
        val slots = ElectricityHourly.hourSlots(day, vat = true)
        assertEquals(24, slots.size)
        assertEquals((0..23).toList(), slots.map { it.hour })
    }

    @Test
    fun `tuntihintojen keskiarvo on sama kuin naytettyjen varttihintojen keskiarvo`() {
        val day = (0..23).flatMap { h -> hour(String.format("2026-11-16T%02d:00:00+02:00", h), h - 3.0, h - 2.5, h * 1.5, h + 0.25) }
        val byQuarter = day.map { shown(it, true) }.average()
        val byHour = ElectricityHourly.hourSlots(day, vat = true).map { shown(it, true) }.average()
        assertEquals(byQuarter, byHour, 1e-9)
    }

    @Test
    fun `asetus pois palauttaa vartit sellaisinaan`() {
        val quarters = hour("2026-11-16T18:00:00+02:00", 1.0, 2.0, 3.0, 4.0)
        assertSame(quarters, ElectricityHourly.daySlots(quarters, hourly = false, vat = true).slots)
    }

    @Test
    fun `kuluva jakso loytyy jakson pituuden mukaan`() {
        val quarters = hour("2026-11-16T18:00:00+02:00", 1.0, 2.0, 3.0, 4.0)
        val now = ms("2026-11-16T18:40:00+02:00")

        val quarter = ElectricityHourly.current(quarters, now, ElectricityHourly.slotMs(hourly = false))
        assertEquals(30, quarter!!.minute)

        val hourSlots = ElectricityHourly.hourSlots(quarters, vat = false)
        val hourSlot = ElectricityHourly.current(hourSlots, now, ElectricityHourly.slotMs(hourly = true))
        assertEquals(2.5, hourSlot!!.sntPerKwh, 1e-9)

        assertNull(ElectricityHourly.current(hourSlots, ms("2026-11-16T19:00:00+02:00"), ElectricityHourly.slotMs(true)))
    }

    @Test
    fun `asetuksen oletus on varttihinnat`() {
        val prefs = FakeSharedPreferences()
        assertFalse(ElectricityHourly.enabled(prefs))
        prefs.edit().putBoolean(ElectricityHourly.KEY, true).apply()
        assertTrue(ElectricityHourly.enabled(prefs))
    }

    @Test
    fun `veron purku on veron lisayksen kaanteistoiminto`() {
        for (v in listOf(-3.2, 0.0, 0.004, 7.5)) {
            assertEquals(v, ElectricityVat.apply(ElectricityVat.unapply(v, true), true), 1e-12)
            assertEquals(v, ElectricityVat.unapply(v, false), 0.0)
        }
    }
}
