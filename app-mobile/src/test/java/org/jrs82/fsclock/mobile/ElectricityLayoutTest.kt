package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sähkösivun mahtumislaskenta: milloin osat ovat rinnakkain ja kuinka paljon iso hinta kutistuu. */
class ElectricityLayoutTest {

    @Test
    fun `osat mahtuvat riville kun leveydet ja valit mahtuvat`() {
        assertTrue(fitsInRow(listOf(100f, 100f, 100f), gapPx = 10f, maxWidthPx = 320f))
        assertFalse(fitsInRow(listOf(100f, 100f, 100f), gapPx = 10f, maxWidthPx = 319f))
    }

    @Test
    fun `yhta leveat osat mitoitetaan leveimman mukaan`() {
        // Summa 250 + 20 mahtuisi 330:een, mutta tasaleveinä jokainen saa vain 103 px < 150 px.
        assertFalse(fitsEqualColumns(listOf(50f, 150f, 50f), gapPx = 10f, maxWidthPx = 330f))
        assertTrue(fitsEqualColumns(listOf(50f, 150f, 50f), gapPx = 10f, maxWidthPx = 470f))
    }

    @Test
    fun `iso fontti pakottaa allekkain vaikka osat mahtuisivat`() {
        assertTrue(sideBySide(fontScale = 1.25f, fits = true))
        assertFalse(sideBySide(fontScale = 1.3f, fits = true))
        assertFalse(sideBySide(fontScale = 1.0f, fits = false))
    }

    @Test
    fun `iso hinta kutistuu suurimpaan mahtuvaan kokoon`() {
        assertEquals(40f, fitFontSizeSp(48f, 32f, 2f, availablePx = 400f) { it * 10f }, 0f)
    }

    @Test
    fun `iso hinta pysyy taysikokoisena kun tilaa on`() {
        assertEquals(48f, fitFontSizeSp(48f, 32f, 2f, availablePx = 1000f) { it * 10f }, 0f)
    }

    @Test
    fun `liian leveaa hintaa ei kutisteta alarajan alle`() {
        assertEquals(32f, fitFontSizeSp(48f, 32f, 2f, availablePx = 100f) { it * 10f }, 0f)
        // Esim. "-12,345" tai "123,456" 150 %:n fontilla: ei mahdu edes alarajalla → kutsuja siirtää yksikön alle.
        assertEquals(32f, fitFontSizeSp(48f, 32f, 2f, availablePx = 300f) { it * 12f }, 0f)
    }
}
