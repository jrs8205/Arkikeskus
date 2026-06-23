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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.AlmanakkaClient
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
private var sHomeWeatherKey: String? = null
private var sHomeWeather: WeatherData? = null
private var sForecastWeatherKey: String? = null
private var sForecastWeather: WeatherData? = null
private var sForecastOpenMeteoKey: String? = null
private var sForecastOpenMeteo: OpenMeteoData? = null
// Sähkövertailun (Vertailu-välilehti) prosessivälimuisti: keskiarvot ovat kalliita laskea (vuosi-/
// kuukausiaggregaatit) → ei lasketa uudelleen joka välilehtivaihdossa, vain kun refresh muuttuu.
private var sElectricityCompareTick = -1
private var sElectricityCompareRows: List<CompareRowData>? = null

internal fun invalidateHomeWeatherCache() {
    if (sHomeWeatherKey == currentHomeWeatherKey()) return
    sHomeWeatherKey = null
    sHomeWeather = null
    WeatherCache.last = null
}

private fun currentHomeWeatherKey(): String {
    val sm = SettingsManager.get()
    val place = sm.homePlace.trim().lowercase(Locale.ROOT)
    return if (sm.hasHomeCoordinates()) {
        "$place|${String.format(Locale.ROOT, "%.3f", sm.homeLatitude)}|" +
            String.format(Locale.ROOT, "%.3f", sm.homeLongitude)
    } else {
        "$place|null|null"
    }
}

