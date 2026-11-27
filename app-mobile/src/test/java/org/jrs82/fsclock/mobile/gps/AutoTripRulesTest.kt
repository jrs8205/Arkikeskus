package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.*
import org.junit.Test
import org.jrs82.fsclock.db.TripEntity

class AutoTripRulesTest {
    @Test fun onlySelectedCarsProduceFirstConnectAndLastDisconnect() {
        val cars = setOf("A", "B")
        var connected = emptySet<String>()
        fun event(address: String, up: Boolean, enabled: Boolean = true): CarEvent? {
            val result = carConnection(enabled, cars, connected, address, up)
            connected = result.connected
            return result.event
        }
        assertNull(event("watch", true))
        assertNull(event("A", true, false))
        assertEquals(CarEvent.LOST, event("B", false))
        assertEquals(CarEvent.FOUND, event("A", true))
        assertNull(event("A", true))
        assertNull(event("B", true))
        assertNull(event("watch", false))
        assertNull(event("A", false))
        assertEquals(CarEvent.LOST, event("B", false))
    }

    @Test fun twoTransportsOfOneCarStayConnectedUntilTheLastLinkDrops() {
        val cars = setOf("car")
        val classic = carConnection(true, cars, emptySet(), "car", true, 1)
        val both = carConnection(true, cars, classic.connected, "car", true, 2)
        val remaining = carConnection(true, cars, both.connected, "car", false, 2)
        assertEquals(CarEvent.FOUND, classic.event)
        assertNull(both.event)
        assertNull(remaining.event)
        assertEquals(CarEvent.LOST, carConnection(true, cars, remaining.connected, "car", false, 1).event)
    }

    @Test fun deadlineAndMinimumDistanceIncludeTheirExactBoundaries() {
        val waiting = CarTiming(disconnectWall = 1_000, disconnectElapsed = 50, boot = 4)
        assertEquals(1L, waiting.remaining(900_000, 60_049, 4))
        assertEquals(0L, waiting.remaining(2_000, 60_050, 4))
        assertEquals(1L, waiting.remaining(60_999, 500, 5))
        assertEquals(0L, waiting.remaining(61_000, 1, 5))
        for (start in listOf(false, true)) for (end in listOf(false, true)) {
            for (distance in listOf(0.0, 99.99, 100.0, 100.01)) {
                assertEquals(start && end && distance < 100, discardAutoTrip(start, end, distance))
            }
        }
    }

    private enum class Step { Y, K, O, PAUSE, RESUME, FINISH, OFF, ON, DIE }

