package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * Laitekohtainen tila, joka ei saa siirtyä varmuuskopion mukana toiselle laitteelle: tiedosto on rajattu pois
 * Auto Backupista ja laitesiirrosta (backup_rules.xml, data_extraction_rules.xml), eikä [BackupManager] vie
 * sitä, koska se kopioi vain oletusasetukset. Esim. ajonaikaiset luvat eivät siirry laitteelta toiselle,
 * joten tieto niiden kysymisestäkään ei saa siirtyä.
 */
internal object DeviceLocalPrefs {
    const val FILE = "device_local_prefs"
    const val KEY_LOCATION_PERMISSION_ASKED = "initial_location_permission_asked"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun markLocationPermissionAsked(context: Context) {
        get(context).edit().putBoolean(KEY_LOCATION_PERMISSION_ASKED, true).apply()
    }
}
