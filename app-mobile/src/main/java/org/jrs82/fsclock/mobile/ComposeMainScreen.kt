package org.jrs82.fsclock.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Compose-pääruudun RUNKO (navigaatio + dashboard) — VAIHE 2/3 esikatselu. Tämä on uusi,
 * Compose-pohjainen päänäkymä joka korvaa lopulta View-pohjaisen [MobileMainActivity]n.
 * Ajetaan toistaiseksi ERILLISENÄ esikatseluna ([MobileComposeMainActivity], avataan valikosta),
 * jotta nykyinen toimiva sovellus ei riko ja käyttäjä voi testata rinnakkain.
 *
 * Tässä vaiheessa: M3-navigaatiodraweri + header + sektioreititys + etusivun elävä kello.
 * Sektioiden varsinainen sisältö (sää, sähkö, anturit, kartat, transit…) kytketään dataan
 * seuraavissa paloissa (ViewModel/repository-sauma). Raskaat näkymät näkyvät nyt paikkamerkkeinä.
 */

private val FI_MAIN = Locale("fi", "FI")
private val HELSINKI: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

/**
 * Globaali "päivitä"-signaali: yläpalkin Päivitä-ikoni kasvattaa tätä, ja datasektiot lukevat
 * arvon [LaunchedEffect]-avaimena → näkyvillä oleva sektio (etusivu/ennuste/uutiset/sähkö/liikenne)
 * hakee tiedot uudelleen. Vain aktiivinen sektio on sommiteltu, joten päivitys osuu siihen mitä
 * käyttäjä katsoo. Oletus 0; ei vaikuta ennen ensimmäistä painallusta.
 */
val LocalRefreshTick = compositionLocalOf { 0 }

/** Automaattisen sijaintipäivityksen aikaleima (per prosessi) — estää tiheän peräkkäisen geokoodauksen. */
internal var sLastAutoLocMs = 0L

/** Pakottaa seuraavan resume-päivityksen hakemaan sijainnin heti (esim. kun automaattinen sijainti
 *  kytketään päälle asetuksista). */
internal fun resetAutoLocationThrottle() {
    sLastAutoLocMs = 0L
}

/**
 * Päivittää etusivun sääpaikan laitteen sijainnista, jos automaattinen sijainti on päällä (oletus)
 * ja sijaintilupa on annettu. Reverse-geokoodaa MML:llä ja asettaa kotipaikan kuten Paikkakunnat-näkymä
 * ([chooseHomePlace]). Palauttaa true jos paikka päivittyi → kutsuja voi pyytää sään haun uudelleen.
 * [force] ohittaa lyhyen aikaikkunan (suoja resume-tapahtuman toistolle).
 */
