package org.jrs82.fsclock.mobile.gps

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Pysyvän ilmoituksen sisältö matkan tilan mukaan ja muistutuksen raja. */
class TripNotificationTest {

    private val fi = Locale.forLanguageTag("fi-FI")
    private val run = TripSnapshot(TripPhase.RUNNING, 12_400.0, 2_520_000, 2_400_000, 20f)

    @Test fun kaynnissaNaytetaanNopeusJaTaukoLopeta() {
        assertEquals(
            TripNotifModel(R.string.gps_trip_running, "12,4 km · 42 min · 63 km/h", listOf(TripAction.PAUSE, TripAction.FINISH)),
            tripNotifModel(run, 63, fi),
        )
    }

    @Test fun tauollaEiNopeuttaJaJatkaLopeta() {
        assertEquals(
            TripNotifModel(R.string.gps_trip_paused, "12,4 km · 42 min", listOf(TripAction.RESUME, TripAction.FINISH)),
            tripNotifModel(run.copy(phase = TripPhase.PAUSED), 63, fi),
        )
    }

    @Test fun lopetetullaJaTyhjallaEiOleIlmoitusta() {
        // Lopetuksen jälkeen saapuva päivitys ei saa herättää ilmoitusta uudelleen henkiin.
        assertNull(tripNotifModel(run.copy(phase = TripPhase.FINISHED), 63, fi))
        assertNull(tripNotifModel(TripSnapshot(), null, fi))
    }

    @Test fun muistutuksenRajaOnKaksiTuntiaJulkaisussa() {
        // Debug-rakennuksen lyhyt raja on vain kokeilua varten: se ei saa päätyä julkaistavaan rakennukseen.
        assertEquals(7_200_000L, reminderLimitMs(debug = false))
        assertEquals(120_000L, reminderLimitMs(debug = true))
    }

    @Test fun muistutuksenTarkistusAjastetaanJaljellaOlevanAjanMukaan() {
        val limit = 7_200_000L
        // Herätys ei saa riippua sovelluksen omasta tikistä: seuraava tarkistus lasketaan tilasta.
        assertEquals(limit, nextReminderCheckDelayMs(run, limit, 0L))
        assertEquals(5_400_000L, nextReminderCheckDelayMs(run.copy(idleMs = 1_800_000), limit, 0L))
        // Raja täyttyy aivan pian: tarkistetaan lyhimmän välin päästä.
        assertEquals(60_000L, nextReminderCheckDelayMs(run.copy(idleMs = 7_190_000), limit, 0L))
        // Muistutettu: seuraava tarkistus vasta täyden rajan päästä (liike virittää muistutuksen uudelleen).
        assertEquals(limit, nextReminderCheckDelayMs(run.copy(idleMs = 8_000_000, reminded = true), limit, 0L))
        // Tauolla, lopetettuna ja ilman matkaa ei ajasteta mitään.
        assertNull(nextReminderCheckDelayMs(run.copy(phase = TripPhase.PAUSED), limit, 0L))
        assertNull(nextReminderCheckDelayMs(run.copy(phase = TripPhase.FINISHED), limit, 0L))
        assertNull(nextReminderCheckDelayMs(TripSnapshot(), limit, 0L))
    }

    /**
     * Raja on täynnä, mutta muistutusta ei ole merkitty annetuksi: sitä ei saatu näytettyä (esim. ilmoituslupa
     * evätty). Uusi yritys tehdään harvakseltaan, ei minuutin välein koko loppumatkaa.
     */
    @Test fun nayttamattaJaanyttaMuistutustaYritetaanUudelleenHarvakseltaan() {
        val limit = 7_200_000L
        // Juuri epäonnistunut yritys: herätys koko uusintavälin päähän.
        assertEquals(900_000L, nextReminderCheckDelayMs(run.copy(idleMs = limit), limit, 900_000L))
        assertEquals(900_000L, nextReminderCheckDelayMs(run.copy(idleMs = limit + 3_600_000), limit, 900_000L))
        // Tikin yritys epäonnistui 14 min sitten: herätys jäljellä olevan minuutin päähän, ei täyden välin.
        assertEquals(60_000L, nextReminderCheckDelayMs(run.copy(idleMs = limit + 840_000), limit, 60_000L))
        assertEquals(240_000L, nextReminderCheckDelayMs(run.copy(idleMs = limit + 660_000), limit, 240_000L))
        // Yritys on jo sallittu tai sallitaan aivan pian: lyhin tarkistusväli.
        assertEquals(60_000L, nextReminderCheckDelayMs(run.copy(idleMs = limit), limit, 0L))
        assertEquals(60_000L, nextReminderCheckDelayMs(run.copy(idleMs = limit), limit, 5_000L))
        // Uusintaväli ei vaikuta, kun raja ei ole vielä täynnä tai muistutus on jo annettu.
        assertEquals(5_400_000L, nextReminderCheckDelayMs(run.copy(idleMs = 1_800_000), limit, 900_000L))
        assertEquals(limit, nextReminderCheckDelayMs(run.copy(idleMs = 8_000_000, reminded = true), limit, 900_000L))
    }
}
