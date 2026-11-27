package org.jrs82.fsclock.mobile.gps

import org.jrs82.fsclock.db.TripEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun TripEntity.snapshot() = TripSnapshot(TripPhase.FINISHED, distanceM, totalMs, movingMs, maxSpeedMps)

internal fun estimatedStartMs(lastSaveMs: Long, saved: TripSnapshot) = lastSaveMs - saved.totalMs

internal fun needsHistory(phase: TripPhase, recorded: Boolean) = phase == TripPhase.FINISHED && !recorded

internal fun tripDays(entries: List<TripEntity>, zone: ZoneId): Map<LocalDate, List<TripEntity>> =
    entries.sortedWith(compareByDescending<TripEntity> { it.startedAtMs }.thenBy { it.id })
        .groupBy { Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate() }
        .toSortedMap(reverseOrder())

internal data class HistoryInterval(val startedAtMs: Long, val endedAtMs: Long, val estimatedStart: Boolean)

internal fun TripEntity.historyInterval() = HistoryInterval(startedAtMs, endedAtMs, estimatedStart)

internal fun dayInterval(rows: List<TripEntity>): HistoryInterval {
    // Jos varma ja arvioitu alku osuvat täsmälleen samaan hetkeen, kellonaika on tiedossa.
    val first = rows.minWith(compareBy<TripEntity> { it.startedAtMs }.thenBy { it.estimatedStart })
    return HistoryInterval(first.startedAtMs, rows.maxOf { it.endedAtMs }, first.estimatedStart)
}

/** Päiväavain säilyy matkan alla vain, kun matka avattiin päivän sivulta. */
internal data class HistorySelection(val dayKey: String? = null, val tripId: String? = null) {
    fun parent() = if (tripId != null) copy(tripId = null) else HistorySelection()
}

internal fun openHistoryDay(date: LocalDate, rows: List<TripEntity>): HistorySelection =
    rows.singleOrNull()?.let { HistorySelection(tripId = it.id) } ?: HistorySelection(dayKey = date.toString())

internal sealed interface HistoryDetail {
    data class Trip(val row: TripEntity) : HistoryDetail
    data class Day(val date: LocalDate, val rows: List<TripEntity>) : HistoryDetail
}

internal fun historyDetail(days: Map<LocalDate, List<TripEntity>>?, dayKey: String?, tripId: String?): HistoryDetail? {
    if (days == null) return null
    if (tripId != null) {
        days.values.asSequence().flatten().find { it.id == tripId }?.let { return HistoryDetail.Trip(it) }
    }
    val date = dayKey?.let(LocalDate::parse) ?: return null
    return days[date]?.let { HistoryDetail.Day(date, it) }
}

internal fun daySnapshot(entries: List<TripEntity>) = TripSnapshot(
    TripPhase.FINISHED, entries.sumOf { it.distanceM }, entries.sumOf { it.totalMs },
    entries.sumOf { it.movingMs }, entries.maxOfOrNull { it.maxSpeedMps } ?: 0f,
)

internal fun TripEntity.toJson(): JSONObject = JSONObject()
    .put("id", id).put("startedAtMs", startedAtMs).put("endedAtMs", endedAtMs)
    .put("estimatedStart", estimatedStart).put("distanceM", distanceM)
    .put("totalMs", totalMs).put("startedAutomatically", startedAutomatically).put("endedAutomatically", endedAutomatically)
    .put("movingMs", movingMs).put("maxSpeedMps", maxSpeedMps.toDouble())

private fun JSONObject.integer(key: String): Long? {
    val value = opt(key)
    return if (value is Int || value is Long) (value as Number).toLong() else null
}

internal fun JSONObject.tripOrNull(): TripEntity? {
    val id = (opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
    val start = integer("startedAtMs") ?: return null
    val end = integer("endedAtMs") ?: return null
    val estimated = opt("estimatedStart") as? Boolean ?: return null
    val distance = (opt("distanceM") as? Number)?.toDouble() ?: return null
    val total = integer("totalMs") ?: return null
    val moving = integer("movingMs") ?: return null
    val speed = (opt("maxSpeedMps") as? Number)?.toFloat() ?: return null
    if (moving !in 0..total || !distance.isFinite() || distance < 0 || !speed.isFinite() || speed < 0) return null
    // Seinäkello voi siirtyä ajossa taaksepäin. Kesto tulee monotonisesta kellosta.
    return TripEntity(id, start, end, estimated, distance, total, moving, speed,
        optBoolean("startedAutomatically", false), optBoolean("endedAutomatically", false))
}

internal fun JSONObject.putTrips(entries: List<TripEntity>) {
    val rows = JSONArray()
    entries.forEach { rows.put(it.toJson()) }
    put("trips", rows)
}

internal fun tripEntries(root: JSONObject): List<TripEntity> {
    val array = root.optJSONArray("trips") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) array.optJSONObject(i)?.tripOrNull()?.let(::add)
    }
}
