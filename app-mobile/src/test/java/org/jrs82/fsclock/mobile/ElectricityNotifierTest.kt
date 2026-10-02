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

    @Test fun withVat_onlyPositiveQuartersAreTaxed_andAverageUsesShownPrices() {
        val s = ElectricityNotifier.summarize(listOf(q(2, 0, -0.5), q(14, 30, 3.2)), vat = true)!!
        assertEquals(-0.5, s.minSnt, 1e-9)
        assertEquals(4.016, s.maxSnt, 1e-9)
        assertEquals((-0.5 + 4.016) / 2, s.avgSnt, 1e-9)
    }
}

class ElectricityNotifierMessageTest {
    private val stats = ElectricityNotifier.DayStats(1.0, "03:15", 9.0, "18:00", 5.0)

    @Test fun message_withVat_showsTaxedPricesAndLabel() {
        val q = { hour: Int, minute: Int, snt: Double ->
            ElectricityData.Quarter().apply { this.hour = hour; this.minute = minute; sntPerKwh = snt }
        }
        val taxed = ElectricityNotifier.summarize(listOf(q(3, 15, 1.0), q(18, 0, 9.0)), vat = true)!!
        assertEquals(
            "Halvin klo 03:15 (1,255 snt), kallein klo 18:00 (11,295 snt). Keskihinta 6,275 snt/kWh, sis. ALV 25,5 %.",
            ElectricityNotifier.message(taxed, true),
        )
    }

    @Test fun message_withoutVat_keepsRawPricesThreeDecimals() {
        assertEquals(
            "Halvin klo 03:15 (1,000 snt), kallein klo 18:00 (9,000 snt). Keskihinta 5,000 snt/kWh, ALV 0 %.",
            ElectricityNotifier.message(stats, false),
        )
    }
}
