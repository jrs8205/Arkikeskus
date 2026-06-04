package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.ElectricityRepository
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WeatherData
import org.jrs82.fsclock.WeatherIconView
import org.jrs82.fsclock.WeatherRepository
import org.jrs82.fsclock.WeatherTextFormatter
import org.jrs82.fsclock.ruuvi.RuuviRepository
import org.jrs82.fsclock.ruuvi.RuuviSample
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Compose-etusivun OIKEALLA DATALLA toimivat kortit (Vaihe 3). Kello + sää + pörssisähkö + anturit.
 * Data luetaan samoista repositoryista/avaimista kuin View-pohjainen [MobileMainActivity] (ei
 * tuplalogiikkaa): [WeatherRepository], [ElectricityRepository], [RuuviRepository], SharedPreferences.
 * Formatointi replikoi MobileMainActivityn vastaavat metodit, jotta arvot/yksiköt näkyvät samoin.
 * Sää-ikoni piirretään olemassa olevalla [WeatherIconView]-custom-viewillä AndroidView-interopin kautta.
 */

private val FI = Locale("fi", "FI")
private val HELSINKI: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

@Composable
internal fun HomeDashboard() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    // Kello (sekuntitarkkuus)
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    // Sää: seed viimeisimmästä haetusta + taustahaku
    var weather by remember { mutableStateOf(MobileMainActivity.sLastWeather) }
    LaunchedEffect(Unit) {
        val fresh = withContext(Dispatchers.IO) {
            try {
                WeatherRepository.get(context).fetchHome(MobileMainActivity.sLastWeather, false)
            } catch (e: Exception) {
                null
            }
        }
        if (fresh != null) {
            MobileMainActivity.sLastWeather = fresh
            weather = fresh
        }
    }

    // Pörssisähkö: hae jos cache vanha → tick pakottaa uudelleenluennan
    val elecRepo = remember { ElectricityRepository.get(context) }
    var elecTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                elecRepo.fetchIfStale()
            } catch (e: Exception) {
                // jätetään cachen varaan
            }
        }
        elecTick++
    }

    // Anturit: live-päivitys RuuviRepositoryn kuuntelijalla
    val ruuvi = remember { RuuviRepository.get(context) }
    var sensorTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        try {
            ruuvi.start()
        } catch (e: Exception) {
            // skannaus vaatii BLE-luvan; ilman sitä näytetään olemassa oleva snapshot
        }
    }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val listener = RuuviRepository.Listener { _, _ -> main.post { sensorTick++ } }
        ruuvi.addListener(listener)
        onDispose { ruuvi.removeListener(listener) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        ClockBlock(nowMs)
        Spacer(Modifier.height(8.dp))
        WeatherCard(context, prefs, weather)
        ElectricityCard(prefs, elecRepo, elecTick)
        SensorsCard(prefs, ruuvi, sensorTick)
    }
}

