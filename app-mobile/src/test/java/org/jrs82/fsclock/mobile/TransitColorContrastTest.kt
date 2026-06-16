package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.9.0 WCAG-värikorjausten regressiotestit. Kaksi osaa:
 * (1) [TransitStyle.onColorFor] — badgen automaattinen teksti­väri (puhdas bittilaskenta).
 * (2) Valittujen paletti-hexien kontrastit täyttävät tavoitteet (AAA ≥7:1 teksteille/bannereille,
 *     ≥4,5:1 badgeille) — vartioi ettei joku myöhemmin laske värin alle kynnyksen.
 * Hexit on tässä kovakoodattu (resurssit eivät näy unit-testissä) → ne PEILAAVAT mobile_colors.xml:ää.
 */
class TransitColorContrastTest {

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF1A1A1A.toInt()

    // HSL-moodivärit (ARGB).
    private val BUS = 0xFF007AC9.toInt()
    private val TRAM = 0xFF00985F.toInt()
    private val RAIL = 0xFF8C4799.toInt()
    private val SUBWAY = 0xFFFF6319.toInt()
    private val FERRY = 0xFF00B9E4.toInt()

    @Test
    fun `onColorFor valitsee valkoisen tummille ja mustan vaaleille moodivareille`() {
        assertEquals(WHITE, TransitStyle.onColorFor(BUS))
        assertEquals(WHITE, TransitStyle.onColorFor(RAIL))
        assertEquals(BLACK, TransitStyle.onColorFor(TRAM))
        assertEquals(BLACK, TransitStyle.onColorFor(SUBWAY))
        assertEquals(BLACK, TransitStyle.onColorFor(FERRY))
    }

    @Test
    fun `badgen paras tekstivari yltaa vahintaan AA 4_5-1 kaikille moodeille`() {
        for (bg in listOf(BUS, TRAM, RAIL, SUBWAY, FERRY)) {
            val r = ratio(TransitStyle.onColorFor(bg), bg)
            assertTrue("moodiväri ${(bg and 0xFFFFFF).toString(16)} kontrasti=$r", r >= 4.5)
        }
    }

    @Test
    fun `alert-bannerit valkoisella tekstilla yltavat AAA 7-1`() {
        assertTrue(ratio(WHITE, 0xFFA8160F.toInt()) >= 7.0)   // mobile_alert_severe
        assertTrue(ratio(WHITE, 0xFF8A3F0A.toInt()) >= 7.0)   // mobile_alert_warning
        assertTrue(ratio(WHITE, 0xFF6E4E00.toInt()) >= 7.0)   // mobile_alert_info
    }

    @Test
    fun `yli-korostetut tekstit vaalealla kortilla yltavat AAA 7-1`() {
        val card = WHITE
        assertTrue(ratio(0xFF49515D.toInt(), card) >= 7.0)    // text_muted
        assertTrue(ratio(0xFF1B45C0.toInt(), card) >= 7.0)    // accent
        assertTrue(ratio(0xFF09602E.toInt(), card) >= 7.0)    // route_depart
        assertTrue(ratio(0xFFAC150F.toInt(), card) >= 7.0)    // warning_red (viive)
        assertTrue(ratio(0xFF8A3F0A.toInt(), card) >= 7.0)    // warning_orange
        assertTrue(ratio(0xFF6E4E00.toInt(), card) >= 7.0)    // warning_yellow
    }

    @Test
    fun `yli-korostetut tekstit tummalla kortilla yltavat AAA 7-1`() {
        val card = 0xFF131B27.toInt()
        assertTrue(ratio(0xFFA8B2C0.toInt(), card) >= 7.0)    // text_muted (tumma)
        assertTrue(ratio(0xFFFF8980.toInt(), card) >= 7.0)    // warning_red (tumma)
    }

    // --- WCAG-kontrastin laskenta (suhteellinen luminanssi); alphalla ei väliä. ---
    private fun lin(c8: Int): Double {
        val c = c8 / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun lum(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    private fun ratio(fg: Int, bg: Int): Double {
        val a = lum(fg)
        val b = lum(bg)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }
}
