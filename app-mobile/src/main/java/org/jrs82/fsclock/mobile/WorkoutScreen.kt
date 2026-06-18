package org.jrs82.fsclock.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    var openWorkoutId by remember { mutableLongStateOf(0L) }
    var showStats by remember { mutableStateOf(false) }

    when {
        state.phase != WorkoutTracker.Phase.IDLE -> ActiveWorkoutView(state)
        showStats -> {
            androidx.activity.compose.BackHandler { showStats = false }
            WorkoutStatsView(onClose = { showStats = false })
        }
        openWorkoutId != 0L -> {
            androidx.activity.compose.BackHandler { openWorkoutId = 0L }
            WorkoutSummaryView(
                workoutId = openWorkoutId,
                autoStopped = false,
                onClose = { openWorkoutId = 0L },
            )
        }
        state.lastFinishedId != 0L && state.lastFinishedId != dismissedSummaryId ->
            WorkoutSummaryView(
                workoutId = state.lastFinishedId,
                autoStopped = state.autoStopped,
                onClose = { dismissedSummaryId = state.lastFinishedId },
            )
        else -> StartWorkoutView(
            prefs,
            onOpenWorkout = { openWorkoutId = it },
            onOpenStats = { showStats = true },
        )
    }
}

// ===================== Aloitusnäkymä =====================

