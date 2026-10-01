package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceScaleTest {
    @Test fun fraction_isRelativeToDayRange() {
        assertEquals(0.5f, PriceScale.fraction(4.0, 1.0, 7.0)!!, 1e-6f)
        assertEquals(0.25f, PriceScale.fraction(2.5, 1.0, 7.0)!!, 1e-6f)
    }

    @Test fun fraction_dayMaxIsAtRightEnd_evenWhenAbsolutePriceIsLow() {
        // 6,83 c/kWh on päivän kallein vartti → oikea reuna, vaikka kiinteällä 0–30-asteikolla se olisi 23 %.
        assertEquals(0.98f, PriceScale.fraction(6.83, 1.053, 6.83)!!, 1e-6f)
    }

    @Test fun fraction_clampsToKeepPointerOnBar() {
        assertEquals(0.02f, PriceScale.fraction(1.0, 1.0, 7.0)!!, 1e-6f)
        assertEquals(0.02f, PriceScale.fraction(-3.0, 1.0, 7.0)!!, 1e-6f)
        assertEquals(0.98f, PriceScale.fraction(9.0, 1.0, 7.0)!!, 1e-6f)
    }

    @Test fun fraction_handlesNegativePrices() {
        assertEquals(0.5f, PriceScale.fraction(0.0, -2.0, 2.0)!!, 1e-6f)
    }

    @Test fun fraction_nullWithoutUsableRange() {
        assertNull(PriceScale.fraction(4.0, 5.0, 5.0))
        assertNull(PriceScale.fraction(4.0, 7.0, 1.0))
        assertNull(PriceScale.fraction(Double.NaN, 1.0, 7.0))
        assertNull(PriceScale.fraction(4.0, Double.NaN, 7.0))
        assertNull(PriceScale.fraction(4.0, 1.0, Double.NaN))
    }

    @Test fun labels_startMiddleEnd_withVat() {
        assertEquals(Triple("1,255", "5,020", "8,785"), PriceScale.labels(1.0, 7.0, true))
    }

    @Test fun labels_startMiddleEnd_withoutVat() {
        assertEquals(Triple("1,000", "4,000", "7,000"), PriceScale.labels(1.0, 7.0, false))
    }

    @Test fun labels_dashWithoutData() {
        assertEquals(Triple("–", "–", "–"), PriceScale.labels(Double.NaN, Double.NaN, true))
    }
}
