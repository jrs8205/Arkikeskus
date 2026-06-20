package org.jrs82.fsclock.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class WidgetFormatTest {
    @Test fun priceLevel_thresholds() {
        assertEquals(PriceLevel.CHEAP, WidgetFormat.priceLevel(2.0, 5.0))
        assertEquals(PriceLevel.NORMAL, WidgetFormat.priceLevel(5.0, 5.0))   // not < threshold
        assertEquals(PriceLevel.NORMAL, WidgetFormat.priceLevel(10.0, 5.0))
        assertEquals(PriceLevel.EXPENSIVE, WidgetFormat.priceLevel(15.01, 5.0))
        assertEquals(PriceLevel.NORMAL, WidgetFormat.priceLevel(Double.NaN, 5.0))
    }
    @Test fun priceLabel_text() {
        assertEquals("Halpaa", WidgetFormat.priceLabel(PriceLevel.CHEAP))
        assertEquals("Normaali", WidgetFormat.priceLabel(PriceLevel.NORMAL))
        assertEquals("Kallista", WidgetFormat.priceLabel(PriceLevel.EXPENSIVE))
    }
    @Test fun stepsPercent_capsAt100_andZeroGoal() {
        assertEquals(50, WidgetFormat.stepsPercent(5000, 10000))
        assertEquals(100, WidgetFormat.stepsPercent(12000, 10000))
        assertEquals(0, WidgetFormat.stepsPercent(0, 10000))
        assertEquals(0, WidgetFormat.stepsPercent(5000, 0))   // guard div-by-zero
    }
    @Test fun minutesUntil_andLabel() {
        assertEquals(0, WidgetFormat.minutesUntil(1000, 1000))
        assertEquals(0, WidgetFormat.minutesUntil(1000, 2000))     // past -> 0, never negative
        assertEquals(5, WidgetFormat.minutesUntil(1000 + 5 * 60, 1000))
        assertEquals("nyt", WidgetFormat.minutesLabel(0))
        assertEquals("7 min", WidgetFormat.minutesLabel(7))
    }
    @Test fun tempLabel_roundsAndHandlesNaN() {
        assertEquals("17 °C", WidgetFormat.tempLabel(17.4))
        assertEquals("18 °C", WidgetFormat.tempLabel(17.6))
        assertEquals("–", WidgetFormat.tempLabel(Double.NaN))
    }
    /** cleanZero: arvot joilla |x| < 0.5 tulevat "0 °C", ei "-0 °C" tai "-1 °C". */
    @Test fun tempLabel_cleanZero_noMinusZeroArtifact() {
        // Positiiviset tapaukset
        assertEquals("0 °C", WidgetFormat.tempLabel(0.0))
        assertEquals("0 °C", WidgetFormat.tempLabel(-0.3))
        assertEquals("0 °C", WidgetFormat.tempLabel(-0.49))
        assertEquals("0 °C", WidgetFormat.tempLabel(0.49))
        // Ei saa alkaa "-0"
        assert(!WidgetFormat.tempLabel(-0.3).startsWith("-")) { "Ei saa tuottaa -0-artefaktia: ${WidgetFormat.tempLabel(-0.3)}" }
        // Realistiset arvot toimivat oikein
        assertEquals("-3 °C", WidgetFormat.tempLabel(-3.4))
        assertEquals("-1 °C", WidgetFormat.tempLabel(-0.7))
        assertEquals("1 °C", WidgetFormat.tempLabel(0.7))
        assertEquals("–", WidgetFormat.tempLabel(Double.NaN))
    }
    @Test fun clockLabel_formatsHHmm() {
        // 2026-06-20 10:11 Europe/Helsinki = 1750403460000 ms
        assertEquals("10.11", WidgetFormat.clockLabel(1750403460000L, ZoneId.of("Europe/Helsinki")))
    }
    @Test fun departures_roundTrip() {
        val list = listOf(
            WidgetFormat.DepartureLine("550", "TRAM", 1750000000L),
            WidgetFormat.DepartureLine("H305", "RAIL", 1750000600L),
        )
        val json = WidgetFormat.encodeDepartures(list)
        val back = WidgetFormat.decodeDepartures(json)
        assertEquals(list, back)
        assertEquals(emptyList<WidgetFormat.DepartureLine>(), WidgetFormat.decodeDepartures("[]"))
        assertEquals(emptyList<WidgetFormat.DepartureLine>(), WidgetFormat.decodeDepartures("garbage"))
    }
}
