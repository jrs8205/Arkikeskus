package org.jrs82.fsclock.mobile.gps

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Matkan kertymä vastaanottimen nopeuksista yhdessä mittausjonossa. Jono on peräkkäisiä mittauksia, joiden väli
 * on enintään [TripCalculator.CONTINUOUS_MAX_MS]; omistaja päättää jonon kutsulla [finish] (tauko, lopetus ja
 * katko) ja aloittaa uuden kutsulla [reset]. Syötteenä on mittauksen aika, vastaanottimen nopeus ja tieto siitä,
 * onko mittaus liikettä. Mittauksen liikenopeus on sen nopeus, jos mittaus on liikettä, muuten 0.
 *
 * Liikenopeudet integroidaan sellaisinaan puolisuunnikassäännöllä. Niihin kosketaan vain kahdessa tapauksessa
 * (käyttäjän päätös 14: virhelukemista korjataan vain varma tapaus):
 *
 *  1. **Varma virhe** ([certainError]): mittaus, johon muutos on mahdoton (yli [ACCEL_MAX_MPS2]) sekä edeltäjästä
 *     että seuraajaan, vaikka edeltäjä ja seuraaja sopivat toisiinsa. Mahdollisuus arvioidaan vastaanottimen
 *     alkuperäisistä nopeuksista, ei liikenopeuksista: liikerajan alle jäävä lukema ei ole virhe, jos muutos
 *     siihen on mahdollinen. Varman virheen liikenopeus korvataan naapuriensa liikenopeuksien väliarvolla
 *     ([between]). Jos kaksi vierekkäistä mittausta täyttää ehdon (vuorotteleva jono), kumpikaan ei ole varma
 *     virhe, eikä kumpaankaan kosketa. Mittaus, joka ei itse ole liikettä, korotetaan liikkeeksi vain, jos sen
 *     jommallakummalla puolella on kaksi peräkkäistä liikenopeutta: kaksi erillistä piikkiä ei tee niiden
 *     välisestä mittauksesta virhettä.
 *  2. **Liike vaatii kaksi peräkkäistä liikenopeutta:** liikenopeus, jonka kummankaan naapurin nopeus ei ole
 *     säännön 1 jälkeen liikettä (tai naapuria ei ole), on nolla. Erilliset piikit paikallaan eivät siis kerry.
 *     Säännöistä yhdessä seuraa, että jokaisessa liikkeeksi lasketussa jaksossa on kaksi peräkkäistä mittausta,
 *     joiden oma nopeus on liikettä.
 *
 * Mitään muuta ei päätellä: jonon ensimmäistä ja viimeistä mittausta ei korjata (niillä on vain yksi naapuri),
 * eikä siitä, kumpi kahdesta ristiriitaisesta mittauksesta on virhe, tehdä oletuksia. Muut virheelliset
 * mittaukset kirjautuvat sellaisinaan. Säännöstä 1 seuraa, ettei yksi virheellinen mittaus voi muuttaa minkään
 * muun mittauksen arvoa: ehto vaatii mahdottoman muutoksen kumpaankin naapuriin, ja oikealla mittauksella on
 * aina vähintään yksi oikea naapuri, johon se sopii.
 *
 * Mittauksen arvo on lopullinen, kun sen ratkaisevat seuraajat tunnetaan (tavallisesti heti, enintään kolme
 * seuraajaa). Siihen asti sen arvona käytetään nollaa, joten arvot voivat vain kasvaa eikä kirjattu matka
 * koskaan pienene.
 *
 * Ei säieturvallinen: omistaja synkronoi kutsut.
 */
internal class SpeedIntegrator {

    /** Yhden kutsun tulos: lisätty matka, liikeajaksi laskettu aika ja viimeisimmän liikkeen päättymishetki. */
    class Delta(val meters: Double, val movingMs: Long, val lastMoveRtMs: Long)

    /** [v] on vastaanottimen nopeus ja [m] liikenopeus (sama, jos mittaus on liikettä, muuten 0). */
    private class Node(val rtMs: Long, val v: Float, val m: Float) {
        var corrected = 0f        // nopeus säännön 1 jälkeen (asetetaan, kun mittaus on lopullinen)
        var speed = 0f            // käytettävä nopeus sääntöjen 1 ja 2 jälkeen (0, kunnes lopullinen)
        var settled = false
        var creditedM = 0.0       // tähän mittaukseen päättyvälle välille jo kirjattu matka
        var creditedMoving = false
    }

