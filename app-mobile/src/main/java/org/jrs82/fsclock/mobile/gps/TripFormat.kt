package org.jrs82.fsclock.mobile.gps

import java.util.Locale

/** Arvo ja yksikkö erikseen, koska laatoissa ne piirretään eri kokoisina. */
data class Quantity(val value: String, val unit: String) {
    override fun toString(): String = "$value $unit"
}

/**
 * Mittariston lukujen muotoilu. Ei Android-riippuvuuksia. Yksiköt (m, km, km/h, min, h) ovat samat
 * kaikilla sovelluksen kielillä; desimaalierotin tulee annetusta localesta.
 */
object TripFormat {
    /** Tyhjä arvo: lukua ei ole vielä tai sitä ei voi laskea. */
    const val EMPTY = "–"

    private const val UNIT_M = "m"
    private const val UNIT_KM = "km"
    private const val UNIT_KMH = "km/h"
    private const val SEPARATOR = " · "

    /**
     * Matka. Muoto valitaan pyöristämättömästä arvosta niin, ettei pyöristys tuota muotoa "1000 m",
     * "10,00 km" tai "1000,0 km": alle 995 m metreinä 10 m:n tarkkuudella, alle 9,995 km kahdella
     * desimaalilla, alle 999,95 km yhdellä, siitä ylöspäin kokonaisina kilometreinä.
     */
    fun distance(meters: Double, locale: Locale): Quantity {
        val m = if (meters.isNaN() || meters < 0.0) 0.0 else meters
        return when {
            m < 995.0 -> Quantity((Math.round(m / 10.0) * 10).toString(), UNIT_M)
            m < 9_995.0 -> Quantity(String.format(locale, "%.2f", Math.round(m / 10.0) / 100.0), UNIT_KM)
            m < 999_950.0 -> Quantity(String.format(locale, "%.1f", Math.round(m / 100.0) / 10.0), UNIT_KM)
            else -> Quantity(Math.round(m / 1000.0).toString(), UNIT_KM)
        }
    }

    /** Nopeus kokonaislukuna (huippunopeus). null tai nolla = arvoa ei ole. */
    fun wholeKmh(mps: Float?): Quantity {
        if (mps == null || mps.isNaN() || mps <= 0f) return Quantity(EMPTY, UNIT_KMH)
        return Quantity(Math.round(mps * 3.6f).toString(), UNIT_KMH)
    }

    /** Keskinopeus yhdellä desimaalilla. */
    fun avgKmh(kmh: Double?, locale: Locale): Quantity {
        if (kmh == null || kmh.isNaN()) return Quantity(EMPTY, UNIT_KMH)
        return Quantity(String.format(locale, "%.1f", kmh), UNIT_KMH)
    }

    /** Kesto näkymissä: alle tunnin mm:ss, muuten h:mm:ss. */
    fun clock(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    /**
     * Näytettävä pysähdysaika: näytetty kokonaisaika miinus näytetty liikeaika. Kukin aika näytetään
     * sekunnin tarkkuudella alaspäin pyöristettynä, joten erikseen pyöristetty pysähdysaika voisi jäädä
     * sekunnin vajaaksi; näin kolme lukua täsmäävät näytöllä aina.
     */
    fun stoppedForDisplay(totalMs: Long, movingMs: Long): Long {
        val totalSec = totalMs.coerceAtLeast(0L) / 1000
        val movingSec = movingMs.coerceAtLeast(0L) / 1000
        return (totalSec - movingSec).coerceAtLeast(0L) * 1000
    }

    /** Kesto ilmoituksessa minuutin tarkkuudella: "42 min", "1 h 42 min". */
    fun coarse(ms: Long): String {
        val totalMin = ms.coerceAtLeast(0L) / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    /** Sijainnin tarkkuus, esim. "±4 m". */
    fun accuracy(meters: Float?): String {
        if (meters == null || meters.isNaN()) return EMPTY
        return "±" + Math.round(meters) + " " + UNIT_M
    }

    /** Nopeuden tarkkuus, esim. "±0,7 km/h". */
    fun speedAccuracy(mps: Float?, locale: Locale): String {
        if (mps == null || mps.isNaN()) return EMPTY
        return "±" + String.format(locale, "%.1f", mps * 3.6) + " " + UNIT_KMH
    }

    /**
     * Pysyvän ilmoituksen tekstirivi: matka, kokonaisaika ja nopeus. Nopeus on mukana vain, kun matka on
     * käynnissä ja tuore mittaus on olemassa.
     */
    fun notificationLine(s: TripSnapshot, speedKmh: Int?, locale: Locale): String {
        val base = distance(s.distanceM, locale).toString() + SEPARATOR + coarse(s.totalMs)
        return if (s.phase == TripPhase.RUNNING && speedKmh != null) "$base$SEPARATOR$speedKmh $UNIT_KMH" else base
    }
}
