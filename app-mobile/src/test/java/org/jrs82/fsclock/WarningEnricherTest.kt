package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningEnricherTest {

    private fun warn(event: String, type: WeatherWarning.AwarenessType, on: Long, ex: Long,
                     marine: Boolean = false) =
        WeatherWarning(event, "lyhyt", "Uusimaa", on, ex, WeatherWarning.Level.YELLOW,
            "id-$event", marine, type, "Moderate", "Likely", "Future", 0L, "FMI", "")

    private fun det(ctx: String, from: Long, until: Long, prob: Int, pv: Double,
                    unit: String, text: String) =
        FmiWarningDetail(ctx, from, until, prob, pv, unit, text)

    @Test fun matchesByTypeAndTime_picksRepresentativeFeatureCoherently() {
        val w = warn("Hellevaroitus", WeatherWarning.AwarenessType.HIGH_TEMPERATURE, 1000L, 5000L)
        val details = listOf(
            det("hot-weather", 1000L, 5000L, 30, 26.0, "celsius", "lyhyt fmi"),
            det("hot-weather", 1000L, 5000L, 40, 27.0, "celsius", "pidempi fmi teksti tähän"),
            det("rain", 1000L, 5000L, 99, 50.0, "mm/h", "eri tyyppi ei saa osua"),
        )
        val out = WarningEnricher.enrich(listOf(w), details)
        assertEquals(1, out.size)
        assertEquals(40, out[0].details.probabilityPct)
        assertEquals(27.0, out[0].details.physicalValue, 0.0)  // suurin arvo
        assertEquals("celsius", out[0].details.physicalUnit)
        assertEquals("pidempi fmi teksti tähän", out[0].details.detailText) // pisin
    }

    @Test fun coherentFields_probComesFromHighestPhysicalFeature() {
        val w = warn("Hellevaroitus", WeatherWarning.AwarenessType.HIGH_TEMPERATURE, 1000L, 5000L)
        val details = listOf(
            // korkein todennäköisyys mutta MATALAMPI lämpötila
            det("hot-weather", 1000L, 5000L, 90, 25.0, "celsius", "a"),
            // edustavin: korkein lämpötila 28 → sen prob 40 pitää näkyä (ei 90)
            det("hot-weather", 1000L, 5000L, 40, 28.0, "celsius", "b"),
        )
        val out = WarningEnricher.enrich(listOf(w), details)
        assertEquals(28.0, out[0].details.physicalValue, 0.0)
        assertEquals(40, out[0].details.probabilityPct)   // SAMASTA featuresta kuin lämpötila, ei 90
    }

    @Test fun noMatchKeepsEmptyDetails() {
        val w = warn("Hellevaroitus", WeatherWarning.AwarenessType.HIGH_TEMPERATURE, 1000L, 5000L)
        val details = listOf(det("rain", 1000L, 5000L, 30, 20.0, "mm/h", "sade"))
        val out = WarningEnricher.enrich(listOf(w), details)
        assertEquals(false, out[0].details.hasAny())
    }

    @Test fun nonOverlappingTimeDoesNotMatch() {
        val w = warn("Hellevaroitus", WeatherWarning.AwarenessType.HIGH_TEMPERATURE, 1000L, 2000L)
        val details = listOf(det("hot-weather", 9000L, 9999L, 40, 27.0, "celsius", "fmi"))
        val out = WarningEnricher.enrich(listOf(w), details)
        assertEquals(false, out[0].details.hasAny())
    }

    @Test fun marineMatchesSeaThunder() {
        val w = warn("Huomautus veneilijöille", WeatherWarning.AwarenessType.WIND, 1000L, 5000L, marine = true)
        val details = listOf(det("sea-thunder-storm", 1000L, 5000L, 30, Double.NaN, "", "Ukkospuuskia merellä."))
        val out = WarningEnricher.enrich(listOf(w), details)
        assertEquals(30, out[0].details.probabilityPct)
        assertTrue(out[0].details.detailText.contains("Ukkospuuskia"))
    }
}
