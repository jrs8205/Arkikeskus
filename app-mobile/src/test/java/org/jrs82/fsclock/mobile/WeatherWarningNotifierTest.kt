package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.WeatherWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4b-ilmoitus #2: [WeatherWarningNotifier] alue-/valintalogiikan yksikkötestit (puhdas, ei Androidia). */
class WeatherWarningNotifierTest {

    private fun warn(
        event: String,
        area: String,
        id: String = "id-$event-$area",
        marine: Boolean = false,
        level: WeatherWarning.Level = WeatherWarning.Level.YELLOW,
    ) = WeatherWarning(event, "kuvaus", area, 0L, Long.MAX_VALUE, level, id, marine)

    // --- areaMatchesHome: kuntanimi ---
    @Test fun homeNameInAreaMatches() {
        assertTrue(WeatherWarningNotifier.areaMatchesHome("Helsinki, Espoo, Vantaa, Kerava", "Vantaa", null))
    }

    @Test fun homeNotInAreaNoMatch() {
        assertFalse(WeatherWarningNotifier.areaMatchesHome("Kainuu, Kuusamo, Pudasjärvi", "Vantaa", "Uusimaa"))
    }

    @Test fun caseInsensitiveMatch() {
        assertTrue(WeatherWarningNotifier.areaMatchesHome("ROVANIEMI, Kemi", "rovaniemi", null))
    }

    // --- areaMatchesHome: maakunta (FMI antaa varoitukset maakunnittain) ---
    @Test fun regionNameMatches() {
        assertTrue(WeatherWarningNotifier.areaMatchesHome("Uusimaa, Päijät-Häme", "Vantaa", "Uusimaa"))
    }

    @Test fun regionSubAreaMatches() {
        // "Pirkanmaan eteläosa" osuu maakuntaan Pirkanmaa (genetiivi + sana-raja).
        assertTrue(WeatherWarningNotifier.areaMatchesHome("Pirkanmaan eteläosa", "Tampere", "Pirkanmaa"))
    }

    @Test fun regionSubstringDoesNotFalseMatch() {
        // Pohjanmaa EI saa osua Etelä-Pohjanmaahan.
        assertFalse(WeatherWarningNotifier.areaMatchesHome("Etelä-Pohjanmaa", "Vaasa", "Pohjanmaa"))
    }

    // --- selectNewWarnings ---
    @Test fun matchingRegionWarningNotified() {
        val w = warn("Tuulivaroitus maa-alueille", "Uusimaa")
        val r = WeatherWarningNotifier.selectNewWarnings(listOf(w), "Vantaa", "Uusimaa", emptySet())
        assertEquals(1, r.toNotify.size)
        assertTrue(r.newSeen.contains(WeatherWarningNotifier.warningKey(w)))
    }

    @Test fun marineWarningExcluded() {
        val w = warn("Huomautus veneilijöille", "Uusimaa", marine = true)
        val r = WeatherWarningNotifier.selectNewWarnings(listOf(w), "Vantaa", "Uusimaa", emptySet())
        assertTrue(r.toNotify.isEmpty())
    }

    @Test fun otherRegionExcluded() {
        val w = warn("Tuulivaroitus", "Lappi, Inari, Utsjoki")
        val r = WeatherWarningNotifier.selectNewWarnings(listOf(w), "Vantaa", "Uusimaa", emptySet())
        assertTrue(r.toNotify.isEmpty())
    }

    @Test fun alreadySeenNotRenotified() {
        val w = warn("Tuulivaroitus", "Uusimaa", id = "FI-2026-1")
        val r = WeatherWarningNotifier.selectNewWarnings(listOf(w), "Vantaa", "Uusimaa", setOf("FI-2026-1"))
        assertTrue(r.toNotify.isEmpty())
        assertTrue(r.newSeen.contains("FI-2026-1"))
    }

    @Test fun warningKeyUsesIdentifier() {
        assertEquals("FI-2026-9", WeatherWarningNotifier.warningKey(warn("X", "Uusimaa", id = "FI-2026-9")))
    }

    @Test fun duplicateWarningNotifiedOnce() {
        val a = warn("Tuulivaroitus", "Uusimaa", id = "FI-1")
        val b = warn("Tuulivaroitus", "Uusimaa", id = "FI-1")
        val r = WeatherWarningNotifier.selectNewWarnings(listOf(a, b), "Vantaa", "Uusimaa", emptySet())
        assertEquals(1, r.toNotify.size)
        assertEquals(1, r.newSeen.size)
    }

    // --- detectMarine: maakunta "Ahvenanmaa" EI saa leimata maavaroitusta merivaroitukseksi ---
    @Test fun alandLandNotMarine() {
        assertFalse(
            WeatherWarning.detectMarine(
                "Tuulivaroitus maa-alueille", "Ahvenanmaa, Varsinais-Suomi, Uusimaa", emptyList(),
            ),
        )
    }

    @Test fun alandSeaIsMarine() {
        assertTrue(WeatherWarning.detectMarine("Kova tuuli", "Ahvenanmeri", emptyList()))
    }

    @Test fun gulfOfFinlandIsMarine() {
        assertTrue(WeatherWarning.detectMarine("Huomautus veneilijöille", "Suomenlahden itäosa", emptyList()))
    }
}
