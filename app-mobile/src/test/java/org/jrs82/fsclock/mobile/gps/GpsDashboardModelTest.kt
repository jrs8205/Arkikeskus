package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mittaristonäkymän päätökset: painikkeet tilan mukaan, näkymävalinta, vaaka-asettelu ja alapalkki. */
class GpsDashboardModelTest {

    @Test fun painikkeetTilanMukaan() {
        assertEquals(listOf(TripAction.START), GpsDashboardModel.primaryActions(TripPhase.NONE))
        assertEquals(listOf(TripAction.PAUSE, TripAction.FINISH), GpsDashboardModel.primaryActions(TripPhase.RUNNING))
        assertEquals(listOf(TripAction.RESUME, TripAction.FINISH), GpsDashboardModel.primaryActions(TripPhase.PAUSED))
        assertEquals(listOf(TripAction.RESET, TripAction.NEW_TRIP), GpsDashboardModel.primaryActions(TripPhase.FINISHED))
    }

    @Test fun lopetettuaMatkaaVoiJatkaa() {
        assertEquals(TripAction.CONTINUE, GpsDashboardModel.secondaryAction(TripPhase.FINISHED))
        for (p in listOf(TripPhase.NONE, TripPhase.RUNNING, TripPhase.PAUSED)) {
            assertNull(p.name, GpsDashboardModel.secondaryAction(p))
        }
    }

    @Test fun matkanMerkkiNakyyVainMatkanAikana() {
        assertTrue(GpsDashboardModel.chipVisible(TripPhase.RUNNING))
        assertTrue(GpsDashboardModel.chipVisible(TripPhase.PAUSED))
        assertFalse(GpsDashboardModel.chipVisible(TripPhase.NONE))
        assertFalse(GpsDashboardModel.chipVisible(TripPhase.FINISHED))
    }

    @Test fun nakymavalinta() {
        assertEquals(DashboardView.TRIP, DashboardView.fromPref("TRIP"))
        assertEquals(DashboardView.FULL, DashboardView.fromPref("FULL"))
        // Puuttuva tai tuntematon arvo (esim. varmuuskopiosta): nykyistä vastaava Perus-näkymä.
        assertEquals(DashboardView.BASIC, DashboardView.fromPref(null))
        assertEquals(DashboardView.BASIC, DashboardView.fromPref("bogus"))
    }

    @Test fun vaakaAsettelu() {
        assertTrue(GpsDashboardModel.isLandscape(914, 411))
        assertFalse(GpsDashboardModel.isLandscape(411, 914))
        assertFalse(GpsDashboardModel.isLandscape(600, 600))
    }

    @Test fun alapalkkiPiiloonVainGpsNakymassaVaakanaPienellaNaytolla() {
        assertTrue(GpsDashboardModel.hideBottomBar(true, 914, 411, false, false))
        assertTrue(GpsDashboardModel.hideBottomBar(true, 914, 479, false, false))
        assertFalse("iso näyttö", GpsDashboardModel.hideBottomBar(true, 1280, 480, false, false))
        assertFalse("muu näkymä", GpsDashboardModel.hideBottomBar(false, 914, 411, false, false))
        assertFalse("pysty", GpsDashboardModel.hideBottomBar(true, 411, 914, false, false))
        assertFalse("valikko auki", GpsDashboardModel.hideBottomBar(true, 914, 411, true, false))
        assertFalse("asetukset auki", GpsDashboardModel.hideBottomBar(true, 914, 411, false, true))
    }

    @Test fun sovitusEiPienennaMahtuvaa() {
        assertEquals(1f, GpsDashboardModel.fitScale(200) { 200 }, 0f)
        assertEquals(1f, GpsDashboardModel.fitScale(200) { 120 }, 0f)
    }

    @Test fun sovitusIlmanTunnettuaLeveyttaEiPienenna() {
        assertEquals(1f, GpsDashboardModel.fitScale(0) { 500 }, 0f)
    }

    @Test fun sovitusMahduttaaKunLeveysSeuraaKerrointa() {
        val widthAt = { s: Float -> (400 * s).toInt() }
        val scale = GpsDashboardModel.fitScale(200, widthAt = widthAt)
        assertTrue("leveys ${widthAt(scale)}", widthAt(scale) <= 200)
        assertTrue("kerroin $scale on turhan pieni", scale >= 0.45f)
    }

