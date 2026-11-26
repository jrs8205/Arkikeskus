package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Mittausjonon säännöt (käyttäjän päätös 14: virhelukemista korjataan vain varma tapaus). Testit osoittavat, että
 * vaiheittainen toteutus antaa täsmälleen saman tuloksen kuin säännöt koko jonolle kerralla sovellettuina, että
 * kirjattu matka on lopullisten nopeuksien pinta-ala, ettei virheettömiin mittauksiin kosketa ja ettei yksi
 * virheellinen mittaus muuta muita mittauksia.
 *
 * Mittauksella on vastaanottimen nopeus ja tieto siitä, onko se liikettä. Ellei testi anna tietoa erikseen,
 * mittaus on liikettä, kun sen nopeus on vähintään sovelluksen liikeraja (2 km/h).
 */
class SpeedIntegratorTest {

    private val limit = TripCalculator.MOVING_MIN_MPS
    private val speeds = floatArrayOf(0f, 0f, 0.3f, 0.6f, 1f, 5f, 12f, 20f, 21f, 30f, 55f, 83f)
    private val steps = longArrayOf(500, 1000, 1000, 2000, 3000, 3001, 5000, 10_000)

    private fun Random.speed() = speeds[nextInt(speeds.size)]
    private fun Random.step() = steps[nextInt(steps.size)]

    /** Liikenopeus: nopeus, jos mittaus on liikettä, muuten 0. */
    private fun movingSpeed(v: Float) = if (v >= limit) v else 0f

    /**
     * Säännöt suoraan määritelmästä, koko jonolle kerralla. Liikenopeus on mittauksen nopeus, jos mittaus on
     * liikettä, muuten 0.
     * 1: varma virhe (vastaanottimen nopeuden muutos on mahdoton kumpaankin naapuriin, naapurit sopivat toisiinsa,
     * eikä kumpikaan naapuri täytä samaa ehtoa) saa liikenopeudekseen naapuriensa liikenopeuksien väliarvon;
     * mittaus, joka ei itse ole liikettä, kuitenkin vain, jos sen jommallakummalla puolella on kaksi peräkkäistä
     * liikettä olevaa mittausta.
     * 2: liikenopeus, jonka kummankaan naapurin liikenopeus ei säännön 1 jälkeen ole yli 0, on nolla.
     */
    private fun reference(times: LongArray, v: FloatArray, moving: BooleanArray): FloatArray {
        val n = v.size
        val m = FloatArray(n) { if (moving[it]) v[it] else 0f }
        fun candidate(j: Int) = j in 1 until n - 1 &&
            SpeedIntegrator.certainError(v[j - 1], times[j] - times[j - 1], v[j], v[j + 1], times[j + 1] - times[j])
        fun isMoving(j: Int) = j in 0 until n && m[j] > 0f
        val corrected = FloatArray(n) { j ->
            val mayRise = m[j] > 0f || (isMoving(j - 1) && isMoving(j - 2)) || (isMoving(j + 1) && isMoving(j + 2))
            if (candidate(j) && !candidate(j - 1) && !candidate(j + 1) && mayRise) {
                SpeedIntegrator.between(m[j - 1], times[j] - times[j - 1], m[j + 1], times[j + 1] - times[j])
            } else {
                m[j]
            }
        }
        return FloatArray(n) { j ->
            val movingNeighbor = (j > 0 && corrected[j - 1] > 0f) || (j < n - 1 && corrected[j + 1] > 0f)
            if (corrected[j] > 0f && movingNeighbor) corrected[j] else 0f
        }
    }

