package org.jrs82.fsclock.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jrs82.fsclock.BuildConfig
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Compose-pääruudun runko: navigaatio (alapalkki + valikko-overlay) + sektioreititys.
 * Tämä on sovelluksen ainoa päänäkymä ([MobileComposeMainActivity] = launcher); View-pohjainen
 * MobileMainActivity on poistettu Compose-migraation valmistuttua. Kaikki sektiot ovat
 * natiiveja composableja — paitsi Reittihaku, joka on tietoisesti View-toteutus
 * Fragment-sillan takana (päätös 5.3-A, ks. reititys alla).
 */

private val HELSINKI: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

/**
 * Globaali "päivitä"-signaali: yläpalkin Päivitä-ikoni kasvattaa tätä, ja datasektiot lukevat
 * arvon [LaunchedEffect]-avaimena → näkyvillä oleva sektio (etusivu/ennuste/uutiset/sähkö/liikenne)
 * hakee tiedot uudelleen. Vain aktiivinen sektio on sommiteltu, joten päivitys osuu siihen mitä
 * käyttäjä katsoo. Oletus 0; ei vaikuta ennen ensimmäistä painallusta.
 */
val LocalRefreshTick = compositionLocalOf { 0 }

/**
 * Uutisten oma päivityssignaali: kasvaa VAIN Päivitä-napista ja automaattivälistä — EI sovelluksen
 * palatessa etualalle (toisin kuin [LocalRefreshTick]). Näin selaimesta/taustalta paluu ei hae
 * uutisia uudelleen eikä nollaa listan vieritystä; uutiset päivittyvät vain kylmästartissa
 * (ensikoostumus), Päivitä-napista tai asetusten päivitysvälistä.
 */
val LocalNewsRefreshTick = compositionLocalOf { 0 }

/** Säänäkymien päivityssukupolvi ([SharedHomeWeather.newGeneration]): uusi jokaisella [LocalRefreshTick]illä ja
 *  jokaisessa uudessa näkymäistunnossa. 0 = ei sukupolvea (esikatselu), jolloin data haetaan aina. */
val LocalWeatherGeneration = compositionLocalOf { 0L }

/** Paikallinen asetus-/datarevision signaali. Ei pakota verkkohakuja kuten [LocalRefreshTick]. */
val LocalHomeDataRevision = compositionLocalOf { 0 }

/** Uutislähteiden revisiosignaali. Käynnistää RSS-haun ilman pakotettua verkkopyyntöä. */
val LocalHomeNewsRevision = compositionLocalOf { 0 }

/** Automaattisen sijaintipäivityksen aikaleima (per prosessi) — estää tiheän peräkkäisen geokoodauksen. */
internal var sLastAutoLocMs = 0L

/** Viimeisimmän ON_RESUME-virkistyksen aikaleima (per prosessi) — throttle, jottei esim. selaimesta tai
 *  lupadialogista palaaminen pakota toistuvia verkkohakuja. Asetusmuutokset virkistyvät erikseen. */
private var sLastResumeRefreshMs = 0L

/** Pakottaa seuraavan resume-päivityksen hakemaan sijainnin heti (esim. kun automaattinen sijainti
 *  kytketään päälle asetuksista). */
internal fun resetAutoLocationThrottle() {
    sLastAutoLocMs = 0L
}

/**
 * Päivittää etusivun sääpaikan laitteen sijainnista, jos automaattinen sijainti on päällä (oletus)
 * ja sijaintilupa on annettu. Nimeää paikan [DevicePlaceResolver]illa (MML → Geocoder, vain Suomi) ja
 * asettaa kotipaikan kuten Paikkakunnat-näkymä ([chooseHomePlace]). Palauttaa true jos data- tai näyttöpaikka muuttui → kutsuja voi pyytää sään haun uudelleen.
 * [force] ohittaa lyhyen aikaikkunan (suoja resume-tapahtuman toistolle).
 */
