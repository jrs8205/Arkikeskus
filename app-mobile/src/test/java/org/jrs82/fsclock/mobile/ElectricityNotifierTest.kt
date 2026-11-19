package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

private val HELSINKI: ZoneId = ZoneId.of("Europe/Helsinki")

/** Vartti 17.11.2026 (tavallinen 24 tunnin päivä) Suomen aikaa. */
private fun q(hour: Int, minute: Int, snt: Double): ElectricityData.Quarter =
    ElectricityData.quarter(
        LocalDateTime.of(2026, 11, 17, hour, minute).atZone(HELSINKI).toInstant().toEpochMilli(), snt,
    )

/** Täysi päivä: jokaiselle tunnille neljä varttia, hinnat [price]n mukaan. */
private fun fullDay(price: (hour: Int, index: Int) -> Double): List<ElectricityData.Quarter> =
    (0..23).flatMap { h -> (0..3).map { i -> q(h, i * 15, price(h, i)) } }

/** 4b-ilmoitus #3: [ElectricityNotifier.summarize] halvin/kallein/keskihinta -logiikan testit. */
class ElectricityNotifierTest {

    @Test fun minMaxAvgComputed() {
        val s = ElectricityNotifier.summarize(
            listOf(q(3, 0, 2.0), q(3, 15, 1.0), q(18, 0, 9.0), q(18, 15, 8.0)),
        )!!
        assertEquals(1.0, s.minSnt, 0.001)
        assertEquals("03.15–03.30", s.minRange)
        assertEquals(9.0, s.maxSnt, 0.001)
        assertEquals("18.00–18.15", s.maxRange)
        assertEquals(5.0, s.avgSnt, 0.001) // (2+1+9+8)/4
        assertFalse(s.hourly)
    }

    @Test fun emptyReturnsNull() {
        assertNull(ElectricityNotifier.summarize(emptyList()))
    }

    @Test fun negativePricesHandled() {
        val s = ElectricityNotifier.summarize(listOf(q(2, 0, -0.5), q(14, 30, 3.2)))!!
        assertEquals(-0.5, s.minSnt, 0.001)
        assertEquals("02.00–02.15", s.minRange)
        assertEquals(3.2, s.maxSnt, 0.001)
        assertEquals("14.30–14.45", s.maxRange)
    }

    @Test fun withVat_onlyPositiveQuartersAreTaxed_andAverageUsesShownPrices() {
        val s = ElectricityNotifier.summarize(listOf(q(2, 0, -0.5), q(14, 30, 3.2)), pricing = ElectricityPricing(true))!!
        assertEquals(-0.5, s.minSnt, 1e-9)
        assertEquals(4.016, s.maxSnt, 1e-9)
        assertEquals((-0.5 + 4.016) / 2, s.avgSnt, 1e-9)
    }

    @Test fun lastQuarterOfDay_endsAtMidnight() {
        val s = ElectricityNotifier.summarize(listOf(q(12, 0, 5.0), q(23, 45, 1.0)))!!
        assertEquals("23.45–00.00", s.minRange)
    }
}

/** Ilmoituksen jakso seuraa asetusta: vartit tai tunnit ("Näytä tunnin keskihinta"). */
class ElectricityNotifierPeriodTest {

    // Tunti 03: päivän halvin yksittäinen vartti (0,2), mutta tunnin keskiarvo 3,05.
    // Tunti 04: tasainen 1,0 (halvin tunti). Tunti 18: tasainen 9,0 (kallein). Muut 5,0.
    private val day = fullDay { h, i ->
        when (h) {
            3 -> if (i == 0) 0.2 else 4.0
            4 -> 1.0
            18 -> 9.0
            else -> 5.0
        }
    }

    @Test fun quarterSetting_reportsCheapestAndDearestQuarter_asQuarterRanges() {
        val s = ElectricityNotifier.summarize(day, pricing = ElectricityPricing(false), hourly = false)!!
        assertFalse(s.hourly)
        assertEquals("03.00–03.15", s.minRange)
        assertEquals(0.2, s.minSnt, 1e-9)
        assertEquals("18.00–18.15", s.maxRange)
    }

    @Test fun hourlySetting_reportsCheapestAndDearestHour_asHourRanges() {
        val s = ElectricityNotifier.summarize(day, pricing = ElectricityPricing(false), hourly = true)!!
        assertTrue(s.hourly)
        assertEquals("04.00–05.00", s.minRange)
        assertEquals(1.0, s.minSnt, 1e-9)
        assertEquals("18.00–19.00", s.maxRange)
        assertEquals(9.0, s.maxSnt, 1e-9)
    }