    /**
     * Ajaa mittaukset (aika ms, nopeus m/s, onko liikettä) jonona loppuun asti. Tarkistaa, että jokainen mittaus
     * saa lopullisen arvon kerran ja järjestyksessä, että arvot ovat samat kuin määritelmästä lasketut, että
     * kirjattu matka on lopullisten nopeuksien pinta-ala (keskeneräinen arvo ei siis koskaan ylitä lopullista) ja
     * että liikeaika on niiden välien summa, joille kirjautui matkaa. Palauttaa lopulliset nopeudet.
     */
    private fun runChain(
        times: LongArray, values: FloatArray, label: String,
        moving: BooleanArray = BooleanArray(values.size) { values[it] >= limit },
    ): FloatArray {
        val integrator = SpeedIntegrator()
        val settledAt = ArrayList<Long>()
        val settled = ArrayList<Float>()
        integrator.onSettled = { t, c ->
            settledAt += t
            settled += c
        }
        var meters = 0.0
        var movingMs = 0L
        fun take(d: SpeedIntegrator.Delta, nowMs: Long) {
            assertTrue("$label: negatiivinen matka", d.meters >= 0.0)
            assertTrue("$label: negatiivinen liikeaika", d.movingMs >= 0L)
            if (d.meters > 0.0) assertTrue("$label: liike ilman päättymishetkeä", d.lastMoveRtMs in times[0]..nowMs)
            meters += d.meters
            movingMs += d.movingMs
        }
        for (i in times.indices) take(integrator.add(times[i], values[i], moving[i]), times[i])
        take(integrator.finish(), times.last())

        assertEquals(label, times.toList(), settledAt)
        val expected = reference(times, values, moving)
        var area = 0.0
        var movingArea = 0L
        for (i in times.indices) {
            assertEquals("$label: kohta $i", expected[i], settled[i], 0f)
            if (i == 0) continue
            val dt = times[i] - times[i - 1]
            val a = SpeedIntegrator.intervalMeters(settled[i - 1], settled[i], dt)
            area += a
            if (a > 0.0) movingArea += dt
        }
        assertEquals("$label: kirjattu matka ei ole lopullisten nopeuksien pinta-ala", area, meters, 1e-3)
        assertEquals("$label: liikeaika", movingArea, movingMs)
        return settled.toFloatArray()
    }

    /** Satunnainen jono: nopeudet ja välit taulukoista; osa liikerajan ylittävistä mittauksista ei ole liikettä. */
    private fun randomChain(rnd: Random, n: Int): Triple<LongArray, FloatArray, BooleanArray> {
        val times = LongArray(n)
        val values = FloatArray(n)
        val moving = BooleanArray(n)
        var t = 0L
        for (i in 0 until n) {
            times[i] = t
            values[i] = rnd.speed()
            // Mittaus voi jäädä liikkeen ulkopuolelle myös oman nopeustarkkuutensa vuoksi, vaikka nopeus on suuri.
            moving[i] = values[i] >= limit && rnd.nextInt(7) != 0
            t += rnd.step()
        }
        return Triple(times, values, moving)
    }

    /** Vaiheittainen ratkaisu = määritelmä, ja kirjanpito täsmää, olipa mittausjono mikä tahansa. */
    @Test fun vaiheittainenRatkaisuVastaaMaaritelmaaSatunnaisillaJonoilla() {
        val rnd = Random(1L)
        repeat(30_000) { round ->
            val (times, values, moving) = randomChain(rnd, 1 + rnd.nextInt(16))
            runChain(times, values, "kierros $round ${values.toList()} ${moving.toList()} ${times.toList()}", moving)
        }
    }

    /** Sama pitkillä jonoilla (300 mittausta): ikkunasta unohdetut mittaukset eivät muuta tulosta. */
    @Test fun vaiheittainenRatkaisuVastaaMaaritelmaaPitkillaJonoilla() {
        val rnd = Random(2L)
        repeat(300) { round ->
            val (times, values, moving) = randomChain(rnd, 300)
            runChain(times, values, "kierros $round", moving)
        }
    }

    /**
     * Sama kaikille kuuden mittauksen jonoille pienestä arvojoukosta, tiheillä ja harvoilla väleillä. Arvot: 0,
     * 0,4 (alle liikerajan), 5, 20 ja 80 m/s sekä 20 m/s mittauksena, joka ei ole liikettä.
     */
    @Test fun vaiheittainenRatkaisuVastaaMaaritelmaaKaikillaLyhyillaJonoilla() {
        val alphabet = floatArrayOf(0f, 0.4f, 5f, 20f, 80f, 20f)
        val gaps = longArrayOf(1000, 5000)
        val n = 6
        val values = FloatArray(n)
        val moving = BooleanArray(n)
        val times = LongArray(n)
        var combos = 0
        for (code in 0 until 46_656) {       // 6^6
            var c = code
            for (i in 0 until n) {
                val symbol = c % 6
                values[i] = alphabet[symbol]
                moving[i] = symbol in 2..4
                c /= 6
            }
            for (gapMask in 0 until 32) {    // 2^5
                for (i in 1 until n) times[i] = times[i - 1] + gaps[(gapMask shr (i - 1)) and 1]
                runChain(times, values, "${values.toList()} ${moving.toList()} ${times.toList()}", moving)
                combos++
            }
        }
        assertEquals(1_492_992, combos)
    }

