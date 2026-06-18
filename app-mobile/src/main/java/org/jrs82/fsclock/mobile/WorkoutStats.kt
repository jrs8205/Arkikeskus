package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.db.WorkoutEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields

/**
 * Lenkkien viikko-/kuukausiyhteenvedot. Puhdas aggregointi ilman Android-riippuvuuksia →
 * yksikkötestattava JVM:ssä (vrt. [KuntaList]). Ryhmittely tehdään lenkin aloitusajasta
 * ([WorkoutEntity.startedAtMs]) annetussa aikavyöhykkeessä; kutsuja päättää suodatuksen
 * (esim. vain omat = `!shared`, vain valmiit). Viikkojako on ISO (ma–su) — sama kuin
 * askelhistoriassa, joten "Viikko N" täsmää sovelluksen muuhun otsikointiin.
 */
object WorkoutStats {

    /** Yksi viikko- tai kuukausiämpäri summattuna. */
    data class Bucket(
        val label: String,        // "Viikko 25" / "Kesäkuu 2026"
        val dateRange: String?,   // "16.6.–22.6." viikoille, null kuukausille
        val sortKey: Int,         // laskeva järjestys (uusin ämpäri ensin)
        val count: Int,
        val distanceM: Double,
        val movingTimeMs: Long,
        val kcal: Int,
        val steps: Long,
        val elevGainM: Double,
        val walkCount: Int,
        val bikeCount: Int,
    )

    private val MONTHS_FI = arrayOf(
        "Tammikuu", "Helmikuu", "Maaliskuu", "Huhtikuu", "Toukokuu", "Kesäkuu",
        "Heinäkuu", "Elokuu", "Syyskuu", "Lokakuu", "Marraskuu", "Joulukuu",
    )

    /** Viikkoämpärit (ISO ma–su), uusin ensin. */
    fun weekly(workouts: List<WorkoutEntity>, zone: ZoneId = ZoneId.systemDefault()): List<Bucket> =
        aggregate(workouts, zone) { d ->
            val wf = WeekFields.ISO
            val wk = d.get(wf.weekOfWeekBasedYear())
            val wky = d.get(wf.weekBasedYear())
            val monday = d.with(wf.dayOfWeek(), 1L)
            val sunday = monday.plusDays(6)
            val range = "${monday.dayOfMonth}.${monday.monthValue}.–${sunday.dayOfMonth}.${sunday.monthValue}."
            Key(wky * 100 + wk, "Viikko $wk", range)
        }

    /** Kuukausiämpärit, uusin ensin. */
    fun monthly(workouts: List<WorkoutEntity>, zone: ZoneId = ZoneId.systemDefault()): List<Bucket> =
        aggregate(workouts, zone) { d ->
            Key(d.year * 100 + d.monthValue, MONTHS_FI[d.monthValue - 1] + " " + d.year, null)
        }

    private data class Key(val sortKey: Int, val label: String, val dateRange: String?)

    private class Acc(val label: String, val dateRange: String?, val sortKey: Int) {
        var count = 0
        var distanceM = 0.0
        var movingTimeMs = 0L
        var kcal = 0
        var steps = 0L
        var elevGainM = 0.0
        var walkCount = 0
        var bikeCount = 0

        fun toBucket() = Bucket(
            label, dateRange, sortKey, count, distanceM, movingTimeMs,
            kcal, steps, elevGainM, walkCount, bikeCount,
        )
    }

    private inline fun aggregate(
        workouts: List<WorkoutEntity>,
        zone: ZoneId,
        keyOf: (LocalDate) -> Key,
    ): List<Bucket> {
        val map = HashMap<Int, Acc>()
        for (w in workouts) {
            val date = Instant.ofEpochMilli(w.startedAtMs).atZone(zone).toLocalDate()
            val k = keyOf(date)
            val acc = map.getOrPut(k.sortKey) { Acc(k.label, k.dateRange, k.sortKey) }
            acc.count++
            acc.distanceM += w.distanceM
            acc.movingTimeMs += w.movingTimeMs
            acc.kcal += w.kcal
            acc.steps += w.steps
            acc.elevGainM += w.elevGainM
            if (w.type == WorkoutEntity.TYPE_BIKE) acc.bikeCount++ else acc.walkCount++
        }
        return map.values.map { it.toBucket() }.sortedByDescending { it.sortKey }
    }
}
