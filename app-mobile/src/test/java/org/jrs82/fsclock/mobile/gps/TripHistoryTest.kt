package org.jrs82.fsclock.mobile.gps

import org.jrs82.fsclock.db.TripEntity
import org.jrs82.fsclock.mobile.FakeSharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class TripHistoryTest {
    private fun stamp(time: Long) = SaveStamp(time, time, "session")
    private fun row(id: String, start: Long, end: Long, distance: Double, total: Long, moving: Long, top: Float) =
        TripEntity(id, start, end, false, distance, total, moving, top)

    @Test fun monthsGroupNewestFirstAcrossYearBoundary() {
        val jan2 = LocalDate.of(2027, 1, 2)
        val dec31 = LocalDate.of(2026, 12, 31)
        val nov26 = LocalDate.of(2026, 11, 26)
        val nov25 = LocalDate.of(2026, 11, 25)
        val previousYear = LocalDate.of(2025, 11, 20)
        val days = listOf(nov25, jan2, previousYear, dec31, nov26).associateWith { emptyList<TripEntity>() }
        assertEquals(listOf(
            YearMonth.of(2027, 1) to listOf(jan2),
            YearMonth.of(2026, 12) to listOf(dec31),
            YearMonth.of(2026, 11) to listOf(nov26, nov25),
            YearMonth.of(2025, 11) to listOf(previousYear),
        ), historyMonths(days))
        val start = Instant.parse("2026-11-30T22:30:00Z").toEpochMilli()
        val trip = row("midnight", start, start + 60_000, 250.0, 60_000, 60_000, 5f)
        val localDays = tripDays(listOf(trip), ZoneId.of("Europe/Helsinki"))
        assertEquals(listOf(YearMonth.of(2026, 12) to listOf(LocalDate.of(2026, 12, 1))), historyMonths(localDays))
    }

    @Test fun legacyPhasesWithAndWithoutStampKeepOriginalKeysUntilWrite() {
        for (phase in listOf(TripPhase.FINISHED, TripPhase.PAUSED, TripPhase.RUNNING)) {
            for (hasStamp in listOf(false, true)) {
                val prefs = FakeSharedPreferences()
                val saved = TripSnapshot(phase, 120.5, 10_000, 8_000, 4.5f)
                val store = TripStore(prefs)
                // Kirjoitetaan hyväksytyn ehdokkaan fyysiset avaimet.
                val editor = prefs.edit().putString("phase", phase.name)
                    .putLong("distance_bits", saved.distanceM.toRawBits()).putLong("total_ms", saved.totalMs)
                    .putLong("moving_ms", saved.movingMs).putFloat("max_speed_mps", saved.maxSpeedMps)
                if (hasStamp) editor.putLong("saved_at_wall_ms", 51_000).putLong("saved_at_elapsed_ms", 1_000)
                    .putString("session", "old")
                editor.commit()
                val original = prefs.all.toMap()
                assertEquals(saved, store.load())
                val restored = TripCalculator.restore(saved, 3_000)
                store.recover(saved, restored, stamp(91_000))
                val reopened = TripStore(prefs)
                assertEquals(restored, reopened.load())
                reopened.recover(restored, restored, stamp(100_000))
                if (phase != TripPhase.FINISHED) {
                    assertTrue(reopened.pendingTrips().isEmpty())
                    reopened.transition(restored.copy(phase = TripPhase.FINISHED), stamp(120_000))
                }
                val entry = reopened.pendingTrips().single()
                assertTrue(entry.estimatedStart)
                assertEquals(if (hasStamp) 41_000L else 81_000L, entry.startedAtMs)
                val expectedEnd = if (phase != TripPhase.FINISHED) 120_000L else if (hasStamp) 51_000L else 91_000L
                assertEquals(expectedEnd, entry.endedAtMs)
                assertEquals(original, prefs.all.filterKeys { it in original })
                reopened.acknowledge(entry)
                reopened.recover(reopened.load(), reopened.load(), stamp(130_000))
                assertTrue(reopened.pendingTrips().isEmpty())
            }
        }
    }

    @Test fun partialStampStillSuppliesItsWallTimeForTheHistoryEstimate() {
        val prefs = FakeSharedPreferences()
        val store = TripStore(prefs)
        val saved = TripSnapshot(TripPhase.PAUSED, 5.0, 12_000, 2_000, 3f)
        store.save(saved)
        prefs.edit().putLong("saved_at_wall_ms", 45_000).putString("trip_id", "existing").apply()
        assertNull(store.stamp()) // vaihe 1:n aikahyvitys vaatii koko leiman
        store.recover(saved, saved, stamp(800_000))
        store.transition(saved.copy(phase = TripPhase.FINISHED), stamp(900_000))
        val row = store.pendingTrips().single()
        assertEquals("existing", row.id)
        assertEquals(33_000L, row.startedAtMs)
        assertTrue(row.estimatedStart)
    }

    // Mallissa historia on viimeisin lopetus tunnisteittain. Poisto pyyhkii vain valitun lopetuksen.
    // Toteutus saa jättää kirjoituksen jonoon, kuitata sen tai kaatua kummallakin kirjoitusrajalla.
    private enum class Op { START, NEW, PAUSE, RESUME, CONTINUE, FINISH, RESET, DELETE, WRITE, CRASH, CRASH_AFTER_WRITE }

    @Test fun allShortCommandSequencesMatchLastFinishMinusDeletes() {
        var checked = 0
        fun visit(phase: TripPhase, sequence: List<Op>) {
            val prefs = FakeSharedPreferences()
            var nextId = 0
            fun open() = TripStore(prefs) { (++nextId).toString() }
            var store = open()
            var active = TripSnapshot()
            var id = 0
            val expected = mutableMapOf<String, Pair<Long, TripSnapshot>>()
            val db = mutableMapOf<String, Pair<Long, TripSnapshot>>()
            fun write(ack: Boolean) {
                for (entry in store.pendingTrips()) {
                    db[entry.id] = entry.endedAtMs to entry.snapshot()
                    if (ack) store.acknowledge(entry)
                }
            }
            for ((index, op) in sequence.withIndex()) {
                val time = 100_000L + index * 1_000
                when (op) {
                    Op.START, Op.NEW -> {
                        id++
                        active = TripSnapshot(TripPhase.RUNNING)
                        store.transition(active, stamp(time), newTrip = true)
                    }
                    Op.PAUSE, Op.RESUME, Op.CONTINUE, Op.FINISH, Op.RESET -> {
                        val next = when (op) {
                            Op.PAUSE -> TripPhase.PAUSED
                            Op.RESUME, Op.CONTINUE -> TripPhase.RUNNING
                            Op.FINISH -> TripPhase.FINISHED
                            else -> TripPhase.NONE
                        }
                        active = if (op == Op.RESET) TripSnapshot() else active.copy(phase = next,
                            distanceM = active.distanceM + 0.5, totalMs = active.totalMs + 1_000)
                        store.transition(active, stamp(time))
                        if (op == Op.FINISH) expected[id.toString()] = time to active
                    }
                    Op.WRITE -> write(true)
                    Op.DELETE -> {
                        write(true)
                        val victim = expected.keys.firstOrNull()
                        expected.remove(victim)
                        db.remove(victim)
                    }
                    Op.CRASH, Op.CRASH_AFTER_WRITE -> {
                        if (op == Op.CRASH_AFTER_WRITE) write(false)
                        store = open()
                        val saved = store.load()
                        active = TripCalculator.restore(saved)
                        store.recover(saved, active, stamp(time))
                    }
                }
            }
            write(true)
            assertEquals(sequence.toString(), expected, db)
            checked++
            if (sequence.size == 6) return
            val commands = when (phase) {
                TripPhase.NONE -> listOf(Op.START)
                TripPhase.RUNNING -> listOf(Op.PAUSE, Op.FINISH)
                TripPhase.PAUSED -> listOf(Op.RESUME, Op.FINISH)
                TripPhase.FINISHED -> listOf(Op.RESET, Op.NEW, Op.CONTINUE)
            }
            for (op in commands + listOf(Op.DELETE, Op.WRITE, Op.CRASH, Op.CRASH_AFTER_WRITE)) {
                val next = when (op) {
                    Op.START, Op.NEW, Op.RESUME, Op.CONTINUE -> TripPhase.RUNNING
                    Op.PAUSE -> TripPhase.PAUSED
                    Op.FINISH -> TripPhase.FINISHED
                    Op.RESET -> TripPhase.NONE
                    Op.CRASH, Op.CRASH_AFTER_WRITE -> if (phase == TripPhase.RUNNING) TripPhase.PAUSED else phase
                    else -> phase
                }
                visit(next, sequence + op)
            }
        }
        visit(TripPhase.NONE, emptyList())
        assertTrue(checked > 30_000)
    }

    @Test fun oldAcknowledgementCannotClearANewerFinish() {
        val store = TripStore(FakeSharedPreferences())
        val trip = TripSnapshot(TripPhase.RUNNING)
        store.transition(trip, stamp(1000), newTrip = true)
        store.transition(trip.copy(phase = TripPhase.FINISHED), stamp(2000))
        val first = store.pendingTrips().single()
        store.transition(trip, stamp(3000))
        store.transition(trip.copy(phase = TripPhase.FINISHED, totalMs = 4_000), stamp(4000))
        store.acknowledge(first)
        val last = store.pendingTrips().single()
        assertEquals(first.id, last.id)
        assertEquals(4000L, last.endedAtMs)
    }

    @Test fun daysUseLocalStartAcrossMidnightAndDstAndWeightedSpeeds() {
        fun ms(s: String) = Instant.parse(s).toEpochMilli()
        val a = row("a", ms("2026-10-24T20:50:00Z"), ms("2026-10-24T21:10:00Z"), 100.0, 20_000, 10_000, 8f)
        val b = row("b", ms("2026-10-25T00:30:00Z"), ms("2026-10-25T01:30:00Z"), 400.0, 20_000, 10_000, 12f)
        val c = row("c", ms("2026-10-25T01:10:00Z"), ms("2026-10-25T02:00:00Z"), 600.0, 80_000, 30_000, 10f)
        val zone = ZoneId.of("Europe/Helsinki")
        val days = tripDays(listOf(a, c, b), zone)
        assertEquals(listOf("2026-10-25", "2026-10-24"), days.keys.map { it.toString() })
        assertEquals(listOf("c", "b"), days.values.first().map { it.id })
        val sum = daySnapshot(days.values.first())
        assertEquals(36.0, sum.avgTotalKmh!!, 1e-9)
        assertEquals(90.0, sum.avgMovingKmh!!, 1e-9)
        assertEquals(60_000L, sum.stoppedMs)
        assertEquals(12f, sum.maxSpeedMps)
        assertNull(daySnapshot(emptyList()).avgTotalKmh)
        assertTrue(tripDays(emptyList(), zone).isEmpty())
    }

    @Test fun backupRoundTripPreservesFiguresAndSkipsMalformedRows() {
        val a = row("a", 1_000, 4_000, 123.456, 3_000, 2_000, 5.25f)
        val b = TripEntity("b", 2_000, 5_000, true, 0.0, 0, 0, 0f)
        val root = JSONObject().apply { putTrips(listOf(a, b)) }
        root.getJSONArray("trips").put(false)
            .put(JSONObject(a.toJson().toString()).put("movingMs", 3_001))
            .put(JSONObject(a.toJson().toString()).put("totalMs", 1.75))
            .put(JSONObject(a.toJson().toString()).put("distanceM", "NaN"))
            .put(JSONObject(a.toJson().toString()).put("id", JSONObject.NULL))
        val restored = tripEntries(JSONObject(root.toString()))
        assertEquals(listOf("a", "b"), restored.map { it.id })
        restored.zip(listOf(a, b)).forEach { (actual, expected) ->
            assertEquals(expected.id, actual.id)
            assertEquals(expected.startedAtMs, actual.startedAtMs)
            assertEquals(expected.endedAtMs, actual.endedAtMs)
            assertEquals(expected.estimatedStart, actual.estimatedStart)
            assertEquals(expected.snapshot(), actual.snapshot())
        }
        assertTrue(tripEntries(JSONObject()).isEmpty())
    }
}
