package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import org.jrs82.fsclock.MetNorwayRepository
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

// Vain kalenterilaskentaan (suomalaiset ISO-viikot ja viikon alku), ei näkyvään tekstiin:
// näkyvät viikonpäivät, kuukaudet ja desimaalit muotoillaan AppLocalen kielellä.
private val FI = Locale("fi", "FI")
private val HELSINKI: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")
// Jaetut formatterit (vain pääsäie/kompositio → turvallinen jakaa; vrt. TransitScreen TR_CLOCK).
// Kellonajat ovat pelkkiä numeroita, joten kieli ei vaikuta niihin.
private val HMS_HC = SimpleDateFormat("HH:mm:ss", Locale.ROOT).apply { timeZone = HELSINKI }
private val HM_HC = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = HELSINKI }

/** Etusivun sisääntuloanimaatio soitetaan vain KERRAN prosessin aikana, ei joka kerta kun
 *  etusivulle palataan. Aiemmin slide-animaatio toistui joka paluulla → "koko sivu hyppäsi". */
private var sHomeEntranceShown = false
private var sForecastOpenMeteoKey: String? = null
private var sForecastOpenMeteo: OpenMeteoData? = null
private var sForecastMetKey: String? = null
private var sForecastMet: WeatherData? = null
// Sähkövertailun (Vertailu-välilehti) prosessivälimuisti: keskiarvot ovat kalliita laskea (vuosi-/
// kuukausiaggregaatit) → ei lasketa uudelleen joka välilehtivaihdossa, vain kun refresh muuttuu.
private var sElectricityCompareTick = -1
private var sElectricityCompareLang: String? = null
private var sElectricityCompareRows: List<CompareRowData>? = null

internal fun invalidateHomeWeatherCache() {
    if (SharedHomeWeather.key == currentHomeWeatherKey()) return
    SharedHomeWeather.clear()
    WeatherCache.last = null
}

