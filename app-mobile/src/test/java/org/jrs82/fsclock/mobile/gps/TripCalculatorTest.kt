package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matkan laskennan säännöt (suunnitteluasiakirjan luku Laskenta). Odotusarvot on laskettu käsin:
 *  72 km/h = 20 m/s, yksi leveysaste = 111 194,93 m (R = 6 371 000 m). */
class TripCalculatorTest {

    private val degM = 111_194.93

    private fun fix(
        tSec: Double,
        lat: Double = 60.0,
        kmh: Double? = null,
        acc: Float? = 5f,
        speedAcc: Float? = null,
    ) = GpsFix((tSec * 1000).toLong(), lat, 25.0, kmh?.let { (it / 3.6).toFloat() }, acc, speedAcc)

    private fun TripCalculator.drive(fromSec: Int, toSec: Int, kmh: Double) {
        for (t in fromSec..toSec) onFix(fix(t.toDouble(), kmh = kmh))
    }

    private fun started(): TripCalculator = TripCalculator().also { it.start(0) }

    @Test fun vakionopeusAntaaNopeusKertaaAika() {
        val c = started()
        c.drive(0, 60, 72.0)
        val s = c.snapshot(60_000)
        assertEquals(1200.0, s.distanceM, 0.5)
        assertEquals(60_000L, s.movingMs)
        assertEquals(60_000L, s.totalMs)
        assertEquals(0L, s.stoppedMs)
        assertEquals(72.0, s.avgTotalKmh!!, 0.1)
        assertEquals(72.0, s.avgMovingKmh!!, 0.1)
    }

    @Test fun paikallaanRyomivaSijaintiEiKerry() {
        val c = started()
        for (t in 0..600) {
            val lat = if (t % 2 == 0) 60.0001 else 59.9999
            c.onFix(fix(t.toDouble(), lat = lat, kmh = 0.5))
        }
        val s = c.snapshot(600_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
        assertEquals(600_000L, s.stoppedMs)
    }

    @Test fun kohinainenNopeusAlleOmanTarkkuutensaEiOleLiiketta() {
        val noisy = started()
        for (t in 0..60) noisy.onFix(fix(t.toDouble(), kmh = 4.0, speedAcc = 1.5f))
        assertEquals(0.0, noisy.snapshot(60_000).distanceM, 0.0)
        assertEquals(0L, noisy.snapshot(60_000).movingMs)

        val clean = started()
        for (t in 0..60) clean.onFix(fix(t.toDouble(), kmh = 4.0, speedAcc = 0.5f))
        assertEquals(66.7, clean.snapshot(60_000).distanceM, 0.5)
        assertEquals(60_000L, clean.snapshot(60_000).movingMs)
    }

    @Test fun sijaintihyppyEiMuutaMatkaa() {
        val mid = started()
        for (t in 0..10) mid.onFix(fix(t.toDouble(), lat = if (t == 5) 60.01 else 60.0, kmh = 72.0))
        assertEquals(200.0, mid.snapshot(10_000).distanceM, 0.5)

        val first = started()
        for (t in 0..10) first.onFix(fix(t.toDouble(), lat = if (t == 0) 60.01 else 60.0, kmh = 72.0))
        assertEquals(200.0, first.snapshot(10_000).distanceM, 0.5)
    }

    @Test fun huippuVaatiiKaksiPerakkaista() {
        fun peak(times: List<Double>, kmh: List<Double>, speedAcc: List<Float?> = kmh.map { null }): Float {
            val c = started()
            for (i in times.indices) c.onFix(fix(times[i], kmh = kmh[i], speedAcc = speedAcc[i]))
            return c.snapshot((times.last() * 1000).toLong()).maxSpeedMps
        }
        val each = listOf(0.0, 1.0, 2.0, 3.0, 4.0)
        // Yksittäinen 120 km/h:n piikki ei kelpaa: huippu jää 50 km/h:iin.
        assertEquals(13.89f, peak(each, listOf(50.0, 50.0, 120.0, 50.0, 50.0)), 0.01f)
        // Kaksi peräkkäistä 90 ja 95: huippu on niistä pienempi.
        assertEquals(25.0f, peak(each.take(4), listOf(50.0, 90.0, 95.0, 50.0)), 0.01f)
        // Sama pari epätarkalla nopeudella (3 m/s) ei kelpaa.
        assertEquals(
            13.89f,
            peak(
                listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0),
                listOf(50.0, 50.0, 90.0, 95.0, 50.0, 50.0),
                listOf(null, null, 3f, 3f, null, null),
            ),
            0.01f,
        )
        // Parin väli 4 s on liian pitkä.
        assertEquals(13.89f, peak(listOf(0.0, 1.0, 2.0, 6.0, 7.0), listOf(50.0, 50.0, 90.0, 95.0, 50.0)), 0.01f)
    }

    @Test fun johdettuNopeusEiKelpaaHuipuksi() {
        val c = started()
        for (t in 0..10) c.onFix(fix(t.toDouble(), lat = 60.0 + t * 0.0001))
        assertEquals(0f, c.snapshot(10_000).maxSpeedMps, 0f)
    }

    @Test fun huonoTaiPuuttuvaTarkkuusOhitetaan() {
        val c = started()
        c.onFix(fix(0.0, kmh = 72.0))
        for (t in 1..5) c.onFix(fix(t.toDouble(), kmh = 72.0, acc = if (t % 2 == 0) null else 30f))
        c.onFix(fix(6.0, kmh = 72.0))
        val s = c.snapshot(6_000)
        assertEquals(120.0, s.distanceM, 0.5)
        assertEquals(6_000L, s.movingMs)
    }

    @Test fun vaaraAikajarjestysJaSamaAikaOhitetaan() {
        val c = started()
        c.onFix(fix(0.0, kmh = 72.0))
        c.onFix(fix(2.0, kmh = 72.0))
        c.onFix(fix(1.0, lat = 60.01))
        c.onFix(fix(2.0, lat = 60.02))
        val s = c.snapshot(2_000)
        assertEquals(40.0, s.distanceM, 0.5)
        assertEquals(2_000L, s.movingMs)
        c.onFix(fix(3.0, kmh = 72.0))
        assertEquals(60.0, c.snapshot(3_000).distanceM, 0.5)
    }

    @Test fun mittausEnnenAloitustaOhitetaan() {
        val c = TripCalculator()
        c.start(10_000)
        c.onFix(fix(9.0, kmh = 72.0))
        c.onFix(fix(11.0, kmh = 72.0))
        c.onFix(fix(12.0, kmh = 72.0))
        val s = c.snapshot(12_000)
        assertEquals(20.0, s.distanceM, 0.5)
        assertEquals(1_000L, s.movingMs)
        assertEquals(2_000L, s.totalMs)
    }

    @Test fun taukoJaadyttaaJaJatkaminenEiLaskeTauonYli() {
        val c = started()
        c.drive(0, 10, 72.0)
        assertTrue(c.pause(10_000))
        for (t in 11..20) c.onFix(fix(t.toDouble(), lat = 60.0 + t * 0.001, kmh = 72.0))
        val paused = c.snapshot(50_000)
        assertEquals(200.0, paused.distanceM, 0.5)
        assertEquals(10_000L, paused.totalMs)
        assertEquals(10_000L, paused.movingMs)

        assertTrue(c.resume(100_000))
        c.onFix(fix(100.0, lat = 60.01, kmh = 72.0))
        c.onFix(fix(101.0, lat = 60.01, kmh = 72.0))
        val s = c.snapshot(101_000)
        assertEquals(220.0, s.distanceM, 0.5)
        assertEquals(11_000L, s.totalMs)
        assertEquals(11_000L, s.movingMs)
    }

    @Test fun katkoLasketaanSuoranaEtaisyytena() {
        val c = started()
        c.onFix(fix(0.0, lat = 60.0, kmh = 72.0))
        c.onFix(fix(60.0, lat = 60.009, kmh = 72.0))
        val s = c.snapshot(60_000)
        assertEquals(0.009 * degM, s.distanceM, 1.0)
        assertEquals(60_000L, s.movingMs)
    }

    @Test fun katkoEiKerryRajojenUlkopuolella() {
        // (katkon pituus s, lat katkon jälkeen): yli 5 min; alle 100 m; alle 2 km/h; yli 300 km/h.
        val cases = listOf(361.0 to 60.02, 60.0 to 60.00045, 290.0 to 60.00108, 11.0 to 60.018)
        for ((gapSec, lat) in cases) {
            val c = started()
            c.onFix(fix(0.0, lat = 60.0, kmh = 72.0))
            c.onFix(fix(gapSec, lat = lat, kmh = 72.0))
            val after = c.snapshot((gapSec * 1000).toLong())
            assertEquals("katko $gapSec s", 0.0, after.distanceM, 0.0)
            assertEquals("katko $gapSec s", 0L, after.movingMs)
            // Katkon päättänyt mittaus jäi edelliseksi: seuraava sekunti lisää tasan nopeus x aika.
            c.onFix(fix(gapSec + 1, lat = lat, kmh = 72.0))
            val next = c.snapshot(((gapSec + 1) * 1000).toLong())
            assertEquals("katko $gapSec s", 20.0, next.distanceM, 0.01)
            assertEquals("katko $gapSec s", 1_000L, next.movingMs)
        }
    }

    @Test fun ajatTasmaavat() {
        val c = started()
        c.drive(0, 30, 72.0)
        for (t in 31..90) c.onFix(fix(t.toDouble(), kmh = 0.0))
        c.drive(91, 120, 36.0)
        for (now in listOf(15_000L, 30_000L, 60_000L, 90_000L, 120_000L, 500_000L)) {
            val s = c.snapshot(now)
            assertEquals(s.totalMs, s.movingMs + s.stoppedMs)
            assertTrue(s.movingMs <= s.totalMs)
        }
        val end = c.snapshot(120_000)
        // Nopeus vaihtuu tässä yhden mittausvälin aikana (72 → 0 ja 0 → 36 km/h). Matka on nopeuden kuvaajan
        // pinta-ala: 30 s × 20 m/s, pysähdyksen väli (20 + 0) / 2, liikkeellelähdön väli (0 + 10) / 2 ja
        // 29 s × 10 m/s. Kummassakin vaihtumisvälissä on liikettä, joten ne kuuluvat liikeaikaan.
        assertEquals(61_000L, end.movingMs)
        assertEquals(59_000L, end.stoppedMs)
        assertEquals(905.0, end.distanceM, 0.01)
    }

