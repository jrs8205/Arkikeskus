package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hintahaun lähdevalinta: ensisijainen lähde (Elering) ja varalähde, jota käytetään vain kun
 * ensisijainen epäonnistuu tai siitä puuttuu huominen julkaisuajan jälkeen.
 */
class ElectricityFetcherTest {

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private val start = ms("2026-11-16T00:00:00+02:00")
    private val end = ms("2026-11-18T00:00:00+02:00")
    private val afterPublish = ms("2026-11-16T15:00:00+02:00")
    private val beforePublish = ms("2026-11-16T13:00:00+02:00")

    private fun data(source: String, vararg days: Int): ElectricityData {
        val d = ElectricityData()
        d.source = source
        for (day in days) {
            for (hour in 0..23) {
                val ts = ms(String.format("2026-11-%02dT%02d:00:00+02:00", day, hour))
                d.quarters.add(ElectricityData.quarter(ts, hour.toDouble()))
            }
        }
        return d
    }

    private fun todayOnly(source: String = ElectricityData.SOURCE_ELERING) = data(source, 16)
    private fun withTomorrow(source: String) = data(source, 16, 17)

    private fun ElectricityData.isFromFallback() = source == ElectricityData.SOURCE_SPOT_HINTA
    private fun ElectricityData.hasTomorrow() = ElectricityData.hasFullTomorrow(quarters, now)

    private class Counting(private val answer: () -> ElectricityData) : ElectricityFetcher.Source {
        @Volatile var calls = 0
        override fun fetchRange(startMs: Long, endMs: Long): ElectricityData {
            calls++
            return answer()
        }
    }

    /** Seinäkello: päivämäärä ja julkaisuaika. */
    private var now = 0L
    /** Monotoninen kello: varalähteen pyyntöväli. */
    private var elapsed = 1_000_000L
    private fun fetcher(primary: Counting, fallback: Counting) =
        ElectricityFetcher(primary, fallback, { now }, { elapsed })

