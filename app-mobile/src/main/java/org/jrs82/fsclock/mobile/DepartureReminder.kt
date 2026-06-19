package org.jrs82.fsclock.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 4b-ilmoitus #4: kertamuistutus tietystä joukkoliikennelähdöstä. Käyttäjä valitsee lähdön
 * (Lähilähdöt → pitkä painallus rivistä → "Muistuta") ja lead-ajan (5/10/15 min); AlarmManager
 * herättää tarkasti ja [DepartureReminderReceiver] näyttää ilmoituksen.
 *
 * Avoimet muistutukset persistoidaan ([KEY_PENDING]), koska AlarmManager EI säilytä hälytyksiä
 * laitteen uudelleenkäynnistyksen yli. [DepartureBootReceiver] asettaa ne uudelleen bootissa
 * (mennyt aika pudotetaan). Toteutunut muistutus poistetaan listalta vastaanottimessa.
 */
object DepartureReminder {

    /** Avoimet muistutukset oletus-SharedPreferencesissä JSON-listana. */
    const val KEY_PENDING = "departure_pending"

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
        setAlarm(context, routeShortName, headsign, stopName, departureEpochSec, reqKey, leadMin, trigger)
        savePending(context, routeShortName, headsign, stopName, departureEpochSec, reqKey, leadMin)
        return true
    }

    /** Asettaa varsinaisen AlarmManager-hälytyksen (jaettu [schedule]n ja [rescheduleAll]n kesken). */
    private fun setAlarm(
        context: Context,
        routeShortName: String,
        headsign: String,
        stopName: String,
        departureEpochSec: Long,
        reqKey: String,
        leadMin: Int,
        trigger: Long,
    ) {
        // Oma ID-alue (>= 1000) ettei lähtömuistutus törmää muiden ilmoitusten kiinteisiin
        // ID:ihin (41–60) ja korvaa niitä. (& 0x7FFFFFFF estää negatiivisen hashin.)
        val notifId = 1000 + (reqKey.hashCode() and 0x7FFFFFFF) % 100000
        val intent = Intent(context, DepartureReminderReceiver::class.java).apply {
            putExtra("line", routeShortName)
            putExtra("headsign", headsign)
            putExtra("stop", stopName)
            putExtra("depEpochSec", departureEpochSec)
            putExtra("leadMin", leadMin)
            putExtra("notifId", notifId)
            putExtra("reqKey", reqKey)
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
    }

    // ---------- Persistointi (boot-selviytymistä varten) ----------

    private fun loadArray(prefs: android.content.SharedPreferences): JSONArray = try {
        JSONArray(prefs.getString(KEY_PENDING, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    /** Tallenna/päivitä muistutus reqKeyllä; poistaa samalla menneet ja saman avaimen duplikaatit. */
    private fun savePending(
        context: Context,
        routeShortName: String,
        headsign: String,
        stopName: String,
        departureEpochSec: Long,
        reqKey: String,
        leadMin: Int,
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val now = System.currentTimeMillis()
        val arr = loadArray(prefs)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("reqKey") == reqKey) continue // korvataan
            if (triggerMs(o.optLong("depEpochSec"), o.optInt("leadMin")) <= now) continue // mennyt
            out.put(o)
        }
        out.put(
            JSONObject()
                .put("line", routeShortName)
                .put("headsign", headsign)
                .put("stop", stopName)
                .put("depEpochSec", departureEpochSec)
                .put("reqKey", reqKey)
                .put("leadMin", leadMin),
        )
        prefs.edit().putString(KEY_PENDING, out.toString()).apply()
    }

    /** Poista yksi muistutus listalta (kutsutaan kun se on lauennut). */
    fun removePending(context: Context, reqKey: String) {
        if (reqKey.isEmpty()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val arr = loadArray(prefs)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("reqKey") == reqKey) continue
            out.put(o)
        }
        prefs.edit().putString(KEY_PENDING, out.toString()).apply()
    }

    /** Aseta kaikki avoimet muistutukset uudelleen (boot / sovelluspäivitys). Menneet pudotetaan. */
    fun rescheduleAll(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val now = System.currentTimeMillis()
        val arr = loadArray(prefs)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val depEpochSec = o.optLong("depEpochSec")
            val leadMin = o.optInt("leadMin")
            val trigger = triggerMs(depEpochSec, leadMin)
            if (trigger <= now + 5_000L) continue // mennyt → ei aseteta uudelleen
            setAlarm(
                context, o.optString("line"), o.optString("headsign"), o.optString("stop"),
                depEpochSec, o.optString("reqKey"), leadMin, trigger,
            )
            out.put(o)
        }
        prefs.edit().putString(KEY_PENDING, out.toString()).apply()
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
        val reqKey = intent.getStringExtra("reqKey").orEmpty()
        // Muistutus toteutui → poista se persistoidusta listasta (ettei boot aseta sitä uudelleen).
        DepartureReminder.removePending(context, reqKey)
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

/**
 * Asettaa avoimet lähtömuistutukset uudelleen laitteen uudelleenkäynnistyksen tai sovelluspäivityksen
 * jälkeen (AlarmManager tyhjentää hälytykset näissä). Vain protected system broadcastit → turvallinen.
 */
class DepartureBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.TIME_SET",
            Intent.ACTION_TIMEZONE_CHANGED ->
                try { DepartureReminder.rescheduleAll(context) } catch (e: Exception) { }
        }
    }
}
