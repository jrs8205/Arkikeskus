package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 4b-ilmoitus #3: [ElectricityNotifier.summarize] halvin/kallein/keskihinta -logiikan testit. */
class ElectricityNotifierTest {

    private fun q(hour: Int, minute: Int, snt: Double): ElectricityData.Quarter {
        val q = ElectricityData.Quarter()
        q.hour = hour
        q.minute = minute
        q.sntPerKwh = snt
        return q
    }

    @Test fun minMaxAvgComputed() {
        val s = ElectricityNotifier.summarize(
            listOf(q(3, 0, 2.0), q(3, 15, 1.0), q(18, 0, 9.0), q(18, 15, 8.0)),
        )!!
        assertEquals(1.0, s.minSnt, 0.001)
        assertEquals("03:15", s.minHm)
        assertEquals(9.0, s.maxSnt, 0.001)
        assertEquals("18:00", s.maxHm)
        assertEquals(5.0, s.avgSnt, 0.001) // (2+1+9+8)/4
    }

    @Test fun emptyReturnsNull() {
        assertNull(ElectricityNotifier.summarize(emptyList()))
    }

    @Test fun negativePricesHandled() {
        val s = ElectricityNotifier.summarize(listOf(q(2, 0, -0.5), q(14, 30, 3.2)))!!
        assertEquals(-0.5, s.minSnt, 0.001)
        assertEquals("02:00", s.minHm)
        assertEquals(3.2, s.maxSnt, 0.001)
        assertEquals("14:30", s.maxHm)
    }
}
