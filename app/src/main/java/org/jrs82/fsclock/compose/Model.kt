package org.jrs82.fsclock.compose

import androidx.compose.ui.graphics.Color
import org.jrs82.fsclock.WeatherCondition

/* ---------------- Sivut ---------------- */

enum class Page { HOME, INFO, FORECAST, ELECTRICITY }

/* ---------------- Tilamalli ---------------- */

data class WeatherUi(
    val t: Float?, val cond: String, val feels: Float?, val wind: Float?, val hum: Float?, val precip: Float?,
    val condition: WeatherCondition? = null
)

data class SensorUi(val slot: String, val name: String, val t: Float?, val rh: Float?)

data class WarnUi(
    val main: String,
    val area: String,
    val validity: String,
    val description: String,
    val color: Color,
)

/** Yhden tunnin ennusterivi (FMI tai Open-Meteo; FMI:llä feels/hum = null). */
data class HourRowUi(
    val hour: Int,
    val temp: Float?,
    val feels: Float?,
    val wind: Float?,
    val hum: Float?,
    val precip: Float?,
    val condition: WeatherCondition?,
)

data class DayForecastUi(
    val label: String,                 // "ke 10.6."
    val fmi: Map<Int, HourRowUi>,
    val om: Map<Int, HourRowUi>,
    val hours: List<Int>,              // union, järjestyksessä
)

data class QuarterUi(val label: String, val snt: Float, val isNow: Boolean)

data class DayPricesUi(
    val label: String,                 // "Tänään" / "Huomenna"
    val quarters: List<QuarterUi>,
    val min: Float, val max: Float, val avg: Float,
    val minAt: String, val maxAt: String,
)

data class HomeUi(
    val time: String = "--:--",
    val sec: String = "--",
    val date: String = "—",
    val holiday: String? = null,
    val city: String = "—",
    val district: String = "",
    val wifiLevel: Int = 0,
    val wifiMbps: Int = 0,
    val wifiBand: String = "",
    val battPct: Int = 0,
    val battCharging: Boolean = false,
    val priceSnt: Float? = null,
    val fmi: WeatherUi? = null,
    val om: WeatherUi? = null,
    val sensors: List<SensorUi> = emptyList(),
    val sunRise: String = "—",
    val sunSet: String = "—",
    val dayLen: String = "",
    val sunriseMin: Int = -1,
    val sunsetMin: Int = -1,
    val moonLabel: String = "",
    val moonIllum: Int = -1,
    val moonPhase: Float = -1f,
    val warnings: List<WarnUi> = emptyList(),
    val warnAutoScroll: Boolean = true,
    val forecast: List<DayForecastUi> = emptyList(),
    val elToday: DayPricesUi? = null,
    val elTomorrow: DayPricesUi? = null,
)

fun sampleHomeUi() = HomeUi(
    time = "14:32", sec = "48", date = "maanantai 8.6.2026", holiday = "Juhannusaatto · pe 19.6.",
    city = "Vantaa", district = "Martinlaakso",
    wifiLevel = 5, wifiMbps = 300, wifiBand = "5 GHz", battPct = 100, battCharging = true,
    priceSnt = 7.128f,
    fmi = WeatherUi(17f, "Puolipilvistä", 16f, 4f, 62f, 0.0f),
    om = WeatherUi(18f, "Puolipilvistä", 17f, 5f, 60f, 0.1f),
    sensors = listOf(
        SensorUi("bedroom", "Makuuhuone", 21.42f, 38f), SensorUi("livingroom", "Olohuone", 22.08f, 41f), SensorUi("balcony", "Parveke", 17.83f, 55f)
    ),
    sunRise = "03:54", sunSet = "22:48", dayLen = "18 h 54 min",
    sunriseMin = 234, sunsetMin = 1368, moonLabel = "kasvava kuu", moonIllum = 62, moonPhase = 0.35f,
    warnings = listOf(
        WarnUi("Metsäpalovaroitus", "Uusimaa, Kanta-Häme", "voimassa 8.6. asti", "Metsäpalon vaara on suuri.", Color(0xFFE89B2C)),
        WarnUi("Ruohikkopalovaroitus", "Koko maa pois lukien Lappi", "", "", Color(0xFFE6C32E)),
        WarnUi("Hellevaroitus", "Etelä- ja Keski-Suomi", "voimassa 11.6. asti", "Korkein lämpötila on paikoin yli 27 astetta.", Color(0xFFE6C32E)),
    )
)

/* ---------------- Suomalainen numeromuotoilu ---------------- */

internal fun fi(n: Float?, dec: Int): String {
    if (n == null || n.isNaN()) return "–"
    return String.format("%.${dec}f", n).replace(".", ",")
}

internal fun fiUnit(n: Float?, dec: Int, unit: String): String =
    if (n == null || n.isNaN()) "–" else fi(n, dec) + unit
