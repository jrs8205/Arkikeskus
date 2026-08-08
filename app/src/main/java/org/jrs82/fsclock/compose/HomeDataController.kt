package org.jrs82.fsclock.compose

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import org.jrs82.fsclock.Astronomy
import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.ElectricityRepository
import org.jrs82.fsclock.FinnishHolidays
import org.jrs82.fsclock.GeoPlace
import org.jrs82.fsclock.OpenMeteoData
import org.jrs82.fsclock.OpenMeteoRepository
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WeatherData
import org.jrs82.fsclock.WeatherRepository
import org.jrs82.fsclock.WeatherSnapshot
import org.jrs82.fsclock.WeatherTextFormatter
import org.jrs82.fsclock.WeatherWarning
import org.jrs82.fsclock.ruuvi.RuuviRepository
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Kerää etusivun datan olemassa olevista (Java-)repositorioista ja julkaisee sen
 * Compose-tilana (uiState). Malli kuten ClockController; tulos menee HomeUi:hin.
 */
class HomeDataController(activityCtx: Context) {

    private val ctx: Context = activityCtx.applicationContext

    var uiState by mutableStateOf(HomeUi())
        private set

    private val ui = Handler(Looper.getMainLooper())
    private var io: ExecutorService? = null
    private val active = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private var wifiTickCount = 0

    @Volatile private var fmiCache: WeatherData? = null
    @Volatile private var fmiCacheKey: String = ""
    @Volatile private var omCache: OpenMeteoData? = null
    @Volatile private var elCache: ElectricityData? = null

    private val ruuviListener = RuuviRepository.Listener { _, _ -> ui.post { renderSensors() } }
    private val warningsListener =
        org.jrs82.fsclock.WarningsRepository.Listener { w -> ui.post { renderWarnings(w) } }

    fun start() {
        if (!active.compareAndSet(false, true)) return
        generation.incrementAndGet()
        SettingsManager.get().init(ctx)
        ensureFmiCacheKey(SettingsManager.get())
        if (io == null) io = Executors.newSingleThreadExecutor()
        update { it.copy(
            city = SettingsManager.get().homePlace, district = "",
            warnAutoScroll = SettingsManager.get().warningsAutoScroll
        ) }
        computeHoliday()
        ui.post(deviceTick)
        ui.post(weatherTick)
        ui.post(electricityTick)
        ui.post(electricityRenderTick)
        ui.post(sunTick)
        ui.post(sensorStalenessTick)
        ui.post(holidayTick)
        org.jrs82.fsclock.WarningsRepository.get().addListener(warningsListener)
        ui.post(warningsTick)
        val ruuvi = RuuviRepository.get(ctx)
        ruuvi.start()
        ruuvi.addListener(ruuviListener)
        renderSensors()
        if (hasLocationPermission()) fetchLocation()
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        generation.incrementAndGet()
        try { org.jrs82.fsclock.WarningsRepository.get().removeListener(warningsListener) } catch (_: Exception) {}
        try {
            val ruuvi = RuuviRepository.get(ctx)
            ruuvi.removeListener(ruuviListener)
            ruuvi.stop()
        } catch (_: Exception) {}
        ui.removeCallbacksAndMessages(null)
        io?.shutdownNow(); io = null
    }

    /** Postaa tilapäivityksen UI:lle; ohittaa jos controller on pysäytetty/uudelleenkäynnistetty. */
    private inline fun update(crossinline block: (HomeUi) -> HomeUi) {
        val run = generation.get()
        update(run, block)
    }

    private inline fun update(run: Long, crossinline block: (HomeUi) -> HomeUi) {
        ui.post { if (isCurrent(run)) uiState = block(uiState) }
    }

    private fun isCurrent(run: Long): Boolean = active.get() && generation.get() == run

    private fun executeCurrent(block: (Long) -> Unit) {
        if (!active.get() || io == null) return
        val run = generation.get()
        io?.execute {
            if (isCurrent(run)) block(run)
        }
    }

