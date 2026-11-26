package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPS-lukeman tulkinta näytölle: nopeus, laatu ja signaalin tila. 17,5 m/s = 63 km/h. */
class GpsReadoutTest {

    private fun fix(rt: Long, mps: Float? = 17.5f, acc: Float? = 5f) = GpsFix(rt, 60.0, 25.0, mps, acc, null)

    @Test fun nopeusNaytto() {
        assertEquals(63, GpsReadout.displaySpeedKmh(fix(0), 1_000))
        // Alle 2 km/h näytetään nollana: paikallaan seisova puhelin ei saa näyttää ryömintää.
        assertEquals(0, GpsReadout.displaySpeedKmh(fix(0, 1.99f / 3.6f), 1_000))
        assertEquals(2, GpsReadout.displaySpeedKmh(fix(0, 2.01f / 3.6f), 1_000))
        // Tuoreusraja 8 s: sen jälkeen vanhaa nopeutta ei näytetä.
        assertEquals(63, GpsReadout.displaySpeedKmh(fix(0), 8_000))
        assertNull(GpsReadout.displaySpeedKmh(fix(0), 8_001))
        // Mittaus ilman nopeutta (esim. emulaattorin yksittäinen sijainti) ja puuttuva mittaus.
        assertNull(GpsReadout.displaySpeedKmh(fix(0, mps = null), 1_000))
        assertNull(GpsReadout.displaySpeedKmh(fix(0, mps = Float.NaN), 1_000))
        assertNull(GpsReadout.displaySpeedKmh(null, 1_000))
    }

    @Test fun tuoreus() {
        assertTrue(GpsReadout.isFresh(fix(5_000), 13_000))
        assertFalse(GpsReadout.isFresh(fix(5_000), 13_001))
        assertFalse(GpsReadout.isFresh(null, 0))
        // Mittauksen aika hieman nykyhetken edellä (eri säikeet) on yhä tuore.
        assertTrue(GpsReadout.isFresh(fix(5_000), 4_990))
    }

    @Test fun laatu() {
        assertEquals(GpsQuality.GOOD, GpsReadout.quality(fix(0, acc = 10f), 0))
        assertEquals(GpsQuality.FAIR, GpsReadout.quality(fix(0, acc = 10.1f), 0))
        assertEquals(GpsQuality.FAIR, GpsReadout.quality(fix(0, acc = 25f), 0))
        assertEquals(GpsQuality.WEAK, GpsReadout.quality(fix(0, acc = 25.1f), 0))
        assertEquals(GpsQuality.WEAK, GpsReadout.quality(fix(0, acc = null), 0))
        assertEquals(GpsQuality.NONE, GpsReadout.quality(fix(0), 8_001))
        assertEquals(GpsQuality.NONE, GpsReadout.quality(null, 0))
    }

    @Test fun tila() {
        assertEquals(GpsStatus.OFF, GpsReadout.status(GpsReading(fix(0), providerEnabled = false), 0))
        assertEquals(GpsStatus.WAITING, GpsReadout.status(GpsReading(), 0))
        assertEquals(GpsStatus.LOST, GpsReadout.status(GpsReading(fix(0)), 8_001))
        assertEquals(GpsStatus.OK, GpsReadout.status(GpsReading(fix(0)), 1_000))
    }

    @Test fun aaretonNopeusEiNay() {
        val f = GpsFix(1_000, 60.0, 25.0, Float.POSITIVE_INFINITY, 5f, null)
        assertNull(GpsReadout.displaySpeedKmh(f, 1_000))
        assertNull(GpsReadout.displaySpeedKmh(f.copy(speedMps = -3f), 1_000))
    }

    @Test fun kelvottomatMittauksenArvotSiivotaanPuuttuviksi() {
        val raw = GpsFix(1_000, 60.0, 25.0, Float.POSITIVE_INFINITY, Float.NaN, -1f)
        assertEquals(GpsFix(1_000, 60.0, 25.0, null, null, null), raw.clean())
        val ok = GpsFix(1_000, 60.0, 25.0, 12f, 5f, 0.5f)
        assertEquals(ok, ok.clean())
        assertEquals(ok.copy(speedMps = 0f), ok.copy(speedMps = 0f).clean())
    }

