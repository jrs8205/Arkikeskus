package org.jrs82.fsclock.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.jrs82.fsclock.R
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Taustailmoitusten jaettu pohja (4b). Yksi tunnittainen WorkManager-työ ([NotificationsWorker]) ajaa
 * kaikki käytössä olevat ilmoitusmoduulit (nyt: [HslAlertNotifier] = HSL-häiriöt suosikeilla). Kukin
 * moduuli tarkistaa OMAN asetuskytkimensä → pois päältä olevat eivät tee mitään (työ on halpa ajaa).
 * Kaikki ilmoitukset ovat OPT-IN (oletus pois).
 *
 * Kanavat luodaan per ilmoitustyyppi → käyttäjä voi säätää/sammuttaa kunkin Androidin asetuksista
 * (sama periaate kuin lenkin km-ilmoituksessa, [WorkoutTrackingService]). Notif-ID:t 41–43 ovat lenkin
 * käytössä → tässä 44+.
 */
object Notifications {

    const val WORK_NAME = "arkikeskus_notifications"

    // Napautus → Häiriöt-sivu "Vain suosikit" -suodatettuna (näyttää vain suosikkien häiriöt).
    const val EXTRA_DISRUPTION_FAV = "open_disruption_fav"

    // HUOM: ID:t vaihdettu (notif_* → arki_*), koska tärkeys nostettiin DEFAULT→HIGH eikä Android
    // päivitä olemassa olevan kanavan tärkeyttä. Vanhat poistetaan [ensureChannels]issa.
    const val CHANNEL_HSL_ALERTS = "arki_hsl_alerts"
    const val NOTIF_ID_HSL = 44 // HSL-häiriöt (44; varaa 44–49 tälle ryhmälle)

    const val CHANNEL_WEATHER = "arki_weather_warnings"
    const val NOTIF_ID_WEATHER = 50 // Säävaroitukset (50; varaa 50–55 tälle ryhmälle)

    const val CHANNEL_ELECTRICITY = "arki_electricity"
    const val NOTIF_ID_ELECTRICITY = 56 // Pörssisähkö (56; varaa 56–59 tälle ryhmälle)

    const val CHANNEL_UPDATE = "arki_app_update"
    const val NOTIF_ID_UPDATE = 60 // Sovelluspäivitys (60)

    // Hiljaiset tunnit: ei push-ilmoituksia yöllä. Uusi häiriö jää "uudeksi" ja ilmoittaa klo 7 jälkeen.
    private const val QUIET_START_HOUR = 23
    private const val QUIET_END_HOUR = 7

    /** Luo ilmoituskanavat (idempotentti). Kaikki hälytyskanavat IMPORTANCE_HIGH (heads-up + ääni). */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Migraatio: poista vanhat DEFAULT-tärkeyden kanavat (tärkeys lukittuu luonnissa).
        nm.deleteNotificationChannel("notif_hsl_alerts")
        nm.deleteNotificationChannel("notif_weather_warnings")
        highChannel(nm, CHANNEL_HSL_ALERTS, "HSL-häiriöt",
            "Ilmoitus kun suosikkilinjalle tai -pysäkille tulee uusi häiriö")
        highChannel(nm, CHANNEL_WEATHER, "Säävaroitukset",
            "FMI:n säävaroitukset kotipaikkakunnallasi")
        highChannel(nm, CHANNEL_ELECTRICITY, "Pörssisähkö",
            "Ilmoitus kun huomisen pörssisähköhinnat saapuvat")
        highChannel(nm, CHANNEL_UPDATE, "Sovelluspäivitykset",
            "Ilmoitus kun Arkikeskuksesta on uusi versio saatavilla")
    }

    private fun highChannel(nm: NotificationManager, id: String, name: String, desc: String) {
        val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
        ch.description = desc
        nm.createNotificationChannel(ch)
    }

    /** Postaa ilmoituksen. Gate POST_NOTIFICATIONS (Android 13+) → ilman lupaa vaikenee siististi.
     *  [openSection] (HomeSection.name, esim. "HSL_DISRUPTIONS") avaa napautuksesta oikean näkymän. */
    fun post(
        context: Context,
        channelId: String,
        notifId: Int,
        title: String,
        text: String,
        openSection: String?,
        favoritesFocus: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels(context)
        val open = Intent(context, MobileComposeMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (openSection != null) {
            open.putExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION, openSection)
        }
        if (favoritesFocus) open.putExtra(EXTRA_DISRUPTION_FAV, true)
        val pi = PendingIntent.getActivity(
            context, notifId, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channelId)
            // Pieni ikoni = branded "A"-siluetti (Android renderöi vain alfan → tilapalkissa valkoinen).
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            // setColor tinttaa pienen ikonin + sovelluksen nimen brändisinisellä (kuten WhatsApp).
            .setColor(ContextCompat.getColor(context, R.color.mobile_accent))
            // Iso ikoni = värillinen Arkikeskus-logo ilmoituksen oikeassa reunassa.
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(notifId, n) } catch (e: Exception) { }
    }

    /** Ajastaa tunnittaisen taustatyön. KEEP = ei nollaa ajastusta joka käynnistyksellä. Kutsutaan
     *  [org.jrs82.fsclock.FsClockApp].onCreatesta — worker tarkistaa kytkimet, joten halpa pitää aina päällä. */
    @JvmStatic
    fun schedule(context: Context) {
        val work = PeriodicWorkRequestBuilder<NotificationsWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work,
        )
    }

    /** Aja tarkistus heti kerran (kun käyttäjä laittaa kytkimen päälle asetuksista). */
    fun runOnce(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<NotificationsWorker>().build(),
        )
    }

    /** Onko annettu tunti (0–23) hiljaisten tuntien sisällä (23–07). */
    fun isQuietHour(hourOfDay: Int): Boolean =
        hourOfDay >= QUIET_START_HOUR || hourOfDay < QUIET_END_HOUR
}

/** Tunnittainen taustatyö: ajaa käytössä olevat ilmoitusmoduulit. Palauttaa aina success
 *  (yksittäisen moduulin virhe ei kaada koko työtä → seuraava kierros yrittää uudelleen). */
class NotificationsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (Notifications.isQuietHour(hour)) return Result.success()
        try {
            HslAlertNotifier.check(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("Notifications", "HslAlertNotifier epäonnistui", e)
        }
        try {
            WeatherWarningNotifier.check(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("Notifications", "WeatherWarningNotifier epäonnistui", e)
        }
        try {
            ElectricityNotifier.check(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("Notifications", "ElectricityNotifier epäonnistui", e)
        }
        try {
            AppUpdateNotifier.check(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("Notifications", "AppUpdateNotifier epäonnistui", e)
        }
        return Result.success()
    }
}
