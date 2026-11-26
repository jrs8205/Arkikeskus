package org.jrs82.fsclock.mobile.gps

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateFormat
import org.jrs82.fsclock.R
import org.jrs82.fsclock.db.TripEntity
import java.text.NumberFormat
import java.text.ParsePosition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Näkyvät luvut ja puhutut yksiköt käyttävät samaa pyöristettyä arvoa. */
internal class HistoryFormat(private val context: Context, val locale: Locale, val zone: ZoneId,
                             private val today: LocalDate, use24h: Boolean) {
    private fun pattern(skeleton: String) =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    private val time = pattern(if (use24h) "Hm" else "hm")
    private val shortDate = pattern("EEEMd")
    private val shortYear = pattern("EEEMdy")
    private val fullDate = pattern("EEEEMMMMd")
    private val fullYear = pattern("EEEEMMMMdy")
    private val measures = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
    private val numbers = NumberFormat.getNumberInstance(locale)

    fun date(day: LocalDate, spoken: Boolean = false): String {
        val format = if (spoken) {
            if (day.year == today.year) fullDate else fullYear
        } else {
            if (day.year == today.year) shortDate else shortYear
        }
        return format.format(day)
    }

    fun dateAndInterval(day: LocalDate, span: HistoryInterval) = "${date(day)} · ${interval(span)}"

    fun interval(trip: TripEntity, spoken: Boolean = false) = interval(trip.historyInterval(), spoken)

    fun interval(span: HistoryInterval, spoken: Boolean = false): String {
        val start = Instant.ofEpochMilli(span.startedAtMs).atZone(zone)
        val end = Instant.ofEpochMilli(span.endedAtMs).atZone(zone)
        val clock = time.format(start)
        val from = if (span.estimatedStart) context.getString(
            if (spoken) R.string.gps_history_approximately else R.string.gps_history_estimated, clock,
        ) else clock
        val until = if (end.toLocalDate() == start.toLocalDate()) time.format(end)
            else "${date(end.toLocalDate(), spoken)} ${time.format(end)}"
        return if (spoken) context.getString(R.string.gps_history_interval, from, until) else "$from–$until"
    }

    fun spokenQuantity(q: Quantity): String {
        if (q.value == TripFormat.EMPTY) return context.getString(R.string.gps_history_unavailable)
        val unit = when (q.unit) {
            "m" -> MeasureUnit.METER
            "km" -> MeasureUnit.KILOMETER
            else -> MeasureUnit.KILOMETER_PER_HOUR
        }
        val value = numbers.parse(q.value, ParsePosition(0)) ?: return context.getString(R.string.gps_history_unavailable)
        return measures.format(Measure(value, unit))
    }

    fun spokenDuration(ms: Long): String {
        val seconds = ms / 1000
        val parts = buildList {
            if (seconds >= 3600) add(Measure(seconds / 3600, MeasureUnit.HOUR))
            if (seconds / 60 % 60 > 0) add(Measure(seconds / 60 % 60, MeasureUnit.MINUTE))
            if (seconds % 60 > 0 || isEmpty()) add(Measure(seconds % 60, MeasureUnit.SECOND))
        }
        return measures.formatMeasures(*parts.toTypedArray())
    }
}