    /** Sijainnin kytkentä: nollaa FMI-cache + hakee sään uudet koordinaatit heti (ei uutta tick-ketjua). */
    fun setLocation(city: String, district: String) {
        update { it.copy(city = city, district = district) }
        resetFmiCache(SettingsManager.get())
        fetchWeatherNow(forceOm = true)
        computeSun()
    }

    // ---------------- WiFi + akku ----------------

    private val deviceTick = object : Runnable {
        override fun run() {
            if (!active.get()) return
            if (wifiTickCount++ % 5 == 0) {
                pushWifi()
                pushModes()
            }
            pushBattery()
            ui.postDelayed(this, 1000L)
        }
    }

    /** Yön punasävy + offline-testitila (5 s välein — testitila vanhenee itsestään). */
    private fun pushModes() {
        try {
            val sm = SettingsManager.get()
            val test = sm.activeTestMode
            val night = when (test) {
                SettingsManager.TEST_NIGHT -> true
                SettingsManager.TEST_DAY -> false
                else -> {
                    val hour = Calendar.getInstance(HELSINKI_TZ, FI).get(Calendar.HOUR_OF_DAY)
                    val morning = sm.morningHour
                    val evening = sm.eveningHour
                    if (evening >= morning) hour >= evening || hour < morning
                    else hour >= evening && hour < morning
                }
            }
            val red = night && sm.isNightRedTint
            val offline = test == SettingsManager.TEST_OFFLINE
            update { it.copy(redTint = red, testOffline = offline) }
        } catch (e: Exception) { Log.w(TAG, "modes", e) }
    }

    @Suppress("DEPRECATION")
    private fun pushWifi() {
        var level = 0; var mbps = 0; var band = ""
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wm.isWifiEnabled) {
                val info = wm.connectionInfo
                if (info != null && info.supplicantState == SupplicantState.COMPLETED) {
                    val rssi = info.rssi
                    if (rssi != Int.MIN_VALUE && rssi > -127) {
                        level = WifiManager.calculateSignalLevel(rssi, 5) + 1
                        mbps = maxOf(0, info.linkSpeed)
                        band = bandLabel(info.frequency)
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "wifi", e) }
        val l = level; val m = mbps; val b = band
        update { it.copy(wifiLevel = l, wifiMbps = m, wifiBand = b) }
    }

    private fun pushBattery() {
        try {
            val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (lvl < 0 || scale <= 0) return
            val pct = Math.round(lvl * 100f / scale)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            update { it.copy(battPct = pct, battCharging = charging) }
        } catch (e: Exception) { Log.w(TAG, "battery", e) }
    }

    // ---------------- Sää (FMI + Open-Meteo) ----------------

    private val weatherTick = object : Runnable {
        override fun run() {
            if (!active.get()) return
            if (!isOfflineTest()) fetchWeatherNow(forceOm = false)
            ui.postDelayed(this, 10L * 60_000L)
        }
    }

    /** Offline-testitila: verkkohaut ohitetaan, jolloin UI näyttää vanhenevan datan. */
    private fun isOfflineTest(): Boolean =
        try { SettingsManager.get().activeTestMode == SettingsManager.TEST_OFFLINE } catch (e: Exception) { false }

