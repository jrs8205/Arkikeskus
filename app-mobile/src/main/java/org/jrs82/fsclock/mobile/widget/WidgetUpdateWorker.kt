package org.jrs82.fsclock.mobile.widget

import android.content.Context
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
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jrs82.fsclock.ElectricityRepository
import org.jrs82.fsclock.OpenMeteoData
import org.jrs82.fsclock.OpenMeteoRepository
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WeatherCondition
import org.jrs82.fsclock.WeatherData
import org.jrs82.fsclock.WeatherRepository
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.mobile.DigitransitApi
import org.jrs82.fsclock.mobile.TransitRegion
import org.jrs82.fsclock.mobile.HealthConnectStepsBridge
import org.jrs82.fsclock.mobile.maybeRefreshWidgetLocationPassive
import org.jrs82.fsclock.mobile.passiveDeviceLocation
import org.jrs82.fsclock.mobile.KEY_STEPS_USE_HC
import org.jrs82.fsclock.mobile.NearbyStop
import org.jrs82.fsclock.mobile.StepGoalNotifier
import org.jrs82.fsclock.mobile.WeatherCache
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Etsii lähintä pysäkkiä käyttäen passiivista, gatettua laitesijaintia ([passiveDeviceLocation]:
 * fused-preferred, ikä ≤ 30 min, tarkkuus ≤ 2000 m — EI aktiivista hakua → taustaturvallinen) ja
 * kotikoordinaatteja fallbackina (jotka sää-block päivittää tuoreesta sijainnista). null jos kumpikaan ei onnistu.
 */
private fun fetchNearestStop(ctx: Context): NearbyStop? {
    val deviceLoc = passiveDeviceLocation(ctx)
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
    // Alue sijainnista (Tampere-bbox → Nysse, muuten HSL) — kuten etusivun Lähilähdöt-kortti.
    return DigitransitApi.nearbyDepartures(lat, lon, TransitRegion.forLocation(lat, lon)).firstOrNull()
}

/** Kokoaa seuraavat 24 tuntia (Helsingin aika, kuluvasta tunnista alkaen — myös keskiyön yli)
 *  FMI- ja Open-Meteo-lähteistä ja tallentaa ne JSON-listana widgetin skrollattavaa kaksisarakkeista
 *  tuntinäkymää varten ([{h,f,o,fc,oc,night}, …]). fc/oc = säätyyppi (WeatherCondition.Type.name) per
 *  lähde, night = yö-lippu (ikonivalintaan). */
private fun storeWeatherHours(ctx: Context, fmiHours: List<WeatherData.Hour>?, om: OpenMeteoData?) {
    // Avain (kuukausi, päivä, tunti) yksilöi tunnin myös keskiyön yli ilman timestamp-yksikköoletuksia.
    fun key(mon: Int, dom: Int, h: Int) = (mon * 100 + dom) * 100 + h
    val fmiByKey = HashMap<Int, WeatherData.Hour>()
    fmiHours?.forEach { fmiByKey[key(it.month, it.dayOfMonth, it.hour)] = it }
    val omByKey = HashMap<Int, OpenMeteoData.Hour>()
    om?.hours?.forEach { omByKey[key(it.month, it.dayOfMonth, it.hour)] = it }

    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Helsinki"))
    cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val arr = org.json.JSONArray()
    repeat(24) {
        val mon = cal.get(Calendar.MONTH) + 1
        val dom = cal.get(Calendar.DAY_OF_MONTH)
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val f = fmiByKey[key(mon, dom, hh)]
        val o = omByKey[key(mon, dom, hh)]
        val fT = f?.temperature?.takeIf { !it.isNaN() }
        val oT = o?.temperature
        if (fT != null || oT != null) {
            val obj = org.json.JSONObject().put("h", hh)
            if (fT != null) obj.put("f", fT)
            if (oT != null) obj.put("o", oT)
            f?.condition?.let { obj.put("fc", it.type.name) }
            o?.condition?.let { obj.put("oc", it.type.name) }
            obj.put("night", o?.condition?.isNight ?: f?.condition?.isNight ?: false)
            arr.put(obj)
        }
        cal.add(Calendar.HOUR_OF_DAY, 1)
    }
    WidgetCache.setWeatherHours(ctx, arr.toString())
}

