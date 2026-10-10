package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.FinnishHolidays
import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HolidayNamesTest {

    @Test fun everyFallbackName_hasTranslation() {
        // Kaksi vuotta kattaa kaikki liikkuvat ja kiinteät päivät (pääsiäinen, juhannus, pyhäinpäivä …).
        val from = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"))
        from.clear()
        from.set(2026, Calendar.JANUARY, 1)
        val names = FinnishHolidays.upcoming(from, 200).map { it.name }.toSet()
        assertTrue(names.size >= 30)
        for (name in names) {
            assertNotNull("Käännös puuttuu: $name", HolidayNames.resId(name))
        }
    }

    @Test fun apiNames_haveTranslations() {
        // vapaapaivat.com-nimet, joita fallback ei tuota (aatot).
        assertEquals(R.string.holiday_may_day_eve, HolidayNames.resId("Vappuaatto"))
        assertEquals(R.string.holiday_new_years_eve, HolidayNames.resId("Uudenvuodenaatto"))
        assertEquals(R.string.holiday_independence, HolidayNames.resId("Itsenäisyyspäivä"))
    }

    @Test fun lookup_ignoresCaseAndSpacing() {
        assertEquals(R.string.holiday_snellman, HolidayNames.resId("J. V. Snellmanin päivä, suomalaisuuden päivä"))
        assertEquals(R.string.holiday_christmas_eve, HolidayNames.resId("  jouluaatto "))
    }

    @Test fun unknownName_hasNoResource() {
        assertNull(HolidayNames.resId("Tuntematon juhla"))
    }

    @Test fun display_prefersResource_thenSourceTranslation_thenFinnish() {
        val resolve = { id: Int -> "res:$id" }
        assertEquals(
            "res:${R.string.holiday_epiphany}",
            HolidayNames.display("Loppiainen", "sv", mapOf("sv" to "Trettondagen"), resolve),
        )
        assertEquals("Ny dag", HolidayNames.display("Uusi päivä", "sv", mapOf("sv" to "Ny dag"), resolve))
        assertEquals("Uusi päivä", HolidayNames.display("Uusi päivä", "en", mapOf("sv" to "Ny dag"), resolve))
        assertEquals("Uusi päivä", HolidayNames.display("Uusi päivä", "en", mapOf("en" to " "), resolve))
    }
}