internal suspend fun maybeRefreshDeviceLocation(context: Context, force: Boolean): Boolean {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    if (!prefs.getBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true)) return false
    if (!hasLocationPermission(context)) return false
    val now = System.currentTimeMillis()
    if (!force && now - sLastAutoLocMs < 15_000L) return false
    // force (Päivitä-nappi) → aina tuore aktiivinen haku; muuten nopea polku jos viimeisin tunnettu on tuore.
    val loc = deviceLocation(context, force) ?: return false
    val place = DevicePlaceResolver.resolve(context, loc.latitude, loc.longitude) ?: return false
    val previousDataPlace = SettingsManager.get().homePlace.trim()
    val previousDisplayPlace = prefs.getString(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME, "")
        ?.trim().orEmpty()
    val newDataPlace = place.dataPlace.trim()
    val newDisplayPlace = place.displayPlace.trim()
    val placeChanged = !previousDataPlace.equals(newDataPlace, ignoreCase = true) ||
        !previousDisplayPlace.equals(newDisplayPlace, ignoreCase = true)
    chooseHomePlace(prefs, place.dataPlace, true, place.displayPlace, loc.latitude, loc.longitude)
    sLastAutoLocMs = now
    return placeChanged
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Widget-workerin (tausta) PASSIIVINEN kotipaikan päivitys: kuten [maybeRefreshDeviceLocation], mutta
 * EI aktiivista sijaintihakua — käyttää vain [passiveDeviceLocation]ia (cachen viimeisin tunnettu) → ~0 akkua,
 * toimii taustalla ACCESS_BACKGROUND_LOCATIONin turvin. Geokoodaa + päivittää kotipaikan VAIN jos siirrytty
 * merkittävästi (> ~400 m), jottei MML-hakua tehdä turhaan joka workerilla. Palauttaa true jos data- tai
 * näyttöpaikka muuttui (→ kutsuja voi pakottaa sään haun heti).
 */
internal suspend fun maybeRefreshWidgetLocationPassive(context: Context): Boolean {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    if (!prefs.getBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true)) return false
    if (!hasLocationPermission(context)) return false
    val loc = passiveDeviceLocation(context) ?: return false
    val sm = SettingsManager.get()
    // Liikuttu liian vähän kotikoordinaateista → ei geokoodata/päivitetä (säästää verkon + cache-churnin).
    // 150 m: tarpeeksi pieni että kaupunginosan vaihtuminen (esim. Raappavuoret↔Laajavuori) huomataan kuten
    // sovelluksessa, mutta suodattaa paikallaan ollessa fix-jitterin (~15–100 m tarkkuus).
    if (sm.hasHomeCoordinates()) {
        val res = FloatArray(1)
        android.location.Location.distanceBetween(sm.homeLatitude, sm.homeLongitude, loc.latitude, loc.longitude, res)
        if (res[0] < 150f) return false
    }
    val place = DevicePlaceResolver.resolve(context, loc.latitude, loc.longitude) ?: return false
    val previousDataPlace = sm.homePlace.trim()
    val previousDisplayPlace = prefs.getString(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME, "")
        ?.trim().orEmpty()
    val placeChanged = !previousDataPlace.equals(place.dataPlace.trim(), ignoreCase = true) ||
        !previousDisplayPlace.equals(place.displayPlace.trim(), ignoreCase = true)
    chooseHomePlace(prefs, place.dataPlace, true, place.displayPlace, loc.latitude, loc.longitude)
    return placeChanged
}

/** Etusivun yläreunan ilmoitus uudesta versiosta: Päivitä lataa + asentaa APK:n, Hylkää piilottaa version. */
@Composable
private fun UpdateBanner(info: AppUpdater.ReleaseInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var showNotes by remember { mutableStateOf(false) }
    if (showNotes) {
        AlertDialog(
            onDismissRequest = { showNotes = false },
            title = { Text(stringResource(R.string.main_update_whats_new_title, info.versionName)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(info.notes, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { showNotes = false }) { Text(stringResource(R.string.common_close)) } },
        )
    }
    ArkiCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 2.dp)) {
            Text(
                stringResource(R.string.main_update_available, info.versionName),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (info.notes.isNotBlank()) {
                        showNotes = true
                    } else {
                        // Ei muutoslokia → avaa julkaisusivu selaimeen.
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text(stringResource(R.string.main_update_whats_new)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_dismiss)) }
                TextButton(onClick = {
                    val url = info.apkUrl
                    if (url != null) {
                        status = context.getString(R.string.main_update_downloading)
                        AppUpdater.downloadAndInstall(context, url) { status = it }
                    } else {
                        // Releasella ei APK-liitettä → avaa julkaisusivu selaimeen.
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text(stringResource(R.string.main_update_install)) }
            }
        }
    }
}

