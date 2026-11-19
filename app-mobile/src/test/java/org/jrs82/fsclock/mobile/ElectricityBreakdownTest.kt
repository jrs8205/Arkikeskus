package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** Sähkösivun yläkortin erittelyrivi: mistä oma kokonaishinta koostuu. */
class ElectricityBreakdownTest {
    private fun q(hour: Int, minute: Int, snt: Double): ElectricityData.Quarter = ElectricityData.quarter(
        LocalDateTime.of(2026, 11, 17, hour, minute).atZone(ElectricityTime.HELSINKI).toInstant().toEpochMilli(), snt,
    )

    private val general = OwnTransfer(TransferProduct.GENERAL, 4.10, 0.0)
    private val own = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, 0.59, general, 2.918))

    @Test fun spotContract_listsSpotMarginTransferTax() {
        val parts = electricityBreakdown(ElectricityHero.CURRENT, q(12, 0, 10.0), own)!!
        assertEquals(
            listOf(
                R.string.electricity_part_spot, R.string.electricity_part_margin,
                R.string.electricity_part_transfer, R.string.electricity_part_tax,
            ),
            parts.map { it.first },
        )
        assertEquals(12.55, parts[0].second, 1e-9)
        assertEquals(listOf(0.59, 4.10, 2.918), parts.drop(1).map { it.second })
        assertEquals(own.shown(q(12, 0, 10.0)), parts.sumOf { it.second }, 1e-9)
    }

    @Test fun partsNotYetEntered_areShownAsZero() {
        // Kytkin päällä, hintoja ei vielä syötetty: käyttäjä näkee, että marginaali ja siirto ovat nollia.
        val empty = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, 0.0, OwnTransfer(TransferProduct.GENERAL, 0.0, 0.0), 2.918))
        val parts = electricityBreakdown(ElectricityHero.CURRENT, q(12, 0, 10.0), empty)!!
        assertEquals(listOf(0.0, 0.0, 2.918), parts.drop(1).map { it.second })
    }

    @Test fun fixedContract_hasNoMargin_andTaxOffIsOmitted() {
        val fixed = ElectricityPricing(true, OwnPrices(OwnContract.FIXED, 7.0, general, 0.0))
        val parts = electricityBreakdown(ElectricityHero.CURRENT, q(12, 0, 10.0), fixed)!!
        assertEquals(listOf(R.string.electricity_part_spot, R.string.electricity_part_transfer), parts.map { it.first })
        assertEquals(4.10, parts[1].second, 1e-9)
    }

    @Test fun timeTransfer_usesTheTransferPriceOfTheCurrentSlot() {
        val time = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, 0.0, OwnTransfer(TransferProduct.TIME, 4.10, 2.60), 0.0))
        assertEquals(2.60, electricityBreakdown(ElectricityHero.CURRENT, q(3, 0, 10.0), time)!![2].second, 1e-9)
        assertEquals(4.10, electricityBreakdown(ElectricityHero.CURRENT, q(12, 0, 10.0), time)!![2].second, 1e-9)
    }

    @Test fun defaultTax_partsAddUpToTheShownTotal() {
        // Osat pyöristetään erikseen kolmeen desimaaliin; oletusveron pitää olla kolmen desimaalin luku,
        // jotta erittelyn summa on sama kuin iso hinta (pörssi 1,002 → 1,258 + 0,590 + 4,100 + 2,918 = 8,866).
        val pricing = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, 0.59, general, ElectricityOwnSettings.DEFAULT_TAX_SNT))
        val slot = q(12, 0, 1.002)
        val parts = electricityBreakdown(ElectricityHero.CURRENT, slot, pricing)!!
        val fi = AppLocale.FINNISH
        val sumOfShownParts = parts.sumOf { ElectricityVat.format(it.second, fi).replace(',', '.').toDouble() }
        assertEquals(ElectricityVat.format(pricing.shown(slot), fi), ElectricityVat.format(sumOfShownParts, fi))
    }

    @Test fun isNullWhenOff_orNoCurrent_orDayAverage() {
        assertNull(electricityBreakdown(ElectricityHero.CURRENT, q(12, 0, 10.0), ElectricityPricing(true)))
        assertNull(electricityBreakdown(ElectricityHero.NO_CURRENT, null, own))
        assertNull(electricityBreakdown(ElectricityHero.DAY_AVERAGE, null, own))
        assertNull(electricityBreakdown(ElectricityHero.DAY_AVERAGE, q(12, 0, 10.0), own))
        assertNull(electricityBreakdown(ElectricityHero.CURRENT, null, own))
    }
}