    private fun fetchWeatherNow(forceOm: Boolean) {
        executeCurrent { run ->
            val sm = SettingsManager.get()
            val place = sm.homePlace
            val key = ensureFmiCacheKey(sm)
            try {
                val wd = WeatherRepository.get(ctx).fetchHome(fmiCache)
                if (!isCurrent(run) || weatherCacheKey(SettingsManager.get()) != key) return@executeCurrent
                fmiCache = wd
                fmiCacheKey = key
                // Asetussivun "Viimeisin sääpäivitys" — legacy-ClockController oli
                // ainoa kirjoittaja, joten arvo jäätyi Compose-UI:ssa ikuisesti.
                sm.setLastSuccessfulFmiUpdate(wd.fetchedAt)
                val fc = buildForecast()
                update(run) { it.copy(fmi = snapshotToUi(WeatherSnapshot.fromFmi(wd.current, place, wd.fetchedAt)), forecast = fc) }
            } catch (e: Exception) { Log.w(TAG, "fmi", e) }
            if (!isCurrent(run) || weatherCacheKey(SettingsManager.get()) != key) return@executeCurrent
            try {
                val om = if (sm.hasHomeCoordinates())
                    OpenMeteoRepository.get(ctx).fetch(place, sm.homeLatitude, sm.homeLongitude, forceOm)
                else OpenMeteoRepository.get(ctx).fetch(place)
                if (!isCurrent(run) || weatherCacheKey(SettingsManager.get()) != key) return@executeCurrent
                omCache = om
                val h = nearestHour(om)
                val fc = buildForecast()
                update(run) { it.copy(
                    om = if (h != null) snapshotToUi(WeatherSnapshot.fromOpenMeteo(h, place, om.fetchedAt)) else null,
                    forecast = fc
                ) }
            } catch (e: Exception) {
                Log.w(TAG, "om", e)
                // Pitkässä katkossa lähin cache-tunti ei enää osu ±30 min sisään —
                // tyhjennetään OM-lohko ettei jäätynyt lukema näytä tuoreelta.
                if (weatherCacheKey(SettingsManager.get()) == key && nearestHour(omCache) == null) {
                    update(run) { it.copy(om = null) }
                }
            }
        }
    }

    // ---------------- 7 vrk -ennuste ----------------

    /** Ryhmittelee FMI- ja Open-Meteo-tunnit päiviksi (kuten ClockController.renderForecastAll). */
    private fun buildForecast(): List<DayForecastUi> {
        val fmiByDay = sortedMapOf<Int, MutableMap<Int, HourRowUi>>()
        val omByDay = sortedMapOf<Int, MutableMap<Int, HourRowUi>>()
        val dayTs = HashMap<Int, Long>()
        fmiCache?.let { wd ->
            for (h in wd.hours) {
                val key = dayKey(h.timestamp)
                val wind = if (!h.windSpeed.isNaN()) h.windSpeed else h.windGust
                fmiByDay.getOrPut(key) { HashMap() }[h.hour] = HourRowUi(
                    h.hour, nanToNull(h.temperature), null, nanToNull(wind), null,
                    nanToNull(h.precipitation), h.condition
                )
                if (!dayTs.containsKey(key)) dayTs[key] = h.timestamp
            }
        }
        omCache?.let { om ->
            for (h in om.hours) {
                val key = dayKey(h.timestamp)
                omByDay.getOrPut(key) { HashMap() }[h.hour] = HourRowUi(
                    h.hour, h.temperature?.toFloat(), h.feelsLike?.toFloat(), h.windSpeed?.toFloat(),
                    h.humidity?.toFloat(), h.precipitation?.toFloat(), h.condition
                )
                if (!dayTs.containsKey(key)) dayTs[key] = h.timestamp
            }
        }
        val dayKeys = sortedSetOf<Int>()
        dayKeys.addAll(fmiByDay.keys)
        dayKeys.addAll(omByDay.keys)
        val out = ArrayList<DayForecastUi>()
        for (dayK in dayKeys) {
            if (out.size >= 7) break
            val ts = dayTs[dayK] ?: continue
            val cal = Calendar.getInstance(HELSINKI_TZ, FI)
            cal.timeInMillis = ts
            val label = "${WD[cal.get(Calendar.DAY_OF_WEEK) - 1]} ${cal.get(Calendar.DAY_OF_MONTH)}.${cal.get(Calendar.MONTH) + 1}."
            val fmiMap = fmiByDay[dayK] ?: emptyMap()
            val omMap = omByDay[dayK] ?: emptyMap()
            val hours = (fmiMap.keys + omMap.keys).toSortedSet().toList()
            out.add(DayForecastUi(label, fmiMap, omMap, hours))
        }
        return out
    }

