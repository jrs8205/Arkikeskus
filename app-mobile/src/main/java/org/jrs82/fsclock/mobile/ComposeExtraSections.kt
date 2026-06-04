package org.jrs82.fsclock.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

private fun hasPerm(context: android.content.Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
