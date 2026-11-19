package org.jrs82.fsclock.mobile

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ElectricityOwnSettings]: omien sähköhintojen asetusten luku ja syötön tarkistus. */
class ElectricityOwnSettingsTest {
    private val s = ElectricityOwnSettings

    private fun enabledPrefs(): FakeSharedPreferences =
        FakeSharedPreferences().also { it.edit().putBoolean(s.KEY_ENABLED, true).apply() }

    @Test fun parsePrice_acceptsCommaDotAndSpaces() {
        assertEquals(4.1, s.parsePrice(" 4,10 ")!!, 1e-9)
        assertEquals(4.1, s.parsePrice("4.100")!!, 1e-9)
        assertEquals(0.0, s.parsePrice("")!!, 0.0)
        assertEquals(0.0, s.parsePrice("   ")!!, 0.0)
        assertEquals(100.0, s.parsePrice("100")!!, 0.0)
        assertEquals(0.59, s.parsePrice("0,59")!!, 1e-9)
    }

    @Test fun parsePrice_rejectsInvalid() {
        for (bad in listOf("-1", "100,001", "4,1234", "abc", "4,1 snt", "1e2", "NaN", "4,1,2", "+4", ",5", "4,")) {
            assertNull(bad, s.parsePrice(bad))
        }
    }

    @Test fun disabled_givesPlainVatPricing() {
        val prefs = FakeSharedPreferences()
        assertNull(s.own(prefs))
        assertEquals(ElectricityPricing(vat = true), s.pricing(prefs))
    }

    @Test fun pricing_followsTheVatSwitch() {
        val prefs = enabledPrefs()
        prefs.edit().putBoolean(ElectricityVat.KEY, false).apply()
        assertFalse(s.pricing(prefs).vat)
    }

    @Test fun enabledWithNothingEntered_isSpotGeneralZeroPlusDefaultTax() {
        val prefs = enabledPrefs()
        val own = s.own(prefs)!!
        assertEquals(OwnContract.SPOT, own.contract)
        assertEquals(0.0, own.energySnt, 0.0)
        assertEquals(TransferProduct.GENERAL, own.transfer.product)
        assertEquals(0.0, own.transfer.highSnt, 0.0)
        assertEquals(0.0, own.transfer.lowSnt, 0.0)
        assertEquals(7, own.transfer.startHour)
        assertEquals(22, own.transfer.endHour)
        assertEquals(2.918, own.taxSnt, 1e-9)
        assertFalse(s.isSet(prefs, s.KEY_MARGIN))
    }

