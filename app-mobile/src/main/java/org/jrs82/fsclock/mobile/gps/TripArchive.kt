package org.jrs82.fsclock.mobile.gps

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jrs82.fsclock.db.TripDao
import org.jrs82.fsclock.db.TripEntity
import java.io.IOException

/** Sama lukko kaikille historian kirjoittajille. Ohjaimen pääsäie ei koskaan ota tätä lukkoa. */
internal class TripHistoryWriter(private val store: TripStore, private val upsert: (TripEntity) -> Unit) {
    @Synchronized
    fun <T> afterWrites(action: () -> T): T {
        val pending = store.pendingTrips()
        if (pending.isNotEmpty()) {
            // Erityisesti vanhan tallenteen uusi tunniste on oltava levyllä ENNEN ensimmäistä inserttiä.
            requireDurable()
            for (entry in pending) {
                upsert(entry)
                store.acknowledge(entry)
            }
        }
        // Myös aiemman epäonnistuneen kuittauksen on oltava levyllä ennen mahdollista poistoa.
        requireDurable()
        return action()
    }

    fun requireDurable() {
        if (!store.flush()) throw IOException("Trip state could not be saved")
    }
}

internal class TripArchive(private val store: TripStore, private val dao: () -> TripDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = TripHistoryWriter(store) { dao().save(it) }

    // Varsinaiset lopetukset ovat TripStoressa. Yksi heräte riittää myös usealle lopetukselle levyvirheen aikana.
    private val writes = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in writes) retryWrite { writer.afterWrites {} }
        }
    }

    fun finish(onFirstWrite: (Boolean) -> Unit) {
        scope.launch {
            onFirstWrite(store.flush())
            writes.trySend(Unit)
        }
    }

    fun recover() {
        scope.launch {
            if (store.pendingTrips().isNotEmpty()) writes.trySend(Unit)
        }
    }

    private suspend fun retryWrite(write: () -> Unit) {
        var waitMs = 1_000L
        while (true) {
            try {
                write()
                return
            } catch (e: IOException) {
                Log.w("TripHistory", "Trip state write will be retried", e)
            } catch (e: android.database.sqlite.SQLiteException) {
                Log.w("TripHistory", "Trip database write will be retried", e)
            }
            delay(waitMs)
            waitMs = (waitMs * 2).coerceAtMost(60_000L)
        }
    }

    /** Varmuuskopio kutsuu omassa IO-säikeessään. */
    fun export(): List<TripEntity> = writer.afterWrites { dao().all() }

    // Käyttäjän jo antama poisto/kumoaminen valmistuu, vaikka näkymä suljettaisiin heti.
    fun remove(entry: TripEntity) = scope.async { writer.afterWrites { dao().remove(entry) } }
    fun undo(entry: TripEntity) = scope.async { writer.afterWrites { dao().insertMissing(entry) } }
}
