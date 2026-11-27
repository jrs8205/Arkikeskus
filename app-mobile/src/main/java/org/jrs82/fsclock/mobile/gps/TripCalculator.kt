package org.jrs82.fsclock.mobile.gps

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class TripPhase { NONE, RUNNING, PAUSED, FINISHED }

/** Käyttäjän toiminnot: näkymän painikkeet ja ilmoituksen toiminnot. */
enum class TripAction { START, PAUSE, RESUME, FINISH, RESET, NEW_TRIP, CONTINUE }

/**
 * Matkan tilannekuva tietyllä hetkellä. Muuttumaton: näkymät, ilmoitus ja tallennus lukevat samaa oliota.
 * [totalMs] on aika tilassa RUNNING (tauot eivät kuulu siihen). [idleMs] on käynnissä olevan matkan aika
 * viimeisimmästä liikkeestä tai aloituksesta/jatkamisesta; muissa tiloissa 0.
 */
data class TripSnapshot(
    val phase: TripPhase = TripPhase.NONE,
    val distanceM: Double = 0.0,
    val totalMs: Long = 0L,
    val movingMs: Long = 0L,
    val maxSpeedMps: Float = 0f,
    val idleMs: Long = 0L,
    val reminded: Boolean = false,
    val waitingForCar: Boolean = false,
) {
    /** Pysähdysaika = kokonaisaika − liikeaika, joten kolme aikaa täsmäävät aina keskenään. */
    val stoppedMs: Long get() = (totalMs - movingMs).coerceAtLeast(0L)

    /** Keskinopeus koko matkalta (pysähdykset mukana); null, kunnes luku on mielekäs. */
    val avgTotalKmh: Double? get() = average(totalMs)

    /** Keskinopeus liikkeessä; null, kunnes luku on mielekäs. */
    val avgMovingKmh: Double? get() = average(movingMs)

    /** Muistutus unohtuneesta matkasta on ajankohtainen: käynnissä, ei vielä muistutettu, paikallaan [limitMs]. */
    fun reminderDue(limitMs: Long = TripCalculator.REMINDER_IDLE_MS): Boolean =
        phase == TripPhase.RUNNING && !reminded && idleMs >= limitMs

    private fun average(ms: Long): Double? =
        if (distanceM > 0.0 && ms >= TripCalculator.AVG_MIN_MS) distanceM / (ms / 1000.0) * 3.6 else null
}

/** Tallennushetki: seinäkello, laitteen käynnistyksestä kulunut aika ja tallentaneen prosessin tunniste. */
data class SaveStamp(val wallMs: Long, val elapsedMs: Long, val session: String)

/** Järjestelmän kirjaama prosessin päättyminen: prosessin tunniste (jos se ehdittiin asettaa) ja seinäkellonaika. */
data class ProcessExit(val session: String?, val wallMs: Long)

/**
 * Matkan tila ja laskentasäännöt. Ei Android-riippuvuuksia: syötteinä komennot ja mittaukset
 * kellonaikoineen (ms laitteen käynnistyksestä), tuloksena [TripSnapshot].
 *
 * Matka lasketaan nopeudesta (nopeus × aika), ei sijaintipisteiden välisistä etäisyyksistä: GPS:n oma
 * nopeus ei riipu sijainnista, joten sijaintihyppy tai paikallaan ryömivä sijainti ei kasvata matkaa.
 * Sijaintia käytetään vain signaalikatkon yli, kahden nopeudellisen mittauksen välillä. Mittaus, jossa ei
 * ole vastaanottimen nopeutta, ohitetaan: ilman nopeutta matkaa ei kerry.
 *
 * Peräkkäiset mittaukset (väli enintään 10 s) muodostavat mittausjonon, jonka matkan laskee [SpeedIntegrator]:
 * tiheässä jonossa matka on nopeuden kuvaajan pinta-ala. Mittauksiin kosketaan vain kahdessa tapauksessa: varma
 * virhe (vastaanottimen nopeuden mahdoton muutos kumpaankin naapuriin) korvataan naapurien väliarvolla, ja liike
 * vaatii kaksi peräkkäistä liikenopeutta. Jono päättyy taukoon, lopetukseen ja katkoon; silloin kirjataan myös mittaukset, jotka odottivat
 * vielä seuraajaansa.
 *
 * Ei säieturvallinen: omistaja synkronoi kutsut. Komennot palauttavat true, jos tila muuttui.
 */
