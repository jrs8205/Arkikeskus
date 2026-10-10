package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.db.WorkoutEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Lenkkien viikko-/kuukausiyhteenvedot. Puhdas aggregointi ilman Android-riippuvuuksia →
 * yksikkötestattava JVM:ssä (vrt. [KuntaList]). Ryhmittely tehdään lenkin aloitusajasta
 * ([WorkoutEntity.startedAtMs]) annetussa aikavyöhykkeessä; kutsuja päättää suodatuksen
 * (esim. vain omat = `!shared`, vain valmiit). Viikkojako on ISO (ma–su) — sama kuin
 * askelhistoriassa, joten viikkonumero täsmää sovelluksen muuhun otsikointiin. Tekstit eivät
 * synny täällä: ämpäri kantaa [Period]-rakenteen, jonka UI muotoilee näkyvällä kielellä.
 */
object WorkoutStats {

    /** Ämpärin ajanjakso rakenteena: teksti muotoillaan UI:ssa kielen mukaan ([weekRange], [monthLabel]). */
    sealed class Period {
        /** ISO-viikko; [monday] = viikon ensimmäinen päivä (päivämääräväliä varten). */
        data class Week(val week: Int, val monday: LocalDate) : Period() {
            val sunday: LocalDate get() = monday.plusDays(6)
        }
        data class Month(val year: Int, val month: Int) : Period()
    }

    /** Yksi viikko- tai kuukausiämpäri summattuna. */
    data class Bucket(
        val period: Period,
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

    /** Viikkoämpärit (ISO ma–su), uusin ensin. */
    fun weekly(workouts: List<WorkoutEntity>, zone: ZoneId = ZoneId.systemDefault()): List<Bucket> =
        aggregate(workouts, zone) { d ->
            val wf = WeekFields.ISO
            val wk = d.get(wf.weekOfWeekBasedYear())
            val wky = d.get(wf.weekBasedYear())
            val monday = d.with(wf.dayOfWeek(), 1L)
            Key(wky * 100 + wk, Period.Week(wk, monday))
        }

    /** Kuukausiämpärit, uusin ensin. */
    fun monthly(workouts: List<WorkoutEntity>, zone: ZoneId = ZoneId.systemDefault()): List<Bucket> =
        aggregate(workouts, zone) { d ->
            Key(d.year * 100 + d.monthValue, Period.Month(d.year, d.monthValue))
        }

    /** Viikon päivämääräväli, esim. fi "15.6.–21.6.", en "15 Jun–21 Jun". [datePattern] tulee
     *  kieliresurssista, jotta päivän ja kuukauden järjestys seuraa kieltä. */
    @JvmStatic
    fun weekRange(p: Period.Week, datePattern: String, locale: Locale): String {
        val f = DateTimeFormatter.ofPattern(datePattern, locale)
        return f.format(p.monday) + "–" + f.format(p.sunday)
    }

    /** Kuukausi ja vuosi otsikoksi, esim. "Kesäkuu 2026" / "Juni 2026" / "June 2026". Itsenäinen
     *  muoto (LLLL), koska fi/sv taivuttaisivat MMMM-muodon ("kesäkuuta"). Iso alkukirjain otsikkona. */
    @JvmStatic
    fun monthLabel(year: Int, month: Int, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale)
            .format(YearMonth.of(year, month))
            .replaceFirstChar { it.titlecase(locale) }

    private data class Key(val sortKey: Int, val period: Period)

    private class Acc(val period: Period, val sortKey: Int) {
        var count = 0
        var distanceM = 0.0
        var movingTimeMs = 0L
        var kcal = 0
        var steps = 0L
        var elevGainM = 0.0
        var walkCount = 0
        var bikeCount = 0

        fun toBucket() = Bucket(
            period, sortKey, count, distanceM, movingTimeMs,
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
            val acc = map.getOrPut(k.sortKey) { Acc(k.period, k.sortKey) }
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