    @Test fun dayAverage_isTheSameInBothModes() {
        val byQuarter = ElectricityNotifier.summarize(day, pricing = ElectricityPricing(false), hourly = false)!!
        val byHour = ElectricityNotifier.summarize(day, pricing = ElectricityPricing(false), hourly = true)!!
        assertEquals(byQuarter.avgSnt, byHour.avgSnt, 1e-9)
    }

    @Test fun hourlySetting_lastHourOfDay_endsAtMidnight() {
        val s = ElectricityNotifier.summarize(fullDay { h, _ -> if (h == 23) 0.5 else 5.0 }, hourly = true)!!
        assertEquals("23.00–00.00", s.minRange)
    }

    @Test fun hourlySetting_incompleteDay_reportsQuarters() {
        // 95 varttia: tunnilta 18 (kallis) puuttuu viimeinen vartti. Tuntitila koskee vain täyttä
        // päivää, joten ilmoitus kertoo halvimman ja kalleimman VARTIN eikä kallis vajaa tunti katoa.
        // Päivän keskihinta lasketaan kaikista saaduista varteista kuten sähkösivulla.
        val quarters = fullDay { h, _ -> if (h == 18) 40.0 else 1.0 }.filterNot { it.hour == 18 && it.minute == 45 }
        assertEquals(95, quarters.size)

        val stats = ElectricityNotifier.summarize(quarters, pricing = ElectricityPricing(true), hourly = true)!!

        assertFalse(stats.hourly)
        val pageAverage = quarters.map { ElectricityVat.apply(it.sntPerKwh, true) }.average()
        assertEquals(pageAverage, stats.avgSnt, 1e-9)
        assertEquals(40.0 * 1.255, stats.maxSnt, 1e-9)
        assertEquals("18.00–18.15", stats.maxRange)
        assertEquals(1.255, stats.minSnt, 1e-9)
        assertEquals(ElectricityNotifier.summarize(quarters, pricing = ElectricityPricing(true), hourly = false), stats)
    }

    @Test fun hourlySetting_withOnlyAFewQuarters_reportsQuarters() {
        val stats = ElectricityNotifier.summarize(listOf(q(3, 0, 1.0), q(3, 15, 2.0), q(4, 0, 3.0)), hourly = true)!!
        assertFalse(stats.hourly)
        assertEquals("03.00–03.15", stats.minRange)
        assertEquals("04.00–04.15", stats.maxRange)
        assertEquals(2.0, stats.avgSnt, 1e-9)
    }

    @Test fun messageResource_followsThePeriod() {
        assertEquals(R.string.electricity_notify_text_quarter, ElectricityNotifier.messageRes(hourly = false))
        assertEquals(R.string.electricity_notify_text_hour, ElectricityNotifier.messageRes(hourly = true))
    }

    @Test fun messageTexts_nameThePeriod_inEveryLanguage() {
        // Luetaan resurssit suoraan (puhdas JVM-testi). Varttiteksti nimeää vartin ja tuntiteksti tunnin.
        val words = mapOf(
            "values" to ("quarter hour" to "hour"),
            "values-fi" to ("vartti" to "tunti"),
            "values-sv" to ("kvarten" to "timmen"),
        )
        for ((dir, expected) in words) {
            val xml = File("src/main/res/$dir/strings_electricity.xml").readText()
            fun body(name: String) = Regex("<string name=\"$name\">([^<]*)</string>").find(xml)!!.groupValues[1].lowercase()
            val quarter = body("electricity_notify_text_quarter")
            val hour = body("electricity_notify_text_hour")
            assertTrue("$dir: $quarter", quarter.contains(expected.first))
            assertTrue("$dir: $hour", hour.contains(expected.second))
            assertFalse("$dir: $hour", hour.contains(expected.first))
        }
    }
}

class ElectricityNotifierMessageTest {
    private val stats = ElectricityNotifier.DayStats(1.0, "03.15–03.30", 9.0, "18.00–18.15", 5.0)

    @Test fun message_withVat_showsTaxedPrices() {
        val taxed = ElectricityNotifier.summarize(listOf(q(3, 15, 1.0), q(18, 0, 9.0)), pricing = ElectricityPricing(true))!!
        assertEquals("03.15–03.30", taxed.minRange)
        assertEquals("18.00–18.15", taxed.maxRange)
        assertEquals(listOf("1,255", "11,295", "6,275"), ElectricityNotifier.messagePrices(taxed, AppLocale.FINNISH))
    }

