package org.jrs82.fsclock.mobile.widget

import android.content.Context
import android.location.LocationManager
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
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
import org.jrs82.fsclock.mobile.HealthConnectStepsBridge
import org.jrs82.fsclock.mobile.KEY_STEPS_USE_HC
import org.jrs82.fsclock.mobile.NearbyStop
import org.jrs82.fsclock.mobile.StepGoalNotifier
import org.jrs82.fsclock.mobile.WeatherCache
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
            } catch (e: Exception) { /* sailyta vanha cache */ }
        }
        // Fix 5: Sääikoni piirretään JOKA kierroksella nykyisen WeatherCache.last-tilan ja
        // uiModen perusteella — näin teema-/värimoodinvaihdon jälkeen ikoni päivittyy heti.
        try {
            val wd = WeatherCache.last
            if (wd != null) {
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
            }
        } catch (ie: Exception) {
            // Kuvanpiirto ei onnistu (esim. paalankausta off-main-thread) -> poistetaan vanha
            try { java.io.File(ctx.filesDir, "widget_weather_icon.png").delete() } catch (_: Exception) {}
        }
        // Fix 4: Haetaan verkkodata vain jos yli 25 min vanha (e_fetch_at); vartti luetaan JOKA
        // kierroksella, jotta hinta vaihtuu 15 min välein ilman uutta verkkopyyntöä.
        if (now - WidgetCache.electricityFetchAt(ctx) > stale) {
            try {
                val repo = ElectricityRepository.get(ctx)
                repo.fetchIfStale()
                WidgetCache.setElectricityFetchAt(ctx, now)
            } catch (e: Exception) { /* sailyta vanha */ }
        }
        try {
            val repo = ElectricityRepository.get(ctx)
            val q = repo.currentQuarter()
            if (q != null) WidgetCache.setElectricity(ctx, q.sntPerKwh, now)
        } catch (e: Exception) { /* sailyta vanha */ }
        // Fix 1: Askeleet joka kierros (paikallinen, halpa). Huomioidaan HC-opt-in, ja säilytetään
        // saman päivän paras arvo (ei korvata suurempaa pienemmällä tai nollalla).
        try {
            val c = Calendar.getInstance()
            val dayKey = c.get(Calendar.YEAR) * 10000 +
                (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
            val roomSteps = FsClockDb.get(ctx).dailyStepsDao().stepsForDay(dayKey) ?: 0
            val useHc = prefs.getBoolean(KEY_STEPS_USE_HC, false)
            val hcSteps: Long = if (useHc) {
                val latch = CountDownLatch(1)
                val result = AtomicLong(-1L)
                HealthConnectStepsBridge.todaySteps(ctx) { s ->
                    result.set(s)
                    latch.countDown()
                }
                try {
                    if (latch.await(8, TimeUnit.SECONDS)) result.get() else -1L
                } catch (ie: InterruptedException) { -1L }
            } else -1L
            val best: Int = when {
                useHc && hcSteps >= 0L -> maxOf(roomSteps, hcSteps.toInt())
                else -> roomSteps
            }
            val goal = StepGoalNotifier.goal(prefs)
            // Ei korvata saman päivän parempaa arvoa huonommalla; ei koskaan kirjoiteta negatiivista.
            val cachedDay = WidgetCache.stepsDayKey(ctx)
            val cachedSteps = WidgetCache.steps(ctx)
            val skipWrite = best <= 0 && cachedDay == dayKey && cachedSteps > 0
            if (!skipWrite) {
                val toWrite: Int = if (cachedDay == dayKey) maxOf(best, cachedSteps).coerceAtLeast(0)
                                   else best.coerceAtLeast(0)
                WidgetCache.setStepsWithDay(ctx, toWrite, goal, dayKey, now)
            }
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
            // Fix 2: Ei verkko- tai akkuehtoja — askeleet ja ikonipäivitys onnistuvat myös offline.
            // Verkkohaut ovat jo staleness-gatettuja ja suojattu try/catchilla.
            val work = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().build())
                .build()
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