@Composable
internal fun HomeDashboard(onOpenSection: (HomeSection) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    // Etusivun kortit ja niiden järjestys luetaan asetuksista. Kun käyttäjä muuttaa niitä
    // muokkausnäkymässä, SharedPreferences-kuuntelija päivittää listan → muutos näkyy kun palaa.
    var widgetTick by remember { mutableStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // Myös uutislähteiden lisäys/poisto päivittää korttilistan (per-lähde-kortit ilmestyvät/katoavat).
            if (isHomeWidgetKey(key) || isHomeNewsListKey(key)) widgetTick++
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
                        // key(id) → kortin muistettu tila (esim. uutisten/lähtöjen lataustila) sitoutuu
                        // kortin id:hen eikä listapaikkaan, joten korttia siirrettäessä/poistettaessa tila
                        // seuraa oikeaa korttia eikä mene väärälle naapurille.
                        key(id) {
                            if (i > 0) Spacer(Modifier.height(12.dp))
                            when (id) {
                                HomeWidget.CLOCK.id -> HomeClockWidget()
                                HomeWidget.HOLIDAY.id -> HomeHolidayWidget()
                                HomeWidget.WEATHER.id -> HomeWeatherWidget(prefs)
                                HomeWidget.ELECTRICITY.id -> HomeElectricityWidget(prefs, onOpenElectricity = { onOpenSection(HomeSection.ELECTRICITY) })
                                HomeWidget.WARNINGS.id -> HomeWarningsCard()
                                HomeWidget.SENSORS.id -> HomeSensorsWidget(prefs)
                                HomeWidget.TRAFFIC.id -> HomeTrafficCard(onOpenTraffic = { onOpenSection(HomeSection.TRAFFIC_INCIDENTS) })
                                HomeWidget.NEWS.id -> HomeNewsCard(onOpenNews = { onOpenSection(HomeSection.NEWS) })
                                HomeWidget.NEWS_FOREIGN.id -> HomeForeignNewsCard(onOpenForeign = { onOpenSection(HomeSection.NEWS_FOREIGN) })
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
}

// Itsenäiset etusivun widgetit: kukin hoitaa oman datansa, jotta ne voi renderöidä missä
// järjestyksessä tahansa. Esityskerros (ClockBlock/HolidayCard/WeatherCard/…) on jaettu alla.

// Nimipäivän prosessivälimuisti (almanakka-API): haetaan kerran/päivä, ei joka etusivun avauksella.
private var sNameDayKey = -1
private var sNameDay = ""

@Composable
private fun HomeClockWidget() {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }
    // Päivän nimipäivät (Yliopiston almanakkatoimisto). Offline/virhe → tyhjä (pilli + maininta piiloon).
    val dayMd = remember(nowMs / 3_600_000L) {
        val c = Calendar.getInstance(HELSINKI, FI)
        c.get(Calendar.MONTH) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }
    var nameDay by remember { mutableStateOf(if (sNameDayKey == dayMd) sNameDay else "") }
    LaunchedEffect(dayMd) {
        if (sNameDayKey == dayMd && sNameDay.isNotEmpty()) {
            nameDay = sNameDay
            return@LaunchedEffect
        }
        val c = Calendar.getInstance(HELSINKI, FI)
        val names = withContext(Dispatchers.IO) {
            try {
                AlmanakkaClient.fetchFinnishNames(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
            } catch (e: Exception) {
                ""
            }
        }
        if (names.isNotEmpty()) {
            sNameDay = names
            sNameDayKey = dayMd
            nameDay = names
        }
    }
    ClockBlock(nowMs, nameDay)
}

@Composable
private fun HomeHolidayWidget() {
    val lifecycleOwner = LocalLifecycleOwner.current
    var dayKey by remember {
        mutableIntStateOf(dateKeyCal(Calendar.getInstance(HELSINKI, FI)))
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = Calendar.getInstance(HELSINKI, FI)
                dayKey = dateKeyCal(now)
                val nextMidnight = (now.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                delay((nextMidnight.timeInMillis - now.timeInMillis).coerceAtLeast(1000L))
            }
        }
    }
    val flagEntry = remember(dayKey) {
        val h = FinnishHolidays.nextFlagDay(Calendar.getInstance(HELSINKI, FI))
        if (h != null) holidayEntryFromCal(h.name, flagCal(h.year, h.month - 1, h.day)) else null
    }
    var holidayEntry by remember(dayKey) { mutableStateOf<HolidayEntry?>(null) }
    LaunchedEffect(dayKey) {
        holidayEntry = withContext(Dispatchers.IO) {
            try {
                val ev = MobileHolidayProvider.next(Calendar.getInstance(HELSINKI, FI))
                if (ev != null) {
                    val cal = isoToCal(ev.date)
                    if (cal != null) holidayEntryFromCal(ev.name, cal)
                    else HolidayEntry(ev.name, formatIsoDayMonth(ev.date), "")
                } else {
                    val up = FinnishHolidays.upcoming(Calendar.getInstance(HELSINKI, FI), 1)
                    if (up.isEmpty()) {
                        null
                    } else {
                        val now = Calendar.getInstance(HELSINKI, FI)
                        var cal = flagCal(now.get(Calendar.YEAR), up[0].month - 1, up[0].day)
                        if (dateKeyCal(cal) < dateKeyCal(now)) {
                            cal = flagCal(now.get(Calendar.YEAR) + 1, up[0].month - 1, up[0].day)
                        }
                        holidayEntryFromCal(up[0].name, cal)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    HolidayCard(holidayEntry, flagEntry)
}

@Composable
private fun HomeWeatherWidget(prefs: SharedPreferences) {
    val context = LocalContext.current
    val refresh = LocalRefreshTick.current
    val settingsRevision = LocalHomeDataRevision.current
    val weatherKey = remember(settingsRevision) { currentHomeWeatherKey() }
    var weather by remember(weatherKey) {
        val seed = if (sHomeWeatherKey == weatherKey) sHomeWeather else null
        mutableStateOf(seed)
    }
    LaunchedEffect(refresh, weatherKey) {
        val weatherSeed = if (sHomeWeatherKey == weatherKey) sHomeWeather else null
        if (weatherSeed == null) weather = null
        val fresh = withContext(Dispatchers.IO) {
            val w = try {
                WeatherRepository.get(context).fetchHome(weatherSeed, refresh > 0)
            } catch (e: Exception) {
                null
            }
            // OpenMeteo-fallback: täytä kosteus/pilvisyys jos FMI-livehavainnosta puuttuvat.
            if (w != null) fillCurrentHumidityCloud(context, w, refresh > 0)
            w
        }
        if (fresh != null) {
            WeatherCache.last = fresh
            sHomeWeatherKey = weatherKey
            sHomeWeather = fresh
            weather = fresh
            // Tallenna onnistuneen FMI-haun aikaleima → "Viimeisin sääpäivitys" (asetukset) pysyy ajan tasalla.
            if (fresh.fetchedAt > 0L) SettingsManager.get().setLastSuccessfulFmiUpdate(fresh.fetchedAt)
        }
    }
    WeatherCard(context, prefs, weather)
}

/** Täydentää nykyhetken kosteuden/pilvisyyden OpenMeteosta, jos FMI-livehavainnosta puuttuvat.
 *  FMI ei aina sisällä niitä, eikä [WeatherData.Hour] kanna niitä → tavallinen ennustefallback ei riitä;
 *  OpenMeteon tuntidatassa molemmat ovat. Täyttää VAIN tyhjät kentät → FMI:n omat arvot säilyvät. IO-säikeessä. */
private fun fillCurrentHumidityCloud(context: Context, weather: WeatherData, forceNetwork: Boolean) {
    val cur = weather.current
    if (!cur.humidity.isNaN() && !cur.cloudCover.isNaN()) return
    try {
        val sm = SettingsManager.get()
        if (!sm.hasHomeCoordinates()) return
        val om = OpenMeteoRepository.get(context).fetch(sm.homePlace, sm.homeLatitude, sm.homeLongitude, forceNetwork)
        val at = if (cur.timestamp > 0L) cur.timestamp else System.currentTimeMillis()
        val h = bestOpenMeteoHourAt(om, at) ?: return
        if (cur.humidity.isNaN()) h.humidity?.let { cur.humidity = it }
        if (cur.cloudCover.isNaN()) h.cloudCover?.let { cur.cloudCover = it }
    } catch (e: Exception) {
        // jätetään ennalleen
    }
}

@Composable
private fun HomeElectricityWidget(prefs: SharedPreferences, onOpenElectricity: () -> Unit) {
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
    ElectricityCard(prefs, elecRepo, elecTick, onOpenElectricity)
}

@Composable
private fun HomeSensorsWidget(prefs: SharedPreferences) {
    val context = LocalContext.current
    val ruuvi = remember { RuuviRepository.get(context) }
    val sensorTick = rememberRuuviScanTick(ruuvi)
    SensorsCard(prefs, ruuvi, sensorTick)
}

/**
 * Sitoo Ruuvi-BLE-skannauksen sekä näkymän ETTÄ sovelluksen elinkaareen: skannataan vain kun tämä
 * sensorinäkymä on koottuna JA sovellus on etualalla. Skannaus pysäytetään taustalle siirryttäessä
 * (ON_STOP) ja näkymästä poistuttaessa (onDispose) → ei jää päälle akkua syömään (aiemmin start()
 * kutsuttiin mutta stop()ia ei koskaan). Palauttaa tickin joka kasvaa uudella mittauksella.
 */
@Composable
private fun rememberRuuviScanTick(ruuvi: RuuviRepository): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val main = Handler(Looper.getMainLooper())
        val listener = RuuviRepository.Listener { _, _ -> main.post { tick++ } }
        ruuvi.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> try { ruuvi.start() } catch (e: Exception) { }
                Lifecycle.Event.ON_STOP -> ruuvi.stop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            try { ruuvi.start() } catch (e: Exception) { }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ruuvi.removeListener(listener)
            ruuvi.stop()
        }
    }
    return tick
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClockBlock(nowMs: Long, nameDay: String) {
    // Pehmeä brändigradientti (OSA B / B6): kaksi sinisen sävyä, diagonaali. Aika vasemmalle + pillit.
    val cs = MaterialTheme.colorScheme
    val arki = ArkiTheme.colors
    val cal = remember(nowMs / 60000L) { Calendar.getInstance(HELSINKI, FI).apply { timeInMillis = nowMs } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(arki.clockTop, arki.clockBottom),
                ),
            )
            .padding(24.dp),
    ) {
        Text(
            text = formatClock(nowMs),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = cs.onPrimaryContainer,
            letterSpacing = (-1.5).sp,
        )
        Spacer(Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClockPill(weekdayEssive(cal), cs.onPrimaryContainer)
            ClockPill(clockDate(cal), cs.onPrimaryContainer)
            ClockPill("Viikko " + cal.get(Calendar.WEEK_OF_YEAR), cs.onPrimaryContainer)
            if (nameDay.isNotEmpty()) {
                ClockPill(nameDay, cs.onPrimaryContainer, painterResource(R.drawable.mobile_ic_cake_24))
            }
        }
        if (nameDay.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Nimipäivät: Helsingin yliopiston almanakkatoimisto",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ClockPill(text: String, onColor: Color, icon: Painter? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(onColor.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = onColor, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = onColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private val WEEKDAY_ESSIVE_FI = arrayOf(
    "Sunnuntaina", "Maanantaina", "Tiistaina", "Keskiviikkona", "Torstaina", "Perjantaina", "Lauantaina",
)

private fun weekdayEssive(c: Calendar): String =
    WEEKDAY_ESSIVE_FI[(c.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]

private fun clockDate(c: Calendar): String =
    "${c.get(Calendar.DAY_OF_MONTH)}.${c.get(Calendar.MONTH) + 1}.${c.get(Calendar.YEAR)}"

/** Kellon alle: seuraava pyhä + seuraava virallinen liputuspäivä — kaksi rinnakkaista kohoavaa
 *  mini-korttia (ikoni-chip + "N pv" -laskuri + label + nimi + pvm), kuten mockup (Etusivu A). */
@Composable
private fun HolidayCard(holiday: HolidayEntry?, flag: HolidayEntry?) {
    if (holiday == null && flag == null) return
    val cs = MaterialTheme.colorScheme
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (holiday != null) {
                HolidayMini(
                    painterResource(R.drawable.mobile_ic_celebration_24),
                    cs.tertiary,
                    "Seuraava pyhä",
                    holiday,
                    Modifier.weight(1f).fillMaxHeight(),
                )
            }
            if (flag != null) {
                HolidayMini(
                    painterResource(R.drawable.mobile_ic_flag_24),
                    cs.primary,
                    "Seuraava liputuspäivä",
                    flag,
                    Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun HolidayMini(icon: Painter, accent: Color, label: String, entry: HolidayEntry, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ArkiTheme.colors.tileSurface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ArkiIconChip(icon, accent)
            if (entry.daysLabel.isNotEmpty()) {
                ArkiPill(entry.daysLabel, accent)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            label.uppercase(FI),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.5.sp,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            entry.name,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            entry.dateLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
    }
}

private data class HolidayEntry(val name: String, val dateLabel: String, val daysLabel: String)

private val WEEKDAY_SHORT_FI = arrayOf("su", "ma", "ti", "ke", "to", "pe", "la")

private fun shortWeekday(c: Calendar): String =
    WEEKDAY_SHORT_FI[(c.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]

private fun daysUntilLabel(target: Calendar): String {
    fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val today = startOfDay(Calendar.getInstance(HELSINKI, FI))
    val days = Math.round((startOfDay(target) - today) / 86_400_000.0).toInt()
    return if (days <= 0) "tänään" else "$days pv"
}

private fun holidayEntryFromCal(name: String, cal: Calendar): HolidayEntry =
    HolidayEntry(name, "${shortWeekday(cal)} ${calDayMonth(cal)}", daysUntilLabel(cal))

private fun isoToCal(iso: String?): Calendar? {
    if (iso == null || iso.length < 10) return null
    return try {
        flagCal(iso.substring(0, 4).toInt(), iso.substring(5, 7).toInt() - 1, iso.substring(8, 10).toInt())
    } catch (e: Exception) {
        null
    }
}

private fun flagCal(year: Int, month: Int, day: Int): Calendar {
    val c = Calendar.getInstance(HELSINKI, FI)
    c.clear()
    c.set(year, month, day)
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
    val cs = MaterialTheme.colorScheme
    val arki = ArkiTheme.colors
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_location_24),
                accent = arki.weatherAccent,
                title = displayPlace(prefs),
                subtitle = if (weather.fetchedAt > 0) "Päivitetty klo " + hhmm(weather.fetchedAt) else null,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AndroidView(
                    factory = { ctx -> WeatherIconView(ctx) },
                    update = { it.setCondition(c.condition) },
                    modifier = Modifier.size(62.dp),
                )
                Spacer(Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        formatTemp(c.temperature),
                        fontSize = 50.sp,
                        fontWeight = FontWeight.Bold,
                        color = arki.weatherAccent,
                        letterSpacing = (-1.5).sp,
                    )
                    Text(
                        WeatherTextFormatter.label(context, c.condition),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            val nowForecast = nearestForecastHour(weather, System.currentTimeMillis())
            val wind = if (!c.windSpeed.isNaN()) c.windSpeed else nowForecast?.windSpeed ?: Double.NaN
            val rain = if (!c.precip1h.isNaN()) c.precip1h else nowForecast?.precipitation ?: Double.NaN
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                WeatherStatTile(R.drawable.mobile_ic_thermometer_24, "Tuntuu kuin",
                    if (c.feelsLike.isNaN()) "--" else formatTemp(c.feelsLike), arki.weatherSunny, Modifier.weight(1f))
                WeatherStatTile(R.drawable.mobile_ic_wind_24, "Tuuli",
                    if (wind.isNaN()) "-- m/s" else one(wind) + " m/s", cs.secondary, Modifier.weight(1f))
                WeatherStatTile(R.drawable.mobile_ic_rain_24, "Sade 1h",
                    if (rain.isNaN()) "-- mm" else one(rain) + " mm", arki.weatherRain, Modifier.weight(1f))
            }
            val details = weatherDetailLines(c)
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                var di = 0
                while (di < details.size) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailChip(details[di], Modifier.weight(1f))
                        if (di + 1 < details.size) {
                            DetailChip(details[di + 1], Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    if (di + 2 < details.size) Spacer(Modifier.height(8.dp))
                    di += 2
                }
            }
            val hours = remainingHours(weather)
            if (hours.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Loppupäivän ennuste", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Spacer(Modifier.height(10.dp))
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
private fun WeatherStatTile(iconRes: Int, label: String, value: String, accent: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.13f))
            .padding(vertical = 13.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Sään lisätietolappu (esim. "Puuska 9,0 m/s") — kohoava mini-laatta, B-tyyli 2-sarakkeisessa ruudukossa. */
@Composable
private fun DetailChip(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ArkiTheme.colors.tileSurface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HourChip(h: WeatherData.Hour) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ArkiTheme.colors.tileSurface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
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
private fun ElectricityCard(prefs: SharedPreferences, repo: ElectricityRepository, tick: Int, onOpenElectricity: (() -> Unit)? = null) {
    val settingsRevision = LocalHomeDataRevision.current
    val q = remember(tick) { repo.currentQuarter() }
    val notice = remember(tick, settingsRevision) { cheapNotice(prefs, repo) }
    val threshold = remember(tick, settingsRevision) { cheapThreshold(prefs) }
    val arki = ArkiTheme.colors
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            val headerLevel = q?.let { priceLevel(it.sntPerKwh, threshold) }
            val headerAccent = headerLevel?.let { priceAccent(it, arki) } ?: MaterialTheme.colorScheme.primary
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_bolt_24),
                accent = headerAccent,
                title = "Pörssisähkö",
                trailing = if (onOpenElectricity != null) {
                    { TextButton(onClick = onOpenElectricity) { Text("Kaikki") } }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(12.dp))
            if (q == null) {
                Text("Nykyistä varttihintaa ei ole vielä saatavilla", style = MaterialTheme.typography.bodyLarge)
            } else {
                val cs = MaterialTheme.colorScheme
                val level = priceLevel(q.sntPerKwh, threshold)
                val accent = priceAccent(level, arki)
                ArkiPill(
                    text = priceLabel(level),
                    accent = accent,
                    leadingIcon = priceTrendIcon(level)?.let { painterResource(it) },
                )
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format(FI, "%.3f", q.sntPerKwh),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        letterSpacing = (-0.5).sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "c/kWh",
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.mobile_ic_clock_24),
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        quarterRangeText(q),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                val range = remember(tick) { todayElecRange(repo) }
                val rmin = range.first
                val rmax = range.second
                if (rmin != null && rmax != null && rmax.sntPerKwh > rmin.sntPerKwh) {
                    Spacer(Modifier.height(16.dp))
                    PriceMeter(q.sntPerKwh, rmin.sntPerKwh, rmax.sntPerKwh, accent, arki)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MinMaxChip(
                            R.drawable.mobile_ic_arrow_down_24, arki.priceCheap, "Halvinta",
                            String.format(FI, "%02d.%02d · %.3f", rmin.hour, rmin.minute, rmin.sntPerKwh),
                            Modifier.weight(1f),
                        )
                        MinMaxChip(
                            R.drawable.mobile_ic_arrow_up_24, arki.priceExpensive, "Kalleinta",
                            String.format(FI, "%02d.%02d · %.3f", rmax.hour, rmax.minute, rmax.sntPerKwh),
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            if (notice != null) {
                Spacer(Modifier.height(12.dp))
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

private fun priceLabel(level: PriceLevel): String = when (level) {
    PriceLevel.CHEAP -> "Halpaa"
    PriceLevel.NORMAL -> "Normaali"
    PriceLevel.EXPENSIVE -> "Kallista"
}

private fun priceTrendIcon(level: PriceLevel): Int? = when (level) {
    PriceLevel.CHEAP -> R.drawable.mobile_ic_arrow_down_24
    PriceLevel.EXPENSIVE -> R.drawable.mobile_ic_arrow_up_24
    PriceLevel.NORMAL -> null
}

/** Tämän päivän halvin/kallein vartti (liikennevalomittariin + halvin/kallein-lappuihin). */
private fun todayElecRange(repo: ElectricityRepository): Pair<ElectricityData.Quarter?, ElectricityData.Quarter?> {
    val cal = Calendar.getInstance(HELSINKI, FI)
    val qs = repo.dayQuarters(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    if (qs.isNullOrEmpty()) return null to null
    return qs.minByOrNull { it.sntPerKwh } to qs.maxByOrNull { it.sntPerKwh }
}

private fun quarterRangeText(q: ElectricityData.Quarter): String {
    val endTotal = q.hour * 60 + q.minute + 15
    val eh = (endTotal / 60) % 24
    val em = endTotal % 60
    return String.format(FI, "Vartti klo %02d.%02d–%02d.%02d · seuraava %02d.%02d", q.hour, q.minute, eh, em, eh, em)
}

/** Liikennevalomittari: vihreä→keltainen→punainen liuku + valkoinen osoitin nykyhinnan kohdalla. */
@Composable
private fun PriceMeter(current: Double, min: Double, max: Double, pointer: Color, arki: ArkiColors) {
    val f = (((current - min) / (max - min)).toFloat()).coerceIn(0.02f, 0.98f)
    Box(modifier = Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(arki.priceCheap, arki.priceNormal, arki.priceExpensive))),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(f))
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(3.dp, pointer, CircleShape),
            )
            Spacer(Modifier.weight(1f - f))
        }
    }
}

@Composable
private fun MinMaxChip(iconRes: Int, accent: Color, label: String, value: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Column {
            Text(
                label.uppercase(FI),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                color = cs.onSurfaceVariant,
            )
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
        }
    }
}

@Composable
private fun SensorsCard(prefs: SharedPreferences, repo: RuuviRepository, tick: Int) {
    val settingsRevision = LocalHomeDataRevision.current
    val sensors = remember(tick, settingsRevision) { buildSensors(prefs, repo) }
    val arki = ArkiTheme.colors
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_thermometer_24),
                accent = arki.sensorWarm,
                title = "Anturit",
                trailing = {
                    Text(
                        "Ruuvi · nyt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
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
}

@Composable
private fun SensorTile(entry: Pair<String, RuuviSample?>, modifier: Modifier) {
    val arki = ArkiTheme.colors
    val cs = MaterialTheme.colorScheme
    val sample = entry.second
    val temp = sample?.temperatureC()
    val tempColor = arki.forTemperature(temp)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(arki.tileSurface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Text(
            entry.first,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sample == null) {
            Spacer(Modifier.height(8.dp))
            Text("odottaa", fontSize = 14.sp, color = cs.onSurfaceVariant)
        } else {
            Spacer(Modifier.height(6.dp))
            val h = sample.humidityPct()
            Text(
                if (temp == null) "--" else one(temp) + "°",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = tempColor,
                letterSpacing = (-0.3).sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(
                    painterResource(R.drawable.mobile_ic_droplet_24),
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (h == null) "-- %" else Math.round(h).toString() + " %",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                )
            }
            Text(
                ageText(sample.timestamp),
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun GenericCard(title: String, note: String) {
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
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
    val tick = rememberRuuviScanTick(ruuvi)
    val settingsRevision = LocalHomeDataRevision.current
    val sensors = remember(tick, settingsRevision) { buildSensors(prefs, ruuvi) }
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
    val settingsRevision = LocalHomeDataRevision.current
    val threshold = remember(settingsRevision) { cheapThreshold(prefs) }
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
            ElectricityCompare(context, refresh)
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
    val dayMin = min?.sntPerKwh ?: 0.0
    val dayMax = max?.sntPerKwh ?: 0.0
    val cs = MaterialTheme.colorScheme

    // HERO: tasopilli/"Huomenna" + iso hinta + halvin/kallein + lähde
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dayOffset == 0 && current != null) {
                    val level = priceLevel(current.sntPerKwh, threshold)
                    ArkiPill(
                        text = priceLabel(level),
                        accent = priceAccent(level, arki),
                        leadingIcon = priceTrendIcon(level)?.let { painterResource(it) },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        String.format(FI, "Nyt klo %02d:%02d", current.hour, current.minute),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Huomenna",
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(cs.primary.copy(alpha = 0.16f))
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                        color = cs.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val heroPrice = if (dayOffset == 0 && current != null) current.sntPerKwh
            else quarters.map { it.sntPerKwh }.average()
            val heroLevel = priceLevel(heroPrice, threshold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    String.format(FI, "%.3f", heroPrice),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = priceAccent(heroLevel, arki),
                    letterSpacing = (-1.5).sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "c/kWh",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (min != null && max != null) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MinMaxChip(
                        R.drawable.mobile_ic_arrow_down_24, arki.priceCheap, "Halvinta",
                        String.format(FI, "%02d.%02d · %.3f", min.hour, min.minute, min.sntPerKwh),
                        Modifier.weight(1f),
                    )
                    MinMaxChip(
                        R.drawable.mobile_ic_arrow_up_24, arki.priceExpensive, "Kalleinta",
                        String.format(FI, "%02d.%02d · %.3f", max.hour, max.minute, max.sntPerKwh),
                        Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            val updated = if (data != null && data.fetchedAt > 0) "Päivitetty klo " + hhmm(data.fetchedAt) + " · " else ""
            Text(
                updated + "Lähde: Elering/Nord Pool. Hinnat ALV 0 %.",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
            )
        }
    }

    // LEGENDA + nyt-osoitin (vain Tänään, kun kuluva vartti tunnetaan)
    if (dayOffset == 0 && current != null && dayMax > dayMin) {
        Spacer(Modifier.height(16.dp))
        val nowN = (((current.sntPerKwh - dayMin) / (dayMax - dayMin)).toFloat()).coerceIn(0.02f, 0.98f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Halpa", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f).height(15.dp), contentAlignment = Alignment.CenterStart) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(arki.priceCheap, arki.priceNormal, arki.priceExpensive))),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(nowN))
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .background(cs.surface)
                            .border(3.dp, arki.priceCheap, CircleShape),
                    )
                    Spacer(Modifier.weight(1f - nowN))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text("Kallis", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
        }
    }

    // LÄMPÖKARTTA: 24 tuntiriviä × 4 varttisolua (solun väri = hinnan taso)
    Spacer(Modifier.height(if (dayOffset == 0 && current != null) 12.dp else 16.dp))
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("klo", modifier = Modifier.width(24.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("15", "30", "45", "60").forEach {
                        Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 1.dp)
            Spacer(Modifier.height(4.dp))
            val byHour = quarters.groupBy { it.hour }
            for (hh in 0..23) {
                val hourQs = byHour[hh].orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(String.format(FI, "%02d", hh), modifier = Modifier.width(24.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (col in 0..3) {
                            val q = hourQs.firstOrNull { it.minute == col * 15 }
                            if (q != null) {
                                HeatCell(q, dayMin, dayMax, current != null && q.timestamp == current.timestamp, Modifier.weight(1f))
                            } else {
                                Box(Modifier.weight(1f).height(30.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class CompareRowData(val label: String, val value: Double, val highlight: Boolean, val isSection: Boolean)

@Composable
private fun ElectricityCompare(context: Context, refresh: Int) {
    // Seedataan prosessivälimuistista → ei lasketa raskaita keskiarvoja uudelleen joka kerta kun
    // Vertailu-välilehti avataan (sektio poistuu kompositiosta välilehteä vaihtaessa).
    var rows by remember { mutableStateOf(if (sElectricityCompareTick == refresh) sElectricityCompareRows else null) }
    LaunchedEffect(refresh) {
        // Ohita lasku vain kun välimuistin tick vastaa nykyistä refreshiä → Päivitä (refresh kasvaa)
        // pakottaa silti tuoreen laskennan.
        val cached = sElectricityCompareRows
        if (cached != null && sElectricityCompareTick == refresh) {
            rows = cached
            return@LaunchedEffect
        }
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
        // Talleta vain kun saatiin oikeaa dataa (vähintään yksi ei-sektio-rivi) → ei jää tyhjää
        // tulosta välimuistiin verkkokatkon ajalta.
        rows?.let { if (it.any { row -> !row.isSection }) { sElectricityCompareRows = it; sElectricityCompareTick = refresh } }
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
        else -> {
            val cs = MaterialTheme.colorScheme
            val yearRow = r.firstOrNull { it.highlight && !it.isSection }
            val sectionRow = r.firstOrNull { it.isSection }
            val monthRows = r.filter { !it.isSection && !it.highlight }
            val mMin = monthRows.minByOrNull { it.value }?.value ?: 0.0
            val mMax = monthRows.maxByOrNull { it.value }?.value ?: 0.0
            if (yearRow != null) {
                ArkiCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            yearRow.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            String.format(FI, "%.3f", yearRow.value),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.primary,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("c/kWh", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (monthRows.isNotEmpty()) {
                ArkiCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        if (sectionRow != null) {
                            Text(
                                sectionRow.label.uppercase(FI),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                color = cs.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        monthRows.forEachIndexed { i, m ->
                            if (i > 0) {
                                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)
                            }
                            MonthRow(m, mMin, mMax)
                        }
                    }
                }
            }
        }
    }
}

/** Vertailun kuukausirivi: väripalkki (taso) + nimi + suhteellinen lämpöpalkki + keskihinta. */
@Composable
private fun MonthRow(m: CompareRowData, mMin: Double, mMax: Double) {
    val cs = MaterialTheme.colorScheme
    val frac = if (mMax > mMin) (((m.value - mMin) / (mMax - mMin)).toFloat()).coerceIn(0.06f, 1f) else 0.5f
    val heat = heatColor(m.value, mMin, mMax)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(5.dp).height(30.dp).clip(RoundedCornerShape(50)).background(heat),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            m.label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(cs.outlineVariant.copy(alpha = 0.4f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(frac)
                    .clip(RoundedCornerShape(50))
                    .background(heat),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            String.format(FI, "%.3f", m.value),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
        )
    }
}

private val MONTHS_FI_ELEC = arrayOf(
    "Tammikuu", "Helmikuu", "Maaliskuu", "Huhtikuu", "Toukokuu", "Kesäkuu",
    "Heinäkuu", "Elokuu", "Syyskuu", "Lokakuu", "Marraskuu", "Joulukuu",
)

/** Jatkuva lämpökarttasävy päivän min–max-välillä: cheap → normal → expensive (tokeneista). */
@Composable
private fun heatColor(price: Double, dayMin: Double, dayMax: Double): Color {
    val arki = ArkiTheme.colors
    val n = if (dayMax > dayMin) (((price - dayMin) / (dayMax - dayMin)).toFloat()).coerceIn(0f, 1f) else 0f
    return if (n < 0.5f) lerp(arki.priceCheap, arki.priceNormal, n / 0.5f)
    else lerp(arki.priceNormal, arki.priceExpensive, (n - 0.5f) / 0.5f)
}

/** Lämpökarttasolu: tausta = vartin hintataso, teksti = varttihinta (tumma muste lukea kaikilla sävyillä). */
@Composable
private fun HeatCell(q: ElectricityData.Quarter, dayMin: Double, dayMax: Double, isNow: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(heatColor(q.sntPerKwh, dayMin, dayMax))
            .then(if (isNow) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(7.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            String.format(FI, "%.3f", q.sntPerKwh),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            color = Color(0xFF080E12).copy(alpha = 0.78f),
        )
    }
}

// ===================== Sää-ennuste-sektio (FMI + Open-Meteo, päivätabit) =====================

@Composable
internal fun ForecastSection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val refresh = LocalRefreshTick.current
    val settingsRevision = LocalHomeDataRevision.current
    // Avaimitettu refreshillä → sijainnin vaihtuessa (refresh kasvaa) paikka luetaan uudelleen, jolloin
    // FMI ja Open-Meteo hakevat SAMAA paikkaa (ei kahden eri kaupungin sekoitusta otsikkoon/dataan).
    val place = remember(refresh, settingsRevision) { displayPlace(prefs) }
    val coordinates = remember(place, refresh, settingsRevision) {
        val sm = SettingsManager.get()
        val latitude = sm.homeLatitude
        val longitude = sm.homeLongitude
        if (sm.hasHomeCoordinates() && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
        ) {
            latitude to longitude
        } else {
            null
        }
    }
    val forecastKey = remember(settingsRevision, coordinates) { currentHomeWeatherKey() }
    val weatherSeed = remember(forecastKey) {
        if (sForecastWeatherKey == forecastKey) sForecastWeather else null
    }
    val openMeteoSeed = remember(forecastKey) {
        if (sForecastOpenMeteoKey == forecastKey) sForecastOpenMeteo else null
    }
    // Seedataan vain samalla paikka- ja koordinaattiavaimella haettu data.
    var weather by remember(forecastKey) { mutableStateOf(weatherSeed) }
    var openMeteo by remember(forecastKey) { mutableStateOf(openMeteoSeed) }
    LaunchedEffect(refresh, forecastKey) {
        val forceNetwork = refresh > 0
        val weatherCache = weather
        val forceOpenMeteo = forceNetwork || sForecastOpenMeteoKey != forecastKey
        val w = withContext(Dispatchers.IO) {
            try {
                WeatherRepository.get(context).fetchHome(weatherCache, forceNetwork)
            } catch (e: Exception) {
                null
            }
        }
        if (w != null) {
            WeatherCache.last = w
            sForecastWeatherKey = forecastKey
            sForecastWeather = w
            weather = w
            if (w.fetchedAt > 0L) SettingsManager.get().setLastSuccessfulFmiUpdate(w.fetchedAt)
        }
        val om = withContext(Dispatchers.IO) {
            try {
                val repo = OpenMeteoRepository.get(context)
                if (coordinates != null) {
                    repo.fetch(
                        place,
                        coordinates.first,
                        coordinates.second,
                        forceOpenMeteo,
                    )
                } else {
                    repo.fetch(place, forceNetwork)
                }
            } catch (e: Exception) {
                openMeteoSeed
            }
        }
        if (om != null) {
            sForecastOpenMeteoKey = forecastKey
            sForecastOpenMeteo = om
            openMeteo = om
        }
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
            // dayKey(ts − 1 ms) poimii mukaan myös seuraavan vuorokauden 00:00-rivin,
            // jotta valitun päivän lista päättyy keskiyöhön eikä 23:00:aan.
            val rows = w.hours.filter {
                dayKey(it.timestamp) == selectedDay || dayKey(it.timestamp - 1L) == selectedDay
            }
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
    ArkiCard(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
    // Uutisten oma tick: ei hae paluulla selaimesta/taustalta, vain kylmästart/Päivitä/väli.
    val refresh = LocalNewsRefreshTick.current
    val newsRevision = LocalHomeNewsRevision.current
    var items by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var handledRefresh by remember { mutableIntStateOf(refresh) }
    var showOnboarding by remember { mutableStateOf(!NewsProfile.isOnboarded(prefs)) }

    // Lukuajan mittaus: kun palataan selaimesta, kirjaa avatun jutun lukuaika profiiliin.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) NewsProfile.recordPendingRead(prefs)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(refresh, newsRevision) {
        val forceNetwork = refresh != handledRefresh
        handledRefresh = refresh
        loading = true
        items = withContext(Dispatchers.IO) {
            val fresh = try {
                RssRepository.get().fetchEnabled(prefs, forceNetwork)
            } catch (e: Exception) {
                null
            } ?: emptyList()
            // Personointi: kotimaisilla ei ole kategorioita → painota suosittuja lähteitä.
            if (fresh.isNotEmpty()) {
                val snap = NewsProfile.snapshot(prefs)
                NewsProfile.rerank(fresh, { NewsProfile.scoreWith(snap, "", it.feedName) }, { it.pubTimeMs })
            } else {
                fresh
            }
        }
        loading = false
    }

    if (showOnboarding) {
        NewsOnboardingDialog(
            onDone = { picked -> NewsProfile.seedTopics(prefs, picked); showOnboarding = false },
            onSkip = { NewsProfile.setOnboarded(prefs); showOnboarding = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Kotimaiset", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                shown.forEach { item ->
                    NewsRow(item) {
                        NewsProfile.recordClick(prefs, "", item.feedName)
                        NewsProfile.markOpened("", item.feedName)
                        openUrl(context, item.link)
                    }
                }
            }
        }
    }
}

// ===================== Omat syötteet -sektio (vain käyttäjän custom-RSS-syötteet) =====================

/** Listaa VAIN käyttäjän omat custom-RSS-syötteet (NewsFeedStore.customFeeds). Builtin-lähteet hoituvat
 *  backend-Kotimaista + per-lähde-etusivukorteista; tämä antaa omille syötteille (blogit/niche) lukunäkymän
 *  joka jäi orvoksi kun "Kotimaiset" siirtyi backendiin (2.10.0). Malli: [NewsSection]. */
@Composable
internal fun OwnFeedsScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    // Sama tick-kuvio kuin NewsSection: ei hae paluulla taustalta, vain kylmästart/Päivitä/väli; syötteen
    // lisäys/muokkaus asetuksissa (mobile_custom_news_feeds → homeNewsRevision++) virkistää näkymän.
    val refresh = LocalNewsRefreshTick.current
    val newsRevision = LocalHomeNewsRevision.current
    var items by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var hasFeeds by remember { mutableStateOf(NewsFeedStore.customFeeds(prefs).isNotEmpty()) }
    var loading by remember { mutableStateOf(true) }
    var handledRefresh by remember { mutableIntStateOf(refresh) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) NewsProfile.recordPendingRead(prefs)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(refresh, newsRevision) {
        val forceNetwork = refresh != handledRefresh
        handledRefresh = refresh
        loading = true
        val feeds = NewsFeedStore.customFeeds(prefs)
        hasFeeds = feeds.isNotEmpty()
        items = withContext(Dispatchers.IO) {
            val all = ArrayList<NewsItem>()
            for (f in feeds) {
                try {
                    all.addAll(RssRepository.get().fetchForFeed(f, forceNetwork))
                } catch (e: Exception) {
                    // ohita yksittäisen syötteen virhe, näytä muut
                }
            }
            val sorted = all.sortedByDescending { it.pubTimeMs }
            if (sorted.isNotEmpty()) {
                val snap = NewsProfile.snapshot(prefs)
                NewsProfile.rerank(sorted, { NewsProfile.scoreWith(snap, "", it.feedName) }, { it.pubTimeMs })
            } else {
                sorted
            }
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Omat syötteet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Text("Haetaan uutisia…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !hasFeeds -> Text(
                "Ei omia syötteitä. Lisää asetuksista.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.isEmpty() -> Text(
                "Ei uutisia juuri nyt.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val shown = items.take(50)
                val note = if (shown.size < items.size) "Näytetään ${shown.size} uusinta uutista" else "${items.size} uutista"
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                shown.forEach { item ->
                    NewsRow(item) {
                        NewsProfile.recordClick(prefs, "", item.feedName)
                        NewsProfile.markOpened("", item.feedName)
                        openUrl(context, item.link)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsRow(item: NewsItem, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen() }
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
    // <= end: myös vuorokauden vaihteen 00:00-tunti mukaan (muuten lista loppuu 23:00:aan)
    return hours.filter { it.timestamp >= now - 30L * 60_000L && it.timestamp <= end }
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

/** Anturit: ensin kaikki nimetyt (MAC→nimi-kartta, rajaton määrä), sitten muut löydetyt
 *  juoksevalla "Anturi N" -nimellä. Nimeäminen: asetukset → Ruuvi-anturit. */
private fun buildSensors(prefs: SharedPreferences, repo: RuuviRepository): List<Pair<String, RuuviSample?>> {
    val named = SettingsManager.get().sensorNamesByMac()
    val out = ArrayList<Pair<String, RuuviSample?>>()
    val used = HashSet<String>()
    for ((mac, name) in named) {
        out.add(name to repo.getLatest(mac))
        used.add(mac.uppercase(Locale.ROOT))
    }
    var next = named.size + 1
    for ((mac, sample) in repo.snapshot().entries.sortedBy { it.key }) {
        if (used.contains(mac.uppercase(Locale.ROOT))) continue
        out.add("Anturi $next" to sample)
        next++
    }
    return out
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
