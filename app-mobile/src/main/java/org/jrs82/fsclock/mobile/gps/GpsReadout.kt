package org.jrs82.fsclock.mobile.gps

/** GPS:n laatu sijaintitarkkuudesta. NONE = ei tuoretta mittausta. */
enum class GpsQuality { GOOD, FAIR, WEAK, NONE }

/**
 * Signaalin tila näytölle: kunnossa, mittauksessa ei nopeutta, ensimmäistä mittausta odotetaan, signaali
 * katkennut, paikannus pois päältä, laitteessa ei GPS-paikanninta.
 */
enum class GpsStatus { OK, NO_SPEED, WAITING, LOST, OFF, UNAVAILABLE }

/**
 * GPS-lukeman tulkinta näytölle. Ei Android-riippuvuuksia; ajat ovat millisekunteja laitteen
 * käynnistyksestä (sama kello kuin [GpsFix.rtMs]).
 */
object GpsReadout {
    /** Tätä vanhempaa mittausta ei näytetä tuoreena. */
    const val FRESH_MS = 8_000L

    /** Tämän alle jäävä nopeus näytetään nollana (sama raja kuin matkan liikkeen tunnistuksessa). */
    private const val DISPLAY_MIN_KMH = 2f

    /** Sijaintitarkkuuden rajat: enintään 10 m hyvä, enintään 25 m kohtalainen. */
    private const val GOOD_MAX_M = 10f
    private const val FAIR_MAX_M = TripCalculator.ACCURACY_MAX_M

    fun isFresh(fix: GpsFix?, nowRtMs: Long): Boolean = fix != null && nowRtMs - fix.rtMs <= FRESH_MS

    /** Näytettävä nopeus kokonaislukuna; null, kun tuoretta nopeutta ei ole (näytetään viiva). */
    fun displaySpeedKmh(fix: GpsFix?, nowRtMs: Long): Int? {
        if (fix == null || !isFresh(fix, nowRtMs)) return null
        val mps = fix.speedMps ?: return null
        if (!mps.isFinite() || mps < 0f) return null
        val kmh = mps * 3.6f
        return if (kmh < DISPLAY_MIN_KMH) 0 else Math.round(kmh)
    }

    /** Laatu on sidottu samaan 25 m:n rajaan, jolla mittaus kelpaa matkan laskentaan. */
    fun quality(fix: GpsFix?, nowRtMs: Long): GpsQuality {
        if (fix == null || !isFresh(fix, nowRtMs)) return GpsQuality.NONE
        val accuracy = fix.accuracyM
        return when {
            accuracy == null || accuracy.isNaN() -> GpsQuality.WEAK
            accuracy <= GOOD_MAX_M -> GpsQuality.GOOD
            accuracy <= FAIR_MAX_M -> GpsQuality.FAIR
            else -> GpsQuality.WEAK
        }
    }

    /**
     * Onko lukemassa tuore mittaus. Tästä riippuu, näytetäänkö mittauksen tiedot (sijainnin ja nopeuden
     * tarkkuus): ne kuuluvat mittaukseen, eivätkä riipu siitä, antoiko vastaanotin nopeuden.
     */
    fun hasFreshFix(reading: GpsReading, nowRtMs: Long): Boolean =
        reading.providerPresent && reading.providerEnabled && isFresh(reading.fix, nowRtMs)

    fun status(reading: GpsReading, nowRtMs: Long): GpsStatus = when {
        // Paikanninta ei ole: odottaminen ei auta, joten tämä ei saa näkyä odotuksena.
        !reading.providerPresent -> GpsStatus.UNAVAILABLE
        !reading.providerEnabled -> GpsStatus.OFF
        reading.fix == null -> GpsStatus.WAITING
        !isFresh(reading.fix, nowRtMs) -> GpsStatus.LOST
        // Mittauksia tulee, mutta niissä ei ole nopeutta: nopeutta ei voi näyttää eikä matkaa mitata.
        reading.fix.speedMps == null -> GpsStatus.NO_SPEED
        else -> GpsStatus.OK
    }
}
