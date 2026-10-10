package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.jrs82.fsclock.WarningDetails
import org.jrs82.fsclock.WeatherWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Säävaroitusten näyttöapurit (puhtaat, ei Composea/Androidia). */
class WarningDisplayTest {

    private val fi = Locale("fi", "FI")
    private val en = Locale.UK

    private fun warn(type: WeatherWarning.AwarenessType, value: Double, unit: String, prob: Int = -1) =
        WeatherWarning("x", "", "Uusimaa", 0L, 1L, WeatherWarning.Level.YELLOW, "id", false,
            type, "", "", "", 0L, "", "", WarningDetails(prob, value, unit, ""))

    @Test fun severityResources() {
        assertEquals(R.string.warning_severity_moderate, severityRes("Moderate"))
        assertEquals(R.string.warning_severity_extreme, severityRes("Extreme"))
        assertNull(severityRes(null))
        assertNull(severityRes("nonsense"))
    }

    @Test fun certaintyResources() {
        assertEquals(R.string.warning_certainty_likely, certaintyRes("Likely"))
        assertEquals(R.string.warning_certainty_possible, certaintyRes("possible"))
    }

    @Test fun urgencyResources() {
        assertEquals(R.string.warning_urgency_future, urgencyRes("Future"))
        assertEquals(R.string.warning_urgency_immediate, urgencyRes("Immediate"))
    }

    @Test fun periodBothEnds() {
        // onset 2025-06-22 20:24 EEST, expires 2025-06-23 00:00 EEST → "–"-väli, molemmat ajat.
        val onset = 1_750_613_040_000L
        val expires = 1_750_626_000_000L
        val s = warningPeriod(onset, expires, "d.M. HH:mm", fi)
        assertEquals(WarningUiText.Raw("22.6. 20:24 – 23.6. 00:00"), s)
    }

    @Test fun periodOnlyExpires() {
        val s = warningPeriod(0L, 1_750_626_000_000L, "d.M. HH:mm", fi)
        assertEquals(WarningUiText.Res(R.string.warning_period_until, listOf("23.6. 00:00")), s)
    }

    @Test fun periodOnlyOnset() {
        val s = warningPeriod(1_750_613_040_000L, 0L, "d MMM HH:mm", en)
        assertEquals(WarningUiText.Res(R.string.warning_period_from, listOf("22 Jun 20:24")), s)
    }

    @Test fun periodEmptyWhenNoTimes() {
        assertNull(warningPeriod(0L, 0L, "d.M. HH:mm", fi))
    }

    @Test fun physicalTextByUnit() {
        assertEquals(WarningUiText.Res(R.string.warning_physical_temperature, listOf("27")),
            physicalText(warn(WeatherWarning.AwarenessType.HIGH_TEMPERATURE, 27.0, "celsius"), fi))
        assertEquals(WarningUiText.Res(R.string.warning_physical_rain, listOf("20")),
            physicalText(warn(WeatherWarning.AwarenessType.RAIN, 20.0, "mm/h"), fi))
        assertEquals(WarningUiText.Res(R.string.warning_physical_gust, listOf("15")),
            physicalText(warn(WeatherWarning.AwarenessType.WIND, 15.0, "m/s"), fi))
        assertEquals(WarningUiText.Res(R.string.warning_physical_uv, listOf("6")),
            physicalText(warn(WeatherWarning.AwarenessType.UV, 6.0, "index"), fi))
        assertEquals(WarningUiText.Raw("4 meter"),
            physicalText(warn(WeatherWarning.AwarenessType.UNKNOWN, 4.0, "meter"), fi))
        assertNull(physicalText(warn(WeatherWarning.AwarenessType.RAIN, Double.NaN, "mm/h"), fi))
    }

    @Test fun coldUsesOwnPhrase() {
        assertEquals(WarningUiText.Res(R.string.warning_physical_cold, listOf("-30")),
            physicalText(warn(WeatherWarning.AwarenessType.LOW_TEMPERATURE, -30.0, "celsius"), en))
    }

    @Test fun decimalSeparatorFollowsLocale() {
        assertEquals("12,5", formatPhysicalValue(12.5, fi))
        assertEquals("12.5", formatPhysicalValue(12.5, en))
        assertEquals("27", formatPhysicalValue(27.0, en))
    }

    @Test fun probabilityTextOnlyWhenKnown() {
        assertEquals(WarningUiText.Res(R.string.warning_probability, listOf(40)), probabilityText(40))
        assertNull(probabilityText(-1))
    }

    @Test fun areaDescTranslatesOnlyKnownRegions() {
        val sv = mapOf("Uusimaa" to "Nyland", "Pirkanmaa" to "Birkaland")
        assertEquals("Nyland, Birkaland", localizeAreaDesc("Uusimaa, Pirkanmaa") { sv[it] })
        // kunnat ja merialueet jäävät syötteen muotoon
        assertEquals("Nyland, Kuusamo, Suomenlahden itäosa",
            localizeAreaDesc("Uusimaa,Kuusamo , Suomenlahden itäosa") { sv[it] })
        assertEquals("", localizeAreaDesc("") { sv[it] })
    }
}
