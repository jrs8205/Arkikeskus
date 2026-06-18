package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.db.WorkoutEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Lenkkien viikko-/kuukausiaggregointi. Kiinteä vyöhyke → tulos riippumaton testikoneen
 *  aikavyöhykkeestä. Päivämäärät LocalDatesta, jotta ISO-viikkorajat ovat ilmeisiä. */
class WorkoutStatsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Helsinki")

    private fun wo(
        date: LocalDate,
        type: Int = WorkoutEntity.TYPE_WALK,
        distM: Double = 1000.0,
        moveMs: Long = 600_000,
        kcal: Int = 100,
        steps: Long = 1000,
        elev: Double = 10.0,
    ) = WorkoutEntity().also {
        it.startedAtMs = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        it.type = type
        it.distanceM = distM
        it.movingTimeMs = moveMs
        it.kcal = kcal
        it.steps = steps
        it.elevGainM = elev
        it.status = WorkoutEntity.STATUS_FINISHED
    }

    @Test
    fun `viikko ryhmittaa ISO-viikkoon ja summaa uusin ensin`() {
        // Viikko 25/2026 = ma 15.6. – su 21.6.; viikko 24 = ma 8.6.
        val list = listOf(
            wo(LocalDate.of(2026, 6, 15), distM = 1000.0, moveMs = 600_000, kcal = 100, steps = 1000, elev = 10.0),
            wo(LocalDate.of(2026, 6, 17), type = WorkoutEntity.TYPE_BIKE, distM = 5000.0, moveMs = 900_000, kcal = 200, steps = 0, elev = 30.0),
            wo(LocalDate.of(2026, 6, 21), distM = 2000.0, moveMs = 300_000, kcal = 50, steps = 2500, elev = 5.0),
            wo(LocalDate.of(2026, 6, 8), distM = 3000.0, moveMs = 1_000_000, kcal = 80, steps = 4000, elev = 20.0),
        )
        val weeks = WorkoutStats.weekly(list, zone)
        assertEquals(2, weeks.size)

        val w25 = weeks[0]
        assertEquals("Viikko 25", w25.label)
        assertEquals("15.6.–21.6.", w25.dateRange)
        assertEquals(3, w25.count)
        assertEquals(8000.0, w25.distanceM, 1e-6)
        assertEquals(1_800_000L, w25.movingTimeMs)
        assertEquals(350, w25.kcal)
        assertEquals(3500L, w25.steps)
        assertEquals(45.0, w25.elevGainM, 1e-6)
        assertEquals(2, w25.walkCount)
        assertEquals(1, w25.bikeCount)

        val w24 = weeks[1]
        assertEquals("Viikko 24", w24.label)
        assertEquals("8.6.–14.6.", w24.dateRange)
        assertEquals(1, w24.count)
    }

    @Test
    fun `kuukausi ryhmittaa ja nimeaa suomeksi uusin ensin`() {
        val list = listOf(
            wo(LocalDate.of(2026, 5, 20), distM = 1000.0),
            wo(LocalDate.of(2026, 6, 2), distM = 2000.0),
            wo(LocalDate.of(2026, 6, 30), type = WorkoutEntity.TYPE_BIKE, distM = 4000.0),
        )
        val months = WorkoutStats.monthly(list, zone)
        assertEquals(2, months.size)
        assertEquals("Kesäkuu 2026", months[0].label)
        assertNull(months[0].dateRange)
        assertEquals(2, months[0].count)
        assertEquals(6000.0, months[0].distanceM, 1e-6)
        assertEquals(1, months[0].walkCount)
        assertEquals(1, months[0].bikeCount)
        assertEquals("Toukokuu 2026", months[1].label)
        assertEquals(1, months[1].count)
    }

    @Test
    fun `vuodenvaihteen viikko 1 ei sekoitu eri vuosilta`() {
        // ISO-viikko 1: ma 30.12.2024 (vko-vuosi 2025) ja ma 29.12.2025 (vko-vuosi 2026).
        val list = listOf(
            wo(LocalDate.of(2024, 12, 30)),
            wo(LocalDate.of(2025, 12, 29)),
        )
        val weeks = WorkoutStats.weekly(list, zone)
        assertEquals(2, weeks.size)
        assertEquals("Viikko 1", weeks[0].label)
        assertEquals("Viikko 1", weeks[1].label)
        // Uusin ensin: 29.12.2025-viikko (–4.1.) ennen 30.12.2024-viikkoa (–5.1.)
        assertEquals("29.12.–4.1.", weeks[0].dateRange)
        assertEquals("30.12.–5.1.", weeks[1].dateRange)
    }

    @Test
    fun `tyhja lista tuottaa tyhjat ampparit`() {
        assertTrue(WorkoutStats.weekly(emptyList(), zone).isEmpty())
        assertTrue(WorkoutStats.monthly(emptyList(), zone).isEmpty())
    }

    @Test
    fun `pelkka pyoraily nollaa kavelymaaran`() {
        val list = listOf(
            wo(LocalDate.of(2026, 6, 15), type = WorkoutEntity.TYPE_BIKE, steps = 0, elev = 0.0),
        )
        val weeks = WorkoutStats.weekly(list, zone)
        assertEquals(1, weeks.size)
        assertEquals(0, weeks[0].walkCount)
        assertEquals(1, weeks[0].bikeCount)
        assertEquals(0L, weeks[0].steps)
        assertEquals(0.0, weeks[0].elevGainM, 1e-6)
    }
}