    /**
     * Jono, jossa jokainen vastaanottimen nopeuden muutos on mahdollinen, kelpaa sellaisenaan: jokaisen mittauksen
     * arvo on sen oma liikenopeus. Mukana on myös liikerajan alle jääviä nopeuksia: ne eivät ole liikettä, mutta
     * eivät myöskään virheitä (jono 10,2, 10,2, 0,4, 10,2, 10,2).
     */
    @Test fun virheettomiinMittauksiinEiKosketa() {
        val rnd = Random(5L)
        repeat(5_000) { round ->
            val n = 2 + rnd.nextInt(12)
            val times = LongArray(n)
            val values = FloatArray(n)
            var t = 0L
            var v = rnd.nextFloat() * 40f
            for (i in 0 until n) {
                times[i] = t
                values[i] = v
                val dt = rnd.step()
                t += dt
                // Muutos enintään 9,9 m/s² seuraavaan mittaukseen; nopeus välillä 0–83 m/s, joskus alle liikerajan.
                v = (v + (rnd.nextFloat() * 2f - 1f) * 9.9f * dt / 1000f).coerceIn(0f, 83f)
            }
            val settled = runChain(times, values, "kierros $round ${values.toList()} ${times.toList()}")
            for (i in 0 until n) {
                val hasMovingNeighbor = (i > 0 && values[i - 1] >= limit) || (i < n - 1 && values[i + 1] >= limit)
                val expected = if (values[i] >= limit && hasMovingNeighbor) values[i] else 0f
                assertEquals("kierros $round kohta $i", expected, settled[i], 0f)
            }
        }
        val reviewed = runChain(LongArray(5) { it * 1000L }, floatArrayOf(10.2f, 10.2f, 0.4f, 10.2f, 10.2f), "10,2 ja 0,4")
        assertEquals(listOf(10.2f, 10.2f, 0f, 10.2f, 10.2f), reviewed.toList())
    }

    /**
     * Muutoksen mahdollisuus arvioidaan vastaanottimen nopeuksista, ja liikeraja vaikuttaa vain siihen, mikä on
     * liikettä. Mittaus, joka ei ole liikettä mutta jonka nopeus sopii naapureihinsa, ei ole virhe. Varman virheen
     * arvo lasketaan naapurien liikenopeuksista, joten liikkeen ulkopuolelle jääneet naapurit eivät tuota liikettä.
     */
    @Test fun muutoksenMahdollisuusArvioidaanVastaanottimenNopeuksista() {
        fun secondly(values: FloatArray, moving: BooleanArray) =
            runChain(LongArray(values.size) { it * 1000L }, values, values.toList().toString(), moving).toList()
        val steady = floatArrayOf(10.2f, 10.2f, 10.2f, 10.2f, 10.2f)
        // Keskimmäinen mittaus ei ole liikettä (esim. nopeus alle oman tarkkuutensa), mutta sen nopeus sopii naapureihin.
        assertEquals(listOf(10.2f, 10.2f, 0f, 10.2f, 10.2f), secondly(steady, booleanArrayOf(true, true, false, true, true)))
        // Nopeus 0,1 m/s on mahdoton kumpaankin naapuriin (10,1 m/s²): varma virhe, korvataan naapurien väliarvolla.
        assertEquals(
            listOf(10.2f, 10.2f, 10.2f, 10.2f, 10.2f),
            secondly(floatArrayOf(10.2f, 10.2f, 0.1f, 10.2f, 10.2f), booleanArrayOf(true, true, false, true, true)),
        )
        // Piikki mittausten keskellä, jotka eivät ole liikettä: varma virhe, mutta liikettä ei synny.
        assertEquals(
            listOf(0f, 0f, 0f, 0f, 0f),
            secondly(floatArrayOf(20f, 20f, 80f, 20f, 20f), booleanArrayOf(false, false, true, false, false)),
        )
        // Katko, jonka kummallakaan puolella ei ole kahta peräkkäistä liikettä olevaa mittausta: ei koroteta.
        assertEquals(
            listOf(0f, 0f, 0f, 0f, 0f),
            secondly(floatArrayOf(20f, 20f, 0f, 20f, 20f), booleanArrayOf(true, false, false, true, false)),
        )
        // Sama katko, kun oikealla puolella on kaksi liikettä olevaa mittausta: korotetaan (0 ja 20 väliin).
        assertEquals(
            listOf(0f, 0f, 10f, 20f, 20f),
            secondly(floatArrayOf(20f, 20f, 0f, 20f, 20f), booleanArrayOf(true, false, false, true, true)),
        )
    }

