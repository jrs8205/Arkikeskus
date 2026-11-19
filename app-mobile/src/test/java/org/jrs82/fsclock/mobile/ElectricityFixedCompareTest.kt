package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Locale

/** Kiinteän sopimuksen vertailutila: oma kiinteä hinta verrattuna pörssihintaan. */
class ElectricityFixedCompareTest {
    private fun q(hour: Int, minute: Int, snt: Double): ElectricityData.Quarter = ElectricityData.quarter(
        LocalDateTime.of(2026, 11, 17, hour, minute).atZone(ElectricityTime.HELSINKI).toInstant().toEpochMilli(), snt,
    )

    private fun fullDay(price: (hour: Int) -> Double): List<ElectricityData.Quarter> =
        (0..23).flatMap { h -> (0..3).map { i -> q(h, i * 15, price(h)) } }

    private val transfer = OwnTransfer(TransferProduct.TIME, 4.10, 2.60)

    /** Kiinteä 7,0 snt/kWh; siirto päivä 4,10 / yö 2,60; vero 2,918; ALV mukana. */
    private val fixed = ElectricityPricing(true, OwnPrices(OwnContract.FIXED, 7.0, transfer, 2.918))
    private val spotContract = ElectricityPricing(true, OwnPrices(OwnContract.SPOT, 0.59, transfer, 2.918))

    /** Pörssi 4,0 (ALV:n kanssa 5,02 < 7,0) tunneilla 0–16 ja 8,0 (10,04 > 7,0) tunneilla 17–23. */
    private val day = fullDay { h -> if (h <= 16) 4.0 else 8.0 }

    @Test fun heroCompare_current_usesTheCurrentSlot() {
        val c = fixedHeroCompare(ElectricityHero.CURRENT, q(12, 0, 10.0), day, fixed)!!
        assertEquals(7.0 + 4.10 + 2.918, c.ownPrice, 1e-9)
        assertEquals(7.0 - 12.55, c.diff, 1e-9) // pörssi 5,55 kalliimpi
        assertFalse(c.dayAverage)
    }

    @Test fun heroCompare_current_atNightUsesNightTransferInOwnPrice() {
        val c = fixedHeroCompare(ElectricityHero.CURRENT, q(3, 0, 4.0), day, fixed)!!
        assertEquals(7.0 + 2.60 + 2.918, c.ownPrice, 1e-9)
        assertEquals(7.0 - 5.02, c.diff, 1e-9) // pörssi 1,98 halvempi
    }

    @Test fun heroCompare_dayAverage_averagesOwnPriceAndSpot() {
        val c = fixedHeroCompare(ElectricityHero.DAY_AVERAGE, null, day, fixed)!!
        assertEquals(day.map { fixed.ownFixedAt(it.timestamp) }.average(), c.ownPrice, 1e-9)
        assertEquals(electricityDayAverage(day, fixed), c.spotPrice, 1e-9) // sama luku kuin kortin iso hinta
        // Ero on kiinteä hinta − pörssin keskihinta, näytettyjen lukujen tarkkuudella (0,001).
        assertEquals(7.0 - day.map { fixed.spot(it.sntPerKwh) }.average(), c.diff, 0.001)
        assertTrue(c.dayAverage)
    }

    @Test fun heroCompare_isNull_whenTheCurrentPriceIsMissing() {
        // Puuttuvaa hintaa (NaN) ei verrata: muuten kortti väittäisi hintoja samoiksi.
        val missing = q(12, 0, Double.NaN)
        assertNull(fixedHeroCompare(ElectricityHero.CURRENT, missing, listOf(missing), fixed))
        assertNull(fixedMark(missing, fixed))
    }

    @Test fun heroCompare_isNullWithoutACurrentPrice_andOutsideCompareMode() {
        assertNull(fixedHeroCompare(ElectricityHero.NO_CURRENT, null, day, fixed))
        assertNull(fixedHeroCompare(ElectricityHero.CURRENT, null, day, fixed))
        assertNull(fixedHeroCompare(ElectricityHero.DAY_AVERAGE, null, emptyList(), fixed))
        assertNull(fixedHeroCompare(ElectricityHero.CURRENT, q(12, 0, 10.0), day, spotContract))
        assertNull(fixedHeroCompare(ElectricityHero.CURRENT, q(12, 0, 10.0), day, ElectricityPricing(true)))
    }

