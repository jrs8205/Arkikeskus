package org.jrs82.fsclock.mobile.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS-mittariston ainoa paikannuksen kuuntelija: GPS_PROVIDER sekunnin välein ja satelliittien tila
 * omassa säikeessään. Näkymä ja matkan taustapalvelu varaavat lähteen ([acquire]) ja vapauttavat sen
 * ([release]); kuuntelu on päällä vain, kun varaajia on, joten GPS ei jää päälle turhaan.
 *
 * Verkkopaikannusta ei kuunnella: siinä ei ole nopeutta. Lenkkiseuranta pitää oman kuuntelijansa.
 */
object GpsSource {

    private const val INTERVAL_MS = 1000L

    /** Lukema on sidottu rekisteröintikertaan: vapautetun rekisteröinnin kuuntelija ei voi enää muuttaa sitä. */
    private val slot = GpsReadingSlot()

    /** Viimeisin lukema. Tyhjä, kun lähde ei ole varattuna. */
    val reading: StateFlow<GpsReading> get() = slot.reading

    /** Jokainen mittaus lähteen säikeessä ennen [reading]-päivitystä. Matkan laskenta kuuntelee tätä. */
    @Volatile
    var onFix: ((GpsFix) -> Unit)? = null

    /** Yksi rekisteröintikerta: oma säie ja omat kuuntelijat, jotka tuntevat kertansa tunnisteen. */
    private class Registration(
        val manager: LocationManager,
        val thread: HandlerThread,
        val locationListener: LocationListener,
        val gnssCallback: GnssStatus.Callback,
    )

    private val owners = HashSet<Any>()
    private var registration: Registration? = null

    private fun locationListener(token: Long, lm: LocationManager) = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val fix = toFix(loc)
            // Vapautetun rekisteröinnin myöhässä tullut mittaus ei kuulu enää kenellekään. Tunnisteen tarkistus ja
            // toimitus laskennalle ovat yksi lukittu operaatio: kun vapautus on palannut, tämän kerran mittaus ei
            // voi enää kirjautua matkaan, vaikka kutsu olisi ollut jo käynnissä tässä säikeessä.
            if (!slot.deliver(token) { onFix?.invoke(fix) }) return
            // Mittaus tulee, vaikka tila sanoo paikannuksen olevan pois: tila tarkistetaan järjestelmältä, koska
            // päälle kytkemisestä ei aina tule ilmoitusta (esim. kytkentä kesken rekisteröinnin).
            val enabledNow = slot.reading.value.providerEnabled || isGpsEnabled(lm)
            slot.publish(token) { (if (enabledNow) it.withProvider(true) else it).withFix(fix) }
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) slot.publish(token) { it.withProvider(true) }
        }

        override fun onProviderDisabled(provider: String) {
            // Vanha nopeus ja satelliittiluvut eivät saa jäädä näkyviin "Sijainti on pois päältä" -tilan rinnalle.
            if (provider == LocationManager.GPS_PROVIDER) slot.publish(token) { it.withProvider(false) }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) { }
    }

    private fun gnssCallback(token: Long) = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val visible = status.satelliteCount
            var used = 0
            for (i in 0 until visible) if (status.usedInFix(i)) used++
            slot.publish(token) { it.withSatellites(used, visible) }
        }
    }

    /** Varaa lähteen. Sama [owner] kahdesti on yksi varaus. Ilman sijaintilupaa varaus jää odottamaan:
     *  rekisteröintiä yritetään uudelleen seuraavalla kutsulla. */
    @Synchronized
    fun acquire(context: Context, owner: Any) {
        owners.add(owner)
        register(context.applicationContext)
    }

    /** Vapauttaa varauksen. Viimeisen varaajan jälkeen kuuntelu loppuu ja lukema tyhjenee. */
    @Synchronized
    fun release(owner: Any) {
        if (!owners.remove(owner)) return
        if (owners.isEmpty()) unregister()
    }

    @SuppressLint("MissingPermission")
    private fun register(appContext: Context) {
        if (registration != null) return
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null || !hasGps(lm)) {
            // Laitteessa ei ole GPS-paikanninta: mittausta ei tule koskaan, joten tila ei saa jäädä odotukseksi.
            slot.close(GpsReading(providerPresent = false))
            return
        }
        // Lukema alustetaan ennen kuuntelijoiden rekisteröintiä: ensimmäinen mittaus voi tulla lähteen
        // säikeeseen jo rekisteröinnin aikana, eikä alustus saa pyyhkiä sitä.
        val token = slot.open(GpsReading(providerEnabled = isGpsEnabled(lm)))
        val listener = locationListener(token, lm)
        val t = HandlerThread("gps-dashboard")
        t.start()
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, t.looper)
        } catch (e: SecurityException) {
            t.quitSafely()
            slot.close()
            return
        } catch (e: IllegalArgumentException) {
            // Paikannin katosi kyselyn ja rekisteröinnin välissä tai järjestelmä ei tunne sitä.
            t.quitSafely()
            slot.close(GpsReading(providerPresent = false))
            return
        }
        val handler = Handler(t.looper)
        // Paikannus on voinut kytkeytyä päälle tai pois kyselyn ja rekisteröinnin välissä, eikä siitä tule
        // ilmoitusta jo rekisteröityneelle kuuntelijalle: tila sovitetaan uudelleen pyyhkimättä mittausta.
        // Tarkistus ajetaan kuuntelijan säikeessä, jossa myös päälle- ja poiskytkennän tapahtumat käsitellään:
        // kysely ja sen julkaisu eivät voi mennä uudemman tapahtuman ohi.
        handler.post {
            val enabled = isGpsEnabled(lm)
            slot.publish(token) { it.withProvider(enabled) }
        }
        val gnss = gnssCallback(token)
        try {
            lm.registerGnssStatusCallback({ r -> handler.post(r) }, gnss)
        } catch (e: SecurityException) {
        }
        registration = Registration(lm, t, listener, gnss)
    }

    /** Onko laitteessa GPS-paikannin. Kyselyn epäonnistuessa oletetaan, että on: rekisteröinti ratkaisee. */
    private fun hasGps(lm: LocationManager): Boolean = try {
        lm.allProviders.contains(LocationManager.GPS_PROVIDER)
    } catch (e: Exception) {
        true
    }

    private fun isGpsEnabled(lm: LocationManager): Boolean = try {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (e: Exception) {
        true
    }

    private fun unregister() {
        // Kerta päätetään ensin: tästä eteenpäin vanhan kuuntelijan kesken oleva kutsu ei enää julkaise mitään,
        // vaikka sen säie ehtisi vielä jatkaa (quitSafely ei keskeytä jo suoritettavaa kutsua).
        slot.close()
        val r = registration ?: return
        registration = null
        try {
            r.manager.removeUpdates(r.locationListener)
        } catch (e: Exception) {
        }
        try {
            r.manager.unregisterGnssStatusCallback(r.gnssCallback)
        } catch (e: Exception) {
        }
        r.thread.quitSafely()
    }

    /** Mittauksen aika otetaan laitteen käynnistyksestä kuluneesta ajasta, ei seinäkellosta. */
    private fun toFix(loc: Location): GpsFix = GpsFix(
        rtMs = loc.elapsedRealtimeNanos / 1_000_000L,
        lat = loc.latitude,
        lon = loc.longitude,
        speedMps = if (loc.hasSpeed()) loc.speed else null,
        accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
        speedAccuracyMps = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null,
    ).clean()
}
