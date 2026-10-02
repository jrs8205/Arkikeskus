package org.jrs82.fsclock.mobile

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

    @Test fun format_alwaysThreeDecimalsWithFinnishComma() {
        assertEquals("5,020", ElectricityVat.format(ElectricityVat.apply(4.0, true)))
        assertEquals("12,550", ElectricityVat.format(ElectricityVat.apply(10.0, true)))
        assertEquals("1,255", ElectricityVat.format(ElectricityVat.apply(1.0, true)))
        assertEquals("0,000", ElectricityVat.format(0.0))
        assertEquals("4,000", ElectricityVat.format(4.0))
    }

    @Test fun format_nanIsDash() {
        assertEquals("–", ElectricityVat.format(Double.NaN))
    }

    @Test fun format_noMinusZero() {
        assertEquals("0,000", ElectricityVat.format(-0.0001))
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
        assertEquals("sis. ALV 25,5 %", ElectricityVat.label(true))
        assertEquals("ALV 0 %", ElectricityVat.label(false))
    }
}

class ElectricityVatNoteTest {
    @Test fun priceNote_saysIncluded_whenEnabled() {
        assertEquals("Hinta sisältää ALV:n 25,5 %", ElectricityVat.priceNote(true))
    }

    @Test fun priceNote_saysExcluded_whenDisabled() {
        assertEquals("Hinta ei sisällä ALV:tä (ALV 0 %)", ElectricityVat.priceNote(false))
    }

    @Test fun widgetNote_isCompact() {
        assertEquals("sis. ALV 25,5 %", ElectricityVat.widgetNote(true))
        assertEquals("ilman ALV:tä", ElectricityVat.widgetNote(false))
    }
}
