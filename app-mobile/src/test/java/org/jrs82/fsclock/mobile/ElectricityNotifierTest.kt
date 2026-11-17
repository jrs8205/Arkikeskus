package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

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

    @Test fun message_withVat_showsTaxedPrices() {
        val q = { hour: Int, minute: Int, snt: Double ->
            ElectricityData.Quarter().apply { this.hour = hour; this.minute = minute; sntPerKwh = snt }
        }
        val taxed = ElectricityNotifier.summarize(listOf(q(3, 15, 1.0), q(18, 0, 9.0)), vat = true)!!
        assertEquals("03:15", taxed.minHm)
        assertEquals("18:00", taxed.maxHm)
        assertEquals(listOf("1,255", "11,295", "6,275"), ElectricityNotifier.messagePrices(taxed, AppLocale.FINNISH))
    }

    @Test fun message_withoutVat_keepsRawPricesThreeDecimals() {
        assertEquals(listOf("1,000", "9,000", "5,000"), ElectricityNotifier.messagePrices(stats, AppLocale.FINNISH))
    }

    @Test fun message_englishUsesDecimalPoint() {
        assertEquals(listOf("1.000", "9.000", "5.000"), ElectricityNotifier.messagePrices(stats, AppLocale.ENGLISH))
    }

    @Test fun message_usesNotificationResources() {
        assertEquals(R.string.electricity_notify_title, ElectricityNotifier.TITLE_RES)
        assertEquals(R.string.electricity_notify_text, ElectricityNotifier.MESSAGE_RES)
    }

    @Test fun summarize_marksRepeatedAutumnHour_withDstResource() {
        val zone = ZoneId.of("Europe/Helsinki")
        val summer0315 = LocalDateTime.of(2026, 10, 25, 3, 15).atZone(zone).withEarlierOffsetAtOverlap()
            .toInstant().toEpochMilli()
        val winter0315 = LocalDateTime.of(2026, 10, 25, 3, 15).atZone(zone).withLaterOffsetAtOverlap()
            .toInstant().toEpochMilli()
        val cheap = ElectricityData.Quarter().apply { hour = 3; minute = 15; sntPerKwh = 1.0; timestamp = summer0315 }
        val dear = ElectricityData.Quarter().apply { hour = 3; minute = 15; sntPerKwh = 9.0; timestamp = winter0315 }
        val s = ElectricityNotifier.summarize(listOf(cheap, dear))!!
        assertEquals("03:15", s.minHm)
        assertEquals(R.string.electricity_dst_summer, s.minDstRes)
        assertEquals(R.string.electricity_dst_winter, s.maxDstRes)
        assertNull(stats.minDstRes)
    }

    @Test fun hourlySetting_reportsCheapestAndDearestHour_withSameDayAverage() {
        // Asetuksella "Näytä tunnin keskihinta" ilmoitus kertoo halvimman ja kalleimman TUNNIN.
        val zone = ZoneId.of("Europe/Helsinki")
        fun hourQuarters(hour: Int, vararg snt: Double) = snt.mapIndexed { i, p ->
            ElectricityData.quarter(
                LocalDateTime.of(2026, 11, 17, hour, i * 15).atZone(zone).toInstant().toEpochMilli(), p,
            )
        }
        // Täysi päivä. Tunti 03: halvin yksittäinen vartti (0,2) mutta tunnin keskiarvo 3,05;
        // tunti 04: tasainen 1,0; tunti 18: tasainen 9,0; muut tunnit 5,0.
        val quarters = (0..23).flatMap { h ->
            when (h) {
                3 -> hourQuarters(3, 0.2, 4.0, 4.0, 4.0)
                4 -> hourQuarters(4, 1.0, 1.0, 1.0, 1.0)
                18 -> hourQuarters(18, 9.0, 9.0, 9.0, 9.0)
                else -> hourQuarters(h, 5.0, 5.0, 5.0, 5.0)
            }
        }

        val byQuarter = ElectricityNotifier.summarize(quarters, vat = false)!!
        val byHour = ElectricityNotifier.summarize(quarters, vat = false, hourly = true)!!

        assertEquals("03:00", byQuarter.minHm)
        assertEquals("04:00", byHour.minHm)
        assertEquals(1.0, byHour.minSnt, 1e-9)
        assertEquals("18:00", byHour.maxHm)
        assertEquals(byQuarter.avgSnt, byHour.avgSnt, 1e-9)
    }

    private fun dayQuarter(hour: Int, index: Int, snt: Double): ElectricityData.Quarter =
        ElectricityData.quarter(
            LocalDateTime.of(2026, 11, 17, hour, index * 15).atZone(ZoneId.of("Europe/Helsinki")).toInstant().toEpochMilli(),
            snt,
        )

    @Test fun hourlySetting_incompleteDay_usesQuarterStatistics() {
        // 95 varttia: tunnilta 18 (kallis) puuttuu viimeinen vartti. Tuntitila koskee vain täyttä
        // päivää, joten ilmoitus kertoo halvimman ja kalleimman VARTIN eikä kallis vajaa tunti katoa.
        // Päivän keskihinta lasketaan kaikista saaduista varteista kuten sähkösivulla.
        val quarters = (0..23).flatMap { h -> (0..3).map { i -> dayQuarter(h, i, if (h == 18) 40.0 else 1.0) } }
            .filterNot { it.hour == 18 && it.minute == 45 }
        assertEquals(95, quarters.size)

        val stats = ElectricityNotifier.summarize(quarters, vat = true, hourly = true)!!

        val pageAverage = quarters.map { ElectricityVat.apply(it.sntPerKwh, true) }.average()
        assertEquals(pageAverage, stats.avgSnt, 1e-9)
        assertEquals(40.0 * 1.255, stats.maxSnt, 1e-9)
        assertEquals("18:00", stats.maxHm)
        assertEquals(1.255, stats.minSnt, 1e-9)
        assertEquals(ElectricityNotifier.summarize(quarters, vat = true, hourly = false), stats)
    }

    @Test fun hourlySetting_withOnlyAFewQuarters_usesQuarterStatistics() {
        val quarters = listOf(dayQuarter(3, 0, 1.0), dayQuarter(3, 1, 2.0), dayQuarter(4, 0, 3.0))
        val stats = ElectricityNotifier.summarize(quarters, vat = false, hourly = true)!!
        assertEquals("03:00", stats.minHm)
        assertEquals("04:00", stats.maxHm)
        assertEquals(2.0, stats.avgSnt, 1e-9)
    }
}
