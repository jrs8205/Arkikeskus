package org.jrs82.fsclock.mobile.gps

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarPauseRecoveryTest {
    @Test fun savedUserPauseOverTwelveHoursEndsAtPauseBeforeNewCarTripStarts() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AutoTripPrefs(app)
        prefs.enable(true)
        prefs.select("car", true)
        val store = TripStore.of(app)
        val pause = System.currentTimeMillis() - MAX_CAR_PAUSE_MS - 1000
        val snap = TripSnapshot(TripPhase.PAUSED, 90.0, 8000, 5000, 10f)
        store.transition(snap, SaveStamp(pause - 8000, 1, "previous"), newTrip = true)
        store.transition(snap, SaveStamp(pause, 8001, "previous"), timing = CarTiming(pausedWall = pause, userPaused = true))
        assertTrue(store.flush())
        TripTracker.init(app)
        TripTracker.carConnectionChanged(app, CarEvent.LOST)
        assertFalse(store.carTiming().waiting)
        assertEquals(CarEvent.FOUND, prefs.connection("car", true))
        assertTrue(AutoTripPrefs(app).currentConnection(prefs.generation))
        TripTracker.acceptCarStart(app, prefs.generation)
        assertEquals(TripPhase.RUNNING, TripTracker.state.value.phase)
        assertTrue(store.carTiming().startedAuto)
        val row = runBlocking(Dispatchers.IO) { TripTracker.archive.export().single() }
        assertEquals(pause, row.endedAtMs)
        assertEquals(90.0, row.distanceM, 0.0)
        assertFalse(row.startedAutomatically)
        assertTrue(row.endedAutomatically)
    }
}
