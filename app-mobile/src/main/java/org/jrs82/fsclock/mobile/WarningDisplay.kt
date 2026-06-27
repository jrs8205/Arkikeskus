package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.jrs82.fsclock.WeatherWarning.AwarenessType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Säävaroitusten näyttöapurit. Puhtaat funktiot (ikoniresurssi-int + suomenkieliset labelit
 *  + ajanjakson muotoilu) → yksikkötestattavissa ilman Composea. */

private val FI_WARN = Locale("fi", "FI")
private val HELSINKI_WARN: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

/** Ilmiötyyppi → drawable-ikoni. Tuntemattomille varoituskolmio. */
internal fun awarenessIconRes(type: AwarenessType): Int = when (type) {
    AwarenessType.WIND -> R.drawable.mobile_ic_wind_24
    AwarenessType.THUNDERSTORM -> R.drawable.mobile_ic_wx_thunder
    AwarenessType.SNOW_ICE, AwarenessType.LOW_TEMPERATURE -> R.drawable.mobile_ic_wx_snow
    AwarenessType.RAIN, AwarenessType.FLOOD -> R.drawable.mobile_ic_rain_24
    AwarenessType.FOG -> R.drawable.mobile_ic_wx_fog
    AwarenessType.HIGH_TEMPERATURE, AwarenessType.UV -> R.drawable.mobile_ic_wx_hot
    AwarenessType.FOREST_FIRE -> R.drawable.mobile_ic_wx_fire
    else -> R.drawable.mobile_ic_warning_24
}

internal fun severityFi(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "minor" -> "Vähäinen"
    "moderate" -> "Kohtalainen"
    "severe" -> "Vakava"
    "extreme" -> "Erittäin vakava"
    else -> ""
}

internal fun certaintyFi(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "observed" -> "Havaittu"
    "likely" -> "Todennäköinen"
    "possible" -> "Mahdollinen"
    "unlikely" -> "Epätodennäköinen"
    else -> ""
}

internal fun urgencyFi(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "immediate" -> "Välitön"
    "expected" -> "Odotettavissa"
    "future" -> "Tuleva"
    "past" -> "Mennyt"
    else -> ""
}

/** "alkaen X – Y" / "voimassa asti Y" / "alkaen X" / "" — Suomen aikavyöhyke. */
internal fun warningPeriod(onsetMs: Long, expiresMs: Long): String {
    if (onsetMs <= 0L && expiresMs <= 0L) return ""
    val fmt = SimpleDateFormat("d.M. HH:mm", FI_WARN)
    fmt.timeZone = HELSINKI_WARN
    return when {
        onsetMs <= 0L -> "voimassa asti " + fmt.format(Date(expiresMs))
        expiresMs <= 0L -> "alkaen " + fmt.format(Date(onsetMs))
        else -> fmt.format(Date(onsetMs)) + " – " + fmt.format(Date(expiresMs))
    }
}
