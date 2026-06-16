package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lenkin km-väli-ilmoituksen tilastorivin (kmSplitStatsText) muotoilu: tahti (täyttyneen km:n kesto),
 * kokonaisliikeaika ja keskinopeus. Pure-funktio (ei Androidia). Suomen lokaali → desimaalipilkku.
 */
class WorkoutKmNotificationTest {

    @Test
    fun `perustapaus tahti aika ja keskinopeus`() {
        // 1. km, tahti 5:00/km, kokonaisaika 5:00 → ka 12,0 km/h
        assertEquals(
            "tahti 5:00/km · aika 5:00 · ka 12,0 km/h",
            kmSplitStatsText(1, 300_000L, 300_000L),
        )
    }

    @Test
    fun `yli tunnin kokonaisaika naytetaan h mm ss`() {
        // 12. km, tahti 5:00/km, kokonaisaika 1:01:00 → ka 11,8 km/h
        assertEquals(
            "tahti 5:00/km · aika 1:01:00 · ka 11,8 km/h",
            kmSplitStatsText(12, 300_000L, 3_660_000L),
        )
    }

    @Test
    fun `hidas tahti yli kymmenen minuuttia`() {
        // 2. km, tahti 12:30/km, kokonaisaika 25:00 → ka 4,8 km/h
        assertEquals(
            "tahti 12:30/km · aika 25:00 · ka 4,8 km/h",
            kmSplitStatsText(2, 750_000L, 1_500_000L),
        )
    }

    @Test
    fun `nollat eivat kaada keskinopeus on nolla`() {
        assertEquals(
            "tahti 0:00/km · aika 0:00 · ka 0,0 km/h",
            kmSplitStatsText(1, 0L, 0L),
        )
    }
}
