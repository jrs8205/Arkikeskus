package org.jrs82.fsclock.mobile.widget

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PriceLevel { CHEAP, NORMAL, EXPENSIVE }

/** Puhtaat muotoilu-/johdantafunktiot widgeteille (yksikkötestattavia, ei Androidia). */
object WidgetFormat {
    private const val EXPENSIVE_THRESHOLD = 15.0 // c/kWh, sama kuin etusivun kortti
    private val HHMM = DateTimeFormatter.ofPattern("HH.mm", Locale("fi", "FI"))

    fun priceLevel(snt: Double, cheapThreshold: Double): PriceLevel = when {
        snt.isNaN() -> PriceLevel.NORMAL
        snt < cheapThreshold -> PriceLevel.CHEAP
        snt > EXPENSIVE_THRESHOLD -> PriceLevel.EXPENSIVE
        else -> PriceLevel.NORMAL
    }

    fun priceLabel(level: PriceLevel): String = when (level) {
        PriceLevel.CHEAP -> "Halpaa"
        PriceLevel.NORMAL -> "Normaali"
        PriceLevel.EXPENSIVE -> "Kallista"
    }

    fun stepsPercent(steps: Int, goal: Int): Int {
        if (goal <= 0) return 0
        return ((steps.toLong() * 100L) / goal).toInt().coerceIn(0, 100)
    }

    fun minutesUntil(departureEpochSec: Long, nowEpochSec: Long): Int {
        val diff = departureEpochSec - nowEpochSec
        if (diff <= 0) return 0
        return (diff / 60L).toInt()
    }

    fun minutesLabel(minutes: Int): String = if (minutes <= 0) "nyt" else "$minutes min"

    fun tempLabel(celsius: Double): String =
        if (celsius.isNaN()) "–" else "${Math.round(celsius)} °C"

    fun clockLabel(epochMs: Long, zone: ZoneId): String =
        HHMM.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
