package org.jrs82.fsclock.mobile

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Singleton: säilyttää koko Suomen lentodatan muistissa, hakee taustasäikeellä ja ilmoittaa
 *  kuuntelijoille. Sivu + etusivun kortti jakavat tämän → yksi verkkokutsu. Vrt. FmiWarningsRepository. */
object FlightsRepository {
    private const val TAG = "FlightsRepo"
    private const val REFRESH_MIN_INTERVAL_MS = 45_000L

    fun interface Listener { fun onFlightsChanged(data: FlightsData?) }

    private val io = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile private var latest: FlightsData? = null
    @Volatile private var lastFetchAt = 0L
    @Volatile private var inFlight = false

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) { listeners.add(l); l.onFlightsChanged(latest) }
    }
    fun removeListener(l: Listener) { listeners.remove(l) }
    fun getLatest(): FlightsData? = latest

    fun refreshIfStale() {
        if (inFlight) return
        if (lastFetchAt > 0L && System.currentTimeMillis() - lastFetchAt < REFRESH_MIN_INTERVAL_MS) return
        refreshNow()
    }

    fun refreshNow() {
        if (inFlight) return
        inFlight = true
        io.execute {
            try {
                val data = FlightsClient.fetch()
                if (data != null) {
                    latest = data
                    lastFetchAt = System.currentTimeMillis()
                    Log.d(TAG, "Refreshed: ${data.dep.size} dep / ${data.arr.size} arr")
                    for (l in listeners) try { l.onFlightsChanged(latest) } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed: $e")
            } finally {
                inFlight = false
            }
        }
    }
}
