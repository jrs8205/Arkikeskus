package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElectricityVatTest {
    @Test fun apply_addsFinnishVat_whenEnabled() {
        assertEquals(12.55, ElectricityVat.apply(10.0, true), 1e-9)
    }

    @Test fun apply_leavesPriceUntouched_whenDisabled() {
        assertEquals(10.0, ElectricityVat.apply(10.0, false), 0.0)
    }

    @Test fun apply_keepsNaN() {
        assertTrue(ElectricityVat.apply(Double.NaN, true).isNaN())
    }

    @Test fun apply_leavesNegativeAndZeroPriceUntaxed() {
        // Negatiivisesta hinnasta ei synny arvonlisäverotettavaa kulua; hyvitys on veroton.
        assertEquals(-1.0, ElectricityVat.apply(-1.0, true), 0.0)
        assertEquals(0.0, ElectricityVat.apply(0.0, true), 0.0)
    }

    private val fi = AppLocale.FINNISH

    @Test fun format_alwaysThreeDecimalsWithFinnishComma() {
        assertEquals("5,020", ElectricityVat.format(ElectricityVat.apply(4.0, true), fi))
        assertEquals("12,550", ElectricityVat.format(ElectricityVat.apply(10.0, true), fi))
        assertEquals("1,255", ElectricityVat.format(ElectricityVat.apply(1.0, true), fi))
        assertEquals("0,000", ElectricityVat.format(0.0, fi))
        assertEquals("4,000", ElectricityVat.format(4.0, fi))
    }

    @Test fun format_followsLocaleDecimalSeparator() {
        assertEquals("5.020", ElectricityVat.format(ElectricityVat.apply(4.0, true), AppLocale.ENGLISH))
        assertEquals("5,020", ElectricityVat.format(ElectricityVat.apply(4.0, true), AppLocale.SWEDISH))
        assertEquals("-0.500", ElectricityVat.format(-0.5, AppLocale.ENGLISH))
    }

    @Test fun format_nanIsDash() {
        assertEquals("–", ElectricityVat.format(Double.NaN, fi))
        assertEquals("–", ElectricityVat.format(Double.NaN, AppLocale.ENGLISH))
    }

    @Test fun format_noMinusZero() {
        assertEquals("0,000", ElectricityVat.format(-0.0001, fi))
        assertEquals("0.000", ElectricityVat.format(-0.0001, AppLocale.ENGLISH))
    }

    @Test fun enabled_defaultsToTrue() {
        assertTrue(ElectricityVat.enabled(FakeSharedPreferences()))
    }

    @Test fun enabled_readsPreference() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putBoolean(ElectricityVat.KEY, false).apply()
        assertFalse(ElectricityVat.enabled(prefs))
    }

    @Test fun label_describesMode() {
        assertEquals(R.string.electricity_vat_label_incl, ElectricityVat.labelRes(true))
        assertEquals(R.string.electricity_vat_label_zero, ElectricityVat.labelRes(false))
    }
}

class ElectricityVatNoteTest {
    @Test fun priceNote_saysIncluded_whenEnabled() {
        assertEquals(R.string.electricity_vat_note_incl, ElectricityVat.priceNoteRes(true))
    }

    @Test fun priceNote_saysExcluded_whenDisabled() {
        assertEquals(R.string.electricity_vat_note_excl, ElectricityVat.priceNoteRes(false))
    }

    @Test fun widgetNote_isCompact() {
        // Päällä widget käyttää samaa tiivistä merkintää kuin hintojen ALV-merkintä.
        assertEquals(R.string.electricity_vat_label_incl, ElectricityVat.widgetNoteRes(true))
        assertEquals(R.string.electricity_vat_widget_excl, ElectricityVat.widgetNoteRes(false))
    }
}
