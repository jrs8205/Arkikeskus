package org.jrs82.fsclock.mobile.widget

import android.content.Context
import android.location.LocationManager
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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.jrs82.fsclock.ElectricityRepository
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WeatherIconView
import org.jrs82.fsclock.WeatherRepository
import org.jrs82.fsclock.WeatherTextFormatter
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.mobile.DigitransitApi
import org.jrs82.fsclock.mobile.NearbyStop
import org.jrs82.fsclock.mobile.StepGoalNotifier
import org.jrs82.fsclock.mobile.WeatherCache
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Etsii lahinta pysakkia kaytten ensin laitteen viimeista tunnettua sijaintia (GPS tai verkko),
 * ja kayttaa kotikoordin. fallbackina jos sijaintilupa puuttuu tai sijainti ei ole saatavilla.
 * Palauttaa null jos kumpikaan ei onnistu.
 */
private fun fetchNearestStop(ctx: Context): NearbyStop? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val deviceLoc: android.location.Location? = try {
        lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } catch (se: SecurityException) { null }

    val lat: Double
    val lon: Double
    if (deviceLoc != null) {
        lat = deviceLoc.latitude
        lon = deviceLoc.longitude
    } else {
        val sm = SettingsManager.get()
        if (!sm.hasHomeCoordinates()) return null
        lat = sm.getHomeLatitude()
        lon = sm.getHomeLongitude()
    }
    return DigitransitApi.nearbyDepartures(lat, lon).firstOrNull()
}

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
                val condLabel = WeatherTextFormatter.label(ctx, wd.current.condition)
                WidgetCache.setWeather(
                    ctx, place, wd.current.temperature, condLabel,
                    wd.current.windSpeed, wd.current.feelsLike, wd.current.precip1h, now,
                )
                // Piirrä sääikoni bitmapiksi widgettiä varten.
                try {
                    val sizePx = 96
                    val iconFile = java.io.File(ctx.filesDir, "widget_weather_icon.png")
                    val view = WeatherIconView(ctx)
                    view.setCondition(wd.current.condition)
                    val ms = View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)
                    view.measure(ms, ms)
                    view.layout(0, 0, sizePx, sizePx)
                    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bmp))
                    iconFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    bmp.recycle()
                } catch (ie: Exception) {
                    // Kuvanpiirto ei onnistu (esim. paalankausta off-main-thread) -> poistetaan vanha
                    try { java.io.File(ctx.filesDir, "widget_weather_icon.png").delete() } catch (_: Exception) {}
                }
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

        // Lahto-widgetit: hae kunkin asetetun widgetin pysakin lahdot.
        try {
            val mgr = androidx.glance.appwidget.GlanceAppWidgetManager(ctx)
            val ids = mgr.getGlanceIds(DepartureWidget::class.java)
            for (gid in ids) {
                val awId = mgr.getAppWidgetId(gid)
                val mode = WidgetCache.departureMode(ctx, awId)
                val stop = try {
                    when (mode) {
                        "FAVORITE" -> {
                            val stopId = WidgetCache.departureStopId(ctx, awId)
                            if (stopId.isNotBlank()) DigitransitApi.stopDepartures(stopId) else null
                        }
                        "NEAREST" -> fetchNearestStop(ctx)
                        else -> null
                    }
                } catch (e: Exception) { null }
                if (stop != null) {
                    val lines = stop.departures.take(3).map {
                        WidgetFormat.DepartureLine(it.routeShortName, it.mode, it.departureEpochSec)
                    }
                    val name = if (mode == "NEAREST") stop.name
                        else WidgetCache.departureStopName(ctx, awId).ifBlank { stop.name }
                    WidgetCache.setDepartureData(ctx, awId, name,
                        WidgetFormat.encodeDepartures(lines), now)
                }
            }
        } catch (e: Exception) { }

        // Paivita Glance-widgetit uudella cachella.
        try { WeatherWidget().updateAll(ctx) } catch (e: Exception) { }
        try { ElectricityWidget().updateAll(ctx) } catch (e: Exception) { }
        try { StepsWidget().updateAll(ctx) } catch (e: Exception) { }
        try { DepartureWidget().updateAll(ctx) } catch (e: Exception) { }
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

        /**
         * Konfiguraatiokäyttöön: varmistaa että virkistys ajetaan heti (REPLACE korvaa
         * mahdollisesti jonossa olevan pyynnön). Käytetään kun käyttäjä tallentaa uuden
         * widgetin asetukset, jotta uusi widget ei jää "Ei lähtöjä" -tilaan.
         */
        @JvmStatic
        fun refreshNowForce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK}_config", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build(),
            )
        }
    }
}
