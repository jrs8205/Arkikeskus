package org.jrs82.fsclock.mobile.gps

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.jrs82.fsclock.db.TripDao
import org.jrs82.fsclock.db.TripEntity
import org.jrs82.fsclock.mobile.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TripHistoryWriterTest {
    private val store = TripStore(FakeSharedPreferences())
    private val trip = TripSnapshot(TripPhase.FINISHED, 10.5, 3_000, 1_000, 6f)

    private fun finish() {
        store.transition(trip.copy(phase = TripPhase.RUNNING), SaveStamp(1000, 1000, "s"), newTrip = true)
        store.transition(trip, SaveStamp(4000, 4000, "s"))
    }

    /** apply näkyy samassa prosessissa heti; testi siirtää erikseen vain commitin saavuttaneen tilan levylle. */
    private class BufferedPrefs(private val disk: MutableMap<String, Any?>,
                                private val memory: FakeSharedPreferences = FakeSharedPreferences()) :
        SharedPreferences by memory {
        init { memory.map.putAll(disk) }
        var dropNextCommit = false
        override fun edit(): SharedPreferences.Editor {
            val editor = memory.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    editor.commit()
                    if (dropNextCommit) return false
                    disk.clear()
                    disk.putAll(memory.map)
                    return true
                }
            }
        }
    }

    @Test fun restartAtEachDurableBoundaryReplaysExactlyOnceEvenAfterReset() {
        for (cut in 0..2) {
            val disk = mutableMapOf<String, Any?>()
            val prefs = BufferedPrefs(disk)
            val first = TripStore(prefs)
            first.transition(trip.copy(phase = TripPhase.RUNNING), SaveStamp(1000, 1000, "s"), newTrip = true)
            first.transition(trip, SaveStamp(4000, 4000, "s"))
            assertTrue(first.flush())
            val expected = first.pendingTrips().single()
            first.transition(TripSnapshot(), SaveStamp(5000, 5000, "s"))
            assertTrue(first.flush())
            val db = mutableMapOf<String, TripEntity>()
            if (cut >= 1) {
                val writer = TripHistoryWriter(first) {
                    db[it.id] = it
                    prefs.dropNextCommit = cut == 1
                }
                if (cut == 1) {
                    assertThrows(java.io.IOException::class.java) { writer.afterWrites { fail("deletion before ack") } }
                } else writer.afterWrites { db.remove(expected.id) }
            }
            val recovered = TripStore(BufferedPrefs(disk))
            recovered.recover(recovered.load(), recovered.load(), SaveStamp(6000, 6000, "next"))
            TripHistoryWriter(recovered) { db[it.id] = it }.afterWrites {}
            if (cut == 2) assertTrue(db.isEmpty()) else assertTrue(expected.sameFinish(db.values.single()))
        }
    }

    @Test fun failedRoomWriteLeavesFinishForRetryAndPreventsDeletion() {
        finish()
        val entry = store.pendingTrips().single()
        val failed = TripHistoryWriter(store) { throw IllegalStateException("disk full") }
        assertThrows(IllegalStateException::class.java) { failed.afterWrites { fail("delete ran") } }
        val db = mutableListOf<String>()
        TripHistoryWriter(store) { db.add(it.id) }.afterWrites { assertTrue(store.pendingTrips().isEmpty()) }
        assertEquals(listOf(entry.id), db)
    }

    @Test fun deleteUndoAndStaleDetailsPreserveTheLastFinish() = runBlocking {
        val rows = mutableMapOf<String, TripEntity>()
        val dao = object : TripDao() {
            override fun save(entry: TripEntity) { rows[entry.id] = entry }
            override fun insertMissing(entry: TripEntity) = if (rows.putIfAbsent(entry.id, entry) == null) 1L else -1L
            override fun all() = rows.values.toList()
            override fun observe() = error("unused")
            override fun find(id: String) = rows[id]
            override fun delete(id: String) { rows.remove(id) }
        }
        val archive = TripArchive(store) { dao }
        finish()
        val first = archive.export().single()
        assertEquals(1000L, first.startedAtMs)
        assertFalse(first.estimatedStart)
        val removed = archive.remove(first).await()!!
        store.recover(store.load(), store.load(), SaveStamp(5000, 5000, "next"))
        assertTrue(archive.export().isEmpty())
        archive.undo(removed).await()
        assertTrue(first.sameFinish(archive.export().single()))
        archive.remove(first).await()
        store.transition(trip.copy(phase = TripPhase.RUNNING), SaveStamp(6000, 6000, "next"))
        store.transition(trip.copy(totalMs = 5000), SaveStamp(8000, 8000, "next"))
        val newer = archive.export().single()
        assertEquals(first.id, newer.id)
        assertEquals(first.startedAtMs, newer.startedAtMs)
        assertEquals(8000L, newer.endedAtMs)
        assertEquals(5000L, newer.totalMs)
        assertNull(archive.remove(first).await())
        archive.undo(removed).await()
        assertTrue(newer.sameFinish(archive.export().single()))
    }

    @Test fun finishReturnsAndPublishesWhileDatabaseIsBlockedOnAnotherThread() {
        finish()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val acknowledged = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val actualWriter = AtomicReference<Thread>()
        val dao = object : TripDao() {
            override fun save(entry: TripEntity) {
                actualWriter.set(Thread.currentThread())
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                finished.countDown()
            }
            override fun insertMissing(entry: TripEntity) = error("unexpected")
            override fun all() = emptyList<TripEntity>()
            override fun observe() = error("unexpected")
            override fun find(id: String) = error("unexpected")
            override fun delete(id: String) = error("unexpected")
        }
        val archive = TripArchive(store) { dao }
        // Kutsujan säie päättyy heti: mikään näkymän tai palvelun työntekijä ei jää odottamaan kantaa.
        val caller = Thread {
            archive.finish { saved -> if (saved) acknowledged.countDown() }
            returned.countDown()
        }
        caller.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(returned.await(1, TimeUnit.SECONDS))
            assertEquals(0, acknowledged.count)
            assertNotSame(caller, actualWriter.get())
            assertEquals(1, finished.count)
        } finally {
            release.countDown()
            caller.join(5000)
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        archive.export() // Odottaa saman kirjoittajan kuittauksen loppuun.
        assertTrue(store.pendingTrips().isEmpty())
    }
}
