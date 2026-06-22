package org.jrs82.fsclock.mobile.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.LocationServices
import org.jrs82.fsclock.SettingsManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DEBUG-only sijaintimittaus. Kirjoittaa joka worker-ajolla rivin CSV:hen
 * (getExternalFilesDir/loc_measure.csv) last-known-sijaintien tuoreudesta ja
 * tarkkuudesta, jotta sää-/lähilähtö-widgetin sijaintiportin (ikä + tarkkuus) ja
 * lähteen (LocationManager GPS/NETWORK vs. Fused getLastLocation) voi valita
 * oikealla kenttädatalla eikä arvaamalla.
 *
 * EI tee aktiivista sijaintihakua — mittaa vain sen, mitä taustaworker voi ilman
 * ACCESS_BACKGROUND_LOCATIONia oikeasti saada (= cachen viimeisin tunnettu fix).
 * Tiedostoon kirjoittaminen (ei pelkkä logcat) varmistaa, että data säilyy kun
 * puhelin poistuu WiFi-ADB:n kantamasta (kauppareissu) ja se voidaan vetää
 * `adb pull`illa kun puhelin palaa verkkoon.
 */
internal object WidgetLocationMeasure {

    private const val FILE = "loc_measure.csv"
    // MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME on package-private (eri paketti) → literaali.
    private const val DISPLAY_KEY = "mobile_auto_location_display_name"
    private const val AUTO_LOC_KEY = "mobile_use_automatic_location"

    fun sample(ctx: Context) {
        val now = System.currentTimeMillis()
        val perm = hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPerm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val gps = if (perm) lastKnown(lm, LocationManager.GPS_PROVIDER) else null
        val net = if (perm) lastKnown(lm, LocationManager.NETWORK_PROVIDER) else null
        val fused = if (perm) fusedLast(ctx) else null

        // "Tuorein" kolmesta = sama valinta jota varsinainen korjaus käyttäisi.
        val newest = listOfNotNull(
            gps?.let { "GPS" to it },
            net?.let { "NET" to it },
            fused?.let { "FUSED" to it },
        ).maxByOrNull { it.second.time }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val home = try { SettingsManager.get().homePlace } catch (e: Exception) { "" }
        val disp = prefs.getString(DISPLAY_KEY, "") ?: ""
        val autoOn = prefs.getBoolean(AUTO_LOC_KEY, true)

        val wall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Europe/Helsinki")
        }.format(Date(now))

        val line = listOf(
            now.toString(), wall, perm.toString(), autoOn.toString(),
            ageSec(gps, now), accM(gps),
            ageSec(net, now), accM(net),
            ageSec(fused, now), accM(fused),
            newest?.first ?: "NONE",
            newest?.let { ageSec(it.second, now) } ?: "-1",
            newest?.let { accM(it.second) } ?: "-1",
            csv(home), csv(disp),
        ).joinToString(",")

        try {
            val dir = ctx.getExternalFilesDir(null) ?: return
            val f = File(dir, FILE)
            if (!f.exists()) {
                f.appendText(
                    "epochMs,wallHelsinki,perm,autoLoc," +
                        "gpsAgeSec,gpsAccM,netAgeSec,netAccM,fusedAgeSec,fusedAccM," +
                        "newestSrc,newestAgeSec,newestAccM,homePlace,displayName\n",
                )
            }
            f.appendText(line + "\n")
        } catch (e: Exception) { /* mittaus ei saa koskaan kaataa workeria */ }
    }

    private fun hasPerm(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun lastKnown(lm: LocationManager?, provider: String): Location? = try {
        lm?.getLastKnownLocation(provider)
    } catch (e: SecurityException) { null } catch (e: IllegalArgumentException) { null }

    /** Fused getLastLocation = passiivinen cachen luku (taustaturvallinen, ei throttlea). */
    private fun fusedLast(ctx: Context): Location? = try {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Location>(1)
        client.lastLocation
            .addOnSuccessListener { holder[0] = it; latch.countDown() }
            .addOnFailureListener { latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        holder[0]
    } catch (e: SecurityException) { null } catch (e: Exception) { null }

    private fun ageSec(loc: Location?, now: Long): String =
        if (loc == null) "-1" else ((now - loc.time) / 1000L).toString()

    private fun accM(loc: Location?): String =
        if (loc == null || !loc.hasAccuracy()) "-1" else loc.accuracy.toInt().toString()

    private fun csv(s: String): String = "\"" + s.replace("\"", "'") + "\""
}
