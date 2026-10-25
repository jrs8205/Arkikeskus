package org.jrs82.fsclock.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jrs82.fsclock.WeatherData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Etusivun sääkortin ja Sää-ennusteen yhteinen FMI-tila (2.27.1). Katselmuskierrokset 2–3: ennustesivu korvasi
 *  etusivun Open-Meteolla täydentämän datan täydentämättömällä, ja uudelleenluodun Activityn refreshTick
 *  (alkaa taas nollasta) osui edellisen Activityn kierrokseen, jolloin vanha sää jäi näkyviin ilman hakua. */
class SharedHomeWeatherTest {

    private val key = "vantaa|60.275|24.838"
    private var fetches = 0
    private val fetchSeeds = ArrayList<WeatherData?>()
    private val fetchForces = ArrayList<Boolean>()
    private var nextFetch: () -> WeatherData? = { fmiWithoutHumidity() }

    @Before fun reset() {
        SharedHomeWeather.clear()
    }

    private fun fmiWithoutHumidity() = WeatherData().apply {
        fetchedAt = 1_000L + fetches
        current.temperature = 11.2
    }

    private fun load(
        generation: Long,
        forceNetwork: Boolean = true,
        k: String = key,
        humidityFromOpenMeteo: Double = 75.0,
    ) = runBlocking {
        SharedHomeWeather.load(
            k, generation, forceNetwork, Dispatchers.Unconfined,
            fetch = { seed, force ->
                fetches++
                fetchSeeds.add(seed)
                fetchForces.add(force)
                nextFetch()
            },
            enrich = { w, _ -> if (w.current.humidity.isNaN()) w.current.humidity = humidityFromOpenMeteo },
        )
    }

    @Test fun forecastVisitThenFailedHomeFetchKeepsEnrichedData() {
        load(SharedHomeWeather.newGeneration(), forceNetwork = false) // etusivu
        load(SharedHomeWeather.newGeneration(), humidityFromOpenMeteo = 80.0) // Sää-ennuste, Päivitä
        nextFetch = { null } // verkko katki
        assertNull(load(SharedHomeWeather.newGeneration())) // etusivun paluuhaku epäonnistuu
        assertEquals(80.0, SharedHomeWeather.seed(key)!!.current.humidity, 0.0)
    }

    @Test fun everyPublishedSnapshotIsEnriched() {
        load(SharedHomeWeather.newGeneration())
        assertEquals(75.0, SharedHomeWeather.seed(key)!!.current.humidity, 0.0)
        load(SharedHomeWeather.newGeneration(), humidityFromOpenMeteo = 60.0)
        assertEquals(60.0, SharedHomeWeather.seed(key)!!.current.humidity, 0.0)
    }

    @Test fun pageSwitchWithinTheSameGenerationShowsTheSameSnapshot() {
        val generation = SharedHomeWeather.newGeneration()
        val home = load(generation)
        val forecast = load(generation)
        assertSame(home, forecast)
        assertEquals(1, fetches)
    }

    @Test fun recreatedActivityGetsANewGenerationAndFetches() {
        // Vanha Activity: alku (tick 0) ja paluupäivitys (tick 1).
        load(SharedHomeWeather.newGeneration(), forceNetwork = false)
        val old = load(SharedHomeWeather.newGeneration())
        // Uusi Activity samassa prosessissa: tick alkaa nollasta, mutta sukupolvet ovat uusia.
        val recreatedStart = SharedHomeWeather.newGeneration()
        val recreatedResume = SharedHomeWeather.newGeneration()
        load(recreatedResume)
        assertEquals(3, fetches)
        assertSame(old, fetchSeeds[2]) // vanha data kelpaa siemeneksi, ei kuittaukseksi
        assertNotEquals(recreatedStart, recreatedResume)
    }

    @Test fun quickRecreateWithoutResumeRefreshFetchesOnce() {
        load(SharedHomeWeather.newGeneration(), forceNetwork = false)
        val recreated = SharedHomeWeather.newGeneration() // throttle ei kasvata tickiä → yksi sukupolvi
        load(recreated, forceNetwork = false)
        load(recreated, forceNetwork = false) // sivunvaihto uudessa istunnossa
        assertEquals(2, fetches)
    }

    @Test fun generationsAreUnique() {
        val seen = HashSet<Long>()
        repeat(1_000) { assertTrue(seen.add(SharedHomeWeather.newGeneration())) }
    }

    @Test fun forceNetworkIsPassedThroughWithSeed() {
        val first = load(SharedHomeWeather.newGeneration(), forceNetwork = false)
        load(SharedHomeWeather.newGeneration(), forceNetwork = true)
        assertEquals(listOf(false, true), fetchForces)
        assertSame(first, fetchSeeds[1])
    }

    @Test fun failedFetchIsRetriedInTheSameGeneration() {
        val generation = SharedHomeWeather.newGeneration()
        nextFetch = { null }
        assertNull(load(generation))
        nextFetch = { fmiWithoutHumidity() }
        assertTrue(load(generation) != null)
        assertEquals(2, fetches)
    }

    @Test fun fetchExceptionGivesNullAndKeepsPrevious() {
        val first = load(SharedHomeWeather.newGeneration())
        nextFetch = { throw java.io.IOException("FMI 503") }
        assertNull(load(SharedHomeWeather.newGeneration()))
        assertSame(first, SharedHomeWeather.seed(key))
    }

    @Test fun otherPlaceIsNotUsedAsSeed() {
        val generation = SharedHomeWeather.newGeneration()
        load(generation, k = "espoo|60.205|24.656")
        load(generation)
        assertNull(fetchSeeds[1])
        assertNull(SharedHomeWeather.seed("espoo|60.205|24.656"))
    }

    @Test fun clearForgetsSnapshot() {
        load(SharedHomeWeather.newGeneration())
        SharedHomeWeather.clear()
        assertNull(SharedHomeWeather.seed(key))
        assertNull(SharedHomeWeather.key)
    }
}
