package org.jrs82.fsclock.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.mobile.gps.GpsDashboardScreen
import org.jrs82.fsclock.mobile.gps.GpsPage
import org.jrs82.fsclock.mobile.gps.TripHistoryScreen
import org.jrs82.fsclock.mobile.gps.gpsPage

/**
 * Lisäsektiot: Puhelimen tiedot, Liikennetiedot, GPS-nopeus (natiivi Compose) sekä
 * [FragmentHostSection]-silta, jolla Reittihaku hostataan View-Fragmenttina (päätös 5.3-A —
 * kelikamerat ja lähilähdöt migratoitiin natiiviksi Composeksi, ks. RoadCamerasScreen/TransitScreen).
 */

// ===================== Fragment-silta (AndroidView + FragmentContainerView) =====================

/**
 * Hostaa olemassa olevan [Fragment]in Composessa: luo [FragmentContainerView]n ja committaa
 * fragmentin Activityn supportFragmentManageriin. Poistaa fragmentin kun sektiosta poistutaan
 * (DisposableEffect onDispose) → ei vuoda eikä jää sieppaamaan back-painalluksia. Activity on
 * AppCompat (FragmentActivity), joten fragmentit toimivat normaalisti.
 */
@Composable
private fun FragmentHostSection(
    tag: String,
    create: () -> Fragment,
    onShown: (Fragment) -> Unit = {},
    onHidden: (Fragment) -> Unit = {},
) {
    val activity = LocalContext.current as FragmentActivity
    val containerId = rememberSaveable { View.generateViewId() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
    )
    DisposableEffect(containerId) {
        val fm = activity.supportFragmentManager
        var frag = fm.findFragmentByTag(tag)
        // Activityn uudelleenluonnissa (fonttikoko, kieli, teema) FragmentManager palauttaa fragmentin
        // ennen kuin Compose on luonut säiliön → näkymä jää irralleen ja sektio tyhjäksi. Korvataan uudella.
        if (frag != null && frag.view?.parent == null && !fm.isStateSaved) {
            fm.beginTransaction().remove(frag).commitNowAllowingStateLoss()
            frag = null
        }
        if (frag == null && !fm.isStateSaved) {
            frag = create()
            fm.beginTransaction().add(containerId, frag, tag).commitNowAllowingStateLoss()
        }
        if (frag != null) onShown(frag)
        onDispose {
            val f = fm.findFragmentByTag(tag)
            if (f != null && !activity.isFinishing && !activity.isDestroyed) {
                onHidden(f)
                if (!fm.isStateSaved) {
                    fm.beginTransaction().remove(f).commitNowAllowingStateLoss()
                }
            }
        }
    }
}

@Composable
internal fun RoutePlannerHost() {
    FragmentHostSection(
        tag = "compose_route_planner",
        create = { RoutePlannerFragment() },
        onShown = { (it as? RoutePlannerFragment)?.onSectionShown() },
        onHidden = { (it as? RoutePlannerFragment)?.onSectionHidden() },
    )
}

// ===================== Puhelimen tiedot (DeviceInfoReaders) =====================

private class DeviceBlock(val title: String, val body: String, val needsPermission: PermissionNeed?)

private enum class PermissionNeed(@StringRes val label: Int, val permissions: Array<String>) {
    LOCATION(
        R.string.home_device_perm_location,
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),
    PHONE_AND_LOCATION(
        R.string.home_device_perm_phone_location,
        arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
    ),
    PHONE(
        R.string.home_device_perm_phone,
        arrayOf(Manifest.permission.READ_PHONE_STATE),
    ),
}

