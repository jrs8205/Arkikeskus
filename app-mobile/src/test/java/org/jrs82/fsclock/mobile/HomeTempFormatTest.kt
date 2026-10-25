package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Etusivun lämpötilateksti: asteen merkki mukana kaikilla kielillä ("5,1 C" → "5,1 °C"). */
class HomeTempFormatTest {

    @Test fun finnishUsesCommaAndDegreeSign() {
        assertEquals("5,1 °C", formatTemp(Locale("fi", "FI"), 5.1))
    }

    @Test fun swedishUsesCommaAndDegreeSign() {
        assertEquals("-3,0 °C", formatTemp(Locale("sv", "FI"), -3.0))
    }

    @Test fun englishUsesPointAndDegreeSign() {
        assertEquals("12.4 °C", formatTemp(Locale.UK, 12.4))
    }

    @Test fun negativeZeroIsShownAsZero() {
        assertEquals("0,0 °C", formatTemp(Locale("fi", "FI"), -0.04))
    }

    @Test fun smallTemperaturesKeepTheirDecimal() {
        // Tabletin cleanZero nollaa |x| < 0,5 kokonaislukunäyttöä varten; yhden desimaalin näytössä 0,3 on 0,3.
        assertEquals("0,3 °C", formatTemp(Locale("fi", "FI"), 0.3))
        assertEquals("-0,3 °C", formatTemp(Locale("fi", "FI"), -0.3))
        assertEquals("-0,1 °C", formatTemp(Locale("fi", "FI"), -0.05))
    }

    @Test fun missingValueKeepsUnit() {
        assertEquals("-- °C", formatTemp(Locale("fi", "FI"), Double.NaN))
        assertEquals("-- °C", formatNullableTemp(Locale("fi", "FI"), null))
    }
}
