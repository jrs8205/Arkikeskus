package org.jrs82.fsclock.mobile.gps

import android.content.SharedPreferences

/**
 * Mittariston kaksi asetusta oletusasetuksista. Luku on tyyppiturvallinen: varmuuskopiosta voi palautua
 * avain eri tyyppisenä, ja silloin käytetään oletusta eikä kaaduta joka avauksella.
 */
object DashboardPrefs {
    fun view(prefs: SharedPreferences): DashboardView = try {
        DashboardView.fromPref(prefs.getString(GpsDashboardModel.KEY_VIEW, null))
    } catch (e: ClassCastException) {
        DashboardView.BASIC
    }

    fun keepScreenOn(prefs: SharedPreferences): Boolean = try {
        prefs.getBoolean(GpsDashboardModel.KEY_KEEP_SCREEN_ON, false)
    } catch (e: ClassCastException) {
        false
    }
}