class TripCalculator(initial: TripSnapshot = TripSnapshot()) {

    private var phase: TripPhase
    private var distanceM: Double
    private var totalBeforeRunMs: Long   // kokonaisaika ennen nykyistä käynnissä-jaksoa
    private var movingMs: Long
    private var maxSpeedMps: Float
    private var runSinceRtMs = 0L        // nykyisen käynnissä-jakson alku
    private var lastMoveRtMs = 0L        // viimeisin liike tai jakson alku
    private var reminded = false
    private var prev: GpsFix? = null     // edellinen kelvollinen mittaus, jossa oli vastaanottimen nopeus
    private val integrator = SpeedIntegrator() // nykyisen mittausjonon nopeuksien vahvistus ja integrointi
    private var idleSeq = 0L             // paikallaanolojakson tunniste: kasvaa liikkeestä ja jakson alusta

    init {
        val s = restore(initial)
        phase = s.phase
        distanceM = s.distanceM
        totalBeforeRunMs = s.totalMs
        movingMs = s.movingMs
        maxSpeedMps = s.maxSpeedMps
    }

    fun snapshot(nowRtMs: Long): TripSnapshot {
        val running = phase == TripPhase.RUNNING
        val total = totalBeforeRunMs + if (running) (nowRtMs - runSinceRtMs).coerceAtLeast(0L) else 0L
        return TripSnapshot(
            phase = phase,
            distanceM = distanceM,
            totalMs = total,
            movingMs = movingMs.coerceAtMost(total),
            maxSpeedMps = maxSpeedMps,
            idleMs = if (running) (nowRtMs - lastMoveRtMs).coerceAtLeast(0L) else 0L,
            reminded = reminded,
        )
    }

    /** Aloita matka tai Uusi matka: luvut nollataan. */
    fun start(nowRtMs: Long): Boolean {
        if (phase != TripPhase.NONE && phase != TripPhase.FINISHED) return false
        distanceM = 0.0
        totalBeforeRunMs = 0L
        movingMs = 0L
        maxSpeedMps = 0f
        beginRun(nowRtMs)
        return true
    }

    fun pause(nowRtMs: Long): Boolean {
        if (phase != TripPhase.RUNNING) return false
        endRun(nowRtMs)
        phase = TripPhase.PAUSED
        return true
    }

    /** Jatka (tauolta) tai Jatka tätä matkaa (lopetetusta): luvut säilyvät, väliaikaa ei lasketa. */
    fun resume(nowRtMs: Long): Boolean {
        if (phase != TripPhase.PAUSED && phase != TripPhase.FINISHED) return false
        beginRun(nowRtMs)
        return true
    }

    fun finish(nowRtMs: Long): Boolean {
        if (phase != TripPhase.RUNNING && phase != TripPhase.PAUSED) return false
        if (phase == TripPhase.RUNNING) endRun(nowRtMs)
        phase = TripPhase.FINISHED
        return true
    }

    /** Nollaa: yhteenveto pois. */
    fun reset(): Boolean {
        if (phase != TripPhase.FINISHED) return false
        phase = TripPhase.NONE
        distanceM = 0.0
        totalBeforeRunMs = 0L
        movingMs = 0L
        maxSpeedMps = 0f
        return true
    }

