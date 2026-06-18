package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.db.FsClockDb
import java.util.Locale

/**
 * 4b-ilmoitus #5: ilmoittaa kun päivän askeltavoite on saavutettu. Lukee tämän päivän askeleet
 * Room `daily_steps`-taulusta ([FsClockDb.dailyStepsDao]) — sama raw-lähde jota askelnäkymä käyttää.
 * Ilmoitetaan kerran per päivä ([KEY_LAST_DAY]). Tavoite säädettävissä ([KEY_GOAL], oletus 10000).
 * Opt-in (oletus pois).
 *
 * Huom: askelluku päivittyy Roomiin kun sovellus/askelpalvelu on käynyt → tausta­ilmoitus voi tulla
 * pienellä viiveellä jos sovellusta ei ole avattu (raw-sensori ei kirjaa Roomiin sovelluksen ollessa kiinni).
 */
object StepGoalNotifier {

    const val KEY_ENABLED = "notify_step_goal"
    const val KEY_GOAL = "step_goal"
    const val DEFAULT_GOAL = "10000"
    private const val KEY_LAST_DAY = "notify_step_goal_day"
    private const val KEY_LAST_DAY_HALF = "notify_step_goal_day_half"

    fun goal(prefs: android.content.SharedPreferences): Int =
        (prefs.getString(KEY_GOAL, DEFAULT_GOAL) ?: DEFAULT_GOAL).toIntOrNull() ?: 10000

    fun check(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val today = StepCounter.todayKey()
        if (prefs.getInt(KEY_LAST_DAY, 0) == today) return // jo ilmoitettu tänään
        val goal = goal(prefs)
        if (goal <= 0) return
        // Lue askeleet KÄYTTÄJÄN VALITSEMASTA lähteestä — sama kuin askelnäkymä: Health Connect kun se on
        // opt-in (KEY_STEPS_USE_HC), muuten puhelimen oma anturi (Room daily_steps). Aiemmin luettiin AINA
        // Roomista → HC-käyttäjällä ilmoitus ei lauennut vaikka HC näytti tavoitteen yli (puhelimen luku jäi pieneksi).
        val steps: Long = if (prefs.getBoolean(KEY_STEPS_USE_HC, false)) {
            val hc = readHealthConnectStepsBlocking(context)
            if (hc < 0L) return // HC ei saatavilla / lupaa puuttuu (myös taustalukulupa) / virhe → ei ilmoiteta, yritetään uudelleen
            hc
        } else {
            try { (FsClockDb.get(context).dailyStepsDao().stepsForDay(today) ?: 0).toLong() } catch (e: Exception) { return }
        }
        if (steps >= goal) {
            android.util.Log.i("StepGoalNotifier", "askeltavoite saavutettu: $steps/$goal")
            Notifications.post(
                context, Notifications.CHANNEL_STEPS, Notifications.NOTIF_ID_STEPS,
                "Päivän askeltavoite saavutettu!",
                String.format(Locale("fi", "FI"), "%,d askelta tänään (tavoite %,d).", steps, goal),
                "STEPS",
            )
            // Merkitään myös puolimatka tehdyksi, ettei se enää laukea tavoitteen jälkeen.
            prefs.edit().putInt(KEY_LAST_DAY, today).putInt(KEY_LAST_DAY_HALF, today).apply()
            return
        }
        // Puolimatkan kannustus: kun ≥ 50 % tavoitteesta, kerran/päivä, vain ennen tavoitteen täyttymistä.
        if (prefs.getInt(KEY_LAST_DAY_HALF, 0) != today && steps >= goal / 2) {
            android.util.Log.i("StepGoalNotifier", "askeltavoitteen puolimatka: $steps/$goal")
            Notifications.post(
                context, Notifications.CHANNEL_STEPS, Notifications.NOTIF_ID_STEPS,
                "Olet puolimatkassa!",
                String.format(Locale("fi", "FI"),
                    "Hyvä — olet puolivälissä tavoitetta. Koita vielä saavuttaa se! (%,d / %,d askelta)", steps, goal),
                "STEPS",
            )
            prefs.edit().putInt(KEY_LAST_DAY_HALF, today).apply()
        }
    }

    /** Tämän päivän Health Connect -askeleet synkronisesti. [NotificationsWorker] on synkroninen, joten
     *  odotetaan HC:n async-tulos lyhyellä latchilla (HC-aggregate pyörii Mainissa, eri säikeessä → ei
     *  lukkiudu). Taustaluku vaatii READ_HEALTH_DATA_IN_BACKGROUND-luvan; ilman sitä/virheellä → -1. */
    private fun readHealthConnectStepsBlocking(context: Context): Long {
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicLong(-1L)
        HealthConnectStepsBridge.todaySteps(context) { s ->
            result.set(s)
            latch.countDown()
        }
        return try {
            if (latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) result.get() else -1L
        } catch (e: InterruptedException) {
            -1L
        }
    }
}
