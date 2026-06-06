package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.ElectricityRepository
import org.jrs82.fsclock.FinnishHolidays
import org.jrs82.fsclock.OpenMeteoData
import org.jrs82.fsclock.OpenMeteoRepository
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WeatherCondition
import org.jrs82.fsclock.WeatherData
import org.jrs82.fsclock.WarningsRepository
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

/** Etusivun sisääntuloanimaatio soitetaan vain KERRAN prosessin aikana, ei joka kerta kun
 *  etusivulle palataan. Aiemmin slide-animaatio toistui joka paluulla → "koko sivu hyppäsi". */
private var sHomeEntranceShown = false

@Composable
internal fun HomeDashboard(onOpenSection: (HomeSection) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    // Etusivun kortit ja niiden järjestys luetaan asetuksista. Kun käyttäjä muuttaa niitä
    // muokkausnäkymässä, SharedPreferences-kuuntelija päivittää listan → muutos näkyy kun palaa.
    var widgetTick by remember { mutableStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (isHomeWidgetKey(key)) widgetTick++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // Säävaroitukset näytetään etusivulla vain kun voimassa olevia varoituksia on (kuten 1.15.1).
    // Tarkistetaan synkronisesti repositoryn muistivälimuistista jo listaa rakennettaessa, jottei
    // tyhjä varoituskortti jätä turhaa väliä; kuuntelija päivittää kun varoitukset muuttuvat.
    val warningsRepo = remember { WarningsRepository.get() }
    var warningsTick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val l = WarningsRepository.Listener { main.post { warningsTick++ } }
        warningsRepo.addListener(l)
        warningsRepo.refreshIfStale()
        onDispose { warningsRepo.removeListener(l) }
    }
    val hasWarnings = remember(warningsTick) { warningsRepo.getLatest().isNotEmpty() }

    val widgets = remember(widgetTick, hasWarnings) {
        visibleHomeWidgetIds(prefs).filter { it != HomeWidget.WARNINGS.id || hasWarnings }
    }

    // Kevyt sisääntulo (OSA B / B6): pehmeä fade + slide — VAIN ensimmäisellä kerralla per prosessi.
    // shown alustetaan jo "näkyväksi" jos animaatio on jo soinut → AnimatedVisibility ei animoi
    // (vain false→true animoituu), joten etusivulle palatessa ei tule hyppäystä.
    var shown by remember { mutableStateOf(sHomeEntranceShown) }
    LaunchedEffect(Unit) {
        if (!sHomeEntranceShown) {
            shown = true
            sHomeEntranceShown = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        AnimatedVisibility(
            visible = shown,
            // Vain pehmeä häivähdys (läpinäkyvyys), EI slide- eikä korkeusanimaatiota → etusivu ei
            // "hyppää" ylös/alas avattaessa tai sivua vaihtaessa eikä kun kortit lataavat dataa.
            enter = fadeIn(spring()),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                if (widgets.isEmpty()) {
                    GenericCard(
                        "Etusivu on tyhjä",
                        "Kaikki kortit on piilotettu. Lisää niitä Muokkaa etusivua -painikkeesta.",
                    )
                } else {
                    widgets.forEachIndexed { i, id ->
                        if (i > 0) Spacer(Modifier.height(12.dp))
                        when (id) {
                            HomeWidget.CLOCK.id -> HomeClockWidget()
                            HomeWidget.HOLIDAY.id -> HomeHolidayWidget()
                            HomeWidget.WEATHER.id -> HomeWeatherWidget(prefs)
                            HomeWidget.ELECTRICITY.id -> HomeElectricityWidget(prefs)
                            HomeWidget.WARNINGS.id -> HomeWarningsCard()
                            HomeWidget.SENSORS.id -> HomeSensorsWidget(prefs)
                            HomeWidget.TRAFFIC.id -> HomeTrafficCard(onOpenTraffic = { onOpenSection(HomeSection.TRAFFIC_INCIDENTS) })
                            HomeWidget.NEWS.id -> HomeNewsCard(onOpenNews = { onOpenSection(HomeSection.NEWS) })
                            HomeWidget.TRANSIT.id -> HomeTransitCard(onOpenTransit = { onOpenSection(HomeSection.TRANSIT) })
                            else -> if (id.startsWith(HOME_NEWS_FEED_PREFIX)) {
                                HomeNewsSourceCard(id.substring(HOME_NEWS_FEED_PREFIX.length))
                            }
                        }
                    }
                }
            }
        }
    }
}