    /**
     * Järjestelmän fonttiskaalaus suurentaa pientä tekstiä enemmän kuin isoa, joten leveys pienenee
     * hitaammin kuin kerroin. Yksi jakolasku jättäisi tekstin liian leveäksi ja luku leikkautuisi.
     */
    @Test fun sovitusMahduttaaVaikkaLeveysPieneneeHitaamminKuinKerroin() {
        val widthAt = { s: Float -> (400 * Math.sqrt(s.toDouble())).toInt() }
        val scale = GpsDashboardModel.fitScale(300, widthAt = widthAt)
        assertTrue("leveys ${widthAt(scale)}", widthAt(scale) <= 300)
        assertTrue("kerroin $scale on turhan pieni", scale >= 0.5f)
    }

    @Test fun sovitusPysahtyyAlarajaan() {
        var calls = 0
        val scale = GpsDashboardModel.fitScale(100, minScale = 0.6f) { calls++; 10_000 }
        assertEquals(0.6f, scale, 0f)
        assertTrue("mittauksia $calls", calls <= 12)
    }

    /** Käyttäjän päätös 10: matkan aikana ylärivi piiloutuu 5 s kosketuksesta ja palaa napauttamalla. */
    @Test fun ylariviPiiloutuuVainKaynnissaOlevanMatkanAikana() {
        val hide = GpsDashboardModel.HEADER_HIDE_MS
        assertEquals(5_000L, hide)
        val running = TripPhase.RUNNING
        // Näkyy viisi sekuntia viimeisestä kosketuksesta, sen jälkeen piilossa.
        assertTrue(GpsDashboardModel.headerVisible(running, 10_000, 10_000, hide))
        assertTrue(GpsDashboardModel.headerVisible(running, 10_000, 14_999, hide))
        assertFalse(GpsDashboardModel.headerVisible(running, 10_000, 15_000, hide))
        assertFalse(GpsDashboardModel.headerVisible(running, 10_000, 3_600_000, hide))
        // Uusi kosketus tuo rivin takaisin.
        assertTrue(GpsDashboardModel.headerVisible(running, 3_600_000, 3_600_001, hide))
        // Ilman käynnissä olevaa matkaa rivi on aina näkyvissä.
        for (phase in listOf(TripPhase.NONE, TripPhase.PAUSED, TripPhase.FINISHED)) {
            assertTrue("$phase", GpsDashboardModel.headerVisible(phase, 0, 3_600_000, hide))
        }
        // Esteettömyysasetus voi poistaa aikarajan: silloin rivi ei piiloudu.
        assertTrue(GpsDashboardModel.headerVisible(running, 0, 3_600_000, Long.MAX_VALUE))
        // Kellon hyppy taaksepäin ei piilota riviä.
        assertTrue(GpsDashboardModel.headerVisible(running, 10_000, 5_000, hide))
    }

    /** Näppäimistökäyttö: rivi ei saa piiloutua, kun kohdistus on sen painikkeessa. */
    @Test fun ylariviPysyyNakyvissaKunKohdistusOnSiina() {
        val hide = GpsDashboardModel.HEADER_HIDE_MS
        assertFalse(GpsDashboardModel.headerVisible(TripPhase.RUNNING, 10_000, 60_000, hide, headerFocused = false))
        assertTrue(GpsDashboardModel.headerVisible(TripPhase.RUNNING, 10_000, 60_000, hide, headerFocused = true))
        assertTrue(GpsDashboardModel.headerVisible(TripPhase.RUNNING, 10_000, 3_600_000, hide, headerFocused = true))
    }

    /**
     * Apupalvelun (esim. kytkinohjaus) käyttäjä ei tuota kosketuksia eikä näppäintapahtumia: kun hän tuo
     * rivin esiin esteettömyystoiminnolla, rivi pysyy näkyvissä eikä piiloudu aikarajasta.
     */
    @Test fun ylariviPysyyNakyvissaKunSeOnTuotuEsiinEsteettomyystoiminnolla() {
        val hide = GpsDashboardModel.HEADER_HIDE_MS
        assertFalse(GpsDashboardModel.headerVisible(TripPhase.RUNNING, 10_000, 60_000, hide, pinned = false))
        assertTrue(GpsDashboardModel.headerVisible(TripPhase.RUNNING, 10_000, 60_000, hide, pinned = true))
        assertTrue(GpsDashboardModel.headerVisible(TripPhase.RUNNING, 10_000, 3_600_000, hide, pinned = true))
    }
}
