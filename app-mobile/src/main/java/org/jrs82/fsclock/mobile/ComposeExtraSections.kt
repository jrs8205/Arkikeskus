package org.jrs82.fsclock.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.SettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OSA A: loput sektiot Compose-esikatseluun. Raskaat näkymät (kelikamerat/lähilähdöt/reittihaku)
 * HOSTATAAN olemassa olevina Fragmentteina [FragmentHostSection]-sillan kautta → täysi
 * datakattavuus ilman uudelleenkirjoitusta. Puhelimen tiedot lukee [DeviceInfoReaders]in valmiit
 * tekstit. Logiikkaa ei kosketa; tämä on vain Compose-esityskerros niiden ympärille.
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
internal fun RoadCamerasHost() {
    FragmentHostSection(
        tag = "compose_road_cameras",
        create = { RoadCamerasFragment() },
        onHidden = { (it as? RoadCamerasFragment)?.onSectionHidden() },
    )
}

@Composable
internal fun TransitHost() {
    FragmentHostSection(
        tag = "compose_transit",
        create = { TransitFragment() },
        onShown = { (it as? TransitFragment)?.onSectionShown() },
        onHidden = { (it as? TransitFragment)?.onSectionHidden() },
    )
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

private enum class PermissionNeed(val label: String, val permissions: Array<String>) {
    LOCATION(
        "Salli sijainti, jotta verkon nimi näkyy",
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),
    PHONE_AND_LOCATION(
        "Salli puhelimen tila ja sijainti",
        arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
    ),
    PHONE(
        "Salli puhelimen tila",
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
                DeviceBlock("Akku", DeviceInfoReaders.battery(context).toString(), null),
                DeviceBlock(
                    "WiFi", DeviceInfoReaders.wifi(context).toString(),
                    if (hasLocation) null else PermissionNeed.LOCATION,
                ),
                DeviceBlock(
                    "Mobiiliverkko", DeviceInfoReaders.cellular(context).toString(),
                    if (hasPhone && hasLocation) null else PermissionNeed.PHONE_AND_LOCATION,
                ),
                DeviceBlock(
                    "SIM", DeviceInfoReaders.sim(context).toString(),
                    if (hasPhone) null else PermissionNeed.PHONE,
                ),
                DeviceBlock("Laitteisto", DeviceInfoReaders.hardware().toString(), null),
                DeviceBlock("Muisti", DeviceInfoReaders.memory(context).toString(), null),
                DeviceBlock("Näyttö", DeviceInfoReaders.display(context).toString(), null),
                DeviceBlock("Anturit", DeviceInfoReaders.sensors(context).toString(), null),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Puhelimen tiedot", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        if (blocks.isEmpty()) {
            Text(
                "Luetaan laitetietoja…",
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
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
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
                    Text(need.label)
                }
            }
        }
    }
}

private fun hasPerm(context: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

// ===================== Liikennetiedot (TrafficNoticesRepository) =====================

private val FI_X = Locale("fi", "FI")

@Composable
internal fun TrafficSection(kind: TrafficNotice.Kind, title: String) {
    val context = LocalContext.current
    val repo = remember { TrafficNoticesRepository() }
    val refresh = LocalRefreshTick.current
    var notices by remember(kind) { mutableStateOf<List<TrafficNotice>?>(null) }
    var status by remember(kind) { mutableStateOf("Haetaan liikennetiedotteita…") }
    LaunchedEffect(kind, refresh) {
        val ref = referenceCoordinates(context)
        if (ref == null) {
            status = "Salli sijainti, jotta lähialueen tiedotteet voidaan hakea."
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
            status = "Liikennetietojen haku epäonnistui."
            notices = emptyList()
        } else {
            notices = result
            status = "Lähialue: noin 50 km säde"
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
                "Haetaan…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            list.isEmpty() -> Text(
                "Ei tiedotteita tällä alueella juuri nyt.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> list.forEach { TrafficCard(it) }
        }
    }
}

@Composable
private fun TrafficCard(n: TrafficNotice) {
    val arki = ArkiTheme.colors
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                n.title.ifEmpty { n.kind.title },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = arki.warning,
            )
            val meta = buildList {
                add(n.kind.title)
                if (!n.distanceMeters.isNaN()) add(formatDistanceFi(n.distanceMeters))
                val v = trafficValidity(n)
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

private fun formatDistanceFi(meters: Double): String = when {
    meters.isNaN() -> ""
    meters < 1000.0 -> "${Math.round(meters)} m"
    else -> String.format(FI_X, "%.1f km", meters / 1000.0)
}

private fun trafficValidity(n: TrafficNotice): String {
    if (n.startTimeMs <= 0L && n.endTimeMs <= 0L) return ""
    val dt = SimpleDateFormat("d.M.yyyy HH:mm", FI_X)
    return when {
        n.startTimeMs <= 0L -> "voimassa asti " + dt.format(Date(n.endTimeMs))
        n.endTimeMs <= 0L -> "alkaen " + dt.format(Date(n.startTimeMs))
        else -> dt.format(Date(n.startTimeMs)) + " – " + dt.format(Date(n.endTimeMs))
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

// ===================== GPS-nopeus (GpsSpeedometerView + LocationManager) =====================

@Composable
internal fun SpeedometerSection() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("GPS-nopeus", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!granted) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Salli sijainti, jotta nopeus voidaan mitata.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = {
                permLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }) { Text("Salli sijainti") }
        } else {
            // Mittari keskitetään käytettävissä olevaan tilaan (ei jää ylälaitaan tyhjän ylle).
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GpsSpeedContent(context)
                }
            }
        }
    }
}