    @Test fun message_withoutVat_keepsRawPricesThreeDecimals() {
        assertEquals(listOf("1,000", "9,000", "5,000"), ElectricityNotifier.messagePrices(stats, AppLocale.FINNISH))
    }

    @Test fun message_englishUsesDecimalPoint() {
        assertEquals(listOf("1.000", "9.000", "5.000"), ElectricityNotifier.messagePrices(stats, AppLocale.ENGLISH))
    }

    @Test fun message_usesNotificationTitleResource() {
        assertEquals(R.string.electricity_notify_title, ElectricityNotifier.TITLE_RES)
    }

    @Test fun summarize_marksRepeatedAutumnHour_withDstResource() {
        val summer0315 = LocalDateTime.of(2026, 10, 25, 3, 15).atZone(HELSINKI).withEarlierOffsetAtOverlap()
            .toInstant().toEpochMilli()
        val winter0315 = LocalDateTime.of(2026, 10, 25, 3, 15).atZone(HELSINKI).withLaterOffsetAtOverlap()
            .toInstant().toEpochMilli()
        val s = ElectricityNotifier.summarize(
            listOf(ElectricityData.quarter(summer0315, 1.0), ElectricityData.quarter(winter0315, 9.0)),
        )!!
        assertEquals("03.15–03.30", s.minRange)
        assertEquals("03.15–03.30", s.maxRange)
        assertEquals(R.string.electricity_dst_summer, s.minDstRes)
        assertEquals(R.string.electricity_dst_winter, s.maxDstRes)
        assertNull(stats.minDstRes)
    }

    @Test fun summarize_lastSummerTimeQuarter_showsQuarterLongRange() {
        // 03.45 kesäaikaa päättyy 03.00 talviaikaa; jakso näytetään vartin pituisena.
        val summer0345 = LocalDateTime.of(2026, 10, 25, 3, 45).atZone(HELSINKI).withEarlierOffsetAtOverlap()
            .toInstant().toEpochMilli()
        val s = ElectricityNotifier.summarize(listOf(ElectricityData.quarter(summer0345, 1.0)))!!
        assertEquals("03.45–04.00", s.minRange)
        assertEquals(R.string.electricity_dst_summer, s.minDstRes)
    }
}

/** Omilla hinnoilla ilmoitus kertoo kokonaishinnat, ja halvin ja kallein jakso valitaan niistä. */
class ElectricityNotifierOwnPricesTest {
    // Pörssi 2,0; klo 12.00–12.15 on pörssin halvin (1,0) ja tunti 18 kallein (9,0).
    private val day = fullDay { h, i ->
        when {
            h == 12 && i == 0 -> 1.0
            h == 18 -> 9.0
            else -> 2.0
        }
    }

    /** Päivä 4,10 (klo 7–22), yö 2,60; ALV mukana, ei marginaalia eikä veroa. */
    private val night = ElectricityPricing(
        true, OwnPrices(OwnContract.SPOT, 0.0, OwnTransfer(TransferProduct.TIME, 4.10, 2.60), 0.0),
    )

    @Test fun ownPrices_cheapestQuarterFollowsTotalPrice() {
        val s = ElectricityNotifier.summarize(day, night)!!
        assertEquals("00.00–00.15", s.minRange) // 2,51 + 2,60 = 5,11 < keskipäivän 1,255 + 4,10 = 5,355
        assertEquals(5.11, s.minSnt, 1e-9)
        assertEquals("18.00–18.15", s.maxRange)
        assertEquals(15.395, s.maxSnt, 1e-9)
        assertEquals(6.40046875, s.avgSnt, 1e-9)
    }

    @Test fun ownPrices_hourlySetting_reportsHourTotals() {
        val s = ElectricityNotifier.summarize(day, night, hourly = true)!!
        assertTrue(s.hourly)
        assertEquals("00.00–01.00", s.minRange)
        assertEquals(5.11, s.minSnt, 1e-9)
        assertEquals("18.00–19.00", s.maxRange)
        assertEquals(15.395, s.maxSnt, 1e-9)
    }

    @Test fun ownPrices_hourlySettingIncompleteDay_usesQuarterTotals() {
        val partial = day.filterNot { it.hour == 18 && it.minute == 45 }
        val s = ElectricityNotifier.summarize(partial, night, hourly = true)!!
        assertFalse(s.hourly)
        assertEquals("00.00–00.15", s.minRange)
        assertEquals("18.00–18.15", s.maxRange)
        assertEquals(partial.map { night.shown(it) }.average(), s.avgSnt, 1e-9)
    }
}