    /**
     * Varaa muistutuksen nykyiselle paikallaanolojaksolle, jos sen ehto täyttyy hetkellä [nowRtMs].
     * Palauttaa jakson tunnisteen tai null. Tarkistus ja varaus ovat yksi operaatio, jottei niiden väliin
     * osuva liike jää merkityksi muistutetuksi.
     */
    fun claimReminder(nowRtMs: Long, limitMs: Long = REMINDER_IDLE_MS): Long? {
        if (!snapshot(nowRtMs).reminderDue(limitMs)) return null
        reminded = true
        return idleSeq
    }

    /** Peruu varauksen, kun muistutusta ei saatu näytettyä. Ei vaikuta, jos jakso on jo vaihtunut. */
    fun releaseReminder(token: Long) {
        if (phase == TripPhase.RUNNING && token == idleSeq) reminded = false
    }

    fun onFix(fix: GpsFix) {
        if (phase != TripPhase.RUNNING) return
        // Ennen aloitusta tai jatkamista mitattu (esim. välimuistista tullut) sijainti ei kuulu tähän jaksoon.
        if (fix.rtMs < runSinceRtMs) return
        if (!fix.lat.isFinite() || !fix.lon.isFinite()) return
        val accuracy = fix.accuracyM ?: return
        if (!accuracy.isFinite() || accuracy < 0f || accuracy > ACCURACY_MAX_M) return
        // Matka lasketaan vain vastaanottimen omasta nopeudesta. Mittaus ilman kelvollista nopeutta ohitetaan
        // kokonaan: se ei kerrytä, nollaa eikä katkaise mitään, ja seuraava nopeudellinen mittaus kattaa välin.
        // Pelkistä sijainneista ei voi luotettavasti erottaa liikettä mittauskohinasta.
        val speed = validSpeed(fix) ?: return
        val moving = isMoving(fix, speed)
        val last = prev
        if (last == null) {
            // Jakson ensimmäinen kelvollinen mittaus aloittaa mittausjonon.
            integrator.reset()
            credit(integrator.add(fix.rtMs, speed, moving))
            prev = fix
            return
        }
        val dtMs = fix.rtMs - last.rtMs
        if (dtMs <= 0L) return

        if (dtMs <= CONTINUOUS_MAX_MS) {
            credit(integrator.add(fix.rtMs, speed, moving))
        } else {
            // Katko: nopeuksista ei voi päätellä välin matkaa, joten käytetään suoraa etäisyyttä. Edellinen
            // mittausjono päättyy, ja uusi alkaa tästä mittauksesta.
            credit(integrator.finish())
            val dtSec = dtMs / 1000.0
            val gapM = haversineM(last.lat, last.lon, fix.lat, fix.lon)
            val gapSpeed = gapM / dtSec
            if (dtMs <= GAP_MAX_MS && gapM >= GAP_MIN_M && gapSpeed >= MOVING_MIN_MPS && gapSpeed <= SPEED_MAX_MPS) {
                accrue(gapM, dtMs, fix.rtMs)
            }
            integrator.reset()
            credit(integrator.add(fix.rtMs, speed, moving))
        }

        val lastSpeed = validSpeed(last) ?: 0f
        if (dtMs <= PEAK_PAIR_MAX_MS && speedAccuracyOk(fix) && speedAccuracyOk(last)) {
            val candidate = min(speed, lastSpeed)
            if (candidate > maxSpeedMps) maxSpeedMps = candidate
        }
        prev = fix
    }

    /**
     * Onko mittaus liikettä: nopeus on vähintään 2 km/h ja suurempi kuin mittauksen oma nopeustarkkuus. Liikeraja
     * ratkaisee vain sen, kertyykö mittauksesta matkaa; muutoksen mahdollisuuden [SpeedIntegrator] arvioi
     * vastaanottimen alkuperäisestä nopeudesta.
     */
    private fun isMoving(fix: GpsFix, speed: Float): Boolean {
        val speedAccuracy = validSpeedAccuracy(fix)
        return speed >= MOVING_MIN_MPS && (speedAccuracy == null || speed > speedAccuracy)
    }

    private fun credit(delta: SpeedIntegrator.Delta) {
        if (delta.meters > 0.0) accrue(delta.meters, delta.movingMs, delta.lastMoveRtMs)
    }