@Composable
private fun GpsSpeedContent(context: Context) {
    var location by remember { mutableStateOf<Location?>(null) }
    var satUsed by remember { mutableStateOf(0) }
    var satVisible by remember { mutableStateOf(0) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000L)
        }
    }
    DisposableEffect(Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val main = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                // Älä korvaa tuoretta GPS-nopeutta nopeudettomalla NETWORK-sijainnilla.
                val prev = location
                if (!loc.hasSpeed() && prev != null && prev.hasSpeed() &&
                    System.currentTimeMillis() - prev.time < 8_000L
                ) {
                    return
                }
                location = loc
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        val gnss = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                val visible = status.satelliteCount
                for (i in 0 until visible) if (status.usedInFix(i)) used++
                satUsed = used
                satVisible = visible
            }
        }
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        }
        try {
            lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener)
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        }
        try {
            lm?.registerGnssStatusCallback(gnss, main)
        } catch (e: SecurityException) {
        }
        try {
            val g = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val n = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            location = when {
                g != null && (n == null || g.time >= n.time) -> g
                n != null -> n
                else -> location
            }
        } catch (e: SecurityException) {
        }
        onDispose {
            try {
                lm?.removeUpdates(listener)
                lm?.unregisterGnssStatusCallback(gnss)
            } catch (e: Exception) {
            }
        }
    }

    val kmh = speedKmh(location, nowMs)
    AndroidView(
        factory = { GpsSpeedometerView(it) },
        update = { it.setSpeedKmh(kmh) },
        modifier = Modifier.size(340.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (kmh <= 0f) "-- km/h" else "${Math.round(kmh)} km/h",
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(signalLabel(location, satUsed, satVisible), style = MaterialTheme.typography.bodyMedium)
    val detail = speedDetail(location, nowMs)
    if (detail.isNotEmpty()) {
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun speedKmh(loc: Location?, nowMs: Long): Float {
    if (loc == null || !loc.hasSpeed()) return 0f
    if (nowMs - loc.time > 8_000L) return 0f
    val kmh = loc.speed * 3.6f
    return if (kmh < 2f) 0f else kmh
}

private fun signalLabel(loc: Location?, used: Int, visible: Int): String {
    if (used > 0) {
        val quality = when {
            used >= 10 -> "Erinomainen"
            used >= 7 -> "Hyvä"
            used >= 4 -> "Heikko"
            else -> "Ei korjausta"
        }
        return "$quality · $used/$visible satelliittia"
    }
    if (loc != null && loc.hasAccuracy()) return "Tarkkuus ±${Math.round(loc.accuracy)} m"
    return "Odotetaan GPS-signaalia…"
}

private fun speedDetail(loc: Location?, nowMs: Long): String {
    if (loc == null) return ""
    val sb = StringBuilder()
    if (loc.hasAccuracy()) sb.append("Tarkkuus ±").append(Math.round(loc.accuracy)).append(" m")
    val ageMin = Math.max(0L, (nowMs - loc.time) / 60_000L)
    val age = when {
        ageMin < 1L -> "nyt"
        ageMin < 60L -> "$ageMin min sitten"
        else -> "${ageMin / 60L} h sitten"
    }
    if (sb.isNotEmpty()) sb.append(" · ")
    sb.append("Päivitetty ").append(age)
    return sb.toString()
}