// Itsenäiset etusivun widgetit: kukin hoitaa oman datansa, jotta ne voi renderöidä missä
// järjestyksessä tahansa. Esityskerros (ClockBlock/HolidayCard/WeatherCard/…) on jaettu alla.

@Composable
private fun HomeClockWidget() {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }
    ClockBlock(nowMs)
}

@Composable
private fun HomeHolidayWidget() {
    var holidayLine by remember { mutableStateOf("") }
    val flagLine = remember {
        val f = nextOfficialFlagDay(Calendar.getInstance(HELSINKI, FI))
        if (f != null) "${f.name} ${calDayMonth(f.cal)}" else ""
    }
    LaunchedEffect(Unit) {
        holidayLine = withContext(Dispatchers.IO) {
            try {
                val ev = MobileHolidayProvider.next(Calendar.getInstance(HELSINKI, FI))
                if (ev != null) {
                    "${ev.name} ${formatIsoDayMonth(ev.date)}"
                } else {
                    val up = FinnishHolidays.upcoming(Calendar.getInstance(HELSINKI, FI), 1)
                    if (up.isEmpty()) "" else "${up[0].name} ${up[0].day}.${up[0].month}."
                }
            } catch (e: Exception) {
                ""
            }
        }
    }
    HolidayCard(holidayLine, flagLine)
}

@Composable
private fun HomeWeatherWidget(prefs: SharedPreferences) {
    val context = LocalContext.current
    val refresh = LocalRefreshTick.current
    var weather by remember { mutableStateOf(MobileMainActivity.sLastWeather) }
    LaunchedEffect(refresh) {
        val fresh = withContext(Dispatchers.IO) {
            try {
                WeatherRepository.get(context).fetchHome(MobileMainActivity.sLastWeather, refresh > 0)
            } catch (e: Exception) {
                null
            }
        }
        if (fresh != null) {
            MobileMainActivity.sLastWeather = fresh
            weather = fresh
        }
    }
    WeatherCard(context, prefs, weather)
}

@Composable
private fun HomeElectricityWidget(prefs: SharedPreferences) {
    val context = LocalContext.current
    val refresh = LocalRefreshTick.current
    val elecRepo = remember { ElectricityRepository.get(context) }
    var elecTick by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            try {
                elecRepo.fetchIfStale()
            } catch (e: Exception) {
                // jätetään cachen varaan
            }
        }
        elecTick++
    }
    ElectricityCard(prefs, elecRepo, elecTick)
}

@Composable
private fun HomeSensorsWidget(prefs: SharedPreferences) {
    val context = LocalContext.current
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
    SensorsCard(prefs, ruuvi, sensorTick)
}


@Composable
private fun ClockBlock(nowMs: Long) {
    // Pehmeä brändigradientti (OSA B / B6): kaksi sinisen sävyä (käyttäjän toive).
    val cs = MaterialTheme.colorScheme
    val arki = ArkiTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    listOf(arki.clockTop, arki.clockBottom),
                ),
            )
            .padding(vertical = 20.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatClock(nowMs),
                fontSize = 60.sp,
                fontWeight = FontWeight.Bold,
                color = cs.onPrimaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = formatDate(nowMs),
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onPrimaryContainer.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Kellon alle: seuraava pyhä + seuraava virallinen liputuspäivä omana siistinä korttina
 *  (label + arvo, ei vanhan UI:n leikkautuvaa pitkää keskitettyä rivitystä). */
@Composable
private fun HolidayCard(holidayLine: String, flagLine: String) {
    if (holidayLine.isEmpty() && flagLine.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (holidayLine.isNotEmpty()) {
                HolidayRow("Seuraava pyhä", holidayLine)
            }
            if (holidayLine.isNotEmpty() && flagLine.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
            }
            if (flagLine.isNotEmpty()) {
                HolidayRow("Seuraava liputuspäivä", flagLine)
            }
        }
    }
}