@Composable
internal fun DeviceInfoSection() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }

    val hasLocation = hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPerm(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    val hasPhone = hasPerm(context, Manifest.permission.READ_PHONE_STATE)

    var blocks by remember { mutableStateOf<List<DeviceBlock>>(emptyList()) }
    LaunchedEffect(tick) {
        blocks = withContext(Dispatchers.IO) {
            listOf(
                DeviceBlock(context.getString(R.string.home_device_battery), DeviceInfoReaders.battery(context).toString(), null),
                DeviceBlock(
                    "WiFi", DeviceInfoReaders.wifi(context).toString(),
                    if (hasLocation) null else PermissionNeed.LOCATION,
                ),
                DeviceBlock(
                    context.getString(R.string.home_device_cellular), DeviceInfoReaders.cellular(context).toString(),
                    if (hasPhone && hasLocation) null else PermissionNeed.PHONE_AND_LOCATION,
                ),
                DeviceBlock(
                    "SIM", DeviceInfoReaders.sim(context).toString(),
                    if (hasPhone) null else PermissionNeed.PHONE,
                ),
                DeviceBlock(context.getString(R.string.home_device_hardware), DeviceInfoReaders.hardware(context).toString(), null),
                DeviceBlock(context.getString(R.string.home_device_memory), DeviceInfoReaders.memory(context).toString(), null),
                DeviceBlock(context.getString(R.string.home_device_display), DeviceInfoReaders.display(context).toString(), null),
                DeviceBlock(context.getString(R.string.home_title_sensors), DeviceInfoReaders.sensors(context).toString(), null),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.home_device_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        if (blocks.isEmpty()) {
            Text(
                stringResource(R.string.home_device_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            blocks.forEach { block ->
                DeviceCard(block) { need -> permLauncher.launch(need.permissions) }
            }
        }
    }
}

@Composable
private fun DeviceCard(block: DeviceBlock, onRequest: (PermissionNeed) -> Unit) {
    ArkiCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                block.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(block.body, style = MaterialTheme.typography.bodyMedium)
            val need = block.needsPermission
            if (need != null) {
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { onRequest(need) }) {
                    Text(stringResource(need.label))
                }
            }
        }
    }
}

private fun hasPerm(context: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

// ===================== Liikennetiedot (TrafficNoticesRepository) =====================

@Composable
internal fun TrafficSection(kind: TrafficNotice.Kind, title: String) {
    val context = LocalContext.current
    val repo = remember { TrafficNoticesRepository() }
    val refresh = LocalRefreshTick.current
    // Myös kotipaikan/sijainnin vaihtuessa (rev kasvaa) → referenssikoordinaatit luetaan uudelleen.
    val rev = LocalHomeDataRevision.current
    var notices by remember(kind) { mutableStateOf<List<TrafficNotice>?>(null) }
    var status by remember(kind) { mutableStateOf(context.getString(R.string.home_traffic_notices_loading)) }
    LaunchedEffect(kind, refresh, rev) {
        val ref = referenceCoordinates(context)
        if (ref == null) {
            status = context.getString(R.string.home_traffic_notices_need_location)
            notices = emptyList()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            try {
                repo.fetchNearby(ref[0], ref[1], kind, refresh > 0)
            } catch (e: Exception) {
                null
            }
        }
        if (result == null) {
            status = context.getString(R.string.home_traffic_failed)
            notices = emptyList()
        } else {
            notices = result
            // Kerro käyttäjälle täsmälleen mitä näytetään (säde, aikaikkuna, lähde).
            status = context.getString(
                when (kind) {
                    TrafficNotice.Kind.ROAD_WORK -> R.string.home_traffic_scope_roadworks
                    TrafficNotice.Kind.WEIGHT_RESTRICTION -> R.string.home_traffic_scope_weight
                    else -> R.string.home_traffic_scope_all
                },
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        val list = notices
        when {
            list == null -> Text(
                stringResource(R.string.common_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            list.isEmpty() -> Text(
                stringResource(R.string.home_traffic_none_area),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> list.forEach { TrafficCard(it) }
        }
    }
}

@Composable
private fun TrafficCard(n: TrafficNotice) {
    val context = LocalContext.current
    val arki = ArkiTheme.colors
    ArkiCard(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            val kindTitle = stringResource(n.kind.titleRes)
            Text(
                n.title.ifEmpty { kindTitle },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = arki.warning,
            )
            val meta = buildList {
                add(kindTitle)
                if (!n.distanceMeters.isNaN()) add(homeDistanceText(AppLocale.current(context), n.distanceMeters))
                val v = homeValidityText(context, n.startTimeMs, n.endTimeMs, R.string.home_date_pattern_full_date_time)
                if (v.isNotEmpty()) add(v)
            }.joinToString("  ·  ")
            Spacer(Modifier.height(2.dp))
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (n.location.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(n.location, style = MaterialTheme.typography.bodyMedium)
            }
            if (n.details.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(n.details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Sama lähde kuin View-appin searchReferenceCoordinates: tuore laitesijainti tai kotikoordinaatit. */
internal fun referenceCoordinates(context: Context): DoubleArray? {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val savedAt = prefs.getLong(MobileThemeController.KEY_LAST_DEVICE_LOCATION_TIME, 0L)
    if (savedAt > 0L && System.currentTimeMillis() - savedAt <= 24L * 3600_000L) {
        val lat = prefs.getFloat(MobileThemeController.KEY_LAST_DEVICE_LATITUDE, Float.NaN)
        val lon = prefs.getFloat(MobileThemeController.KEY_LAST_DEVICE_LONGITUDE, Float.NaN)
        if (!lat.isNaN() && !lon.isNaN()) return doubleArrayOf(lat.toDouble(), lon.toDouble())
    }
    val sm = SettingsManager.get()
    if (sm.hasHomeCoordinates()) return doubleArrayOf(sm.homeLatitude, sm.homeLongitude)
    return null
}

// ===================== GPS-nopeus (mittaristo: gps/GpsDashboardScreen) =====================

/** GPS-näkymä: tarkan sijaintiluvan tarkistus ja pyyntö; itse mittaristo on [GpsDashboardScreen]issa. */
@Composable
internal fun SpeedometerSection(onBack: () -> Unit, visible: Boolean, bottomBarHidden: Boolean,
                                requestedTrip: String? = null, onTripOpened: () -> Unit = {}) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    when (gpsPage(historyOpen || requestedTrip != null, granted)) {
        GpsPage.HISTORY -> {
            TripHistoryScreen(visible = visible, onBack = { historyOpen = false }, requestedTrip = requestedTrip,
                onTripOpened = { historyOpen = true; onTripOpened() })
            return
        }
        GpsPage.DASHBOARD -> {
            GpsDashboardScreen(onBack = onBack, onHistory = { historyOpen = true }, visible = visible)
            return
        }
        GpsPage.PERMISSION -> Unit
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Sama reunojen väistö kuin mittaristossa: vaakana kameran aukko ei saa peittää nuolta.
            .consumeWindowInsets(WindowInsets.systemBars)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (bottomBarHidden) {
            // Vaakana alapalkki on piilossa: paluun pitää näkyä jo ennen sijaintiluvan antamista.
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.mobile_ic_arrow_back),
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
            }
        }
        Text(stringResource(R.string.home_gps_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.home_gps_need_location),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = {
            permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }) { Text(stringResource(R.string.home_allow_location)) }
        FilledTonalButton(onClick = { historyOpen = true }) { Text(stringResource(R.string.gps_history)) }
    }
}