    /** Mittaukset, joiden arvo ei ole vielä lopullinen, ja niitä edeltävät kaksi lopullista mittausta. */
    private val window = ArrayList<Node>(8)

    /** Jono on päättymässä: seuraajaa odottavat mittaukset ratkaistaan ilman sitä. */
    private var ending = false

    /** Kutsutaan, kun mittauksen arvo on lopullinen (aika, nopeus). Testit tarkistavat tällä kirjanpidon. */
    internal var onSettled: ((Long, Float) -> Unit)? = null

    fun reset() {
        window.clear()
    }

    fun add(rtMs: Long, speedMps: Float, moving: Boolean): Delta {
        window.add(Node(rtMs, speedMps, if (moving) speedMps else 0f))
        return settle()
    }

    /** Päättää jonon: seuraajaa odottaneet mittaukset saavat lopullisen arvonsa ilman sitä. */
    fun finish(): Delta {
        ending = true
        val delta = settle()
        ending = false
        window.clear()
        return delta
    }

    private fun settle(): Delta {
        resolve()
        var meters = 0.0
        var movingMs = 0L
        var lastMoveRtMs = Long.MIN_VALUE
        for (i in 1 until window.size) {
            val a = window[i - 1]
            val b = window[i]
            val dtMs = b.rtMs - a.rtMs
            val now = intervalMeters(a.speed, b.speed, dtMs)
            if (now > b.creditedM) {
                meters += now - b.creditedM
                b.creditedM = now
                if (!b.creditedMoving) {
                    b.creditedMoving = true
                    movingMs += dtMs
                }
                lastMoveRtMs = max(lastMoveRtMs, b.rtMs)
            }
        }
        // Keskeneräisen mittauksen ratkaisu tarvitsee kaksi edeltävää mittausta: vanhemmat voidaan unohtaa.
        while (window.size > 2 && window[0].settled && window[1].settled && window[2].settled) {
            window.removeAt(0)
        }
        return Delta(meters, movingMs, lastMoveRtMs)
    }

    /** Ratkaisee mittaukset järjestyksessä niin pitkälle kuin tunnetut seuraajat riittävät. */
    private fun resolve() {
        for (i in window.indices) {
            val n = window[i]
            if (n.settled) continue
            val prev = window.getOrNull(i - 1)
            // Ensimmäinen keskeneräinen pysäyttää ratkaisun: sen jälkeisten arvo on toistaiseksi nolla.
            if (prev != null && !prev.settled) return
            val corrected = correctedSpeed(i) ?: return
            val moving: Boolean = when {
                corrected == 0f -> false
                prev != null && prev.corrected > 0f -> true
                i + 1 < window.size -> (correctedSpeed(i + 1) ?: return) > 0f
                ending -> false
                else -> return
            }
            n.corrected = corrected
            n.speed = if (moving) corrected else 0f
            n.settled = true
            onSettled?.invoke(n.rtMs, n.speed)
        }
    }

    /** Mittauksen [i] liikenopeus säännön 1 jälkeen; null, jos ratkaisuun tarvitaan vielä seuraajia. */
    private fun correctedSpeed(i: Int): Float? {
        val n = window[i]
        val candidate = isCandidate(i) ?: return null
        if (!candidate) return n.m
        // Vierekkäiset ehdokkaat kumoavat toisensa: kumpaakaan ei voi pitää varmana virheenä.
        if (isCandidate(i - 1) ?: return null) return n.m
        if (isCandidate(i + 1) ?: return null) return n.m
        // Korjaus ei saa synnyttää liikettä erillisistä piikeistä: mittaus, joka ei itse ole liikettä, korotetaan
        // liikkeeksi vain, jos sen jommallakummalla puolella on kaksi peräkkäistä liikenopeutta.
        if (n.m == 0f && !(isMoving(i - 1) && isMoving(i - 2)) && !(isMoving(i + 1) && isMoving(i + 2))) return n.m
        val prev = window[i - 1]
        val next = window[i + 1]
        return between(prev.m, n.rtMs - prev.rtMs, next.m, next.rtMs - n.rtMs)
    }

    /** Onko mittaus [i] itse liikettä; false, jos mittausta ei ole (jonon alku tai loppu). */
    private fun isMoving(i: Int): Boolean = window.getOrNull(i)?.let { it.m > 0f } ?: false

