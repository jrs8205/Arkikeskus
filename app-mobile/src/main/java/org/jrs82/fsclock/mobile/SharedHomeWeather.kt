package org.jrs82.fsclock.mobile

import kotlinx.coroutines.withContext
import org.jrs82.fsclock.WeatherData
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Etusivun sääkortin ja Sää-ennuste-sivun yhteinen FMI-tila: molemmat näyttävät saman haun lukuja.
 * Uusi haku tehdään vain uudessa päivityssukupolvessa ([newGeneration]: jokainen refreshTick ja jokainen uusi
 * näkymäistunto) tai paikan vaihtuessa; pelkkä sivun vaihto käyttää samaa dataa. Haku ja Open-Meteo-täydennys
 * tehdään aina yhdessä, joten tilaan ei päädy täydentämätöntä dataa, joka epäonnistuneen paluuhaun jälkeen
 * jäisi näkyviin.
 */
internal object SharedHomeWeather {

    private class Entry(val key: String, val data: WeatherData, val generation: Long)

    @Volatile private var entry: Entry? = null

    private val generations = AtomicLong()

    /** Prosessin sisällä yksilöllinen päivityssukupolvi. refreshTick alkaa nollasta jokaisessa uudessa
     *  Activityssä, joten pelkkä tick voisi osua edellisen Activityn kierrokseen ja kuitata vanhan datan. */
    fun newGeneration(): Long = generations.incrementAndGet()

    /** Paikka-avain, jolle data on haettu (null = tyhjä). */
    val key: String? get() = entry?.key

    /** Viimeisin data samalle paikka-avaimelle, muuten null. */
    fun seed(key: String): WeatherData? = entry?.takeIf { it.key == key }?.data

    fun clear() {
        entry = null
    }

    /**
     * Palauttaa saman sukupolven datan suoraan, muuten hakee ([fetch]) ja täydentää ([enrich]) [io]:ssa ja
     * julkaisee tuloksen. Edellinen data kelpaa haun siemeneksi. Epäonnistunut haku palauttaa null ja jättää
     * edellisen datan voimaan.
     */
    suspend fun load(
        key: String,
        generation: Long,
        forceNetwork: Boolean,
        io: CoroutineContext,
        fetch: (seed: WeatherData?, forceNetwork: Boolean) -> WeatherData?,
        enrich: (data: WeatherData, forceNetwork: Boolean) -> Unit,
    ): WeatherData? {
        val current = entry?.takeIf { it.key == key }
        if (current != null && current.generation == generation) return current.data
        val fresh = withContext(io) {
            try {
                fetch(current?.data, forceNetwork)?.also { enrich(it, forceNetwork) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } ?: return null
        entry = Entry(key, fresh, generation)
        return fresh
    }
}