    /**
     * Yksi virhe ei muuta muita mittauksia. Virheettömiä ajoja (tasainen, kiihdytys ja jarrutus enintään 9 m/s²,
     * nopeuden tason vaihdos, myös pitkän mittausvälin aikana, sekä pysähdys ja liikkeellelähtö) mittausvälein
     * 1, 2 ja 3 s, yksi viiden sekunnin väli missä tahansa kohdassa tai ei missään. Yksi mittaus missä tahansa
     * kohdassa (myös ensimmäinen ja viimeinen) korvataan virheellä 0–80 m/s.
     *
     * Virheellinen mittaus itse joko korvautuu naapuriensa väliarvolla (kun se on varma virhe) tai jää
     * sellaisekseen. Mikään muu mittaus ei muutu säännön 1 vuoksi. Sääntö 2 (liike vaatii kaksi peräkkäistä
     * liikenopeutta) voi muuttaa virheen viereisen mittauksen vain määritelmänsä mukaisesti: liikenopeus, jonka
     * ainoa liikkuva naapuri oli virheen kohdalla, ei ole liikettä, jos virheen kohdalla on nolla (ja päinvastoin).
     */
    @Test fun yksiVirheEiMuutaMuitaMittauksia() {
        val n = 9
        val profiles = ArrayList<Pair<String, (Int, Int) -> Float>>()
        for (v in listOf(3f, 8f, 10.2f, 15f, 30f)) profiles += "tasainen $v" to { _: Int, _: Int -> v }
        for (a in listOf(2f, 4f, 5f, 9f)) {
            profiles += "kiihdytys $a" to { i: Int, _: Int -> 5f + a * i }
            profiles += "jarrutus $a" to { i: Int, _: Int -> 5f + a * (n - 1 - i) }
        }
        for ((v1, v2) in listOf(20f to 25f, 25f to 20f, 12f to 25f, 25f to 12f, 30f to 10f, 10f to 30f, 34f to 10f, 10f to 34f)) {
            for (k in 1 until n) profiles += "taso $v1 → $v2 kohdassa $k" to { i: Int, _: Int -> if (i < k) v1 else v2 }
            profiles += "taso $v1 → $v2 pitkän välin aikana" to { i: Int, g: Int -> if (g in 0 until i) v2 else v1 }
        }
        for (a in listOf(2f, 3f, 5f)) {
            profiles += "liikkeellelähtö $a" to { i: Int, _: Int -> maxOf(0f, a * (i - 2)) }
            profiles += "pysähdys $a" to { i: Int, _: Int -> maxOf(0f, a * (5 - i)) }
            profiles += "pysähdys ja liikkeellelähtö $a" to { i: Int, _: Int -> a * maxOf(0, abs(i - 4) - 1) }
        }
        val wrongs = floatArrayOf(0f, 0.4f, 1f, 4f, 10f, 14f, 20f, 22f, 35f, 40f, 45f, 60f, 80f)
        var checked = 0
        var corrected = 0
        for ((name, speed) in profiles) for (stepMs in longArrayOf(1000, 2000, 3000)) for (gapAfter in -1..n - 2) {
            val times = LongArray(n)
            for (i in 1 until n) times[i] = times[i - 1] + if (i - 1 == gapAfter) 5000L else stepMs
            val clean = FloatArray(n) { speed(it, gapAfter) }
            // Vain virheettömät ajot: jokainen muutos on mahdollinen.
            if ((1 until n).any { !SpeedIntegrator.fits(clean[it - 1], clean[it], times[it] - times[it - 1]) }) continue
            val base = runChain(times, clean, name)
            for (errorAt in 0 until n) for (wrong in wrongs) {
                if (wrong == clean[errorAt]) continue
                val label = "$name, väli $stepMs ms, pitkä väli kohdan $gapAfter jälkeen, virhe $wrong kohdassa $errorAt"
                if (assertSingleErrorIsLocal(times, clean, base, errorAt, wrong, wrong >= limit, label)) corrected++
                checked++
            }
        }
        assertTrue("tapauksia vain $checked", checked > 150_000)
        assertTrue("varmoja virheitä vain $corrected", corrected > 30_000)
    }