    /** Kirjaa matkaa. [movingDtMs] on väli, joka lasketaan liikeajaksi (0, jos se on jo laskettu). */
    private fun accrue(meters: Double, movingDtMs: Long, endRtMs: Long) {
        distanceM += meters
        movingMs += movingDtMs
        if (endRtMs > lastMoveRtMs) lastMoveRtMs = endRtMs
        reminded = false
        idleSeq++
    }

    private fun beginRun(nowRtMs: Long) {
        phase = TripPhase.RUNNING
        runSinceRtMs = nowRtMs
        lastMoveRtMs = nowRtMs
        prev = null
        integrator.reset()
        reminded = false
        idleSeq++
    }

    private fun endRun(nowRtMs: Long) {
        // Mittausjono päättyy: seuraajaa odottaneet mittaukset kirjataan ennen kuin jakson ajat lasketaan.
        credit(integrator.finish())
        totalBeforeRunMs += (nowRtMs - runSinceRtMs).coerceAtLeast(0L)
        movingMs = movingMs.coerceAtMost(totalBeforeRunMs)
        prev = null
        integrator.reset()
        reminded = false
    }

    private fun speedAccuracyOk(fix: GpsFix): Boolean {
        val a = validSpeedAccuracy(fix) ?: return true
        return a <= PEAK_SPEED_ACC_MAX_MPS
    }

    /**
     * Oma nopeus vain, jos se on äärellinen, ei-negatiivinen ja enintään [SPEED_MAX_MPS]; muuten mittauksessa ei
     * ole nopeutta. Yläraja estää myös äärellisen mutta mielettömän arvon ylivuodon matkaan tai huippunopeuteen.
     */
    private fun validSpeed(fix: GpsFix): Float? =
        fix.speedMps?.takeIf { it.isFinite() && it >= 0f && it <= SPEED_MAX_MPS }

    private fun validSpeedAccuracy(fix: GpsFix): Float? =
        fix.speedAccuracyMps?.takeIf { it.isFinite() && it >= 0f }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Liikkeen raja: sama 2 km/h, jonka alla nopeusnäyttö näyttää nollaa. */
        const val MOVING_MIN_MPS = 2f / 3.6f
        /** Suurin sijaintitarkkuus (m), jolla mittaus kelpaa laskentaan. */
        const val ACCURACY_MAX_M = 25f
        /** Tätä pidempi väli kelvollisten mittausten välillä on katko. */
        const val CONTINUOUS_MAX_MS = 10_000L
        /** Pisin katko, jonka yli lasketaan suora etäisyys. */
        const val GAP_MAX_MS = 300_000L
        /** Lyhin etäisyys, joka katkon yli lasketaan. */
        const val GAP_MIN_M = 100.0
        /** Suurin uskottava nopeus (300 km/h): katkon keskinopeuden ja vastaanottimen oman nopeuden yläraja. */
        const val SPEED_MAX_MPS = 300f / 3.6f
        /** Seinäkellon ja monotonisen kellon suurin sallittu ero palautuksen aikahyvityksessä. */
        const val CLOCK_TOLERANCE_MS = 5_000L
        /** Huippunopeuden parin suurin väli. */
        const val PEAK_PAIR_MAX_MS = 3_000L
        /** Huippunopeuteen kelpaavan mittauksen suurin nopeustarkkuus. */
        const val PEAK_SPEED_ACC_MAX_MPS = 2f
        /** Keskinopeus näytetään vasta, kun aikaa on kertynyt tämän verran. */
        const val AVG_MIN_MS = 10_000L
        /** Muistutus unohtuneesta matkasta: paikallaan kaksi tuntia. */
        const val REMINDER_IDLE_MS = 7_200_000L

