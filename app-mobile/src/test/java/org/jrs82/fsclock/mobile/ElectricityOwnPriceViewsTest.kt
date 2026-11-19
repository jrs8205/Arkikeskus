package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Näkymien yhteiset laskennat omilla hinnoilla: halvin ja kallein jakso, hintalistan rivit, päivän
 * keskihinta ja tuntijakson hinta lasketaan kokonaishinnasta; hintataso pysyy pörssihinnassa.
 */
class ElectricityOwnPriceViewsTest {
    private fun q(hour: Int, minute: Int, snt: Double): ElectricityData.Quarter = ElectricityData.quarter(
        LocalDateTime.of(2026, 11, 17, hour, minute).atZone(ElectricityTime.HELSINKI).toInstant().toEpochMilli(), snt,
    )

    /** Täysi päivä 17.11.2026: pörssi 2,0; klo 12.00–12.15 on pörssin halvin (1,0) ja tunti 18 kallein (9,0). */
    private val day: List<ElectricityData.Quarter> = (0..23).flatMap { h ->
        (0..3).map { i ->
            q(h, i * 15, when {
                h == 12 && i == 0 -> 1.0
                h == 18 -> 9.0
                else -> 2.0
            })
        }
    }

    private val timeTransfer = OwnTransfer(TransferProduct.TIME, 4.10, 2.60)

    /** Päivä 4,10 (klo 7–22), yö 2,60; ALV mukana, ei marginaalia eikä veroa. */
    private val night = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, 0.0, timeTransfer, 0.0))

    @Test fun slotRange_cheapestFollowsTotalPrice_notSpot() {
        val (min, max) = electricitySlotRange(day, night)!!
        assertEquals(0, min.hour) // yövartti 2,51 + 2,60 = 5,11 < keskipäivän 1,255 + 4,10 = 5,355
        assertEquals(0, min.minute)
        assertEquals(18, max.hour)
        assertEquals(12, electricitySlotRange(day, ElectricityPricing(false))!!.first.hour)
    }

    @Test fun slotRange_emptyIsNull() {
        assertEquals(null, electricitySlotRange(emptyList(), night))
    }

    @Test fun rows_showTotalPrice_andScaleByIt() {
        val rows = electricityPriceRows(day, ElectricityHourly.QUARTER_MS, night, null)
        assertEquals(5.11, rows.first().displayPrice, 1e-9)
        assertEquals(5.11, rows.first().scalePrice, 1e-9)
    }

    @Test fun rows_withoutOwnPrices_keepRawScale() {
        // Kytkin pois: lämpöväri skaalataan verottomasta hinnasta kuten ennen, näytetty hinta ALV:n kanssa.
        val row = electricityPriceRows(day, ElectricityHourly.QUARTER_MS, ElectricityPricing(true), null).first()
        assertEquals(2.0, row.scalePrice, 1e-9)
        assertEquals(2.51, row.displayPrice, 1e-9)
    }

    @Test fun dayAverage_isMeanOfShownQuarterPrices() {
        // Pörssi: (96 × 2,0 − 1,0 + 4 × 7,0) × 1,255 = 274,845. Siirto: 60 × 4,10 + 36 × 2,60 = 339,6. Summa / 96.
        assertEquals(6.40046875, electricityDayAverage(day, night), 1e-9)
    }

    @Test fun hourSlot_totalEqualsMeanOfItsQuarterTotals() {
        val pricing = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, 0.59, timeTransfer, 2.918))
        val hours = ElectricityHourly.daySlots(day, hourly = true, vat = true)
        assertTrue(hours.hourly)
        for (slot in hours.slots) {
            val quarterTotals = day.filter { it.hour == slot.hour }.map { pricing.shown(it) }
            assertEquals("tunti ${slot.hour}", quarterTotals.average(), pricing.shown(slot), 1e-9)
        }
    }

    @Test fun priceLevel_followsSpot_evenWhenTotalExceedsTheExpensiveLimit() {
        val own = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, 0.59, OwnTransfer(TransferProduct.GENERAL, 4.10, 0.0), 2.918))
        assertTrue(own.shown(day.first().timestamp, 10.0) > 15.0)
        assertEquals(PriceLevel.NORMAL, priceLevel(own.spot(10.0), 5.0))
        assertEquals(priceLevel(ElectricityPricing(true).spot(10.0), 5.0), priceLevel(own.spot(10.0), 5.0))
    }
}
