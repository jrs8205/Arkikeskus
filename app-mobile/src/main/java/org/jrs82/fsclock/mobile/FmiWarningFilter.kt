package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.FmiCounties
import org.jrs82.fsclock.WeatherWarning
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 5 vrk -näkymän päivä-/maakuntasuodatus + tyyppikoonti. Puhtaat funktiot → testattavissa. */

private val FI_5D = Locale("fi", "FI")
private val HELSINKI_5D: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

internal data class DayOption(val label: String, val startMs: Long, val endMs: Long)

/** 5 päivää tästä (Helsinki): label Tänään / Huomenna / "Pe 29.6." jne., päivän [start,end). */
internal fun daysFrom(nowMs: Long): List<DayOption> {
    val cal = Calendar.getInstance(HELSINKI_5D)
    cal.timeInMillis = nowMs
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val wd = SimpleDateFormat("EEE d.M.", FI_5D); wd.timeZone = HELSINKI_5D
    val out = ArrayList<DayOption>(5)
    for (i in 0 until 5) {
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis
        val label = when (i) {
            0 -> "Tänään"
            1 -> "Huomenna"
            else -> wd.format(Date(start)).replaceFirstChar { it.uppercase() }
        }
        out.add(DayOption(label, start, end))
    }
    return out
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
    // edustava = suurin todennäköisyys, sitten pisin fyysinen teksti
    val rep = group.maxWith(compareBy({ it.details.probabilityPct }, { it.details.physicalText.length }))
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