        /** Tallennetun tilan palautus prosessin käynnistyessä: käynnissä ollut matka on tauolla. */
        /**
         * Tallennetun tilan palautus prosessin käynnistyessä: käynnissä ollut matka on tauolla.
         * [runningCreditMs] on aika viimeisestä tallennuksesta prosessin kuolemaan: matka oli sen ajan
         * käynnissä, vaikka mittauksia ei tullut eikä tilaa tallennettu (esim. pysäköintihalli näyttö pimeänä).
         */
        fun restore(saved: TripSnapshot, runningCreditMs: Long = 0L): TripSnapshot {
            val wasRunning = saved.phase == TripPhase.RUNNING
            return saved.copy(
                phase = if (wasRunning) TripPhase.PAUSED else saved.phase,
                totalMs = if (wasRunning) saved.totalMs + runningCreditMs.coerceAtLeast(0L) else saved.totalMs,
                idleMs = 0L,
                reminded = false,
            )
        }

        /**
         * Aika viimeisestä tallennuksesta matkaa ajaneen prosessin päättymiseen. [exitWallMs] on kyseisen
         * prosessin päättymisaika seinäkellossa ([exitOfSession]); tallennuksessa ja nykyhetkessä on lisäksi
         * laitteen käynnistyksestä kulunut aika. Ei hyvitystä, jos tallennusleimaa tai päättymistä ei ole,
         * laite on käynnistetty uudelleen, seinäkelloa on siirretty tai päättymisaika osuu avaushetken jälkeen.
         * Hyvitys ei ylitä tallennuksesta kulunutta monotonista aikaa.
         */
        fun runningCreditMs(saved: SaveStamp?, exitWallMs: Long?, nowWallMs: Long, nowElapsedMs: Long): Long {
            if (saved == null || exitWallMs == null) return 0L
            // Monotoninen kello ei riipu seinäkellon siirroista. Negatiivinen ero = laite on käynnistetty uudelleen.
            val elapsedSinceSave = nowElapsedMs - saved.elapsedMs
            if (elapsedSinceSave < 0L) return 0L
            // Samalla käynnistyskerralla kellojen pitää edetä samaa tahtia. Jos eivät etene, seinäkelloa on
            // siirretty tai laite on käynnistetty uudelleen: päättymisaikaa ei voi verrata tallennusaikaan.
            val wallSinceSave = nowWallMs - saved.wallMs
            if (abs(wallSinceSave - elapsedSinceSave) > CLOCK_TOLERANCE_MS) return 0L
            // Päättymisaika avaushetken jälkeen on mahdoton: seinäkello on ollut väärässä prosessin päättyessä
            // (ja korjattu sen jälkeen). Ristiriitainen kirjaus hylätään, sitä ei rajata avaushetkeen.
            // Päättymisaika ja avaushetki ovat samasta seinäkellosta, joten ne verrataan suoraan: muuten
            // sallittu kelloero tulisi käytetyksi kahdesti (kellojen ero ja päättymisajan ylitys).
            if (exitWallMs - nowWallMs > CLOCK_TOLERANCE_MS) return 0L
            val credit = exitWallMs - saved.wallMs
            if (credit > elapsedSinceSave + CLOCK_TOLERANCE_MS) return 0L
            return credit.coerceAtMost(elapsedSinceSave).coerceAtLeast(0L)
        }

        /**
         * Matkaa ajaneen prosessin päättymisaika järjestelmän kirjauksista. Vain kirjaus, jossa on sama
         * prosessin tunniste kuin tallenteessa, kelpaa: myöhemmän tausta- tai widget-prosessin päättyminen ei
         * saa päätyä matkan loppuajaksi, jos matkaprosessin omaa päättymistä ei ole kirjattu.
         */
        fun exitOfSession(exits: List<ProcessExit>, session: String): Long? {
            if (session.isEmpty()) return null
            return exits.firstOrNull { it.session == session }?.wallMs
        }

        /** Pisteiden välinen etäisyys metreinä pallon pinnalla (R = 6 371 000 m). */
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = EARTH_RADIUS_M
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = Math.toRadians(lat2 - lat1)
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