    /**
     * Täyttääkö mittaus [i] varman virheen ehdon ([certainError]) vastaanottimen alkuperäisillä nopeuksilla; null,
     * jos seuraajaa ei vielä tunneta. Ikkunan
     * ensimmäisellä mittauksella ei ole edeltäjää: sitä kysytään vain, kun se on jonon ensimmäinen mittaus
     * (muulloin ikkunan alussa on kaksi lopullista mittausta, eikä niistä ensimmäistä tarvita).
     */
    private fun isCandidate(i: Int): Boolean? {
        if (i <= 0) return false
        val prev = window[i - 1]
        val n = window[i]
        val prevDt = n.rtMs - prev.rtMs
        if (fits(prev.v, n.v, prevDt)) return false
        val next = window.getOrNull(i + 1) ?: return if (ending) false else null
        return certainError(prev.v, prevDt, n.v, next.v, next.rtMs - n.rtMs)
    }

    companion object {
        /** Enintään näin kaukana toisistaan olevat mittaukset ovat tiheä jono. */
        const val DENSE_MAX_MS = 3_000L

        /**
         * Suurin uskottava nopeuden muutos (m/s²), noin 1 g. Tätä jyrkempi muutos kahden mittauksen välillä on
         * mahdoton: ainakin toinen mittauksista on virheellinen.
         */
        const val ACCEL_MAX_MPS2 = 10f

        /**
         * Aikaleimojen tarkkuus: mittausten ajat on katkaistu millisekunteihin, joten todellinen mittausväli voi
         * olla lähes näin paljon mitattua pidempi.
         */
        const val TIME_TOLERANCE_MS = 1L

        /** Nopeuksien esitystarkkuuden vara (m/s): liukulukujen erotus voi poiketa todellisesta erosta. */
        const val SPEED_TOLERANCE_MPS = 0.001

        internal fun dense(dtMs: Long): Boolean = dtMs <= DENSE_MAX_MS

        /**
         * Onko muutos nopeudesta [a] nopeuteen [b] ajassa [dtMs] mahdollinen. Mittaustarkkuus luetaan mittausten
         * eduksi: muutos on mahdoton vain, jos se ylittää rajan [ACCEL_MAX_MPS2], vaikka väli olisi
         * [TIME_TOLERANCE_MS] mitattua pidempi ja nopeuksien ero [SPEED_TOLERANCE_MPS] pienempi. Mittauspari, jonka
         * todellinen kiihtyvyys on enintään raja, sopii siis aina yhteen. Lasketaan kaksoistarkkuudella, jottei
         * vertailun oma pyöristys ratkaise raja-arvolla.
         */
        internal fun fits(a: Float, b: Float, dtMs: Long): Boolean =
            abs(a.toDouble() - b.toDouble()) <=
                ACCEL_MAX_MPS2.toDouble() * (dtMs + TIME_TOLERANCE_MS) / 1000.0 + SPEED_TOLERANCE_MPS

        /**
         * Välin matka: tiheällä välillä nopeuden kuvaajan pinta-ala (päiden keskiarvo × aika), harvalla välillä
         * (yli [DENSE_MAX_MS]) vain nopeus, jonka molemmat päät antavat (pienempi × aika).
         */
        internal fun intervalMeters(startMps: Float, endMps: Float, dtMs: Long): Double {
            val dtSec = dtMs / 1000.0
            return if (dense(dtMs)) {
                (startMps.toDouble() + endMps.toDouble()) / 2.0 * dtSec
            } else {
                min(startMps, endMps).toDouble() * dtSec
            }
        }

        /**
         * Varman virheen ehto mittaukselle [m], jonka edeltäjä on [before] ja seuraaja [next] (vastaanottimen
         * alkuperäiset nopeudet): muutos on mahdoton kumpaankin naapuriin, mutta naapurit sopivat toisiinsa. Jos
         * naapurit eivät sovi toisiinsa, kyse ei ole yhdestä virheestä (tasonvaihdos tai useita virheitä), eikä
         * mittaukseen kosketa.
         */
        internal fun certainError(before: Float, beforeDtMs: Long, m: Float, next: Float, nextDtMs: Long): Boolean =
            !fits(before, m, beforeDtMs) && !fits(m, next, nextDtMs) && fits(before, next, beforeDtMs + nextDtMs)

        /** Varman virheen tilalle laskettava liikenopeus: naapurien liikenopeuksien väliltä ajan suhteessa. */
        internal fun between(before: Float, beforeDtMs: Long, next: Float, nextDtMs: Long): Float =
            before + (next - before) * beforeDtMs / (beforeDtMs + nextDtMs)
    }
}
