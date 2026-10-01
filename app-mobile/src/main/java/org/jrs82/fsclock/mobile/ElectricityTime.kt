package org.jrs82.fsclock.mobile

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Sähkön varttien kellonajat aikaleimasta. Talviaikaan siirryttäessä tunti 03 on vuorokaudessa kahdesti,
 * joten paikallinen tunti ei yksilöi varttia eikä vartin loppua voi laskea seinäkellosta.
 */
object ElectricityTime {
    val HELSINKI: ZoneId = ZoneId.of("Europe/Helsinki")
    private const val HOUR_MS = 3_600_000L
    private const val QUARTER_MS = 15L * 60_000L

    /** Lämpökartan tuntirivi. [repeated] = talviaikaan siirtymisen toistuvan tunnin jälkimmäinen kierros. */
    data class HourRow(val startMs: Long, val hour: Int, val repeated: Boolean)

    /** Todellisen tunnin avain; Suomen aikavyöhykkeen siirtymä on täysiä tunteja. */
    fun hourKey(ms: Long): Long = Math.floorDiv(ms, HOUR_MS)

    /** Päivän tunnit keskiyöstä keskiyöhön: tavallisesti 24, kesäaikaan siirryttäessä 23 ja
     *  talviaikaan siirryttäessä 25. */
    fun dayHours(anyMsOfDay: Long): List<HourRow> {
        val day = Instant.ofEpochMilli(anyMsOfDay).atZone(HELSINKI).toLocalDate()
        val end = day.plusDays(1).atStartOfDay(HELSINKI).toInstant().toEpochMilli()
        val out = ArrayList<HourRow>(25)
        var ms = day.atStartOfDay(HELSINKI).toInstant().toEpochMilli()
        while (ms < end) {
            out.add(HourRow(ms, Instant.ofEpochMilli(ms).atZone(HELSINKI).hour, overlapPass(ms) == 2))
            ms += HOUR_MS
        }
        return out
    }

    /** Sen vartin alku ja loppu, johon hetki kuuluu. Loppu lasketaan hetkestä, joten se on oikein
     *  myös kellonsiirron yli (03.45 kesäaikaa päättyy 03.00 talviaikaa). */
    fun quarterBounds(ms: Long): Pair<ZonedDateTime, ZonedDateTime> {
        val start = Math.floorDiv(ms, QUARTER_MS) * QUARTER_MS
        return Instant.ofEpochMilli(start).atZone(HELSINKI) to Instant.ofEpochMilli(start + QUARTER_MS).atZone(HELSINKI)
    }

    /** Kellonajan tarkenne toistuvalla tunnilla, muuten tyhjä. */
    fun dstSuffix(ms: Long): String = when (overlapPass(ms)) {
        1 -> " (kesäaikaa)"
        2 -> " (talviaikaa)"
        else -> ""
    }

    /** 0 = tavallinen hetki, 1 = toistuvan tunnin ensimmäinen kierros, 2 = jälkimmäinen. */
    private fun overlapPass(ms: Long): Int {
        val z = Instant.ofEpochMilli(ms).atZone(HELSINKI)
        val t = HELSINKI.rules.getTransition(z.toLocalDateTime()) ?: return 0
        if (!t.isOverlap) return 0
        return if (z.offset == t.offsetBefore) 1 else 2
    }
}