@Composable
private fun StartWorkoutView(
    prefs: android.content.SharedPreferences,
    onOpenWorkout: (Long) -> Unit,
    onOpenStats: () -> Unit,
) {
    val context = LocalContext.current
    var type by remember {
        mutableStateOf(prefs.getInt(KEY_WORKOUT_TYPE, WorkoutEntity.TYPE_WALK))
    }

    // Lupaketju: sijainti → notifikaatiot (33+) → askeltunnistus (kävely) → käynnistys.
    fun hasPerm(p: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(context, p) ==
            PackageManager.PERMISSION_GRANTED

    var pendingStart by remember { mutableStateOf(false) }
    // GPS-lähtölaskuri (käyttäjän toive): lupien jälkeen 10 s sykkivä ympyrä, jonka aikana
    // GPS hakee signaalin; fix tai 0 → "Aloita!" ja seuranta käynnistyy.
    var countdown by remember { mutableStateOf(-1) }
    var gotFix by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    fun beginCountdown() {
        gotFix = false
        ready = false
        countdown = 10
    }

    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Askellupa on valinnainen — käynnistetään joka tapauksessa.
        if (pendingStart) {
            pendingStart = false
            beginCountdown()
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
                beginCountdown()
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
            beginCountdown()
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
            beginCountdown()
        }
    }

    if (countdown >= 0) {
        // GPS-lämmittely: kuunnellaan fixiä laskurin ajan (palvelu ottaa GPS:n haltuunsa startissa).
        DisposableEffect(Unit) {
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
                as android.location.LocationManager
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(l: android.location.Location) { gotFix = true }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) { }
                override fun onProviderEnabled(p: String) { }
                override fun onProviderDisabled(p: String) { }
            }
            try {
                lm.requestLocationUpdates(
                    android.location.LocationManager.GPS_PROVIDER, 1000L, 0f,
                    listener, android.os.Looper.getMainLooper(),
                )
            } catch (e: SecurityException) { }
            onDispose { try { lm.removeUpdates(listener) } catch (e: Exception) { } }
        }
        // Laskuri käy AINA loppuun asti (käyttäjän toive) — GPS-löytö päivittää vain tekstin.
        LaunchedEffect(countdown) {
            if (countdown > 0) {
                delay(1000L)
                countdown--
            } else if (countdown == 0) {
                ready = true
                delay(800L)
                countdown = -1
                WorkoutTrackingService.start(context, type)
            }
        }
        CountdownOverlay(
            seconds = countdown,
            ready = ready,
            gotFix = gotFix,
            onCancel = { countdown = -1 },
        )
        return
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

        // Lenkkihistoria: omat ja jaetut (tuodut) lenkit omissa osioissaan, napautus avaa
        // yhteenvedon reittikarttoineen.
        var history by remember { mutableStateOf<List<WorkoutEntity>>(emptyList()) }
        var historyTick by remember { mutableLongStateOf(0L) }
        // Ulkopuolinen tuonti (WhatsApp/Files-avaus) päivittää listan vaikka sivu olisi jo auki.
        val importTick by WorkoutFileImporter.changeTick.collectAsStateWithLifecycle()
        LaunchedEffect(historyTick, importTick) {
            history = withContext(Dispatchers.IO) {
                try {
                    FsClockDb.get(context).workoutDao().recentWorkouts(30)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
        val ownWorkouts = history.filter { !it.shared }
        val sharedWorkouts = history.filter { it.shared }
        if (ownWorkouts.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(
                onClick = onOpenStats,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Viikko- ja kuukausitilastot") }
            Spacer(Modifier.height(20.dp))
            Text("Aiemmat lenkit", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            ownWorkouts.forEach { w -> WorkoutHistoryRow(w, onOpenWorkout) }
        }
        if (sharedWorkouts.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Jaetut lenkit", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            sharedWorkouts.forEach { w -> WorkoutHistoryRow(w, onOpenWorkout) }
        }

        // Jaetun GPX/TCX-tiedoston tuonti käsin (sama polku kuin tiedoston avaus muualta).
        Spacer(Modifier.height(8.dp))
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                Thread {
                    var tooLarge: String? = null
                    val res = try {
                        context.contentResolver.openInputStream(uri)?.use {
                            WorkoutFileImporter.importStream(context, it)
                        }
                    } catch (e: FileTooLargeException) {
                        tooLarge = e.message
                        null
                    } catch (e: Exception) {
                        android.util.Log.w("WorkoutImport", "Käsituonti epäonnistui", e)
                        null
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            context,
                            when {
                                tooLarge != null -> tooLarge
                                res == null -> "Tiedostosta ei löytynyt lenkkiä (GPX/TCX)."
                                res.alreadyExists -> "Lenkki on jo laitteella: ${res.name}"
                                else -> "Jaettu lenkki tuotu: ${res.name}"
                            },
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        historyTick++
                    }
                }.start()
            }
        }
        TextButton(onClick = {
            importLauncher.launch(arrayOf(
                "application/gpx+xml", "application/vnd.garmin.tcx+xml",
                "application/xml", "text/xml", "application/octet-stream"))
        }) { Text("Tuo lenkki tiedostosta (GPX/TCX)") }
    }
}

// ===================== Tilastonäkymä =====================

/** Omat lenkit viikoittain/kuukausittain summattuna (matka, määrä, aika, kcal, askeleet, nousu).
 *  Jaettuja (tuotuja) lenkkejä ei lasketa mukaan. Aggregointi [WorkoutStats]-puhdasfunktiolla. */
@Composable
private fun WorkoutStatsView(onClose: () -> Unit) {
    val context = LocalContext.current
    var workouts by remember { mutableStateOf<List<WorkoutEntity>?>(null) }
    var byMonth by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        workouts = withContext(Dispatchers.IO) {
            try {
                FsClockDb.get(context).workoutDao().allFinishedWorkouts().filter { !it.shared }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ Takaisin") }
            Spacer(Modifier.width(4.dp))
            Text("Tilastot", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !byMonth,
                onClick = { byMonth = false },
                label = { Text("Viikot") },
            )
            FilterChip(
                selected = byMonth,
                onClick = { byMonth = true },
                label = { Text("Kuukaudet") },
            )
        }
        Spacer(Modifier.height(12.dp))

        val list = workouts
        when {
            list == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            list.isEmpty() -> Text(
                "Ei vielä omia lenkkejä.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val buckets = remember(list, byMonth) {
                    if (byMonth) WorkoutStats.monthly(list) else WorkoutStats.weekly(list)
                }
                buckets.forEach { b -> WorkoutStatBucketCard(b) }
            }
        }
    }
}

/** Yksi viikko-/kuukausikortti: otsikko (+ päivämääräväli), tunnusluvut kahdella rivillä,
 *  laji-erittely. Nolla-arvoiset tunnusluvut (esim. pyöräilyssä askeleet) jätetään pois. */