internal suspend fun maybeRefreshDeviceLocation(context: Context, force: Boolean): Boolean {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    if (!prefs.getBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true)) return false
    if (!hasLocationPermission(context)) return false
    val now = System.currentTimeMillis()
    if (!force && now - sLastAutoLocMs < 15_000L) return false
    val loc = lastKnownLocation(context) ?: return false
    val place = withContext(Dispatchers.IO) {
        try {
            MmlGeocodingClient.reversePlace(loc.latitude, loc.longitude)
        } catch (e: Exception) {
            null
        }
    } ?: return false
    chooseHomePlace(prefs, place.dataPlace, true, place.displayPlace, loc.latitude, loc.longitude)
    sLastAutoLocMs = now
    return true
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Päänäkymän sektiot (vastaavat nykyisen valikon kohtia). */
enum class HomeSection(val title: String) {
    HOME("Arkikeskus"),
    FORECAST("Sää-ennuste"),
    PLACES("Paikkakunnat"),
    SENSORS("Anturit"),
    TRAFFIC_ACCIDENTS("Onnettomuudet"),
    TRAFFIC_ROADWORKS("Tietyöt"),
    TRAFFIC_WEIGHT("Painorajoitukset"),
    TRAFFIC_INCIDENTS("Liikennetiedotteet"),
    TRAFFIC_CONGESTION("Ruuhkat"),
    ROAD_CAMERAS("Kelikamerat"),
    TRANSIT("Lähilähdöt"),
    ROUTE_PLANNER("Reittihaku"),
    SPEEDOMETER("GPS-nopeus"),
    STEPS("Askeleet"),
    NEWS("Uutiset"),
    ELECTRICITY("Pörssisähkö"),
    DEVICE_INFO("Puhelimen tiedot"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeMainScreen() {
    val context = LocalContext.current
    var section by remember { mutableStateOf(HomeSection.HOME) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Päivitä-ikonin signaali → tarjotaan sektioille CompositionLocalin kautta.
    var refreshTick by remember { mutableStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshRotation = remember { Animatable(0f) }

    // Etusivun sää käyttää laitteen sijaintia: päivitä se kun sovellus tulee etualalle
    // (automaattinen sijainti päällä + lupa annettu). Kaupungin valinta Paikkakunnilla on
    // tilapäinen ja palautuu laitteen sijaintiin seuraavalla avauksella.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { if (maybeRefreshDeviceLocation(context, force = false)) refreshTick++ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Valikko avautuu VAIN hampurilaisikonista (alapalkki) — ei reunavedoksesta. Tämä estää
        // valikon avautumisen oikealle vetäessä joka sivulla ja palauttaa esim. kelikamerakartan
        // yhden sormen panoroinnin (reunavedos söi sen aiemmin).
        gesturesEnabled = false,
        drawerContent = {
            DrawerContent(
                current = section,
                onSelect = { s ->
                    section = s
                    scope.launch { drawerState.close() }
                },
                onSettings = {
                    context.startActivity(Intent(context, MobileSettingsActivity::class.java))
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
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
                        selected = section == HomeSection.HOME,
                        onClick = { section = HomeSection.HOME },
                        icon = {
                            Icon(painterResource(R.drawable.ic_home_24), contentDescription = "Etusivu")
                        },
                        label = { Text("Koti") },
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
                            }
                        },
                        icon = {
                            Icon(
                                painterResource(R.drawable.mobile_ic_refresh_24),
                                contentDescription = "Päivitä",
                                modifier = Modifier.rotate(refreshRotation.value),
                            )
                        },
                        label = { Text("Päivitä") },
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { scope.launch { drawerState.open() } },
                        icon = {
                            Icon(painterResource(R.drawable.mobile_ic_menu_24), contentDescription = "Valikko")
                        },
                        label = { Text("Valikko") },
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
                CompositionLocalProvider(LocalRefreshTick provides refreshTick) {
                when (section) {
                    HomeSection.HOME -> HomeDashboard(onOpenSection = { section = it })
                    HomeSection.FORECAST -> ForecastSection()
                    HomeSection.PLACES -> PlacesSection(onPlaceChosen = { section = HomeSection.HOME })
                    HomeSection.STEPS -> StepsSection()
                    HomeSection.SENSORS -> SensorsSection()
                    HomeSection.ELECTRICITY -> ElectricitySection()
                    HomeSection.NEWS -> NewsSection()
                    HomeSection.DEVICE_INFO -> DeviceInfoSection()
                    HomeSection.ROAD_CAMERAS -> RoadCamerasHost()
                    HomeSection.TRANSIT -> TransitHost()
                    HomeSection.ROUTE_PLANNER -> RoutePlannerHost()
                    HomeSection.SPEEDOMETER -> SpeedometerSection()
                    HomeSection.TRAFFIC_ACCIDENTS -> TrafficSection(TrafficNotice.Kind.ACCIDENT, section.title)
                    HomeSection.TRAFFIC_ROADWORKS -> TrafficSection(TrafficNotice.Kind.ROAD_WORK, section.title)
                    HomeSection.TRAFFIC_WEIGHT -> TrafficSection(TrafficNotice.Kind.WEIGHT_RESTRICTION, section.title)
                    HomeSection.TRAFFIC_INCIDENTS -> TrafficSection(TrafficNotice.Kind.INCIDENT, section.title)
                    HomeSection.TRAFFIC_CONGESTION -> TrafficSection(TrafficNotice.Kind.CONGESTION, section.title)
                }
                }
            }
        }
    }
}

@Composable
private fun DrawerContent(
    current: HomeSection,
    onSelect: (HomeSection) -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Arkikeskus",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
            // Selkeä koti-navigointi: HOME-sektioon pääsee aina valikon ylälaidasta.
            NavigationDrawerItem(
                icon = { Icon(painterResource(R.drawable.ic_home_24), contentDescription = null) },
                label = { Text("Etusivu") },
                selected = current == HomeSection.HOME,
                onClick = { onSelect(HomeSection.HOME) },
                modifier = Modifier.padding(vertical = 2.dp),
            )

            // Valikko ryhmitelty otsikoiden alle selkeyden vuoksi (käyttäjän toive):
            // säähän liittyvät, liikenne, joukkoliikenne ja loput "Muut"-otsikon alle.
            DrawerHeader("Sää")
            DrawerItem(HomeSection.FORECAST, current, onSelect)
            DrawerItem(HomeSection.PLACES, current, onSelect)
            DrawerItem(HomeSection.SENSORS, current, onSelect)

            DrawerHeader("Liikennetiedot")
            DrawerItem(HomeSection.TRAFFIC_ACCIDENTS, current, onSelect)
            DrawerItem(HomeSection.TRAFFIC_ROADWORKS, current, onSelect)
            DrawerItem(HomeSection.TRAFFIC_WEIGHT, current, onSelect)
            DrawerItem(HomeSection.TRAFFIC_INCIDENTS, current, onSelect)
            DrawerItem(HomeSection.TRAFFIC_CONGESTION, current, onSelect)
            DrawerItem(HomeSection.ROAD_CAMERAS, current, onSelect)

            DrawerHeader("Joukkoliikenne (HSL)")
            DrawerItem(HomeSection.TRANSIT, current, onSelect)
            DrawerItem(HomeSection.ROUTE_PLANNER, current, onSelect)

            DrawerHeader("Muut")
            DrawerItem(HomeSection.ELECTRICITY, current, onSelect)
            DrawerItem(HomeSection.NEWS, current, onSelect)
            DrawerItem(HomeSection.SPEEDOMETER, current, onSelect)
            DrawerItem(HomeSection.STEPS, current, onSelect)
            DrawerItem(HomeSection.DEVICE_INFO, current, onSelect)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                label = { Text("Asetukset") },
                selected = false,
                onClick = onSettings,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DrawerItem(section: HomeSection, current: HomeSection, onSelect: (HomeSection) -> Unit) {
    NavigationDrawerItem(
        label = { Text(section.title) },
        selected = current == section,
        onClick = { onSelect(section) },
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun DrawerHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
