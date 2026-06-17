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

    fun goal(prefs: android.content.SharedPreferences): Int =
        (prefs.getString(KEY_GOAL, DEFAULT_GOAL) ?: DEFAULT_GOAL).toIntOrNull() ?: 10000

    fun check(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val today = StepCounter.todayKey()
        if (prefs.getInt(KEY_LAST_DAY, 0) == today) return // jo ilmoitettu tänään
        val goal = goal(prefs)
        if (goal <= 0) return
        val steps = try { FsClockDb.get(context).dailyStepsDao().stepsForDay(today) ?: 0 } catch (e: Exception) { return }
        if (steps < goal) return
        android.util.Log.i("StepGoalNotifier", "askeltavoite saavutettu: $steps/$goal")
        Notifications.post(
            context, Notifications.CHANNEL_STEPS, Notifications.NOTIF_ID_STEPS,
            "Päivän askeltavoite saavutettu!",
            String.format(Locale("fi", "FI"), "%,d askelta tänään (tavoite %,d).", steps, goal),
            "STEPS",
        )
        prefs.edit().putInt(KEY_LAST_DAY, today).apply()
    }
}