/** Päänäkymän sektiot (vastaavat nykyisen valikon kohtia). Enumin nimi on deep-link-avain, ei näkyvä teksti. */
enum class HomeSection(@StringRes val titleRes: Int) {
    HOME(R.string.main_section_home),
    FORECAST(R.string.main_section_forecast),
    WEATHER_WARNINGS(R.string.main_section_weather_warnings),
    PLACES(R.string.main_section_places),
    SENSORS(R.string.main_section_sensors),
    TRAFFIC_ACCIDENTS(R.string.main_section_traffic_accidents),
    TRAFFIC_ROADWORKS(R.string.main_section_traffic_roadworks),
    TRAFFIC_WEIGHT(R.string.main_section_traffic_weight),
    TRAFFIC_INCIDENTS(R.string.main_section_traffic_incidents),
    TRAFFIC_CONGESTION(R.string.main_section_traffic_congestion),
    ROAD_CAMERAS(R.string.main_section_road_cameras),
    TRANSIT(R.string.main_section_transit),
    ROUTE_PLANNER(R.string.main_section_route_planner),
    HSL_DISRUPTIONS(R.string.main_section_hsl_disruptions),
    SPEEDOMETER(R.string.main_section_speedometer),
    STEPS(R.string.main_section_steps),
    WORKOUT(R.string.main_section_workout),
    NEWS(R.string.main_section_news),
    NEWS_FOREIGN(R.string.main_section_news_foreign),
    OWN_FEEDS(R.string.main_section_own_feeds),
    ELECTRICITY(R.string.main_section_electricity),
    TELETEXT(R.string.main_section_teletext),
    TV_GUIDE(R.string.main_section_tv_guide),
    DEVICE_INFO(R.string.main_section_device_info),
    FLIGHTS(R.string.main_section_flights),
}

