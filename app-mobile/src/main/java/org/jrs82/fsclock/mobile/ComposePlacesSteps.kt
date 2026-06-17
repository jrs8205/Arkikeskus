package org.jrs82.fsclock.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import java.util.Locale
import kotlin.coroutines.resume

/**
 * OSA A (viimeiset inline-sektiot): Paikkakunnat (PLACES) + Askeleet (STEPS) Composeen.
 * Nämä eivät ole Fragmentteja View-appissa vaan [MobileMainActivity]n sisäisiä näkymiä, joten ne
 * reimplementoidaan Composeen. Logiikka luetaan samoista repositoryistä/silloista kuin View-appissa
 * (SettingsManager, MmlGeocodingClient, HealthConnectStepsBridge, StepCounter, StepCalorieEstimator,
 * StepsHistory, StepsHtmlExporter) — ei tuplalogiikkaa, vain Compose-esityskerros.
 */

private val FI_PS = Locale("fi", "FI")

// Profiiliavaimet (samat kuin MobileMainActivityssa — jaettu SharedPreferences).
private const val KEY_PROFILE_SEX = "mobile_profile_sex"
private const val KEY_PROFILE_AGE = "mobile_profile_age"
private const val KEY_PROFILE_HEIGHT = "mobile_profile_height_cm"
private const val KEY_PROFILE_WEIGHT = "mobile_profile_weight_kg"
private const val KEY_PROFILE_STEP = "mobile_profile_step_length_cm"
private const val KEY_STEPS_USE_HC = "mobile_steps_use_hc" // VALINNAINEN: käytä Health Connectia (oletus pois → puhelimen anturi)

// ============================================================================
//  PAIKKAKUNNAT (PLACES)
// ============================================================================

/** Yksi hakutulos / suosikki valittavaksi sääpaikaksi. */
private data class PlaceChoice(
    val dataPlace: String,
    val displayPlace: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Paikkakunnat-sektio: nykyinen sääpaikka + suosikiksi-kytkin, ennakoiva haku (MML-geokoodaus),
 * laitteen sijainti (reverse-geokoodaus) ja suosikkilistalta valinta. Paikan valinta vaihtaa
 * SettingsManagerin kotipaikan (kuten View-appin selectHomePlace) ja palaa etusivulle hakemaan
 * uuden paikan sään.
 */
@Composable
internal fun PlacesSection(onPlaceChosen: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var tick by remember { mutableStateOf(0) }

    val current = remember(tick) { placesDisplayPlace(prefs) }
    val isFavorite = remember(tick) { SettingsManager.get().isFavoritePlace(current) }
    val favorites = remember(tick) { SettingsManager.get().favoritePlaces }

    // --- Haku (ennakoiva, debouncattu MML-geokoodaus) ---
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceChoice>>(emptyList()) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            status = ""
            return@LaunchedEffect
        }
        // 1) Välitön offline-kuntahaku assets-listasta (jo 1 merkistä, ei verkkoa) → ehdotukset heti.
        val offline = withContext(Dispatchers.Default) {
            KuntaList.search(context, q, 8).map { PlaceChoice(it.name, it.name, it.lat, it.lon) }
        }
        results = offline
        status = if (offline.isNotEmpty()) "Valitse paikka listalta." else "Haetaan…"
        // 2) MML täydentää osoitteet/kylät/kaupunginosat (vaatii ≥3 merkkiä). Yhdistetään: kunnat ensin, dedup.
        if (q.length < 3) {
            if (offline.isEmpty()) status = "Kirjoita vähintään kolme kirjainta."
            return@LaunchedEffect
        }
        delay(250)
        val mml = withContext(Dispatchers.IO) {
            try {
                MmlGeocodingClient.searchPlaces(q, 8).map {
                    PlaceChoice(it.dataPlace, it.displayPlace, it.latitude, it.longitude)
                }
            } catch (e: Exception) {
                null
            }
        }
        if (mml != null) {
            val seen = HashSet<String>()
            val merged = ArrayList<PlaceChoice>()
            for (p in offline + mml) {
                if (seen.add(KuntaList.normalize(p.displayPlace))) merged.add(p)
            }
            results = if (merged.size > 12) ArrayList(merged.subList(0, 12)) else merged
            status = if (merged.isEmpty()) "Kaupunkeja ei löytynyt." else "Valitse paikka listalta."
        } else if (offline.isEmpty()) {
            status = "Haku epäonnistui."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Paikkakunnat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Etusivun sää käyttää laitteen sijaintia automaattisesti. Voit myös hakea kaupungin tai " +
                "valita suosikin — valinta näkyy, kunnes avaat sovelluksen uudelleen. Automaattisen " +
                "sijainnin voi kytkeä pois Asetuksista.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Nykyinen paikka + suosikiksi-kytkin
        Card(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Nykyinen sääpaikka", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(current, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = {
                    val sm = SettingsManager.get()
                    if (sm.isFavoritePlace(current)) {
                        sm.removeFavoritePlace(current)
                        Toast.makeText(context, "Poistettu suosikeista", Toast.LENGTH_SHORT).show()
                    } else {
                        sm.addFavoritePlace(current)
                        Toast.makeText(context, "Lisätty suosikiksi", Toast.LENGTH_SHORT).show()
                    }
                    tick++
                }) {
                    Icon(
                        painterResource(
                            if (isFavorite) R.drawable.mobile_ic_favorite_24 else R.drawable.mobile_ic_favorite_add_24,
                        ),
                        contentDescription = if (isFavorite) "Poista suosikeista" else "Lisää suosikiksi",
                        tint = if (isFavorite) Color(0xFFE0526B) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Haku
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Hae kaupunkia tai kaupunginosaa") },
            leadingIcon = { Icon(painterResource(R.drawable.mobile_ic_search_24), contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; results = emptyList(); status = "" }) {
                        Text("✕", fontSize = 18.sp)
                    }
                }
            },
        )
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        results.forEach { hit ->
            PlaceRow(hit.displayPlace, leading = R.drawable.mobile_ic_search_24, onClick = {
                chooseHomePlace(prefs, hit.dataPlace, false, hit.displayPlace, hit.latitude, hit.longitude)
                onPlaceChosen()
            })
        }

        // Suosikit
        Spacer(Modifier.height(16.dp))
        Text("Suosikkipaikat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (favorites.isEmpty()) {
            Text(
                "Ei suosikkipaikkoja. Lisää nykyinen sääpaikka yllä olevasta sydämestä.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            favorites.forEach { fav ->
                FavoritePlaceRow(
                    place = fav,
                    onSelect = {
                        val data = dataPlaceFromDisplay(fav)
                        val geo = org.jrs82.fsclock.GeoPlace.tryForPlace(fav)
                            ?: org.jrs82.fsclock.GeoPlace.tryForPlace(data)
                        chooseHomePlace(prefs, data, false, fav,
                            geo?.latitude ?: Double.NaN, geo?.longitude ?: Double.NaN)
                        onPlaceChosen()
                    },
                    onRemove = {
                        SettingsManager.get().removeFavoritePlace(fav)
                        tick++
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaceRow(label: String, leading: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(leading),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun FavoritePlaceRow(place: String, onSelect: () -> Unit, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                place,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSelect)
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
            )
            IconButton(onClick = onRemove, modifier = Modifier.padding(end = 6.dp)) {
                Icon(
                    painterResource(R.drawable.mobile_ic_favorite_24),
                    contentDescription = "Poista suosikeista",
                    tint = Color(0xFFE0526B),
                )
            }
        }
    }
}

/** Nykyinen näytettävä paikka: tallennettu näyttönimi tai kotipaikka (kuten currentDisplayPlace). */
private fun placesDisplayPlace(prefs: SharedPreferences): String {
    val d = prefs.getString(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME, "") ?: ""
    if (d.trim().isNotEmpty()) return d.trim()
    return SettingsManager.get().homePlace
}

/** Erottaa datapaikan (kaupunki) näyttönimestä ennen pilkkua (kuten dataPlaceFromDisplay). */
private fun dataPlaceFromDisplay(display: String?): String {
    if (display == null) return SettingsManager.DEFAULT_HOME_PLACE
    var trimmed = display.trim()
    val comma = trimmed.indexOf(',')
    if (comma > 0) trimmed = trimmed.substring(0, comma).trim()
    return if (trimmed.isEmpty()) SettingsManager.DEFAULT_HOME_PLACE else trimmed
}

/**
 * Replikoi View-appin selectHomePlace: asettaa kotipaikan + koordinaatit + näyttönimen samoihin
 * SharedPreferences-/SettingsManager-avaimiin ja nollaa sää-cachen, jotta etusivu hakee uuden paikan.
 */
internal fun chooseHomePlace(
    prefs: SharedPreferences,
    dataPlace: String,
    fromLocation: Boolean,
    displayPlace: String?,
    latitude: Double,
    longitude: Double,
) {
    val data = dataPlace.trim()
    if (data.isEmpty()) return
    val sm = SettingsManager.get()
    sm.homePlace = data
    val disp = displayPlace?.trim().orEmpty()
    if (!latitude.isNaN() && !longitude.isNaN()) {
        sm.setHomeCoordinates(latitude, longitude)
        org.jrs82.fsclock.GeoPlace.register(data, latitude, longitude)
        if (disp.isNotEmpty()) org.jrs82.fsclock.GeoPlace.register(disp, latitude, longitude)
    } else {
        sm.clearHomeCoordinates()
    }
    val editor = prefs.edit()
    if (!fromLocation) {
        // Kaupungin/suosikin valinta EI sammuta automaattista sijaintia: valinta on tilapäinen ja
        // palautuu laitteen sijaintiin seuraavalla avauksella (kun auto on päällä). Pysyvän kiinteän
        // paikan saa kytkemällä automaattisen sijainnin pois Asetuksista.
        if (disp.isNotEmpty() && !disp.equals(data, ignoreCase = true)) {
            editor.putString(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME, disp)
        } else {
            editor.remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME)
        }
    } else {
        editor.putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true)
        if (disp.isNotEmpty()) {
            editor.putString(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME, disp)
        } else {
            editor.remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME)
        }
    }
    editor.apply()
    // Nollaa etusivun sää-seed → HomeDashboard hakee uuden paikan sään tuoreena.
    WeatherCache.last = null
}

