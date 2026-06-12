package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PNG-jakokuvan Web Mercator -projektion perusominaisuudet (WorkoutShareImage). */
class WebMercatorTest {

    @Test
    fun `origo on maailman keskipiste zoomilla 0`() {
        assertEquals(128.0, WorkoutShareImage.worldX(0.0, 0), 1e-9)
        assertEquals(128.0, WorkoutShareImage.worldY(0.0, 0), 1e-9)
    }

    @Test
    fun `pituuspiirin aarilaidat`() {
        assertEquals(0.0, WorkoutShareImage.worldX(-180.0, 0), 1e-9)
        assertEquals(256.0, WorkoutShareImage.worldX(180.0, 0), 1e-9)
    }

    @Test
    fun `zoom tuplaa maailman koon`() {
        assertEquals(128.0 * 32, WorkoutShareImage.worldX(0.0, 5), 1e-9)
        assertEquals(128.0 * 32, WorkoutShareImage.worldY(0.0, 5), 1e-6)
    }

    @Test
    fun `leveysaste clampataan mercatorin rajoihin`() {
        // 89° ja clampattu maksimi tuottavat saman arvon (~0 = ylareuna)
        assertEquals(
            WorkoutShareImage.worldY(85.05112878, 0),
            WorkoutShareImage.worldY(89.0, 0),
            1e-9,
        )
        assertTrue(WorkoutShareImage.worldY(85.05112878, 0) < 0.001)
    }

    @Test
    fun `pohjoisempi piste on pienempi y`() {
        val helsinki = WorkoutShareImage.worldY(60.17, 12)
        val vantaa = WorkoutShareImage.worldY(60.30, 12)
        assertTrue(vantaa < helsinki)
    }

    @Test
    fun `suomen koordinaatit osuvat jarkeviin tiili-indekseihin`() {
        // Helsinki z10: tile x = 582, y = 296 (slippy-tiilikaavan referenssiarvo)
        val tx = (WorkoutShareImage.worldX(24.94, 10) / 256).toInt()
        val ty = (WorkoutShareImage.worldY(60.17, 10) / 256).toInt()
        assertEquals(582, tx)
        assertEquals(296, ty)
    }
}
