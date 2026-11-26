package org.jrs82.fsclock.mobile.gps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS-lukeman haltija, joka sitoo julkaisut rekisteröintikertaan. Jokainen rekisteröinti avaa uuden kerran
 * ([open]) ja saa tunnisteen; lukemaa saa muuttaa vain voimassa olevan kerran tunnisteella ([publish]).
 * Kuuntelijan säikeessä jo käynnissä oleva vanha kutsu ei siis voi muuttaa lukemaa sen jälkeen, kun lähde
 * on vapautettu tai rekisteröity uudelleen: peräkkäisillä rekisteröinneillä on eri säikeet, joten pelkkä
 * tapahtumien järjestys yhden säikeen sisällä ei riitä.
 */
internal class GpsReadingSlot {

    private val mutableReading = MutableStateFlow(GpsReading())

    val reading: StateFlow<GpsReading> get() = mutableReading

    private var current = 0L     // voimassa olevan rekisteröintikerran tunniste; 0 = ei rekisteröintiä
    private var lastToken = 0L

    /** Aloittaa uuden rekisteröintikerran lukemalla [initial] ja palauttaa sen tunnisteen. */
    @Synchronized
    fun open(initial: GpsReading): Long {
        lastToken++
        current = lastToken
        mutableReading.value = initial
        return current
    }

    /** Päättää rekisteröintikerran. [reading] jää näkyviin: oletuksena tyhjä, epäonnistuneessa rekisteröinnissä syy. */
    @Synchronized
    fun close(reading: GpsReading = GpsReading()) {
        current = 0L
        mutableReading.value = reading
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token != 0L && token == current

    /**
     * Ajaa [block]in, jos [token] on voimassa olevan rekisteröintikerran. Tarkistus ja ajo ovat yksi lukittu
     * operaatio: [close] ja [open] odottavat kesken olevan toimituksen loppuun, eikä päättyneen kerran mittausta
     * toimiteta niiden jälkeen. Näin mittaus ei voi päätyä matkan laskentaan enää sen jälkeen, kun lähteen
     * vapautus on palannut. Palauttaa, ajettiinko [block].
     */
    @Synchronized
    fun deliver(token: Long, block: () -> Unit): Boolean {
        if (token == 0L || token != current) return false
        block()
        return true
    }

    /** Muuttaa lukemaa, jos [token] on voimassa olevan rekisteröintikerran. Palauttaa, julkaistiinko muutos. */
    @Synchronized
    fun publish(token: Long, transform: (GpsReading) -> GpsReading): Boolean {
        if (token == 0L || token != current) return false
        mutableReading.value = transform(mutableReading.value)
        return true
    }
}
