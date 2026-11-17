package org.jrs82.fsclock.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sähkösivun hintarivin tausta on päivän sisäinen lämpöväri, jonka päällä teksti on tummalla
 * musteella. Kontrastin pitää riittää tavalliselle tekstille (WCAG AA 4,5:1) koko asteikolla
 * molemmissa teemoissa: vaaleassa teemassa siksi käytetään vaaleita container-sävyjä.
 */
class ElectricityRowContrastTest {

    @Test
    fun `vaalean teeman riviasteikko on vaaleista container-savyista`() {
        assertEquals(LightArki.priceCheapContainer, LightArki.priceRowCheap)
        assertEquals(LightArki.priceNormalContainer, LightArki.priceRowNormal)
        assertEquals(LightArki.priceExpensiveContainer, LightArki.priceRowExpensive)
    }

    @Test
    fun `tumman teeman riviasteikko on nykyiset hintavarit`() {
        assertEquals(DarkArki.priceCheap, DarkArki.priceRowCheap)
        assertEquals(DarkArki.priceNormal, DarkArki.priceRowNormal)
        assertEquals(DarkArki.priceExpensive, DarkArki.priceRowExpensive)
    }

    @Test
    fun `muste erottuu koko asteikolla vaaleassa teemassa`() {
        assertInkContrast(LightArki, "vaalea")
    }

    @Test
    fun `muste erottuu koko asteikolla tummassa teemassa`() {
        assertInkContrast(DarkArki, "tumma")
    }

    @Test
    fun `tasainen paiva on halvan savyinen`() {
        assertEquals(LightArki.priceRowCheap, rowHeatColor(5.0, 5.0, 5.0, LightArki))
    }

    private fun assertInkContrast(colors: ArkiColors, theme: String) {
        for (n in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val bg = rowHeatColor(n * 10.0, 0.0, 10.0, colors)
            val r = ratio(PriceRowInk, bg)
            assertTrue("$theme n=$n tausta=${Integer.toHexString(bg.toArgb())} kontrasti=$r", r >= 4.5)
        }
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a.toArgb())
        val lb = luminance(b.toArgb())
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }
}