    @Test fun actionTableMatchesShortEventSequences() {
        // Mallin rivit ovat ei matkaa, ajo, käyttäjän tauko, järjestelmän tauko, odotus ja lopetettu.
        val table = listOf(
            listOf(CarAction.START, CarAction.NONE, CarAction.NONE),
            listOf(CarAction.NONE, CarAction.WAIT, CarAction.NONE),
            listOf(CarAction.RESUME, CarAction.NONE, CarAction.NONE),
            listOf(CarAction.RESUME, CarAction.WAIT, CarAction.NONE),
            listOf(CarAction.RESUME, CarAction.NONE, CarAction.FINISH),
            listOf(CarAction.START, CarAction.NONE, CarAction.NONE),
        )
        val phases = listOf(TripPhase.NONE, TripPhase.RUNNING, TripPhase.PAUSED,
            TripPhase.PAUSED, TripPhase.PAUSED, TripPhase.FINISHED)
        data class Case(val row: Int, val snapshot: TripSnapshot, val timing: CarTiming,
                        val enabled: Boolean, val stepsLeft: Int)
        for (age in listOf(43_199_999L, 43_200_000L, 43_200_001L)) {
            // Sama tila ja yhtä pitkä jäljellä oleva jono tuottavat samat jatkot.
            val visited = mutableSetOf<Case>()
            fun visit(input: Case) {
                val (row, snap, clock, enabled, left) = input
                assertEquals(phases[row], snap.phase)
                assertEquals(row == 4, clock.waiting)
                if (snap.phase == TripPhase.PAUSED) assertEquals(row == 2, clock.userPaused)
                if (left == 0 || !visited.add(input)) return
                val wall = age + 1_000
                for (step in Step.entries) {
                    val calc = TripCalculator(if (snap.phase == TripPhase.RUNNING) snap.copy(phase = TripPhase.PAUSED) else snap)
                    if (snap.phase == TripPhase.RUNNING) calc.resume(0)
                    var next = row
                    var timing = clock
                    var on = enabled
                    val moving = CarTiming(startedAuto = clock.startedAuto)
                    when (step) {
                        Step.Y, Step.K, Step.O -> if (on) {
                            val event = CarEvent.entries[step.ordinal]
                            val expected = if (step == Step.Y && row in 2..3 && age > 43_200_000)
                                CarAction.REPLACE else table[row][step.ordinal]
                            val action = carAction(calc.snapshot(0).phase, timing, event, wall)
                            assertEquals(expected, action)
                            next = when (expected) {
                                CarAction.NONE -> row
                                CarAction.WAIT -> 4
                                CarAction.FINISH -> if (clock.startedAuto && snap.distanceM < 100) 0 else 5
                                else -> 1
                            }
                            when (action) {
                                CarAction.START, CarAction.REPLACE -> {
                                    if (action == CarAction.REPLACE) calc.finish(0)
                                    assertTrue(calc.start(0))
                                    timing = CarTiming(startedAuto = true)
                                }
                                CarAction.RESUME -> { assertTrue(calc.resume(0)); timing = moving }
                                CarAction.WAIT -> {
                                    calc.pause(0)
                                    timing = moving.copy(pausedWall = 1_000, disconnectWall = wall)
                                }
                                CarAction.FINISH -> {
                                    assertTrue(calc.finish(0))
                                    if (discardAutoTrip(clock.startedAuto, true, snap.distanceM)) calc.reset()
                                    timing = if (calc.snapshot(0).phase == TripPhase.NONE) CarTiming() else moving
                                }
                                CarAction.NONE -> Unit
                            }
                        }
                        Step.PAUSE -> {
                            if (row == 1) next = 2
                            if (calc.pause(0)) timing = moving.copy(pausedWall = 1_000, userPaused = true)
                        }
                        Step.RESUME -> {
                            if (row in 2..5) next = 1
                            if (calc.resume(0)) timing = moving
                        }
                        Step.FINISH -> {
                            if (row in 1..4) next = 5
                            if (calc.finish(0)) timing = moving
                        }
                        Step.OFF -> {
                            on = false
                            if (row == 4) next = 3
                            if (timing.waiting) timing = moving.copy(pausedWall = 1_000)
                        }
                        Step.ON -> on = true
                        Step.DIE -> {
                            if (row == 1) next = 3
                            if (snap.phase == TripPhase.RUNNING) timing = moving.copy(pausedWall = 1_000)
                        }
                    }
                    val result = calc.snapshot(0).let { if (step == Step.DIE) TripCalculator.restore(it) else it }
                    visit(Case(next, result, timing, on, left - 1))
                }
            }
            for (row in table.indices) for (started in listOf(false, true)) for (meters in listOf(99.0, 100.0, 101.0)) {
                val timing = CarTiming(started, if (row in 2..4) 1_000 else null, row == 2,
                    if (row == 4) 1_000 else null)
                visit(Case(row, TripSnapshot(phases[row], meters), timing, true, 4))
            }
        }
    }

    @Test fun backupFlagsRoundTripAndOldRowsDefaultToManual() {
        val row = TripEntity("car", 10, 20, false, 101.0, 10, 4, 2f, true, true)
        assertTrue(row.sameFinish(row.toJson().tripOrNull()))
        val old = row.toJson().apply { remove("startedAutomatically"); remove("endedAutomatically") }.tripOrNull()!!
        assertFalse(old.startedAutomatically)
        assertFalse(old.endedAutomatically)
        assertFalse(row.sameFinish(old))
    }
}
