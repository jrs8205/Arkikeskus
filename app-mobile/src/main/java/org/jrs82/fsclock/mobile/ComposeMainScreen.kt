package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    ModalNavigationDrawer(
        drawerState = drawerState,
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
            topBar = {
                TopAppBar(
                    title = { Text(section.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", fontSize = 24.sp)
                        }
                    },
                    actions = {
                        IconButton(onClick = { toastSoon(context) }) {
                            Icon(painterResource(R.drawable.mobile_ic_search_24), contentDescription = "Haku")
                        }
                        IconButton(onClick = { toastSoon(context) }) {
                            Icon(painterResource(R.drawable.mobile_ic_my_location_24), contentDescription = "Sijainti")
                        }
                        IconButton(onClick = { toastSoon(context) }) {
                            Icon(painterResource(R.drawable.mobile_ic_refresh_24), contentDescription = "Päivitä")
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (section) {
                    HomeSection.HOME -> HomeDashboard()
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

            DrawerItem(HomeSection.SPEEDOMETER, current, onSelect)
            DrawerItem(HomeSection.STEPS, current, onSelect)
            DrawerItem(HomeSection.NEWS, current, onSelect)
            DrawerItem(HomeSection.ELECTRICITY, current, onSelect)
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

private fun toastSoon(context: Context) {
    Toast.makeText(context, "Tämä toiminto kytketään seuraavassa palassa.", Toast.LENGTH_SHORT).show()
}
