package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class FontScaleCapTest {
    @Test fun scaleAboveCapIsCapped() = assertEquals(1.5f, FontScaleCap.effective(2.0f))
    @Test fun scaleAtCapIsKept() = assertEquals(1.5f, FontScaleCap.effective(1.5f))
    @Test fun scaleBelowCapIsKept() = assertEquals(1.3f, FontScaleCap.effective(1.3f))

    @Test fun widgetSpUnchangedBelowCap() {
        assertEquals(14f, FontScaleCap.widgetSpLinear(14f, 1.0f), 0.0001f)
        assertEquals(14f, FontScaleCap.widgetSpLinear(14f, 1.5f), 0.0001f)
    }

    /** 200 %:n järjestelmäkoossa 14 sp → 10,5 sp, joka piirtyy 21 dp:ksi = 14 sp × 1,5. */
    @Test fun widgetSpShrinksAboveCap() = assertEquals(10.5f, FontScaleCap.widgetSpLinear(14f, 2.0f), 0.0001f)
}
