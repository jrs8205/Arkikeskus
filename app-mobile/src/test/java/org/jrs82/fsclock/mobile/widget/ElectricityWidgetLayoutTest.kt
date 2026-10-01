package org.jrs82.fsclock.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ElectricityWidgetLayoutTest {
    private val l = ElectricityWidgetLayout

    @Test fun choose_measuredDeviceSizes() {
        // Pixel 10 Pro (ARK-launcher): 300 dp, fonttiskaala 0,85 → väljä.
        assertSame(l.ROOMY, l.choose(300.3f, 0.85f))
        // Emulaattori (Pixel Launcher 4×2): 224 dp → tiivis.
        assertSame(l.COMPACT, l.choose(224f, 1.0f))
        // Pixel 8a (ARK-launcher): 183,6 dp — tiivis asettelu (≈ 208 dp) ei mahdu → varttirivi pois, jana jää.
        assertSame(l.TIGHT, l.choose(183.6f, 1.0f))
    }

    @Test fun choose_dropsGaugeAndHeaderWhenTooLow() {
        assertSame(l.NO_GAUGE, l.choose(150f, 1.0f))
        assertSame(l.PRICE_ONLY, l.choose(100f, 1.0f))
        assertSame(l.PRICE_ONLY, l.choose(0f, 1.0f))
    }

    @Test fun choose_largeFontNeedsMoreHeight() {
        assertSame(l.COMPACT, l.choose(224f, 1.0f))
        assertSame(l.TIGHT, l.choose(224f, 1.3f))
        assertSame(l.NO_GAUGE, l.choose(224f, 2.0f))
    }

    @Test fun chosenLayoutAlwaysFitsReportedHeight() {
        for (scale in listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 2.0f)) {
            var h = 60f
            while (h <= 420f) {
                val spec = l.choose(h, scale)
                if (spec !== l.PRICE_ONLY) {
                    assertTrue("h=$h scale=$scale", l.neededHeight(spec, scale) <= h)
                }
                h += 0.5f
            }
        }
    }

    @Test fun neededHeight_matchesMeasuredCompactLayout() {
        // Pixel 8a: tiiviin asettelun todellinen korkeus 208 dp; arvio ei saa jäädä sen alle.
        assertTrue(l.neededHeight(l.COMPACT, 1.0f) >= 208f)
        assertTrue(l.neededHeight(l.TIGHT, 1.0f) <= 183.6f)
    }

    @Test fun isNarrow_hidesChipBelowFourCells() {
        assertTrue(l.isNarrow(173f))
        assertFalse(l.isNarrow(360f))
    }

    @Test fun dotCenter_matchesAppFormula_andStaysInsideImage() {
        assertEquals(8f, l.dotCenterPx(0f, 1000, 16), 0f)
        assertEquals(992f, l.dotCenterPx(1f, 1000, 16), 0f)
        assertEquals(500f, l.dotCenterPx(0.5f, 1000, 16), 0f)
        var f = -0.5f
        while (f <= 1.5f) {
            val cx = l.dotCenterPx(f, 963, 48)
            assertTrue(cx - 24f >= 0f && cx + 24f <= 963f)
            f += 0.01f
        }
    }
}