    @Test fun noComparison_untilAFixedPriceIsEntered() {
        // Kiinteä sopimus valittu, hintaa ei vielä syötetty: vertailu nollaan olisi harhaanjohtava.
        val unset = ElectricityPricing(true, OwnPrices(OwnContract.FIXED, 0.0, transfer, 2.918, energySet = false))
        assertNull(fixedHeroCompare(ElectricityHero.CURRENT, q(12, 0, 10.0), day, unset))
        assertNull(fixedHeroCompare(ElectricityHero.DAY_AVERAGE, null, day, unset))
        assertNull(fixedMark(q(12, 0, 4.0), unset))
        assertNull(fixedCheaperCount(day, unset))
        val rows = electricityPriceRows(day, ElectricityHourly.QUARTER_MS, unset, null)
        assertTrue(rows.all { it.fixedMark == null })
        assertEquals(5.02 + 2.60 + 2.918, rows.first().displayPrice, 1e-9) // hinnat silti pörssi + siirto + vero
    }

    @Test fun diff_isTheSameWithDayAndNightTransfer() {
        // Siirto ja vero ovat samat omassa ja pörssin hinnassa, joten sama pörssihinta antaa saman eron yöllä ja päivällä
        // (ALV päällä luvut ovat tasan kolmen desimaalin lukuja, joten pyöristys ei vaikuta).
        val atNight = fixedHeroCompare(ElectricityHero.CURRENT, q(3, 0, 6.0), day, fixed)!!
        val atNoon = fixedHeroCompare(ElectricityHero.CURRENT, q(12, 0, 6.0), day, fixed)!!
        assertEquals(atNight.diff, atNoon.diff, 1e-9)
        assertEquals(fixed.ownFixedAt(q(12, 0, 6.0).timestamp) - fixed.shown(q(12, 0, 6.0)), atNoon.diff, 1e-9)
    }

    @Test fun cheaperCount_countsSlotsWhereSpotIsBelowTheFixedPrice() {
        assertEquals(68 to 96, fixedCheaperCount(day, fixed))
        val hours = ElectricityHourly.daySlots(day, hourly = true, vat = true)
        assertTrue(hours.hourly)
        assertEquals(17 to 24, fixedCheaperCount(hours.slots, fixed))
    }

    @Test fun cheaperCount_isNullOutsideCompareMode() {
        assertNull(fixedCheaperCount(day, spotContract))
        assertNull(fixedCheaperCount(day, ElectricityPricing(true)))
    }

    @Test fun mark_cheaperDearerEqual() {
        assertEquals(FixedMark.SPOT_CHEAPER, fixedMark(q(12, 0, 4.0), fixed))
        assertEquals(FixedMark.SPOT_DEARER, fixedMark(q(12, 0, 8.0), fixed))
        assertEquals(FixedMark.EQUAL, fixedMark(q(12, 0, 7.0 / 1.255), fixed))
        assertNull(fixedMark(q(12, 0, 4.0), spotContract))
    }

    // --- Vertailu näytetyistä hinnoista (katselmointi 2.30.0, kierros 3) ---

    private val fi = AppLocale.FINNISH

    /** ALV pois, kiinteä 9,900, ei siirtoa, vero 2,918 (kaikki syötetty ALV:n kanssa). */
    private val noVatFixed = ElectricityPricing(
        false, OwnPrices(OwnContract.FIXED, 9.9, OwnTransfer(TransferProduct.GENERAL, 0.0, 0.0), 2.918),
    )

    @Test fun visiblyDifferentPrices_areNotMarkedEqual() {
        // Veroton pörssi 7,888: kortti näyttää pörssin 10,213 ja oman hinnan 10,214. Pyöristämätön ero on
        // alle 0,0005, mutta näkyvät hinnat eroavat, joten pörssi on halvempi myös merkissä ja laskurissa.
        val slot = q(12, 0, 7.888)
        assertEquals("10,213", ElectricityVat.format(noVatFixed.shown(slot), fi))
        assertEquals("10,214", ElectricityVat.format(noVatFixed.ownFixedAt(slot.timestamp), fi))
        assertEquals(FixedMark.SPOT_CHEAPER, fixedMark(slot, noVatFixed))
        assertEquals(1 to 1, fixedCheaperCount(listOf(slot), noVatFixed))
        val c = fixedHeroCompare(ElectricityHero.CURRENT, slot, listOf(slot), noVatFixed)!!
        assertEquals(R.string.electricity_fixed_hero_cheaper, fixedHeroRes(c))
        assertEquals("0,001", ElectricityVat.format(Math.abs(c.diff), fi))
    }