/** Etusivun ja Sää-ennusteen yhteinen FMI-haku ([SharedHomeWeather]): haku + Open-Meteo-täydennys IO:ssa. */
private suspend fun loadHomeWeather(context: Context, key: String, generation: Long, refresh: Int): WeatherData? {
    val w = SharedHomeWeather.load(
        key, generation, refresh > 0, Dispatchers.IO,
        fetch = { seed, force -> WeatherRepository.get(context).fetchHome(seed, force) },
        enrich = { data, force -> fillCurrentHumidityCloud(context, data, force) },
    ) ?: return null
    WeatherCache.last = w
    // Onnistuneen FMI-haun aikaleima → "Viimeisin sääpäivitys" (asetukset) pysyy ajan tasalla.
    if (w.fetchedAt > 0L) SettingsManager.get().setLastSuccessfulFmiUpdate(w.fetchedAt)
    return w
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
            // Myös exit on pelkkä fadeOut: oletus-exitin shrinkVertically teki koko siirtymästä
            // alareunaan ankkuroidun kokoanimaation, jolloin häivytyksen aikana latautuvat kortit
            // työnsivät sisällön ylös ja se liukui takaisin (kylmäkäynnistyksen "hyppely").
            enter = fadeIn(spring()),
            exit = fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                if (widgets.isEmpty()) {
                    GenericCard(
                        stringResource(R.string.home_empty_title),
                        stringResource(R.string.home_empty_body),
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
                                HomeWidget.WARNINGS.id -> HomeWarningsCard(onOpenWarnings = { onOpenSection(HomeSection.WEATHER_WARNINGS) })
                                HomeWidget.SENSORS.id -> HomeSensorsWidget(prefs)
                                HomeWidget.TRAFFIC.id -> HomeTrafficCard(onOpenTraffic = { onOpenSection(HomeSection.TRAFFIC_INCIDENTS) })
                                HomeWidget.NEWS.id -> HomeNewsCard(onOpenNews = { onOpenSection(HomeSection.NEWS) })
                                HomeWidget.NEWS_FOREIGN.id -> HomeForeignNewsCard(onOpenForeign = { onOpenSection(HomeSection.NEWS_FOREIGN) })
                                HomeWidget.TRANSIT.id -> HomeTransitCard(onOpenTransit = { onOpenSection(HomeSection.TRANSIT) })
                                HomeWidget.FLIGHTS.id -> HomeFlightsCard(onOpenFlights = { onOpenSection(HomeSection.FLIGHTS) })
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
// Avaimessa kalenterityyppi, jottei kielen vaihto näytä toisen kalenterin (suomi/ruotsi) nimiä.
private var sNameDayKey: String? = null
private var sNameDay = ""

@Composable
private fun HomeClockWidget() {
    val context = LocalContext.current
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
    val nameType = AppLocale.almanakkaType(context)
    val nameKey = "$nameType|$dayMd"
    var nameDay by remember { mutableStateOf(if (sNameDayKey == nameKey) sNameDay else "") }
    LaunchedEffect(nameKey) {
        if (sNameDayKey == nameKey && sNameDay.isNotEmpty()) {
            nameDay = sNameDay
            return@LaunchedEffect
        }
        val c = Calendar.getInstance(HELSINKI, FI)
        val names = withContext(Dispatchers.IO) {
            try {
                AlmanakkaClient.fetchNames(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), nameType)
            } catch (e: Exception) {
                ""
            }
        }
        if (names.isNotEmpty()) {
            sNameDay = names
            sNameDayKey = nameKey
            nameDay = names
        }
    }
    ClockBlock(nowMs, nameDay)
}

@Composable
private fun HomeHolidayWidget() {
    val context = LocalContext.current
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
        if (h != null) {
            holidayEntryFromCal(context, HolidayNames.display(context, h.name), flagCal(h.year, h.month - 1, h.day))
        } else {
            null
        }
    }
    var holidayEntry by remember(dayKey) { mutableStateOf<HolidayEntry?>(null) }
    LaunchedEffect(dayKey) {
        holidayEntry = withContext(Dispatchers.IO) {
            try {
                val ev = MobileHolidayProvider.next(Calendar.getInstance(HELSINKI, FI))
                if (ev != null) {
                    val name = HolidayNames.display(context, ev.name, ev.localized)
                    val cal = isoToCal(ev.date)
                    if (cal != null) holidayEntryFromCal(context, name, cal)
                    else HolidayEntry(name, formatIsoDayMonth(ev.date), "")
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
                        holidayEntryFromCal(context, HolidayNames.display(context, up[0].name), cal)
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
    val generation = LocalWeatherGeneration.current
    var weather by remember(weatherKey) { mutableStateOf(SharedHomeWeather.seed(weatherKey)) }
    LaunchedEffect(refresh, generation, weatherKey) {
        if (SharedHomeWeather.seed(weatherKey) == null) weather = null
        loadHomeWeather(context, weatherKey, generation, refresh)?.let { weather = it }
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
    val context = LocalContext.current
    val cal = remember(nowMs / 60000L) { Calendar.getInstance(HELSINKI, FI).apply { timeInMillis = nowMs } }
    val dateText = remember(cal) { clockDate(context, cal) }
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
            ClockPill(stringResource(weekdayRes(cal)), cs.onPrimaryContainer)
            ClockPill(dateText, cs.onPrimaryContainer)
            ClockPill(stringResource(R.string.home_week, cal.get(Calendar.WEEK_OF_YEAR)), cs.onPrimaryContainer)
            if (nameDay.isNotEmpty()) {
                ClockPill(nameDay, cs.onPrimaryContainer, painterResource(R.drawable.mobile_ic_cake_24))
            }
        }
        if (nameDay.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.holiday_nameday_source),
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

// Omat viikonpäiväresurssit: suomen pillissä on essiivi ("Maanantaina"), jota Locale ei anna.
private val WEEKDAY_RES = intArrayOf(
    R.string.home_weekday_sunday, R.string.home_weekday_monday, R.string.home_weekday_tuesday,
    R.string.home_weekday_wednesday, R.string.home_weekday_thursday, R.string.home_weekday_friday,
    R.string.home_weekday_saturday,
)

@StringRes
private fun weekdayRes(c: Calendar): Int = WEEKDAY_RES[(c.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]

private fun clockDate(context: Context, c: Calendar): String =
    localDateFormat(context, R.string.home_date_pattern_full).format(c.time)

/** Päivämäärä kielen kuviolla (esim. fi "EEE d.M.", en "EEE d MMM"); aikavyöhyke aina Helsinki. */
private fun localDateFormat(context: Context, @StringRes pattern: Int): SimpleDateFormat =
    SimpleDateFormat(context.getString(pattern), AppLocale.current(context)).apply { timeZone = HELSINKI }

@Composable
private fun appLocale(): Locale = AppLocale.current(LocalContext.current)

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
                    stringResource(R.string.holiday_next_holiday),
                    holiday,
                    Modifier.weight(1f).fillMaxHeight(),
                )
            }
            if (flag != null) {
                HolidayMini(
                    painterResource(R.drawable.mobile_ic_flag_24),
                    cs.primary,
                    stringResource(R.string.holiday_next_flag_day),
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
            label.uppercase(appLocale()),
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

private fun daysUntilLabel(context: Context, target: Calendar): String {
    fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val today = startOfDay(Calendar.getInstance(HELSINKI, FI))
    val days = Math.round((startOfDay(target) - today) / 86_400_000.0).toInt()
    return if (days <= 0) {
        context.getString(R.string.common_today)
    } else {
        context.resources.getQuantityString(R.plurals.holiday_days_until, days, days)
    }
}

private fun holidayEntryFromCal(context: Context, name: String, cal: Calendar): HolidayEntry = HolidayEntry(
    name,
    localDateFormat(context, R.string.home_date_pattern_weekday_short).format(cal.time),
    daysUntilLabel(context, cal),
)

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
        GenericCard(stringResource(R.string.home_title_weather), stringResource(R.string.home_weather_loading))
        return
    }
    val cs = MaterialTheme.colorScheme
    val arki = ArkiTheme.colors
    val loc = AppLocale.current(context)
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_location_24),
                accent = arki.weatherAccent,
                title = displayPlace(prefs),
                subtitle = if (weather.fetchedAt > 0) stringResource(R.string.common_updated_at, hhmm(weather.fetchedAt)) else null,
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
                        formatTemp(loc, c.temperature),
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
                    if (!c.feelsLike.isNaN()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.home_feels_like, formatTemp(loc, c.feelsLike)),
                            fontSize = 14.sp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            val nowForecast = nearestForecastHour(weather, System.currentTimeMillis())
            val wind = if (!c.windSpeed.isNaN()) c.windSpeed else nowForecast?.windSpeed ?: Double.NaN
            val rain = if (!c.precip1h.isNaN()) c.precip1h else nowForecast?.precipitation ?: Double.NaN
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                WeatherStatTile(R.drawable.mobile_ic_wind_24, stringResource(R.string.home_wind),
                    if (wind.isNaN()) "-- m/s" else one(loc, wind) + " m/s", cs.secondary, Modifier.weight(1f))
                WeatherStatTile(R.drawable.mobile_ic_rain_24, stringResource(R.string.home_rain_1h),
                    if (rain.isNaN()) "-- mm" else one(loc, rain) + " mm", arki.weatherRain, Modifier.weight(1f))
            }
            val details = weatherDetailLines(context, c)
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
                Text(stringResource(R.string.home_forecast_rest_of_day), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
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
    val loc = appLocale()
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
        Text(formatTemp(loc, h.temperature), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        ChipIconRow(R.drawable.mobile_ic_rain_24, if (h.precipitation.isNaN()) "--" else one(loc, h.precipitation) + " mm")
        ChipIconRow(R.drawable.mobile_ic_wind_24, if (h.windSpeed.isNaN()) "--" else one(loc, h.windSpeed) + " m/s")
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
    val context = LocalContext.current
    val loc = AppLocale.current(context)
    val settingsRevision = LocalHomeDataRevision.current
    val vat = remember(settingsRevision) { ElectricityVat.enabled(prefs) }
    val q = remember(tick) { repo.currentQuarter() }
    val notice = remember(tick, settingsRevision) { cheapNotice(context, prefs, repo) }
    val threshold = remember(tick, settingsRevision) { cheapThreshold(prefs) }
    val arki = ArkiTheme.colors
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            val headerLevel = q?.let { priceLevel(ElectricityVat.apply(it.sntPerKwh, vat), threshold) }
            val headerAccent = headerLevel?.let { priceAccent(it, arki) } ?: MaterialTheme.colorScheme.primary
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_bolt_24),
                accent = headerAccent,
                title = stringResource(R.string.electricity_title),
                trailing = if (onOpenElectricity != null) {
                    { TextButton(onClick = onOpenElectricity) { Text(stringResource(R.string.home_see_all)) } }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(12.dp))
            if (q == null) {
                Text(stringResource(R.string.electricity_no_current_price), style = MaterialTheme.typography.bodyLarge)
            } else {
                val cs = MaterialTheme.colorScheme
                val price = ElectricityVat.apply(q.sntPerKwh, vat)
                val level = priceLevel(price, threshold)
                val accent = priceAccent(level, arki)
                ArkiPill(
                    text = stringResource(priceLabelRes(level)),
                    accent = accent,
                    leadingIcon = priceTrendIcon(level)?.let { painterResource(it) },
                )
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        ElectricityVat.format(price, loc),
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
                        quarterRangeText(context, q),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(ElectricityVat.priceNoteRes(vat)),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                val range = remember(tick) { todayElecRange(repo) }
                val rmin = range.first
                val rmax = range.second
                if (rmin != null && rmax != null && rmax.sntPerKwh > rmin.sntPerKwh) {
                    Spacer(Modifier.height(16.dp))
                    PriceMeter(q.sntPerKwh, rmin.sntPerKwh, rmax.sntPerKwh, vat, accent, arki)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MinMaxChip(
                            R.drawable.mobile_ic_arrow_down_24, arki.priceCheap, stringResource(R.string.electricity_cheapest),
                            quarterPriceText(context, rmin, vat),
                            Modifier.weight(1f),
                        )
                        MinMaxChip(
                            R.drawable.mobile_ic_arrow_up_24, arki.priceExpensive, stringResource(R.string.electricity_most_expensive),
                            quarterPriceText(context, rmax, vat),
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

/** Yli tämän (c/kWh, NÄYTETTY hinta eli ALV-asetuksen mukainen) hinta luokitellaan kalliiksi (punainen). */
private const val EXPENSIVE_THRESHOLD = 15.0

/** "HH.MM · 0,000" — vartin kello + hinta ALV-asetuksen mukaan, aina kolme desimaalia. */
private fun quarterPriceText(context: Context, q: ElectricityData.Quarter, vat: Boolean): String =
    String.format(Locale.ROOT, "%02d.%02d", q.hour, q.minute) + ElectricityTime.dstSuffix(context, q.timestamp) +
        " · " + ElectricityVat.format(ElectricityVat.apply(q.sntPerKwh, vat), AppLocale.current(context))

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

@StringRes
private fun priceLabelRes(level: PriceLevel): Int = when (level) {
    PriceLevel.CHEAP -> R.string.electricity_level_cheap
    PriceLevel.NORMAL -> R.string.electricity_level_normal
    PriceLevel.EXPENSIVE -> R.string.electricity_level_expensive
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

private fun quarterRangeText(context: Context, q: ElectricityData.Quarter): String {
    val (start, end) = ElectricityTime.quarterBounds(q.timestamp)
    val from = String.format(Locale.ROOT, "%02d.%02d", start.hour, start.minute)
    val to = String.format(Locale.ROOT, "%02d.%02d", end.hour, end.minute)
    return context.getString(R.string.electricity_quarter_range, from, to, to)
}

/** Liikennevalomittari: vihreä→keltainen→punainen liuku päivän halvimmasta kalleimpaan varttiin,
 *  valkoinen osoitin nykyhinnan kohdalla ja alla janan alun, keskikohdan ja lopun hinnat.
 *  Sama jana etusivun kortissa ja sähkösivulla; widget piirtää vastaavan Glancella. */
@Composable
private fun PriceMeter(current: Double, min: Double, max: Double, vat: Boolean, pointer: Color, arki: ArkiColors) {
    val f = PriceScale.fraction(current, min, max, vat) ?: return
    val (minText, midText, maxText) = PriceScale.labels(min, max, vat, appLocale())
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
    Spacer(Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        val cs = MaterialTheme.colorScheme
        Text(minText, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = arki.priceCheap, textAlign = TextAlign.Start, maxLines = 1)
        Text(midText, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1)
        Text(maxText, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = arki.priceExpensive, textAlign = TextAlign.End, maxLines = 1)
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
                label.uppercase(appLocale()),
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
    val context = LocalContext.current
    val settingsRevision = LocalHomeDataRevision.current
    val sensors = remember(tick, settingsRevision) { buildSensors(context, prefs, repo) }
    val arki = ArkiTheme.colors
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_thermometer_24),
                accent = arki.sensorWarm,
                title = stringResource(R.string.home_title_sensors),
                trailing = {
                    Text(
                        stringResource(R.string.home_sensors_ruuvi_now),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            if (sensors.isEmpty()) {
                Text(
                    stringResource(R.string.home_sensors_none),
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
    val context = LocalContext.current
    val loc = AppLocale.current(context)
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
            Text(stringResource(R.string.home_sensor_waiting), fontSize = 14.sp, color = cs.onSurfaceVariant)
        } else {
            Spacer(Modifier.height(6.dp))
            val h = sample.humidityPct()
            Text(
                if (temp == null) "--" else one(loc, temp) + "°",
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
                    stringResource(R.string.home_percent, if (h == null) "--" else Math.round(h).toString()),
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                )
            }
            Text(
                homeAgeText(context, sample.timestamp),
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
    val sensors = remember(tick, settingsRevision) { buildSensors(context, prefs, ruuvi) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.home_title_sensors), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (sensors.isEmpty()) {
            Text(
                stringResource(R.string.home_sensors_none),
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
    val vat = remember(settingsRevision) { ElectricityVat.enabled(prefs) }
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
    // Kun Huomenna-välilehti avataan eikä huomisen TÄYTTÄ päivää ole vielä cachessa (mutta klo ≥14,
    // jolloin NordPool-päivädata pitäisi olla julki), pakota tuore haku OHI 55 min cachen — muuten sivu
    // näyttäisi tyhjää vaikka palvelimella on jo huomisen hinnat. HUOM: hasFullTomorrow() (ei hasTomorrow)
    // koska CET-aikavyöhykkeen ~1 h aamuyön ylivuoto pitäisi hasTomorrow():n aina tosena → pakkohaku ei
    // laukeaisi koskaan. hasFullTomorrow vaatii iltavartin → ohittaa ylivuodon (kesä/talvi-turvallinen).
    LaunchedEffect(dayOffset, refresh) {
        if (dayOffset == 1 && !repo.hasFullTomorrow() &&
            Calendar.getInstance(HELSINKI, FI).get(Calendar.HOUR_OF_DAY) >= 14
        ) {
            withContext(Dispatchers.IO) {
                try { repo.fetchNow() } catch (e: Exception) { }
            }
            tick++
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.electricity_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ElecTab(stringResource(R.string.common_today), dayOffset == 0, Modifier.weight(1f)) { dayOffset = 0 }
            ElecTab(stringResource(R.string.common_tomorrow), dayOffset == 1, Modifier.weight(1f)) { dayOffset = 1 }
            ElecTab(stringResource(R.string.electricity_tab_compare), dayOffset == 2, Modifier.weight(1f)) { dayOffset = 2 }
        }
        Spacer(Modifier.height(14.dp))
        if (dayOffset == 2) {
            ElectricityCompare(context, refresh, vat)
        } else {
            ElectricityDay(repo, threshold, dayOffset, tick, vat)
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
private fun ElectricityDay(repo: ElectricityRepository, threshold: Double, dayOffset: Int, tick: Int, vat: Boolean) {
    val context = LocalContext.current
    val loc = AppLocale.current(context)
    val arki = ArkiTheme.colors
    val data = remember(tick) { repo.peek() }
    val quarters = remember(tick, dayOffset) {
        val c = Calendar.getInstance(HELSINKI, FI)
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        repo.dayQuarters(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
    val current = remember(tick, dayOffset) { if (dayOffset == 0) repo.currentQuarter() else null }

    // Näytä huomisen hinnat vasta kun koko päivä on iltaan asti (tunti ≥ 20) — pelkkä CET-ylivuoto
    // (huomisen 00:00–00:45) ei riitä. Toimii myös DST-päivinä (92 / 100 varttia) toisin kuin kiinteä 96.
    if (dayOffset == 1 && (quarters == null || quarters.none { it.hour >= 20 })) {
        Text(
            stringResource(R.string.electricity_tomorrow_pending),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (quarters.isNullOrEmpty()) {
        Text(
            stringResource(
                if (dayOffset == 0) R.string.electricity_today_unavailable else R.string.electricity_tomorrow_pending,
            ),
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
                    val level = priceLevel(ElectricityVat.apply(current.sntPerKwh, vat), threshold)
                    ArkiPill(
                        text = stringResource(priceLabelRes(level)),
                        accent = priceAccent(level, arki),
                        leadingIcon = priceTrendIcon(level)?.let { painterResource(it) },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            R.string.electricity_now_at,
                            String.format(Locale.ROOT, "%02d:%02d", current.hour, current.minute),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.common_tomorrow),
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
            val heroPrice =
                if (dayOffset == 0 && current != null) ElectricityVat.apply(current.sntPerKwh, vat)
                else quarters.map { ElectricityVat.apply(it.sntPerKwh, vat) }.average()
            val heroLevel = priceLevel(heroPrice, threshold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    ElectricityVat.format(heroPrice, loc),
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
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(ElectricityVat.priceNoteRes(vat)),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (min != null && max != null) {
                Spacer(Modifier.height(16.dp))
                // Hintajana + nyt-osoitin kuten etusivun kortissa (vain Tänään, kun kuluva vartti tunnetaan).
                if (dayOffset == 0 && current != null && dayMax > dayMin) {
                    PriceMeter(current.sntPerKwh, dayMin, dayMax, vat, priceAccent(heroLevel, arki), arki)
                    Spacer(Modifier.height(14.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MinMaxChip(
                        R.drawable.mobile_ic_arrow_down_24, arki.priceCheap, stringResource(R.string.electricity_cheapest),
                        quarterPriceText(context, min, vat),
                        Modifier.weight(1f),
                    )
                    MinMaxChip(
                        R.drawable.mobile_ic_arrow_up_24, arki.priceExpensive, stringResource(R.string.electricity_most_expensive),
                        quarterPriceText(context, max, vat),
                        Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            val updated = if (data != null && data.fetchedAt > 0) {
                stringResource(R.string.common_updated_at, hhmm(data.fetchedAt)) + " · "
            } else {
                ""
            }
            Text(
                updated + stringResource(R.string.electricity_source_footer, stringResource(ElectricityVat.labelRes(vat))),
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
            )
        }
    }

    // LÄMPÖKARTTA: tuntirivit × 4 varttisolua (solun väri = hinnan taso). Rivit todellisista tunneista:
    // talviaikaan siirryttäessä tunti 03 on kahdesti (25 riviä), kesäaikaan siirryttäessä sitä ei ole (23).
    Spacer(Modifier.height(16.dp))
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(stringResource(R.string.electricity_heatmap_hour), modifier = Modifier.width(24.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("15", "30", "45", "60").forEach {
                        Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 1.dp)
            Spacer(Modifier.height(4.dp))
            val byHour = quarters.groupBy { ElectricityTime.hourKey(it.timestamp) }
            val hourRows = remember(quarters.first().timestamp) { ElectricityTime.dayHours(quarters.first().timestamp) }
            for (hr in hourRows) {
                val hourQs = byHour[ElectricityTime.hourKey(hr.startMs)].orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(String.format(Locale.ROOT, "%02d", hr.hour) + if (hr.repeated) "*" else "", modifier = Modifier.width(24.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (col in 0..3) {
                            val q = hourQs.firstOrNull { it.minute == col * 15 }
                            if (q != null) {
                                HeatCell(q, dayMin, dayMax, current != null && q.timestamp == current.timestamp, vat, loc, Modifier.weight(1f))
                            } else {
                                Box(Modifier.weight(1f).height(30.dp))
                            }
                        }
                    }
                }
            }
            if (hourRows.any { it.repeated }) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.electricity_dst_footnote),
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

private data class CompareRowData(val label: String, val value: Double, val highlight: Boolean, val isSection: Boolean)

@Composable
private fun ElectricityCompare(context: Context, refresh: Int, vat: Boolean) {
    // Seedataan prosessivälimuistista → ei lasketa raskaita keskiarvoja uudelleen joka kerta kun
    // Vertailu-välilehti avataan (sektio poistuu kompositiosta välilehteä vaihtaessa).
    // Rivien otsikot ovat valmiiksi käännettyjä → välimuisti kelpaa vain samalla kielellä.
    val lang = AppLocale.language(context)
    var rows by remember {
        mutableStateOf(
            if (sElectricityCompareTick == refresh && sElectricityCompareLang == lang) sElectricityCompareRows else null,
        )
    }
    LaunchedEffect(refresh, lang) {
        // Ohita lasku vain kun välimuistin tick vastaa nykyistä refreshiä → Päivitä (refresh kasvaa)
        // pakottaa silti tuoreen laskennan.
        val cached = sElectricityCompareRows
        if (cached != null && sElectricityCompareTick == refresh && sElectricityCompareLang == lang) {
            rows = cached
            return@LaunchedEffect
        }
        var complete = false
        rows = withContext(Dispatchers.IO) {
            val out = ArrayList<CompareRowData>()
            try {
                val now = Calendar.getInstance(HELSINKI, FI)
                val year = now.get(Calendar.YEAR)
                val curMonth = now.get(Calendar.MONTH) + 1
                val loc = AppLocale.current(context)
                val prev = ElectricityAverages.previousYearAverage(context, true)
                if (prev != null) {
                    out.add(CompareRowData(context.getString(R.string.electricity_compare_year_avg, year - 1), prev.avgSntPerKwh, true, false))
                }
                out.add(CompareRowData(context.getString(R.string.electricity_compare_month_section, year), Double.NaN, false, true))
                for (m in 1..curMonth) {
                    val ma = ElectricityAverages.monthAverage(context, year, m, true) ?: continue
                    val name = monthName(loc, m)
                    val label = if (m == curMonth) context.getString(R.string.electricity_compare_month_partial, name) else name
                    out.add(CompareRowData(label, ma.avgSntPerKwh, false, false))
                }
                complete = prev != null && out.size == curMonth + 2
            } catch (e: Exception) {
                // näytetään mitä saatiin
            }
            out
        }
        // Talleta vain täydellinen tulos (vuosiarvo + kaikki kuukaudet) → verkkokatkon aikana syntynyt
        // vajaa tulos ei jää välimuistiin, vaan välilehden seuraava avaus yrittää puuttuvia uudelleen.
        rows?.let {
            if (complete) {
                sElectricityCompareRows = it
                sElectricityCompareTick = refresh
                sElectricityCompareLang = lang
            }
        }
    }
    Text(
        stringResource(R.string.electricity_compare_header, stringResource(ElectricityVat.labelRes(vat))),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    val r = rows
    when {
        r == null -> Text(
            stringResource(R.string.electricity_compare_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        r.none { !it.isSection } -> Text(
            stringResource(R.string.electricity_compare_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            val cs = MaterialTheme.colorScheme
            val loc = AppLocale.current(context)
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
                            ElectricityVat.format(ElectricityVat.apply(yearRow.value, vat), loc),
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
                                sectionRow.label.uppercase(loc),
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
                            MonthRow(m, mMin, mMax, vat)
                        }
                    }
                }
            }
        }
    }
}

/** Vertailun kuukausirivi: väripalkki (taso) + nimi + suhteellinen lämpöpalkki + keskihinta. */
@Composable
private fun MonthRow(m: CompareRowData, mMin: Double, mMax: Double, vat: Boolean) {
    val cs = MaterialTheme.colorScheme
    val loc = appLocale()
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
            ElectricityVat.format(ElectricityVat.apply(m.value, vat), loc),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
        )
    }
}

/** Kuukauden itsenäinen nimi isolla alkukirjaimella kielen mukaan ("Tammikuu", "Januari", "January"). */
private fun monthName(locale: Locale, month: Int): String {
    val c = Calendar.getInstance(HELSINKI, FI)
    c.clear()
    c.set(2000, month - 1, 1)
    val name = SimpleDateFormat("LLLL", locale).apply { timeZone = HELSINKI }.format(c.time)
    return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

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
private fun HeatCell(q: ElectricityData.Quarter, dayMin: Double, dayMax: Double, isNow: Boolean, vat: Boolean, loc: Locale, modifier: Modifier) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(heatColor(q.sntPerKwh, dayMin, dayMax))
            .then(if (isNow) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(7.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ElectricityVat.format(ElectricityVat.apply(q.sntPerKwh, vat), loc),
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
    val generation = LocalWeatherGeneration.current
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
    val weatherSeed = remember(forecastKey) { SharedHomeWeather.seed(forecastKey) }
    val openMeteoSeed = remember(forecastKey) {
        if (sForecastOpenMeteoKey == forecastKey) sForecastOpenMeteo else null
    }
    val metSeed = remember(forecastKey) {
        if (sForecastMetKey == forecastKey) sForecastMet else null
    }
    // Seedataan vain samalla paikka- ja koordinaattiavaimella haettu data.
    var weather by remember(forecastKey) { mutableStateOf(weatherSeed) }
    var openMeteo by remember(forecastKey) { mutableStateOf(openMeteoSeed) }
    var met by remember(forecastKey) { mutableStateOf(metSeed) }
    LaunchedEffect(refresh, generation, forecastKey) {
        val forceNetwork = refresh > 0
        val forceOpenMeteo = forceNetwork || sForecastOpenMeteoKey != forecastKey
        loadHomeWeather(context, forecastKey, generation, refresh)?.let { weather = it }
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
        // MET Norway vaatii koordinaatit — ilman niitä kolmas rivi jää pois.
        val forceMet = forceNetwork || sForecastMetKey != forecastKey
        val m = withContext(Dispatchers.IO) {
            try {
                if (coordinates != null) {
                    MetNorwayRepository.get(context).fetch(
                        place,
                        coordinates.first,
                        coordinates.second,
                        forceMet,
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                metSeed
            }
        }
        if (m != null) {
            sForecastMetKey = forecastKey
            sForecastMet = m
            met = m
        }
    }

    val w = weather
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.home_forecast_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(place, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (w == null || w.hours.isEmpty()) {
            Text(
                stringResource(R.string.home_forecast_not_loaded),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val days = remember(w) { forecastDayKeys(w) }
            var selectedDay by remember(w) { mutableStateOf(days.firstOrNull() ?: 0) }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                days.forEach { key ->
                    DayTab(dayLabel(context, key), key == selectedDay) { selectedDay = key }
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
                Text(stringResource(R.string.home_forecast_no_hours), style = MaterialTheme.typography.bodyMedium)
            } else {
                rows.forEach { h ->
                    ForecastRow(
                        context, h,
                        bestOpenMeteoHourAt(openMeteo, h.timestamp),
                        bestMetHourAt(met, h.timestamp),
                    )
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
private fun ForecastRow(context: Context, h: WeatherData.Hour, om: OpenMeteoData.Hour?, met: WeatherData.Hour?) {
    val loc = AppLocale.current(context)
    ArkiCard(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(hhmm(h.timestamp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            ProviderRow(
                "FMI", h.condition, formatTemp(loc, h.temperature),
                WeatherTextFormatter.shortLabel(context, h.condition), fmiForecastStats(context, h),
            )
            if (om != null) {
                ProviderRow(
                    "Open-Meteo", om.condition, formatNullableTemp(loc, om.temperature),
                    WeatherTextFormatter.shortLabel(context, om.condition), openMeteoForecastStats(context, om),
                )
            } else {
                Text(
                    stringResource(R.string.home_forecast_om_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // MET kattaa tunneittain vain ~2,5 vrk ja sen jälkeen 6 h -lohkot →
            // rivi näytetään vain kun tunnille osuu dataa, ei "ei saatavilla" -tekstiä.
            if (met != null) {
                ProviderRow(
                    "MET Norway", met.condition, formatTemp(loc, met.temperature),
                    WeatherTextFormatter.shortLabel(context, met.condition), metForecastStats(context, met),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    stats.forEach { s -> StatChip(s) }
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
        Text(stringResource(R.string.home_news_domestic_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Text(stringResource(R.string.home_news_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.isEmpty() -> Text(
                stringResource(R.string.home_news_empty_check_sources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val shown = items.take(50)
                val note = newsCountNote(shown.size, items.size)
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
        Text(stringResource(R.string.home_own_feeds_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Text(stringResource(R.string.home_news_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !hasFeeds -> Text(
                stringResource(R.string.home_own_feeds_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.isEmpty() -> Text(
                stringResource(R.string.home_news_none_now),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val shown = items.take(50)
                val note = newsCountNote(shown.size, items.size)
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

/** "Näytetään 50 uusinta uutista" kun lista katkaistiin, muuten "N uutista". */
@Composable
private fun newsCountNote(shown: Int, total: Int): String =
    if (shown < total) {
        pluralStringResource(R.plurals.home_news_showing_latest, shown, shown)
    } else {
        pluralStringResource(R.plurals.home_news_count, total, total)
    }

@Composable
private fun NewsRow(item: NewsItem, onOpen: () -> Unit) {
    val context = LocalContext.current
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
                        " · " + homeAgeText(context, item.pubTimeMs),
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

private fun dayLabel(context: Context, key: Int): String {
    val year = key / 10000
    val month = (key / 100) % 100
    val day = key % 100
    val c = Calendar.getInstance(HELSINKI, FI)
    c.clear()
    c.set(year, month - 1, day)
    val loc = AppLocale.current(context)
    var label = localDateFormat(context, R.string.home_date_pattern_weekday_short).format(c.time)
    if (label.isNotEmpty()) label = label.substring(0, 1).uppercase(loc) + label.substring(1)
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

private fun bestMetHourAt(met: WeatherData?, timestamp: Long): WeatherData.Hour? {
    val hours = met?.hours ?: return null
    var best: WeatherData.Hour? = null
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

private fun fmiForecastStats(context: Context, h: WeatherData.Hour): List<ForecastStat> {
    val loc = AppLocale.current(context)
    val out = ArrayList<ForecastStat>()
    if (!h.precipitation.isNaN()) out.add(ForecastStat(StatKind.RAIN, R.drawable.mobile_ic_rain_24, one(loc, h.precipitation) + " mm"))
    if (!h.windSpeed.isNaN()) out.add(ForecastStat(StatKind.WIND, R.drawable.mobile_ic_wind_24, one(loc, h.windSpeed) + " m/s"))
    if (!h.windGust.isNaN()) out.add(ForecastStat(StatKind.GUST, R.drawable.mobile_ic_wind_24, context.getString(R.string.home_stat_gust, one(loc, h.windGust))))
    if (!h.humidity.isNaN()) out.add(ForecastStat(StatKind.HUMIDITY, R.drawable.mobile_ic_droplet_24, percentText(context, h.humidity)))
    return out
}

private fun metForecastStats(context: Context, h: WeatherData.Hour): List<ForecastStat> {
    val loc = AppLocale.current(context)
    val out = ArrayList<ForecastStat>()
    if (!h.precipitation.isNaN()) {
        val value = if (h.blockHours > 1) {
            one(loc, h.precipitation) + " mm/" + h.blockHours + " h"
        } else {
            one(loc, h.precipitation) + " mm"
        }
        out.add(ForecastStat(StatKind.RAIN, R.drawable.mobile_ic_rain_24, value))
    }
    if (!h.windSpeed.isNaN()) out.add(ForecastStat(StatKind.WIND, R.drawable.mobile_ic_wind_24, one(loc, h.windSpeed) + " m/s"))
    if (!h.humidity.isNaN()) out.add(ForecastStat(StatKind.HUMIDITY, R.drawable.mobile_ic_droplet_24, percentText(context, h.humidity)))
    return out
}

private fun openMeteoForecastStats(context: Context, h: OpenMeteoData.Hour): List<ForecastStat> {
    val loc = AppLocale.current(context)
    val out = ArrayList<ForecastStat>()
    h.precipitation?.let { out.add(ForecastStat(StatKind.RAIN, R.drawable.mobile_ic_rain_24, one(loc, it) + " mm")) }
    h.windSpeed?.let { out.add(ForecastStat(StatKind.WIND, R.drawable.mobile_ic_wind_24, one(loc, it) + " m/s")) }
    h.windGust?.let { out.add(ForecastStat(StatKind.GUST, R.drawable.mobile_ic_wind_24, context.getString(R.string.home_stat_gust, one(loc, it)))) }
    h.humidity?.let { out.add(ForecastStat(StatKind.HUMIDITY, R.drawable.mobile_ic_droplet_24, percentText(context, it))) }
    return out
}

/** Prosentti kielen tavalla: fi/sv "45 %", en "45%". */
private fun percentText(context: Context, v: Double): String =
    context.getString(R.string.home_percent, Math.round(v).toString())

internal fun formatNullableTemp(loc: Locale, v: Double?): String = if (v == null) "-- °C" else formatTemp(loc, v)

// ===================== Data- ja formatointiapurit (replikoi MobileMainActivity) =====================

/** Sääpaikan näyttönimi kaikille sivuille (etusivu, Sää-ennuste, Paikkakunnat). */
internal fun displayPlace(prefs: SharedPreferences): String {
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

private fun weatherDetailLines(context: Context, c: WeatherData.Current): List<String> {
    val loc = AppLocale.current(context)
    val lines = ArrayList<String>()
    if (!c.windGust.isNaN()) lines.add(context.getString(R.string.home_detail_gust, one(loc, c.windGust)))
    if (!c.humidity.isNaN()) lines.add(context.getString(R.string.home_detail_humidity, Math.round(c.humidity)))
    if (!c.cloudCover.isNaN()) lines.add(context.getString(R.string.home_detail_cloud, Math.round(c.cloudCover)))
    if (c.timestamp > 0) lines.add(context.getString(R.string.home_detail_fmi_obs, hhmm(c.timestamp)))
    return lines
}

private fun cheapNotice(context: Context, prefs: SharedPreferences, repo: ElectricityRepository): String? {
    if (!prefs.getBoolean(MobileThemeController.KEY_CHEAP_ELECTRICITY_NOTICE, true)) return null
    val threshold = cheapThreshold(prefs)
    val mode = prefs.getString(MobileThemeController.KEY_CHEAP_ELECTRICITY_MODE, MobileThemeController.CHEAP_MODE_ALL_DAY)
        ?: MobileThemeController.CHEAP_MODE_ALL_DAY
    val cal = Calendar.getInstance(HELSINKI, FI)
    val today = repo.dayQuarters(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    val vat = ElectricityVat.enabled(prefs)
    val checked = filterByMode(today, mode)
    if (checked.isEmpty() || !allBelow(checked, threshold, vat)) return null
    val max = checked.maxByOrNull { it.sntPerKwh } ?: return null
    val loc = AppLocale.current(context)
    return context.getString(
        cheapNoticeRes(mode),
        ElectricityVat.format(threshold, loc), ElectricityVat.format(ElectricityVat.apply(max.sntPerKwh, vat), loc),
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

private fun allBelow(quarters: List<ElectricityData.Quarter>, threshold: Double, vat: Boolean): Boolean {
    if (quarters.isEmpty()) return false
    return quarters.all { ElectricityVat.apply(it.sntPerKwh, vat) < threshold }
}

// Koko lause per tila, koska ajanilmaus taipuu eri kielissä eri kohtaan lausetta.
@StringRes
private fun cheapNoticeRes(mode: String): Int = when (mode) {
    MobileThemeController.CHEAP_MODE_CURRENT -> R.string.electricity_cheap_notice_now
    MobileThemeController.CHEAP_MODE_REMAINING_DAY -> R.string.electricity_cheap_notice_rest_of_day
    else -> R.string.electricity_cheap_notice_all_day
}

/** Anturit: ensin kaikki nimetyt (MAC→nimi-kartta, rajaton määrä), sitten muut löydetyt
 *  juoksevalla "Anturi N" -nimellä. Nimeäminen: asetukset → Ruuvi-anturit. */
private fun buildSensors(context: Context, prefs: SharedPreferences, repo: RuuviRepository): List<Pair<String, RuuviSample?>> {
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
        out.add(context.getString(R.string.home_sensor_default_name, next) to sample)
        next++
    }
    return out
}

internal fun formatTemp(loc: Locale, v: Double): String {
    if (v.isNaN()) return "-- °C"
    // Vain "-0,0" estetään. WeatherData.cleanZero (|x| < 0,5 → 0) on tabletin kokonaislukunäytölle, ja
    // yhden desimaalin näytössä se muutti esim. 0,3 °C:n muotoon 0,0 °C.
    val shown = if (Math.abs(v) < 0.05) 0.0 else v
    return String.format(loc, "%.1f °C", shown)
}

private fun one(loc: Locale, v: Double): String = String.format(loc, "%.1f", v)

/** "nyt" / "5 min sitten" / "2 h sitten" kielen mukaan (jaettu etusivun korttien kesken). */
internal fun homeAgeText(context: Context, timestamp: Long, nowMs: Long = System.currentTimeMillis()): String {
    val ageMin = Math.max(0L, (nowMs - timestamp) / 60_000L)
    return when {
        ageMin < 1L -> context.getString(R.string.home_now)
        ageMin < 60L -> context.getString(R.string.home_age_minutes, ageMin)
        else -> context.getString(R.string.home_age_hours, ageMin / 60L)
    }
}

/** "850 m" / "1,2 km" kielen desimaalierottimella. */
internal fun homeDistanceText(loc: Locale, meters: Double): String = when {
    meters.isNaN() || meters < 0 -> ""
    meters < 1000.0 -> "${Math.round(meters)} m"
    else -> String.format(loc, "%.1f km", meters / 1000.0)
}

private fun formatClock(ms: Long): String = HMS_HC.format(Date(ms))

private fun hhmm(ms: Long): String = HM_HC.format(Date(ms))
