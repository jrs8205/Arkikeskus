package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Kiinteän sopimuksen vertailutilan tekstien ja kuvakkeiden valinta. */
class ElectricityFixedCompareTextsTest {

    // FixedHeroCompare(oma hinta, pörssisähkön hinta, päivän keskiarvo): teksti seuraa näytettyjä hintoja.

    @Test fun heroText_followsTheShownPrices() {
        assertEquals(R.string.electricity_fixed_hero_cheaper, fixedHeroRes(FixedHeroCompare(14.0, 10.8, false)))
        assertEquals(R.string.electricity_fixed_hero_dearer, fixedHeroRes(FixedHeroCompare(14.0, 17.2, false)))
        assertEquals(R.string.electricity_fixed_hero_equal, fixedHeroRes(FixedHeroCompare(14.0, 14.0002, false)))
        // Pienikin näkyvä ero ratkaisee: 14,000 ja 13,999.
        assertEquals(R.string.electricity_fixed_hero_cheaper, fixedHeroRes(FixedHeroCompare(14.0, 13.9994, false)))
        assertEquals(R.string.electricity_fixed_hero_dearer, fixedHeroRes(FixedHeroCompare(14.0, 14.0006, false)))
    }

    @Test fun heroText_usesAverageWording_forTheDayAverage() {
        assertEquals(R.string.electricity_fixed_hero_avg_cheaper, fixedHeroRes(FixedHeroCompare(14.0, 10.8, true)))
        assertEquals(R.string.electricity_fixed_hero_avg_dearer, fixedHeroRes(FixedHeroCompare(14.0, 17.2, true)))
        assertEquals(R.string.electricity_fixed_hero_avg_equal, fixedHeroRes(FixedHeroCompare(14.0, 13.9998, true)))
    }

    @Test fun summaryText_namesQuartersOrHours() {
        assertEquals(R.string.electricity_fixed_summary_quarters, fixedSummaryRes(hourly = false))
        assertEquals(R.string.electricity_fixed_summary_hours, fixedSummaryRes(hourly = true))
    }

    @Test fun rowMark_hasADistinctIconAndDescription_forEachCase() {
        assertEquals(R.string.electricity_fixed_row_cheaper, fixedRowDescriptionRes(FixedMark.SPOT_CHEAPER))
        assertEquals(R.string.electricity_fixed_row_dearer, fixedRowDescriptionRes(FixedMark.SPOT_DEARER))
        assertEquals(R.string.electricity_fixed_row_equal, fixedRowDescriptionRes(FixedMark.EQUAL))
        // Merkki ei saa nojata väriin: jokaisella tapauksella on oma kuvakkeensa.
        assertEquals(3, FixedMark.entries.map { fixedMarkIconRes(it) }.toSet().size)
        assertEquals(R.drawable.mobile_ic_arrow_down_24, fixedMarkIconRes(FixedMark.SPOT_CHEAPER))
        assertEquals(R.drawable.mobile_ic_arrow_up_24, fixedMarkIconRes(FixedMark.SPOT_DEARER))
    }

    @Test fun heroTexts_sayCheaperOrDearer_inEveryLanguage() {
        // Luetaan resurssit suoraan: halvempi-teksti ei saa sanoa kalliimpi eikä päinvastoin.
        val words = mapOf(
            "values" to ("cheaper" to "more expensive"),
            "values-fi" to ("halvempi" to "kalliimpi"),
            "values-sv" to ("billigare" to "dyrare"),
        )
        for ((dir, w) in words) {
            val xml = File("src/main/res/$dir/strings_electricity.xml").readText()
            fun body(name: String) = Regex("<string name=\"$name\">([^<]*)</string>").find(xml)!!.groupValues[1]
            for (prefix in listOf("electricity_fixed_hero", "electricity_fixed_hero_avg", "electricity_fixed_row")) {
                val cheaper = body("${prefix}_cheaper")
                val dearer = body("${prefix}_dearer")
                assertTrue("$dir $prefix: $cheaper", cheaper.contains(w.first))
                assertFalse("$dir $prefix: $cheaper", cheaper.contains(w.second))
                assertTrue("$dir $prefix: $dearer", dearer.contains(w.second))
                assertFalse("$dir $prefix: $dearer", dearer.contains(w.first))
            }
        }
    }
}
