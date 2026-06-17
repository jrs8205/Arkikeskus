package org.jrs82.fsclock.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 4b-ilmoitus #4: kertamuistutus tietystä joukkoliikennelähdöstä. Käyttäjä valitsee lähdön
 * (Lähilähdöt → pitkä painallus rivistä → "Muistuta") ja lead-ajan (5/10/15 min); AlarmManager
 * herättää tarkasti ja [DepartureReminderReceiver] näyttää ilmoituksen. Asetetaan joka kerta erikseen.
 */
object DepartureReminder {

    /** Hälytyksen laukaisuhetki (ms epoch): leadMin ennen lähtöä. */
    fun triggerMs(departureEpochSec: Long, leadMin: Int): Long =
        departureEpochSec * 1000L - leadMin * 60_000L

    /** Ajasta muistutus. False jos lähtö on jo liian lähellä (lead-aika menneisyydessä). */
    fun schedule(
        context: Context,
        routeShortName: String,
        headsign: String,
        stopName: String,
        departureEpochSec: Long,
        reqKey: String,
        leadMin: Int,
    ): Boolean {
        val trigger = triggerMs(departureEpochSec, leadMin)
        if (trigger <= System.currentTimeMillis() + 5_000L) return false
        val notifId = reqKey.hashCode()
        val intent = Intent(context, DepartureReminderReceiver::class.java).apply {
            putExtra("line", routeShortName)
            putExtra("headsign", headsign)
            putExtra("stop", stopName)
            putExtra("depEpochSec", departureEpochSec)
            putExtra("leadMin", leadMin)
            putExtra("notifId", notifId)
        }
        val pi = PendingIntent.getBroadcast(
            context, notifId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } catch (e: SecurityException) {
            // Ilman tarkka-ajastuslupaa: epätarkka (voi laueta hieman myöhässä) mutta toimii silti.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
        return true
    }
}

/** Herää AlarmManagerista lead-ajan koittaessa ja näyttää lähtömuistutus-ilmoituksen. */
class DepartureReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val line = intent.getStringExtra("line").orEmpty()
        val stop = intent.getStringExtra("stop").orEmpty()
        val headsign = intent.getStringExtra("headsign").orEmpty()
        val depEpochSec = intent.getLongExtra("depEpochSec", 0L)
        val leadMin = intent.getIntExtra("leadMin", 0)
        val notifId = intent.getIntExtra("notifId", Notifications.NOTIF_ID_DEPARTURE)
        val clock = SimpleDateFormat("HH:mm", Locale("fi", "FI"))
        val timeText = if (depEpochSec > 0) clock.format(Date(depEpochSec * 1000L)) else ""
        val title = if (line.isNotEmpty()) "Linja $line lähtee klo $timeText" else "Lähtö klo $timeText"
        val dest = if (headsign.isNotEmpty()) " ($headsign)" else ""
        Notifications.post(
            context, Notifications.CHANNEL_DEPARTURE, notifId,
            title,
            "Pysäkiltä $stop$dest noin $leadMin min kuluttua.",
            "TRANSIT",
        )
    }
}
