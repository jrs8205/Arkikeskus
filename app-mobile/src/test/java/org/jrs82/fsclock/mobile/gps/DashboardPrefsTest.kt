package org.jrs82.fsclock.mobile.gps

import android.content.SharedPreferences
import org.jrs82.fsclock.mobile.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mittariston asetusten luku: varmuuskopiosta palautunut vääräntyyppinen arvo ei saa kaataa näkymää. */
class DashboardPrefsTest {

    @Test fun tallennetutArvotLuetaan() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString(GpsDashboardModel.KEY_VIEW, DashboardView.FULL.name)
            .putBoolean(GpsDashboardModel.KEY_KEEP_SCREEN_ON, true)
            .apply()
        assertEquals(DashboardView.FULL, DashboardPrefs.view(prefs))
        assertTrue(DashboardPrefs.keepScreenOn(prefs))
    }

    @Test fun puuttuvatArvotOvatOletuksia() {
        val prefs = FakeSharedPreferences()
        assertEquals(DashboardView.BASIC, DashboardPrefs.view(prefs))
        assertFalse(DashboardPrefs.keepScreenOn(prefs))
    }

    @Test fun vaaranTyyppinenArvoOnOletusEikaKaada() {
        // Oikea SharedPreferences heittää ClassCastExceptionin, kun avaimen alla on eri tyyppi.
        val backing = FakeSharedPreferences()
        val throwing = object : SharedPreferences by backing {
            override fun getString(key: String?, defValue: String?): String? = throw ClassCastException("Boolean")
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = throw ClassCastException("String")
        }
        assertEquals(DashboardView.BASIC, DashboardPrefs.view(throwing))
        assertFalse(DashboardPrefs.keepScreenOn(throwing))
    }
}