    @Test fun sijainninPoiskytkentaMitatoiLukeman() {
        val fix = GpsFix(1_000, 60.0, 25.0, 20f, 5f, 0.5f)
        val on = GpsReading(fix, satUsed = 9, satVisible = 14, providerEnabled = true)
        val off = on.withProvider(false)
        assertEquals(GpsReading(providerEnabled = false), off)
        assertNull(GpsReadout.displaySpeedKmh(off.fix, 1_000))
        assertEquals(GpsQuality.NONE, GpsReadout.quality(off.fix, 1_000))
        assertEquals(GpsStatus.OFF, GpsReadout.status(off, 1_000))
        // Pois kytkennän jälkeen myöhässä tullut mittaus tai satelliittitieto ei palauta vanhaa näyttöä.
        assertEquals(off, off.withFix(fix))
        assertEquals(off, off.withSatellites(9, 14))
        // Päälle kytkettäessä odotetaan uutta mittausta.
        val back = off.withProvider(true)
        assertEquals(GpsReading(providerEnabled = true), back)
        assertEquals(GpsStatus.WAITING, GpsReadout.status(back, 1_000))
        assertEquals(back.copy(fix = fix), back.withFix(fix))
        assertEquals(back.copy(satUsed = 3, satVisible = 8), back.withSatellites(3, 8))
        // Jo päällä: ilmoitus päällä olosta ei hävitä mittausta.
        assertEquals(on, on.withProvider(true))
    }

    /** Päätös 11: jos vastaanotin ei anna nopeutta, tila kertoo sen (matkaa ei silloin voi mitata). */
    @Test fun tilaKertooPuuttuvastaNopeudesta() {
        val withSpeed = GpsFix(1_000, 60.0, 25.0, 0f, 5f, 0.5f)
        val noSpeed = withSpeed.copy(speedMps = null)
        assertEquals(GpsStatus.OK, GpsReadout.status(GpsReading(withSpeed), 1_000))
        assertEquals(GpsStatus.NO_SPEED, GpsReadout.status(GpsReading(noSpeed), 1_000))
        // Kelvoton nopeus siivotaan puuttuvaksi ennen näyttöä.
        assertEquals(
            GpsStatus.NO_SPEED,
            GpsReadout.status(GpsReading(withSpeed.copy(speedMps = Float.NaN).clean()), 1_000),
        )
        // Vanhentunut mittaus on yhä "signaali katkesi", ja pois päältä oleva paikannus "pois".
        assertEquals(GpsStatus.LOST, GpsReadout.status(GpsReading(noSpeed), 60_000))
        assertEquals(GpsStatus.OFF, GpsReadout.status(GpsReading(noSpeed, providerEnabled = false), 1_000))
    }

    /** Laitteessa ei ole GPS-paikanninta: odottaminen ei auta, joten tila ei saa olla "odotetaan". */
    @Test fun puuttuvaPaikanninEiOleOdotustila() {
        val missing = GpsReading(providerPresent = false)
        assertEquals(GpsStatus.UNAVAILABLE, GpsReadout.status(missing, 0))
        assertEquals(GpsStatus.UNAVAILABLE, GpsReadout.status(missing, 3_600_000))
        assertFalse(GpsReadout.hasFreshFix(missing, 0))
        // Tavallinen tyhjä lukema on yhä odotus.
        assertEquals(GpsStatus.WAITING, GpsReadout.status(GpsReading(), 0))
    }

    /**
     * Sijaintitarkkuuden näkyvyys riippuu mittauksen tuoreudesta, ei nopeuden saatavuudesta: tuore mittaus
     * ilman nopeutta näyttää tarkkuutensa, vanhentunut tai paikannuksen ollessa pois päältä ei.
     */
    @Test fun tuoreenMittauksenTiedotNakyvatIlmanNopeuttakin() {
        val noSpeed = fix(0, mps = null)
        assertEquals(GpsStatus.NO_SPEED, GpsReadout.status(GpsReading(noSpeed), 1_000))
        assertTrue(GpsReadout.hasFreshFix(GpsReading(noSpeed), 1_000))
        assertTrue(GpsReadout.hasFreshFix(GpsReading(fix(0)), 1_000))
        assertFalse(GpsReadout.hasFreshFix(GpsReading(noSpeed), 8_001))
        assertFalse(GpsReadout.hasFreshFix(GpsReading(), 0))
        assertFalse(GpsReadout.hasFreshFix(GpsReading(noSpeed, providerEnabled = false), 1_000))
    }
}