    @Test fun isSet_isTrueOnlyForAValidStoredValue() {
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_MARGIN, "0,59").putString(s.KEY_TRANSFER_HIGH, "abc").apply()
        assertTrue(s.isSet(prefs, s.KEY_MARGIN))
        assertFalse(s.isSet(prefs, s.KEY_TRANSFER_HIGH))
    }

    @Test fun contractSelectsMarginOrFixedPrice() {
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_MARGIN, "0,59").putString(s.KEY_FIXED_PRICE, "7,0").apply()
        assertEquals(0.59, s.own(prefs)!!.energySnt, 1e-9)
        prefs.edit().putString(s.KEY_CONTRACT, "fixed").apply()
        val fixed = s.own(prefs)!!
        assertEquals(OwnContract.FIXED, fixed.contract)
        assertEquals(7.0, fixed.energySnt, 1e-9)
    }

    @Test fun transferPricesAndProductAreRead() {
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_TRANSFER_PRODUCT, "time")
            .putString(s.KEY_TRANSFER_HIGH, "4,10").putString(s.KEY_TRANSFER_LOW, "2.6")
            .putInt(s.KEY_TRANSFER_START, 6).putInt(s.KEY_TRANSFER_END, 23).apply()
        assertEquals(
            OwnTransfer(TransferProduct.TIME, 4.10, 2.6, 6, 23, TransferDays.EVERY_DAY),
            s.own(prefs)!!.transfer,
        )
    }

    @Test fun taxOff_isZero_andEditedTaxIsUsed() {
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_TAX, "2,79").apply()
        assertEquals(2.79, s.own(prefs)!!.taxSnt, 1e-9)
        prefs.edit().putBoolean(s.KEY_TAX_ENABLED, false).apply()
        assertEquals(0.0, s.own(prefs)!!.taxSnt, 0.0)
    }

    @Test fun daysDefaultFollowsProduct() {
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_TRANSFER_PRODUCT, "time").apply()
        assertEquals(TransferDays.EVERY_DAY, s.own(prefs)!!.transfer.days)
        prefs.edit().putString(s.KEY_TRANSFER_PRODUCT, "season").apply()
        assertEquals(TransferDays.MON_SAT, s.own(prefs)!!.transfer.days)
        prefs.edit().putString(s.KEY_TRANSFER_DAYS, "mon_fri").apply()
        assertEquals(TransferDays.MON_FRI, s.own(prefs)!!.transfer.days)
    }

    @Test fun changingTheProduct_resetsTheDayChoiceToTheProductDefault() {
        // Aikasiirrolle tallennettu "joka päivä" ei saa siirtyä kausisiirtoon, jonka oletus on ma–la.
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_TRANSFER_PRODUCT, "time").putString(s.KEY_TRANSFER_DAYS, "all")
            .putInt(s.KEY_TRANSFER_START, 6).putInt(s.KEY_TRANSFER_END, 23).apply()
        s.saveProduct(prefs, s.PRODUCT_SEASON)
        val season = s.own(prefs)!!.transfer
        assertEquals(TransferProduct.SEASON, season.product)
        assertEquals(TransferDays.MON_SAT, season.days)
        assertEquals(6 to 23, season.startHour to season.endHour) // tunnit säilyvät
    }

    @Test fun savingTheSameProduct_keepsTheDayChoice() {
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_TRANSFER_PRODUCT, "time").putString(s.KEY_TRANSFER_DAYS, "mon_fri").apply()
        s.saveProduct(prefs, s.PRODUCT_TIME)
        assertEquals(TransferDays.MON_FRI, s.own(prefs)!!.transfer.days)
    }

    @Test fun garbageStoredValues_fallBackWithoutThrowing() {
        // Esimerkiksi käsin muokattu tai toisesta versiosta palautettu varmuuskopio.
        val prefs = enabledPrefs()
        prefs.edit().putString(s.KEY_MARGIN, "abc").putString(s.KEY_TRANSFER_HIGH, "-5")
            .putString(s.KEY_TRANSFER_LOW, "1e9").putString(s.KEY_TAX, "")
            .putString(s.KEY_CONTRACT, "x").putString(s.KEY_TRANSFER_PRODUCT, "y")
            .putString(s.KEY_TRANSFER_DAYS, "z").apply()
        val own = s.own(prefs)!!
        assertEquals(OwnContract.SPOT, own.contract)
        assertEquals(0.0, own.energySnt, 0.0)
        assertEquals(OwnTransfer(TransferProduct.GENERAL, 0.0, 0.0), own.transfer)
        assertEquals(s.DEFAULT_TAX_SNT, own.taxSnt, 1e-9)
    }

    @Test fun wrongStoredTypes_fallBackWithoutThrowing() {
        // Oikea SharedPreferences heittää ClassCastExceptionin, jos avaimen tyyppi on väärä.
        val throwing = object : SharedPreferences by enabledPrefs() {
            override fun getString(key: String?, defValue: String?): String? = throw ClassCastException()
            override fun getInt(key: String?, defValue: Int): Int = throw ClassCastException()
        }
        val own = s.own(throwing)!!
        assertEquals(OwnPrices(OwnContract.SPOT, 0.0, OwnTransfer(TransferProduct.GENERAL, 0.0, 0.0), s.DEFAULT_TAX_SNT, energySet = false), own)
    }

    @Test fun invalidHours_fallBackTo7And22() {
        fun hours(start: Int, end: Int): Pair<Int, Int> {
            val prefs = enabledPrefs()
            prefs.edit().putInt(s.KEY_TRANSFER_START, start).putInt(s.KEY_TRANSFER_END, end).apply()
            val t = s.own(prefs)!!.transfer
            return t.startHour to t.endHour
        }
        assertEquals(7 to 22, hours(22, 7))
        assertEquals(7 to 22, hours(7, 7))
        assertEquals(7 to 22, hours(-1, 30))
        assertEquals(0 to 24, hours(0, 24))
        assertEquals(6 to 23, hours(6, 23))
    }

    @Test fun ownKeysRefreshHomeData() {
        assertTrue(isHomeDataPrefKey(s.KEY_MARGIN))
        assertTrue(isHomeDataPrefKey(s.KEY_ENABLED))
        assertTrue(isHomeDataPrefKey(s.KEY_TRANSFER_DAYS))
    }

    @Test fun keysAreNotExcludedFromBackup() {
        for (key in listOf(s.KEY_ENABLED, s.KEY_CONTRACT, s.KEY_MARGIN, s.KEY_FIXED_PRICE, s.KEY_TRANSFER_PRODUCT,
            s.KEY_TRANSFER_HIGH, s.KEY_TRANSFER_LOW, s.KEY_TRANSFER_START, s.KEY_TRANSFER_END, s.KEY_TRANSFER_DAYS,
            s.KEY_TAX_ENABLED, s.KEY_TAX)) {
            assertTrue(key, key.startsWith("mobile_electricity_own_"))
        }
    }
}