internal fun lastKnownLocation(context: Context): Location? {
    if (!hasGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
        !hasGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    ) {
        return null
    }
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        val g = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val n = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        when {
            g != null && (n == null || g.time >= n.time) -> g
            n != null -> n
            else -> null
        }
    } catch (e: SecurityException) {
        null
    }
}

private fun hasGranted(context: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** Lähde-erittelyn nimi: sovelluksen nimi PackageManagerista jos asennettu, muuten paketinimi.
 *  Yleismaallinen — toimii mille tahansa HC:hen kirjoittavalle sovellukselle/laitemerkille.
 *  Erikoistapaus: puhelimen oman askellaskurin attribuutio on "android" (ja kesäkuusta 2026
 *  alkaen mahdollisesti laitekohtainen tunniste, joka ei ole pakettinimi) → selkokielinen nimi. */
private fun appLabel(context: Context, pkg: String): String {
    // Puhelimen oman askellaskurin attribuutiot: "android" (vanha) sekä kesäkuusta 2026
    // "com.android.healthconnect.phone.<laitehash>" (todettu Pixel 8a:lla livenä).
    if (pkg == "android" || pkg.isBlank() || pkg.startsWith("com.android.healthconnect")) {
        return "Puhelimen askellaskuri"
    }
    return try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        // Ei asennettu paketti: jos arvo ei edes näytä paketilta (ei pistettä), kyse on
        // laitetason tunnisteesta → näytä ymmärrettävänä.
        if ('.' !in pkg) "Puhelimen askellaskuri ($pkg)" else pkg
    }
}

private const val LOCATION_MAX_AGE_MS = 10L * 60_000L
private const val CURRENT_LOCATION_TIMEOUT_MS = 8_000L
private const val MAX_AUTO_LOCATION_ACCURACY_M = 1500f

/**
 * Laitteen sijainti sääpaikan automaattipäivitykseen. **Nopea polku säilyy:** jos viimeisin tunnettu
 * sijainti on tuore (< 10 min), se palautetaan VÄLITTÖMÄSTI. Vain jos se on null (esim. puhdas asennus)
 * tai liian vanha (esim. matkustettu toiseen kaupunkiin), haetaan AKTIIVISESTI tuore sijainti
 * (FusedLocationProviderClient.getCurrentLocation, kuten vanha View-UI). Korjaa "ei sijaintia" ja
 * "väärä kaupunki" -tilanteet rikkomatta nopeaa toimintaa.
 */
