package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** [ElectricityPricing]: näytetty hinta pörssihinnasta ja omista lisistä (marginaali, siirto, sähkövero). */
class ElectricityPricingTest {
    private val margin = 0.59
    private val tax = 2.918
    private val general = OwnTransfer(TransferProduct.GENERAL, 4.10, 0.0)
    private val time = OwnTransfer(TransferProduct.TIME, 4.10, 2.60)

    /** Hetki Suomen aikaa (epoch-ms). */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(ElectricityTime.HELSINKI).toInstant().toEpochMilli()

    private val noon = at(2026, 11, 18, 12, 0)

    @Test fun off_shownIsVatAppliedSpot_andScaleValueIsRaw() {
        val p = ElectricityPricing(vat = true)
        assertEquals(12.55, p.shown(noon, 10.0), 1e-9)
        assertEquals(10.0, p.scaleValue(noon, 10.0), 1e-9)
        val parts = p.parts(noon, 10.0)
        assertEquals(12.55, parts.spot, 1e-9)
        assertEquals(PriceParts(parts.spot, 0.0, 0.0, 0.0), parts)
    }

    @Test fun spotContract_addsMarginTransferAndTax() {
        val p = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, margin, general, tax))
        assertEquals(20.158, p.shown(noon, 10.0), 1e-9) // 12,55 + 0,59 + 4,10 + 2,918
        assertEquals(20.158, p.scaleValue(noon, 10.0), 1e-9)
    }

    @Test fun vatOff_dividesOwnPartsBy1255() {
        val p = ElectricityPricing(false, OwnPrices(OwnContract.SPOT, margin, general, tax))
        assertEquals(10.0 + 7.608 / 1.255, p.shown(noon, 10.0), 1e-9)
    }

    @Test fun negativeSpot_isNotTaxed_butOwnPartsAreAdded() {
        val p = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, margin, general, tax))
        assertEquals(6.608, p.shown(noon, -1.0), 1e-9) // -1,0 + 7,608
    }

    @Test fun timeTransfer_boundariesAt7And22() {
        assertEquals(2.60, time.at(at(2026, 11, 18, 6, 45)), 0.0)
        assertEquals(4.10, time.at(at(2026, 11, 18, 7, 0)), 0.0)
        assertEquals(4.10, time.at(at(2026, 11, 18, 21, 45)), 0.0)
        assertEquals(2.60, time.at(at(2026, 11, 18, 22, 0)), 0.0)
    }

    @Test fun days_weekendHandling() {
        // 21.11.2026 on lauantai, 22.11. sunnuntai ja 23.11. maanantai.
        val monFri = time.copy(days = TransferDays.MON_FRI)
        val monSat = time.copy(days = TransferDays.MON_SAT)
        assertEquals(2.60, monFri.at(at(2026, 11, 21, 12, 0)), 0.0)
        assertEquals(4.10, monSat.at(at(2026, 11, 21, 12, 0)), 0.0)
        assertEquals(2.60, monSat.at(at(2026, 11, 22, 12, 0)), 0.0)
        assertEquals(4.10, monFri.at(at(2026, 11, 23, 12, 0)), 0.0)
        assertEquals(4.10, time.at(at(2026, 11, 22, 12, 0)), 0.0) // joka päivä
    }

    @Test fun season_isHighOnlyFromNov1ToMar31() {
        // Kaikki päivät ovat arkipäiviä; kellonaika 12, ellei toisin mainita.
        val season = OwnTransfer(TransferProduct.SEASON, 4.10, 2.60, days = TransferDays.MON_SAT)
        assertEquals(2.60, season.at(at(2026, 10, 30, 12, 0)), 0.0)
        assertEquals(4.10, season.at(at(2026, 11, 2, 12, 0)), 0.0)
        assertEquals(4.10, season.at(at(2027, 3, 31, 12, 0)), 0.0)
        assertEquals(2.60, season.at(at(2027, 4, 1, 12, 0)), 0.0)
        assertEquals(2.60, season.at(at(2026, 11, 2, 23, 0)), 0.0) // talvella yö
    }

    @Test fun season_boundaryDays_withEveryDay() {
        // 31.10.2026 on lauantai ja 1.11.2026 sunnuntai, joten rajat kiinnitetään joka päivä -valinnalla.
        val season = OwnTransfer(TransferProduct.SEASON, 4.10, 2.60, days = TransferDays.EVERY_DAY)
        assertEquals(2.60, season.at(at(2026, 10, 31, 12, 0)), 0.0)
        assertEquals(4.10, season.at(at(2026, 11, 1, 12, 0)), 0.0)
        assertEquals(4.10, season.at(at(2027, 3, 31, 12, 0)), 0.0)
        assertEquals(2.60, season.at(at(2027, 4, 1, 12, 0)), 0.0)
    }

    @Test fun general_ignoresTimeAndDay() {
        assertEquals(4.10, general.at(at(2026, 7, 5, 3, 0)), 0.0)
    }

    @Test fun missingPrice_staysNaN() {
        // Puuttuva hinta näkyy viivana, ei lisien summana.
        val p = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, margin, general, tax))
        assertTrue(p.shown(noon, Double.NaN).isNaN())
    }

    @Test fun repeatedAutumnHour_bothPassesAreNight() {
        val t = LocalDateTime.of(2026, 10, 25, 3, 15).atZone(ElectricityTime.HELSINKI)
        assertEquals(2.60, time.at(t.withEarlierOffsetAtOverlap().toInstant().toEpochMilli()), 0.0)
        assertEquals(2.60, time.at(t.withLaterOffsetAtOverlap().toInstant().toEpochMilli()), 0.0)
    }

    @Test fun nightTransfer_canMakeAHigherSpotPriceTheCheaperTotal() {
        val p = ElectricityPricing(false, OwnPrices(OwnContract.SPOT, 0.0, time, 0.0))
        val day = p.scaleValue(noon, 2.0) // 2,0 + 4,10
        val night = p.scaleValue(at(2026, 11, 18, 3, 0), 3.0) // 3,0 + 2,60
        assertTrue(night < day)
    }

    @Test fun fixedContract_comparePrices() {
        val p = ElectricityPricing(true, OwnPrices(OwnContract.FIXED, 7.0, general, tax))
        assertTrue(p.fixedCompare)
        assertEquals(12.55 + 4.10 + 2.918, p.shown(noon, 10.0), 1e-9) // ei kiinteää hintaa eikä marginaalia
        assertEquals(0.0, p.parts(noon, 10.0).energy, 0.0)
        assertEquals(7.0 + 4.10 + 2.918, p.ownFixedAt(noon), 1e-9)
        assertEquals((7.0 + 4.10 + 2.918) / 1.255, p.copy(vat = false).ownFixedAt(noon), 1e-9)
    }
}
