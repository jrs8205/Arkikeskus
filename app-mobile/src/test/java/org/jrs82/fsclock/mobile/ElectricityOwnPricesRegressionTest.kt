package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Omien sähköhintojen katselmointilöydösten (2.30.0, kierros 2) regressiotestit. */
class ElectricityOwnPricesRegressionTest {
    private val s = ElectricityOwnSettings

    private fun q(hour: Int, minute: Int, snt: Double): ElectricityData.Quarter = ElectricityData.quarter(
        LocalDateTime.of(2026, 11, 17, hour, minute).atZone(ElectricityTime.HELSINKI).toInstant().toEpochMilli(), snt,
    )

    private fun enabledPrefs(): FakeSharedPreferences =
        FakeSharedPreferences().also { it.edit().putBoolean(s.KEY_ENABLED, true).apply() }

    // --- Varmuuskopion palautus: omat hinnat palautetaan kokonaisuutena ---

    @Test fun restore_clearsOwnPriceKeysMissingFromTheBackup() {
        val existing = setOf(s.KEY_ENABLED, s.KEY_CONTRACT, s.KEY_FIXED_PRICE, ElectricityVat.KEY, "news_onboarding_done")
        val backup = setOf(s.KEY_ENABLED, s.KEY_MARGIN, ElectricityVat.KEY)
        assertEquals(setOf(s.KEY_CONTRACT, s.KEY_FIXED_PRICE), s.keysToClearOnRestore(existing, backup))
    }

    @Test fun restore_keepsOwnPrices_whenTheBackupHasNone() {
        val existing = setOf(s.KEY_ENABLED, s.KEY_CONTRACT, s.KEY_FIXED_PRICE)
        assertEquals(emptySet<String>(), s.keysToClearOnRestore(existing, setOf(ElectricityVat.KEY, "news_onboarding_done")))
    }

    @Test fun restore_ofASpotContractOverAFixedOne_givesTheSpotContract() {
        // Asennuksessa on kiinteä sopimus. Kopiossa on vain kytkin ja marginaali, koska pörssisopimus on
        // oletus eikä sitä tallenneta: palautuksen jälkeen sopimuksen pitää olla pörssisähkö.
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_CONTRACT, s.CONTRACT_FIXED).putString(s.KEY_FIXED_PRICE, "7.0").apply()
        val backupKeys = setOf(s.KEY_ENABLED, s.KEY_MARGIN)
        val editor = prefs.edit()
        for (key in s.keysToClearOnRestore(prefs.all.keys, backupKeys)) editor.remove(key)
        editor.putBoolean(s.KEY_ENABLED, true).putString(s.KEY_MARGIN, "0.59").apply()

        val own = s.own(prefs)!!
        assertEquals(OwnContract.SPOT, own.contract)
        assertEquals(0.59, own.energySnt, 1e-9)
    }

    // --- Kiinteä hinta: syötetty nolla on eri asia kuin puuttuva hinta ---

    @Test fun enteredZeroFixedPrice_isComparedLikeAnyOtherPrice() {
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_CONTRACT, s.CONTRACT_FIXED).putString(s.KEY_FIXED_PRICE, "0").apply()
        val pricing = s.pricing(prefs)
        assertTrue(pricing.fixedPriceSet)
        // Negatiivinen pörssihinta −1,0 on nollahintaa halvempi.
        val negative = q(12, 0, -1.0)
        assertEquals(1.0, fixedHeroCompare(ElectricityHero.CURRENT, negative, listOf(negative), pricing)!!.diff, 1e-9)
        assertEquals(FixedMark.SPOT_CHEAPER, fixedMark(negative, pricing))
        assertEquals(FixedMark.SPOT_DEARER, fixedMark(q(12, 0, 4.0), pricing))
    }

    @Test fun missingFixedPrice_isNotCompared() {
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_CONTRACT, s.CONTRACT_FIXED).apply()
        val pricing = s.pricing(prefs)
        assertTrue(pricing.fixedCompare)
        assertFalse(pricing.fixedPriceSet)
    }

    // --- Hinnan tallennus: muuttamaton oletusvero ei jää tallennetuksi ---

    @Test fun savingTheUnchangedDefaultTax_leavesItUnset() {
        val prefs = enabledPrefs()
        assertTrue(s.savePrice(prefs, s.KEY_TAX, "2,918", s.DEFAULT_TAX_SNT))
        assertFalse(prefs.contains(s.KEY_TAX)) // oletus seuraa edelleen sovelluspäivityksiä
    }

    @Test fun savingAChangedTax_storesIt() {
        val prefs = enabledPrefs()
        assertTrue(s.savePrice(prefs, s.KEY_TAX, "2,79", s.DEFAULT_TAX_SNT))
        assertEquals(2.79, s.storedPrice(prefs, s.KEY_TAX, s.DEFAULT_TAX_SNT), 1e-9)
    }

    @Test fun savingTheDefaultOverAnEditedTax_storesIt() {
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_TAX, "2.79").apply()
        assertTrue(s.savePrice(prefs, s.KEY_TAX, "2,918", s.DEFAULT_TAX_SNT))
        assertTrue(prefs.contains(s.KEY_TAX))
        assertEquals(2.918, s.storedPrice(prefs, s.KEY_TAX), 1e-9)
    }

    @Test fun savePrice_rejectsInvalidInput_andEmptyClearsTheValue() {
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_MARGIN, "0.59").apply()
        assertFalse(s.savePrice(prefs, s.KEY_MARGIN, "-1"))
        assertEquals(0.59, s.storedPrice(prefs, s.KEY_MARGIN), 1e-9)
        assertTrue(s.savePrice(prefs, s.KEY_MARGIN, " 0,45 "))
        assertEquals(0.45, s.storedPrice(prefs, s.KEY_MARGIN), 1e-9)
        assertTrue(s.savePrice(prefs, s.KEY_MARGIN, ""))
        assertFalse(prefs.contains(s.KEY_MARGIN))
    }

    // --- Erittely: näytetyt osat summautuvat aina näytettyyn kokonaishintaan ---

    @Test fun breakdown_shownPartsAddUpToTheShownTotal_atRoundingBoundaries() {
        // Veroton pörssihinta 0,300 antoi ALV:n kanssa 0,376 + 0,590 + 4,100 + 2,918 = 7,984, vaikka
        // kokonaishinta näkyi 7,985. Käydään läpi tiheä hintaväli (myös negatiiviset) molemmilla ALV-tiloilla.
        val fi = AppLocale.FINNISH
        val own = OwnPrices(OwnContract.SPOT, 0.59, OwnTransfer(TransferProduct.GENERAL, 4.10, 0.0), 2.918)
        for (vat in listOf(true, false)) {
            val pricing = ElectricityPricing(vat, own)
            for (i in 0..3000) {
                val slot = q(12, 0, i / 1000.0 - 0.5)
                val parts = electricityBreakdown(ElectricityHero.CURRENT, slot, pricing)!!
                val shownSum = parts.sumOf { ElectricityVat.format(it.second, fi).replace(',', '.').toDouble() }
                assertEquals(
                    "ALV=$vat pörssi=${slot.sntPerKwh}",
                    ElectricityVat.format(pricing.shown(slot), fi), ElectricityVat.format(shownSum, fi),
                )
            }
        }
    }
}
