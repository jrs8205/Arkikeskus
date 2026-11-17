package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone

/** Sähkövaraston hakutila: näkymä erottaa sen avulla "ei vielä julkaistu" ja "haku epäonnistui". */
class ElectricityRepositoryTest {

    private class Scripted : ElectricityFetcher.Source {
        var next: () -> ElectricityData = { ElectricityData() }
        var calls = 0
        override fun fetchRange(startMs: Long, endMs: Long): ElectricityData {
            calls++
            return next()
        }
    }

    private val helsinki = TimeZone.getTimeZone("Europe/Helsinki")

    /** Kuluvan päivän (Suomen aikaa) kellonaika millisekunteina; varasto laskee päivänsä oikeasta kellosta. */
    private fun todayAt(hour: Int, dayOffset: Int = 0): Long {
        val c = Calendar.getInstance(helsinki)
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun hourly(source: String, days: Int): ElectricityData {
        val d = ElectricityData()
        d.source = source
        for (day in 0 until days) for (hour in listOf(0, 6, 12, 18, 21)) {
            d.quarters.add(ElectricityData.quarter(todayAt(hour, day), hour.toDouble()))
        }
        return d
    }

    private val primary = Scripted()
    private val fallback = Scripted().also { it.next = { throw IOException("varalahde alhaalla") } }
    private var wallClock: () -> Long = { System.currentTimeMillis() }
    private var elapsed = 1_000_000L
    private val fetcher = ElectricityFetcher(primary, fallback, { wallClock() }, { elapsed })
    private val repo = ElectricityRepository(fetcher)

    @Test
    fun `uusi varasto ei ole virhetilassa`() {
        assertFalse(repo.lastFetchFailed())
    }

    @Test
    fun `epaonnistunut haku merkitaan ja aiempi data sailyy`() {
        val first = hourly(ElectricityData.SOURCE_ELERING, days = 1)
        primary.next = { first }
        repo.fetchNow()

        primary.next = { throw IOException("HTTP 500") }
        assertThrows(IOException::class.java) { repo.fetchNow() }

        assertTrue(repo.lastFetchFailed())
        assertSame(first, repo.peek())
    }

    @Test
    fun `onnistunut haku poistaa virhetilan`() {
        primary.next = { throw IOException("HTTP 500") }
        assertThrows(IOException::class.java) { repo.fetchNow() }

        val fresh = hourly(ElectricityData.SOURCE_ELERING, days = 1)
        primary.next = { fresh }
        assertSame(fresh, repo.fetchNow())

        assertFalse(repo.lastFetchFailed())
    }

    @Test
    fun `perakkaiset haut eivat korvaa varalahteen huomista vajaalla ensisijaisella datalla`() {
        wallClock = { todayAt(15) } // julkaisuajan jälkeen
        primary.next = { hourly(ElectricityData.SOURCE_ELERING, days = 1) }
        fallback.next = { hourly(ElectricityData.SOURCE_SPOT_HINTA, days = 2) }

        repo.fetchNow()
        elapsed += 20_000L // toinen kutsuja (esim. sähkösivu ilmoitustyön jälkeen) pyyntövälin sisällä
        repo.fetchNow()

        assertEquals(10, repo.peek().quarters.size)
        assertEquals(ElectricityData.SOURCE_SPOT_HINTA, repo.peek().source)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `ennen julkaisuaikaa palaava vajaa ensisijainen data ei pudota varalahteen huomista`() {
        wallClock = { todayAt(14) + 25 * 60_000L } // klo 14.25, ennen julkaisuaikaa
        primary.next = { throw IOException("elering alhaalla") }
        fallback.next = { hourly(ElectricityData.SOURCE_SPOT_HINTA, days = 2) }

        repo.fetchNow()
        assertTrue(repo.hasFullTomorrow())

        primary.next = { hourly(ElectricityData.SOURCE_ELERING, days = 1) }
        elapsed += 5_000L
        repo.fetchNow()

        assertTrue(repo.hasFullTomorrow())
        assertEquals(10, repo.peek().quarters.size)
    }

    @Test
    fun `jo saatu huominen ei katoa vaikka myohempi haku palauttaisi vain taman paivan`() {
        wallClock = { todayAt(16) }
        val full = hourly(ElectricityData.SOURCE_ELERING, days = 2)
        primary.next = { full }
        repo.fetchNow()

        primary.next = { hourly(ElectricityData.SOURCE_ELERING, days = 1) } // lähde palauttaa hetkellisesti vajaan vastauksen
        val returned = repo.fetchNow()

        assertSame(full, returned)
        assertSame(full, repo.peek())
        assertTrue(repo.hasFullTomorrow())
        assertFalse(repo.lastFetchFailed())
    }

    @Test
    fun `molempien lahteiden epaonnistuessa hinnat sailyvat mutta haku merkitaan epaonnistuneeksi`() {
        // Katselmoinnin tapaus: varalähteen muistissa vain tämä päivä, sitten klo 14.30 jälkeen molemmat kaatuvat.
        wallClock = { todayAt(15) }
        primary.next = { throw IOException("elering alhaalla") }
        fallback.next = { hourly(ElectricityData.SOURCE_SPOT_HINTA, days = 1) }
        repo.fetchNow()
        assertFalse(repo.lastFetchFailed())

        fallback.next = { throw IOException("HTTP 429") }
        elapsed += 5 * 60_000L
        repo.fetchNow()

        assertTrue(repo.lastFetchFailed())
        assertEquals(5, repo.peek().quarters.size)
        assertFalse(repo.hasFullTomorrow())
    }

    @Test
    fun `vanhentunut tulos ei merkitse dataa tuoreeksi`() {
        wallClock = { todayAt(15) }
        // Toinen kutsuja (esim. ilmoitustyö) on jo hakenut varalähteestä; varastossa ei ole vielä mitään.
        primary.next = { throw IOException("elering alhaalla") }
        fallback.next = { hourly(ElectricityData.SOURCE_SPOT_HINTA, days = 1) }
        fetcher.fetch(todayAt(0), todayAt(0, dayOffset = 2))
        fallback.next = { throw IOException("HTTP 429") }
        elapsed += 5 * 60_000L

        repo.fetchNow() // saa vain säilytetyn vastauksen
        assertTrue(repo.lastFetchFailed())
        val callsBefore = primary.calls
        repo.fetchIfStale()

        assertEquals(callsBefore + 1, primary.calls)
    }

    @Test
    fun `vanhentunut tulos ei korvaa varaston omaa dataa`() {
        wallClock = { todayAt(15) }
        val own = hourly(ElectricityData.SOURCE_ELERING, days = 1)
        primary.next = { own }
        fallback.next = { hourly(ElectricityData.SOURCE_SPOT_HINTA, days = 1) }
        repo.fetchNow() // Elering onnistuu; varalähteen vastaus jää fetcherin muistiin

        primary.next = { throw IOException("elering alhaalla") }
        fallback.next = { throw IOException("HTTP 429") }
        elapsed += 5 * 60_000L
        repo.fetchNow()

        assertSame(own, repo.peek())
        assertTrue(repo.lastFetchFailed())
    }

    @Test
    fun `tuoreempi data korvaa aiemman kun siina on huominen`() {
        wallClock = { todayAt(16) }
        primary.next = { hourly(ElectricityData.SOURCE_ELERING, days = 2) }
        repo.fetchNow()

        val newer = hourly(ElectricityData.SOURCE_ELERING, days = 2)
        primary.next = { newer }
        repo.fetchNow()

        assertSame(newer, repo.peek())
    }

    @Test
    fun `sailytetty taydempi vastaus otetaan kayttoon vaikka varastossa on jo taman paivan hinnat`() {
        // Katselmoinnin tapaus: varastossa vain tämä päivä, ilmoitushaku saa huomisen fetcherin
        // muistiin, ja sen jälkeen Elering lakkaa vastaamasta.
        wallClock = { todayAt(15) }
        primary.next = { hourly(ElectricityData.SOURCE_ELERING, days = 1) }
        fallback.next = { hourly(ElectricityData.SOURCE_SPOT_HINTA, days = 1) }
        repo.fetchNow()
        assertFalse(repo.hasFullTomorrow())

        elapsed += 5 * 60_000L
        fallback.next = { hourly(ElectricityData.SOURCE_SPOT_HINTA, days = 2) }
        fetcher.fetch(todayAt(15), todayAt(0, dayOffset = 2)) // ilmoitustyön haku: huominen varalähteestä

        primary.next = { throw IOException("elering alhaalla") }
        fallback.next = { throw IOException("HTTP 429") }
        repo.fetchNow()

        assertTrue(repo.hasFullTomorrow())
        assertEquals(10, repo.peek().quarters.size)
        assertTrue(repo.lastFetchFailed()) // hakuvirhe näkyy edelleen erillään hinnoista
    }

    @Test
    fun `epaonnistuneen haun jalkeen seuraava paivitys yrittaa uudelleen vaikka data on tuoretta`() {
        // Virheteksti kehottaa painamaan Päivitä: sen on silloin oikeasti haettava, vaikka edellinen
        // onnistunut haku olisi alle välimuistin iän takana.
        primary.next = { hourly(ElectricityData.SOURCE_ELERING, days = 1) }
        repo.fetchNow()
        primary.next = { throw IOException("HTTP 500") }
        assertThrows(IOException::class.java) { repo.fetchNow() }
        assertTrue(repo.lastFetchFailed())

        primary.next = { hourly(ElectricityData.SOURCE_ELERING, days = 1) }
        val callsBefore = primary.calls
        repo.fetchIfStale()

        assertEquals(callsBefore + 1, primary.calls)
        assertFalse(repo.lastFetchFailed())
    }

    @Test
    fun `onnistuneen haun jalkeen tuore data palautetaan hakematta`() {
        primary.next = { hourly(ElectricityData.SOURCE_ELERING, days = 1) }
        repo.fetchNow()
        val callsBefore = primary.calls

        repo.fetchIfStale()

        assertEquals(callsBefore, primary.calls)
    }

    @Test
    fun `sailytetty vastaus joka ei kata enempaa ei korvaa varaston dataa`() {
        wallClock = { todayAt(15) }
        val own = hourly(ElectricityData.SOURCE_ELERING, days = 2)
        primary.next = { own }
        repo.fetchNow()

        // Fetcherin muistiin jää varalähteen vastaus samalta jaksolta (toisen kutsujan haku).
        primary.next = { throw IOException("elering alhaalla") }
        fallback.next = { hourly(ElectricityData.SOURCE_SPOT_HINTA, days = 2) }
        fetcher.fetch(todayAt(0), todayAt(0, dayOffset = 2))
        fallback.next = { throw IOException("HTTP 429") }
        repo.fetchNow()

        assertSame(own, repo.peek())
        assertTrue(repo.lastFetchFailed())
    }

    @Test
    fun `vajaa data korvaa aiemman kun aiemmassakaan ei ole huomista`() {
        primary.next = { hourly(ElectricityData.SOURCE_ELERING, days = 1) }
        repo.fetchNow()

        val newer = hourly(ElectricityData.SOURCE_ELERING, days = 1)
        primary.next = { newer }
        repo.fetchNow()

        assertSame(newer, repo.peek())
    }
}
