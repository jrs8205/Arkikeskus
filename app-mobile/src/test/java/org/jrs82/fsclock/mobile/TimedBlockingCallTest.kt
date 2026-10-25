package org.jrs82.fsclock.mobile

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.locks.LockSupport

/** Android 11:n synkroninen Geocoder-kutsu (Binder) ei reagoi keskeytykseen: aikarajan on vapautettava odottaja
 *  silti, eivätkä toistuvat Päivitä-painallukset saa kasata roikkuvia kutsuja (katselmus 2.27.1, A2). */
class TimedBlockingCallTest {

    @Volatile private var released = false

    @After fun release() {
        released = true
    }

    /** Kuin roikkuva Binder-kutsu: keskeytys ei katkaise odotusta, vain [released] (tai 3 s:n turvaraja, jottei
     *  rikkinäinen toteutus jumita testiajoa vaan kaatuu aikamittaukseen). */
    private fun hangUntilReleased(): String {
        val cap = System.nanoTime() + 3_000_000_000L
        while (!released && System.nanoTime() < cap) LockSupport.parkNanos(1_000_000L)
        return "late"
    }

    private fun awaitIdle(call: TimedBlockingCall) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (call.isBusy && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse("kutsu ei vapautunut", call.isBusy)
    }

    private inline fun <T> timedMs(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - start) / 1_000_000L
    }

    @Test fun timeoutReleasesWaiterEvenWhenBlockIgnoresInterrupt() {
        val call = TimedBlockingCall("test-hang")
        val (result, elapsedMs) = timedMs { runBlocking { call.runWithTimeout(100L) { hangUntilReleased() } } }
        assertNull(result)
        assertTrue("odottaja palasi vasta $elapsedMs ms:n jälkeen", elapsedMs < 1_000L)
        assertTrue(call.isBusy)
    }

    @Test fun secondCallWhileFirstHangsReturnsNullAtOnce() {
        val call = TimedBlockingCall("test-busy")
        runBlocking { call.runWithTimeout(100L) { hangUntilReleased() } }
        var secondRan = false
        val (result, elapsedMs) = timedMs { runBlocking { call.runWithTimeout(5_000L) { secondRan = true; "x" } } }
        assertNull(result)
        assertFalse(secondRan)
        assertTrue("toinen kutsu odotti $elapsedMs ms", elapsedMs < 1_000L)
    }

    @Test fun callWorksAgainAfterHangingBlockFinishes() {
        val call = TimedBlockingCall("test-recover")
        runBlocking { call.runWithTimeout(100L) { hangUntilReleased() } }
        released = true
        awaitIdle(call)
        assertEquals("ok", runBlocking { call.runWithTimeout(1_000L) { "ok" } })
    }

    @Test fun resultWithinTimeoutIsReturned() {
        val call = TimedBlockingCall("test-fast")
        assertEquals("Vantaa", runBlocking { call.runWithTimeout(1_000L) { "Vantaa" } })
        assertNull(runBlocking { call.runWithTimeout<String>(1_000L) { null } })
        assertFalse(call.isBusy)
    }

    @Test fun exceptionIsRethrownAndFreesTheCall() {
        val call = TimedBlockingCall("test-error")
        assertThrows(IOException::class.java) {
            runBlocking { call.runWithTimeout(1_000L) { throw IOException("grpc failed") } }
        }
        assertFalse(call.isBusy)
        assertEquals("ok", runBlocking { call.runWithTimeout(1_000L) { "ok" } })
    }
}