    @Test fun keskinopeudetNakyvatVastaKymmenenSekunninJalkeen() {
        assertNull(TripSnapshot(TripPhase.RUNNING, 180.0, 9_999, 9_000).avgTotalKmh)
        assertEquals(64.8, TripSnapshot(TripPhase.RUNNING, 180.0, 10_000, 9_000).avgTotalKmh!!, 0.01)
        assertNull(TripSnapshot(TripPhase.RUNNING, 180.0, 10_000, 9_000).avgMovingKmh)
        assertEquals(72.0, TripSnapshot(TripPhase.RUNNING, 200.0, 10_000, 10_000).avgMovingKmh!!, 0.01)
        assertNull(TripSnapshot(TripPhase.RUNNING, 0.0, 20_000, 0).avgTotalKmh)
        assertNull(TripSnapshot(TripPhase.RUNNING, 0.0, 20_000, 20_000).avgMovingKmh)
    }

    @Test fun tilasiirtymat() {
        fun inPhase(p: TripPhase): TripCalculator = TripCalculator().also {
            when (p) {
                TripPhase.NONE -> {}
                TripPhase.RUNNING -> it.start(0)
                TripPhase.PAUSED -> { it.start(0); it.pause(1_000) }
                TripPhase.FINISHED -> { it.start(0); it.finish(1_000) }
            }
        }
        val commands: Map<String, (TripCalculator) -> Boolean> = linkedMapOf(
            "start" to { c -> c.start(5_000) },
            "pause" to { c -> c.pause(5_000) },
            "resume" to { c -> c.resume(5_000) },
            "finish" to { c -> c.finish(5_000) },
            "reset" to { c -> c.reset() },
        )
        // Sallitut siirtymät ja niiden kohdetila; kaikki muu ei saa muuttaa mitään.
        val allowed = mapOf(
            (TripPhase.NONE to "start") to TripPhase.RUNNING,
            (TripPhase.RUNNING to "pause") to TripPhase.PAUSED,
            (TripPhase.RUNNING to "finish") to TripPhase.FINISHED,
            (TripPhase.PAUSED to "resume") to TripPhase.RUNNING,
            (TripPhase.PAUSED to "finish") to TripPhase.FINISHED,
            (TripPhase.FINISHED to "reset") to TripPhase.NONE,
            (TripPhase.FINISHED to "start") to TripPhase.RUNNING,
            (TripPhase.FINISHED to "resume") to TripPhase.RUNNING,
        )
        for (from in TripPhase.values()) {
            for ((name, command) in commands) {
                val c = inPhase(from)
                val changed = command(c)
                val target = allowed[from to name]
                assertEquals("$from.$name", target != null, changed)
                assertEquals("$from.$name", target ?: from, c.snapshot(5_000).phase)
            }
        }
        // Kaksoisnapautus Lopeta: toinen ei tee mitään.
        val twice = inPhase(TripPhase.RUNNING)
        assertTrue(twice.finish(2_000))
        assertFalse(twice.finish(3_000))
        assertEquals(2_000L, twice.snapshot(9_000).totalMs)
    }

    @Test fun uusiMatkaNollaaJaJatkaTataMatkaaSailyttaa() {
        val cont = started()
        cont.drive(0, 10, 72.0)
        cont.finish(10_000)
        assertTrue(cont.resume(500_000))
        val kept = cont.snapshot(500_000)
        assertEquals(TripPhase.RUNNING, kept.phase)
        assertEquals(200.0, kept.distanceM, 0.5)
        assertEquals(10_000L, kept.totalMs)
        assertEquals(20f, kept.maxSpeedMps, 0.01f)

        val fresh = started()
        fresh.drive(0, 10, 72.0)
        fresh.finish(10_000)
        assertTrue(fresh.start(500_000))
        val zero = fresh.snapshot(500_000)
        assertEquals(TripPhase.RUNNING, zero.phase)
        assertEquals(0.0, zero.distanceM, 0.0)
        assertEquals(0L, zero.totalMs)
        assertEquals(0L, zero.movingMs)
        assertEquals(0f, zero.maxSpeedMps, 0f)
    }

    @Test fun nollausTyhjentaaYhteenvedon() {
        val c = started()
        c.drive(0, 10, 72.0)
        c.finish(10_000)
        assertTrue(c.reset())
        assertEquals(TripSnapshot(), c.snapshot(20_000))
    }

    @Test fun palautusMuuttaaKaynnissaTauolle() {
        val running = TripSnapshot(TripPhase.RUNNING, 200.0, 10_000, 9_000, 20f, idleMs = 5, reminded = true)
        assertEquals(TripSnapshot(TripPhase.PAUSED, 200.0, 10_000, 9_000, 20f), TripCalculator.restore(running))
        for (p in listOf(TripPhase.NONE, TripPhase.PAUSED, TripPhase.FINISHED)) {
            val s = TripSnapshot(p, 200.0, 10_000, 9_000, 20f)
            assertEquals(s, TripCalculator.restore(s))
        }
        // Palautettu matka jatkuu samoista luvuista eikä laske katkon aikaa.
        val c = TripCalculator(running)
        assertEquals(TripPhase.PAUSED, c.snapshot(999_000).phase)
        assertEquals(10_000L, c.snapshot(999_000).totalMs)
        assertTrue(c.resume(1_000_000))
        assertEquals(10_500L, c.snapshot(1_000_500).totalMs)
    }

    @Test fun muistutusTuleeKahdenTunninPaikallaanolosta() {
        val c = started()
        assertFalse(c.snapshot(7_199_999).reminderDue())
        assertTrue(c.snapshot(7_200_000).reminderDue())
        assertTrue(c.claimReminder(7_200_000) != null)
        assertFalse(c.snapshot(7_300_000).reminderDue())

        // Liike virittää muistutuksen uudelleen ja nollaa paikallaanoloajan.
        c.onFix(fix(7_300.0, kmh = 72.0))
        c.onFix(fix(7_301.0, kmh = 72.0))
        val moved = c.snapshot(7_301_000)
        assertEquals(0L, moved.idleMs)
        assertFalse(moved.reminded)
        assertFalse(c.snapshot(7_301_000 + 7_199_999).reminderDue())
        assertTrue(c.snapshot(7_301_000 + 7_200_000).reminderDue())
    }

    @Test fun tauollaEiMuistuteta() {
        val c = started()
        c.pause(1_000)
        assertFalse(c.snapshot(50_000_000).reminderDue())
        assertEquals(0L, c.snapshot(50_000_000).idleMs)
        // Jatkamisen jälkeen paikallaanolo lasketaan jatkamishetkestä.
        c.resume(50_000_000)
        assertFalse(c.snapshot(50_000_000 + 7_199_999).reminderDue())
        assertTrue(c.snapshot(50_000_000 + 7_200_000).reminderDue())
    }

    // ---- nopeudeton vastaanotin: siirtymä erotetaan mittausepävarmuudesta ----