@Composable
private fun HolidayRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

// Seuraava virallinen liputuspäivä — laskettu paikallisesti (replikoi MobileMainActivityn logiikan).
private data class FlagDayInfo(val name: String, val cal: Calendar)

private fun nextOfficialFlagDay(from: Calendar): FlagDayInfo? {
    val fromKey = dateKeyCal(from)
    var best: FlagDayInfo? = null
    for (year in from.get(Calendar.YEAR)..from.get(Calendar.YEAR) + 1) {
        for (d in officialFlagDays(year)) {
            if (dateKeyCal(d.cal) < fromKey) continue
            if (best == null || dateKeyCal(d.cal) < dateKeyCal(best.cal)) best = d
        }
    }
    return best
}

private fun officialFlagDays(year: Int): List<FlagDayInfo> = listOf(
    FlagDayInfo("Kalevalan päivä, suomalaisen kulttuurin päivä", flagCal(year, Calendar.FEBRUARY, 28)),
    FlagDayInfo("Vappu, suomalaisen työn päivä", flagCal(year, Calendar.MAY, 1)),
    FlagDayInfo("Äitienpäivä", nthWeekday(year, Calendar.MAY, Calendar.SUNDAY, 2)),
    FlagDayInfo("Puolustusvoimain lippujuhlan päivä", flagCal(year, Calendar.JUNE, 4)),
    FlagDayInfo("Juhannuspäivä, Suomen lipun päivä", firstWeekdayOnOrAfter(year, Calendar.JUNE, 20, Calendar.SATURDAY)),
    FlagDayInfo("Isänpäivä", nthWeekday(year, Calendar.NOVEMBER, Calendar.SUNDAY, 2)),
    FlagDayInfo("Itsenäisyyspäivä", flagCal(year, Calendar.DECEMBER, 6)),
)

private fun flagCal(year: Int, month: Int, day: Int): Calendar {
    val c = Calendar.getInstance(HELSINKI, FI)
    c.clear()
    c.set(year, month, day)
    return c
}

private fun nthWeekday(year: Int, month: Int, weekday: Int, nth: Int): Calendar {
    val c = flagCal(year, month, 1)
    var seen = 0
    while (c.get(Calendar.MONTH) == month) {
        if (c.get(Calendar.DAY_OF_WEEK) == weekday && ++seen == nth) return c
        c.add(Calendar.DAY_OF_MONTH, 1)
    }
    return flagCal(year, month, 1)
}

private fun firstWeekdayOnOrAfter(year: Int, month: Int, day: Int, weekday: Int): Calendar {
    val c = flagCal(year, month, day)
    while (c.get(Calendar.DAY_OF_WEEK) != weekday) c.add(Calendar.DAY_OF_MONTH, 1)
    return c
}

private fun dateKeyCal(c: Calendar): Int =
    c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)

private fun calDayMonth(c: Calendar): String =
    "${c.get(Calendar.DAY_OF_MONTH)}.${c.get(Calendar.MONTH) + 1}."

private fun formatIsoDayMonth(date: String?): String {
    if (date == null || date.length != 10) return ""
    return try {
        val day = date.substring(8, 10).toInt()
        val month = date.substring(5, 7).toInt()
        "$day.$month."
    } catch (e: NumberFormatException) {
        date
    }
}

