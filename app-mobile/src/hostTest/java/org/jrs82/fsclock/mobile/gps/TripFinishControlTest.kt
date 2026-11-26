package org.jrs82.fsclock.mobile.gps

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jrs82.fsclock.R
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.db.TripEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TripFinishControlTest {
    private fun withCommit(commit: (SharedPreferences.Editor) -> Boolean): Context {
        val app = RuntimeEnvironment.getApplication()
        return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val prefs = super.getSharedPreferences(name, mode)
                if (name != TripStore.FILE) return prefs
                return object : SharedPreferences by prefs {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = prefs.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun commit() = commit(editor)
                        }
                    }
                }
            }
        }
    }

    private fun awaitFinished() = runBlocking {
        withTimeout(5_000) { TripTracker.state.first { it.phase == TripPhase.FINISHED } }
    }

    @Test fun slowAndFailedCommitsReleaseControlsFromTheVisibleFinish() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val canWrite = AtomicBoolean(true)
        val context = withCommit { editor ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            canWrite.get() && editor.commit()
        }

        try {
            assertTrue(TripTracker.start(context))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            assertTrue(TripTracker.finish(context))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            assertFalse(TripTracker.acceptsCommands())
            assertEquals(TripPhase.RUNNING, TripTracker.state.value.phase)
        } finally {
            release.countDown()
        }
        awaitFinished()
        assertFalse(TripTracker.acceptsCommands())
        ShadowSystemClock.advanceBy(Duration.ofMillis(699))
        assertFalse(TripTracker.resume(context))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        assertTrue(TripTracker.acceptsCommands())
        assertTrue(TripTracker.resume(context))

        val store = TripStore.of(context)
        val pending: TripEntity
        try {
            canWrite.set(false)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            assertTrue(TripTracker.finish(context))
            awaitFinished()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(context.getString(R.string.gps_history_save_pending), ShadowToast.getTextOfLatestToast())
            assertFalse(TripTracker.acceptsCommands())
            ShadowSystemClock.advanceBy(Duration.ofMillis(700))
            assertTrue(TripTracker.reset(context))
            assertEquals(TripPhase.NONE, TripTracker.state.value.phase)
            pending = store.pendingTrips().single()
        } finally {
            canWrite.set(true)
        }
        // Vain havainnointi: testi ei pyydä arkistoa viemään tai tallentamaan jonoa.
        runBlocking(Dispatchers.IO) {
            val dao = FsClockDb.get(context).tripDao()
            withTimeout(10_000) {
                while (store.pendingTrips().isNotEmpty() || dao.all().none { pending.sameFinish(it) }) delay(20)
            }
            assertEquals(1, dao.all().count { it.id == pending.id })
            dao.remove(pending)
        }
    }
}