    /**
     * Sama satunnaisilla virheettömillä ajoilla: pysähdyksiä, liikkeellelähtöjä, kiihdytyksiä ja jarrutuksia
     * (enintään 9,5 m/s²), mittausvälit 1–5 s sekaisin, 6–12 mittausta, yksi virhe satunnaisessa kohdassa. Osa
     * virheellisistä mittauksista ei ole liikettä, vaikka niiden nopeus on suuri.
     */
    @Test fun yksiVirheEiMuutaMuitaMittauksiaSatunnaisissaAjoissa() {
        val rnd = Random(42L)
        val stepChoices = longArrayOf(1000, 1000, 1000, 2000, 3000, 5000)
        var checked = 0
        var corrected = 0
        repeat(60_000) { round ->
            val n = 6 + rnd.nextInt(7)
            val times = LongArray(n)
            val clean = FloatArray(n)
            var t = 0L
            var v = if (rnd.nextInt(3) == 0) 0f else rnd.nextFloat() * 35f
            var stopped = if (v == 0f) 1 + rnd.nextInt(3) else 0
            for (i in 0 until n) {
                times[i] = t
                clean[i] = v
                val dt = stepChoices[rnd.nextInt(stepChoices.size)]
                t += dt
                val maxChange = (if (rnd.nextInt(4) == 0) 9.5f else 4f) * dt / 1000f
                if (stopped > 0) {
                    stopped--
                    if (stopped == 0) v = 0.6f + rnd.nextFloat() * minOf(maxChange, 8f)
                } else {
                    v += (rnd.nextFloat() * 2f - 1f) * maxChange
                    if (v < 0.6f) {
                        v = 0f
                        stopped = 2 + rnd.nextInt(3)
                    }
                    if (v > 80f) v = 80f
                }
            }
            if ((1 until n).any { !SpeedIntegrator.fits(clean[it - 1], clean[it], times[it] - times[it - 1]) }) return@repeat
            val errorAt = rnd.nextInt(n)
            val wrong = when (rnd.nextInt(6)) {
                0 -> 0f
                1 -> 1f
                2 -> clean[errorAt] + 11f + rnd.nextFloat() * 40f
                3 -> maxOf(0f, clean[errorAt] - 11f - rnd.nextFloat() * 20f)
                4 -> rnd.nextFloat() * 0.5f
                else -> rnd.nextFloat() * 83f
            }
            if (wrong == clean[errorAt] || wrong > 83f) return@repeat
            val wrongIsMoving = wrong >= limit && rnd.nextInt(5) != 0
            val label = "kierros $round ${clean.toList()} ${times.toList()}, virhe $wrong ($wrongIsMoving) kohdassa $errorAt"
            val base = runChain(times, clean, label)
            if (assertSingleErrorIsLocal(times, clean, base, errorAt, wrong, wrongIsMoving, label)) corrected++
            checked++
        }
        assertTrue("tapauksia vain $checked", checked > 40_000)
        assertTrue("varmoja virheitä vain $corrected", corrected > 8_000)
    }

