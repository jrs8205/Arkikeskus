package org.jrs82.fsclock.mobile.gps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.jrs82.fsclock.R

internal enum class CarPermission(val label: Int, val permission: String?) {
    NEARBY(R.string.car_permission_nearby, if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null),
    FINE(R.string.car_permission_fine, Manifest.permission.ACCESS_FINE_LOCATION),
    BACKGROUND(R.string.car_permission_background, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    NOTIFICATIONS(R.string.car_permission_notifications, if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null);

    fun missing(context: Context) = permission != null && !hasCarPermission(context, permission)
}

@Composable
internal fun CarSettings() {
    val context = LocalContext.current
    val prefs = remember(context) { AutoTripPrefs(context) }
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(context) {
        revision++
        onPauseOrDispose {}
    }
    val missing = remember(revision) { CarPermission.entries.filter { it.missing(context) } }
    val pairs = remember(revision) { pairedCars(context) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var askInOrder by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val last = pending?.let(CarPermission::valueOf)
        val next = if (askInOrder && last != null && !last.missing(context))
            CarPermission.entries.firstOrNull { it.missing(context) } else null
        pending = next?.name
        if (next == null) askInOrder = false
        revision++
    }
    // Avainta vaihdetaan vain käyttäjän painalluksesta tai myönnetyn edellisen luvan jälkeen.
    var launched by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        val step = pending?.let(CarPermission::valueOf)
        if (step == null) { launched = null; return@LaunchedEffect }
        if (launched != step.name) {
            launched = step.name
            val permission = step.permission ?: return@LaunchedEffect
            val permissions = if (step == CarPermission.FINE)
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, permission) else arrayOf(permission)
            launcher.launch(permissions)
        }
    }
    CarSettingsContent(prefs.enabled, pairs.orEmpty(), prefs.selected, pairs != null, missing, { on ->
        prefs.enable(on)
        askInOrder = on
        pending = if (on) missing.firstOrNull()?.name else null
        revision++
    }, { address, on -> prefs.select(address, on); revision++ }, { step ->
        askInOrder = false
        // Tarkka sijainti pyydetään ennen taustasijaintia myös yksittäisestä korjauspainikkeesta.
        pending = if (step == CarPermission.BACKGROUND && CarPermission.FINE in missing) CarPermission.FINE.name else step.name
    }, {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    })
}

@Composable
internal fun CarSettingsContent(enabled: Boolean, pairs: Map<String, String>, selected: Set<String>,
    pairingKnown: Boolean, missing: List<CarPermission>, onEnable: (Boolean) -> Unit,
    onSelect: (String, Boolean) -> Unit, onRequest: (CarPermission) -> Unit, openSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(enabled, role = Role.Switch, onValueChange = onEnable),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.car_switch), Modifier.weight(1f))
            Switch(enabled, onCheckedChange = null)
        }
        Text(stringResource(R.string.car_help))
        Text(stringResource(R.string.car_cars), style = MaterialTheme.typography.titleMedium)
        for (address in (pairs.keys + selected).sortedBy { pairs[it] ?: it }) {
            val name = pairs[address] ?: if (pairingKnown) stringResource(R.string.car_unpaired, address) else address
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(address in selected, role = Role.Checkbox,
                onValueChange = { onSelect(address, it) }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(address in selected, onCheckedChange = null)
                Text(name, Modifier.padding(start = 12.dp))
            }
        }
        if (!pairingKnown) Text(stringResource(R.string.car_paired_unknown))
        else if (pairs.isEmpty()) Text(stringResource(R.string.car_pair))
        if (enabled && selected.isEmpty()) Text(stringResource(R.string.car_no_selection))
        if (enabled && missing.isNotEmpty()) {
            Text(stringResource(R.string.car_permissions), style = MaterialTheme.typography.titleMedium)
            for (step in missing) {
                TextButton(onClick = { onRequest(step) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(step.label))
                }
            }
            TextButton(onClick = openSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.car_open_settings))
            }
        }
    }
}