    private fun dayKey(ts: Long): Int {
        val c = Calendar.getInstance(HELSINKI_TZ, FI)
        c.timeInMillis = ts
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    private fun nanToNull(v: Double): Float? = if (v.isNaN()) null else v.toFloat()

    private fun ensureFmiCacheKey(sm: SettingsManager): String {
        val key = weatherCacheKey(sm)
        if (fmiCacheKey != key) {
            fmiCache = null
            fmiCacheKey = key
        }
        return key
    }

    private fun resetFmiCache(sm: SettingsManager) {
        fmiCache = null
        fmiCacheKey = weatherCacheKey(sm)
    }

    private fun weatherCacheKey(sm: SettingsManager): String {
        val place = sm.homePlace.trim().lowercase(Locale.ROOT)
        return if (sm.hasHomeCoordinates())
            "$place|${sm.homeLatitude}|${sm.homeLongitude}"
        else
            "$place|name"
    }

    private fun snapshotToUi(snap: WeatherSnapshot?): WeatherUi? {
        if (snap == null) return null
        val cond = try { if (snap.condition != null) WeatherTextFormatter.label(ctx, snap.condition) else "" } catch (e: Exception) { "" }
        return WeatherUi(
            t = snap.temperature?.toFloat(),
            cond = cond ?: "",
            feels = snap.feelsLike?.toFloat(),
            wind = snap.windSpeed?.toFloat(),
            hum = snap.humidity?.toFloat(),
            precip = snap.precip1h?.toFloat(),
            condition = snap.condition
        )
    }

    private fun nearestHour(om: OpenMeteoData?): OpenMeteoData.Hour? {
        if (om == null) return null
        val now = System.currentTimeMillis()
        var best = Long.MAX_VALUE; var bestH: OpenMeteoData.Hour? = null
        for (h in om.hours) {
            val diff = Math.abs(h.timestamp - now)
            if (diff < best && diff <= 30L * 60_000L) { best = diff; bestH = h }
        }
        return bestH
    }

    // ---------------- Sähkö ----------------

    private val electricityTick = object : Runnable {
        override fun run() {
            if (!active.get()) return
            if (!isOfflineTest()) executeCurrent { run ->
                try {
                    val repo = ElectricityRepository.get(ctx)
                    // Klo 14 jälkeen huomisen hinnat julkaistaan — ohita TTL kunnes ne ovat tulleet.
                    val hourNow = Calendar.getInstance(HELSINKI_TZ, FI).get(Calendar.HOUR_OF_DAY)
                    val data = if (hourNow >= 14 && !hasTomorrow(elCache)) repo.fetchNow() else repo.fetchIfStale()
                    if (data != null) elCache = data
                    ui.post { if (isCurrent(run)) renderElectricity() }
                } catch (e: Exception) { Log.w(TAG, "electricity", e) }
            }
            ui.postDelayed(this, 15L * 60_000L)
        }
    }

    /** Kevyt minuuttitick: päivittää "nyt"-vartin ja pillerin cachesta ilman verkkohakua. */
    private val electricityRenderTick = object : Runnable {
        override fun run() {
            if (!active.get()) return
            renderElectricity()
            ui.postDelayed(this, 60_000L)
        }
    }

    /** Onko huominen oikeasti julkaistu? Nord Poolin CET-kauppapäivä ulottuu Suomen
     *  aikaan klo 01:00 asti, joten "huomiselta" löytyy AINA 4 varttia (00:00–00:45)
     *  ennen varsinaista julkaisua. Vaaditaan siksi vähintään 90/96 varttia. */
    private fun hasTomorrow(data: ElectricityData?): Boolean {
        if (data == null) return false
        val c = Calendar.getInstance(HELSINKI_TZ, FI)
        c.add(Calendar.DAY_OF_YEAR, 1)
        val d = c.get(Calendar.DAY_OF_MONTH); val m = c.get(Calendar.MONTH) + 1; val y = c.get(Calendar.YEAR)
        var count = 0
        for (q in data.quarters) if (q.dayOfMonth == d && q.month == m && q.year == y) count++
        return count >= MIN_PUBLISHED_QUARTERS
    }

    private fun renderElectricity() {
        val data = elCache ?: return
        val now = System.currentTimeMillis()
        val cToday = Calendar.getInstance(HELSINKI_TZ, FI)
        val cTomorrow = Calendar.getInstance(HELSINKI_TZ, FI)
        cTomorrow.add(Calendar.DAY_OF_YEAR, 1)
        val today = buildDayPrices("Tänään", data, cToday, now)
        // CET-päivän vuotamat alkuvartit (≤4 kpl) eivät vielä ole "huominen julkaistu".
        var tomorrow = buildDayPrices("Huomenna", data, cTomorrow, now)
        if (tomorrow != null && tomorrow.quarters.size < MIN_PUBLISHED_QUARTERS) tomorrow = null
        var nowSnt: Float? = null
        today?.quarters?.forEach { if (it.isNow) nowSnt = it.snt }
        // priceSnt saa tyhjentyä kun kuluvalle vartille ei ole dataa — vanhaan hintaan
        // jääminen näyttäisi katkon aikana eilisen hinnan normaalin näköisenä.
        update { it.copy(elToday = today, elTomorrow = tomorrow, priceSnt = nowSnt) }
    }

    private fun buildDayPrices(label: String, data: ElectricityData, day: Calendar, nowMs: Long): DayPricesUi? {
        val d = day.get(Calendar.DAY_OF_MONTH); val m = day.get(Calendar.MONTH) + 1; val y = day.get(Calendar.YEAR)
        val list = ArrayList<QuarterUi>()
        var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE; var sum = 0.0
        var minAt = ""; var maxAt = ""
        for (q in data.quarters) {
            if (q.dayOfMonth != d || q.month != m || q.year != y) continue
            val snt = q.sntPerKwh.toFloat()
            val lab = String.format(FI, "%02d:%02d", q.hour, q.minute)
            val isNow = nowMs >= q.timestamp && nowMs < q.timestamp + 15L * 60_000L
            list.add(QuarterUi(lab, snt, isNow))
            sum += snt
            if (snt < min) { min = snt; minAt = lab }
            if (snt > max) { max = snt; maxAt = lab }
        }
        if (list.isEmpty()) return null
        return DayPricesUi(label, list, min, max, (sum / list.size).toFloat(), minAt, maxAt)
    }

    // ---------------- Aurinko ----------------

    private val sunTick = object : Runnable {
        override fun run() { if (!active.get()) return; computeSun(); ui.postDelayed(this, 60L * 60_000L) }
    }

    private fun computeSun() {
        try {
            val sm = SettingsManager.get()
            val lat: Double; val lon: Double
            if (sm.hasHomeCoordinates()) {
                lat = sm.homeLatitude; lon = sm.homeLongitude
            } else {
                val place = GeoPlace.forPlace(sm.homePlace)
                lat = place.latitude; lon = place.longitude
            }
            val astro = Astronomy.calculate(Date(), lat, lon, HELSINKI_TZ)
            update { it.copy(
                sunRise = Astronomy.formatSunrise(astro.sun) ?: "—",
                sunSet = Astronomy.formatSunset(astro.sun) ?: "—",
                dayLen = Astronomy.formatDayLength(astro.sun) ?: "",
                sunriseMin = astro.sun.sunriseMinutes,
                sunsetMin = astro.sun.sunsetMinutes,
                moonLabel = astro.moon.label ?: "",
                moonIllum = Math.round(astro.moon.illumination * 100.0).toInt(),
                moonPhase = astro.moon.phase.toFloat()
            ) }
        } catch (e: Exception) { Log.w(TAG, "sun", e) }
    }

    // ---------------- Anturit ----------------

    private val sensorStalenessTick = object : Runnable {
        override fun run() { if (!active.get()) return; renderSensors(); ui.postDelayed(this, 60_000L) }
    }

    /** Näyttää KAIKKI konfiguroidut slotit (MAC asetettu); vanhentunut/puuttuva näyte → "–"
     *  (näin sen voi myös nimetä heti). */
    private fun renderSensors() {
        try {
            val sm = SettingsManager.get()
            val repo = RuuviRepository.get(ctx)
            val now = System.currentTimeMillis()
            val slots = listOf(
                SettingsManager.RUUVI_SLOT_BEDROOM to R.string.sensor_label_bedroom,
                SettingsManager.RUUVI_SLOT_LIVINGROOM to R.string.sensor_label_livingroom,
                SettingsManager.RUUVI_SLOT_BALCONY to R.string.sensor_label_balcony
            )
            val list = ArrayList<SensorUi>()
            for ((slot, labelRes) in slots) {
                val mac = sm.getRuuviMac(slot) ?: continue
                val name = sm.getRuuviName(slot, ctx.getString(labelRes))
                val sample = repo.getLatest(mac)
                val tC = sample?.temperatureC()
                val fresh = sample != null && tC != null && now - sample.timestamp <= 15L * 60_000L
                if (sample != null && tC != null && fresh)
                    list.add(SensorUi(slot, name, tC.toFloat(), sample.humidityPct()?.toFloat()))
                else
                    list.add(SensorUi(slot, name, null, null))
            }
            update { it.copy(sensors = list) }
        } catch (e: Exception) { Log.w(TAG, "sensors", e) }
    }

    fun setSensorName(slot: String, name: String) {
        SettingsManager.get().setRuuviName(slot, name)
        renderSensors()
    }

    /** Asetussivu kutsuu kun anturikytkennät/nimet ovat muuttuneet. */
    fun refreshSensors() {
        renderSensors()
    }

    // ---------------- Varoitukset ----------------

    private val warningsTick = object : Runnable {
        override fun run() {
            if (!active.get()) return
            executeCurrent { try { org.jrs82.fsclock.WarningsRepository.get().refreshIfStale() } catch (_: Exception) {} }
            ui.postDelayed(this, 15L * 60_000L)
        }
    }

    private fun renderWarnings(warnings: List<WeatherWarning>?) {
        val list = ArrayList<WarnUi>()
        // Testivaroitus-testitila: injektoidaan keinotekoinen varoitus listan kärkeen.
        if (SettingsManager.get().activeTestMode == SettingsManager.TEST_WARNING) {
            list.add(WarnUi("TESTIVAROITUS", "Testitila — ei oikea varoitus", "",
                "Tämä varoitus on kytketty päälle asetusten testinapista ja poistuu itsestään 30 minuutissa.",
                Color(0xFFE6C32E)))
        }
        if (warnings != null) {
            for (w in warnings) {
                val validity = if (w.expiresMs > 0L) "voimassa ${fmtDay(w.expiresMs)} asti" else ""
                val event = if (w.event.isNullOrBlank()) "Säävaroitus" else w.event
                list.add(WarnUi(event, w.areaDesc ?: "", validity, w.description ?: "", Color(w.level.color)))
            }
        }
        update { it.copy(warnings = list) }
    }

    // ---------------- Juhlapyhä (tarkistus tunneittain 24/7) ----------------

    private val holidayTick = object : Runnable {
        override fun run() { if (!active.get()) return; computeHoliday(); ui.postDelayed(this, 60L * 60_000L) }
    }

    private fun computeHoliday() {
        try {
            val from = Calendar.getInstance(HELSINKI_TZ, FI)
            val hols = FinnishHolidays.upcoming(from, 1)
            if (hols.isEmpty()) { update { it.copy(holiday = null) }; return }
            val h = hols[0]
            val cal = Calendar.getInstance(HELSINKI_TZ, FI)
            cal.set(h.year, h.month - 1, h.day, 0, 0, 0)
            val wd = WD[cal.get(Calendar.DAY_OF_WEEK) - 1]
            update { it.copy(holiday = "${h.name} · $wd ${h.day}.${h.month}.") }
        } catch (e: Exception) { Log.w(TAG, "holiday", e) }
    }

    private fun fmtDay(ms: Long): String {
        val c = Calendar.getInstance(HELSINKI_TZ, FI); c.timeInMillis = ms
        return "${c.get(Calendar.DAY_OF_MONTH)}.${c.get(Calendar.MONTH) + 1}."
    }

    // ---------------- Sijainti (GPS + reverse-geokoodaus) ----------------

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun fetchLocation() {
        if (!hasLocationPermission()) return
        // GPS-seuranta pois päältä (tai käyttäjä asettanut kotipaikan käsin) → ei ylikirjoiteta.
        if (!SettingsManager.get().isFollowGpsLocation) return
        executeCurrent { run ->
            try {
                val loc = bestLocation() ?: return@executeCurrent
                // MML antaa Suomen kaupunginosan (Android Geocoder ei); Geocoder varalle.
                val pair = reverseMml(loc.latitude, loc.longitude)
                    ?: reverseGeocode(loc.latitude, loc.longitude)
                    ?: return@executeCurrent
                if (!isCurrent(run)) return@executeCurrent
                val city = pair.first; val district = pair.second
                val sm = SettingsManager.get()
                sm.setHomePlace(city)
                sm.setHomeCoordinates(loc.latitude, loc.longitude)
                GeoPlace.register(city, loc.latitude, loc.longitude)
                if (district.isNotEmpty()) GeoPlace.register("$city, $district", loc.latitude, loc.longitude)
                ui.post { if (isCurrent(run)) setLocation(city, district) }
            } catch (e: Exception) { Log.w(TAG, "location", e) }
        }
    }

    @SuppressLint("MissingPermission") // tarkistettu hasLocationPermission()
    private fun bestLocation(): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            } catch (_: Exception) {}
        }
        val cached = best
        if (cached != null && isUsable(cached)) return cached
        return requestFresh(lm)?.takeIf { isUsable(it) } ?: cached?.takeIf { isUsable(it) }
    }

    private fun isUsable(l: Location): Boolean {
        val ageOk = System.currentTimeMillis() - l.time < 60L * 60_000L
        val accOk = !l.hasAccuracy() || l.accuracy <= 1500f
        return ageOk && accOk
    }

    @SuppressLint("MissingPermission")
    private fun requestFresh(lm: LocationManager): Location? {
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Location>(1)
        try {
            lm.getCurrentLocation(provider, CancellationSignal(), ctx.mainExecutor) { loc ->
                holder[0] = loc; latch.countDown()
            }
            latch.await(8L, TimeUnit.SECONDS)
        } catch (e: Exception) { Log.w(TAG, "getCurrentLocation", e) }
        return holder[0]
    }

    /** MML-käänteisgeokoodaus (kaupunki + kaupunginosa). null jos ei avainta tai virhe. */
    private fun reverseMml(lat: Double, lon: Double): Pair<String, String>? {
        if (!org.jrs82.fsclock.MmlReverseGeocoder.isConfigured()) return null
        return try {
            val r = org.jrs82.fsclock.MmlReverseGeocoder.reverse(lat, lon) ?: return null
            val city = r[0]
            if (city.isBlank()) return null
            Log.w(TAG, "mml city=${r[0]} district=${r[1]}")
            Pair(city, r[1])
        } catch (e: Exception) { Log.w(TAG, "mml", e); null }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lon: Double): Pair<String, String>? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geo = Geocoder(ctx, FI)
            val addrs = geo.getFromLocation(lat, lon, 3) ?: return null
            if (addrs.isEmpty()) return null
            val a = addrs[0]
            var city = a.locality
            if (city.isNullOrBlank()) city = a.subAdminArea
            if (city.isNullOrBlank()) city = a.adminArea
            val cityName = if (city.isNullOrBlank()) return null else city
            // Etsi kaupunginosa kaikista tuloksista: subLocality on paras lähde.
            var district = ""
            for (a2 in addrs) {
                val cand = a2.subLocality
                if (!cand.isNullOrBlank() && !cand.equals(cityName, ignoreCase = true) && cand.none { it.isDigit() }) {
                    district = cand; break
                }
            }
            Pair(cityName, district)
        } catch (e: Exception) { Log.w(TAG, "geocode", e); null }
    }

    companion object {
        private const val TAG = "HomeDataController"
        /** Vartteja joiden jälkeen huominen katsotaan julkaistuksi (96:sta). */
        private const val MIN_PUBLISHED_QUARTERS = 90
        private val FI = Locale.Builder().setLanguage("fi").setRegion("FI").build()
        private val HELSINKI_TZ = TimeZone.getTimeZone("Europe/Helsinki")
        private val WD = arrayOf("su", "ma", "ti", "ke", "to", "pe", "la")
        private fun bandLabel(freqMhz: Int): String = when {
            freqMhz <= 0 -> ""
            freqMhz < 2500 -> "2.4 GHz"
            freqMhz < 5925 -> "5 GHz"
            else -> "6 GHz"
        }
    }
}