@Composable
private fun WeatherCard(context: Context, prefs: SharedPreferences, weather: WeatherData?) {
    val c = weather?.current
    if (c == null) {
        GenericCard("Sää", "Säätietoja haetaan…")
        return
    }
    val arki = ArkiTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = arki.weatherContainer,
            contentColor = arki.onWeatherContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(displayPlace(prefs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (weather.fetchedAt > 0) {
                Text(
                    "Päivitetty klo " + hhmm(weather.fetchedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = arki.onWeatherContainer.copy(alpha = 0.7f),
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
                        color = arki.weatherAccent,
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
                    if (c.feelsLike.isNaN()) "--" else formatTemp(c.feelsLike), arki.weatherSunny, Modifier.weight(1f))
                QuickStat(R.drawable.mobile_ic_wind_24, "Tuuli",
                    if (wind.isNaN()) "-- m/s" else one(wind) + " m/s", MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                QuickStat(R.drawable.mobile_ic_rain_24, "Sade 1h",
                    if (rain.isNaN()) "-- mm" else one(rain) + " mm", arki.weatherRain, Modifier.weight(1f))
            }
            val details = weatherDetailLines(c)
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    details.joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = arki.onWeatherContainer.copy(alpha = 0.75f),
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
private fun QuickStat(iconRes: Int, label: String, value: String, tint: Color, modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            label,
            fontSize = 11.sp,
            color = LocalContentColor.current.copy(alpha = 0.7f),
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
    val threshold = remember(tick) { cheapThreshold(prefs) }
    val arki = ArkiTheme.colors
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Pörssisähkö", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (q == null) {
                Text("Nykyistä varttihintaa ei ole vielä saatavilla", style = MaterialTheme.typography.bodyLarge)
            } else {
                val level = priceLevel(q.sntPerKwh, threshold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PricePill(level)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        String.format(FI, "%.3f c/kWh", q.sntPerKwh),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = priceAccent(level, arki),
                    )
                }
                Text(
                    String.format(FI, "Nyt klo %02d:%02d", q.hour, q.minute),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (notice != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = arki.onPriceCheapContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(arki.priceCheapContainer)
                        .padding(12.dp),
                )
            }
        }
    }
}

private enum class PriceLevel { CHEAP, NORMAL, EXPENSIVE }

/** Yli tämän (c/kWh, ALV 0 %) hinta luokitellaan kalliiksi (punainen). */
private const val EXPENSIVE_THRESHOLD = 15.0

private fun priceLevel(snt: Double, cheapThreshold: Double): PriceLevel = when {
    snt.isNaN() -> PriceLevel.NORMAL
    snt < cheapThreshold -> PriceLevel.CHEAP
    snt > EXPENSIVE_THRESHOLD -> PriceLevel.EXPENSIVE
    else -> PriceLevel.NORMAL
}

private fun priceAccent(level: PriceLevel, arki: ArkiColors): Color = when (level) {
    PriceLevel.CHEAP -> arki.priceCheap
    PriceLevel.NORMAL -> arki.priceNormal
    PriceLevel.EXPENSIVE -> arki.priceExpensive
}

@Composable
private fun PricePill(level: PriceLevel) {
    val arki = ArkiTheme.colors
    val bg: Color
    val fg: Color
    val label: String
    when (level) {
        PriceLevel.CHEAP -> { bg = arki.priceCheapContainer; fg = arki.onPriceCheapContainer; label = "Halpaa" }
        PriceLevel.NORMAL -> { bg = arki.priceNormalContainer; fg = arki.onPriceNormalContainer; label = "Normaali" }
        PriceLevel.EXPENSIVE -> { bg = arki.priceExpensiveContainer; fg = arki.onPriceExpensiveContainer; label = "Kallista" }
    }
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SensorsCard(prefs: SharedPreferences, repo: RuuviRepository, tick: Int) {
    val sensors = remember(tick) { buildSensors(prefs, repo) }
    Card(modifier = Modifier.fillMaxWidth()) {
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
    val arki = ArkiTheme.colors
    val sample = entry.second
    val temp = sample?.temperatureC()
    val tempColor = arki.forTemperature(temp)
    // Lämpötilan mukainen hento sävytys (B2: kylmä→lämmin). Ilman näytettä neutraali.
    val tileBg = if (sample != null && temp != null) tempColor.copy(alpha = 0.14f)
    else MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tileBg)
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
        if (sample == null) {
            Spacer(Modifier.height(8.dp))
            Text("odottaa", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Spacer(Modifier.height(6.dp))
            val h = sample.humidityPct()
            Text(
                if (temp == null) "--" else one(temp) + " °C",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = tempColor,
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ===================== Anturit-sektio (koko näkymä) =====================

@Composable
internal fun SensorsSection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val ruuvi = remember { RuuviRepository.get(context) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        try {
            ruuvi.start()
        } catch (e: Exception) {
            // skannaus vaatii BLE-luvan
        }
    }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val listener = RuuviRepository.Listener { _, _ -> main.post { tick++ } }
        ruuvi.addListener(listener)
        onDispose { ruuvi.removeListener(listener) }
    }
    val sensors = remember(tick) { buildSensors(prefs, ruuvi) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Anturit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
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

// ===================== Pörssisähkö-sektio (koko näkymä) =====================

@Composable
internal fun ElectricitySection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val repo = remember { ElectricityRepository.get(context) }
    val threshold = remember { cheapThreshold(prefs) }
    val refresh = LocalRefreshTick.current
    var dayOffset by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            try {
                repo.fetchIfStale()
            } catch (e: Exception) {
                // cachen varaan
            }
        }
        tick++
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Pörssisähkö", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ElecTab("Tänään", dayOffset == 0, Modifier.weight(1f)) { dayOffset = 0 }
            ElecTab("Huomenna", dayOffset == 1, Modifier.weight(1f)) { dayOffset = 1 }
            ElecTab("Vertailu", dayOffset == 2, Modifier.weight(1f)) { dayOffset = 2 }
        }
        Spacer(Modifier.height(14.dp))
        if (dayOffset == 2) {
            ElectricityCompare(context)
        } else {
            ElectricityDay(repo, threshold, dayOffset, tick)
        }
    }
}

@Composable
private fun ElecTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        textAlign = TextAlign.Center,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
private fun ElectricityDay(repo: ElectricityRepository, threshold: Double, dayOffset: Int, tick: Int) {
    val arki = ArkiTheme.colors
    val data = remember(tick) { repo.peek() }
    val quarters = remember(tick, dayOffset) {
        val c = Calendar.getInstance(HELSINKI, FI)
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        repo.dayQuarters(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
    val current = remember(tick, dayOffset) { if (dayOffset == 0) repo.currentQuarter() else null }

    if (dayOffset == 1 && (quarters == null || quarters.size < 96)) {
        Text(
            "Huomisen hinnat päivittyvät noin klo 14:30 joka päivä.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (quarters.isNullOrEmpty()) {
        Text(
            if (dayOffset == 0) "Tämän päivän varttihintoja ei ole saatavilla."
            else "Huomisen hinnat päivittyvät noin klo 14:30 joka päivä.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val min = quarters.minByOrNull { it.sntPerKwh }
    val max = quarters.maxByOrNull { it.sntPerKwh }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (dayOffset == 0 && current != null) {
                val level = priceLevel(current.sntPerKwh, threshold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PricePill(level)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        String.format(FI, "Nyt klo %02d:%02d  %.3f c/kWh", current.hour, current.minute, current.sntPerKwh),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = priceAccent(level, arki),
                    )
                }
            } else {
                Text(
                    if (dayOffset == 0) "Tänään" else "Huomenna",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (min != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    String.format(FI, "Halvinta klo %02d:%02d  %.3f c/kWh", min.hour, min.minute, min.sntPerKwh),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = arki.priceCheap,
                )
            }
            if (max != null) {
                Text(
                    String.format(FI, "Kalleinta klo %02d:%02d  %.3f c/kWh", max.hour, max.minute, max.sntPerKwh),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = arki.priceExpensive,
                )
            }
            if (data != null && data.fetchedAt > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Päivitetty klo " + hhmm(data.fetchedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Lähde: Elering/Nord Pool. Hinnat ALV 0 %.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            quarters.take(96).forEach { q ->
                QuarterRow(q, isCurrent = current != null && q.timestamp == current.timestamp, threshold = threshold)
            }
        }
    }
}

private data class CompareRowData(val label: String, val value: Double, val highlight: Boolean, val isSection: Boolean)

@Composable
private fun ElectricityCompare(context: Context) {
    var rows by remember { mutableStateOf<List<CompareRowData>?>(null) }
    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            val out = ArrayList<CompareRowData>()
            try {
                val now = Calendar.getInstance(HELSINKI, FI)
                val year = now.get(Calendar.YEAR)
                val curMonth = now.get(Calendar.MONTH) + 1
                val prev = ElectricityAverages.previousYearAverage(context, true)
                if (prev != null) out.add(CompareRowData("${year - 1} keskihinta", prev.avgSntPerKwh, true, false))
                out.add(CompareRowData("$year kuukausikeskiarvot", Double.NaN, false, true))
                for (m in 1..curMonth) {
                    val ma = ElectricityAverages.monthAverage(context, year, m, true) ?: continue
                    val label = MONTHS_FI_ELEC[m - 1] + if (m == curMonth) " (kesken)" else ""
                    out.add(CompareRowData(label, ma.avgSntPerKwh, false, false))
                }
            } catch (e: Exception) {
                // näytetään mitä saatiin
            }
            out
        }
    }
    Text(
        "Pörssisähkön keskihinnat (ALV 0 %). Lähde: Elering/Nord Pool.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    val r = rows
    when {
        r == null -> Text(
            "Haetaan keskiarvoja…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        r.none { !it.isSection } -> Text(
            "Keskiarvoja ei ole vielä saatavilla. Päivitä uudelleen verkkoyhteydellä.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                r.forEach { CompareRowView(it) }
            }
        }
    }
}

@Composable
private fun CompareRowView(row: CompareRowData) {
    if (row.isSection) {
        Text(
            row.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.label,
            modifier = Modifier.weight(1f),
            fontWeight = if (row.highlight) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            String.format(FI, "%.3f c/kWh", row.value),
            color = if (row.highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val MONTHS_FI_ELEC = arrayOf(
    "Tammikuu", "Helmikuu", "Maaliskuu", "Huhtikuu", "Toukokuu", "Kesäkuu",
    "Heinäkuu", "Elokuu", "Syyskuu", "Lokakuu", "Marraskuu", "Joulukuu",
)

@Composable
private fun QuarterRow(q: ElectricityData.Quarter, isCurrent: Boolean, threshold: Double) {
    val arki = ArkiTheme.colors
    val level = priceLevel(q.sntPerKwh, threshold)
    val accent = priceAccent(level, arki)
    val weight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            String.format(FI, "%02d:%02d", q.hour, q.minute),
            modifier = Modifier.width(64.dp),
            color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
            fontWeight = weight,
        )
        Text(
            String.format(FI, "%.3f c/kWh", q.sntPerKwh),
            color = accent,
            fontWeight = weight,
        )
    }
}

// ===================== Sää-ennuste-sektio (FMI + Open-Meteo, päivätabit) =====================

@Composable
internal fun ForecastSection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val place = remember { displayPlace(prefs) }
    val refresh = LocalRefreshTick.current
    var weather by remember { mutableStateOf(MobileMainActivity.sLastWeather) }
    var openMeteo by remember { mutableStateOf(OpenMeteoRepository.get(context).peek(place)) }
    LaunchedEffect(refresh) {
        val w = withContext(Dispatchers.IO) {
            try {
                WeatherRepository.get(context).fetchHome(MobileMainActivity.sLastWeather, refresh > 0)
            } catch (e: Exception) {
                null
            }
        }
        if (w != null) {
            MobileMainActivity.sLastWeather = w
            weather = w
        }
        val om = withContext(Dispatchers.IO) {
            try {
                OpenMeteoRepository.get(context).fetch(place, refresh > 0)
            } catch (e: Exception) {
                OpenMeteoRepository.get(context).peek(place)
            }
        }
        if (om != null) openMeteo = om
    }

    val w = weather
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Sää-ennuste", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(place, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (w == null || w.hours.isEmpty()) {
            Text(
                "Ennustetta ei ole vielä ladattu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val days = remember(w) { forecastDayKeys(w) }
            var selectedDay by remember(w) { mutableStateOf(days.firstOrNull() ?: 0) }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                days.forEach { key ->
                    DayTab(dayLabel(key), key == selectedDay) { selectedDay = key }
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            val rows = w.hours.filter { dayKey(it.timestamp) == selectedDay }
            if (rows.isEmpty()) {
                Text("Tälle päivälle ei löytynyt tuntirivejä.", style = MaterialTheme.typography.bodyMedium)
            } else {
                rows.forEach { h ->
                    ForecastRow(context, h, bestOpenMeteoHourAt(openMeteo, h.timestamp))
                }
            }
        }
    }
}

@Composable
private fun DayTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
private fun ForecastRow(context: Context, h: WeatherData.Hour, om: OpenMeteoData.Hour?) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(hhmm(h.timestamp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            ProviderRow(
                "FMI", h.condition, formatTemp(h.temperature),
                WeatherTextFormatter.shortLabel(context, h.condition), fmiForecastStats(h),
            )
            if (om != null) {
                ProviderRow(
                    "Open-Meteo", om.condition, formatNullableTemp(om.temperature),
                    WeatherTextFormatter.shortLabel(context, om.condition), openMeteoForecastStats(om),
                )
            } else {
                Text(
                    "Open-Meteo ei saatavilla tälle tunnille",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(source: String, condition: WeatherCondition, temp: String, label: String, stats: List<ForecastStat>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            factory = { ctx -> WeatherIconView(ctx) },
            update = { it.setCondition(condition) },
            modifier = Modifier.size(42.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("$source  $temp  $label", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    stats.forEachIndexed { i, s ->
                        if (i > 0) Spacer(Modifier.width(14.dp))
                        StatChip(s)
                    }
                }
            }
        }
    }
}

/** Värillinen sää-stat-siru (kuten etusivun QuickStat): värikoodattu ikoni + arvo. */
@Composable
private fun StatChip(stat: ForecastStat) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(stat.icon),
            contentDescription = null,
            tint = statColor(stat.kind),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(stat.value, fontSize = 13.sp)
    }
}

// ===================== Uutiset-sektio (RSS + kuvat) =====================

@Composable
internal fun NewsSection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val refresh = LocalRefreshTick.current
    var items by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(refresh) {
        loading = true
        val fresh = withContext(Dispatchers.IO) {
            try {
                RssRepository.get().fetchEnabled(prefs, refresh > 0)
            } catch (e: Exception) {
                null
            }
        }
        items = fresh ?: emptyList()
        loading = false
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Uutiset", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Text("Haetaan uutisia…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.isEmpty() -> Text(
                "Ei uutisia. Tarkista uutislähteet asetuksista.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val shown = items.take(50)
                val note = if (shown.size < items.size) "Näytetään ${shown.size} uusinta uutista" else "${items.size} uutista"
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                shown.forEach { NewsRow(context, it) }
            }
        }
    }
}

@Composable
private fun NewsRow(context: Context, item: NewsItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { openUrl(context, item.link) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.mobile_ic_news_placeholder)
                }
            },
            update = { ImageLoader.get().load(item.imageUrl, it, R.drawable.mobile_ic_news_placeholder) },
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    item.feedName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = ArkiTheme.colors.newsAccent,
                )
                if (item.pubTimeMs > 0) {
                    Text(
                        " · " + ageText(item.pubTimeMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun openUrl(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e2: Exception) {
            // ei selainta
        }
    }
}

private fun forecastDayKeys(w: WeatherData): List<Int> {
    val out = ArrayList<Int>()
    for (h in w.hours) {
        val key = dayKey(h.timestamp)
        if (!out.contains(key)) out.add(key)
        if (out.size >= 7) break
    }
    return out
}

private fun dayKey(timestamp: Long): Int {
    val c = Calendar.getInstance(HELSINKI, FI)
    c.timeInMillis = timestamp
    return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}

private fun dayLabel(key: Int): String {
    val year = key / 10000
    val month = (key / 100) % 100
    val day = key % 100
    val c = Calendar.getInstance(HELSINKI, FI)
    c.clear()
    c.set(year, month - 1, day)
    val f = SimpleDateFormat("EEE d.M.", FI)
    f.timeZone = HELSINKI
    var label = f.format(c.time)
    if (label.isNotEmpty()) label = label.substring(0, 1).uppercase(FI) + label.substring(1)
    return label
}

private fun bestOpenMeteoHourAt(om: OpenMeteoData?, timestamp: Long): OpenMeteoData.Hour? {
    val hours = om?.hours ?: return null
    var best: OpenMeteoData.Hour? = null
    var bestDiff = Long.MAX_VALUE
    for (h in hours) {
        val diff = Math.abs(h.timestamp - timestamp)
        if (diff < bestDiff) {
            bestDiff = diff
            best = h
        }
    }
    return if (best == null || bestDiff > 31L * 60_000L) null else best
}

private enum class StatKind { RAIN, WIND, GUST, HUMIDITY }

private data class ForecastStat(val kind: StatKind, val icon: Int, val value: String)

@Composable
private fun statColor(kind: StatKind): Color = when (kind) {
    StatKind.RAIN -> ArkiTheme.colors.weatherRain
    StatKind.WIND -> MaterialTheme.colorScheme.secondary
    StatKind.GUST -> MaterialTheme.colorScheme.tertiary
    StatKind.HUMIDITY -> ArkiTheme.colors.weatherFrost
}

private fun fmiForecastStats(h: WeatherData.Hour): List<ForecastStat> {
    val out = ArrayList<ForecastStat>()
    if (!h.precipitation.isNaN()) out.add(ForecastStat(StatKind.RAIN, R.drawable.mobile_ic_rain_24, one(h.precipitation) + " mm"))
    if (!h.windSpeed.isNaN()) out.add(ForecastStat(StatKind.WIND, R.drawable.mobile_ic_wind_24, one(h.windSpeed) + " m/s"))
    if (!h.windGust.isNaN()) out.add(ForecastStat(StatKind.GUST, R.drawable.mobile_ic_wind_24, "puuska " + one(h.windGust) + " m/s"))
    return out
}

private fun openMeteoForecastStats(h: OpenMeteoData.Hour): List<ForecastStat> {
    val out = ArrayList<ForecastStat>()
    h.precipitation?.let { out.add(ForecastStat(StatKind.RAIN, R.drawable.mobile_ic_rain_24, one(it) + " mm")) }
    h.windSpeed?.let { out.add(ForecastStat(StatKind.WIND, R.drawable.mobile_ic_wind_24, one(it) + " m/s")) }
    h.windGust?.let { out.add(ForecastStat(StatKind.GUST, R.drawable.mobile_ic_wind_24, "puuska " + one(it) + " m/s")) }
    h.humidity?.let { out.add(ForecastStat(StatKind.HUMIDITY, R.drawable.mobile_ic_droplet_24, Math.round(it).toString() + " %")) }
    return out
}

private fun formatNullableTemp(v: Double?): String = if (v == null) "-- C" else formatTemp(v)

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