internal suspend fun deviceLocation(context: Context, force: Boolean): Location? {
    if (!hasGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
        !hasGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    ) {
        return null
    }
    val now = System.currentTimeMillis()
    val last = lastKnownLocation(context)
    // Päivitä-nappi (force) hakee AINA tuoreen sijainnin. Automaattipolku käyttää nopeaa viimeisintä
    // tunnettua vain jos se on käyttökelpoinen (ikä 0..10 min, tarkkuus enintään 1500 m), muuten hakee aktiivisesti.
    if (!force && isUsableAutoLocation(last, now)) return last
    val fresh = requestCurrentLocation(context, force)
    val completedAt = System.currentTimeMillis()
    if (isUsableAutoLocation(fresh, completedAt)) return fresh
    // Automaattipolku voi palata käyttökelpoiseen fallbackiin. Pakotettu haku ei käytä vanhaa sijaintia.
    return if (!force && isUsableAutoLocation(last, completedAt)) last else null
}

/** true jos sijainti on käyttökelpoinen: ikä 0..10 min ja ilmoitettu tarkkuus enintään 1500 m. */
private fun isUsableAutoLocation(loc: Location?, now: Long): Boolean {
    if (loc == null) return false
    val age = now - loc.time
    if (age !in 0..LOCATION_MAX_AGE_MS) return false
    return !loc.hasAccuracy() || loc.accuracy <= MAX_AUTO_LOCATION_ACCURACY_M
}

/** Aktiivinen tuore sijaintipyyntö (best-effort, aikaraja [CURRENT_LOCATION_TIMEOUT_MS]).
 *  [force] (Päivitä-nappi) pakottaa oikeasti tuoreen lukeman: maxUpdateAge 0 + korkea tarkkuus. */
