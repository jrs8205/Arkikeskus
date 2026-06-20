package org.jrs82.fsclock.mobile.widget

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.ElectricityRepository
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WeatherRepository
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.mobile.StepGoalNotifier
import org.jrs82.fsclock.mobile.WeatherCache
import java.util.Calendar
import java.util.concurrent.TimeUnit

class WidgetUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        SettingsManager.get().init(ctx) // idempotentti varmistus
        val now = System.currentTimeMillis()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        // Saa + sahko vain jos vanhentunut (>25 min) -> ~30 min 15 min workerilla, akkua saastäen.
        val stale = 25L * 60_000L
        if (now - WidgetCache.weatherUpdatedAt(ctx) > stale) {
            try {
                val wd = WeatherRepository.get(ctx).fetchHome(WeatherCache.last, true)
                WeatherCache.last = wd
                // MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME on package-private
                // -> kaytamme suoraan avainmerkkijonoa.
                val place = (prefs.getString("mobile_auto_location_display_name", "")
                    ?: "").ifBlank { SettingsManager.get().homePlace }
                WidgetCache.setWeather(ctx, place, wd.current.temperature,
                    wd.current.condition?.toString() ?: "", now)
            } catch (e: Exception) { /* sailyta vanha cache */ }
        }
        if (now - WidgetCache.electricityUpdatedAt(ctx) > stale) {
            try {
                val repo = ElectricityRepository.get(ctx)
                repo.fetchIfStale()
                val q = repo.currentQuarter()
                if (q != null) WidgetCache.setElectricity(ctx, q.sntPerKwh, now)
            } catch (e: Exception) { /* sailyta vanha */ }
        }
        // Askeleet joka kierros (paikallinen, halpa).
        // StepCounter on package-private -> ei saavutettavissa workerista.
        // Lasketaan paivaainen samalla logiikalla kuin StepCounter.todayKey().
        try {
            val c = Calendar.getInstance()
            val today = c.get(Calendar.YEAR) * 10000 +
                (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
            val room = FsClockDb.get(ctx).dailyStepsDao().stepsForDay(today)
            val steps = room ?: 0
            WidgetCache.setSteps(ctx, steps, StepGoalNotifier.goal(prefs), now)
        } catch (e: Exception) { /* sailyta vanha */ }

        // Paivita Glance-widgetit uudella cachella.
        try { WeatherWidget().updateAll(ctx) } catch (e: Exception) { }
        try { ElectricityWidget().updateAll(ctx) } catch (e: Exception) { }
        try { StepsWidget().updateAll(ctx) } catch (e: Exception) { }
        Result.success()
    }

    companion object {
        private const val WORK = "arkikeskus_widgets"
        @JvmStatic
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, work)
        }
        @JvmStatic
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK}_once", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build(),
            )
        }
    }
}
