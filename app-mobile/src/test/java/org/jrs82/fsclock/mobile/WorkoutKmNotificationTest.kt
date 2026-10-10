package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lenkin km-väli-ilmoituksen tunnusluvut (kmSplitStats): tahti (täyttyneen km:n kesto),
 * kokonaisliikeaika ja keskinopeus. Pure-funktio (ei Androidia). Tekstimuotoilu
 * (kmSplitStatsText) testataan erikseen kielen mallilla ja localella.
 */
class WorkoutKmNotificationTest {

    private val fiTemplate = "tahti %1\$s/km · aika %2\$s · ka %3\$.1f km/h"
    private val enTemplate = "pace %1\$s/km · time %2\$s · avg %3\$.1f km/h"

    @Test
    fun `perustapaus tahti aika ja keskinopeus`() {
        // 1. km, tahti 5:00/km, kokonaisaika 5:00 → ka 12,0 km/h
        val s = kmSplitStats(1, 300_000L, 300_000L)
        assertEquals("5:00", s.pace)
        assertEquals("5:00", s.total)
        assertEquals(12.0, s.avgKmh, 1e-9)
    }

    @Test
    fun `yli tunnin kokonaisaika naytetaan h mm ss`() {
        // 12. km, tahti 5:00/km, kokonaisaika 1:01:00 → ka 11,8 km/h
        val s = kmSplitStats(12, 300_000L, 3_660_000L)
        assertEquals(KmSplitStats("5:00", "1:01:00", 12 * 3_600_000.0 / 3_660_000L), s)
        assertEquals("tahti 5:00/km · aika 1:01:00 · ka 11,8 km/h",
            kmSplitStatsText(s, fiTemplate, AppLocale.FINNISH))
    }

    @Test
    fun `hidas tahti yli kymmenen minuuttia`() {
        // 2. km, tahti 12:30/km, kokonaisaika 25:00 → ka 4,8 km/h
        val s = kmSplitStats(2, 750_000L, 1_500_000L)
        assertEquals("12:30", s.pace)
        assertEquals("25:00", s.total)
        assertEquals(4.8, s.avgKmh, 1e-9)
    }

    @Test
    fun `nollat eivat kaada keskinopeus on nolla`() {
        assertEquals(KmSplitStats("0:00", "0:00", 0.0), kmSplitStats(1, 0L, 0L))
    }

    @Test
    fun `teksti muotoillaan kielen mallilla ja desimaalierottimella`() {
        val s = kmSplitStats(1, 300_000L, 300_000L)
        assertEquals("tahti 5:00/km · aika 5:00 · ka 12,0 km/h",
            kmSplitStatsText(s, fiTemplate, AppLocale.FINNISH))
        assertEquals("pace 5:00/km · time 5:00 · avg 12.0 km/h",
            kmSplitStatsText(s, enTemplate, AppLocale.ENGLISH))
    }
}