private suspend fun requestCurrentLocation(context: Context, force: Boolean): Location? =
    suspendCancellableCoroutine { cont ->
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            val request = CurrentLocationRequest.Builder()
                .setPriority(if (force) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(if (force) 0L else LOCATION_MAX_AGE_MS)
                .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MS)
                .build()
            client.getCurrentLocation(request, cts.token)
                .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
            cont.invokeOnCancellation { cts.cancel() }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

// ============================================================================
//  ASKELEET (STEPS)
// ============================================================================

/**
 * Askeleet-sektio: Health Connect ensisijaisesti (HealthConnectStepsBridge) + raw-anturi-fallback
 * (StepCounter + Room), 4 välilehteä (Tänään/Päivät/Viikot/Kuukaudet), kaloriarvio (StepCalorieEstimator)
 * + profiili, HTML-vienti (StepsHtmlExporter) ja 3-tilainen kytkin. Replikoi MobileMainActivity.showSteps().
 */
@Composable
internal fun StepsSection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val hcAvailable = remember { HealthConnectStepsBridge.isAvailable(context) }
    val stepCounter = remember { StepCounter(context) }
    val rawAvailable = remember { stepCounter.isAvailable() }

    var enabled by remember { mutableStateOf(prefs.getBoolean(MobileThemeController.KEY_STEPS_ENABLED, false)) }
    var useHcEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_STEPS_USE_HC, false)) }
    var hcGranted by remember { mutableStateOf(false) }
    var hcCaloriesGranted by remember { mutableStateOf(false) }
    var permTick by remember { mutableStateOf(0) }
    LaunchedEffect(permTick) {
        HealthConnectStepsBridge.hasPermission(context) { hcGranted = it }
        HealthConnectStepsBridge.hasCaloriePermission(context) { hcCaloriesGranted = it }
    }
    // Oletuslähde on puhelimen oma askelanturi; Health Connect on VALINNAINEN (useHcEnabled, oletus pois).
    val useHc = hcAvailable && hcGranted && useHcEnabled
    val canHcCalories = useHc && hcCaloriesGranted

    var tab by remember { mutableStateOf(0) }
    var todaySteps by remember { mutableStateOf(0L) }
    var hcSteps by remember { mutableStateOf(-1L) } // Health Connectin luku; -1 = ei haettu/ei dataa
    var rawSteps by remember { mutableStateOf(0L) }  // puhelimen anturi (varakeino kun HC tyhjä)
    var arTick by remember { mutableStateOf(0) }     // kasvaa kun liikunta-aktiivisuuslupa myönnetään
    var sourcePkgs by remember { mutableStateOf(arrayOf<String>()) }
    var sourceSteps by remember { mutableStateOf(LongArray(0)) }
    var lastRefreshMs by remember { mutableStateOf(0L) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var profileTick by remember { mutableStateOf(0) }
    var hcCalActive by remember { mutableStateOf(0) }
    var hcCalTotal by remember { mutableStateOf(0) }
    var hcCalHas by remember { mutableStateOf(false) }
    var historyText by remember { mutableStateOf("") }
    var showProfile by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<StepsHtmlExporter.Result?>(null) }

    // Lupavirrat
    val hcLauncher = rememberLauncherForActivityResult(
        HealthConnectStepsBridge.permissionContract(),
    ) { permTick++ }
    val arLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        if (ok) {
            prefs.edit().putBoolean(MobileThemeController.KEY_STEPS_ENABLED, true).apply()
            enabled = true
            arTick++ // käynnistää puhelimen askelanturin (DisposableEffect-avain)
            refreshTrigger++
        }
    }

    // Puhelimen askelanturi: käynnistä aina kun käytössä + saatavilla + lupa — MYÖS HC:n rinnalla
    // varakeinoksi, koska HC palauttaa 0 jos siihen ei kirjoita mikään (esim. ilman kelloa/Fitiä).
    DisposableEffect(enabled, rawAvailable, arTick) {
        if (enabled && rawAvailable && hasGranted(context, Manifest.permission.ACTIVITY_RECOGNITION)) {
            stepCounter.setListener { s -> rawSteps = s.toLong() }
            stepCounter.start()
            rawSteps = stepCounter.currentTodaySteps().toLong()
        }
        onDispose { stepCounter.stop() }
    }
    DisposableEffect(Unit) { onDispose { stepCounter.shutdown() } }

    // Tänään: Health Connectin askeleet (hcSteps; >0 = dataa, 0 = tyhjä, -1 = ei käytössä/virhe).
    LaunchedEffect(enabled, useHc, refreshTrigger) {
        if (enabled && useHc) {
            HealthConnectStepsBridge.todaySteps(context) { s ->
                hcSteps = s
                if (s >= 0) lastRefreshMs = System.currentTimeMillis()
            }
        } else {
            hcSteps = -1L
        }
    }

    // Näytettävä luku: Health Connect jos sillä on dataa (> 0), muuten puhelimen anturi (varakeino).
    LaunchedEffect(enabled, useHc, hcSteps, rawSteps) {
        todaySteps = when {
            !enabled -> 0L
            useHc && hcSteps > 0L -> maxOf(hcSteps, rawSteps)
            else -> rawSteps
        }
    }

    // Reaaliaikaisuus: virkistä Tänään-luku ~15 s välein auki ollessa, jotta askeleet kasvavat
    // kävellessä ilman manuaalista Päivitä-nappia (HC luetaan uudelleen; raw on jo live listenerillä).
    LaunchedEffect(enabled, tab) {
        if (enabled && tab == 0) {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                refreshTrigger++
            }
        }
    }

    // Tänään: per-lähde-erittely (vain HC-lähteellä). Yleismaallinen — listaa kaikki HC:hen
    // kirjoittaneet sovellukset dataOriginin mukaan, laitemerkistä riippumatta.
    LaunchedEffect(enabled, useHc, refreshTrigger) {
        if (enabled && useHc) {
            HealthConnectStepsBridge.todayStepsBySource(context) { pkgs, steps ->
                sourcePkgs = pkgs
                sourceSteps = steps
            }
        } else {
            sourcePkgs = arrayOf()
            sourceSteps = LongArray(0)
        }
    }

    // Tänään: HC-kalorit (jos lupa)
    LaunchedEffect(enabled, canHcCalories, refreshTrigger) {
        if (enabled && canHcCalories) {
            HealthConnectStepsBridge.todayCalories(context) { active, total, has ->
                hcCalActive = if (active > 0) Math.round(active).toInt() else 0
                hcCalTotal = if (total > 0) Math.round(total).toInt() else 0
                hcCalHas = has
            }
        } else {
            hcCalHas = false
        }
    }

    // Historia (Päivät/Viikot/Kuukaudet)
    // Generaatiolaskuri: jokainen historian latauspyyntö saa oman numeron. Health Connect -kutsut ovat
    // callback-pohjaisia (eivät peruunnu LaunchedEffectin mukana), joten nopeassa välilehtivaihdossa vanha
    // vastaus voisi kirjoittaa uuden välilehden tekstin päälle. Vartija: kirjoita vain jos pyyntö on yhä uusin.
    val historyGen = remember { intArrayOf(0) }
    LaunchedEffect(tab, enabled, useHc, hcCaloriesGranted, refreshTrigger) {
        // Kasvata generaatio ENNEN aikaisia return-kohtia → myös Tänään-välilehdelle siirtyminen mitätöi
        // piilossa olevan historiakutsun (vanha callback ei kirjoita myöhemmin).
        historyGen[0]++
        val myGen = historyGen[0]
        if (tab == 0 || !enabled) {
            return@LaunchedEffect
        }
        historyText = "Ladataan…"
        if (useHc) {
            val period = when (tab) {
                2 -> HealthConnectStepsBridge.PERIOD_WEEKS
                3 -> HealthConnectStepsBridge.PERIOD_MONTHS
                else -> HealthConnectStepsBridge.PERIOD_DAYS
            }
            val count = when (tab) {
                2 -> 8
                3 -> 6
                else -> 14
            }
            HealthConnectStepsBridge.historyWithCalories(context, period, count, hcCaloriesGranted) { labels, steps, active, total ->
                if (myGen == historyGen[0]) {
                    historyText = formatHcHistory(prefs, labels, steps, active, total, period)
                }
            }
        } else {
            val text = withContext(Dispatchers.IO) { StepsHistory.build(context, tab).toString() }
            if (myGen == historyGen[0]) historyText = text
        }
    }

    val available = hcAvailable || rawAvailable
    val caloriesText = remember(todaySteps, hcCalActive, hcCalTotal, hcCalHas, profileTick, canHcCalories) {
        todayCaloriesText(prefs, todaySteps, hcCalActive, hcCalTotal, hcCalHas, canHcCalories)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Askeleet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        // Kytkin + lähde-info
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Askelmittari", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled && available,
                        enabled = available,
                        onCheckedChange = { on ->
                            toggleSteps(context, prefs, on, hcAvailable, rawAvailable, hcGranted,
                                stepCounter, hcLauncher, arLauncher,
                                onEnabledChange = { enabled = it }, onRefresh = { refreshTrigger++ })
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stepsNote(available, enabled, useHc, hcAvailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Puhelimen oma askelanturi = OLETUSLÄHDE → tarvitsee liikunta-aktiivisuusluvan.
                if (enabled && !useHc && rawAvailable &&
                    !hasGranted(context, Manifest.permission.ACTIVITY_RECOGNITION)
                ) {
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = {
                        arLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }) {
                        Text("Salli liikunta-aktiivisuus (puhelimen askelmittari)")
                    }
                }
                // Health Connect = VALINNAINEN lisälähde (oletus pois). Yhdistä esim. kello tai sormus.
                if (enabled) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Käytä Health Connectia",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Switch(
                            checked = useHcEnabled,
                            onCheckedChange = { on ->
                                useHcEnabled = on
                                prefs.edit().putBoolean(KEY_STEPS_USE_HC, on).apply()
                                if (on) {
                                    if (!hcAvailable) openOrInstallHealthConnect(context)
                                    else if (!hcGranted) {
                                        hcLauncher.launch(HealthConnectStepsBridge.permissions().toSet())
                                    }
                                }
                                refreshTrigger++
                            },
                        )
                    }
                    Text(
                        "Health Connect kokoaa askeleet ja muut terveystiedot yhteen — voit yhdistää esim. " +
                            "älykellon, -sormuksen tai muut terveyssovellukset. Se ei ole oletuksena " +
                            "asennettuna; ilman sitä Arkikeskus käyttää puhelimen omaa askelmittaria.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { openOrInstallHealthConnect(context) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(if (hcAvailable) "Avaa Health Connect" else "Asenna Health Connect")
                    }
                    if (useHcEnabled && hcAvailable && hcGranted && !hcCaloriesGranted) {
                        FilledTonalButton(onClick = {
                            hcLauncher.launch(HealthConnectStepsBridge.permissions().toSet())
                        }) {
                            Text("Lue myös kalorit Health Connectista")
                        }
                    }
                }
            }
        }

        if (enabled && available) {
            // Välilehdet
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StepsTab("Tänään", tab == 0, Modifier.weight(1f)) { tab = 0 }
                StepsTab("Päivät", tab == 1, Modifier.weight(1f)) { tab = 1 }
                StepsTab("Viikot", tab == 2, Modifier.weight(1f)) { tab = 2 }
                StepsTab("Kuukaudet", tab == 3, Modifier.weight(1f)) { tab = 3 }
            }
            Spacer(Modifier.height(14.dp))

            if (tab == 0) {
                // Matka-arvio: askelpituus omasta askelmitasta (cm) tai pituudesta (yleinen kaava).
                // Näytetään vain jos pituus tai askelmitta on annettu.
                val stepCm = prefs.getFloat(KEY_PROFILE_STEP, 0f)
                val heightCm = prefs.getFloat(KEY_PROFILE_HEIGHT, 0f)
                val distanceText = if ((heightCm > 0f || stepCm > 0f) && todaySteps > 0) {
                    String.format(
                        java.util.Locale("fi", "FI"), "≈ %.1f km kävelty",
                        StepCalorieEstimator.distanceKm(todaySteps, heightCm.toDouble(), stepCm.toDouble()),
                    )
                } else {
                    ""
                }
                StepsTodayContent(
                    steps = todaySteps,
                    caloriesText = caloriesText,
                    distanceText = distanceText,
                    useHc = useHc,
                    lastRefreshMs = lastRefreshMs,
                    hasProfile = hasProfile(prefs),
                    exporting = exporting,
                    onRefresh = { refreshTrigger++ },
                    onProfile = { showProfile = true },
                    onExport = {
                        if (!exporting) {
                            exporting = true
                            exportSteps(context, prefs, useHc, hcCaloriesGranted, stepCounter) { result ->
                                exporting = false
                                if (result != null && result.ok) exportResult = result
                                else Toast.makeText(context, "HTML-vienti epäonnistui", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
                if (useHc) {
                    Spacer(Modifier.height(14.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Lähteet (Health Connect)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (sourcePkgs.isEmpty()) {
                                Text(
                                    "Yksikään sovellus ei ole kirjannut askeleita tämän puhelimen Health " +
                                        "Connectiin tänään. Health Connect on puhelinkohtainen tietovarasto: " +
                                        "se ei synkkaudu Google-tilin kautta toisesta puhelimesta. Askeleet " +
                                        "ilmestyvät tähän, kun jokin sovellus (esim. kellon, sormuksen tai " +
                                        "puhelimen terveyssovellus) kirjoittaa ne tämän laitteen Health " +
                                        "Connectiin — tarkista sovelluksen kirjoitusluvat ja avaa se kerran.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                sourcePkgs.forEachIndexed { i, pkg ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                    ) {
                                        Text(
                                            appLabel(context, pkg),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            sourceSteps.getOrElse(i) { 0L }.toString() + " askelta",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Askelluku yllä on Health Connectin yhdistämä summa, josta päällekkäiset " +
                                        "lähteet on poistettu. Tässä kunkin sovelluksen oma luku (Health " +
                                        "Connectin laskemana) — eri rivit voivat mitata samoja askelia, joten " +
                                        "niitä ei kuulu laskea yhteen. Kellon tai sormuksen data näkyy vasta, " +
                                        "kun sen oma sovellus on synkannut tähän puhelimeen. Lähteiden " +
                                        "tärkeysjärjestystä voi muuttaa Health Connectin asetuksista.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = {
                                try {
                                    context.startActivity(
                                        Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"))
                                } catch (e: Exception) {
                                    Toast.makeText(context,
                                        "Health Connect -asetuksia ei voitu avata", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Avaa Health Connect") }
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        historyText.ifEmpty { "Ladataan…" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (showProfile) {
        ProfileDialog(
            prefs = prefs,
            onDismiss = { showProfile = false },
            onSaved = { showProfile = false; profileTick++; refreshTrigger++ },
        )
    }

    val res = exportResult
    if (res != null) {
        AlertDialog(
            onDismissRequest = { exportResult = null },
            title = { Text("HTML tallennettu") },
            text = { Text("Tallennettu kansioon Download/Arkikeskus:\n" + res.fileName) },
            confirmButton = {
                TextButton(onClick = {
                    exportResult = null
                    try {
                        val view = Intent(Intent.ACTION_VIEW)
                        view.setDataAndType(res.uri, "text/html")
                        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(view)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Avaamiseen ei löytynyt sovellusta", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Avaa") }
            },
            dismissButton = { TextButton(onClick = { exportResult = null }) { Text("Sulje") } },
        )
    }
}

@Composable
private fun StepsTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .padding(vertical = 10.dp),
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepsTodayContent(
    steps: Long,
    caloriesText: String,
    distanceText: String,
    useHc: Boolean,
    lastRefreshMs: Long,
    hasProfile: Boolean,
    exporting: Boolean,
    onRefresh: () -> Unit,
    onProfile: () -> Unit,
    onExport: () -> Unit,
) {
    val arki = ArkiTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = arki.healthContainer,
            contentColor = arki.onHealthContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatStepsNum(steps), fontSize = 52.sp, fontWeight = FontWeight.Bold, color = arki.health)
            Text("askelta tänään" + if (useHc) " (Health Connect)" else "", style = MaterialTheme.typography.bodyMedium)
            if (distanceText.isNotEmpty()) {
                Text(
                    distanceText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = arki.health,
                )
            }
        }
    }
    if (caloriesText.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(caloriesText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
    }
    if (useHc) {
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.mobile_ic_refresh_24), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Päivitä Health Connectista")
        }
        if (lastRefreshMs > 0L) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Päivitetty klo " + hhmmPs(lastRefreshMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onProfile, modifier = Modifier.fillMaxWidth()) {
        Text(if (hasProfile) "Muokkaa profiilia" else "Lisää pituus ja paino kalori- ja matka-arviota varten")
    }
    Spacer(Modifier.height(4.dp))
    Button(onClick = onExport, enabled = !exporting, modifier = Modifier.fillMaxWidth()) {
        Text(if (exporting) "Viedään…" else "Lataa HTML-yhteenveto")
    }
}

@Composable
private fun ProfileDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var sex by remember { mutableStateOf(prefs.getString(KEY_PROFILE_SEX, "") ?: "") }
    var age by remember { mutableStateOf(numText(prefs.getInt(KEY_PROFILE_AGE, 0))) }
    var height by remember { mutableStateOf(numText(prefs.getFloat(KEY_PROFILE_HEIGHT, 0f))) }
    var weight by remember { mutableStateOf(numText(prefs.getFloat(KEY_PROFILE_WEIGHT, 0f))) }
    var step by remember { mutableStateOf(numText(prefs.getFloat(KEY_PROFILE_STEP, 0f))) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profiili kalori- ja matka-arviota varten") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Sukupuoli", style = MaterialTheme.typography.labelLarge)
                SexOption("Nainen", sex == "female") { sex = "female" }
                SexOption("Mies", sex == "male") { sex = "male" }
                SexOption("En halua kertoa", sex != "female" && sex != "male") { sex = "" }
                Spacer(Modifier.height(8.dp))
                NumberField("Ikä (v)", age) { age = it }
                NumberField("Pituus (cm)", height) { height = it }
                NumberField("Paino (kg)", weight) { weight = it }
                NumberField("Askelpituus (cm, valinnainen)", step) { step = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit()
                    .putString(KEY_PROFILE_SEX, sex)
                    .putInt(KEY_PROFILE_AGE, parseIntPs(age))
                    .putFloat(KEY_PROFILE_HEIGHT, parseFloatPs(height))
                    .putFloat(KEY_PROFILE_WEIGHT, parseFloatPs(weight))
                    .putFloat(KEY_PROFILE_STEP, parseFloatPs(step))
                    .apply()
                onSaved()
            }) { Text("Tallenna") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Peruuta") } },
    )
}

@Composable
private fun SexOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

// ----------------------- STEPS-apurit (replikoi MobileMainActivity) -----------------------

private fun hasProfile(prefs: SharedPreferences): Boolean =
    prefs.getFloat(KEY_PROFILE_HEIGHT, 0f) > 0 && prefs.getFloat(KEY_PROFILE_WEIGHT, 0f) > 0

/** Avaa Health Connect -asetukset jos saatavilla; muuten ohjaa Play Storeen asentamaan (onboarding). */
private fun openOrInstallHealthConnect(context: Context) {
    for (action in listOf(
        "androidx.health.connect.action.HEALTH_CONNECT_SETTINGS",
        "android.health.connect.action.HEALTH_CONNECT_SETTINGS",
    )) {
        try {
            context.startActivity(android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) { /* kokeile seuraavaa */ }
    }
    for (url in listOf(
        "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding",
        "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata",
    )) {
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        } catch (e: Exception) { /* kokeile seuraavaa */ }
    }
}

private fun stepsNote(available: Boolean, enabled: Boolean, useHc: Boolean, hcAvailable: Boolean): String = when {
    !available -> "Tässä laitteessa ei ole askelanturia eikä Health Connectia, joten askelmittaria ei voi ottaa käyttöön."
    !enabled -> "Askelmittari on pois päältä. Kytke päälle laskeaksesi askeleet."
    useHc -> "Lähde: Health Connect (yhdistetyt laitteet, esim. kello/sormus). Luku haetaan automaattisesti; " +
        "jos kellon/sormuksen sovellus ei ole vielä synkannut tähän puhelimeen, avaa se kerran."
    else -> "Lähde: puhelimen oma askelmittari. Kävellessä askeleet kasvavat reaaliajassa. Halutessasi voit " +
        "yhdistää Health Connectin (kello/sormus) alta."
}

/** Askelmittarin pääkytkin: oletuslähde puhelimen oma anturi (ACTIVITY_RECOGNITION); Health Connect
 *  on erillinen opt-in askelsivulla. HC otetaan käyttöön ainoana lähteenä vain jos anturia ei ole. */
private fun toggleSteps(
    context: Context,
    prefs: SharedPreferences,
    on: Boolean,
    hcAvailable: Boolean,
    rawAvailable: Boolean,
    hcGranted: Boolean,
    stepCounter: StepCounter,
    hcLauncher: androidx.activity.result.ActivityResultLauncher<Set<String>>,
    arLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    if (!on) {
        prefs.edit().putBoolean(MobileThemeController.KEY_STEPS_ENABLED, false).apply()
        onEnabledChange(false)
        stepCounter.stop()
        return
    }
    // Oletuslähde: puhelimen oma askelanturi (Health Connect on erillinen opt-in askelsivulla).
    prefs.edit().putBoolean(MobileThemeController.KEY_STEPS_ENABLED, true).apply()
    onEnabledChange(true)
    if (rawAvailable) {
        if (!hasGranted(context, Manifest.permission.ACTIVITY_RECOGNITION)) {
            arLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            return
        }
        stepCounter.start()
    } else if (hcAvailable && !hcGranted) {
        // Ei puhelimen anturia → Health Connect ainoana lähteenä.
        hcLauncher.launch(HealthConnectStepsBridge.permissions().toSet())
        return
    }
    onRefresh()
}

/** Replikoi renderStepsCalories + buildCaloriesText: tämän päivän kaloriteksti profiilista/HC:stä. */
private fun todayCaloriesText(
    prefs: SharedPreferences,
    steps: Long,
    hcActive: Int,
    hcTotal: Int,
    hcHas: Boolean,
    canHcCalories: Boolean,
): String {
    val h = prefs.getFloat(KEY_PROFILE_HEIGHT, 0f).toDouble()
    val w = prefs.getFloat(KEY_PROFILE_WEIGHT, 0f).toDouble()
    val age = prefs.getInt(KEY_PROFILE_AGE, 0)
    val sex = prefs.getString(KEY_PROFILE_SEX, "") ?: ""
    val stepCm = prefs.getFloat(KEY_PROFILE_STEP, 0f).toDouble()
    val profile = h > 0 && w > 0
    if (!profile && !(canHcCalories && hcHas)) return ""
    val km = if (profile) StepCalorieEstimator.distanceKm(steps, h, stepCm) else 0.0
    val activeEst = if (profile) StepCalorieEstimator.activeKcal(steps, h, w, stepCm) else 0
    val totalEst = if (profile) StepCalorieEstimator.totalDailyKcal(StepCalorieEstimator.bmr(w, h, age, sex), activeEst) else 0
    if (canHcCalories && hcHas) {
        val a = if (hcActive > 0) hcActive else activeEst
        val t = if (hcTotal > 0) hcTotal else totalEst
        return buildCaloriesText(km, a, hcActive > 0, t, hcTotal > 0)
    }
    return buildCaloriesText(km, activeEst, false, totalEst, false)
}

private fun buildCaloriesText(km: Double, active: Int, activeIsHc: Boolean, total: Int, totalIsHc: Boolean): String {
    val sb = StringBuilder()
    if (km > 0) sb.append(String.format(Locale.US, "Matka-arvio: %.1f km", km).replace('.', ','))
    if (activeIsHc || active > 0) {
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append("Aktiivinen kulutus: ")
        if (activeIsHc) sb.append("$active kcal (Health Connect)")
        else sb.append("noin $active kcal (arvio)")
    }
    if (totalIsHc || total > 0) {
        if (sb.isNotEmpty()) sb.append("\n")
        if (totalIsHc) sb.append("Päivän kokonaiskulutus: $total kcal (Health Connect)")
        else sb.append("Päivän kokonaisarvio: noin $total kcal")
    }
    if (activeIsHc || active > 0 || totalIsHc || total > 0) {
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append("Kalorit: ").append(if (activeIsHc || totalIsHc) "Health Connect" else "Arkikeskus-arvio")
    }
    return sb.toString()
}

/** Replikoi formatHcHistoryWithCalories: HC-historia askeleet + kalorit per ämpäri (uusin ensin). */
private fun formatHcHistory(
    prefs: SharedPreferences,
    labels: Array<String>,
    steps: LongArray,
    active: DoubleArray,
    total: DoubleArray,
    periodType: Int,
): String {
    if (labels.isEmpty()) return "Ei vielä askeldataa Health Connectissa."
    val h = prefs.getFloat(KEY_PROFILE_HEIGHT, 0f).toDouble()
    val w = prefs.getFloat(KEY_PROFILE_WEIGHT, 0f).toDouble()
    val stepCm = prefs.getFloat(KEY_PROFILE_STEP, 0f).toDouble()
    val canEstimate = h > 0 && w > 0
    val sb = StringBuilder()
    for (i in labels.indices.reversed()) {
        sb.append(hcHistoryLabel(labels[i], periodType)).append("\n  ")
            .append(formatStepsNum(steps[i])).append(" askelta")
        val a = Math.round(active[i]).toInt()
        val tk = Math.round(total[i]).toInt()
        if (a > 0 || tk > 0) {
            if (a > 0) {
                sb.append("\n  aktiiviset $a kcal")
                if (tk > 0) sb.append(" · yhteensä $tk kcal")
            } else {
                sb.append("\n  yhteensä $tk kcal")
            }
        } else if (canEstimate && steps[i] > 0) {
            sb.append("\n  aktiiviset ~${StepCalorieEstimator.activeKcal(steps[i], h, w, stepCm)} kcal (arvio)")
        }
        sb.append("\n\n")
    }
    return sb.toString().trim()
}

private fun hcHistoryLabel(isoDate: String, periodType: Int): String = try {
    val d = java.time.LocalDate.parse(isoDate)
    when (periodType) {
        HealthConnectStepsBridge.PERIOD_WEEKS -> "Viikko " + d.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
        HealthConnectStepsBridge.PERIOD_MONTHS -> StepsHistory.monthNameFi(d.monthValue) + " " + d.year
        else -> "${d.dayOfMonth}.${d.monthValue}."
    }
} catch (e: Exception) {
    isoDate
}

private fun exportLabel(isoDate: String, periodType: Int): String = try {
    val d = java.time.LocalDate.parse(isoDate)
    when (periodType) {
        HealthConnectStepsBridge.PERIOD_WEEKS -> {
            val wk = d.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
            val wky = d.get(java.time.temporal.WeekFields.ISO.weekBasedYear())
            val end = d.plusDays(6)
            "Vk $wk/$wky (${d.dayOfMonth}.${d.monthValue}.–${end.dayOfMonth}.${end.monthValue}.)"
        }
        HealthConnectStepsBridge.PERIOD_MONTHS -> StepsHistory.monthNameFi(d.monthValue) + " " + d.year
        else -> "${d.dayOfMonth}.${d.monthValue}.${d.year}"
    }
} catch (e: Exception) {
    isoDate
}

private fun estimateActiveKcal(prefs: SharedPreferences, steps: Long): Int {
    val h = prefs.getFloat(KEY_PROFILE_HEIGHT, 0f).toDouble()
    val w = prefs.getFloat(KEY_PROFILE_WEIGHT, 0f).toDouble()
    val stepCm = prefs.getFloat(KEY_PROFILE_STEP, 0f).toDouble()
    if (h <= 0 || w <= 0 || steps <= 0) return 0
    return StepCalorieEstimator.activeKcal(steps, h, w, stepCm)
}

/**
 * Replikoi exportStepsHtml: HC-tilassa ketjuttaa historia-/kalorikutsut ja rakentaa raportin,
 * raw-tilassa lukee Room-päiväsummat. Kirjoitus taustasäikeessä, tulos pääsäikeessä [onResult].
 */
private fun exportSteps(
    context: Context,
    prefs: SharedPreferences,
    useHc: Boolean,
    hcCaloriesGranted: Boolean,
    stepCounter: StepCounter,
    onResult: (StepsHtmlExporter.Result?) -> Unit,
) {
    if (useHc) {
        gatherHcReportThenExport(context, prefs, hcCaloriesGranted) { report ->
            writeReport(context, report, onResult)
        }
    } else {
        Thread {
            val report = buildRawReport(context, prefs, stepCounter)
            writeReport(context, report, onResult)
        }.start()
    }
}

private fun writeReport(context: Context, report: StepsHtmlExporter.Report, onResult: (StepsHtmlExporter.Result?) -> Unit) {
    Thread {
        val name = StepsHtmlExporter.buildFileName()
        val result = StepsHtmlExporter.export(context.applicationContext, report, name)
        Handler(Looper.getMainLooper()).post { onResult(result) }
    }.start()
}

/** Ketjuttaa HC-historian (päivät → viikot → kuukaudet → tänään → kalorit) ja kokoaa raportin. */
private fun gatherHcReportThenExport(
    context: Context,
    prefs: SharedPreferences,
    hcCaloriesGranted: Boolean,
    onReport: (StepsHtmlExporter.Report) -> Unit,
) {
    val days = ArrayList<StepsHtmlExporter.Row>()
    val weeks = ArrayList<StepsHtmlExporter.Row>()
    val months = ArrayList<StepsHtmlExporter.Row>()
    HealthConnectStepsBridge.historyWithCalories(context, HealthConnectStepsBridge.PERIOD_DAYS, 365, hcCaloriesGranted) { l1, s1, a1, t1 ->
        fillHcRows(prefs, days, l1, s1, a1, t1, HealthConnectStepsBridge.PERIOD_DAYS)
        HealthConnectStepsBridge.historyWithCalories(context, HealthConnectStepsBridge.PERIOD_WEEKS, 104, hcCaloriesGranted) { l2, s2, a2, t2 ->
            fillHcRows(prefs, weeks, l2, s2, a2, t2, HealthConnectStepsBridge.PERIOD_WEEKS)
            HealthConnectStepsBridge.historyWithCalories(context, HealthConnectStepsBridge.PERIOD_MONTHS, 36, hcCaloriesGranted) { l3, s3, a3, t3 ->
                fillHcRows(prefs, months, l3, s3, a3, t3, HealthConnectStepsBridge.PERIOD_MONTHS)
                HealthConnectStepsBridge.todaySteps(context) { todaySteps ->
                    val ts = if (todaySteps < 0) 0L else todaySteps
                    HealthConnectStepsBridge.todayCalories(context) { active, total, has ->
                        val ta: Int
                        val tt: Int
                        val estd: Boolean
                        if (has && (active > 0 || total > 0)) {
                            ta = if (active > 0) Math.round(active).toInt() else 0
                            tt = if (total > 0) Math.round(total).toInt() else 0
                            estd = false
                        } else {
                            ta = estimateActiveKcal(prefs, ts)
                            tt = 0
                            estd = ta > 0
                        }
                        onReport(StepsHtmlExporter.Report("Health Connect", ts, ta, tt, estd, days, weeks, months))
                    }
                }
            }
        }
    }
}

private fun fillHcRows(
    prefs: SharedPreferences,
    out: MutableList<StepsHtmlExporter.Row>,
    labels: Array<String>,
    steps: LongArray,
    active: DoubleArray,
    total: DoubleArray,
    periodType: Int,
) {
    for (i in labels.indices.reversed()) {
        if (steps[i] <= 0) continue
        var a = Math.round(active[i]).toInt()
        val t = Math.round(total[i]).toInt()
        var estimated = false
        if (a <= 0 && t <= 0) {
            val est = estimateActiveKcal(prefs, steps[i])
            if (est > 0) { a = est; estimated = true }
        }
        out.add(StepsHtmlExporter.Row(exportLabel(labels[i], periodType), steps[i], a, t, estimated))
    }
}

private fun buildRawReport(context: Context, prefs: SharedPreferences, stepCounter: StepCounter): StepsHtmlExporter.Report {
    val rows = org.jrs82.fsclock.db.FsClockDb.get(context).dailyStepsDao().range(0, 99999999)
        ?.sortedBy { it.dateKey } ?: emptyList()

    val days = ArrayList<StepsHtmlExporter.Row>()
    val weekSteps = LinkedHashMap<String, Long>()
    val weekLabel = LinkedHashMap<String, String>()
    val monthSteps = LinkedHashMap<String, Long>()
    val monthLabel = LinkedHashMap<String, String>()

    for (e in rows) {
        val key = e.dateKey
        val d = java.time.LocalDate.of(key / 10000, (key / 100) % 100, key % 100)
        val ae = estimateActiveKcal(prefs, e.steps.toLong())
        days.add(StepsHtmlExporter.Row("${d.dayOfMonth}.${d.monthValue}.${d.year}", e.steps.toLong(), ae, 0, ae > 0))
        val wk = d.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
        val wky = d.get(java.time.temporal.WeekFields.ISO.weekBasedYear())
        val wkey = "$wky-" + if (wk < 10) "0$wk" else "$wk"
        weekSteps[wkey] = (weekSteps[wkey] ?: 0L) + e.steps
        weekLabel[wkey] = "Vk $wk/$wky"
        val mkey = "${d.year}-" + if (d.monthValue < 10) "0${d.monthValue}" else "${d.monthValue}"
        monthSteps[mkey] = (monthSteps[mkey] ?: 0L) + e.steps
        monthLabel[mkey] = StepsHistory.monthNameFi(d.monthValue) + " " + d.year
    }
    days.reverse()

    val todaySteps = stepCounter.currentTodaySteps().toLong()
    val todayActive = estimateActiveKcal(prefs, todaySteps)
    return StepsHtmlExporter.Report(
        "Puhelimen askelanturi", todaySteps, todayActive, 0, todayActive > 0,
        days, mapToRows(prefs, weekSteps, weekLabel), mapToRows(prefs, monthSteps, monthLabel),
    )
}

private fun mapToRows(
    prefs: SharedPreferences,
    steps: LinkedHashMap<String, Long>,
    labels: LinkedHashMap<String, String>,
): List<StepsHtmlExporter.Row> {
    val out = ArrayList<StepsHtmlExporter.Row>()
    for ((k, s) in steps) {
        val a = estimateActiveKcal(prefs, s)
        out.add(StepsHtmlExporter.Row(labels[k] ?: k, s, a, 0, a > 0))
    }
    out.reverse()
    return out
}

private fun formatStepsNum(steps: Long): String = String.format(Locale.US, "%,d", steps).replace(',', ' ')

private fun hhmmPs(ms: Long): String {
    val f = java.text.SimpleDateFormat("HH:mm", FI_PS)
    return f.format(java.util.Date(ms))
}

private fun numText(v: Int): String = if (v > 0) v.toString() else ""

private fun numText(v: Float): String = when {
    v <= 0f -> ""
    v == Math.rint(v.toDouble()).toFloat() -> v.toInt().toString()
    else -> v.toString()
}

private fun parseIntPs(s: String): Int = try { s.trim().toInt() } catch (e: Exception) { 0 }

private fun parseFloatPs(s: String): Float = try { s.trim().replace(',', '.').toFloat() } catch (e: Exception) { 0f }
