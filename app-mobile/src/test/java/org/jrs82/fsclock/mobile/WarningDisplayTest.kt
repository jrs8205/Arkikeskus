package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Säävaroitusten näyttöapurit (puhtaat, ei Composea/Androidia). */
class WarningDisplayTest {

    @Test fun severityFinnish() {
        assertEquals("Kohtalainen", severityFi("Moderate"))
        assertEquals("Erittäin vakava", severityFi("Extreme"))
        assertEquals("", severityFi(null))
        assertEquals("", severityFi("nonsense"))
    }

    @Test fun certaintyFinnish() {
        assertEquals("Todennäköinen", certaintyFi("Likely"))
        assertEquals("Mahdollinen", certaintyFi("possible"))
    }

    @Test fun urgencyFinnish() {
        assertEquals("Tuleva", urgencyFi("Future"))
        assertEquals("Välitön", urgencyFi("Immediate"))
    }

    @Test fun periodBothEnds() {
        // onset 2026-06-22 20:24 EEST, expires 2026-06-23 00:00 EEST → "–"-väli, molemmat ajat.
        val onset = 1_750_613_040_000L
        val expires = 1_750_626_000_000L
        val s = warningPeriod(onset, expires)
        assertTrue(s.contains("–"))
    }

    @Test fun periodOnlyExpires() {
        assertTrue(warningPeriod(0L, 1_750_626_000_000L).startsWith("voimassa asti "))
    }

    @Test fun periodOnlyOnset() {
        assertTrue(warningPeriod(1_750_613_040_000L, 0L).startsWith("alkaen "))
    }

    @Test fun periodEmptyWhenNoTimes() {
        assertEquals("", warningPeriod(0L, 0L))
    }
}