/** Sektion talletus Activityn uudelleenluontiin (rememberSaveable): enum → nimi → enum. */
private val HomeSectionSaver = Saver<HomeSection, String>(
    save = { it.name },
    restore = { runCatching { HomeSection.valueOf(it) }.getOrDefault(HomeSection.HOME) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeMainScreen(
    externalSection: androidx.compose.runtime.MutableState<String?>? = null,
    // Kutsutaan kun ulkoinen sektio (widget/ilmoitus) on navigoitu -> Activity tyhjentää launch-intentin,
    // jottei relaunch/recreate toista deep-linkkiä. Oletus tyhjä (esikatselut/testit).
    onSectionConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    // rememberSaveable → valittu sektio säilyy Activityn uudelleenluonnissa (esim. dynamic-color-/teemanvaihto),
    // jolloin hostatut fragmentit (kelikamerat/lähilähdöt/reittihaku) eivät jää orvoiksi näkymättömään konttiin.
    var section by rememberSaveable(stateSaver = HomeSectionSaver) { mutableStateOf(HomeSection.HOME) }
    // Valikko (hampurilainen): piirretään sisältöalueen päälle ILMAN liukuanimaatiota (ilmestyy
    // heti kun Valikkoa klikataan) ja JÄTTÄÄ alapalkin näkyviin (overlay vain sisällön päällä).
    var menuOpen by remember { mutableStateOf(false) }
    // Asetukset samanlaisena overlayna kuin valikko (alapalkki jää näkyviin, ratas korostuu).
    // rememberSaveable → teeman-/dynamic color -vaihdon recreate palauttaa asetukset auki.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Asetusten alasivu (keskussivu HUB + 10 alasivua). rememberSaveable → teemanvaihdon recreate
    // pitää käyttäjän samalla alasivulla. Avattaessa nollataan keskussivulle (ks. alapalkki).
    var settingsPage by rememberSaveable(stateSaver = SettingsPageSaver) { mutableStateOf(SettingsPage.HUB) }
    val scope = rememberCoroutineScope()
    // Päivitä-ikonin signaali → tarjotaan sektioille CompositionLocalin kautta.
    var refreshTick by remember { mutableStateOf(0) }
    // Prosessin sisällä yksilöllinen: refreshTick nollautuu Activityn uudelleenluonnissa, sukupolvi ei toistu.
    val weatherGeneration = remember(refreshTick) { SharedHomeWeather.newGeneration() }
    // Uutisten oma tick: kasvaa vain Päivitä-napista + automaattivälistä, EI ON_RESUME:ssa → uutislistat
    // eivät hae/nollaudu selaimesta tai taustalta palatessa (ks. [LocalNewsRefreshTick]).
    var newsRefreshTick by remember { mutableStateOf(0) }
    var homeDataRevision by remember { mutableIntStateOf(0) }
    var homeNewsRevision by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshRotation = remember { Animatable(0f) }

    // Päivitysilmoitus: löydetty uusi versio talletetaan prefseihin (banneri näkyy heti myös
    // välikäynnistyksillä), verkkotarkistus max 6 h välein. Hylkäys piilottaa kyseisen version.
    var updateInfo by remember { mutableStateOf<AppUpdater.ReleaseInfo?>(null) }
    LaunchedEffect(Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val dismissed = prefs.getString(AppUpdater.PREF_DISMISSED_VERSION, null)
        val savedVer = prefs.getString(AppUpdater.PREF_AVAILABLE_VERSION, null)
        if (savedVer != null && savedVer != dismissed &&
            AppUpdater.isNewer(BuildConfig.VERSION_NAME, savedVer)
        ) {
            updateInfo = AppUpdater.ReleaseInfo(
                "v$savedVer",
                savedVer,
                prefs.getString(AppUpdater.PREF_AVAILABLE_URL, null),
                prefs.getString(AppUpdater.PREF_AVAILABLE_HTML, null) ?: AppUpdater.REPO_URL,
                prefs.getString(AppUpdater.PREF_AVAILABLE_NOTES, null) ?: "",
            )
        }
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(AppUpdater.PREF_LAST_AUTO_CHECK, 0L) < AppUpdater.AUTO_CHECK_INTERVAL_MS) {
            return@LaunchedEffect
        }
        AppUpdater.checkLatest(BuildConfig.VERSION_NAME) { rel, newer ->
            if (rel == null) return@checkLatest // verkkovirhe → uusi yritys seuraavalla käynnistyksellä
            prefs.edit().putLong(AppUpdater.PREF_LAST_AUTO_CHECK, System.currentTimeMillis()).apply()
            if (newer) {
                prefs.edit()
                    .putString(AppUpdater.PREF_AVAILABLE_VERSION, rel.versionName)
                    .putString(AppUpdater.PREF_AVAILABLE_URL, rel.apkUrl)
                    .putString(AppUpdater.PREF_AVAILABLE_HTML, rel.htmlUrl)
                    .putString(AppUpdater.PREF_AVAILABLE_NOTES, rel.notes)
                    .apply()
                if (rel.versionName != prefs.getString(AppUpdater.PREF_DISMISSED_VERSION, null)) {
                    updateInfo = rel
                }
            } else {
                // Ajan tasalla → siivoa talletus, ettei vanhentunut banneri palaa.
                prefs.edit()
                    .remove(AppUpdater.PREF_AVAILABLE_VERSION)
                    .remove(AppUpdater.PREF_AVAILABLE_URL)
                    .remove(AppUpdater.PREF_AVAILABLE_HTML)
                    .remove(AppUpdater.PREF_AVAILABLE_NOTES)
                    .apply()
            }
        }
    }

    // Etusivun sää käyttää laitteen sijaintia: päivitä se kun sovellus tulee etualalle
    // (automaattinen sijainti päällä + lupa annettu). Kaupungin valinta Paikkakunnilla on
    // tilapäinen ja palautuu laitteen sijaintiin seuraavalla avauksella.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Virkistä näkyvä data palatessa etualalle (esim. sovelluksen uudelleenavaus) — THROTTLATTU
                // (väh. 30 s välein), jottei selaimesta/lupadialogista palaaminen pakota toistuvia hakuja.
                // Päivittää myös laitteen sijainnin (jos auto päällä). Asetusmuutokset virkistyvät erikseen.
                scope.launch {
                    val placeChanged = maybeRefreshDeviceLocation(context, force = false)
                    val now = System.currentTimeMillis()
                    if (placeChanged || now - sLastResumeRefreshMs > 30_000L) {
                        sLastResumeRefreshMs = now
                        refreshTick++
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Automaattinen päivitysväli (asetus): kasvata refreshTickiä valitun välin mukaan VAIN sovelluksen
    // ollessa etualalla (repeatOnLifecycle RESUMED → silmukka pysähtyy taustalla, ei turhaa verkkoa/akkua).
    // Väli luetaan joka kierroksella, joten asetusmuutos otetaan huomioon.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val minutes = (
                    PreferenceManager.getDefaultSharedPreferences(context).getString(
                        MobileThemeController.KEY_UPDATE_INTERVAL_MINUTES,
                        MobileThemeController.DEFAULT_UPDATE_INTERVAL_MINUTES,
                    )?.toIntOrNull() ?: 15
                    ).coerceAtLeast(1)
                delay(minutes * 60_000L)
                refreshTick++
                newsRefreshTick++
            }
        }
    }

    // Asetusmuutokset (uutislähteet, oma syöte, sähköraja/-tila/-huomio, anturinimet) → virkistä etusivu
    // HETI ja tyhjennä uutisvälimuisti, jotta muutos näkyy asetuksista palatessa (ei vasta seuraavalla
    // Päivitä-painalluksella). Tämä on erillään ON_RESUME-throttlesta → kohdistettu, ei turhaa verkkoa.
    DisposableEffect(Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (isHomeDataPrefKey(key)) {
                if (isHomeNewsListKey(key)) {
                    invalidateHomeNewsCache()
                    homeNewsRevision++
                }
                if (isHomeWeatherIdentityPrefKey(key)) {
                    invalidateHomeWeatherCache()
                }
                homeDataRevision++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Ulkoinen navigointipyyntö (lenkkinotifikaation napautus / lenkin palautus avauksessa).
    LaunchedEffect(externalSection?.value) {
        val name = externalSection?.value ?: return@LaunchedEffect
        runCatching { HomeSection.valueOf(name) }.getOrNull()?.let {
            section = it
            menuOpen = false
            settingsOpen = false
        }
        // Kuluta deep-link vasta TÄSSÄ (navigoinnin jälkeen): kylmän käynnistyksen relaunch ei näin
        // hukkaa sektiota, ja launch-intent tyhjenee jottei myöhempi recreate bounssaa takaisin.
        onSectionConsumed()
        externalSection.value = null
    }

    // Takaisin-ele/nappi: sektiosta palataan ensin etusivulle, ja vasta etusivulta takaisin
    // siirtää sovelluksen taustalle (Android-oletus). Sisemmät käsittelijät (valikko-/asetus-
    // overlay, Teksti-TV:n sivuhistoria, fragmenttien omat) rekisteröityvät myöhemmin → ne
    // voittavat tämän kun ovat aktiivisia.
    BackHandler(enabled = section != HomeSection.HOME) { section = HomeSection.HOME }

    Scaffold(
            bottomBar = {
                // Kiinteä alapalkki (HSL Reittiopas -tyyli): kolme ikonia tekstilappuineen.
                // Koti (vasen) korostuu kun ollaan etusivulla; Päivitä (keski) hakee datan
                // pyörähdys- + värinäanimaatiolla; Valikko (oikea) avaa hampurilaisvalikon.
                // NavigationBarItem antaa automaattisesti ≥48dp kosketusalat. Sovelluksen nimeä
                // ei näytetä. Erotinviivat + oma sävy puhelimen navigaatiopalkin (nuolet) alueelle,
                // jotta sovelluksen palkki erottuu järjestelmän palkista (erit. tumma teema).
                Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    NavigationBarItem(
                        // Korostus vain kun etusivu on AIDOSTI näkyvissä — ei valikon/asetusten alla
                        // (muuten Koti + Valikko palaisivat yhtä aikaa).
                        selected = section == HomeSection.HOME && !menuOpen && !settingsOpen,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            section = HomeSection.HOME
                            menuOpen = false
                            settingsOpen = false
                        },
                        icon = {
                            Icon(painterResource(R.drawable.ic_home_24), contentDescription = stringResource(R.string.main_section_home))
                        },
                        label = { NavLabel(stringResource(R.string.main_nav_home)) },
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                refreshRotation.snapTo(0f)
                                refreshRotation.animateTo(360f, animationSpec = tween(700))
                            }
                            scope.launch {
                                // Pakota laitteen sijainnin päivitys (jos auto päällä) + datan haku.
                                maybeRefreshDeviceLocation(context, force = true)
                                refreshTick++
                                newsRefreshTick++
                            }
                        },
                        icon = {
                            Icon(
                                painterResource(R.drawable.mobile_ic_refresh_24),
                                contentDescription = stringResource(R.string.common_refresh),
                                modifier = Modifier.rotate(refreshRotation.value),
                            )
                        },
                        label = { NavLabel(stringResource(R.string.common_refresh)) },
                    )
                    NavigationBarItem(
                        selected = menuOpen,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = !menuOpen
                            settingsOpen = false
                        },
                        icon = {
                            Icon(painterResource(R.drawable.mobile_ic_menu_24), contentDescription = stringResource(R.string.main_menu))
                        },
                        label = { NavLabel(stringResource(R.string.main_menu)) },
                    )
                    // Asetukset suoraan alapalkissa (käyttäjän toive: valikon perällä sitä ei löydä).
                    // Avautuu overlayna kuten valikko (aina keskussivulle); alasivulla napautus tekee
                    // pop-to-rootin (keskussivulle); keskussivulla napautus sulkee.
                    NavigationBarItem(
                        selected = settingsOpen,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = false
                            when {
                                !settingsOpen -> {
                                    settingsPage = SettingsPage.HUB
                                    settingsOpen = true
                                }
                                settingsPage != SettingsPage.HUB -> settingsPage = SettingsPage.HUB
                                else -> settingsOpen = false
                            }
                        },
                        icon = {
                            Icon(painterResource(R.drawable.mobile_ic_settings_24), contentDescription = stringResource(R.string.common_settings))
                        },
                        label = { NavLabel(stringResource(R.string.common_settings)) },
                    )
                }
                // Puhelimen oman navigaatiopalkin (koti/takaisin/nuolet) alue: oma sävy + erotinviiva.
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                CompositionLocalProvider(
                    LocalRefreshTick provides refreshTick,
                    LocalWeatherGeneration provides weatherGeneration,
                    LocalNewsRefreshTick provides newsRefreshTick,
                    LocalHomeDataRevision provides homeDataRevision,
                    LocalHomeNewsRevision provides homeNewsRevision,
                ) {
                val sectionTitle = stringResource(section.titleRes)
                when (section) {
                    HomeSection.HOME -> Column(modifier = Modifier.fillMaxSize()) {
                        updateInfo?.let { info ->
                            UpdateBanner(
                                info = info,
                                onDismiss = {
                                    PreferenceManager.getDefaultSharedPreferences(context).edit()
                                        .putString(AppUpdater.PREF_DISMISSED_VERSION, info.versionName)
                                        .apply()
                                    updateInfo = null
                                },
                            )
                        }
                        HomeDashboard(onOpenSection = { section = it })
                    }
                    HomeSection.FORECAST -> ForecastSection()
                    HomeSection.WEATHER_WARNINGS -> WarningsSection()
                    HomeSection.FLIGHTS -> FlightsSection()
                    HomeSection.PLACES -> PlacesSection(onPlaceChosen = { section = HomeSection.HOME })
                    HomeSection.STEPS -> StepsSection()
                    HomeSection.WORKOUT -> WorkoutScreen()
                    HomeSection.SENSORS -> SensorsSection()
                    HomeSection.ELECTRICITY -> ElectricitySection()
                    HomeSection.NEWS -> NewsCategoryScreen(domestic = true)
                    HomeSection.NEWS_FOREIGN -> NewsCategoryScreen(domestic = false)
                    HomeSection.OWN_FEEDS -> OwnFeedsScreen()
                    // TV-ohjelmat = Teksti-TV:n ohjelmaopassivut 300– (kattaa myös MTV3/Nelosen;
                    // Ylen julkinen API ei tarjoa erillistä EPG:tä, ja tämä on lisenssin mukainen).
                    HomeSection.TELETEXT -> TeletextScreen(100, sectionTitle)
                    HomeSection.TV_GUIDE -> TeletextScreen(300, sectionTitle)
                    HomeSection.DEVICE_INFO -> DeviceInfoSection()
                    HomeSection.ROAD_CAMERAS -> RoadCamerasScreen()
                    HomeSection.TRANSIT -> TransitScreen()
                    // PÄÄTÖS 5.3-A (migraatioraportti): reittihaku pidetään tietoisesti View-
                    // toteutuksena Compose-kuoren sisällä. Sen kolmitilainen BottomSheet (peek/
                    // 52 %/täysi) + kartan padding-koreografia ei mäppäydy M3 1.2.1:n kaksitilaiseen
                    // sheetiin ilman omaa elekoodia → uudelleenkirjoituksen regressioriski ylittäisi
                    // hyödyn. Visuaalinen yhtenäisyys tulee jaetusta kuoresta (drawer/alapalkki/teema).
                    HomeSection.ROUTE_PLANNER -> RoutePlannerHost()
                    HomeSection.HSL_DISRUPTIONS -> HslDisruptionsScreen()
                    HomeSection.SPEEDOMETER -> SpeedometerSection()
                    HomeSection.TRAFFIC_ACCIDENTS -> TrafficSection(TrafficNotice.Kind.ACCIDENT, sectionTitle)
                    HomeSection.TRAFFIC_ROADWORKS -> TrafficSection(TrafficNotice.Kind.ROAD_WORK, sectionTitle)
                    HomeSection.TRAFFIC_WEIGHT -> TrafficSection(TrafficNotice.Kind.WEIGHT_RESTRICTION, sectionTitle)
                    HomeSection.TRAFFIC_INCIDENTS -> TrafficSection(TrafficNotice.Kind.INCIDENT, sectionTitle)
                    HomeSection.TRAFFIC_CONGESTION -> TrafficSection(TrafficNotice.Kind.CONGESTION, sectionTitle)
                }
                }

                // Valikko KOKO sisältöalueen kokoisena (peittää etusivun kokonaan; alapalkki jää
                // näkyviin sen alle). Ilmestyy heti, ei liukuanimaatiota. Sulkeutuu Valikko-napista,
                // Takaisin-eleellä tai valitsemalla kohdan.
                if (menuOpen) {
                    DrawerContent(
                        modifier = Modifier.fillMaxSize(),
                        current = section,
                        onSelect = { s ->
                            section = s
                            menuOpen = false
                        },
                    )
                    BackHandler(enabled = true) { menuOpen = false }
                }

                // Asetukset-overlay (kuten valikko): sisältöalueen päällä, alapalkki näkyvissä.
                // Takaisin-käsittely on SettingsScreenin sisällä (alasivu → keskussivu → sulje).
                if (settingsOpen) {
                    SettingsScreen(
                        page = settingsPage,
                        onPageChange = { settingsPage = it },
                        onClose = { settingsOpen = false },
                    )
                }
            }
        }
}

