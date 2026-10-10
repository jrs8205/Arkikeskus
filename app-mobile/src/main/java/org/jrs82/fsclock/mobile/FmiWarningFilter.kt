package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.FmiCounties
import org.jrs82.fsclock.R
import org.jrs82.fsclock.WeatherWarning
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** 5 vrk -näkymän päivä-/maakuntasuodatus + tyyppikoonti. Puhtaat funktiot → testattavissa. */

private val HELSINKI_5D: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

internal enum class DayKind { TODAY, TOMORROW, DATE }

/** Päivävalinta: [dateText] on päiväys [daysFrom]-kuviolla (fi/sv pienellä alkukirjaimella, esim. "pe 29.6."). */
internal data class DayOption(val kind: DayKind, val dateText: String, val startMs: Long, val endMs: Long)

/** 5 päivää tästä (Helsinki), päivän [start,end). [datePattern] ja [locale] näyttökielen mukaan. */
internal fun daysFrom(nowMs: Long, datePattern: String, locale: Locale): List<DayOption> {
    val cal = Calendar.getInstance(HELSINKI_5D)
    cal.timeInMillis = nowMs
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val wd = SimpleDateFormat(datePattern, locale); wd.timeZone = HELSINKI_5D
    val out = ArrayList<DayOption>(5)
    for (i in 0 until 5) {
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis
        val kind = when (i) {
            0 -> DayKind.TODAY
            1 -> DayKind.TOMORROW
            else -> DayKind.DATE
        }
        out.add(DayOption(kind, wd.format(Date(start)), start, end))
    }
    return out
}

/** Päivärivin sirun teksti: Tänään / Huomenna / "Pe 29.6.". */
internal fun DayOption.chipLabel(): WarningUiText = when (kind) {
    DayKind.TODAY -> WarningUiText.Res(R.string.common_today)
    DayKind.TOMORROW -> WarningUiText.Res(R.string.common_tomorrow)
    DayKind.DATE -> WarningUiText.Raw(dateText.replaceFirstChar { it.uppercaseChar() })
}

/** Päivä lauseen sisällä ("Ei varoituksia — Uusimaa, tänään."). */
internal fun DayOption.inlineLabel(): WarningUiText = when (kind) {
    DayKind.TODAY -> WarningUiText.Res(R.string.warning_day_today_inline)
    DayKind.TOMORROW -> WarningUiText.Res(R.string.warning_day_tomorrow_inline)
    DayKind.DATE -> WarningUiText.Raw(dateText)
}

/** Onko varoitus voimassa annettuna päivänä (aikaikkunoiden leikkaus). */
internal fun overlapsDay(w: WeatherWarning, startMs: Long, endMs: Long): Boolean {
    val onset = if (w.onsetMs > 0) w.onsetMs else Long.MIN_VALUE
    val expires = if (w.expiresMs > 0) w.expiresMs else Long.MAX_VALUE
    return onset < endMs && expires > startMs
}

/** Valitun päivän + maakunnan varoitukset, ryhmiteltynä tyypeittäin yhdeksi kortiksi/tyyppi. */
internal fun warningsFor(all: List<WeatherWarning>, day: DayOption, region: String?): List<WeatherWarning> {
    val cands = all.filter {
        overlapsDay(it, day.startMs, day.endMs) && (region == null || it.areaDesc == region)
    }
    return cands.groupBy { it.awarenessType }.values.map { group ->
        mergeGroup(group, region)
    }.sortedWith(compareBy({ it.marine }, { -it.level.rank() }, { it.onsetMs }))
}

private fun mergeGroup(group: List<WeatherWarning>, region: String?): WeatherWarning {
    // edustava = suurin todennäköisyys, sitten äärevin fyysinen arvo (itseisarvo: myös pakkaslukema)
    val rep = group.maxWith(compareBy({ it.details.probabilityPct },
        { if (it.details.hasPhysical()) abs(it.details.physicalValue) else -1.0 }))!!
    val area = region ?: group.map { it.areaDesc }.filter { it.isNotEmpty() }.distinct()
        .sortedBy { FmiCounties.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .joinToString(", ")
    val level = group.maxByOrNull { it.level.rank() }!!.level
    val onset = group.minOf { it.onsetMs }
    val expires = group.maxOf { it.expiresMs }
    val marine = group.any { it.marine }
    return WeatherWarning(rep.event, rep.description, area, onset, expires, level, rep.identifier,
        marine, rep.awarenessType, "", "", "", 0L, rep.senderName, rep.web, rep.details)
}
