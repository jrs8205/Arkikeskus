package org.jrs82.fsclock.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.db.WorkoutEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lenkki-sivu (v1, ilman karttaa): aloitus (tyyppivalinta + Aloita + lupaketju), aktiivinen
 * näkymä (nopeus 0,0-tarkkuudella, matka, kesto, splitit, Tauko/Lopeta) ja yksinkertainen
 * yhteenveto. Totuus on WorkoutTracker.state — sovelluksen sulkeminen/avaaminen ei hukkaa mitään.
 */

private val FI_WO = Locale("fi", "FI")
private const val KEY_WORKOUT_TYPE = "workout_default_type"

@Composable
internal fun WorkoutScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val state by WorkoutTracker.state.collectAsStateWithLifecycle()
    var dismissedSummaryId by remember { mutableLongStateOf(0L) }

    when {
        state.phase != WorkoutTracker.Phase.IDLE -> ActiveWorkoutView(state)
        state.lastFinishedId != 0L && state.lastFinishedId != dismissedSummaryId ->
            SimpleSummaryView(state.lastFinishedId, state.autoStopped) {
                dismissedSummaryId = state.lastFinishedId
            }
        else -> StartWorkoutView(prefs)
    }
}

// ===================== Aloitusnäkymä =====================

@Composable
private fun StartWorkoutView(prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    var type by remember {
        mutableStateOf(prefs.getInt(KEY_WORKOUT_TYPE, WorkoutEntity.TYPE_WALK))
    }

    // Lupaketju: sijainti → notifikaatiot (33+) → askeltunnistus (kävely) → käynnistys.
    fun hasPerm(p: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(context, p) ==
            PackageManager.PERMISSION_GRANTED

    var pendingStart by remember { mutableStateOf(false) }

    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Askellupa on valinnainen — käynnistetään joka tapauksessa.
        if (pendingStart) {
            pendingStart = false
            WorkoutTrackingService.start(context, type)
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (pendingStart) {
            if (type == WorkoutEntity.TYPE_WALK &&
                !hasPerm(Manifest.permission.ACTIVITY_RECOGNITION)
            ) {
                activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                pendingStart = false
                WorkoutTrackingService.start(context, type)
            }
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
            pendingStart = false
            android.widget.Toast.makeText(
                context, "Lenkkiseuranta tarvitsee tarkan sijaintiluvan.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (type == WorkoutEntity.TYPE_WALK &&
            !hasPerm(Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            pendingStart = false
            WorkoutTrackingService.start(context, type)
        }
    }

    fun startWorkout() {
        prefs.edit().putInt(KEY_WORKOUT_TYPE, type).apply()
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pendingStart = true
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        } else if (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
            pendingStart = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (type == WorkoutEntity.TYPE_WALK &&
            !hasPerm(Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
            pendingStart = true
            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            WorkoutTrackingService.start(context, type)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Lenkki", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Seuraa kävely- tai pyörälenkkiä: matka, nopeus, kilometrivälit ja reitti. " +
                "Seuranta jatkuu, vaikka lukitset näytön.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = type == WorkoutEntity.TYPE_WALK,
                onClick = { type = WorkoutEntity.TYPE_WALK },
                label = { Text("Kävely") },
            )
            FilterChip(
                selected = type == WorkoutEntity.TYPE_BIKE,
                onClick = { type = WorkoutEntity.TYPE_BIKE },
                label = { Text("Pyöräily") },
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { startWorkout() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("Aloita lenkki", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Lenkki päättyy automaattisesti, jos liikettä ei ole 10 minuuttiin.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ===================== Aktiivinen näkymä =====================

@Composable
private fun ActiveWorkoutView(state: WorkoutTracker.UiState) {
    val context = LocalContext.current
    var confirmStop by remember { mutableStateOf(false) }

    // Kestonäyttö tickaa sekunnin välein ACTIVE-tilassa (movingTime + ankkurista kulunut).
    var displayedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.movingTimeMs, state.movingAnchorRt, state.phase) {
        while (true) {
            displayedMs = state.movingTimeMs +
                if (state.phase == WorkoutTracker.Phase.ACTIVE && state.movingAnchorRt > 0) {
                    (SystemClock.elapsedRealtime() - state.movingAnchorRt).coerceAtLeast(0)
                } else 0L
            delay(1000L)
        }
    }

    val gpsOk = state.lastFixMs > 0 && System.currentTimeMillis() - state.lastFixMs < 5_000

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.type == WorkoutEntity.TYPE_BIKE) "Pyörälenkki" else "Kävelylenkki",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (state.phase == WorkoutTracker.Phase.PAUSED) {
                Text("TAUKO", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            } else if (!gpsOk) {
                Text(
                    "Ei GPS-signaalia",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // Nopeus isolla, 0,0-tarkkuudella
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                String.format(FI_WO, "%.1f", state.speedKmh),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("km/h", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("Matka", String.format(FI_WO, "%.2f km", state.distanceM / 1000.0))
                StatRow("Kesto", formatDurationLong(displayedMs))
                StatRow("Keskinopeus", avgSpeedText(state.distanceM, displayedMs))
                if (state.type == WorkoutEntity.TYPE_WALK) {
                    StatRow("Tahti", paceText(state.distanceM, displayedMs))
                    if (state.steps > 0) StatRow("Askeleet", state.steps.toString())
                }
                StatRow("Maksiminopeus", String.format(FI_WO, "%.1f km/h", state.maxSpeedKmh))
            }
        }

        if (state.splits.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Kilometrivälit", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    state.splits.forEach { s ->
                        StatRow("${s.index}. km", formatDurationLong(s.durationMs))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.phase == WorkoutTracker.Phase.PAUSED) {
                Button(
                    onClick = { WorkoutTrackingService.command(context, WorkoutTrackingService.ACTION_RESUME) },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Jatka") }
            } else {
                FilledTonalButton(
                    onClick = { WorkoutTrackingService.command(context, WorkoutTrackingService.ACTION_PAUSE) },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Tauko") }
            }
            OutlinedButton(
                onClick = { confirmStop = true },
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Lopeta") }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Lopetetaanko lenkki?") },
            text = { Text("Lenkki tallennetaan ja näet yhteenvedon.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    WorkoutTrackingService.command(context, WorkoutTrackingService.ACTION_STOP)
                }) { Text("Lopeta") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("Peruuta") }
            },
        )
    }
}

// ===================== Yhteenveto (v1: perustiedot) =====================

@Composable
private fun SimpleSummaryView(workoutId: Long, autoStopped: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var workout by remember(workoutId) { mutableStateOf<WorkoutEntity?>(null) }
    LaunchedEffect(workoutId) {
        workout = withContext(Dispatchers.IO) {
            try { FsClockDb.get(context).workoutDao().workoutById(workoutId) } catch (e: Exception) { null }
        }
    }
    val w = workout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Lenkin yhteenveto", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        if (autoStopped) {
            Spacer(Modifier.height(4.dp))
            Text("Päättyi automaattisesti (ei liikettä 10 min)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        if (w == null) {
            Text("Ladataan…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatRow("Laji", if (w.type == WorkoutEntity.TYPE_BIKE) "Pyöräily" else "Kävely")
                    StatRow("Alkoi", timeText(w.startedAtMs))
                    w.endedAtMs?.let { StatRow("Päättyi", timeText(it)) }
                    StatRow("Matka", String.format(FI_WO, "%.2f km", w.distanceM / 1000.0))
                    StatRow("Liikkeellä", formatDurationLong(w.movingTimeMs))
                    StatRow("Keskinopeus", avgSpeedText(w.distanceM, w.movingTimeMs))
                    if (w.type == WorkoutEntity.TYPE_WALK) {
                        StatRow("Tahti", paceText(w.distanceM, w.movingTimeMs))
                        if (w.steps > 0) StatRow("Askeleet", w.steps.toString())
                    }
                    StatRow("Maksiminopeus", String.format(FI_WO, "%.1f km/h", w.maxSpeedMps * 3.6f))
                    if (w.kcal > 0) StatRow("Kalorit (arvio)", "${w.kcal} kcal")
                    if (w.elevGainM > 1) {
                        StatRow("Nousua (GPS-arvio)", String.format(FI_WO, "%.0f m", w.elevGainM))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Valmis")
        }
    }
}

// ===================== Apurit =====================

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

private fun formatDurationLong(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(FI_WO, "%d:%02d:%02d", h, m, s)
    else String.format(FI_WO, "%02d:%02d", m, s)
}

private fun avgSpeedText(distanceM: Double, movingMs: Long): String {
    if (movingMs <= 0 || distanceM <= 0) return "–"
    val kmh = distanceM / 1000.0 / (movingMs / 3_600_000.0)
    return String.format(FI_WO, "%.1f km/h", kmh)
}

private fun paceText(distanceM: Double, movingMs: Long): String {
    if (distanceM < 50) return "–"
    val minPerKm = (movingMs / 60_000.0) / (distanceM / 1000.0)
    val min = minPerKm.toInt()
    val sec = ((minPerKm - min) * 60).toInt()
    return String.format(FI_WO, "%d:%02d min/km", min, sec)
}

private fun timeText(ms: Long): String =
    SimpleDateFormat("d.M. HH:mm", FI_WO).format(Date(ms))
