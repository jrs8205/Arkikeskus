package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Muistutuksen yritykset: epäonnistunut näyttö ei saa toistua jokaisella palvelun tikillä. */
class ReminderFlowTest {

    private val retry = 900_000L
    private val due = ReminderClaim(7, TripSnapshot(TripPhase.RUNNING, idleMs = 7_200_000))

    @Test fun nayttoOnnistuuEnsimmaisellaYrityksella() {
        var posts = 0
        val released = ArrayList<Long>()
        val flow = ReminderFlow(retry, { due }, { posts++; true }, { released.add(it) })
        assertTrue(flow.attempt(1_000))
        assertEquals(1, posts)
        assertTrue(released.isEmpty())
        assertEquals(0L, flow.retryInMs(1_000))
    }

    /** Ilmoituslupa evätty: palvelu tikittää 5 s välein 15 minuuttia. */
    @Test fun epaonnistunuttaNayttoaEiYritetaJokaisellaTikilla() {
        var claims = 0
        var posts = 0
        val released = ArrayList<Long>()
        val flow = ReminderFlow(retry, { claims++; due }, { posts++; false }, { released.add(it) })
        var now = 10_000L
        while (now < 10_000L + retry) {
            assertFalse(flow.attempt(now))
            now += 5_000
        }
        // Yksi yritys koko uusintavälin aikana, ja sen varaus peruttiin.
        assertEquals(1, posts)
        assertEquals(1, claims)
        assertEquals(listOf(7L), released)
        // Uusintavälin jälkeen yritetään uudelleen.
        assertFalse(flow.attempt(10_000L + retry))
        assertEquals(2, posts)
    }

    @Test fun herataanSamaanAikaanKuinSeuraavaYritysOnSallittu() {
        val flow = ReminderFlow(retry, { due }, { false }, { })
        flow.attempt(10_000)
        assertEquals(retry, flow.retryInMs(10_000))
        assertEquals(retry - 60_000, flow.retryInMs(70_000))
        assertEquals(0L, flow.retryInMs(10_000 + retry))
    }

    @Test fun onnistunutNayttoPoistaaOdotuksen() {
        var ok = false
        val flow = ReminderFlow(retry, { due }, { ok }, { })
        assertFalse(flow.attempt(10_000))
        ok = true
        assertFalse(flow.attempt(20_000))
        assertTrue(flow.attempt(10_000 + retry))
        assertEquals(0L, flow.retryInMs(10_000 + retry))
    }

    @Test fun ilmanAjankohtaistaMuistutustaEiYritetaMitaan() {
        var posts = 0
        val flow = ReminderFlow(retry, { null }, { posts++; true }, { })
        assertFalse(flow.attempt(1_000))
        assertEquals(0, posts)
        assertEquals(0L, flow.retryInMs(1_000))
    }
}
