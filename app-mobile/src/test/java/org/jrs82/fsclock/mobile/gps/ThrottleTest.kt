package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tallennuksen harvennus: matkan tila tallennetaan mittauksista, mutta enintään kerran 10 sekunnissa. */
class ThrottleTest {

    @Test fun ensimmainenKutsuJaSittenVainValinKuluttua() {
        val t = Throttle(10_000)
        assertTrue(t.due(1_000))
        assertFalse(t.due(1_001))
        assertFalse(t.due(10_999))
        assertTrue(t.due(11_000))
        assertFalse(t.due(20_999))
        assertTrue(t.due(21_000))
    }

    @Test fun nollausSalliiHetiUudelleen() {
        val t = Throttle(10_000)
        assertTrue(t.due(1_000))
        t.reset()
        assertTrue(t.due(1_500))
    }

    @Test fun kellonHyppyTaaksepainEiJumita() {
        // Aika ei käytännössä kulje taaksepäin, mutta jos näin käy, tallennus ei saa pysähtyä pysyvästi.
        val t = Throttle(10_000)
        assertTrue(t.due(50_000))
        assertTrue(t.due(1_000))
    }
}
