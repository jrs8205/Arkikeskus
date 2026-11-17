package org.jrs82.fsclock.mobile

import android.content.SharedPreferences
import org.jrs82.fsclock.ElectricityData

/**
 * Asetus "Näytä tunnin keskihinta": hinnat näytetään varttien sijaan tunneittain. Tunnin hinta on
 * tunnin neljän vartin keskiarvo. Lähdedata, varasto ja widget-cache pysyvät vartteina; tunnit
 * lasketaan vasta näytettäessä, joten asetus vaikuttaa heti ilman uutta hakua.
 *
 * Tuntitila on voimassa vain päivälle, jonka JOKAISELLA tunnilla on kaikki neljä varttia
 * ([daySlots]). Vajaa päivä näytetään vartteina kuten ilman asetusta.
 */
object ElectricityHourly {
    const val KEY = "mobile_electricity_hourly_average"
    const val DEFAULT = false
    const val QUARTER_MS = 15L * 60_000L
    const val HOUR_MS = 60L * 60_000L
    private const val QUARTERS_PER_HOUR = 4

    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    /** Näytettävän jakson pituus: tunti tai vartti. */
    fun slotMs(hourly: Boolean): Long = if (hourly) HOUR_MS else QUARTER_MS

    /**
     * Täysien tuntien keskihinnat pisteistä (vartin alku, veroton snt/kWh), aikajärjestyksessä.
     *
     * Arvonlisävero lisätään vain nollaa suurempiin vartteihin, joten tunnin hinta on NÄYTETTYJEN
     * varttihintojen keskiarvo eikä verottoman keskiarvon verollinen muoto. Tulos palautetaan
     * verottomana vastineena ([ElectricityVat.unapply]): kun siihen sovelletaan [ElectricityVat.apply]
     * samalla asetuksella, saadaan täsmälleen tuo keskiarvo. Näin tuntihinnat kulkevat samojen
     * näyttö- ja vertailufunktioiden läpi kuin vartit.
     *
     * Tunnille, jolta puuttuu vartteja, ei lasketa hintaa. Tunnit ryhmitellään todellisen tunnin
     * mukaan, joten talviaikaan siirtymisen toistuva tunti on kaksi eri tuntia.
     */
    fun hourAverages(points: List<Pair<Long, Double>>, vat: Boolean): List<Pair<Long, Double>> {
        val byHour = HashMap<Long, HashMap<Long, Double>>()
        for ((timestamp, snt) in points) {
            byHour.getOrPut(Math.floorDiv(timestamp, HOUR_MS)) { HashMap() }.putIfAbsent(timestamp, snt)
        }
        val out = ArrayList<Pair<Long, Double>>(byHour.size)
        for ((hour, quarters) in byHour) {
            if (quarters.size != QUARTERS_PER_HOUR) continue
            val shownAverage = quarters.values.sumOf { ElectricityVat.apply(it, vat) } / QUARTERS_PER_HOUR
            out.add(hour * HOUR_MS to ElectricityVat.unapply(shownAverage, vat))
        }
        out.sortBy { it.first }
        return out
    }

    /**
     * Yhden päivän (Suomen aikaa) tuntien keskihinnat, tai null, jos päivän jokaiselle tunnille ei
     * saada täyttä tuntihintaa. [dayPoints] saa sisältää vain tuon päivän vartteja. Päivän tunnit
     * tulevat kalenterista ([ElectricityTime.dayHours]), joten kellonsiirtopäivän 23 tai 25 tuntia
     * ja kokonaan puuttuva tunti tunnistetaan oikein.
     */
    fun completeDayHourAverages(dayPoints: List<Pair<Long, Double>>, vat: Boolean): List<Pair<Long, Double>>? {
        if (dayPoints.isEmpty()) return null
        val averages = hourAverages(dayPoints, vat)
        return if (coversEveryHourOfDay(averages.map { it.first }, dayPoints[0].first)) averages else null
    }

    /** True jos [hourStarts] (aikajärjestyksessä) ovat täsmälleen hetken [anyMsOfDay] päivän tunnit. */
    private fun coversEveryHourOfDay(hourStarts: List<Long>, anyMsOfDay: Long): Boolean {
        val expected = ElectricityTime.dayHours(anyMsOfDay)
        if (hourStarts.size != expected.size) return false
        for (i in expected.indices) {
            if (hourStarts[i] != expected[i].startMs) return false
        }
        return true
    }

    /** Varteista täydet tunnit: jokainen tunti on [ElectricityData.Quarter], jonka alku on tasatunti. */
    fun hourSlots(quarters: List<ElectricityData.Quarter>, vat: Boolean): List<ElectricityData.Quarter> =
        hourAverages(quarters.map { it.timestamp to it.sntPerKwh }, vat)
            .map { (timestamp, snt) -> ElectricityData.quarter(timestamp, snt) }

    /** Yhden päivän näytettävät jaksot ja niiden pituus. */
    data class DaySlots(val slots: List<ElectricityData.Quarter>, val slotMs: Long) {
        /** True kun jaksot ovat tunteja; false kun ne ovat vartteja. */
        val hourly: Boolean get() = slotMs == HOUR_MS
    }

    /**
     * Yhden päivän varteista näytettävät jaksot. Tunnit palautetaan vain, kun asetus on päällä JA
     * päivän jokaisella tunnilla on täysi tuntihinta; muuten palautetaan vartit sellaisinaan.
     *
     * Vajaan tunnin pois jättäminen piilottaisi sen tunnetut vartit ja voisi tuottaa väitteitä, jotka
     * eivät pidä paikkaansa (halvin ja kallein, hintajanan asteikko, "sähkö on halpaa koko päivän").
     * Varttinäyttö käyttää kaiken tiedossa olevan datan, joten vajaalla päivällä asetus ei muuta
     * mitään verrattuna tilaan, jossa se on pois päältä.
     */
    fun daySlots(dayQuarters: List<ElectricityData.Quarter>, hourly: Boolean, vat: Boolean): DaySlots {
        if (hourly && dayQuarters.isNotEmpty()) {
            val hours = hourSlots(dayQuarters, vat)
            if (coversEveryHourOfDay(hours.map { it.timestamp }, dayQuarters[0].timestamp)) return DaySlots(hours, HOUR_MS)
        }
        return DaySlots(dayQuarters, QUARTER_MS)
    }

    /** Jakso, johon hetki kuuluu, tai null. */
    fun current(slots: List<ElectricityData.Quarter>, nowMs: Long, slotMs: Long): ElectricityData.Quarter? =
        slots.firstOrNull { it.timestamp <= nowMs && nowMs < it.timestamp + slotMs }
}
