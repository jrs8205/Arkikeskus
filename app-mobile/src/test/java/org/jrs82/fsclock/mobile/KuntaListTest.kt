package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jatkolista #1: offline-kuntahaun suodatuslogiikka (prefix ensin, aksentti-/koko-riippumaton). */
class KuntaListTest {

    private val all = listOf(
        KuntaList.Kunta("Tampere", 61.5, 23.76),
        KuntaList.Kunta("Tammela", 60.8, 23.77),
        KuntaList.Kunta("Ähtäri", 62.55, 24.07),
        KuntaList.Kunta("Helsinki", 60.17, 24.94),
        KuntaList.Kunta("Hämeenlinna", 61.0, 24.46),
    )

    @Test fun prefixMatchesAlphabetical() {
        val r = KuntaList.filter(all, "tam", 10).map { it.name }
        assertEquals(listOf("Tammela", "Tampere"), r)
    }

    @Test fun diacriticInsensitive() {
        // "aht" (ilman aksenttia) löytää "Ähtäri" (Ä→a)
        assertEquals("Ähtäri", KuntaList.filter(all, "aht", 10).first().name)
        assertEquals("Ähtäri", KuntaList.filter(all, "äht", 10).first().name)
    }

    @Test fun caseInsensitiveAndUmlaut() {
        assertTrue(KuntaList.filter(all, "HÄME", 10).any { it.name == "Hämeenlinna" })
    }

    @Test fun emptyQueryReturnsEmpty() {
        assertTrue(KuntaList.filter(all, "   ", 10).isEmpty())
    }

    @Test fun limitApplied() {
        assertEquals(1, KuntaList.filter(all, "ta", 1).size)
    }
}