    @Test
    fun `ensisijainen riittaa kun huominen on mukana`() {
        now = afterPublish
        val elering = withTomorrow(ElectricityData.SOURCE_ELERING)
        val primary = Counting { elering }
        val fallback = Counting { withTomorrow(ElectricityData.SOURCE_SPOT_HINTA) }

        assertSame(elering, fetcher(primary, fallback).fetch(start, end))
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `ensisijaisen virheessa kaytetaan varalahdetta`() {
        now = beforePublish
        val primary = Counting { throw IOException("HTTP 503") }
        val fallback = Counting { todayOnly(ElectricityData.SOURCE_SPOT_HINTA) }

        val result = fetcher(primary, fallback).fetch(start, end)
        assertTrue(result.isFromFallback())
        assertEquals(24, result.quarters.size)
    }

    @Test
    fun `tyhja ensisijainen vastaus kasitellaan virheena`() {
        now = beforePublish
        val primary = Counting { ElectricityData() }
        val fallback = Counting { todayOnly(ElectricityData.SOURCE_SPOT_HINTA) }

        assertTrue(fetcher(primary, fallback).fetch(start, end).isFromFallback())
    }

    @Test
    fun `huomisen puuttuessa julkaisuajan jalkeen huominen haetaan varalahteesta`() {
        now = afterPublish
        val primary = Counting { todayOnly() }
        val fallback = Counting { withTomorrow(ElectricityData.SOURCE_SPOT_HINTA) }

        val result = fetcher(primary, fallback).fetch(start, end)
        assertTrue(result.isFromFallback())
        assertTrue(result.hasTomorrow())
        assertEquals(48, result.quarters.size)
    }

    @Test
    fun `ennen julkaisuaikaa huomisen puuttuminen ei kaynnista varalahdetta`() {
        now = beforePublish
        val elering = todayOnly()
        val primary = Counting { elering }
        val fallback = Counting { withTomorrow(ElectricityData.SOURCE_SPOT_HINTA) }

        assertSame(elering, fetcher(primary, fallback).fetch(start, end))
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `jos huominen puuttuu varalahteestakin pidetaan ensisijaisen data`() {
        now = afterPublish
        val elering = todayOnly()
        val primary = Counting { elering }
        val fallback = Counting { todayOnly(ElectricityData.SOURCE_SPOT_HINTA) }

        assertSame(elering, fetcher(primary, fallback).fetch(start, end))
    }

    @Test
    fun `varalahteen virhe ei havita ensisijaisen dataa`() {
        now = afterPublish
        val elering = todayOnly()
        val primary = Counting { elering }
        val fallback = Counting { throw IOException("HTTP 429") }

        assertSame(elering, fetcher(primary, fallback).fetch(start, end))
    }

    @Test
    fun `molempien epaonnistuessa heitetaan ensisijaisen virhe`() {
        now = afterPublish
        val primary = Counting { throw IOException("elering alhaalla") }
        val fallback = Counting { throw IOException("varalahde alhaalla") }

        val e = assertThrows(IOException::class.java) { fetcher(primary, fallback).fetch(start, end) }
        assertEquals("elering alhaalla", e.message)
    }

    @Test
    fun `varalahdetta kysytaan enintaan kerran kahdessa minuutissa`() {
        now = afterPublish
        val primary = Counting { todayOnly() }
        val fallback = Counting { todayOnly(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        f.fetch(start, end)
        elapsed += 60_000L
        f.fetch(start, end)
        assertEquals(1, fallback.calls)

        elapsed += 61_000L
        f.fetch(start, end)
        assertEquals(2, fallback.calls)
    }

    @Test
    fun `seinakellon siirto taaksepain ei pidenna pyyntovalia`() {
        now = afterPublish
        val primary = Counting { todayOnly() }
        val fallback = Counting { todayOnly(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        f.fetch(start, end)
        now -= 30 * 60_000L // kello korjataan puoli tuntia taaksepäin, yhä julkaisuajan jälkeen
        elapsed += 3 * 60_000L
        f.fetch(start, end)

        assertEquals(2, fallback.calls)
    }

    @Test
    fun `seinakellon siirto eteenpain ei ohita pyyntovalia`() {
        now = afterPublish
        val primary = Counting { todayOnly() }
        val fallback = Counting { todayOnly(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        f.fetch(start, end)
        now += 60 * 60_000L
        elapsed += 10_000L
        f.fetch(start, end)

        assertEquals(1, fallback.calls)
    }

    @Test
    fun `pyyntovalin aikana toinen kutsuja saa saman varalahteen vastauksen`() {
        now = afterPublish
        val primary = Counting { todayOnly() }
        val fallback = Counting { withTomorrow(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        f.fetch(start, end)
        elapsed += 30_000L
        val second = f.fetch(start, end)

        assertTrue(second.isFromFallback())
        assertTrue(second.hasTomorrow())
        assertEquals(48, second.quarters.size)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `eri aikavalia kysyva kutsuja saa oman valinsa samasta varalahteen vastauksesta`() {
        now = afterPublish
        val primary = Counting { todayOnly() }
        val fallback = Counting { withTomorrow(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        f.fetch(start, end) // esim. sähkösivu: koko tämä päivä + huominen
        elapsed += 30_000L
        val fromNow = f.fetch(afterPublish, end) // esim. ilmoitus: nykyhetkestä eteenpäin

        assertTrue(fromNow.hasTomorrow())
        assertEquals(afterPublish, fromNow.quarters.first().timestamp)
        assertEquals(9 + 24, fromNow.quarters.size)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `huomisen kattavaa varalahteen vastausta ei haeta uudelleen`() {
        now = afterPublish
        val primary = Counting { todayOnly() }
        val fallback = Counting { withTomorrow(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        f.fetch(start, end)
        elapsed += 60 * 60_000L
        val later = f.fetch(start, end)

        assertTrue(later.hasTomorrow())
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `uuden varalahdehaun epaonnistuessa kaytetaan aiempaa vastausta`() {
        now = afterPublish
        var fallbackFails = false
        val primary = Counting { throw IOException("elering alhaalla") }
        val fallback = Counting {
            if (fallbackFails) throw IOException("HTTP 429") else todayOnly(ElectricityData.SOURCE_SPOT_HINTA)
        }
        val f = fetcher(primary, fallback)

        f.fetch(start, end)
        fallbackFails = true
        elapsed += 5 * 60_000L
        val second = f.fetch(start, end)

        assertEquals(2, fallback.calls)
        assertTrue(second.isFromFallback())
        assertEquals(24, second.quarters.size)
    }

    @Test
    fun `pyyntovalin aikana ensisijaisen virhe korvataan aiemmalla varalahteen vastauksella`() {
        now = afterPublish
        var primaryFails = false
        val primary = Counting { if (primaryFails) throw IOException("elering alhaalla") else todayOnly() }
        val fallback = Counting { todayOnly(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        f.fetch(start, end)
        primaryFails = true
        elapsed += 30_000L
        val second = f.fetch(start, end)

        assertTrue(second.isFromFallback())
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `pyyntovalin aikana ilman aiempaa vastausta ensisijaisen virhe valittyy kutsujalle`() {
        now = afterPublish
        val primary = Counting { throw IOException("elering alhaalla") }
        val fallback = Counting { throw IOException("HTTP 429") }
        val f = fetcher(primary, fallback)

        assertThrows(IOException::class.java) { f.fetch(start, end) }
        elapsed += 30_000L
        val e = assertThrows(IOException::class.java) { f.fetch(start, end) }

        assertEquals("elering alhaalla", e.message)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `tuore vastaus kummasta tahansa lahteesta ei ole vanhentunut`() {
        now = afterPublish
        val fromPrimary = fetcher(Counting { todayOnly() }, Counting { throw IOException("HTTP 429") })
            .fetchResult(start, end)
        val fromFallback = fetcher(
            Counting { throw IOException("elering alhaalla") },
            Counting { todayOnly(ElectricityData.SOURCE_SPOT_HINTA) },
        ).fetchResult(start, end)

        assertFalse(fromPrimary.stale)
        assertFalse(fromFallback.stale)
    }

    @Test
    fun `molempien lahteiden epaonnistuessa sailytetty vastaus palautetaan vanhentuneeksi merkittyna`() {
        now = afterPublish
        var fallbackFails = false
        val primary = Counting { throw IOException("elering alhaalla") }
        val fallback = Counting {
            if (fallbackFails) throw IOException("HTTP 429") else todayOnly(ElectricityData.SOURCE_SPOT_HINTA)
        }
        val f = fetcher(primary, fallback)

        assertFalse(f.fetchResult(start, end).stale)
        fallbackFails = true
        elapsed += 5 * 60_000L
        val second = f.fetchResult(start, end)

        assertEquals(2, fallback.calls)
        assertTrue(second.stale)
        assertEquals(24, second.data.quarters.size)
    }

    @Test
    fun `pyyntovalin aikana ensisijaisen virheessa sailytetty vastaus on vanhentunut`() {
        now = afterPublish
        var primaryFails = false
        val primary = Counting { if (primaryFails) throw IOException("elering alhaalla") else todayOnly() }
        val fallback = Counting { todayOnly(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        f.fetchResult(start, end)
        primaryFails = true
        elapsed += 30_000L
        val second = f.fetchResult(start, end)

        assertEquals(1, fallback.calls)
        assertTrue(second.stale)
    }

    @Test
    fun `ensisijaisen onnistuessa sailytetyn huomisen kaytto ei ole virhe`() {
        now = afterPublish
        val primary = Counting { todayOnly() }
        val fallback = Counting { withTomorrow(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        f.fetchResult(start, end)
        elapsed += 30_000L
        val second = f.fetchResult(start, end)

        assertTrue(second.data.hasTomorrow())
        assertFalse(second.stale)
    }

    @Test
    fun `ennen julkaisuaikaa muistissa oleva varalahteen huominen sailyy kun ensisijainen palaa vajaana`() {
        now = ms("2026-11-16T14:25:00+02:00")
        var primaryFails = true
        val primary = Counting { if (primaryFails) throw IOException("elering alhaalla") else todayOnly() }
        val fallback = Counting { withTomorrow(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        assertTrue(f.fetch(start, end).hasTomorrow())
        primaryFails = false
        elapsed += 10_000L
        val second = f.fetch(start, end)

        assertTrue(second.hasTomorrow())
        assertTrue(second.isFromFallback())
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `ennen julkaisuaikaa muistissa oleva vajaa varalahteen vastaus ei kaynnista uutta pyyntoa`() {
        now = beforePublish
        var primaryFails = true
        val elering = todayOnly()
        val primary = Counting { if (primaryFails) throw IOException("elering alhaalla") else elering }
        val fallback = Counting { todayOnly(ElectricityData.SOURCE_SPOT_HINTA) }
        val f = fetcher(primary, fallback)

        f.fetch(start, end)
        primaryFails = false
        elapsed += 10 * 60_000L
        val second = f.fetch(start, end)

        assertSame(elering, second)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `ennen julkaisuaikaa rinnakkainen onnistunut ensisijainen haku odottaa kesken olevaa varalahdehakua`() {
        now = ms("2026-11-16T14:25:00+02:00")
        val insideFallback = CountDownLatch(1)
        val release = CountDownLatch(1)
        val primaryCalls = java.util.concurrent.atomic.AtomicInteger()
        val primary = Counting {
            if (primaryCalls.incrementAndGet() == 1) throw IOException("elering alhaalla") else todayOnly()
        }
        val fallback = Counting {
            insideFallback.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            withTomorrow(ElectricityData.SOURCE_SPOT_HINTA)
        }
        val f = fetcher(primary, fallback)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<ElectricityData> { f.fetch(start, end) }
            assertTrue(insideFallback.await(5, TimeUnit.SECONDS))
            val second = pool.submit<ElectricityData> { f.fetch(start, end) }
            Thread.sleep(200)
            assertFalse(second.isDone)
            release.countDown()

            assertTrue(first.get(5, TimeUnit.SECONDS).hasTomorrow())
            assertTrue(second.get(5, TimeUnit.SECONDS).hasTomorrow())
            assertEquals(1, fallback.calls)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `samanaikaiset kutsujat jakavat yhden varalahdehaun tuloksen`() {
        now = afterPublish
        val insideFallback = CountDownLatch(1)
        val release = CountDownLatch(1)
        val primary = Counting { todayOnly() }
        val fallback = Counting {
            insideFallback.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            withTomorrow(ElectricityData.SOURCE_SPOT_HINTA)
        }
        val f = fetcher(primary, fallback)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<ElectricityData> { f.fetch(start, end) }
            assertTrue(insideFallback.await(5, TimeUnit.SECONDS))
            val second = pool.submit<ElectricityData> { f.fetch(start, end) }
            // Toinen kutsuja ei saa valmistua vajaalla datalla ensimmäisen haun ollessa kesken.
            Thread.sleep(200)
            assertFalse(second.isDone)
            release.countDown()

            assertTrue(first.get(5, TimeUnit.SECONDS).hasTomorrow())
            assertTrue(second.get(5, TimeUnit.SECONDS).hasTomorrow())
            assertEquals(1, fallback.calls)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