/** Lähin Open-Meteo-tunti annettuun hetkeen (max 31 min toleranssi); null jos ei sovi. */
private fun nearestOpenMeteoHour(om: OpenMeteoData?, nowMs: Long): OpenMeteoData.Hour? {
    val hours = om?.hours ?: return null
    var best: OpenMeteoData.Hour? = null
    var bestDiff = Long.MAX_VALUE
    for (h in hours) {
        val diff = Math.abs(h.timestamp - nowMs)
        if (diff < bestDiff) { bestDiff = diff; best = h }
    }
    return if (best == null || bestDiff > 31L * 60_000L) null else best
}

class WidgetUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        SettingsManager.get().init(ctx) // idempotentti varmistus
        val now = System.currentTimeMillis()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        // Saa + sahko vain jos vanhentunut (>25 min) -> ~30 min 15 min workerilla, akkua saastäen.
        val stale = 25L * 60_000L
        // Option 2a: passiivinen taustasijaintipäivitys — päivittää kotipaikan + näyttönimen jos liikuttu.
        try { maybeRefreshWidgetLocationPassive(ctx) } catch (e: Exception) { }
        // (KEY_AUTO_LOCATION_DISPLAY_NAME on package-private → suora avainmerkkijono; sama paikka molemmille lähteille.)
        val place = (prefs.getString("mobile_auto_location_display_name", "")
            ?: "").ifBlank { SettingsManager.get().homePlace }
        // 1) Paikan NIMI päivittyy aina HALVALLA (ei verkkoa): kaupunginosa vaihtuu liikkuessa, mutta sää
        //    tulee samalta viralliselta FMI-asemalta koko kaupungin alueella → säädataa ei tarvitse hakea.
        if (!place.equals(WidgetCache.weatherPlace(ctx), ignoreCase = true)) WidgetCache.setWeatherPlace(ctx, place)
        // 2) SÄÄDATA haetaan vain jos vanha (25 min) TAI siirrytty niin kauas (>8 km) että lähin virallinen
        //    FMI-asema voi vaihtua (esim. Vantaa→Helsinki/Kaisaniemi). Sama asema → sama sää → ei turhia hakuja.
        val smW = SettingsManager.get()
        val movedFar = if (!smW.hasHomeCoordinates()) false else {
            val lat = WidgetCache.weatherLat(ctx)
            val lon = WidgetCache.weatherLon(ctx)
            if (lat.isNaN() || lon.isNaN()) true else {
                val res = FloatArray(1)
                android.location.Location.distanceBetween(lat, lon, smW.homeLatitude, smW.homeLongitude, res)
                res[0] > 8000f
            }
        }
        if (movedFar || now - WidgetCache.weatherUpdatedAt(ctx) > stale) {
            try {
                val wd = WeatherRepository.get(ctx).fetchHome(WeatherCache.last, true)
                WeatherCache.last = wd
                WidgetCache.setWeather(ctx, place, wd.current.temperature, now)
                // Hero-ikonia varten: nykytilan tyyppi + yö (robustimpi kuin label-avainsanat).
                WidgetCache.setWeatherCond(
                    ctx, wd.current.condition.type.name, wd.current.condition.isNight,
                )
                // Tallenna haun koordinaatit etäisyysporttiin (seuraava haku vasta >8 km siirtymästä).
                if (smW.hasHomeCoordinates()) WidgetCache.setWeatherLocation(ctx, smW.homeLatitude, smW.homeLongitude)
            } catch (e: Exception) { /* sailyta vanha cache */ }
            // Open-Meteo samalle paikalle (FMI:n rinnalle widgetiin); itsenainen FMI-hausta. KOORDINAATEILLA
            // kuten sovelluksen ennustesivu → ei nimiriippuvaista GeoPlace-resoluutiota (robustimpi taustalla).
            val sm = SettingsManager.get()
            val hasCoords = sm.hasHomeCoordinates()
            var om: OpenMeteoData? = null
            try {
                om = if (hasCoords)
                    OpenMeteoRepository.get(ctx).fetch(place, sm.homeLatitude, sm.homeLongitude, true)
                else OpenMeteoRepository.get(ctx).fetch(place, true)
                val h = nearestOpenMeteoHour(om, now)
                if (h != null) {
                    val c = h.condition ?: WeatherCondition.unknown()
                    WidgetCache.setWeatherOpenMeteo(
                        ctx, h.temperature ?: Double.NaN, c.type.name, c.isNight, now,
                    )
                }
            } catch (e: Exception) {
                // Hetkellinen verkko-/lähdevirhe EI saa pyyhkiä OM-saraketta tuntilistasta → viimeisin onnistunut.
                om = try {
                    if (hasCoords) OpenMeteoRepository.get(ctx).peek(place, sm.homeLatitude, sm.homeLongitude)
                    else OpenMeteoRepository.get(ctx).peek(place)
                } catch (e2: Exception) { null }
            }
            // Koko paivan tuntilista (FMI + Open-Meteo). Paivitetaan VAIN jos OM-data saatiin → hetkellinen
            // OM-hakuvirhe EI pyyhi koko OM-saraketta tuntilistasta (sailyy viimeisin onnistunut; hero-FMI
            // paivittyy erikseen). Korjaa "OM-sarake katoaa liikkuessa" -bugin kun fetch+peek epaonnistuvat.
            if (om?.hours?.isNotEmpty() == true) {
                try {
                    storeWeatherHours(ctx, WeatherCache.last?.hours, om)
                } catch (e: Exception) { /* sailyta vanha tuntilista */ }
            }
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
            // Paivan halvin/kallein vartti (Helsingin aika) widgetille.
            val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Helsinki"))
            val today = repo.dayQuarters(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            val cheapest = today.minByOrNull { it.sntPerKwh }
            val dearest = today.maxByOrNull { it.sntPerKwh }
            WidgetCache.setElectricityExtremes(
                ctx,
                cheapest?.sntPerKwh, cheapest?.timestamp,
                dearest?.sntPerKwh, dearest?.timestamp,
            )
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
                // Suspendoiva odotus (ei säie-estoa): CoroutineWorkerin IO-coroutine suspendoituu kunnes
                // HC-callback (Main) resumeoi; 8 s timeout → -1L. isActive-vartija estää late-resume-kaadon.
                withTimeoutOrNull(8_000L) {
                    suspendCancellableCoroutine<Long> { cont ->
                        HealthConnectStepsBridge.todaySteps(ctx) { s -> if (cont.isActive) cont.resume(s) }
                    }
                } ?: -1L
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
                try {
                    val awId = mgr.getAppWidgetId(gid)
                    val mode = WidgetCache.departureMode(ctx, awId)
                    if (mode.isBlank()) continue // ei konfiguroitu, ohita
                    val stop = try {
                        when (mode) {
                            "FAVORITE" -> {
                                val stopId = WidgetCache.departureStopId(ctx, awId)
                                // Suosikki voi olla pysakki TAI asema (metro/juna): jos pysakkihaku
                                // palauttaa nullin, kokeillaan asemahakua -> suosikkiasemat toimivat.
                                if (stopId.isNotBlank()) {
                                    // Alue suosikin gtfsId-prefiksistä ("tampere:" → Nysse) — suosikit globaaleja.
                                    val region = TransitRegion.forGtfsId(stopId)
                                    DigitransitApi.stopDepartures(stopId, region)
                                        ?: DigitransitApi.stationDepartures(stopId, region)
                                } else {
                                    null
                                }
                            }
                            "NEAREST" -> fetchNearestStop(ctx)
                            else -> null
                        }
                    } catch (e: Exception) { null }
                    if (stop != null) {
                        val lines = stop.departures.take(5).map {
                            WidgetFormat.DepartureLine(it.routeShortName, it.mode, it.departureEpochSec)
                        }
                        val name = if (mode == "NEAREST") stop.name
                            else WidgetCache.departureStopName(ctx, awId).ifBlank { stop.name }
                        WidgetCache.setDepartureData(ctx, awId, name, stop.code ?: "",
                            WidgetFormat.encodeDepartures(lines), now)
                    } else {
                        // Fix B (loop-esto): jos haku epäonnistui eikä cachessa ole YHTÄÄN aiempaa
                        // dataa, kirjoitetaan tyhjä merkki jotta aikaleima etenee. Näin provideGlance
                        // ei jää ikuisesti käynnistämään uusia workereita.
                        // EI ylikirjoiteta olemassa olevaa hyvää dataa ohimenevällä virheellä.
                        if (WidgetCache.departureUpdatedAt(ctx, awId) == 0L) {
                            val fallbackName = WidgetCache.departureStopName(ctx, awId)
                            WidgetCache.setDepartureData(ctx, awId, fallbackName, "", "[]", now)
                        }
                    }
                } catch (e: Exception) { /* yksittainen widget ei kaada koko silmukkaa */ }
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
            // Fix A: REPLACE ettei onResume-perus-refresh tipu jo-jonossa olevan taakse.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK}_once", ExistingWorkPolicy.REPLACE,
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
