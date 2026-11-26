package org.jrs82.fsclock.mobile.gps

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Test

/** Palvelun käynnistys kutsujan puolelta: järjestelmän esto ei saa kaataa sovellusta. */
class TripServiceStartTest {

    @Test fun kaynnistyksenEstoPalauttaaEpatodenEikaKaadaKutsujaa() {
        // Android 12+ voi kieltää etualapalvelun käynnistyksen jo kutsussa (IllegalStateExceptionin aliluokka).
        val blocked = object : ContextWrapper(null) {
            override fun startService(service: Intent?): ComponentName? =
                throw IllegalStateException("startForegroundService not allowed")

            override fun startForegroundService(service: Intent?): ComponentName? =
                throw IllegalStateException("startForegroundService not allowed")
        }
        assertFalse(TripTrackingService.start(blocked, TripTrackingService.ACTION_START))
    }

    @Test fun luvanPuuttuminenPalauttaaEpatoden() {
        val denied = object : ContextWrapper(null) {
            override fun startService(service: Intent?): ComponentName? = throw SecurityException("denied")
            override fun startForegroundService(service: Intent?): ComponentName? = throw SecurityException("denied")
        }
        assertFalse(TripTrackingService.start(denied, TripTrackingService.ACTION_RESUME))
    }
}