    /**
     * Ajaa jonon, jonka mittaus [errorAt] on vaihdettu virheeksi [wrong], ja tarkistaa, että virhe pysyy
     * paikallisena. Virheellinen mittaus: varma virhe saa naapuriensa liikenopeuksien väliarvon, muu jää
     * sellaisekseen (kumpikin on nolla, jos sillä ei ole liikkuvaa naapuria tai korotus liikkeeksi ei ole sallittu).
     * Muut mittaukset: ennallaan, paitsi säännön 2 määritelmän mukainen muutos virheen vieressä (liikenopeus
     * menetti tai sai ainoan liikkuvan naapurinsa). Palauttaa, oliko virhe varma.
     */
    private fun assertSingleErrorIsLocal(
        times: LongArray, clean: FloatArray, base: FloatArray, errorAt: Int, wrong: Float, wrongIsMoving: Boolean,
        label: String,
    ): Boolean {
        val n = clean.size
        val values = clean.copyOf()
        values[errorAt] = wrong
        val moving = BooleanArray(n) { if (it == errorAt) wrongIsMoving else clean[it] >= limit }
        val settled = runChain(times, values, label, moving)
        val certain = errorAt in 1 until n - 1 && SpeedIntegrator.certainError(
            clean[errorAt - 1], times[errorAt] - times[errorAt - 1], wrong,
            clean[errorAt + 1], times[errorAt + 1] - times[errorAt],
        )
        if (certain) {
            val between = SpeedIntegrator.between(
                movingSpeed(clean[errorAt - 1]), times[errorAt] - times[errorAt - 1],
                movingSpeed(clean[errorAt + 1]), times[errorAt + 1] - times[errorAt],
            )
            val own = if (wrongIsMoving) wrong else 0f
            // Korotus liikkeeksi voi jäädä tekemättä (ei kahta peräkkäistä liikenopeutta vieressä): silloin oma arvo.
            assertTrue("$label: varma virhe ei korvautunut", settled[errorAt] == between || settled[errorAt] == 0f ||
                (own == 0f && settled[errorAt] == own))
        } else {
            val own = if (wrongIsMoving) wrong else 0f
            assertTrue("$label: muu kuin varma virhe muuttui", settled[errorAt] == own || settled[errorAt] == 0f)
        }
        for (j in 0 until n) {
            if (j == errorAt || settled[j] == base[j]) continue
            val nextToError = j == errorAt - 1 || j == errorAt + 1
            val lostNeighbor = settled[j] == 0f && settled[errorAt] == 0f
            val gainedNeighbor = base[j] == 0f && settled[j] == movingSpeed(clean[j]) && settled[errorAt] > 0f
            assertTrue("$label: kohta $j muuttui ${base[j]} → ${settled[j]}", nextToError && (lostNeighbor || gainedNeighbor))
        }
        return certain
    }

    /** Varman virheen ehto: mahdoton muutos kumpaankin naapuriin, ja naapurit sopivat toisiinsa. */
    @Test fun varmaVirheOnMahdotonKumpaankinNaapuriin() {
        // Piikki ja katko tasaisessa ajossa.
        assertTrue(SpeedIntegrator.certainError(20f, 1000, 80f, 20f, 1000))
        assertTrue(SpeedIntegrator.certainError(20f, 1000, 0f, 20f, 1000))
        // Piikki paikallaan ja pysähdyksen jälkeen (5, 0, [40], 0, 0).
        assertTrue(SpeedIntegrator.certainError(0f, 1000, 40f, 0f, 1000))
        // Sopii toiseen naapuriinsa: ei varma virhe, vaikka toinen muutos on mahdoton.
        assertFalse(SpeedIntegrator.certainError(20f, 1000, 31f, 40f, 1000))
        assertFalse(SpeedIntegrator.certainError(0f, 1000, 14f, 4f, 1000))
        assertFalse(SpeedIntegrator.certainError(10f, 1000, 22f, 22f, 3000))
        assertFalse(SpeedIntegrator.certainError(29f, 1000, 18f, 20f, 1000))
        // Hitaassa ajossa nolla on mahdollinen pysähdys.
        assertFalse(SpeedIntegrator.certainError(5f, 1000, 0f, 5f, 1000))
        // Liikerajan alle jäävä lukema, johon muutos on mahdollinen (9,8 m/s²), ei ole virhe; 10,1 m/s² on.
        assertFalse(SpeedIntegrator.certainError(10.2f, 1000, 0.4f, 10.2f, 1000))
        assertTrue(SpeedIntegrator.certainError(10.2f, 1000, 0.1f, 10.2f, 1000))
        // Naapurit eivät sovi toisiinsa: tasonvaihdos tai useita virheitä.
        assertFalse(SpeedIntegrator.certainError(20f, 1000, 80f, 50f, 1000))
        // Pitkän välin yli muutos on mahdoton vasta, kun se ylittää 10 m/s² × väli.
        assertFalse(SpeedIntegrator.certainError(20f, 5000, 1f, 20f, 1000))
        assertTrue(SpeedIntegrator.certainError(20f, 5000, 80f, 20f, 1000))
    }