@Composable
private fun WorkoutStatBucketCard(b: WorkoutStats.Bucket) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.label, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (b.dateRange != null) {
                    Text(b.dateRange, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${b.count} ${lenkkiWord(b.count)} · " +
                    String.format(FI_WO, "%.2f km", b.distanceM / 1000.0) + " · " +
                    formatDurationLong(b.movingTimeMs),
                style = MaterialTheme.typography.bodyMedium,
            )
            val parts = buildList {
                if (b.kcal > 0) add("${b.kcal} kcal")
                if (b.steps > 0) add(String.format(FI_WO, "%,d askelta", b.steps))
                if (b.elevGainM >= 1.0) add(String.format(FI_WO, "↑%.0f m", b.elevGainM))
            }
            if (parts.isNotEmpty()) {
                Text(parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val laji = lajiBreakdown(b.walkCount, b.bikeCount)
            if (laji.isNotEmpty()) {
                Text("($laji)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun lenkkiWord(n: Int) = if (n == 1) "lenkki" else "lenkkiä"

/** "2 kävelyä · 1 pyöräily" — vain ne lajit joita ämpärissä on. */
private fun lajiBreakdown(walk: Int, bike: Int): String {
    val parts = mutableListOf<String>()
    if (walk > 0) parts += "$walk " + if (walk == 1) "kävely" else "kävelyä"
    if (bike > 0) parts += "$bike " + if (bike == 1) "pyöräily" else "pyöräilyä"
    return parts.joinToString(" · ")
}

/** Historiarivi: nimi (tai aika · laji) + tunnusluvut; jaetuissa lenkeissä Jaettu-merkki. */
@Composable
private fun WorkoutHistoryRow(w: WorkoutEntity, onOpenWorkout: (Long) -> Unit) {
    Card(
        onClick = { onOpenWorkout(w.id) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val laji = if (w.type == WorkoutEntity.TYPE_BIKE) "Pyöräily" else "Kävely"
                val named = !w.name.isNullOrBlank()
                Text(
                    if (named) w.name!!.trim()
                    else timeText(w.startedAtMs) + " · " + laji,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    (if (named) timeText(w.startedAtMs) + " · " + laji + " · " else "") +
                        String.format(FI_WO, "%.2f km", w.distanceM / 1000.0) +
                        " · " + formatDurationLong(w.movingTimeMs) +
                        " · " + avgSpeedText(w.distanceM, w.movingTimeMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (w.shared) {
                Text(
                    "Jaettu",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Sykkivä lähtölaskuriympyrä: sekunnit isolla, GPS-tila alla, "Aloita!" kun valmis. */
@Composable
private fun CountdownOverlay(seconds: Int, ready: Boolean, gotFix: Boolean, onCancel: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "scale",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                }
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (ready) "Aloita!" else seconds.toString(),
                fontSize = if (ready) 40.sp else 76.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            when {
                ready && gotFix -> "GPS löytyi — lenkki alkaa!"
                ready -> "Lenkki alkaa — GPS tarkentuu matkalla"
                gotFix -> "GPS löytyi!"
                else -> "Haetaan GPS-signaalia…"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onCancel) { Text("Peruuta") }
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

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        ) {
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

        // Kartta täyttää kaiken jouston (käyttäjän toive: ei tyhjää tilaa nappien yllä).
        WorkoutMap(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        String.format(FI_WO, "%.1f", state.speedKmh),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("km/h", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        String.format(FI_WO, "%.2f km", state.distanceM / 1000.0),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(formatDurationLong(displayedMs), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
            // Kompakti tunnuslukurivi (kartta vie pystysuunnan — ei korttipinoa tähän).
            val details = buildString {
                append("Keski ").append(avgSpeedText(state.distanceM, displayedMs))
                if (state.type == WorkoutEntity.TYPE_WALK) {
                    append(" · ").append(paceText(state.distanceM, displayedMs))
                    if (state.steps > 0) append(" · ").append(state.steps).append(" askelta")
                }
                append(" · max ").append(String.format(FI_WO, "%.1f", state.maxSpeedKmh))
                if (state.elevGainM > 1) {
                    append(" · ↑").append(String.format(FI_WO, "%.0f m", state.elevGainM))
                }
                if (state.splits.isNotEmpty()) {
                    append(" · ").append(state.splits.size).append(" km-väliä")
                }
            }
            Text(
                details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

// ===================== Yhteenveto (reittikartta + tunnusluvut + poisto) =====================

@Composable
private fun WorkoutSummaryView(workoutId: Long, autoStopped: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var workout by remember(workoutId) { mutableStateOf<WorkoutEntity?>(null) }
    var points by remember(workoutId) {
        mutableStateOf<List<org.jrs82.fsclock.db.WorkoutPointEntity>>(emptyList())
    }
    var splits by remember(workoutId) {
        mutableStateOf<List<org.jrs82.fsclock.db.WorkoutSplitEntity>>(emptyList())
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var shareDialog by remember { mutableStateOf(false) }
    var shareBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Nimi omana tilanaan: entiteetin kentän mutatointi ei laukaisisi recompositiota.
    var workoutName by remember(workoutId) { mutableStateOf<String?>(null) }
    LaunchedEffect(workoutId) {
        withContext(Dispatchers.IO) {
            try {
                val dao = FsClockDb.get(context).workoutDao()
                workout = dao.workoutById(workoutId)
                points = dao.pointsFor(workoutId)
                splits = dao.splitsFor(workoutId)
                workoutName = workout?.name
            } catch (e: Exception) {
                workout = null
            }
        }
    }
    val w = workout

    // Koko ruudun kartta (käyttäjän toive: pitkää reittiä pitää voida tutkia kunnolla).
    var mapFullscreen by remember { mutableStateOf(false) }
    if (mapFullscreen && points.size >= 2) {
        androidx.activity.compose.BackHandler { mapFullscreen = false }
        Box(modifier = Modifier.fillMaxSize()) {
            SummaryRouteMap(
                points = points,
                splits = splits,
                modifier = Modifier.fillMaxSize(),
                interactive = true,
            )
            FilledTonalButton(
                onClick = { mapFullscreen = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) { Text("Sulje") }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (w == null) "Lenkin yhteenveto"
                else workoutName?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: workoutDefaultName(w.type, w.startedAtMs),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (w != null) {
                IconButton(onClick = { renameDialog = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Nimeä lenkki",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (w != null && w.shared) {
            Spacer(Modifier.height(4.dp))
            Text("Jaettu lenkki (tuotu tiedostosta)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        if (autoStopped || (w != null && w.autoStopped)) {
            Spacer(Modifier.height(4.dp))
            Text("Päättyi automaattisesti (ei liikettä 10 min)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        if (points.size >= 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            ) {
                SummaryRouteMap(
                    points = points,
                    splits = splits,
                    modifier = Modifier.fillMaxSize(),
                    interactive = false,
                )
                // Läpinäkyvä napautusalue kartan päällä (MapView nielee kosketukset muuten).
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { mapFullscreen = true },
                )
                Text(
                    "Suurenna napauttamalla",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

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
                    if (w.elevLossM > 1) {
                        StatRow("Laskua (GPS-arvio)", String.format(FI_WO, "%.0f m", w.elevLossM))
                    }
                }
            }
            if (splits.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Kilometrivälit", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        splits.forEach { s ->
                            StatRow("${s.splitIndex}. km", formatDurationLong(s.durationMs))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (w != null && points.size >= 2) {
            OutlinedButton(
                onClick = { shareDialog = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Jaa lenkki…") }
            Spacer(Modifier.height(12.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Poista", color = MaterialTheme.colorScheme.error) }
            Button(onClick = onClose, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Valmis")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Poistetaanko lenkki?") },
            text = { Text("Lenkki ja sen reitti poistetaan pysyvästi.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    Thread {
                        try {
                            val dao = FsClockDb.get(context).workoutDao()
                            dao.deletePointsFor(workoutId)
                            dao.deleteSplitsFor(workoutId)
                            dao.deleteWorkout(workoutId)
                        } catch (e: Exception) { }
                    }.start()
                    onClose()
                }) { Text("Poista", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Peruuta") }
            },
        )
    }

    if (renameDialog && w != null) {
        var nameInput by remember { mutableStateOf(workoutName ?: "") }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Nimeä lenkki") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = { Text("esim. Koiralenkki") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameDialog = false
                    val newName = nameInput.trim().ifEmpty { null }
                    workoutName = newName
                    w.name = newName
                    Thread {
                        try {
                            FsClockDb.get(context).workoutDao().renameWorkout(workoutId, newName)
                        } catch (e: Exception) { }
                    }.start()
                }) { Text("Tallenna") }
            },
            dismissButton = {
                TextButton(onClick = { renameDialog = false }) { Text("Peruuta") }
            },
        )
    }

    if (shareDialog && w != null) {
        // Jakotiedoston nimi: lenkin nimi + päivämäärä, kielletyt merkit siivottuna.
        val fileBase = ShareFiles.sanitizeFileName(workoutDisplayName(w)) + " " +
            SimpleDateFormat("yyyy-MM-dd", FI_WO).format(Date(w.startedAtMs))
        fun shareData(ext: String, mime: String, build: () -> ByteArray) {
            scope.launch {
                shareBusy = true
                try {
                    val uri = withContext(Dispatchers.IO) {
                        ShareFiles.writeShareFile(context, fileBase + ext, build())
                    }
                    ShareFiles.launchShare(context, uri, mime, "Jaa lenkki")
                    shareDialog = false
                } catch (e: Exception) {
                    android.util.Log.w("WorkoutShare", "Jako epäonnistui ($ext)", e)
                    android.widget.Toast.makeText(
                        context, "Jako epäonnistui.", android.widget.Toast.LENGTH_LONG).show()
                } finally {
                    shareBusy = false
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!shareBusy) shareDialog = false },
            title = { Text("Jaa lenkki") },
            text = {
                if (shareBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Luodaan jakotiedostoa…")
                    }
                } else {
                    Column {
                        ShareOption("Kuva kartalla (PNG)", "Someen ja viesteihin") {
                            shareBusy = true
                            WorkoutShareImage.render(context, w, points, splits) { bmp ->
                                scope.launch {
                                    try {
                                        val uri = withContext(Dispatchers.IO) {
                                            val bos = java.io.ByteArrayOutputStream()
                                            bmp.compress(
                                                android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                                            ShareFiles.writeShareFile(
                                                context, "$fileBase.png", bos.toByteArray())
                                        }
                                        ShareFiles.launchShare(context, uri, "image/png", "Jaa lenkki")
                                        shareDialog = false
                                    } catch (e: Exception) {
                                        android.util.Log.w("WorkoutShare", "PNG-jako epäonnistui", e)
                                        android.widget.Toast.makeText(
                                            context, "Jako epäonnistui.",
                                            android.widget.Toast.LENGTH_LONG).show()
                                    } finally {
                                        shareBusy = false
                                    }
                                }
                            }
                        }
                        ShareOption("GPX-reittitiedosto", "Strava, Komoot, Garmin…") {
                            shareData(".gpx", "application/gpx+xml") {
                                WorkoutGpxExporter.buildGpx(w, points).toByteArray(Charsets.UTF_8)
                            }
                        }
                        ShareOption("TCX-harjoitustiedosto", "Väliajat ja kalorit mukana") {
                            shareData(".tcx", "application/vnd.garmin.tcx+xml") {
                                WorkoutTcxExporter.buildTcx(w, points, splits).toByteArray(Charsets.UTF_8)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(enabled = !shareBusy, onClick = { shareDialog = false }) { Text("Peruuta") }
            },
        )
    }
}

/** Staattinen reittikartta yhteenvetoon: koko reitti sovitettuna näkymään + km-merkit +
 *  lähtö (vihreä) ja maali (punainen). */
@Composable
private fun SummaryRouteMap(
    points: List<org.jrs82.fsclock.db.WorkoutPointEntity>,
    splits: List<org.jrs82.fsclock.db.WorkoutSplitEntity>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
) {
    val mapView = rememberMapViewWithLifecycle()
    androidx.compose.ui.viewinterop.AndroidView(factory = { mapView }, modifier = modifier)
    LaunchedEffect(mapView, points.size) {
        if (points.size < 2) return@LaunchedEffect
        mapView.getMapAsync { m ->
            if (interactive) {
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
            } else {
                m.uiSettings.setAllGesturesEnabled(false)
            }
            m.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(buildMmlStyleJson())) { style ->
                // Reittiviiva segmenteittäin
                val features = ArrayList<org.maplibre.geojson.Feature>()
                var seg = Int.MIN_VALUE
                var coords = ArrayList<org.maplibre.geojson.Point>()
                for (p in points) {
                    if (p.segment != seg) {
                        if (coords.size >= 2) {
                            features.add(org.maplibre.geojson.Feature.fromGeometry(
                                org.maplibre.geojson.LineString.fromLngLats(coords)))
                        }
                        coords = ArrayList()
                        seg = p.segment
                    }
                    coords.add(org.maplibre.geojson.Point.fromLngLat(p.lon, p.lat))
                }
                if (coords.size >= 2) {
                    features.add(org.maplibre.geojson.Feature.fromGeometry(
                        org.maplibre.geojson.LineString.fromLngLats(coords)))
                }
                val route = org.maplibre.android.style.sources.GeoJsonSource(
                    WO_SRC_ROUTE, org.maplibre.geojson.FeatureCollection.fromFeatures(features))
                style.addSource(route)
                val line = org.maplibre.android.style.layers.LineLayer(WO_LAYER_ROUTE, WO_SRC_ROUTE)
                line.setProperties(
                    org.maplibre.android.style.layers.PropertyFactory.lineColor("#1A73E8"),
                    org.maplibre.android.style.layers.PropertyFactory.lineWidth(5f),
                    org.maplibre.android.style.layers.PropertyFactory.lineCap(
                        org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                    org.maplibre.android.style.layers.PropertyFactory.lineJoin(
                        org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
                )
                style.addLayer(line)

                // Lähtö/maali (vihreä/punainen piste, väri feature-datasta)
                val endsFeatures = ArrayList<org.maplibre.geojson.Feature>()
                val startF = org.maplibre.geojson.Feature.fromGeometry(
                    org.maplibre.geojson.Point.fromLngLat(points.first().lon, points.first().lat))
                startF.addStringProperty("color", "#188038")
                endsFeatures.add(startF)
                val endF = org.maplibre.geojson.Feature.fromGeometry(
                    org.maplibre.geojson.Point.fromLngLat(points.last().lon, points.last().lat))
                endF.addStringProperty("color", "#C5221F")
                endsFeatures.add(endF)
                val ends = org.maplibre.android.style.sources.GeoJsonSource(
                    WO_SRC_POS, org.maplibre.geojson.FeatureCollection.fromFeatures(endsFeatures))
                style.addSource(ends)
                val endsDot = org.maplibre.android.style.layers.CircleLayer(WO_LAYER_POS, WO_SRC_POS)
                endsDot.setProperties(
                    org.maplibre.android.style.layers.PropertyFactory.circleColor(
                        org.maplibre.android.style.expressions.Expression.toColor(
                            org.maplibre.android.style.expressions.Expression.get("color"))),
                    org.maplibre.android.style.layers.PropertyFactory.circleRadius(7f),
                    org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor("#FFFFFF"),
                    org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(2f),
                )
                style.addLayer(endsDot)

                // Km-merkit
                val splitFeatures = ArrayList<org.maplibre.geojson.Feature>()
                for (s in splits) {
                    val name = "wo-km-" + s.splitIndex
                    if (style.getImage(name) == null) {
                        style.addImage(name, kmMarkerBitmap(mapView.context, s.splitIndex))
                    }
                    val f = org.maplibre.geojson.Feature.fromGeometry(
                        org.maplibre.geojson.Point.fromLngLat(s.endLon, s.endLat))
                    f.addStringProperty("icon", name)
                    splitFeatures.add(f)
                }
                val splitSrc = org.maplibre.android.style.sources.GeoJsonSource(
                    WO_SRC_SPLITS, org.maplibre.geojson.FeatureCollection.fromFeatures(splitFeatures))
                style.addSource(splitSrc)
                val splitIcons = org.maplibre.android.style.layers.SymbolLayer(WO_LAYER_SPLITS, WO_SRC_SPLITS)
                splitIcons.setProperties(
                    org.maplibre.android.style.layers.PropertyFactory.iconImage(
                        org.maplibre.android.style.expressions.Expression.get("icon")),
                    org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                    org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
                )
                style.addLayer(splitIcons)

                // Sovita koko reitti näkymään
                try {
                    val b = org.maplibre.android.geometry.LatLngBounds.Builder()
                    for (p in points) {
                        b.include(org.maplibre.android.geometry.LatLng(p.lat, p.lon))
                    }
                    m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory
                        .newLatLngBounds(b.build(), 60))
                } catch (e: Exception) {
                    m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(points.first().lat, points.first().lon), 15.0))
                }
            }
        }
    }
}

// ===================== Kartta =====================

private const val WO_SRC_ROUTE = "workout-route"
private const val WO_LAYER_ROUTE = "workout-route-line"
private const val WO_SRC_SPLITS = "workout-splits"
private const val WO_LAYER_SPLITS = "workout-splits-icons"
private const val WO_SRC_POS = "workout-pos"
private const val WO_LAYER_POS = "workout-pos-dot"

/** Lenkkikartta: MML-pohja + reittiviiva segmenteittäin + km-merkit (bitmap-numerot, koska
 *  rasterityylissä ei ole glyphs-fontteja) + oma sijainti. Kamera seuraa sijaintia läheltä
 *  (kävely zoom 17 / pyöräily 16); käyttäjän ele irrottaa seurannan, Kohdista palauttaa. */
@Composable
private fun WorkoutMap(state: WorkoutTracker.UiState, modifier: Modifier = Modifier) {
    val mapView = rememberMapViewWithLifecycle()
    var mapRef by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var routeSource by remember { mutableStateOf<org.maplibre.android.style.sources.GeoJsonSource?>(null) }
    var posSource by remember { mutableStateOf<org.maplibre.android.style.sources.GeoJsonSource?>(null) }
    var splitSource by remember { mutableStateOf<org.maplibre.android.style.sources.GeoJsonSource?>(null) }
    var following by remember { mutableStateOf(true) }
    var zoomInitialized by remember { mutableStateOf(false) }
    val followZoom = if (state.type == WorkoutEntity.TYPE_BIKE) 16.0 else 17.0

    androidx.compose.foundation.layout.Box(modifier = modifier) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )
        if (!following) {
            FilledTonalButton(
                onClick = {
                    zoomInitialized = false
                    following = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
            ) { Text("Kohdista") }
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    following = false
                }
            }
            m.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(buildMmlStyleJson())) { style ->
                val route = org.maplibre.android.style.sources.GeoJsonSource(
                    WO_SRC_ROUTE, org.maplibre.geojson.FeatureCollection.fromFeatures(ArrayList()),
                )
                style.addSource(route)
                val line = org.maplibre.android.style.layers.LineLayer(WO_LAYER_ROUTE, WO_SRC_ROUTE)
                line.setProperties(
                    org.maplibre.android.style.layers.PropertyFactory.lineColor("#1A73E8"),
                    org.maplibre.android.style.layers.PropertyFactory.lineWidth(5f),
                    org.maplibre.android.style.layers.PropertyFactory.lineCap(
                        org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                    org.maplibre.android.style.layers.PropertyFactory.lineJoin(
                        org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
                )
                style.addLayer(line)

                val splits = org.maplibre.android.style.sources.GeoJsonSource(
                    WO_SRC_SPLITS, org.maplibre.geojson.FeatureCollection.fromFeatures(ArrayList()),
                )
                style.addSource(splits)
                val splitIcons = org.maplibre.android.style.layers.SymbolLayer(WO_LAYER_SPLITS, WO_SRC_SPLITS)
                splitIcons.setProperties(
                    org.maplibre.android.style.layers.PropertyFactory.iconImage(
                        org.maplibre.android.style.expressions.Expression.get("icon")),
                    org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                    org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
                )
                style.addLayer(splitIcons)

                val pos = org.maplibre.android.style.sources.GeoJsonSource(
                    WO_SRC_POS, org.maplibre.geojson.FeatureCollection.fromFeatures(ArrayList()),
                )
                style.addSource(pos)
                val posDot = org.maplibre.android.style.layers.CircleLayer(WO_LAYER_POS, WO_SRC_POS)
                posDot.setProperties(
                    org.maplibre.android.style.layers.PropertyFactory.circleColor("#1A73E8"),
                    org.maplibre.android.style.layers.PropertyFactory.circleRadius(8f),
                    org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor("#FFFFFF"),
                    org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(3f),
                )
                style.addLayer(posDot)

                mapRef = m
                routeSource = route
                splitSource = splits
                posSource = pos

                // Alkukamera HETI: ilman tätä kartta jää maailmanzoomille kunnes ensimmäinen
                // GPS-fix tulee (sisätiloissa voi kestää) — "Suomi kuusta katsottuna" -bugi.
                val st = WorkoutTracker.state.value
                val start = if (!st.currentLat.isNaN() && !st.currentLon.isNaN()) {
                    org.maplibre.android.geometry.LatLng(st.currentLat, st.currentLon)
                } else {
                    lastKnownLatLng(mapView.context)
                }
                if (start != null) {
                    m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory
                        .newLatLngZoom(start, followZoom))
                    // zoomInitialized jätetään falseksi → ensimmäinen oikea fix kohdistaa vielä.
                } else {
                    m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(64.5, 26.0), 4.5))
                }
            }
        }
    }

    // Kaikki karttapäivitykset YHDESSÄ efektissä suoraan StateFlow'sta — riippumaton
    // recomposition-ajoituksesta (live-päivitys ei saa jäädä parametriketjun varaan).
    LaunchedEffect(routeSource, splitSource, posSource, mapRef) {
        val route = routeSource ?: return@LaunchedEffect
        val splitsSrc = splitSource ?: return@LaunchedEffect
        val pos = posSource ?: return@LaunchedEffect
        val m = mapRef ?: return@LaunchedEffect
        var drawnPoints = -1
        var drawnSplits = -1
        WorkoutTracker.state.collect { st ->
            // Oma sijainti + seurantakamera
            if (!st.currentLat.isNaN() && !st.currentLon.isNaN()) {
                pos.setGeoJson(org.maplibre.geojson.Feature.fromGeometry(
                    org.maplibre.geojson.Point.fromLngLat(st.currentLon, st.currentLat)))
                if (following) {
                    val target = org.maplibre.android.geometry.LatLng(st.currentLat, st.currentLon)
                    if (!zoomInitialized) {
                        m.easeCamera(org.maplibre.android.camera.CameraUpdateFactory
                            .newLatLngZoom(target, followZoom), 600)
                        zoomInitialized = true
                    } else {
                        m.easeCamera(org.maplibre.android.camera.CameraUpdateFactory
                            .newLatLng(target), 800)
                    }
                }
            }
            // Reittiviiva segmenteittäin (pause/GPS-katko katkaisee viivan)
            if (st.points.size != drawnPoints) {
                drawnPoints = st.points.size
                val features = ArrayList<org.maplibre.geojson.Feature>()
                var seg = Int.MIN_VALUE
                var coords = ArrayList<org.maplibre.geojson.Point>()
                for (p in st.points) {
                    if (p.segment != seg) {
                        if (coords.size >= 2) {
                            features.add(org.maplibre.geojson.Feature.fromGeometry(
                                org.maplibre.geojson.LineString.fromLngLats(coords)))
                        }
                        coords = ArrayList()
                        seg = p.segment
                    }
                    coords.add(org.maplibre.geojson.Point.fromLngLat(p.lon, p.lat))
                }
                if (coords.size >= 2) {
                    features.add(org.maplibre.geojson.Feature.fromGeometry(
                        org.maplibre.geojson.LineString.fromLngLats(coords)))
                }
                route.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(features))
            }
            // Km-merkit (numeroidut bitmap-ikonit rekisteröidään tarvittaessa)
            if (st.splits.size != drawnSplits) {
                drawnSplits = st.splits.size
                m.getStyle { style ->
                    val features = ArrayList<org.maplibre.geojson.Feature>()
                    for (s in st.splits) {
                        val name = "wo-km-" + s.index
                        if (style.getImage(name) == null) {
                            style.addImage(name, kmMarkerBitmap(mapView.context, s.index))
                        }
                        val f = org.maplibre.geojson.Feature.fromGeometry(
                            org.maplibre.geojson.Point.fromLngLat(s.endLon, s.endLat))
                        f.addStringProperty("icon", name)
                        features.add(f)
                    }
                    splitsSrc.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(features))
                }
            }
        }
    }
}

/** Viimeksi tunnettu sijainti karttakameran alkuasemointiin (GPS ensisijaisesti, sitten verkko). */
private fun lastKnownLatLng(context: android.content.Context): org.maplibre.android.geometry.LatLng? {
    return try {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        loc?.let { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        null
    }
}

/** Valkoinen ympyrä sinisellä reunalla ja numerolla — km-merkki kartalle (ei vaadi fontteja).
 *  Internal: myös jakokuva (WorkoutShareImage) käyttää samaa merkkiä. */
internal fun kmMarkerBitmap(context: android.content.Context, km: Int): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (26 * density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    c.drawCircle(size / 2f, size / 2f, size / 2f - density, paint)
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 2 * density
    paint.color = 0xFF1A73E8.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f - 1.5f * density, paint)
    paint.style = android.graphics.Paint.Style.FILL
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.textSize = 12 * density
    paint.isFakeBoldText = true
    val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
    c.drawText(km.toString(), size / 2f, baseline, paint)
    return bmp
}

// ===================== Apurit =====================

/** Jakodialogin valintarivi: otsikko + selite, koko rivi napautettava. */
@Composable
private fun ShareOption(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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

internal fun formatDurationLong(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(FI_WO, "%d:%02d:%02d", h, m, s)
    else String.format(FI_WO, "%02d:%02d", m, s)
}

internal fun avgSpeedText(distanceM: Double, movingMs: Long): String {
    if (movingMs <= 0 || distanceM <= 0) return "–"
    val kmh = distanceM / 1000.0 / (movingMs / 3_600_000.0)
    return String.format(FI_WO, "%.1f km/h", kmh)
}

internal fun paceText(distanceM: Double, movingMs: Long): String {
    // movingMs voi olla 0 esim. tuodussa GPX:ssä ilman aikaleimoja → "0:00 min/km" olisi väärin.
    if (movingMs <= 0 || distanceM < 50) return "–"
    val minPerKm = (movingMs / 60_000.0) / (distanceM / 1000.0)
    val min = minPerKm.toInt()
    val sec = ((minPerKm - min) * 60).toInt()
    return String.format(FI_WO, "%d:%02d min/km", min, sec)
}

private fun timeText(ms: Long): String =
    SimpleDateFormat("d.M. HH:mm", FI_WO).format(Date(ms))

/** Oletusnimi nimettömälle lenkille: "Kävely 12.6." */
internal fun workoutDefaultName(type: Int, startedAtMs: Long): String {
    val laji = if (type == WorkoutEntity.TYPE_BIKE) "Pyöräily" else "Kävely"
    return laji + " " + SimpleDateFormat("d.M.", FI_WO).format(Date(startedAtMs))
}

/** Käyttäjän antama nimi tai oletus — käytetään otsikossa ja jakotiedostoissa. */
internal fun workoutDisplayName(w: WorkoutEntity): String {
    val n = w.name
    return if (n.isNullOrBlank()) workoutDefaultName(w.type, w.startedAtMs) else n.trim()
}