    /** Paikallaan oleva vastaanotin ilman omaa nopeutta: sijainti heiluu sekunnin välein 2,2 m, tarkkuus 5 m. */
    @Test fun nopeudetonPaikallaanHeiluvaSijaintiEiKerry() {
        val c = started()
        val wobble = 2.2 / degM
        for (t in 0..600) c.onFix(fix(t.toDouble(), lat = 60.0 + if (t % 2 == 0) 0.0 else wobble))
        val s = c.snapshot(600_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
        // Paikallaanoloaika kertyy, joten muistutus voi virittyä.
        assertEquals(600_000L, s.idleMs)
    }

    @Test fun nopeudetonYksittainenHyppyEiKerry() {
        val c = started()
        for (t in 0..60) c.onFix(fix(t.toDouble(), lat = 60.0 + if (t == 30) 40.0 / degM else 0.0))
        val s = c.snapshot(60_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
    }

    // ---- kelvottomat arvot ----

    @Test fun aaretonTaiKelvotonNopeusEiRikoMatkaa() {
        val c = started()
        c.drive(0, 10, 72.0)
        c.onFix(GpsFix(11_000, 60.0, 25.0, Float.POSITIVE_INFINITY, 5f, null))
        c.onFix(GpsFix(12_000, 60.0, 25.0, Float.NaN, 5f, null))
        c.onFix(GpsFix(13_000, 60.0, 25.0, -5f, 5f, null))
        c.drive(14, 20, 72.0)
        val s = c.snapshot(20_000)
        // Kelvoton nopeus = mittaus ilman nopeutta: se ohitetaan, ja seuraava kelvollinen nopeus kattaa välin.
        // 10 s + 4 s (kolme ohitettua mittausta) + 6 s nopeudella 20 m/s.
        assertEquals(400.0, s.distanceM, 0.5)
        assertEquals(20f, s.maxSpeedMps, 0.01f)
    }

    @Test fun kelvotonNopeustarkkuusKasitellaanPuuttuvana() {
        val c = started()
        for (t in 0..10) c.onFix(fix(t.toDouble(), kmh = 72.0, speedAcc = Float.NaN))
        val s = c.snapshot(10_000)
        assertEquals(200.0, s.distanceM, 0.5)
        assertEquals(20f, s.maxSpeedMps, 0.01f)
    }

    @Test fun kelvotonSijaintiOhitetaanEikaSeMyrkytaKatkonLaskentaa() {
        val c = started()
        c.drive(0, 5, 72.0)
        c.onFix(GpsFix(6_000, Double.NaN, 25.0, 20f, 5f, null))
        // Minuutin katko, jonka aikana on siirrytty 1200 m: lasketaan viimeisestä kelvollisesta sijainnista.
        c.onFix(fix(66.0, lat = 60.0 + 1200.0 / degM, kmh = 72.0))
        assertEquals(1300.0, c.snapshot(66_000).distanceM, 1.0)
    }

    // ---- muistutuksen varaus ----

    @Test fun muistutusVarataanYhdelleJaksolleJaPerutaanVainSamalleJaksolle() {
        val c = started()
        assertNull(c.claimReminder(7_199_999))
        val first = c.claimReminder(7_200_000)
        assertTrue(first != null)
        // Sama pysähdys: toista muistutusta ei tule.
        assertNull(c.claimReminder(7_250_000))

        // Liike aloittaa uuden jakson. Vanhan jakson myöhässä tuleva peruutus ei saa koskea siihen.
        c.onFix(fix(7_300.0, kmh = 72.0))
        c.onFix(fix(7_301.0, kmh = 72.0))
        val second = c.claimReminder(7_301_000 + 7_200_000)
        assertTrue(second != null)
        c.releaseReminder(first!!)
        assertTrue(c.snapshot(7_301_000 + 7_200_000).reminded)
        assertNull(c.claimReminder(7_301_000 + 7_200_001))

        // Muistutusta ei saatu näytettyä: varaus perutaan ja sama pysähdys saa yrittää uudelleen.
        c.releaseReminder(second!!)
        assertFalse(c.snapshot(7_301_000 + 7_200_001).reminded)
        assertTrue(c.claimReminder(7_301_000 + 7_200_002) != null)
    }

    @Test fun tauollaTaiLopetettunaMuistutustaEiVoiVarata() {
        val c = started()
        c.pause(1_000)
        assertNull(c.claimReminder(50_000_000))
        c.finish(50_000_000)
        assertNull(c.claimReminder(90_000_000))
    }

    // ---- palautus prosessin kuoleman jälkeen ----

    @Test fun palautusLaskeeKaynnissaoloajanProsessinKuolemaanAsti() {
        val saved = TripSnapshot(TripPhase.RUNNING, 500.0, 60_000, 30_000, 10f)
        val restored = TripCalculator.restore(saved, runningCreditMs = 600_000)
        assertEquals(TripPhase.PAUSED, restored.phase)
        assertEquals(660_000L, restored.totalMs)
        assertEquals(30_000L, restored.movingMs)
        assertEquals(630_000L, restored.stoppedMs)
        assertEquals(500.0, restored.distanceM, 0.0)
        // Vain käynnissä ollut matka saa hyvityksen, eikä negatiivinen hyvitys lyhennä aikaa.
        assertEquals(60_000L, TripCalculator.restore(saved.copy(phase = TripPhase.PAUSED), 600_000).totalMs)
        assertEquals(60_000L, TripCalculator.restore(saved.copy(phase = TripPhase.FINISHED), 600_000).totalMs)
        assertEquals(60_000L, TripCalculator.restore(saved, -5).totalMs)
    }

    // ---- katselmointikierros 2: nopeudeton ja sekamuotoinen mittausjono ----

    private fun at(tSec: Int, northM: Double, eastM: Double = 0.0, kmh: Double? = null, acc: Float = 5f) = GpsFix(
        tSec * 1000L,
        60.0 + northM / degM,
        25.0 + eastM / (degM * Math.cos(Math.toRadians(60.0))),
        kmh?.let { (it / 3.6).toFloat() },
        acc,
        null,
    )

    /** Suora 1000 m nopeudella 10 m/s; vastaanotin antaa nopeuden vain joka toisessa mittauksessa. */
    @Test fun vuorottelevaOmaNopeusEiHukkaaMatkaa() {
        val c = started()
        for (t in 0..100) c.onFix(at(t, 10.0 * t, kmh = if (t % 2 == 0) 36.0 else null))
        val s = c.snapshot(100_000)
        assertEquals(1000.0, s.distanceM, 1.0)
        assertEquals(100_000L, s.movingMs)
    }

    @Test fun vuorottelevaOmaNopeusHitaassaLiikkeessa() {
        val c = started()
        for (t in 0..100) c.onFix(at(t, 1.4 * t, kmh = if (t % 2 == 0) 5.04 else null))
        val s = c.snapshot(100_000)
        assertEquals(140.0, s.distanceM, 1.0)
        assertEquals(100_000L, s.movingMs)
    }

    /** Oma nopeus sanoo paikallaan; välissä tulevat nopeudettomat mittaukset heiluvat. */
    @Test fun omaNopeusNollaKumoaaNopeudettomienHeilunnan() {
        val c = started()
        for (t in 0..120) c.onFix(at(t, if (t % 2 == 0) 0.0 else 6.0, kmh = if (t % 2 == 0) 0.0 else null))
        val s = c.snapshot(120_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
    }

    /** 30 s välein kaksi peräkkäistä pistettä: 21 m lähtöpisteen toisella ja sitten vastakkaisella puolella. */
    @Test fun vastakkaisetPoikkeamatEivatVahvistaToisiaan() {
        val c = started()
        for (t in 0..600) {
            val off = when {
                t > 0 && t % 30 == 0 -> 21.0
                t > 1 && t % 30 == 1 -> -21.0
                else -> 0.0
            }
            c.onFix(at(t, off))
        }
        val s = c.snapshot(600_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
    }

    @Test fun kahdenPisteenPoikkeamaSamassaPaikassaEiKerry() {
        val c = started()
        for (t in 0..60) c.onFix(at(t, if (t == 30 || t == 31) 30.0 else 0.0))
        assertEquals(0.0, c.snapshot(60_000).distanceM, 0.0)
    }

    @Test fun matkanEnsimmainenPisteSivussaEiKerry() {
        val c = started()
        for (t in 0..60) c.onFix(at(t, if (t == 0) 40.0 else 0.0))
        val s = c.snapshot(60_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
    }

    @Test fun katkonJalkeinenPisteSivussaEiKerry() {
        val c = started()
        for (t in 0..30) c.onFix(at(t, 0.0))
        for (t in 60..120) c.onFix(at(t, if (t == 60) 40.0 else 0.0))
        val s = c.snapshot(120_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
    }

    // ---- katselmointikierros 2: palautuksen aikahyvitys ----

    private val stamp = SaveStamp(wallMs = 1_000_000L, elapsedMs = 500_000L, session = "matkaprosessi")

    @Test fun hyvitysOnAikaTallennuksestaProsessinPaattymiseen() {
        // Tallennus, prosessi päättyy 400 s myöhemmin, sovellus avataan 1000 s tallennuksen jälkeen.
        assertEquals(400_000L, TripCalculator.runningCreditMs(stamp, 1_400_000L, 2_000_000L, 1_500_000L))
        // Ei tallennusleimaa tai päättymiskirjausta: ei hyvitystä.
        assertEquals(0L, TripCalculator.runningCreditMs(null, 1_400_000L, 2_000_000L, 1_500_000L))
        assertEquals(0L, TripCalculator.runningCreditMs(stamp, null, 2_000_000L, 1_500_000L))
        // Päättyminen ennen tallennusta ei lyhennä aikaa.
        assertEquals(0L, TripCalculator.runningCreditMs(stamp, 900_000L, 2_000_000L, 1_500_000L))
    }

    /** Seinäkelloa siirretty vuorokausi eteenpäin tallennuksen jälkeen; prosessi päättyi 5 s tallennuksesta. */
    @Test fun hyvitysHylataanKunSeinakelloEiTasmaaMonotoniseenKelloon() {
        val day = 86_400_000L
        val credit = TripCalculator.runningCreditMs(
            stamp, exitWallMs = stamp.wallMs + day + 5_000, nowWallMs = stamp.wallMs + day + 60_000,
            nowElapsedMs = stamp.elapsedMs + 60_000,
        )
        assertEquals(0L, credit)
        // Sama taaksepäin.
        assertEquals(
            0L,
            TripCalculator.runningCreditMs(stamp, stamp.wallMs - day + 5_000, stamp.wallMs - day + 60_000, stamp.elapsedMs + 60_000),
        )
    }

    @Test fun hyvitysHylataanUudenKaynnistyskerranJalkeen() {
        // Laite käynnistetty uudelleen: käynnistyksestä kulunut aika on pienempi kuin tallennuksessa.
        assertEquals(0L, TripCalculator.runningCreditMs(stamp, 1_400_000L, 2_000_000L, 120_000L))
        // Uusi käynnistyskerta on ehtinyt pidemmälle kuin vanha: kellot eivät silti täsmää.
        assertEquals(0L, TripCalculator.runningCreditMs(stamp, 1_400_000L, 9_000_000L, 700_000L))
    }

    @Test fun hyvitysEiYlitaTallennuksestaKulunuttaMonotonistaAikaa() {
        // Päättymisaika on sallitun kelloeron verran avaushetken jälkeen: hyväksytään, mutta enintään kulunut aika.
        assertEquals(300_000L, TripCalculator.runningCreditMs(stamp, 1_302_000L, 1_300_000L, 800_000L))
    }

    /**
     * Seinäkello oli vuorokauden edellä prosessin päättyessä ja korjattiin ennen sovelluksen avaamista:
     * tallennuksen ja avauksen kellot täsmäävät, mutta päättymisaika on avaushetkeen nähden tulevaisuudessa.
     */
    @Test fun hyvitysHylataanKunPaattymisaikaOnAvaushetkenJalkeen() {
        val hour = 3_600_000L
        val day = 86_400_000L
        assertEquals(
            0L,
            TripCalculator.runningCreditMs(stamp, stamp.wallMs + day + 5_000, stamp.wallMs + hour, stamp.elapsedMs + hour),
        )
        // Minuutin verran avaushetken jälkeen: ristiriitainen kirjaus hylätään, sitä ei rajata avaushetkeen.
        assertEquals(
            0L,
            TripCalculator.runningCreditMs(stamp, stamp.wallMs + hour + 60_000, stamp.wallMs + hour, stamp.elapsedMs + hour),
        )
        // Uskottava kirjaus samassa tilanteessa hyväksytään.
        assertEquals(
            5_000L,
            TripCalculator.runningCreditMs(stamp, stamp.wallMs + 5_000, stamp.wallMs + hour, stamp.elapsedMs + hour),
        )
    }

    /** Matkaprosessin päättymistä ei kirjattu; myöhemmän tausta- tai widget-prosessin päättyminen ei kelpaa. */
    @Test fun hyvitysKohdistuuVainMatkaaAjaneeseenProsessiin() {
        val exits = listOf(
            ProcessExit("widget", 90_000_000L),
            ProcessExit(null, 80_000_000L),
            ProcessExit("matkaprosessi", 1_400_000L),
            ProcessExit("vanha", 900_000L),
        )
        assertEquals(1_400_000L, TripCalculator.exitOfSession(exits, "matkaprosessi"))
        assertNull(TripCalculator.exitOfSession(exits.filter { it.session != "matkaprosessi" }, "matkaprosessi"))
        assertNull(TripCalculator.exitOfSession(emptyList(), "matkaprosessi"))
        assertNull(TripCalculator.exitOfSession(exits, ""))
    }

    // ---- satunnaiskohina (kiinteä siemen, joten tulos on toistettava) ----

    /**
     * Paikallaan oleva nopeudeton vastaanotin, jonka sijainti heittelee joka mittauksessa toisistaan
     * riippumatta (hajonta 3 m, ilmoitettu tarkkuus 5 m), 10 minuuttia. Heilunnan askelten yhteispituus on
     * kilometrejä; siitä saa kirjautua matkaksi enintään kaksi prosenttia.
     */
    @Test fun nopeudetonSatunnaiskohinaPaikallaanEiKerryMerkittavasti() {
        val random = java.util.Random(20261121L)
        val c = started()
        var wobbleM = 0.0
        var previous: Pair<Double, Double>? = null
        for (t in 0..600) {
            val north = random.nextGaussian() * 3.0
            val east = random.nextGaussian() * 3.0
            previous?.let { wobbleM += Math.hypot(north - it.first, east - it.second) }
            previous = north to east
            c.onFix(at(t, north, east))
        }
        val s = c.snapshot(600_000)
        assertTrue("heilunta ${wobbleM.toInt()} m, kirjattu ${s.distanceM} m", wobbleM > 2_000.0)
        assertTrue("kirjattu ${s.distanceM} m", s.distanceM <= 0.02 * wobbleM)
        assertTrue("liikeaika ${s.movingMs}", s.movingMs <= 12_000L)
    }

    // ---- käyttäjän päätös 11: matka lasketaan vain vastaanottimen omasta nopeudesta ----

    /** Vastaanotin antaa uuden sijainnin vain joka toinen sekunti ja nopeuden joka toisessa mittauksessa. */
    @Test fun toistuvaSijaintiEiHukkaaMatkaa() {
        val c = started()
        for (t in 0..100) c.onFix(at(t, 20.0 * (t / 2), kmh = if (t % 2 == 0) 36.0 else null))
        val s = c.snapshot(100_000)
        assertEquals(1000.0, s.distanceM, 1.0)
        assertEquals(100_000L, s.movingMs)
    }

    /** Vastaanotin, joka ei anna nopeutta lainkaan: matkaa ei kerry, vaikka sijainti etenee. */
    @Test fun ilmanNopeuttaMatkaaEiKerry() {
        val c = started()
        for (t in 0..100) c.onFix(at(t, 10.0 * t))
        val s = c.snapshot(100_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
        assertEquals(0f, s.maxSpeedMps, 0f)
        assertEquals(100_000L, s.idleMs)
    }

    /** Paikallaan oleva nopeudeton vastaanotin heiluu 0,8 m/s edestakaisin välillä −2…+2 m, tarkkuus 5 m. */
    @Test fun hidasHeiluntaTarkkuusalueenSisallaEiKerry() {
        val c = started()
        // Viisi 0,8 m:n askelta reunasta reunaan ja takaisin: jokainen pyyhkäisy on viisi samansuuntaista askelta.
        for (t in 0..600) {
            val k = t % 10
            c.onFix(at(t, -2.0 + 0.8 * (if (k <= 5) k else 10 - k)))
        }
        val s = c.snapshot(600_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
        assertEquals(600_000L, s.idleMs)
    }

    /** Neliöreitti, sivu 50 m, mittaus 5 s välein kulmissa, ei nopeutta: rajauksen mukaan ei kerry. */
    @Test fun nopeudetonNelioreittiEiKerry() {
        val c = started()
        val corners = listOf(0.0 to 0.0, 50.0 to 0.0, 50.0 to 50.0, 0.0 to 50.0)
        for (i in 0..100) c.onFix(at(i * 5, corners[i % 4].first, corners[i % 4].second, acc = 3f))
        assertEquals(0.0, c.snapshot(500_000).distanceM, 0.0)
    }

    /** Matkan ensimmäinen nopeudeton piste on 900 m sivussa: hylätyt mittaukset eivät saa synnyttää katkoa. */
    @Test fun kaukanaOlevaEnsimmainenPisteEiSynnytaKatkoa() {
        val c = started()
        for (t in 0..60) c.onFix(at(t, if (t == 0) 900.0 else 0.0))
        val s = c.snapshot(60_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
    }

    /** Sama nopeudellisella vastaanottimella: nopeus on nolla, joten sijaintihyppy ei kerry. */
    @Test fun kaukanaOlevaEnsimmainenPisteNopeudellaNollaEiKerry() {
        val c = started()
        for (t in 0..60) c.onFix(at(t, if (t == 0) 900.0 else 0.0, kmh = 0.0))
        assertEquals(0.0, c.snapshot(60_000).distanceM, 0.0)
    }

    /** Yli 10 s:n jakso ilman nopeutta kahden nopeudellisen mittauksen välissä lasketaan katkosäännöllä. */
    @Test fun pitkaNopeudetonJaksoLasketaanKatkona() {
        val c = started()
        c.onFix(at(0, 0.0, kmh = 36.0))
        for (t in 1..19) c.onFix(at(t, 10.0 * t))
        c.onFix(at(20, 200.0, kmh = 36.0))
        val s = c.snapshot(20_000)
        assertEquals(200.0, s.distanceM, 0.5)
        assertEquals(20_000L, s.movingMs)
    }

    /** Nopeudeton mittaus ei katkaise huippunopeuden paria eikä vaikuta siihen. */
    @Test fun nopeudetonMittausEiVaikutaHuippunopeuteen() {
        val c = started()
        for (t in 0..10) c.onFix(at(t, 20.0 * t, kmh = if (t % 2 == 0) 72.0 else null))
        assertEquals(20f, c.snapshot(10_000).maxSpeedMps, 0.01f)
    }

    // ---- yksittäinen virheellinen nopeus ----

    /** Paikallaan: yksi virheellinen nopeus (300 m/s) 10 s:n välin jälkeen ei saa kirjautua matkaksi eikä liikeajaksi. */
    @Test fun yksittainenVirheellinenNopeusPaikallaanEiKerry() {
        // 1080 km/h on katselmoinnin alkuperäinen jono (nyt kelvoton nopeus, joka ohitetaan); 288 km/h on kelvollinen
        // nopeus, jonka suodatus hylkää.
        for (kmh in listOf(1080.0, 288.0)) {
            val c = started()
            c.onFix(fix(0.0, kmh = 0.0))
            c.onFix(fix(10.0, kmh = kmh))
            c.onFix(fix(11.0, kmh = 0.0))
            val s = c.snapshot(11_000)
            assertEquals("$kmh km/h", 0.0, s.distanceM, 0.0)
            assertEquals("$kmh km/h", 0L, s.movingMs)
            assertEquals("$kmh km/h", 0f, s.maxSpeedMps, 0f)
        }
    }

    /** Sama uskottavan suuruisella virheellä: 54 km/h aukon jälkeen, ennen sitä ja sen jälkeen paikallaan. */
    @Test fun yksittainenUskottavanSuuruinenVirhenopeusAukonJalkeenEiKerry() {
        val c = started()
        c.onFix(fix(0.0, kmh = 0.0))
        c.onFix(fix(1.0, kmh = 0.0))
        c.onFix(fix(10.0, kmh = 54.0))
        c.onFix(fix(11.0, kmh = 0.0))
        c.onFix(fix(12.0, kmh = 0.0))
        val s = c.snapshot(12_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
    }

    /** Sekunnin välein tulevissa mittauksissa yksi poikkeava nopeus (36 km/h) paikallaan. */
    @Test fun yksittainenNopeuspiikkiPaikallaanEiKerry() {
        val c = started()
        for (t in 0..10) c.onFix(fix(t.toDouble(), kmh = if (t == 5) 36.0 else 0.0))
        val s = c.snapshot(10_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
    }

    /** Ajossa yksi liian suuri nopeus ei lisää matkaa: 20 s nopeudella 72 km/h on 400 m. */
    @Test fun yksittainenNopeuspiikkiAjossaEiLisaaMatkaa() {
        val c = started()
        for (t in 0..20) c.onFix(fix(t.toDouble(), kmh = if (t == 11) 288.0 else 72.0))
        val s = c.snapshot(20_000)
        assertEquals(400.0, s.distanceM, 0.01)
        assertEquals(20_000L, s.movingMs)
        assertEquals(20f, s.maxSpeedMps, 0.01f)
    }

    /** Ajossa yksi virheellinen nolla ei hukkaa matkaa eikä liikeaikaa. */
    @Test fun yksittainenVirheellinenNollaAjossaEiHukkaaMatkaa() {
        val c = started()
        for (t in 0..20) c.onFix(fix(t.toDouble(), kmh = if (t == 11) 0.0 else 72.0))
        val s = c.snapshot(20_000)
        assertEquals(400.0, s.distanceM, 0.01)
        assertEquals(20_000L, s.movingMs)
    }

    /**
     * Tasainen kiihdytys 0 → 72 km/h (10 s), ajo (10 s) ja jarrutus (10 s): matka on nopeuden kuvaajan
     * pinta-ala 100 + 200 + 100 m. Poikkeavien nopeuksien suodatus ei saa vinouttaa tavallista ajoa.
     * Liikettä on hetkestä 0 hetkeen 30, eli 30 s: myös ensimmäinen ja viimeinen väli ovat liikettä.
     */
    @Test fun kiihdytysAjoJaJarrutusKertyvatKuvaajanPintaAlana() {
        val c = started()
        for (t in 0..31) {
            val mps = when {
                t <= 10 -> 2.0 * t
                t <= 20 -> 20.0
                t <= 30 -> 20.0 - 2.0 * (t - 20)
                else -> 0.0
            }
            c.onFix(fix(t.toDouble(), kmh = mps * 3.6))
        }
        val s = c.snapshot(31_000)
        assertEquals(400.0, s.distanceM, 0.01)
        assertEquals(30_000L, s.movingMs)
        assertEquals(20f, s.maxSpeedMps, 0.01f)
    }

    /**
     * Aukon (yli 3 s ilman kelvollista mittausta) yli kirjataan vain nopeus, jonka aukon molemmat päät
     * vahvistavat: tasainen ajo aukon läpi kertyy kokonaan, aukon aikana pysähtynyt tai liikkeelle lähtenyt ei.
     */
    @Test fun aukonYliKirjataanPienempiPaatepisteidenNopeuksista() {
        val through = started()
        through.onFix(fix(0.0, kmh = 72.0))
        through.onFix(fix(1.0, kmh = 72.0))
        through.onFix(fix(9.0, kmh = 72.0))
        assertEquals(180.0, through.snapshot(9_000).distanceM, 0.01)

        val stopped = started()
        stopped.onFix(fix(0.0, kmh = 72.0))
        stopped.onFix(fix(1.0, kmh = 72.0))
        stopped.onFix(fix(9.0, kmh = 0.0))
        stopped.onFix(fix(10.0, kmh = 0.0))
        assertEquals(20.0, stopped.snapshot(10_000).distanceM, 0.01)

        val setOff = started()
        setOff.onFix(fix(0.0, kmh = 0.0))
        setOff.onFix(fix(1.0, kmh = 0.0))
        setOff.onFix(fix(9.0, kmh = 72.0))
        setOff.onFix(fix(10.0, kmh = 72.0))
        assertEquals(20.0, setOff.snapshot(10_000).distanceM, 0.01)
    }

    /**
     * Sallittu kelloero ei saa tulla käytetyksi kahdesti: tallennuksesta avaukseen 3 600 s monotonista aikaa ja
     * 3 596 s seinäkelloa, päättymiskirjaus 3 603 s tallennuksesta eli 7 s avaushetken jälkeen.
     */
    @Test fun hyvitysHylataanKunPaattymisaikaOnYliKelloeronAvaushetkenSeinakellonJalkeen() {
        val sec = 1_000L
        assertEquals(
            0L,
            TripCalculator.runningCreditMs(
                stamp, stamp.wallMs + 3_603 * sec, stamp.wallMs + 3_596 * sec, stamp.elapsedMs + 3_600 * sec,
            ),
        )
        // Rajalla: tasan 5 s avaushetken jälkeen hyväksytään (enintään kulunut aika), millisekunti yli hylätään.
        assertEquals(
            3_600 * sec,
            TripCalculator.runningCreditMs(
                stamp, stamp.wallMs + 3_601 * sec, stamp.wallMs + 3_596 * sec, stamp.elapsedMs + 3_600 * sec,
            ),
        )
        assertEquals(
            0L,
            TripCalculator.runningCreditMs(
                stamp, stamp.wallMs + 3_601 * sec + 1, stamp.wallMs + 3_596 * sec, stamp.elapsedMs + 3_600 * sec,
            ),
        )
    }

    // ---- nopeuskäyrän integrointi ja liikkeen vahvistus ----

    private fun mps(tSec: Double, v: Double) = fix(tSec, kmh = v * 3.6)

    /** Syöttää mittaukset (aika s, nopeus m/s) ja palauttaa nopeuden kuvaajan pinta-alan puolisuunnikassäännöllä. */
    private fun TripCalculator.feed(samples: List<Pair<Double, Double>>): Double {
        var area = 0.0
        for (i in samples.indices) {
            onFix(mps(samples[i].first, samples[i].second))
            if (i > 0) {
                area += (samples[i - 1].second + samples[i].second) / 2 * (samples[i].first - samples[i - 1].first)
            }
        }
        return area
    }

    /**
     * Virheetön profiili eripituisin mittausvälein hetkestä [t0] alkaen: 0 → 18 m/s yhdeksässä sekunnissa (mittaus
     * 3 s välein), 3 s vakiona ja 18 → 0 m/s yhdeksässä sekunnissa (mittaus 1 s välein). Pinta-ala 81 + 54 + 81 m.
     */
    private fun rampProfile(t0: Double): List<Pair<Double, Double>> {
        val rise = listOf(0.0 to 0.0, 3.0 to 6.0, 6.0 to 12.0, 9.0 to 18.0)
        val level = listOf(10.0 to 18.0, 11.0 to 18.0, 12.0 to 18.0)
        val fall = (13..21).map { it.toDouble() to 18.0 - 2.0 * (it - 12) }
        return (rise + level + fall).map { (t, v) -> t0 + t to v }
    }

    @Test fun eripituisetMittausvalitEivatVinoutaMatkaa() {
        val once = started()
        once.feed(rampProfile(0.0))
        val s = once.snapshot(21_000)
        assertEquals(216.0, s.distanceM, 0.01)
        assertEquals(21_000L, s.movingMs)
        assertEquals(18f, s.maxSpeedMps, 0.01f)

        val tenTimes = started()
        for (k in 0 until 10) tenTimes.feed(rampProfile(k * 22.0))
        assertEquals(2160.0, tenTimes.snapshot(220_000).distanceM, 0.1)
    }

    /** Terävä nopeushuippu (yksi mittaus naapureitaan suurempi) on uskottava muutos: sitä ei saa leikata pois. */
    @Test fun teravaUskottavaNopeushuippuEiLeikkaudu() {
        val c = started()
        val area = c.feed(
            listOf(0.0 to 0.0, 3.0 to 6.0, 6.0 to 12.0, 9.0 to 18.0, 12.0 to 12.0, 15.0 to 6.0, 18.0 to 0.0, 19.0 to 0.0),
        )
        assertEquals(162.0, area, 0.0)
        assertEquals(162.0, c.snapshot(19_000).distanceM, 0.01)
    }

    /** Paikallaan kaksi erillistä nopeuspiikkiä, joiden välissä on nollamittaus: ne eivät vahvista toisiaan. */
    @Test fun erillisetNopeuspiikitPaikallaanEivatVahvistaToisiaan() {
        val c = started()
        listOf(0.0, 0.0, 10.0, 0.0, 10.0, 0.0, 0.0).forEachIndexed { t, v -> c.onFix(mps(t.toDouble(), v)) }
        val s = c.snapshot(6_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
        assertEquals(0f, s.maxSpeedMps, 0f)
        assertEquals(6_000L, s.idleMs)
    }

    /** Toistuva häiriö (piikki joka toisessa mittauksessa) ei saa estää muistutusta paikallaanolosta. */
    @Test fun toistuvatErillisetPiikitEivatNollaaPaikallaanoloaikaa() {
        val c = started()
        for (t in 0..130) c.onFix(mps(t.toDouble(), if (t % 2 == 1) 10.0 else 0.0))
        val s = c.snapshot(130_000)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingMs)
        assertEquals(130_000L, s.idleMs)
        assertTrue(s.reminderDue(120_000))
    }

    /** Liike alkaa vasta kahdesta peräkkäisestä liikenopeudesta, ja silloin koko kuvaaja lasketaan mukaan. */
    @Test fun liikeAlkaaKahdestaPerakkaisestaLiikenopeudesta() {
        val c = started()
        listOf(0.0, 0.0, 3.0, 0.0, 0.0, 3.0, 3.0, 0.0, 0.0).forEachIndexed { t, v -> c.onFix(mps(t.toDouble(), v)) }
        val s = c.snapshot(8_000)
        // Yksittäinen 3 m/s hetkellä 2 ei kerry. Jakso 3, 3 hetkillä 5 ja 6: 1,5 + 3 + 1,5 m, liikettä väleillä 4–7.
        assertEquals(6.0, s.distanceM, 0.001)
        assertEquals(3_000L, s.movingMs)
        assertEquals(1_000L, s.idleMs)
    }

    /**
     * Yksittäinen nolla liikkeen keskellä on nopeassa ajossa mahdoton (varma virhe) ja korjataan. Hitaassa ajossa
     * (tässä 5 m/s) se on mahdollinen pysähdys ja kirjautuu sellaisenaan (päätös 14): 25 + 2,5 + 2,5 + 20 m.
     * Kaksi peräkkäistä nollaa on aina pysähdys.
     */
    @Test fun yksittainenNollaKorjataanNopeassaAjossaJaKirjautuuHitaassa() {
        val fast = started()
        for (t in 0..10) fast.onFix(mps(t.toDouble(), if (t == 5) 0.0 else 20.0))
        assertEquals(200.0, fast.snapshot(10_000).distanceM, 0.001)
        assertEquals(10_000L, fast.snapshot(10_000).movingMs)

        val slow = started()
        for (t in 0..10) slow.onFix(mps(t.toDouble(), if (t == 5) 0.0 else 5.0))
        assertEquals(45.0, slow.snapshot(10_000).distanceM, 0.001)
        assertEquals(10_000L, slow.snapshot(10_000).movingMs)

        val stop = started()
        val area = stop.feed(listOf(0.0 to 5.0, 1.0 to 5.0, 2.0 to 0.0, 3.0 to 0.0, 4.0 to 5.0, 5.0 to 5.0))
        assertEquals(15.0, area, 0.0)
        assertEquals(15.0, stop.snapshot(5_000).distanceM, 0.001)
        assertEquals(4_000L, stop.snapshot(5_000).movingMs)
    }

    /**
     * Mahdottoman suuri nopeus heti liikkeellelähdössä korvautuu naapuriensa (0 ja 20 m/s) väliin arvolla
     * 10 m/s: 5 + 15 + 20 + 10 m.
     */
    @Test fun mahdotonNopeusLiikkeellelahdossaKorvautuu() {
        val c = started()
        listOf(0.0, 0.0, 80.0, 20.0, 20.0, 0.0, 0.0).forEachIndexed { t, v -> c.onFix(mps(t.toDouble(), v)) }
        assertEquals(50.0, c.snapshot(6_000).distanceM, 0.001)
    }

    private val denseSteps = doubleArrayOf(0.5, 1.0, 2.0, 3.0)

    /**
     * Satunnaisia virheettömiä ajoja: mittausväli 0,5–3 s, liikkeellelähtö enintään 3 m/s², nopeuden muutos ajossa
     * enintään 4 m/s², jarrutus pysähdyksiin 2–6 m/s², pysähdykset vähintään kolmen ja liikejaksot vähintään kahden
     * mittauksen mittaisia. Matka on aina nopeuden kuvaajan pinta-ala.
     */
    @Test fun virheetonAjoKertyyAinaKuvaajanPintaAlana() {
        val rnd = java.util.Random(20261122L)
        repeat(300) { round ->
            val samples = ArrayList<Pair<Double, Double>>()
            var t = 0.0
            var v = 0.0
            samples += t to v
            while (samples.size < 60) {
                repeat(2 + rnd.nextInt(4)) {
                    t += denseSteps[rnd.nextInt(denseSteps.size)]
                    samples += t to 0.0
                }
                repeat(2 + rnd.nextInt(8)) {
                    val dt = denseSteps[rnd.nextInt(denseSteps.size)]
                    t += dt
                    v = if (v == 0.0) {
                        1.0 + rnd.nextDouble() * 3.0 * dt
                    } else {
                        (v + (rnd.nextDouble() * 8.0 - 4.0) * dt).coerceIn(1.0, 45.0)
                    }
                    samples += t to v
                }
                while (v > 0.0) {
                    val dt = denseSteps[rnd.nextInt(denseSteps.size)]
                    t += dt
                    v -= (2.0 + rnd.nextDouble() * 4.0) * dt
                    if (v < 1.0) v = 0.0
                    samples += t to v
                }
            }
            repeat(3) {
                t += 1.0
                samples += t to 0.0
            }
            val c = started()
            val area = c.feed(samples)
            assertEquals("kierros $round", area, c.snapshot((t * 1000).toLong()).distanceM, 0.05)
        }
    }

    /** Paikallaan yksittäiset piikit (ei kahta peräkkäin), koosta ja mittausvälistä riippumatta, eivät kerry. */
    @Test fun yksittaisetPiikitPaikallaanEivatKoskaanKerry() {
        val rnd = java.util.Random(7L)
        val steps = doubleArrayOf(0.5, 1.0, 1.0, 2.0, 3.0, 5.0, 9.0, 20.0)
        repeat(300) { round ->
            val c = started()
            var t = 0.0
            var lastWasSpike = true
            repeat(80) {
                val spike = !lastWasSpike && rnd.nextInt(3) == 0
                c.onFix(mps(t, if (spike) 0.6 + rnd.nextDouble() * 82.0 else 0.0))
                lastWasSpike = spike
                t += steps[rnd.nextInt(steps.size)]
            }
            val s = c.snapshot((t * 1000).toLong())
            assertEquals("kierros $round", 0.0, s.distanceM, 0.0)
            assertEquals("kierros $round", 0L, s.movingMs)
        }
    }

    /**
     * Tasainen ajo 20 m/s, jossa on yksittäisiä virheitä (nolla tai mahdottoman suuri nopeus) ja virheiden välissä
     * vähintään kaksi ehjää mittausta: matka ja liikeaika ovat samat kuin ilman virheitä.
     */
    @Test fun yksittaisetVirheetTasaisessaAjossaEivatMuutaMatkaa() {
        val rnd = java.util.Random(11L)
        repeat(200) { round ->
            val c = started()
            var sinceError = 0
            for (t in 0..200) {
                val error = t in 2..198 && sinceError >= 2 && rnd.nextInt(4) == 0
                val v = if (!error) 20.0 else if (rnd.nextBoolean()) 0.0 else 31.0 + rnd.nextDouble() * 52.0
                c.onFix(mps(t.toDouble(), v))
                sinceError = if (error) 0 else sinceError + 1
            }
            val s = c.snapshot(200_000)
            assertEquals("kierros $round", 4000.0, s.distanceM, 0.01)
            assertEquals("kierros $round", 200_000L, s.movingMs)
        }
    }

    /** Näytetty matka ja liikeaika eivät koskaan pienene, vaikka mittaukset olisivat mitä tahansa. */
    @Test fun matkaJaLiikeaikaEivatKoskaanPienene() {
        val rnd = java.util.Random(3L)
        val speeds = doubleArrayOf(0.0, 0.0, 0.3, 1.0, 5.0, 20.0, 21.0, 40.0, 80.0, 400.0)
        val steps = doubleArrayOf(0.5, 1.0, 1.0, 1.0, 2.0, 3.0, 5.0, 9.0, 20.0)
        repeat(300) { round ->
            val c = started()
            var t = 0.0
            var lastDistance = 0.0
            var lastMoving = 0L
            repeat(120) {
                c.onFix(mps(t, speeds[rnd.nextInt(speeds.size)]))
                val s = c.snapshot((t * 1000).toLong())
                assertTrue("kierros $round: matka pieneni", s.distanceM >= lastDistance)
                assertTrue("kierros $round: liikeaika pieneni", s.movingMs >= lastMoving)
                assertTrue("kierros $round: liikeaika yli kokonaisajan", s.movingMs <= s.totalMs)
                lastDistance = s.distanceM
                lastMoving = s.movingMs
                t += steps[rnd.nextInt(steps.size)]
            }
        }
    }

    // ---- yksittäinen virhemittaus missä tahansa kohdassa ----

    /** Tasainen 30 m/s, jonon toinen mittaus virheellinen: oikea matka on 4 × 30 m. Sama pitkän välin jälkeen. */
    @Test fun jononToinenMittausVirheellisenaEiLyhennaMatkaa() {
        val c = started()
        listOf(30.0, 1.0, 30.0, 30.0, 30.0).forEachIndexed { t, v -> c.onFix(mps(t.toDouble(), v)) }
        assertEquals(120.0, c.snapshot(4_000).distanceM, 0.001)
        assertEquals(4_000L, c.snapshot(4_000).movingMs)

        // Viiden sekunnin mittausväli kesken ajon ja virhe heti sen jälkeisenä toisena mittauksena.
        val afterGap = started()
        listOf(0.0 to 30.0, 1.0 to 30.0, 2.0 to 30.0, 7.0 to 30.0, 8.0 to 1.0, 9.0 to 30.0, 10.0 to 30.0)
            .forEach { (t, v) -> afterGap.onFix(mps(t, v)) }
        assertEquals(300.0, afterGap.snapshot(10_000).distanceM, 0.001)
        assertEquals(10_000L, afterGap.snapshot(10_000).movingMs)
    }

    /**
     * Yksi virheellinen mittaus missä tahansa kohdassa (päätös 14). Tasainen ajo 30 m/s, yhdeksän mittausta
     * sekunnin välein; yksi väleistä voi olla viiden sekunnin mittainen missä tahansa kohdassa. Yksi mittauksista,
     * myös ensimmäinen tai viimeinen, korvataan virheellä: nolla, 1, 45 tai 80 m/s. Jakso päätetään lopuksi.
     *
     * Varma virhe (muutos on mahdoton, yli 10 m/s² × väli, sekä edeltäjästä että seuraajaan) korjautuu, ja matka
     * ja liikeaika ovat samat kuin ilman virhettä. Muu virhe (jonon ensimmäinen tai viimeinen mittaus, tai
     * pitkän välin vieressä oleva mittaus, joka sopii välin takaiseen naapuriinsa) kirjautuu sellaisenaan: matka
     * on mittausten kuvaajan pinta-ala virheineen. Odotusarvot lasketaan tässä suoraan näistä kahdesta säännöstä.
     * Sivuun on jätetty kaksi yhdistelmää, joissa jonon reunimmaisen mittauksen ainoa naapuri on kirjautuva nolla
     * (ks. [jononReunanLiikenopeusNollanVieressaEiOleLiiketta]).
     */
    @Test fun yksittainenVirhemittausKorjautuuTaiKirjautuuSellaisenaan() {
        val n = 9
        val v = 30.0
        var certainCases = 0
        var asIsCases = 0
        for (gapAfter in -1..n - 2) for (errorAt in 0 until n) for (wrong in listOf(0.0, 1.0, 45.0, 80.0)) {
            if (wrong == 0.0 && errorAt == 1 && gapAfter == 1) continue
            if (wrong == 0.0 && errorAt == n - 2 && gapAfter == n - 3) continue
            fun step(i: Int) = if (i == gapAfter) 5.0 else 1.0      // väli mittauksesta i seuraavaan
            fun impossible(dt: Double) = kotlin.math.abs(wrong - v) > 10.0 * dt
            val certain = errorAt in 1..n - 2 && impossible(step(errorAt - 1)) && impossible(step(errorAt))
            // Odotusarvo: tiheä väli päiden keskiarvo × aika, pitkä väli pienempi × aika.
            var expected = 0.0
            var expectedMoving = 0L
            for (i in 0 until n - 1) {
                val a = if (i == errorAt && !certain) wrong else v
                val b = if (i + 1 == errorAt && !certain) wrong else v
                val meters = if (step(i) <= 3.0) (a + b) / 2 * step(i) else minOf(a, b) * step(i)
                expected += meters
                if (meters > 0.0) expectedMoving += (step(i) * 1000).toLong()
            }
            val c = started()
            var t = 0.0
            for (i in 0 until n) {
                c.onFix(mps(t, if (i == errorAt) wrong else v))
                if (i < n - 1) t += step(i)
            }
            c.pause((t * 1000).toLong())
            val label = "virhe $wrong m/s kohdassa $errorAt, pitkä väli kohdan $gapAfter jälkeen, varma virhe: $certain"
            val s = c.snapshot((t * 1000).toLong())
            assertEquals(label, expected, s.distanceM, 0.001)
            assertEquals(label, expectedMoving, s.movingMs)
            if (certain) certainCases++ else asIsCases++
        }
        assertEquals(196, certainCases)
        assertEquals(126, asIsCases)
    }

    /**
     * Liike vaatii kaksi peräkkäistä liikenopeutta myös jonon reunassa: jos jonon ensimmäisen tai viimeisen
     * mittauksen ainoa naapuri on nolla, joka ei ole varma virhe (tässä se sopii pitkän välin takaiseen
     * naapuriinsa), reunimmainen mittaus on yksittäinen liikenopeus eikä kerry. Sama jono syntyy, kun matka alkaa
     * yksittäisellä piikillä ennen pysähdystä.
     */
    @Test fun jononReunanLiikenopeusNollanVieressaEiOleLiiketta() {
        val start = started()
        listOf(0.0 to 30.0, 1.0 to 0.0, 6.0 to 30.0, 7.0 to 30.0, 8.0 to 30.0).forEach { (t, v) -> start.onFix(mps(t, v)) }
        assertEquals(60.0, start.snapshot(8_000).distanceM, 0.001)
        assertEquals(2_000L, start.snapshot(8_000).movingMs)

        val end = started()
        listOf(0.0 to 30.0, 1.0 to 30.0, 2.0 to 30.0, 7.0 to 0.0, 8.0 to 30.0).forEach { (t, v) -> end.onFix(mps(t, v)) }
        end.pause(8_000)
        assertEquals(60.0, end.snapshot(8_000).distanceM, 0.001)
        assertEquals(2_000L, end.snapshot(8_000).movingMs)
    }

    /** Paikallaan yksi liikenopeus missä tahansa kohdassa (myös ensimmäisenä tai viimeisenä) ei kerry. */
    @Test fun yksittainenPiikkiPaikallaanMissaTahansaKohdassaEiKerry() {
        val n = 6
        for (spikeAt in 0 until n) for (gapAfter in -1..n - 2) for (spike in listOf(0.6, 5.0, 30.0, 83.0)) {
            val c = started()
            var t = 0.0
            for (i in 0 until n) {
                c.onFix(mps(t, if (i == spikeAt) spike else 0.0))
                if (i < n - 1) t += if (i == gapAfter) 5.0 else 1.0
            }
            val label = "piikki $spike m/s kohdassa $spikeAt, pitkä väli kohdan $gapAfter jälkeen"
            val s = c.snapshot((t * 1000).toLong())
            assertEquals(label, 0.0, s.distanceM, 0.0)
            assertEquals(label, 0L, s.movingMs)
        }
    }

    /** Jonon alussa kaksi erillistä piikkiä nollan ympärillä ei kerry, ellei liike jatku heti niiden jälkeen. */
    @Test fun jononAlunErillisetPiikitEivatVahvistaToisiaan() {
        for (spike in listOf(5.0, 20.0, 80.0)) {
            val c = started()
            listOf(spike, 0.0, spike, 0.0, 0.0, 0.0).forEachIndexed { t, v -> c.onFix(mps(t.toDouble(), v)) }
            assertEquals("piikki $spike", 0.0, c.snapshot(5_000).distanceM, 0.0)
        }
    }

    /** Liikkeellelähtö ja ajon aloitus vauhdissa kertyvät sellaisinaan: jonon alun vahvistus ei muuta virheetöntä ajoa. */
    @Test fun virheetonJononAlkuKertyySellaisenaan() {
        val fromRest = started()
        assertEquals(15.0, fromRest.feed(listOf(0.0 to 0.0, 1.0 to 2.0, 2.0 to 4.0, 3.0 to 6.0, 4.0 to 6.0)), 0.0)
        assertEquals(15.0, fromRest.snapshot(4_000).distanceM, 0.001)

        val braking = started()
        assertEquals(82.0, braking.feed(listOf(0.0 to 30.0, 1.0 to 28.0, 2.0 to 27.0, 3.0 to 24.0)), 0.0)
        assertEquals(82.0, braking.snapshot(3_000).distanceM, 0.001)
    }

    /**
     * Yli 300 km/h:n nopeus ei ole uskottava: mittaus ohitetaan kuten nopeudeton, eikä äärellinen mutta valtava
     * arvo voi ylivuotaa matkaa äärettömäksi (tallenne hylättäisiin seuraavalla avauksella kokonaan).
     */
    @Test fun mahdotonNopeusOhitetaanEikaTuhoaMatkaa() {
        val c = started()
        listOf(10f, 10f, Float.MAX_VALUE, Float.MAX_VALUE, 10f, 10f).forEachIndexed { t, v ->
            c.onFix(GpsFix(t * 1000L, 60.0, 25.0, v, 5f, null))
        }
        c.finish(5_000)
        val s = c.snapshot(5_000)
        assertTrue(s.distanceM.isFinite())
        // Kelvottomat ohitetaan, joten välin 1 s → 4 s kattavat sen päiden nopeudet: 10 m/s × 5 s.
        assertEquals(50.0, s.distanceM, 0.001)
        assertEquals(10f, s.maxSpeedMps, 0.001f)
        assertTrue(s.maxSpeedMps.isFinite())

        val fast = started()
        for (t in 0..10) fast.onFix(fix(t.toDouble(), kmh = if (t in 4..6) 310.0 else 290.0))
        // 290 km/h kelpaa, 310 km/h ohitetaan: matka on 290 km/h × 10 s.
        assertEquals(290.0 / 3.6 * 10, fast.snapshot(10_000).distanceM, 0.01)
        assertEquals(290.0 / 3.6, fast.snapshot(10_000).maxSpeedMps.toDouble(), 0.01)
    }

    // ---- ristiriidan ratkaisu: kumpi kahdesta peräkkäisestä mittauksesta on virheellinen ----

    /**
     * Nopeus nousee pitkän mittausvälin aikana 10 → 25 m/s, ja välin jälkeinen toinen mittaus on virheellinen.
     * Välin jälkeinen ensimmäinen, oikea mittaus ei saa leikkautua: 20 + 5 × 10 + 5 × 25 m.
     */
    @Test fun pitkanValinJalkeinenOikeaNopeusEiLeikkaudu() {
        val c = started()
        listOf(0.0 to 10.0, 1.0 to 10.0, 2.0 to 10.0, 7.0 to 25.0, 8.0 to 1.0, 9.0 to 25.0, 10.0 to 25.0,
            11.0 to 25.0, 12.0 to 25.0).forEach { (t, v) -> c.onFix(mps(t, v)) }
        assertEquals(195.0, c.snapshot(12_000).distanceM, 0.001)
        assertEquals(12_000L, c.snapshot(12_000).movingMs)
    }

    /**
     * Jonon ensimmäinen mittaus on virheellinen (40 m/s, oikea 20 m/s). Jonon ensimmäistä mittausta ei korjata,
     * koska sillä on vain yksi naapuri (päätös 14): se kirjautuu sellaisenaan, ja muut mittaukset säilyvät.
     * Matka on mittausten kuvaajan pinta-ala virheineen, 130,45 m (ilman virhettä 120,45 m).
     */
    @Test fun jononEnsimmainenVirheellinenMittausKirjautuuSellaisenaan() {
        val clean = started()
        val cleanArea = clean.feed(listOf(0.0 to 20.0, 1.0 to 20.0, 2.0 to 20.1, 3.0 to 20.1, 4.0 to 20.1, 5.0 to 20.1, 6.0 to 20.1))
        assertEquals(120.45, cleanArea, 1e-9)
        assertEquals(120.45, clean.snapshot(6_000).distanceM, 0.001)

        val c = started()
        val area = c.feed(listOf(0.0 to 40.0, 1.0 to 20.0, 2.0 to 20.1, 3.0 to 20.1, 4.0 to 20.1, 5.0 to 20.1, 6.0 to 20.1))
        assertEquals(130.45, area, 1e-9)
        assertEquals(130.45, c.snapshot(6_000).distanceM, 0.001)
    }

    /**
     * Kahdeksannen katselmointikierroksen neljä jonoa. Kahdessa virhe on varma (mahdoton kumpaankin naapuriinsa
     * tai yksittäinen liikenopeus ennen pysähdystä), ja matka on sama kuin ilman virhettä. Kahdessa virhe sopii
     * seuraajaansa, joten se kirjautuu sellaisenaan (päätös 14); yhdessäkään muu mittaus ei muutu.
     */
    @Test fun kahdeksannenKierroksenJonot() {
        // 1. Jarrutus pysähdyksiin 10, 5, 0, 0, 0, 0 ja piikki pysähdyksen jälkeen: 7,5 + 2,5 m.
        val afterStop = started()
        listOf(10.0, 5.0, 0.0, 40.0, 0.0, 0.0).forEachIndexed { t, v -> afterStop.onFix(mps(t.toDouble(), v)) }
        assertEquals(10.0, afterStop.snapshot(5_000).distanceM, 0.001)
        assertEquals(2_000L, afterStop.snapshot(5_000).movingMs)

        // 2. Liikkeellelähtö 0, 2, 4, 6, 8, 10 (25 m), toinen lukema 14: se sopii seuraajaansa (14 → 4 sekunnissa),
        //    joten se kirjautuu sellaisenaan, ja ensimmäinen nolla säilyy: 7 + 9 + 5 + 7 + 9 m.
        val setOff = started()
        assertEquals(37.0, setOff.feed(listOf(0.0 to 0.0, 1.0 to 14.0, 2.0 to 4.0, 3.0 to 6.0, 4.0 to 8.0, 5.0 to 10.0)), 0.0)
        assertEquals(37.0, setOff.snapshot(5_000).distanceM, 0.001)

        // 3. Ensimmäinen lukema 40 ja heti sen jälkeen nolla: yksittäinen liikenopeus ei ole liikettä, ja mitattu
        //    pysähdys säilyy: 1,5 + 4,5 + 7,5 m.
        val firstWrong = started()
        listOf(40.0, 0.0, 3.0, 6.0, 9.0).forEachIndexed { t, v -> firstWrong.onFix(mps(t.toDouble(), v)) }
        assertEquals(13.5, firstWrong.snapshot(4_000).distanceM, 0.001)
        assertEquals(3_000L, firstWrong.snapshot(4_000).movingMs)

        // 4. Kiihdytys 10, 13, 22, 25, 28, 31 hetkillä 0, 1, 4, 5, 6, 7 s (143,5 m), toinen lukema 22: se sopii
        //    seuraajaansa, joten se kirjautuu sellaisenaan, ja ensimmäinen lukema 10 säilyy: 16 + 66 + 79,5 m.
        val second = started()
        val area = second.feed(listOf(0.0 to 10.0, 1.0 to 22.0, 4.0 to 22.0, 5.0 to 25.0, 6.0 to 28.0, 7.0 to 31.0))
        assertEquals(161.5, area, 1e-9)
        assertEquals(161.5, second.snapshot(7_000).distanceM, 0.001)
    }

    /**
     * Varma virhe tasaisessa kiihdytyksessä (10 → 30 m/s, 5 m/s²): korvaava arvo lasketaan naapurien väliltä,
     * joten matka on sama kuin ilman virhettä (12,5 + 17,5 + 22,5 + 27,5 m), olipa virhe piikki tai nolla.
     */
    @Test fun virheKiihdytyksessaKorvautuuNaapurienValiin() {
        for (wrong in listOf(20.0, 80.0, 0.0)) {
            val c = started()
            listOf(10.0, 15.0, wrong, 25.0, 30.0).forEachIndexed { t, v -> c.onFix(mps(t.toDouble(), v)) }
            assertEquals("arvo $wrong", 80.0, c.snapshot(4_000).distanceM, 0.001)
            assertEquals("arvo $wrong", 4_000L, c.snapshot(4_000).movingMs)
        }
    }

    /**
     * Virhe, joka sopii edeltäjäänsä (20 → 29 m/s), ei ole varma virhe ja kirjautuu sellaisenaan. Se ei saa
     * muuttaa viereistä oikeaa mittausta, vaikka muutos siihen (29 → 18 m/s) on mahdoton: matka on mittausten
     * kuvaajan pinta-ala sellaisenaan. Sama, kun oikea kiihdytys on rajalla ja virhe hieman sen yli
     * (20, 20, 31, 40).
     */
    @Test fun edeltajaansaSopivaVirheEiMuutaViereisiaMittauksia() {
        val c = started()
        val area = c.feed(listOf(0.0 to 20.0, 1.0 to 20.0, 2.0 to 29.0, 3.0 to 18.0, 4.0 to 20.0, 5.0 to 20.0))
        assertEquals(107.0, area, 1e-9)
        assertEquals(107.0, c.snapshot(5_000).distanceM, 0.001)

        val atLimit = started()
        val limitArea = atLimit.feed(listOf(0.0 to 20.0, 1.0 to 20.0, 2.0 to 31.0, 3.0 to 40.0, 4.0 to 40.0))
        assertEquals(121.0, limitArea, 1e-9)
        assertEquals(121.0, atLimit.snapshot(4_000).distanceM, 0.001)
    }

    /**
     * Kaksi mittausta ennen pitkää väliä, joista toinen on virhe: jono 1, 25, (5 s), 12 syntyy sekä ajosta
     * 25, 25, (5 s), 12 (ensimmäinen mittaus virhe) että ajosta 1, 1, (5 s), 12 (toinen mittaus virhe). Kumpikaan
     * ei ole varma virhe, eikä siitä, kumpi on väärä, päätellä mitään: mittaukset kirjautuvat sellaisinaan,
     * 13 + 5 × 12 + 12 + 12 m.
     */
    @Test fun kaksiMittaustaEnnenPitkaaValiaKirjautuvatSellaisinaan() {
        val c = started()
        val samples = listOf(0.0 to 1.0, 1.0 to 25.0, 6.0 to 12.0, 7.0 to 12.0, 8.0 to 12.0)
        samples.forEach { (t, v) -> c.onFix(mps(t, v)) }
        assertEquals(97.0, c.snapshot(8_000).distanceM, 0.001)
    }

    /**
     * Jakson viimeinen mittaus on mahdoton hyppy (20 → 80 m/s). Kunnes seuraaja tunnetaan, mittaus odottaa nollana.
     * Jos seuraaja osoittaa sen varmaksi virheeksi, se korjautuu. Jos jakso päättyy ennen seuraajaa, viimeistä
     * mittausta ei korjata (sillä on vain yksi naapuri, päätös 14), ja se kirjautuu sellaisenaan.
     */
    @Test fun jaksonViimeinenMittausOdottaaSeuraajaaJaKirjautuuJaksonPaattyessa() {
        val c = started()
        listOf(20.0, 20.0, 20.0, 80.0).forEachIndexed { t, v -> c.onFix(mps(t.toDouble(), v)) }
        assertEquals(50.0, c.snapshot(3_000).distanceM, 0.001)       // 20 + 20 + (20 + 0) / 2
        c.onFix(mps(4.0, 20.0))
        c.onFix(mps(5.0, 20.0))
        assertEquals(100.0, c.snapshot(5_000).distanceM, 0.001)      // piikki korjautui

        val paused = started()
        listOf(20.0, 20.0, 20.0, 80.0).forEachIndexed { t, v -> paused.onFix(mps(t.toDouble(), v)) }
        paused.pause(3_000)
        assertEquals(90.0, paused.snapshot(3_000).distanceM, 0.001)  // 20 + 20 + (20 + 80) / 2
    }

    /**
     * Muutoksen mahdollisuus arvioidaan vastaanottimen alkuperäisistä nopeuksista, ei liikerajalla nollatuista.
     * Virheetön jono 10,2, 10,2, 0,4, 10,2, 10,2 m/s: suurin muutos on 9,8 m/s² eli mahdollinen, joten mihinkään ei
     * kosketa. Keskimmäinen lukema on alle liikerajan (2 km/h) eikä ole liikettä: 10,2 + 5,1 + 5,1 + 10,2 m.
     * Sama, kun keskimmäinen lukema ei ole liikettä siksi, että se on pienempi kuin oma nopeustarkkuutensa.
     * Lukema 0,1 m/s (muutos 10,1 m/s²) on sen sijaan varma virhe ja korjautuu.
     */
    @Test fun muutoksenMahdollisuusArvioidaanAlkuperaisistaNopeuksista() {
        val belowLimit = started()
        listOf(10.2, 10.2, 0.4, 10.2, 10.2).forEachIndexed { t, v -> belowLimit.onFix(mps(t.toDouble(), v)) }
        assertEquals(30.6, belowLimit.snapshot(4_000).distanceM, 0.001)
        assertEquals(4_000L, belowLimit.snapshot(4_000).movingMs)

        val inaccurate = started()
        for (t in 0..4) inaccurate.onFix(fix(t.toDouble(), kmh = 10.2 * 3.6, speedAcc = if (t == 2) 11f else 0.5f))
        assertEquals(30.6, inaccurate.snapshot(4_000).distanceM, 0.001)

        val certain = started()
        listOf(10.2, 10.2, 0.1, 10.2, 10.2).forEachIndexed { t, v -> certain.onFix(mps(t.toDouble(), v)) }
        assertEquals(40.8, certain.snapshot(4_000).distanceM, 0.001)
    }

    /**
     * Muutos täsmälleen kiihtyvyysrajalla (10 m/s²) on mahdollinen, eikä mittaustarkkuus saa tehdä siitä virhettä.
     * Aikaleimat katkaistaan millisekunteihin, joten todellinen mittausväli voi olla lähes millisekunnin mitattua
     * pidempi, ja nopeudet ovat liukulukuja, joiden erotus voi pyöristyä rajan yli.
     */
    @Test fun muutosKiihtyvyysrajallaEiOleVirheMittaustarkkuudenVuoksi() {
        // 7,2 → 17,2 → 7,2 m/s tasan sekunnin välein: liukulukujen erotus on hieman yli 10, todellinen muutos 10.
        val rounded = started()
        listOf(7.2, 17.2, 7.2).forEachIndexed { t, v -> rounded.onFix(mps(t.toDouble(), v)) }
        assertEquals(24.4, rounded.snapshot(2_000).distanceM, 0.001)

        // 10 → 20,00390625 → 10 m/s todellisin välein 1 000,45 ms (9,9994 m/s²): aikaleimat ovat katkaistuina 0, 1 000
        // ja 2 000 ms. Matka mitatuilla väleillä: (10 + 20,0039) / 2 × 1 s × 2.
        val truncated = started()
        listOf(0L to 10f, 1_000L to 20.00390625f, 2_000L to 10f)
            .forEach { (t, v) -> truncated.onFix(GpsFix(t, 60.0, 25.0, v, 5f, null)) }
        assertEquals(30.0039, truncated.snapshot(2_000).distanceM, 0.001)

        // Selvästi rajan yli (10,02 m/s sekunnissa kumpaankin suuntaan) on yhä varma virhe.
        val over = started()
        listOf(10.0, 20.02, 10.0, 10.0).forEachIndexed { t, v -> over.onFix(mps(t.toDouble(), v)) }
        assertEquals(30.0, over.snapshot(3_000).distanceM, 0.001)
    }

    /** Tauot ja jatkot kesken minkä tahansa mittausjonon: matka ja liikeaika eivät pienene, liikeaika ei ylitä kokonaisaikaa. */
    @Test fun taukoEiPienennaMatkaaEikaLiikeaikaa() {
        val rnd = java.util.Random(9L)
        val speeds = doubleArrayOf(0.0, 0.3, 1.0, 5.0, 20.0, 21.0, 40.0, 80.0)
        val steps = doubleArrayOf(0.5, 1.0, 1.0, 2.0, 3.0, 5.0, 9.0)
        repeat(300) { round ->
            val c = started()
            var t = 0.0
            var lastDistance = 0.0
            var lastMoving = 0L
            repeat(120) {
                val now = (t * 1000).toLong()
                if (rnd.nextInt(8) == 0) {
                    c.pause(now)
                    c.resume(now)
                }
                c.onFix(mps(t, speeds[rnd.nextInt(speeds.size)]))
                val s = c.snapshot(now)
                assertTrue("kierros $round: matka pieneni", s.distanceM >= lastDistance)
                assertTrue("kierros $round: liikeaika pieneni", s.movingMs >= lastMoving)
                assertTrue("kierros $round: liikeaika yli kokonaisajan", s.movingMs <= s.totalMs)
                lastDistance = s.distanceM
                lastMoving = s.movingMs
                t += steps[rnd.nextInt(steps.size)]
            }
        }
    }
}