    /**
     * Mittauspari, jonka todellinen kiihtyvyys on enintään raja (10 m/s²), sopii aina yhteen, vaikka aikaleimat
     * katkaistaan millisekunteihin ja nopeudet pyöristyvät liukuluvuiksi. Pahin tapaus on muutos täsmälleen rajalla:
     * 200 000 satunnaista paria todellisin (ei millisekunnin tarkoin) ajoin.
     */
    @Test fun todellinenMuutosRajallaSopiiMittaustarkkuudestaRiippumatta() {
        // Liukulukujen erotus 17,2 − 7,2 on hieman yli 10; todellinen muutos on 10.
        assertTrue(SpeedIntegrator.fits(7.2f, 17.2f, 1000))
        // Todellinen väli 1 000,45 ms (9,9994 m/s²), mitattu väli 1 000 ms.
        assertTrue(SpeedIntegrator.fits(10f, 20.00390625f, 1000))
        // Vara on pieni: aikaleiman tarkkuus 1 ms (0,01 m/s rajalla) ja nopeuden esitystarkkuus 0,001 m/s.
        assertTrue(SpeedIntegrator.fits(10f, 20.01f, 1000))
        assertFalse(SpeedIntegrator.fits(10f, 20.02f, 1000))
        assertTrue(SpeedIntegrator.fits(0f, 30f, 3000))
        assertFalse(SpeedIntegrator.fits(0f, 30.1f, 3000))

        val rnd = Random(10L)
        var checked = 0
        repeat(200_000) {
            val start = rnd.nextDouble() * 1000.0                 // todellinen aika (ms)
            val interval = 400.0 + rnd.nextDouble() * 9_600.0     // todellinen väli 0,4–10 s
            val a = rnd.nextDouble() * 70.0
            val change = SpeedIntegrator.ACCEL_MAX_MPS2 * interval / 1000.0
            val b = if (a - change < 0.0 || rnd.nextBoolean()) a + change else a - change
            if (b > 83.3) return@repeat
            val measured = Math.floor(start + interval).toLong() - Math.floor(start).toLong()
            assertTrue("$a → $b, väli $interval ms (mitattu $measured ms)", SpeedIntegrator.fits(a.toFloat(), b.toFloat(), measured))
            checked++
        }
        assertTrue("pareja vain $checked", checked > 100_000)
    }

    /** Korvaava arvo on naapurien väliltä ajan suhteessa. */
    @Test fun korvaavaArvoOnNaapurienValissa() {
        assertEquals(20f, SpeedIntegrator.between(10f, 1000, 30f, 1000), 1e-4f)
        assertEquals(15f, SpeedIntegrator.between(10f, 1000, 30f, 3000), 1e-4f)
        assertEquals(25f, SpeedIntegrator.between(10f, 3000, 30f, 1000), 1e-4f)
        assertEquals(0f, SpeedIntegrator.between(0f, 1000, 0f, 1000), 0f)
        val rnd = Random(7L)
        repeat(100_000) {
            val a = rnd.speed()
            val b = rnd.speed()
            val r = SpeedIntegrator.between(a, rnd.step(), b, rnd.step())
            assertTrue("$a $b: $r", r >= minOf(a, b) && r <= maxOf(a, b))
        }
    }