    @Test fun pricesShownAsTheSame_areMarkedEqual() {
        // Kiinteä 9,901 ja veroton pörssi 7,8885: molemmat näkyvät 10,214, vaikka pyöristämätön ero on 0,0007.
        val pricing = noVatFixed.copy(own = noVatFixed.own!!.copy(energySnt = 9.901))
        val slot = q(12, 0, 7.8885)
        assertEquals("10,214", ElectricityVat.format(pricing.shown(slot), fi))
        assertEquals("10,214", ElectricityVat.format(pricing.ownFixedAt(slot.timestamp), fi))
        assertEquals(FixedMark.EQUAL, fixedMark(slot, pricing))
        assertEquals(0 to 1, fixedCheaperCount(listOf(slot), pricing))
        val c = fixedHeroCompare(ElectricityHero.CURRENT, slot, listOf(slot), pricing)!!
        assertEquals(R.string.electricity_fixed_hero_equal, fixedHeroRes(c))
        assertEquals("0,000", ElectricityVat.format(Math.abs(c.diff), fi))
    }

    @Test fun markCounterAndCardText_alwaysAgreeWithTheShownPrices() {
        // Tiheä hintaväli tasahinnan molemmin puolin, molemmat ALV-tilat, siirrolla ja ilman: rivin
        // merkki, päivän laskuri sekä kortin teksti ja ero seuraavat aina näytettyjä lukuja.
        for (vat in listOf(true, false)) for (transferSnt in listOf(0.0, 4.10)) {
            val pricing = ElectricityPricing(
                vat, OwnPrices(OwnContract.FIXED, 9.9, OwnTransfer(TransferProduct.GENERAL, transferSnt, 0.0), 2.918),
            )
            for (i in 0..4000) {
                val slot = q(12, 0, 7.7 + i / 10000.0)
                val label = "ALV=$vat siirto=$transferSnt pörssi=${slot.sntPerKwh}"
                val shownSpot = BigDecimal(ElectricityVat.format(pricing.shown(slot), Locale.ROOT))
                val shownOwn = BigDecimal(ElectricityVat.format(pricing.ownFixedAt(slot.timestamp), Locale.ROOT))
                val sign = shownOwn.compareTo(shownSpot)
                val mark = when {
                    sign > 0 -> FixedMark.SPOT_CHEAPER
                    sign < 0 -> FixedMark.SPOT_DEARER
                    else -> FixedMark.EQUAL
                }
                assertEquals(label, mark, fixedMark(slot, pricing))
                assertEquals(label, (if (sign > 0) 1 else 0) to 1, fixedCheaperCount(listOf(slot), pricing))
                val c = fixedHeroCompare(ElectricityHero.CURRENT, slot, listOf(slot), pricing)!!
                val res = when (mark) {
                    FixedMark.SPOT_CHEAPER -> R.string.electricity_fixed_hero_cheaper
                    FixedMark.SPOT_DEARER -> R.string.electricity_fixed_hero_dearer
                    FixedMark.EQUAL -> R.string.electricity_fixed_hero_equal
                }
                assertEquals(label, res, fixedHeroRes(c))
                assertEquals(label, (shownOwn - shownSpot).abs().toPlainString(), ElectricityVat.format(Math.abs(c.diff), Locale.ROOT))
            }
        }
    }

    @Test fun dayAverageCard_comparesTheShownAverages() {
        // Huomenna-kortti näyttää päivän keskihinnan ja oman hinnan keskiarvon: ero on näiden näkyvien lukujen erotus.
        val c = fixedHeroCompare(ElectricityHero.DAY_AVERAGE, null, day, fixed)!!
        val shownSpot = BigDecimal(ElectricityVat.format(electricityDayAverage(day, fixed), Locale.ROOT))
        val shownOwn = BigDecimal(ElectricityVat.format(c.ownPrice, Locale.ROOT))
        assertEquals((shownOwn - shownSpot).toPlainString(), ElectricityVat.format(c.diff, Locale.ROOT))
        assertEquals(R.string.electricity_fixed_hero_avg_cheaper, fixedHeroRes(c))
    }

    @Test fun rows_carryFixedMark_onlyInCompareMode() {
        val rows = electricityPriceRows(day, ElectricityHourly.QUARTER_MS, fixed, null)
        assertEquals(FixedMark.SPOT_CHEAPER, rows.first().fixedMark)
        assertEquals(FixedMark.SPOT_DEARER, rows.last().fixedMark)
        assertTrue(electricityPriceRows(day, ElectricityHourly.QUARTER_MS, spotContract, null).all { it.fixedMark == null })
        assertTrue(electricityPriceRows(day, ElectricityHourly.QUARTER_MS, ElectricityPricing(true), null).all { it.fixedMark == null })
    }

    @Test fun rows_inCompareMode_showSpotWithOwnTransferAndTax() {
        // Vertailutilassa rivin hinta on pörssi + oma siirto + vero, ei kiinteä hinta.
        val row = electricityPriceRows(day, ElectricityHourly.QUARTER_MS, fixed, null).first()
        assertEquals(5.02 + 2.60 + 2.918, row.displayPrice, 1e-9)
    }
}
