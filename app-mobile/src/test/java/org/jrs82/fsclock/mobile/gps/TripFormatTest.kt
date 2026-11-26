package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Mittariston lukujen muodot (suunnitteluasiakirjan taulukko Muotoilu). */
class TripFormatTest {

    private val fi = Locale.forLanguageTag("fi-FI")
    private val sv = Locale.forLanguageTag("sv-FI")
    private val en = Locale.UK

    @Test fun matkanRajat() {
        assertEquals("0 m", TripFormat.distance(0.0, fi).toString())
        assertEquals("850 m", TripFormat.distance(850.0, fi).toString())
        assertEquals("990 m", TripFormat.distance(994.0, fi).toString())
        assertEquals("1,00 km", TripFormat.distance(995.0, fi).toString())
        assertEquals(Quantity("4,25", "km"), TripFormat.distance(4_250.0, fi))
        assertEquals("9,99 km", TripFormat.distance(9_994.0, fi).toString())
        assertEquals("10,0 km", TripFormat.distance(9_995.0, fi).toString())
        assertEquals("38,6 km", TripFormat.distance(38_600.0, sv).toString())
        assertEquals("38.6 km", TripFormat.distance(38_600.0, en).toString())
        assertEquals("999,9 km", TripFormat.distance(999_940.0, fi).toString())
        assertEquals("1000 km", TripFormat.distance(999_950.0, fi).toString())
    }

    @Test fun kelvotonMatkaOnNolla() {
        assertEquals("0 m", TripFormat.distance(-5.0, fi).toString())
        assertEquals("0 m", TripFormat.distance(Double.NaN, fi).toString())
    }

    @Test fun ajat() {
        assertEquals("00:00", TripFormat.clock(0))
        assertEquals("42:10", TripFormat.clock(2_530_000))
        assertEquals("59:59", TripFormat.clock(3_599_999))
        assertEquals("1:00:00", TripFormat.clock(3_600_000))
        assertEquals("1:42:10", TripFormat.clock(6_130_000))
        assertEquals("0 min", TripFormat.coarse(59_999))
        assertEquals("42 min", TripFormat.coarse(2_520_000))
        assertEquals("1 h 0 min", TripFormat.coarse(3_600_000))
        assertEquals("1 h 42 min", TripFormat.coarse(6_120_000))
    }

    @Test fun pitkatArvot() {
        assertEquals("100:00:00", TripFormat.clock(360_000_000))
        assertEquals("100 h 0 min", TripFormat.coarse(360_000_000))
        assertEquals("12346 km", TripFormat.distance(12_345_678.0, fi).toString())
        assertEquals("00:00", TripFormat.clock(-1))
    }

    @Test fun nopeudetJaTarkkuudet() {
        assertEquals("63 km/h", TripFormat.wholeKmh(17.5f).toString())
        assertEquals("– km/h", TripFormat.wholeKmh(null).toString())
        assertEquals("– km/h", TripFormat.wholeKmh(0f).toString())
        assertEquals("54,9 km/h", TripFormat.avgKmh(54.94, fi).toString())
        assertEquals("54.9 km/h", TripFormat.avgKmh(54.94, en).toString())
        assertEquals(Quantity("–", "km/h"), TripFormat.avgKmh(null, fi))
        assertEquals("±4 m", TripFormat.accuracy(4.4f))
        assertEquals("–", TripFormat.accuracy(null))
        assertEquals("±0,7 km/h", TripFormat.speedAccuracy(0.2f, fi))
        assertEquals("±0.7 km/h", TripFormat.speedAccuracy(0.2f, en))
        assertEquals("–", TripFormat.speedAccuracy(null, fi))
    }

    @Test fun ilmoitusrivi() {
        val run = TripSnapshot(TripPhase.RUNNING, 12_400.0, 2_520_000, 2_400_000, 20f)
        assertEquals("12,4 km · 42 min · 63 km/h", TripFormat.notificationLine(run, 63, fi))
        assertEquals("12.4 km · 42 min · 63 km/h", TripFormat.notificationLine(run, 63, en))
        assertEquals("12,4 km · 42 min · 0 km/h", TripFormat.notificationLine(run, 0, fi))
        // Ei signaalia: nopeutta ei näytetä.
        assertEquals("12,4 km · 42 min", TripFormat.notificationLine(run, null, fi))
        // Tauolla nopeutta ei näytetä, vaikka mittaus olisi tuore.
        assertEquals("12,4 km · 42 min", TripFormat.notificationLine(run.copy(phase = TripPhase.PAUSED), 63, fi))
    }

    @Test fun naytetytAjatTasmaavatSekunnilleen() {
        // Kokonaisaika 10,0 s ja liikeaika 4,5 s näkyvät 00:10 ja 00:04: pysähdysajan on oltava 00:06,
        // ei 5,5 s:sta pyöristetty 00:05, jotta kolme lukua täsmäävät näytöllä.
        assertEquals(6_000L, TripFormat.stoppedForDisplay(10_000, 4_500))
        assertEquals("00:06", TripFormat.clock(TripFormat.stoppedForDisplay(10_000, 4_500)))
        assertEquals(255_000L, TripFormat.stoppedForDisplay(2_520_000, 2_265_000))
        assertEquals(0L, TripFormat.stoppedForDisplay(5_000, 5_999))
    }
}