@Composable
private fun ClockBlock(nowMs: Long) {
    Text(
        text = formatClock(nowMs),
        fontSize = 64.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = formatDate(nowMs),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WeatherCard(context: Context, prefs: SharedPreferences, weather: WeatherData?) {
    val c = weather?.current
    if (c == null) {
        GenericCard("Sää", "Säätietoja haetaan…")
        return
    }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(displayPlace(prefs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (weather.fetchedAt > 0) {
                Text(
                    "Päivitetty klo " + hhmm(weather.fetchedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AndroidView(
                    factory = { ctx -> WeatherIconView(ctx) },
                    update = { it.setCondition(c.condition) },
                    modifier = Modifier.size(88.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        formatTemp(c.temperature),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        WeatherTextFormatter.label(context, c.condition),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val nowForecast = nearestForecastHour(weather, System.currentTimeMillis())
            val wind = if (!c.windSpeed.isNaN()) c.windSpeed else nowForecast?.windSpeed ?: Double.NaN
            val rain = if (!c.precip1h.isNaN()) c.precip1h else nowForecast?.precipitation ?: Double.NaN
            Row(modifier = Modifier.fillMaxWidth()) {
                QuickStat(R.drawable.mobile_ic_thermometer_24, "Tuntuu kuin",
                    if (c.feelsLike.isNaN()) "--" else formatTemp(c.feelsLike), Modifier.weight(1f))
                QuickStat(R.drawable.mobile_ic_wind_24, "Tuuli",
                    if (wind.isNaN()) "-- m/s" else one(wind) + " m/s", Modifier.weight(1f))
                QuickStat(R.drawable.mobile_ic_rain_24, "Sade 1h",
                    if (rain.isNaN()) "-- mm" else one(rain) + " mm", Modifier.weight(1f))
            }
            val details = weatherDetailLines(c)
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    details.joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val hours = remainingHours(weather)
            if (hours.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Loppupäivän ennuste", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    hours.forEach { h ->
                        HourChip(h)
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickStat(iconRes: Int, label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HourChip(h: WeatherData.Hour) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(hhmm(h.timestamp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        AndroidView(
            factory = { ctx -> WeatherIconView(ctx) },
            update = { it.setCondition(h.condition) },
            modifier = Modifier.size(42.dp).padding(vertical = 4.dp),
        )
        Text(formatTemp(h.temperature), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        ChipIconRow(R.drawable.mobile_ic_rain_24, if (h.precipitation.isNaN()) "--" else one(h.precipitation) + " mm")
        ChipIconRow(R.drawable.mobile_ic_wind_24, if (h.windSpeed.isNaN()) "--" else one(h.windSpeed) + " m/s")
    }
}

@Composable
private fun ChipIconRow(iconRes: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ElectricityCard(prefs: SharedPreferences, repo: ElectricityRepository, tick: Int) {
    val q = remember(tick) { repo.currentQuarter() }
    val notice = remember(tick) { cheapNotice(prefs, repo) }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Pörssisähkö", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (q == null) "Nykyistä varttihintaa ei ole vielä saatavilla"
                else String.format(FI, "Nyt klo %02d:%02d  %.3f c/kWh", q.hour, q.minute, q.sntPerKwh),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (notice != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SensorsCard(prefs: SharedPreferences, repo: RuuviRepository, tick: Int) {
    val sensors = remember(tick) { buildSensors(prefs, repo) }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Anturit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (sensors.isEmpty()) {
                Text(
                    "Ei määritettyjä Ruuvi-antureita",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                var i = 0
                while (i < sensors.size) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = if (i > 0) 8.dp else 0.dp)) {
                        SensorTile(sensors[i], Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        if (i + 1 < sensors.size) {
                            SensorTile(sensors[i + 1], Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    i += 2
                }
            }
        }
    }
}

@Composable
private fun SensorTile(entry: Pair<String, RuuviSample?>, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            entry.first,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val sample = entry.second
        if (sample == null) {
            Spacer(Modifier.height(8.dp))
            Text("odottaa", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Spacer(Modifier.height(6.dp))
            val t = sample.temperatureC()
            val h = sample.humidityPct()
            Text(
                if (t == null) "--" else one(t) + " °C",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (h == null) "kosteus --" else "kosteus " + Math.round(h) + " %",
                fontSize = 14.sp,
            )
            Text(
                ageText(sample.timestamp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GenericCard(title: String, note: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ===================== Data- ja formatointiapurit (replikoi MobileMainActivity) =====================

private fun displayPlace(prefs: SharedPreferences): String {
    val d = prefs.getString(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME, "") ?: ""
    if (d.trim().isNotEmpty()) return d.trim()
    return SettingsManager.get().homePlace
}

private fun nearestForecastHour(weather: WeatherData?, timestampMs: Long): WeatherData.Hour? {
    val hours = weather?.hours ?: return null
    var closest: WeatherData.Hour? = null
    var bestDelta = Long.MAX_VALUE
    for (hour in hours) {
        val delta = Math.abs(hour.timestamp - timestampMs)
        if (delta < bestDelta) {
            bestDelta = delta
            closest = hour
        }
    }
    return if (bestDelta <= 90L * 60_000L) closest else null
}

private fun remainingHours(weather: WeatherData?): List<WeatherData.Hour> {
    val hours = weather?.hours ?: return emptyList()
    if (hours.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val end = Calendar.getInstance(HELSINKI, FI).apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return hours.filter { it.timestamp >= now - 30L * 60_000L && it.timestamp < end }
}

private fun weatherDetailLines(c: WeatherData.Current): List<String> {
    val lines = ArrayList<String>()
    if (!c.windGust.isNaN()) lines.add("Puuska " + one(c.windGust) + " m/s")
    if (!c.humidity.isNaN()) lines.add("Kosteus " + Math.round(c.humidity) + " %")
    if (!c.cloudCover.isNaN()) lines.add("Pilvisyys " + Math.round(c.cloudCover) + " %")
    if (c.timestamp > 0) lines.add("FMI-havainto klo " + hhmm(c.timestamp))
    return lines
}

private fun cheapNotice(prefs: SharedPreferences, repo: ElectricityRepository): String? {
    if (!prefs.getBoolean(MobileThemeController.KEY_CHEAP_ELECTRICITY_NOTICE, true)) return null
    val threshold = cheapThreshold(prefs)
    val mode = prefs.getString(MobileThemeController.KEY_CHEAP_ELECTRICITY_MODE, MobileThemeController.CHEAP_MODE_ALL_DAY)
        ?: MobileThemeController.CHEAP_MODE_ALL_DAY
    val cal = Calendar.getInstance(HELSINKI, FI)
    val today = repo.dayQuarters(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    val checked = filterByMode(today, mode)
    if (checked.isEmpty() || !allBelow(checked, threshold)) return null
    val max = checked.maxByOrNull { it.sntPerKwh } ?: return null
    val scope = scopeText(mode)
    return String.format(
        FI,
        "Sähkö on halpaa %s: kaikki alle %.3f c/kWh, korkein %.3f c/kWh",
        scope, threshold, max.sntPerKwh,
    )
}

private fun cheapThreshold(prefs: SharedPreferences): Double {
    val raw = prefs.getString(MobileThemeController.KEY_CHEAP_ELECTRICITY_THRESHOLD, MobileThemeController.DEFAULT_CHEAP_ELECTRICITY_THRESHOLD)
        ?: return 5.0
    return try {
        Math.max(0.0, raw.trim().replace(',', '.').toDouble())
    } catch (e: NumberFormatException) {
        5.0
    }
}

private fun filterByMode(today: List<ElectricityData.Quarter>, mode: String): List<ElectricityData.Quarter> {
    val now = System.currentTimeMillis()
    return today.filter { q ->
        when (mode) {
            MobileThemeController.CHEAP_MODE_CURRENT -> q.timestamp <= now && q.timestamp + 15L * 60_000L > now
            MobileThemeController.CHEAP_MODE_REMAINING_DAY -> q.timestamp + 15L * 60_000L > now
            else -> true
        }
    }
}

private fun allBelow(quarters: List<ElectricityData.Quarter>, threshold: Double): Boolean {
    if (quarters.isEmpty()) return false
    return quarters.all { it.sntPerKwh < threshold }
}

private fun scopeText(mode: String): String = when (mode) {
    MobileThemeController.CHEAP_MODE_CURRENT -> "nyt"
    MobileThemeController.CHEAP_MODE_REMAINING_DAY -> "loppupäivän"
    else -> "koko päivän"
}

private fun buildSensors(prefs: SharedPreferences, repo: RuuviRepository): List<Pair<String, RuuviSample?>> {
    val sm = SettingsManager.get()
    val out = ArrayList<Pair<String, RuuviSample?>>()
    val assigned = ArrayList<String>()
    val bMac = sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BEDROOM)
    val lMac = sm.getRuuviMac(SettingsManager.RUUVI_SLOT_LIVINGROOM)
    val baMac = sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BALCONY)
    addAssigned(assigned, bMac)
    addAssigned(assigned, lMac)
    addAssigned(assigned, baMac)
    addSensor(out, homeSensorName(prefs, SettingsManager.RUUVI_SLOT_BEDROOM), bMac, repo)
    addSensor(out, homeSensorName(prefs, SettingsManager.RUUVI_SLOT_LIVINGROOM), lMac, repo)
    addSensor(out, homeSensorName(prefs, SettingsManager.RUUVI_SLOT_BALCONY), baMac, repo)
    var next = 1
    if (hasMac(bMac)) next = 2
    if (hasMac(lMac)) next = 3
    if (hasMac(baMac)) next = 4
    for ((mac, sample) in repo.snapshot().entries.sortedBy { it.key }) {
        if (assigned.contains(mac.uppercase(Locale.ROOT))) continue
        out.add("Anturi $next" to sample)
        next++
    }
    return out
}

private fun addSensor(out: MutableList<Pair<String, RuuviSample?>>, label: String, mac: String?, repo: RuuviRepository) {
    if (!hasMac(mac)) return
    out.add(label to repo.getLatest(mac))
}

private fun addAssigned(assigned: MutableList<String>, mac: String?) {
    if (hasMac(mac)) assigned.add(mac!!.trim().uppercase(Locale.ROOT))
}

private fun hasMac(mac: String?): Boolean = mac != null && mac.trim().isNotEmpty()

private fun homeSensorName(prefs: SharedPreferences, slot: String): String {
    val key = when (slot) {
        SettingsManager.RUUVI_SLOT_BEDROOM -> MobileThemeController.KEY_SENSOR_NAME_BEDROOM
        SettingsManager.RUUVI_SLOT_LIVINGROOM -> MobileThemeController.KEY_SENSOR_NAME_LIVINGROOM
        else -> MobileThemeController.KEY_SENSOR_NAME_BALCONY
    }
    val default = when (slot) {
        SettingsManager.RUUVI_SLOT_BEDROOM -> "Anturi 1"
        SettingsManager.RUUVI_SLOT_LIVINGROOM -> "Anturi 2"
        else -> "Anturi 3"
    }
    return prefs.getString(key, default) ?: default
}

private fun formatTemp(v: Double): String {
    if (v.isNaN()) return "-- C"
    return String.format(FI, "%.1f C", WeatherData.cleanZero(v))
}

private fun one(v: Double): String = String.format(FI, "%.1f", v)

private fun ageText(timestamp: Long): String {
    val ageMin = Math.max(0L, (System.currentTimeMillis() - timestamp) / 60_000L)
    return when {
        ageMin < 1L -> "nyt"
        ageMin < 60L -> "$ageMin min sitten"
        else -> "${ageMin / 60L} h sitten"
    }
}

private fun formatClock(ms: Long): String {
    val f = SimpleDateFormat("HH:mm:ss", FI)
    f.timeZone = HELSINKI
    return f.format(Date(ms))
}

private fun hhmm(ms: Long): String {
    val f = SimpleDateFormat("HH:mm", FI)
    f.timeZone = HELSINKI
    return f.format(Date(ms))
}

private fun formatDate(ms: Long): String {
    val f = SimpleDateFormat("EEEE d.M.yyyy", FI)
    f.timeZone = HELSINKI
    var d = f.format(Date(ms))
    if (d.isNotEmpty()) d = d.substring(0, 1).uppercase(FI) + d.substring(1)
    val cal = Calendar.getInstance(HELSINKI, FI)
    cal.timeInMillis = ms
    return "$d · viikko ${cal.get(Calendar.WEEK_OF_YEAR)}"
}
