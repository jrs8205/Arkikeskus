package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.ZoneOffset
import org.jrs82.fsclock.db.TripEntity
import javax.xml.parsers.DocumentBuilderFactory

class TripHistoryUiTest {
    @Test fun dayIntervalUsesIndependentExtremesAndOnlyTheEarliestStartEstimate() {
        fun trip(id: String, start: Long, end: Long, estimate: Boolean) =
            TripEntity(id, start, end, estimate, 100.0, 60_000, 50_000, 20f)
        val early = trip("early", 10, 70, true)
        val longest = trip("longest", 20, 100, false)
        val newest = trip("newest", 30, 60, true)
        for (rows in listOf(listOf(early, longest, newest), listOf(newest, longest, early))) {
            assertEquals(HistoryInterval(10, 100, true), dayInterval(rows))
        }
        assertEquals(HistoryInterval(20, 100, false), dayInterval(listOf(newest, longest)))
        assertEquals(HistoryInterval(30, 60, true), dayInterval(listOf(newest)))
        val exact = trip("exact", 10, 60, false)
        for (rows in listOf(listOf(exact, early), listOf(early, exact))) {
            assertEquals(HistoryInterval(10, 70, false), dayInterval(rows))
        }
    }

    @Test fun openingAndReturningDependsOnTheOriginEvenWhenOnlyOneTripRemains() {
        val a = TripEntity("A", 10, 70, true, 800.0, 60_000, 50_000, 20f)
        val b = TripEntity("B", 30, 60, false, 900.0, 20_000, 10_000, 20f)
        val days = tripDays(listOf(a, b), ZoneOffset.UTC)
        val date = days.keys.single()
        val direct = openHistoryDay(date, listOf(a))
        val day = openHistoryDay(date, days.getValue(date))
        val nested = day.copy(tripId = a.id)
        assertEquals(HistorySelection(tripId = a.id), direct)
        assertEquals(HistorySelection(dayKey = date.toString()), day)
        assertEquals(HistorySelection(), direct.parent())
        assertEquals(day, nested.parent())
        assertEquals(HistorySelection(), day.parent())
        val remaining = mapOf(date to listOf(b))
        assertEquals(HistoryDetail.Day(date, listOf(b)), historyDetail(remaining, nested.dayKey, nested.tripId))
        assertNull(historyDetail(remaining, direct.dayKey, direct.tripId))
        assertNull(historyDetail(emptyMap(), nested.dayKey, nested.tripId))
    }

    @Test fun populatedHistoryResolvesListDayTripAndRemovedSelections() {
        val row = TripEntity("A", 1_700_000_000_000, 1_700_000_060_000, false, 800.0, 60_000, 50_000, 20f)
        val days = tripDays(listOf(row), ZoneOffset.UTC)
        val date = days.keys.single()
        assertNull(historyDetail(days, null, null))
        assertEquals(HistoryDetail.Day(date, listOf(row)), historyDetail(days, date.toString(), null))
        assertEquals(HistoryDetail.Trip(row), historyDetail(days, null, row.id))
        assertNull(historyDetail(days, date.minusDays(1).toString(), null))
        assertNull(historyDetail(days, null, "removed"))
        assertNull(historyDetail(null, date.toString(), null))
    }

    @Test fun historyOpensWithFineCoarseOrNoPermission() {
        // Likimääräinen on sama syöte kuin evätty tarkka lupa. Paluu palauttaa alkuperäisen kohteen.
        for (fine in listOf(false, true)) {
            assertEquals(GpsPage.HISTORY, gpsPage(historyOpen = true, fineGranted = fine))
            assertEquals(if (fine) GpsPage.DASHBOARD else GpsPage.PERMISSION, gpsPage(false, fine))
        }
    }

    @Test fun allHistoryTextsExistAndEmptyStateUsesTheCurrentStopAction() {
        fun strings(folder: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$folder/strings_gps.xml"))
            val nodes = doc.documentElement.childNodes
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                .associate { it.getAttribute("name") to it.textContent }
        }
        val english = strings("values")
        val keys = english.keys.filter { it.startsWith("gps_history") }.toSet()
        assertTrue("history resources", keys.size >= 8)
        for (folder in listOf("values", "values-fi", "values-sv")) {
            val resources = strings(folder)
            assertEquals(keys, resources.keys.filter { it.startsWith("gps_history") }.toSet())
            val stop = resources.getValue("gps_action_finish")
            val empty = resources.getValue("gps_history_empty")
            assertTrue(empty.format(stop).contains(stop))
            assertTrue(empty.format("test-stop-label").contains("test-stop-label"))
        }
    }
}
