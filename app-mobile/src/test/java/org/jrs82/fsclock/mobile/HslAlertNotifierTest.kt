package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4b-ilmoitus #1: [HslAlertNotifier.selectNewAlerts] -valintalogiikan yksikkötestit (puhdas, ei Androidia). */
class HslAlertNotifierTest {

    private val now = 1_000_000L

    private fun alert(
        header: String,
        route: String = "",
        stop: String = "",
        start: Long = 0L,
        end: Long = 0L,
    ) = TransitAlert(header, "kuvaus", "WARNING", "DETOUR", start, end, route, "BUS", stop)

    @Test fun lineMatchNotifies() {
        val a = alert("Linja 63 poikkeaa", route = "63")
        val r = HslAlertNotifier.selectNewAlerts(listOf(a), setOf("63"), emptySet(), emptySet(), now)
        assertEquals(1, r.toNotify.size)
        assertTrue(r.newSeen.contains(HslAlertNotifier.alertKey(a)))
    }

    @Test fun stopMatchNotifiesByName() {
        val a = alert("Pysäkki suljettu", stop = "Rautatientori")
        val r = HslAlertNotifier.selectNewAlerts(listOf(a), emptySet(), setOf("Rautatientori"), emptySet(), now)
        assertEquals(1, r.toNotify.size)
    }

    @Test fun inactiveAlertFilteredOut() {
        val a = alert("Mennyt", route = "63", end = now - 100) // päättynyt → isActiveAt false
        val r = HslAlertNotifier.selectNewAlerts(listOf(a), setOf("63"), emptySet(), emptySet(), now)
        assertTrue(r.toNotify.isEmpty())
        assertTrue(r.newSeen.isEmpty())
    }

    @Test fun alreadySeenNotNotifiedButStaysSeen() {
        val a = alert("Linja 63 poikkeaa", route = "63")
        val seen = setOf(HslAlertNotifier.alertKey(a))
        val r = HslAlertNotifier.selectNewAlerts(listOf(a), setOf("63"), emptySet(), seen, now)
        assertTrue(r.toNotify.isEmpty())
        assertTrue(r.newSeen.contains(HslAlertNotifier.alertKey(a)))
    }

    @Test fun resolvedAlertDropsFromSeen() {
        val a = alert("Linja 63 poikkeaa", route = "63")
        val seen = setOf("vanha|häiriö|7|") // ei enää aktiivisissa
        val r = HslAlertNotifier.selectNewAlerts(listOf(a), setOf("63"), emptySet(), seen, now)
        assertEquals(setOf(HslAlertNotifier.alertKey(a)), r.newSeen)
        assertFalse(r.newSeen.contains("vanha|häiriö|7|"))
    }

    @Test fun noFavoritesMatchesNothing() {
        val r = HslAlertNotifier.selectNewAlerts(
            listOf(alert("Linja 63", route = "63"), alert("Pysäkki", stop = "Rautatientori")),
            emptySet(), emptySet(), emptySet(), now,
        )
        assertTrue(r.toNotify.isEmpty())
        assertTrue(r.newSeen.isEmpty())
    }

    @Test fun unrelatedAlertIgnored() {
        val a = alert("Muu linja", route = "550", stop = "Muupysäkki")
        val r = HslAlertNotifier.selectNewAlerts(listOf(a), setOf("63"), setOf("Rautatientori"), emptySet(), now)
        assertTrue(r.toNotify.isEmpty())
    }

    @Test fun duplicateAlertNotifiedOnce() {
        val a1 = alert("Linja 63 poikkeaa", route = "63")
        val a2 = alert("Linja 63 poikkeaa", route = "63") // sama identiteetti
        val r = HslAlertNotifier.selectNewAlerts(listOf(a1, a2), setOf("63"), emptySet(), emptySet(), now)
        assertEquals(1, r.toNotify.size)
        assertEquals(1, r.newSeen.size)
    }
}
