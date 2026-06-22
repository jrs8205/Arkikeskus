package org.jrs82.fsclock.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Sijaintiportin (ikä + tarkkuus) ja lähteenvalinnan (tuorein voittaa) yksikkötestit.
 *  Arvot poimittu Pixel 8a:n kenttämittauksesta (loc_measure.csv). */
class WidgetLocationTest {
    private val now = 1782128000000L
    private fun cand(src: String, ageSec: Long, accM: Float?) =
        WidgetLocation.Candidate(src, now - ageSec * 1000L, accM)
    private val maxAge = 30L * 60_000L // 30 min
    private val maxAcc = 2000f

    @Test fun picksFreshestValidSource() {
        // Kenttädata: GPS 5,5 vrk + NET 2,9 h hylätään (liian vanhat), fused 112 s voittaa.
        val chosen = WidgetLocation.choose(
            listOf(cand("GPS", 478950, 39f), cand("NET", 10510, 13f), cand("FUSED", 112, 100f)),
            now, maxAge, maxAcc,
        )
        assertEquals("FUSED", chosen?.source)
    }

    @Test fun rejectsStaleFix() {
        assertNull(WidgetLocation.choose(listOf(cand("GPS", 478950, 39f)), now, maxAge, maxAcc))
    }

    @Test fun rejectsInaccurateFix() {
        assertNull(WidgetLocation.choose(listOf(cand("FUSED", 60, 5000f)), now, maxAge, maxAcc))
    }

    @Test fun acceptsNullAccuracy() {
        // Tarkkuus tuntematon → ei hylätä pelkän tarkkuuden takia.
        assertEquals("NET", WidgetLocation.choose(listOf(cand("NET", 60, null)), now, maxAge, maxAcc)?.source)
    }

    @Test fun picksNewestAmongValid() {
        val chosen = WidgetLocation.choose(
            listOf(cand("NET", 600, 20f), cand("FUSED", 120, 50f)), now, maxAge, maxAcc,
        )
        assertEquals("FUSED", chosen?.source)
    }

    @Test fun rejectsFutureFix() {
        // Negatiivinen ikä (kelloheitto) → hylätään.
        assertNull(WidgetLocation.choose(listOf(cand("GPS", -60, 10f)), now, maxAge, maxAcc))
    }

    @Test fun emptyListReturnsNull() {
        assertNull(WidgetLocation.choose(emptyList(), now, maxAge, maxAcc))
    }
}