    /**
     * Vuorotteleva jono (20, 0, 20, 0, …) täyttää varman virheen ehdon vierekkäisissä mittauksissa: silloin
     * kumpaankaan ei kosketa, eivätkä erilliset piikit pääse vahvistamaan toisiaan nollan yli.
     */
    @Test fun vierekkaisetEhdokkaatEivatOleVarmojaVirheita() {
        fun secondly(vararg v: Float) = runChain(LongArray(v.size) { it * 1000L }, v, v.toList().toString())
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f), secondly(20f, 0f, 20f, 0f, 0f, 0f).toList())
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f), secondly(0f, 0f, 20f, 0f, 20f, 0f, 0f).toList())
        // Yksi katko liikkeen keskellä on varma virhe.
        assertEquals(listOf(20f, 20f, 20f, 20f, 20f), secondly(20f, 20f, 0f, 20f, 20f).toList())
    }

    /**
     * Liike vaatii kaksi peräkkäistä liikenopeutta, myös korjausten jälkeen: jokaisessa liikkeeksi lasketussa
     * jaksossa on kaksi peräkkäistä mittausta, jotka ovat itse liikettä. Erilliset piikit eivät siis voi kerryttää
     * matkaa missään jonossa, eivät myöskään nollan molemmin puolin tai pitkien välien vieressä.
     */
    @Test fun jokaisessaLiikejaksossaOnKaksiPerakkaistaLiikenopeutta() {
        val rnd = Random(8L)
        repeat(30_000) { round ->
            val n = 1 + rnd.nextInt(16)
            val times = LongArray(n)
            val values = FloatArray(n)
            val moving = BooleanArray(n)
            var t = 0L
            for (i in 0 until n) {
                times[i] = t
                // Puolet mittauksista ei ole liikettä (nolla, alle liikerajan tai suuri nopeus ilman liikettä),
                // jotta erillisiä piikkejä ja lyhyitä jaksoja on paljon.
                values[i] = if (rnd.nextInt(4) == 0) 0f else rnd.speed()
                moving[i] = values[i] >= limit && rnd.nextBoolean()
                t += rnd.step()
            }
            val label = "kierros $round ${values.toList()} ${moving.toList()} ${times.toList()}"
            val settled = runChain(times, values, label, moving)
            var i = 0
            while (i < n) {
                if (settled[i] == 0f) {
                    i++
                    continue
                }
                var end = i
                while (end + 1 < n && settled[end + 1] > 0f) end++
                assertTrue("$label: jakso $i–$end", (i until end).any { moving[it] && moving[it + 1] })
                i = end + 1
            }
        }
        // Kaksi erillistä piikkiä nollan ympärillä, ulommat mittaukset pitkän välin takana: nolla ei ole katko.
        val spikes = runChain(longArrayOf(0, 9000, 10_000, 11_000, 20_000), floatArrayOf(0f, 80f, 0f, 80f, 0f), "piikit")
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), spikes.toList())
    }

    /** Välin matka: tiheällä välillä kuvaajan pinta-ala, harvalla pienempi päiden nopeuksista. */
    @Test fun valinMatkaTiheallaJaHarvallaValilla() {
        assertEquals(45.0, SpeedIntegrator.intervalMeters(10f, 20f, 3_000), 1e-9)
        assertEquals(30.01, SpeedIntegrator.intervalMeters(10f, 20f, 3_001), 1e-9)
        assertEquals(0.0, SpeedIntegrator.intervalMeters(0f, 20f, 5_000), 0.0)
        // Suurimmatkaan kelvolliset nopeudet eivät vuoda yli: summa lasketaan kaksoistarkkuudella.
        assertTrue(SpeedIntegrator.intervalMeters(Float.MAX_VALUE, Float.MAX_VALUE, 1_000).isFinite())
    }

    /**
     * Uusin mittaus, johon muutos edeltäjästä on mahdoton, odottaa seuraajaansa nollana. Jos jono päättyy ennen
     * sitä, mittaus kirjautuu sellaisenaan: jonon viimeistä mittausta ei korjata.
     */
    @Test fun keskenerainenMittausOnNollaJaKirjautuuJononPaattyessa() {
        val integrator = SpeedIntegrator()
        var meters = 0.0
        for ((t, v) in listOf(0L to 20f, 1000L to 20f, 2000L to 80f)) meters += integrator.add(t, v, true).meters
        assertEquals(30.0, meters, 1e-9)                      // 20 + (20 + 0) / 2
        assertEquals(40.0, integrator.finish().meters, 1e-9)  // (20 + 80) / 2 − 10

        // Seuraajat osoittavat piikin varmaksi virheeksi: väli korjautuu ylöspäin, ei koskaan alaspäin.
        val spike = SpeedIntegrator()
        val deltas = listOf(0L to 20f, 1000L to 20f, 2000L to 80f, 3000L to 20f, 4000L to 20f)
            .map { (t, v) -> spike.add(t, v, true).meters }
        assertEquals(80.0, deltas.sum(), 1e-9)
        assertTrue(deltas.all { it >= 0.0 })
        assertEquals(0.0, spike.finish().meters, 1e-9)
    }
}