@Composable
private fun DrawerContent(
    modifier: Modifier = Modifier,
    current: HomeSection,
    onSelect: (HomeSection) -> Unit,
) {
    Surface(
        // Sama taustaväri kuin asetussivulla → valikko ja asetukset näyttävät yhtenäisiltä.
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.main_menu),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
            // Etusivulle pääsee alapalkin Koti-painikkeesta → ei erillistä "Etusivu"-kohtaa valikossa.

            // Valikko ryhmitelty otsikoiden alle selkeyden vuoksi (käyttäjän toive):
            // säähän liittyvät, liikenne, joukkoliikenne ja loput "Muut"-otsikon alle.
            DrawerHeader(stringResource(R.string.main_drawer_weather), R.drawable.mobile_ic_weather_24)
            DrawerItem(HomeSection.FORECAST, current, onSelect)
            DrawerItem(HomeSection.WEATHER_WARNINGS, current, onSelect)
            DrawerItem(HomeSection.PLACES, current, onSelect)
            DrawerItem(HomeSection.SENSORS, current, onSelect)

            DrawerHeader(stringResource(R.string.main_drawer_traffic), R.drawable.mobile_ic_car_24)
            DrawerItem(HomeSection.TRAFFIC_ACCIDENTS, current, onSelect)
            DrawerItem(HomeSection.TRAFFIC_ROADWORKS, current, onSelect)
            DrawerItem(HomeSection.TRAFFIC_WEIGHT, current, onSelect)
            DrawerItem(HomeSection.TRAFFIC_INCIDENTS, current, onSelect)
            DrawerItem(HomeSection.TRAFFIC_CONGESTION, current, onSelect)
            DrawerItem(HomeSection.ROAD_CAMERAS, current, onSelect)

            DrawerHeader(stringResource(R.string.main_drawer_transit), R.drawable.mobile_ic_bus_24)
            DrawerItem(HomeSection.TRANSIT, current, onSelect)
            DrawerItem(HomeSection.ROUTE_PLANNER, current, onSelect)
            DrawerItem(HomeSection.HSL_DISRUPTIONS, current, onSelect)

            DrawerHeader(stringResource(R.string.main_drawer_tv), R.drawable.mobile_ic_apps_24)
            DrawerItem(HomeSection.TELETEXT, current, onSelect)
            DrawerItem(HomeSection.TV_GUIDE, current, onSelect)

            DrawerHeader(stringResource(R.string.main_drawer_news), R.drawable.mobile_ic_news_24)
            DrawerItem(HomeSection.NEWS, current, onSelect)
            DrawerItem(HomeSection.NEWS_FOREIGN, current, onSelect)
            DrawerItem(HomeSection.OWN_FEEDS, current, onSelect)

            DrawerHeader(stringResource(R.string.main_drawer_fitness), R.drawable.mobile_ic_apps_24)
            DrawerItem(HomeSection.WORKOUT, current, onSelect)
            DrawerItem(HomeSection.STEPS, current, onSelect)
            DrawerItem(HomeSection.SPEEDOMETER, current, onSelect)

            DrawerHeader(stringResource(R.string.main_drawer_other), R.drawable.mobile_ic_apps_24)
            DrawerItem(HomeSection.ELECTRICITY, current, onSelect)
            DrawerItem(HomeSection.DEVICE_INFO, current, onSelect)
            DrawerItem(HomeSection.FLIGHTS, current, onSelect)
            // Asetukset siirretty alapalkkiin (ratas oikeassa reunassa) — löydettävyys.

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DrawerItem(section: HomeSection, current: HomeSection, onSelect: (HomeSection) -> Unit) {
    // Kukin valikon kohta omana pyöristettynä laatikkona (sama tyyli kuin asetussivulla).
    // Nykyinen sektio korostetaan primaryContainer-värillä.
    val selected = current == section
    ArkiCard(
        onClick = { onSelect(section) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = ItemBoxShape,
        colors = if (selected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Text(
            stringResource(section.titleRes),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun DrawerHeader(text: String, iconRes: Int? = null) {
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Alapalkin teksti yhdellä rivillä: pitkä käännös (sv "Inställningar") isolla fonttikoolla
 *  kutistetaan mahtumaan, muuten se rivittyisi ja nostaisi ikonin eri tasolle kuin muiden. */
@Composable
private fun NavLabel(text: String) {
    val style = LocalTextStyle.current
    var scale by remember(text) { mutableFloatStateOf(1f) }
    Text(
        text,
        style = style.copy(fontSize = style.fontSize * scale),
        maxLines = 1,
        softWrap = false,
        onTextLayout = { if (it.didOverflowWidth && scale > 0.7f) scale -= 0.05f },
    )
}
