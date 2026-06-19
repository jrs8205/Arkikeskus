package org.jrs82.fsclock.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Lähilähdöt natiivina Compose-näkymänä (korvasi View-pohjaisen TransitFragmentin).
 * Logiikka replikoitu 1:1: GPS:n lähimmät HSL-lähdöt pysäkeittäin, suosikit (linjat +
 * pysäkit), ennakoiva haku (linjat + pysäkit/asemat), vuoron aikajana MQTT-live-sijainnilla
 * ja linjanäkymä suunnanvaihdolla. Datakerros ([TransitRepository], [DigitransitApi],
 * [TransitFavorites], [HslMqttClient], [RouteProjection]) on koskematta.
 *
 * Erot View-versioon (tietoiset): SwipeRefresh korvattu alapalkin Päivitä-napilla
 * ([LocalRefreshTick]) + 25 s auto-virkistyksellä; listan DiffUtil korvautuu LazyColumnin
 * avaimilla (sama lopputulos: ei koko listan nytkähdystä).
 */

private const val TRANSIT_AUTO_REFRESH_MS = 25_000L
private const val TRANSIT_SEARCH_DEBOUNCE_MS = 280L
private const val KEY_TRANSIT_MODE_FILTER = "transit_mode_filter"
private const val LOC_FALLBACK_MAX_AGE_MS = 2L * 60_000L
private const val LOC_FALLBACK_MAX_ACCURACY_M = 250f
private const val MAX_PER_STOP = 5
private const val MAX_NEARBY_STOPS = 10
private const val MAX_PER_FAV_STOP = 5
private const val MAX_PER_SELECTED_STOP = 8

private val FI_TR = Locale("fi", "FI")
private val TR_CLOCK = SimpleDateFormat("HH:mm", FI_TR)

// ===================== Listan rivimalli (vastaa TransitFragment.buildItems-tyyppejä) =====================

private sealed class TransitRow(val key: String) {
    // stop != null pysäkkiotsikoilla (avaa koko päivän aikataulun); null ryhmäotsikoilla.
    class HeaderRow(val title: String, val mode: String, key: String, val zone: String,
                    val stop: NearbyStop? = null) : TransitRow("h|$key")
    class DepRow(val d: Departure, key: String) : TransitRow("d|$key")
    class AlertRow(val alert: TransitAlert, key: String) : TransitRow("a|$key")
    class RouteRow(val r: RouteHit) : TransitRow("r|${r.gtfsId}")
    class PlaceRow(val p: PlaceHit) : TransitRow("p|${p.gtfsId}|${p.name}")
}

// ===================== Avattu detaljinäkymä (vuoro tai linja) =====================

private sealed class TransitDetail {
    abstract val mode: String
    abstract val shortName: String

    class Trip(
        val tripGtfsId: String,
        val patternCode: String?,
        val boardStop: String?,
        override val mode: String,
        override val shortName: String,
        val headsign: String,
    ) : TransitDetail()

    class Route(
        val routeGtfsId: String,
        override val shortName: String,
        override val mode: String,
        val preferredPattern: String,
        val preferredHeadsign: String,
    ) : TransitDetail()
}

// ===================== Tilanhaltija (vastaa TransitFragmentin kenttiä + säikeitä) =====================

private class TransitState(private val appContext: Context) {
    var query by mutableStateOf("")
    var lastStops by mutableStateOf<List<NearbyStop>>(emptyList())
    var favStops by mutableStateOf<List<NearbyStop>>(emptyList())
    var searchRoutes by mutableStateOf<List<RouteHit>?>(null)
    var searchPlaces by mutableStateOf<List<PlaceHit>?>(null)
    var selectedStop by mutableStateOf<NearbyStop?>(null)
    var transientStatus by mutableStateOf<String?>(null)
    var favVersion by mutableIntStateOf(0)
    var detail by mutableStateOf<TransitDetail?>(null)
    var favDialogFor by mutableStateOf<Departure?>(null)
    var reminderFor by mutableStateOf<Departure?>(null)    // lähtömuistutuksen ajan asetus (#4)
    var fullDayStop by mutableStateOf<NearbyStop?>(null)   // avoinna koko päivän aikataulu
    var alertDialogFor by mutableStateOf<TransitAlert?>(null)   // häiriön koko teksti

    // Kulkuvälinesuodatin (null = kaikki; muuten BUS/TRAM/RAIL/SUBWAY/FERRY). Valinta
    // talletetaan prefseihin, jotta se säilyy käyntien välillä.
    var modeFilter by mutableStateOf<String?>(
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(KEY_TRANSIT_MODE_FILTER, "")?.takeIf { it.isNotEmpty() }
    )
        private set

    fun updateModeFilter(mode: String?) {
        modeFilter = mode
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putString(KEY_TRANSIT_MODE_FILTER, mode ?: "").apply()
    }

    var lastLat = Double.NaN
    var lastLon = Double.NaN
    private var inFlight = false
    private var placeGeneration = 0L

    @Volatile var disposed = false
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    val searchIo: ExecutorService = Executors.newSingleThreadExecutor()
    val ui = Handler(Looper.getMainLooper())
    var requestPermission: () -> Unit = {}

    fun dispose() {
        disposed = true
        io.shutdownNow()
        searchIo.shutdownNow()
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Vastaa TransitFragment.refresh(): lupa → sijainti → haku. */
    fun refresh() {
        if (disposed) return
        if (!hasLocationPermission()) {
            transientStatus = "Salli sijainti nähdäksesi lähimmät lähdöt."
            requestPermission()
            return
        }
        if (inFlight) return
        inFlight = true
        if (lastStops.isEmpty() && favStops.isEmpty()) transientStatus = "Haetaan lähimpiä lähtöjä…"
        requestLocationThenFetch()
    }

    /** Nopea last-known heti + tarkennettu FusedLocation-haku perään (kuten View-versio). */
    private fun requestLocationThenFetch() {
        try {
            val client = LocationServices.getFusedLocationProviderClient(appContext)
            val fast = lastKnownFromLocationManager()
            val usedFast = fast != null
            if (fast != null) fetch(fast.latitude, fast.longitude)
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                // PERMISSION_LEVEL: hyväksymme myös pelkän coarse-luvan (likimääräinen sijainti
                // Android 12+) — GRANULARITY_FINE heittäisi SecurityExceptionin ilman fine-lupaa.
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .setMaxUpdateAgeMillis(if (usedFast) 3_000L else 10_000L)
                .setDurationMillis(if (usedFast) 5_000L else 8_000L)
                .build()
            client.getCurrentLocation(request, CancellationTokenSource().token)
                .addOnSuccessListener { location ->
                    if (disposed) { inFlight = false; return@addOnSuccessListener }
                    if (location == null) {
                        if (!usedFast) fetchFallbackLocation(client)
                        return@addOnSuccessListener
                    }
                    if (!usedFast || shouldFetchRefined(fast, location)) {
                        fetch(location.latitude, location.longitude)
                    }
                }
                .addOnFailureListener {
                    if (!usedFast) fetchFallbackLocation(client)
                }
        } catch (e: SecurityException) {
            // Lupa ehti kadota tarkistuksen (refresh → hasLocationPermission) jälkeen.
            onFetchFail("Sijaintilupa puuttuu.")
        } catch (e: Exception) {
            onFetchFail("Sijaintia ei voitu lukea.")
        }
    }

    private fun fetchFallbackLocation(client: FusedLocationProviderClient) {
        if (disposed) { inFlight = false; return }
        val errorMessage = "Sijaintia ei saatu. Kokeile Päivitä-nappia hetken päästä."
        try {
            client.lastLocation
                .addOnSuccessListener { location ->
                    if (disposed) { inFlight = false; return@addOnSuccessListener }
                    var best = betterLocation(null, location)
                    best = betterLocation(best, lastKnownFromLocationManager())
                    if (best != null) fetch(best.latitude, best.longitude) else onFetchFail(errorMessage)
                }
                .addOnFailureListener {
                    val best = lastKnownFromLocationManager()
                    if (best != null) fetch(best.latitude, best.longitude) else onFetchFail(errorMessage)
                }
        } catch (e: SecurityException) {
            onFetchFail("Sijaintilupa puuttuu.")
        } catch (e: Exception) {
            val best = lastKnownFromLocationManager()
            if (best != null) fetch(best.latitude, best.longitude) else onFetchFail(errorMessage)
        }
    }

    private fun lastKnownFromLocationManager(): Location? {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        try {
            best = betterLocation(best, lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
        } catch (ignored: SecurityException) {
        } catch (ignored: IllegalArgumentException) {
        }
        try {
            best = betterLocation(best, lm.getLastKnownLocation(LocationManager.GPS_PROVIDER))
        } catch (ignored: SecurityException) {
        } catch (ignored: IllegalArgumentException) {
        }
        return best
    }

    /** Lähtöjen + suosikkipysäkkien haku io-säikeessä (kuten View-versio). */
    private fun fetch(lat: Double, lon: Double) {
        lastLat = lat
        lastLon = lon
        if (disposed) return
        io.execute {
            try {
                val stops = TransitRepository.get().fetch(lat, lon)
                val fav = ArrayList<NearbyStop>()
                for (fs in TransitFavorites.getStops(appContext)) {
                    try {
                        DigitransitApi.stopDepartures(fs.gtfsId)?.let { fav.add(it) }
                    } catch (ignored: Exception) {
                    }
                }
                ui.post {
                    if (disposed) return@post
                    inFlight = false
                    lastStops = stops
                    favStops = fav
                    transientStatus = null
                }
            } catch (e: Exception) {
                ui.post { onFetchFail("Lähtöjen haku epäonnistui. Kokeile Päivitä-nappia.") }
            }
        }
    }

    private fun onFetchFail(msg: String) {
        inFlight = false
        if (disposed) return
        if (lastStops.isEmpty() && favStops.isEmpty()) transientStatus = msg
    }

    /** Ennakoiva haku: linjat + pysäkit/asemat rinnakkain, stale-guard kyselyyn. */
    fun runLiveSearch(q: String) {
        if (disposed) return
        searchIo.execute {
            val routes = try { DigitransitApi.searchRoutes(q) } catch (e: Exception) { ArrayList() }
            // Yksi merkki (esim. junalinjat A/P/I/K/T/E/L/U) → vain linjahaku; paikkahaku (geokoodaus)
            // vaatii ≥2 merkkiä ettei se tuota roskatuloksia.
            val places = if (q.length >= 2) {
                try { DigitransitApi.searchPlaces(q, lastLat, lastLon) } catch (e: Exception) { ArrayList() }
            } else {
                ArrayList()
            }
            ui.post {
                if (disposed) return@post
                if (selectedStop != null || q != query.trim()) return@post   // vanhentunut tulos
                searchRoutes = routes
                searchPlaces = places
            }
        }
    }

    /** Haun pysäkki-/asematuloksen avaus: hae lähdöt ja näytä ne (vastaa onPlaceClick). */
    fun openPlace(p: PlaceHit) {
        if (p.gtfsId.isNullOrEmpty()) {
            Toast.makeText(appContext, "Tälle kohteelle ei ole lähtötietoja.", Toast.LENGTH_SHORT).show()
            return
        }
        transientStatus = "Haetaan lähtöjä: ${p.name}…"
        val request = ++placeGeneration
        searchIo.execute {
            val ns = try {
                if (p.station) DigitransitApi.stationDepartures(p.gtfsId) else DigitransitApi.stopDepartures(p.gtfsId)
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (disposed || request != placeGeneration) return@post
                if (ns == null || ns.departures.isEmpty()) {
                    transientStatus = "Ei tulevia lähtöjä: ${p.name}"
                    return@post
                }
                searchRoutes = null
                searchPlaces = null
                selectedStop = ns
                transientStatus = null
            }
        }
    }

    fun bumpPlaceGeneration() {
        placeGeneration++
    }

    /** Paras lähilistan lähtö linjalle: lähin pysäkki + pian lähtevä (vastaa View-versiota). */
    fun preferredDepartureForRoute(routeGtfsId: String?): Departure? {
        if (routeGtfsId.isNullOrEmpty()) return null
        var best: Departure? = null
        var bestScore = Double.POSITIVE_INFINITY
        val now = System.currentTimeMillis() / 1000L
        for (stop in lastStops) {
            val dist = if (stop.distanceMeters.isFinite()) maxOf(0.0, stop.distanceMeters) else 1000.0
            for (d in stop.departures) {
                if (routeGtfsId != d.routeGtfsId) continue
                val waitSec = maxOf(0L, d.departureEpochSec - now)
                val score = dist + minOf(waitSec / 60.0, 60.0) * 2.0
                if (score < bestScore) {
                    bestScore = score
                    best = d
                }
            }
        }
        return best
    }

    /** Listan rakennus — replikoi TransitFragment.buildItems() 1:1. */
    fun buildItems(ctx: Context): List<TransitRow> {
        val items = ArrayList<TransitRow>()
        // Kulkuvälinesuodatin koskee suosikki- ja lähipysäkkien lähtöjä; valittu pysäkki
        // ja hakutulokset näytetään aina suodattamatta.
        val mf = modeFilter
        fun depMatches(d: Departure) = mf == null || (d.mode ?: "BUS").equals(mf, ignoreCase = true)

        val sel = selectedStop
        if (sel != null) {
            val deps = sel.departures.sortedBy { it.departureEpochSec }
            val sectionKey = "selected-stop|${sel.gtfsId}"
            items.add(TransitRow.HeaderRow(stopHeaderText(sel), headerModeOf(sel), sectionKey, zoneLabelOf(sel), sel))
            addAlertRows(items, sel, sectionKey)
            addDepRows(items, deps, minOf(deps.size, MAX_PER_SELECTED_STOP), sectionKey)
            return items
        }

        if (query.trim().isNotEmpty()) {
            val routes = searchRoutes
            if (!routes.isNullOrEmpty()) {
                items.add(TransitRow.HeaderRow("Linjat", "FAV", "search-routes", ""))
                routes.forEach { items.add(TransitRow.RouteRow(it)) }
            }
            val places = searchPlaces
            if (!places.isNullOrEmpty()) {
                items.add(TransitRow.HeaderRow("Pysäkit ja asemat", "BUS", "search-places", ""))
                places.forEach { items.add(TransitRow.PlaceRow(it)) }
            }
            return items
        }

        for (fs in favStops) {
            val deps = fs.departures.filter { depMatches(it) }.sortedBy { it.departureEpochSec }
            val n = minOf(deps.size, MAX_PER_FAV_STOP)
            if (n == 0) continue
            val sectionKey = "favorite-stop|${fs.gtfsId}"
            items.add(TransitRow.HeaderRow(stopHeaderText(fs) + " ★", fs.vehicleMode, sectionKey, zoneLabelOf(fs), fs))
            addAlertRows(items, fs, sectionKey)
            addDepRows(items, deps, n, sectionKey)
        }

        val favLines = TransitFavorites.getLines(ctx)
        if (favLines.isNotEmpty()) {
            items.add(TransitRow.HeaderRow("Suosikkilinjat", "FAV", "favorite-routes", ""))
            favLines.forEach { items.add(TransitRow.RouteRow(it)) }
        }

        var stopsShown = 0
        for (stop in lastStops) {
            val deps = stop.departures.filter { depMatches(it) }.sortedBy { it.departureEpochSec }
            if (deps.isEmpty()) continue
            if (stopsShown >= MAX_NEARBY_STOPS) break
            val sectionKey = "nearby-stop|${stop.gtfsId}"
            items.add(TransitRow.HeaderRow(stopHeaderText(stop), headerModeOf(stop), sectionKey, zoneLabelOf(stop), stop))
            addAlertRows(items, stop, sectionKey)
            addDepRows(items, deps, minOf(deps.size, MAX_PER_STOP), sectionKey)
            stopsShown++
        }
        return items
    }

    private fun addDepRows(items: MutableList<TransitRow>, deps: List<Departure>, count: Int, sectionKey: String) {
        for (i in 0 until count) {
            val d = deps[i]
            items.add(TransitRow.DepRow(d, "$sectionKey|${d.tripGtfsId}|${d.stopGtfsId}|${d.departureEpochSec}"))
        }
    }

    /** Pysäkin häiriötiedotteet otsikon alle, vakavin ensin. */
    private fun addAlertRows(items: MutableList<TransitRow>, stop: NearbyStop, sectionKey: String) {
        val sorted = stop.alerts.sortedByDescending { it.severityRank() }
        for ((idx, a) in sorted.withIndex()) {
            items.add(TransitRow.AlertRow(a, "$sectionKey|$idx"))
        }
    }
}

// ===================== Apurit (portattu TransitFragmentista) =====================

private fun headerModeOf(s: NearbyStop): String {
    if (!s.vehicleMode.isNullOrEmpty()) return s.vehicleMode
    for (d in s.departures) {
        if (!d.mode.isNullOrEmpty()) return d.mode
    }
    return "BUS"
}

private fun stopHeaderText(s: NearbyStop): String {
    val name = if (s.name.isNullOrEmpty()) "Pysäkki" else s.name
    val code = if (s.code.isNullOrEmpty()) "" else " " + s.code
    if (s.distanceMeters.isNaN() || s.distanceMeters < 0) return name + code
    val m = Math.round(s.distanceMeters)
    return if (m < 1000) "$name$code · $m m"
    else "$name$code · " + String.format(FI_TR, "%.1f km", m / 1000.0)
}

private fun zoneLabelOf(s: NearbyStop?): String {
    var z = s?.zoneId?.trim() ?: return ""
    if (z.isEmpty()) return ""
    val colon = z.lastIndexOf(':')
    if (colon >= 0 && colon + 1 < z.length) z = z.substring(colon + 1)
    return z.uppercase(Locale.ROOT)
}

private fun depTimeText(d: Departure): String {
    val nowSec = System.currentTimeMillis() / 1000L
    val diffSec = d.departureEpochSec - nowSec
    if (diffSec <= 30) return "nyt"
    val minutes = diffSec / 60L
    return if (minutes < 60) "$minutes min" else TR_CLOCK.format(Date(d.departureEpochSec * 1000L))
}

private fun transitModeWord(mode: String?): String = when (mode) {
    "TRAM" -> "Ratikka"
    "RAIL" -> "Juna"
    "SUBWAY" -> "Metro"
    "FERRY" -> "Lautta"
    else -> "Bussi"
}

private fun timelineModeWord(mode: String?): String = when (mode) {
    "TRAM" -> "ratikka"
    "RAIL" -> "juna"
    "SUBWAY" -> "metro"
    else -> "bussi"
}

private fun liveClockText(epochSec: Long): String = TR_CLOCK.format(Date(epochSec * 1000L))

private fun formatMetersFi(m: Double): String {
    if (m >= 1000) return String.format(FI_TR, "%.1f km", m / 1000.0)
    val r = Math.round(m / 10.0) * 10
    return "$r m"
}

private fun betterLocation(current: Location?, candidate: Location?): Location? {
    if (!isUsableLocation(candidate)) return current
    if (current == null || !isUsableLocation(current)) return candidate
    val ca = if (candidate!!.hasAccuracy()) candidate.accuracy else LOC_FALLBACK_MAX_ACCURACY_M
    val ba = if (current.hasAccuracy()) current.accuracy else LOC_FALLBACK_MAX_ACCURACY_M
    if (candidate.time > current.time + 60_000L) return candidate
    return if (ca <= ba) candidate else current
}

private fun isUsableLocation(location: Location?): Boolean {
    if (location == null || location.time <= 0L) return false
    val age = System.currentTimeMillis() - location.time
    if (age < 0L || age > LOC_FALLBACK_MAX_AGE_MS) return false
    return !location.hasAccuracy() || location.accuracy <= LOC_FALLBACK_MAX_ACCURACY_M
}

private fun shouldFetchRefined(first: Location?, next: Location?): Boolean {
    if (next == null) return false
    if (first == null) return true
    val firstAcc = if (first.hasAccuracy()) first.accuracy else LOC_FALLBACK_MAX_ACCURACY_M
    val nextAcc = if (next.hasAccuracy()) next.accuracy else firstAcc
    if (nextAcc + 15f < firstAcc) return true
    return metersBetweenPts(first.latitude, first.longitude, next.latitude, next.longitude) >= 20.0
}

private fun metersBetweenPts(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1)
    val dl = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dp / 2.0) * Math.sin(dp / 2.0) +
        Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2.0) * Math.sin(dl / 2.0)
    return 2.0 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a))
}

// ===================== Päänäkymä =====================

@Composable
internal fun TransitScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { TransitState(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { state.dispose() }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) state.refresh()
        else state.transientStatus = "Sijaintilupa tarvitaan lähimpien lähtöjen näyttämiseen."
    }
    state.requestPermission = {
        permLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    // Ennakoiva haku kirjoituksen tauottua (vastaa searchDebounce-Runnablea).
    LaunchedEffect(state.query) {
        val q = state.query.trim()
        if (q.isEmpty()) return@LaunchedEffect
        delay(TRANSIT_SEARCH_DEBOUNCE_MS)
        // 1 merkki riittää (HSL:n junalinjat ovat yksikirjaimisia: A/P/I/K/T/E/L/U…); q on jo ei-tyhjä.
        state.runLiveSearch(q)
    }

    // Resume-virkistys + 25 s auto-virkistys vain etualalla (vastaa onResume + autoRefresh-tick).
    val detailOpen = rememberUpdatedState(state.detail != null)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!detailOpen.value && state.query.isEmpty() && state.selectedStop == null) state.refresh()
            while (true) {
                delay(TRANSIT_AUTO_REFRESH_MS)
                if (!detailOpen.value && state.query.isEmpty() && state.selectedStop == null) state.refresh()
            }
        }
    }

    // Alapalkin Päivitä-nappi virkistää myös tämän sektion.
    val refreshTick = LocalRefreshTick.current
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) state.refresh()
    }

    val favTick = state.favVersion
    val items = remember(
        state.lastStops, state.favStops, state.searchRoutes, state.searchPlaces,
        state.selectedStop, state.query, favTick, state.modeFilter,
    ) { state.buildItems(context) }

    val trimmedQuery = state.query.trim()
    val emptyStatus: String? = if (items.isNotEmpty()) null else when {
        state.selectedStop != null -> "Ei tulevia lähtöjä tältä pysäkiltä."
        trimmedQuery.isNotEmpty() -> when {
            state.searchRoutes == null && state.searchPlaces == null -> "Haetaan \"$trimmedQuery\"…"
            else -> "Ei osumia haulla \"$trimmedQuery\".\nKokeile pysäkin, aseman tai linjan nimeä/numeroa."
        }
        state.modeFilter != null && (state.lastStops.isNotEmpty() || state.favStops.isNotEmpty()) ->
            "Ei valitun kulkuvälineen lähtöjä lähistöllä."
        else -> null
    }
    val statusText = state.transientStatus ?: emptyStatus

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Lähilähdöt",
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            SearchTextField(
                value = state.query,
                onValueChange = { q ->
                    state.bumpPlaceGeneration()
                    state.query = q
                    state.selectedStop = null   // tekstin muokkaus poistaa valitun pysäkin
                    state.transientStatus = null
                    if (q.trim().isEmpty()) {
                        state.searchRoutes = null
                        state.searchPlaces = null
                    }
                },
                placeholder = "Hae linja (esim. 550), pysäkki tai asema",
                modifier = Modifier.padding(horizontal = 14.dp),
                onSearch = {
                    if (state.query.trim().isNotEmpty()) state.runLiveSearch(state.query)
                },
            )
            // Kulkuvälinesuodatin — näkyy vain lähilistassa (ei haussa eikä valitulla pysäkillä).
            if (state.selectedStop == null && trimmedQuery.isEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(start = 14.dp, end = 14.dp, top = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.modeFilter == null,
                        onClick = { state.updateModeFilter(null) },
                        label = { Text("Kaikki") },
                    )
                    listOf(
                        "BUS" to "Bussit", "TRAM" to "Ratikat", "RAIL" to "Junat",
                        "SUBWAY" to "Metro", "FERRY" to "Lautat",
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = state.modeFilter == mode,
                            onClick = { state.updateModeFilter(if (state.modeFilter == mode) null else mode) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 20.dp),
                ) {
                    // Ohje lähtömuistutuksesta (#4): kertoo ominaisuudesta ja sen käytöstä. Vierii pois listan mukana.
                    if (items.isNotEmpty()) {
                        item(key = "reminder_hint") {
                            Text(
                                "Vinkki: tallenna lähtö suosikiksi ja aseta muistutus.\n" +
                                    "Saat ilmoituksen 1–60 minuuttia ennen lähtöä.",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(items, key = { it.key }) { row ->
                        when (row) {
                            is TransitRow.HeaderRow -> TransitHeaderRow(row, onFullDay = { state.fullDayStop = it })
                            is TransitRow.DepRow -> TransitDepartureRow(row.d, favTick, state)
                            is TransitRow.AlertRow -> TransitAlertRow(row.alert) { state.alertDialogFor = it }
                            is TransitRow.RouteRow -> TransitRouteRow(row.r, favTick, state)
                            is TransitRow.PlaceRow -> TransitPlaceRow(row.p, state)
                        }
                    }
                }
                statusText?.let {
                    Text(
                        it,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.detail?.let { det ->
            TransitDetailOverlay(detail = det, onClose = { state.detail = null })
        }

        state.fullDayStop?.let { fd ->
            TransitFullDayOverlay(
                stop = fd,
                onClose = { state.fullDayStop = null },
                onDeparture = { d ->
                    state.fullDayStop = null
                    if (!d.tripGtfsId.isNullOrEmpty()) {
                        state.detail = TransitDetail.Trip(
                            d.tripGtfsId, d.patternCode, d.stopGtfsId, d.mode ?: "",
                            d.routeShortName ?: "", d.directionLabel() ?: "",
                        )
                    }
                },
            )
        }

        state.favDialogFor?.let { d ->
            TransitFavoriteDialog(d, state) { state.favDialogFor = null }
        }

        state.reminderFor?.let { d ->
            TransitReminderDialog(d) { state.reminderFor = null }
        }

        state.alertDialogFor?.let { a ->
            TransitAlertDialog(a) { state.alertDialogFor = null }
        }
    }
}

// ===================== Listarivit =====================

@Composable
private fun TransitHeaderRow(row: TransitRow.HeaderRow, onFullDay: (NearbyStop) -> Unit = {}) {
    val stop = row.stop
    val base = Modifier.fillMaxWidth()
    Row(
        modifier = (if (stop != null) base.clickable { onFullDay(stop) } else base)
            .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(transitModeColor(row.mode), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(transitModeIconRes(row.mode)),
                contentDescription = null,
                tint = transitOnModeColor(row.mode),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            row.title,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (stop != null) {
            Text(
                "Koko päivä ›",
                modifier = Modifier.padding(start = 8.dp),
                color = colorResource(R.color.mobile_accent),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (row.zone.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(28.dp)
                    .background(colorResource(R.color.mobile_transit_bus), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(row.zone, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransitDepartureRow(d: Departure, favTick: Int, state: TransitState) {
    val context = LocalContext.current
    val fav = remember(d.routeGtfsId, favTick) { TransitFavorites.isLineFav(context, d.routeGtfsId) }
    ArkiCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        if (!d.tripGtfsId.isNullOrEmpty()) {
                            state.detail = TransitDetail.Trip(
                                d.tripGtfsId, d.patternCode, d.stopGtfsId, d.mode ?: "",
                                d.routeShortName ?: "", d.directionLabel() ?: "",
                            )
                        }
                    },
                    onLongClick = { state.favDialogFor = d },
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .widthIn(min = 46.dp)
                    .background(transitModeColor(d.mode), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (d.routeShortName.isNullOrEmpty()) "?" else d.routeShortName,
                    color = transitOnModeColor(d.mode),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                val direction = d.directionLabel()
                Text(
                    if (direction.isNullOrEmpty()) "—" else direction,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier.padding(start = 10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    depTimeText(d),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(
                        if (d.realtime) R.color.mobile_transit_tram else R.color.mobile_text_secondary,
                    ),
                )
                if (d.realtime && Math.abs(d.delaySeconds) >= 60) {
                    val min = Math.round(d.delaySeconds / 60.0f)
                    Text(
                        if (d.delaySeconds > 0) "+$min min" else "$min min",
                        fontSize = 12.sp,
                        color = colorResource(
                            if (d.delaySeconds > 0) R.color.mobile_warning_red else R.color.mobile_transit_tram,
                        ),
                    )
                }
            }
            IconButton(
                onClick = {
                    val now = TransitFavorites.toggleLineFav(
                        context, d.routeGtfsId, d.routeShortName, "", d.mode,
                    )
                    Toast.makeText(
                        context,
                        if (now) "Linja lisätty suosikkeihin" else "Linja poistettu suosikeista",
                        Toast.LENGTH_SHORT,
                    ).show()
                    state.favVersion++
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painterResource(if (fav) R.drawable.mobile_ic_star else R.drawable.mobile_ic_star_outline),
                    contentDescription = "Suosikki",
                    tint = Color.Unspecified,
                )
            }
        }
    }
}

@Composable
private fun TransitRouteRow(r: RouteHit, favTick: Int, state: TransitState) {
    val context = LocalContext.current
    val fav = remember(r.gtfsId, favTick) { TransitFavorites.isLineFav(context, r.gtfsId) }
    ArkiCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable {
                    // Napautus AVAA linjan (reitti + aikataulu); suosikki hoidetaan tähdellä.
                    val preferred = state.preferredDepartureForRoute(r.gtfsId)
                    state.detail = TransitDetail.Route(
                        r.gtfsId, r.shortName ?: "", r.mode ?: "",
                        preferred?.patternCode ?: "", preferred?.headsign ?: "",
                    )
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .widthIn(min = 46.dp)
                    .background(transitModeColor(r.mode), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (r.shortName.isNullOrEmpty()) "?" else r.shortName,
                    color = transitOnModeColor(r.mode),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                r.longName ?: "",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = {
                    TransitFavorites.toggleLineFav(context, r.gtfsId, r.shortName, r.longName, r.mode)
                    state.favVersion++
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painterResource(if (fav) R.drawable.mobile_ic_star else R.drawable.mobile_ic_star_outline),
                    contentDescription = "Suosikki",
                    tint = Color.Unspecified,
                )
            }
        }
    }
}

@Composable
private fun TransitPlaceRow(p: PlaceHit, state: TransitState) {
    ArkiCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable { state.openPlace(p) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (p.name.isNullOrEmpty()) "?" else p.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!p.locality.isNullOrEmpty()) {
                    Text(
                        p.locality,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!p.modes.isNullOrEmpty()) {
                Row(
                    modifier = Modifier.padding(start = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    p.modes.forEach { m ->
                        Icon(
                            painterResource(transitModeIconRes(m)),
                            contentDescription = m,
                            tint = transitModeColor(m),
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Pitkän painalluksen valikko lähdölle: suosikkilinja/-pysäkki (asetussivun täpät) + lähtömuistutus. */
@Composable
private fun TransitFavoriteDialog(d: Departure, state: TransitState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var lineFav by remember { mutableStateOf(TransitFavorites.isLineFav(context, d.routeGtfsId)) }
    var stopFav by remember { mutableStateOf(TransitFavorites.isStopFav(context, d.stopGtfsId)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Sulje") }
        },
        text = {
            // Samat täpät/rivit kuin asetussivulla (SwitchRow/ClickableRow) → yhtenäinen ulkoasu.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow(
                    title = "Suosikkilinja",
                    subtitle = "Linja ${d.routeShortName ?: ""}",
                    leadingIconRes = R.drawable.mobile_ic_star_outline,
                    checked = lineFav,
                ) {
                    lineFav = TransitFavorites.toggleLineFav(context, d.routeGtfsId, d.routeShortName, "", d.mode)
                    state.favVersion++
                }
                SwitchRow(
                    title = "Suosikkipysäkki",
                    subtitle = d.stopName ?: "",
                    leadingIconRes = R.drawable.mobile_ic_star_outline,
                    checked = stopFav,
                ) {
                    stopFav = TransitFavorites.toggleStopFav(context, d.stopGtfsId, d.stopName)
                    state.favVersion++
                    state.refresh()
                }
                // Lähtömuistutus (#4): klikattava rivi (chevron-nuoli) joka avaa ajan asetus -ikkunan.
                // Vain jos lähtö on >= 2 min päässä → säädin 1–60 min on aina kelvollinen.
                if (d.departureEpochSec - System.currentTimeMillis() / 1000L >= 120) {
                    ClickableRow(
                        title = "Lisää muistutus lähtöajasta",
                        subtitle = "Saat ilmoituksen ennen lähtöä",
                        leadingIconRes = R.drawable.mobile_ic_clock_24,
                        trailingIconRes = R.drawable.mobile_ic_chevron_right_24,
                    ) {
                        onDismiss()
                        state.reminderFor = d
                    }
                }
            }
        },
    )
}

/** Lähtömuistutuksen (#4) ajan asetus: 0–60 min ennen lähtöä (rajattu lähdön ajankohtaan). */
@Composable
private fun TransitReminderDialog(d: Departure, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Ilmoituslupa (Android 13+): myös muistutuksen asettaminen pyytää POST_NOTIFICATIONS jos se puuttuu.
    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val nowSec = System.currentTimeMillis() / 1000L
    val maxLead = minOf(60, ((d.departureEpochSec - nowSec) / 60L).toInt()).coerceAtLeast(2)
    var lead by remember { mutableFloatStateOf(minOf(10, maxLead).toFloat()) }
    val leadMin = lead.toInt()
    val remindAtSec = d.departureEpochSec - leadMin * 60L
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val ok = DepartureReminder.schedule(
                    context, d.routeShortName ?: "", d.directionLabel() ?: "",
                    d.stopName ?: "", d.departureEpochSec,
                    "${d.tripGtfsId}|${d.stopGtfsId}|${d.departureEpochSec}", leadMin,
                )
                Toast.makeText(
                    context,
                    if (ok) "Muistutus $leadMin min ennen lähtöä (klo ${TR_CLOCK.format(Date(remindAtSec * 1000L))})"
                    else "Lähtö on jo liian lähellä",
                    Toast.LENGTH_LONG,
                ).show()
                onDismiss()
            }) { Text("Aseta muistutus") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Peruuta") } },
        title = {
            Text(
                (if (!d.routeShortName.isNullOrEmpty()) "Linja ${d.routeShortName} " else "Lähtö ") +
                    "klo ${TR_CLOCK.format(Date(d.departureEpochSec * 1000L))}",
            )
        },
        text = {
            Column {
                Text("Muistuta $leadMin min ennen lähtöä", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ilmoitus klo ${TR_CLOCK.format(Date(remindAtSec * 1000L))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = lead,
                    onValueChange = { lead = it },
                    valueRange = 1f..maxLead.toFloat(),
                    steps = (maxLead - 2).coerceAtLeast(0),
                )
                if (!d.stopName.isNullOrEmpty()) {
                    Text(
                        "Pysäkiltä ${d.stopName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

// ===================== Häiriötiedotteet =====================

private fun alertColorRes(rank: Int): Int = when {
    rank >= 3 -> R.color.mobile_alert_severe      // SEVERE
    rank == 2 -> R.color.mobile_alert_warning     // WARNING
    else -> R.color.mobile_alert_info             // INFO / tuntematon
}

@Composable
private fun TransitAlertRow(a: TransitAlert, onClick: (TransitAlert) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colorResource(alertColorRes(a.severityRank())))
            .clickable { onClick(a) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.mobile_ic_info_24),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text(
            a.displayText(),
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TransitAlertDialog(a: TransitAlert, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Sulje") } },
        title = {
            Text(
                if (a.header.isNotEmpty()) a.header else "Häiriötiedote",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            val body = if (a.description.isNotEmpty()) a.description else a.displayText()
            Text(
                body,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
        },
    )
}

// ===================== Koko päivän aikataulu (per pysäkki) =====================

private sealed class FullDayRow {
    class Hour(val label: String) : FullDayRow()
    class Dep(val d: Departure) : FullDayRow()
}

private val TR_HOUR = SimpleDateFormat("HH", FI_TR)

/** Tunti-otsikko jokaiselle (aikajärjestyksessä olevalle) lähdölle, tai null jos sama tunti kuin
 *  edellinen. Eriytetty puhtaaksi funktioksi yksikkötestiä varten. */
internal fun fullDayHourHeaders(epochSecs: List<Long>): List<String?> {
    val out = ArrayList<String?>(epochSecs.size)
    var last = ""
    for (e in epochSecs) {
        val h = TR_HOUR.format(Date(e * 1000L))
        if (h != last) {
            out.add("klo $h")
            last = h
        } else {
            out.add(null)
        }
    }
    return out
}

private fun buildFullDayRows(deps: List<Departure>): List<FullDayRow> {
    val sorted = deps.sortedBy { it.departureEpochSec }
    val headers = fullDayHourHeaders(sorted.map { it.departureEpochSec })
    val out = ArrayList<FullDayRow>(sorted.size + 24)
    for (i in sorted.indices) {
        headers[i]?.let { out.add(FullDayRow.Hour(it)) }
        out.add(FullDayRow.Dep(sorted[i]))
    }
    return out
}

@Composable
private fun TransitFullDayOverlay(
    stop: NearbyStop,
    onClose: () -> Unit,
    onDeparture: (Departure) -> Unit,
) {
    BackHandler(onBack = onClose)
    var rows by remember(stop) { mutableStateOf<List<FullDayRow>?>(null) }
    var status by remember(stop) { mutableStateOf("Haetaan koko päivän aikataulua…") }
    LaunchedEffect(stop) {
        val ns = withContext(Dispatchers.IO) {
            try {
                // stop(id) palauttaa nullin asema-id:lle → fallback station(id):iin.
                DigitransitApi.stopDeparturesFullDay(stop.gtfsId)
                    ?: DigitransitApi.stationDeparturesFullDay(stop.gtfsId)
            } catch (e: Exception) {
                null
            }
        }
        if (ns == null || ns.departures.isEmpty()) {
            status = "Koko päivän aikataulua ei saatu."
            rows = emptyList()
        } else {
            rows = buildFullDayRows(ns.departures)
            status = ""
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.mobile_ic_arrow_back), contentDescription = "Takaisin")
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            ) {
                Text(
                    if (stop.name.isNullOrEmpty()) "Pysäkki" else stop.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Koko päivän aikataulu",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val r = rows
        if (r.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Text(
                    status,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 20.dp),
            ) {
                items(
                    r.size,
                    // Pysyvä avain: tunti-otsikko labelista, lähtö tripistä + ajasta → ei skrollihyppyä
                    // päivityksessä. (i mukana erottamassa harvinaiset duplikaatit.)
                    key = { i ->
                        when (val row = r[i]) {
                            is FullDayRow.Hour -> "h:${row.label}"
                            is FullDayRow.Dep -> "d:${row.d.tripGtfsId}:${row.d.departureEpochSec}:$i"
                        }
                    },
                ) { i ->
                    when (val row = r[i]) {
                        is FullDayRow.Hour -> Text(
                            row.label,
                            modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is FullDayRow.Dep -> FullDayDepRow(row.d, onDeparture)
                    }
                }
            }
        }
    }
}

@Composable
private fun FullDayDepRow(d: Departure, onClick: (Departure) -> Unit) {
    ArkiCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable { onClick(d) }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .widthIn(min = 42.dp)
                    .background(transitModeColor(d.mode), RoundedCornerShape(7.dp))
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (d.routeShortName.isNullOrEmpty()) "?" else d.routeShortName,
                    color = transitOnModeColor(d.mode),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            val direction = d.directionLabel()
            Text(
                if (direction.isNullOrEmpty()) "—" else direction,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                liveClockText(d.departureEpochSec),
                modifier = Modifier.padding(start = 8.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = colorResource(
                    if (d.realtime) R.color.mobile_transit_tram else R.color.mobile_text_secondary,
                ),
            )
        }
    }
}

// ===================== Detaljioverlay: vuoron aikajana / linjanäkymä =====================

/** Live-MQTT-projektion tila (vastaa TransitFragmentin liveCum/liveStopDist/liveStopVertex/liveLastAlong). */
private class LiveTracking(val tl: TripTimeline) {
    val cum: DoubleArray = RouteProjection.cumulative(tl.shape)
    val stopVertex = IntArray(tl.stops.size)
    val stopDist: DoubleArray = RouteProjection.stopDistances(tl.shape, cum, tl.stops, stopVertex)
    var lastAlong = Double.NaN

    /** VP-päivitys → uusi banneriteksti tai null jos päivitys hylätään (sama suodatus kuin View). */
    fun bannerFor(mode: String, lat: Double, lon: Double, loc: String?, tsi: Long): String? {
        if (!lat.isFinite() || !lon.isFinite() || lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        val nowSec = System.currentTimeMillis() / 1000L
        if (tsi > 0 && (tsi < nowSec - 60 || tsi > nowSec + 30)) return null
        val board = tl.boardStopIndex
        if (board < 0 || board >= stopDist.size) return null

        var loV = 0
        var hiV = tl.shape.size - 1
        if (tl.vehicleStopIndices.isNotEmpty()) {
            val relIdx = maxOf(0, minOf(tl.vehicleStopIndices[0], stopVertex.size - 1))
            val loIdx = maxOf(0, relIdx - 2)
            val hiIdx = minOf(stopVertex.size - 1, relIdx + 3)
            loV = maxOf(0, stopVertex[loIdx] - 4)
            hiV = minOf(tl.shape.size - 1, stopVertex[hiIdx] + 4)
            if (hiV - loV < 2) {
                loV = 0
                hiV = tl.shape.size - 1
            }
        }
        val minAlong = if (lastAlong.isFinite()) maxOf(0.0, lastAlong - 75) else 0.0
        val pr = RouteProjection.project(tl.shape, cum, lat, lon, loV, hiV, minAlong)
        if (!pr[0].isFinite() || pr[1] > 250) return null
        if (lastAlong.isFinite() && pr[0] + 30 < lastAlong) return null
        lastAlong = if (lastAlong.isFinite()) maxOf(lastAlong, pr[0]) else pr[0]
        val toBoard = stopDist[board] - lastAlong
        val word = transitModeWord(mode)
        val src = if ("GPS".equals(loc, ignoreCase = true)) "" else " (arvio)"
        if (toBoard < -30) return "$word on jo ohittanut pysäkkisi$src."
        val boardEpoch = tl.stops[board].depEpochSec
        var eta = ""
        if (boardEpoch > 0) {
            val secs = boardEpoch - System.currentTimeMillis() / 1000L
            eta = if (secs in 0 until 3600) {
                " (saapuu ~klo ${liveClockText(boardEpoch)}, ~${maxOf(1, Math.round(secs / 60.0))} min)"
            } else {
                " (saapuu ~klo ${liveClockText(boardEpoch)})"
            }
        }
        return "$word ~${formatMetersFi(maxOf(0.0, toBoard))} ennen pysäkkiäsi$eta$src."
    }
}

private fun tripBannerText(tl: TripTimeline, mode: String): String {
    val word = transitModeWord(mode)
    if (tl.vehicleStopIndices.isEmpty()) return "Ei live-sijaintia — vuoro ei ole vielä liikkeellä."
    val relIdx = tl.vehicleStopIndices[0]
    val approaching = tl.vehicleIncoming
    val at = if (relIdx >= 0 && relIdx < tl.stops.size) tl.stops[relIdx].name ?: "" else ""
    // Lähestyttäessä bussi on vielä edellisellä välillä → efektiivinen "viimeksi ohitettu" = relIdx-1.
    val effIdx = if (approaching) relIdx - 1 else relIdx
    if (tl.boardStopIndex >= 0) {
        val n = tl.boardStopIndex - effIdx
        if (n > 0) {
            val pos = if (approaching) "matkalla pysäkille $at" else "pysäkillä $at"
            return "$word on $n pysäkin päässä pysäkistäsi (nyt $pos)."
        }
        if (n == 0) return "$word on pysäkilläsi tai aivan vieressä ($at)."
        return "$word on jo ohittanut pysäkkisi (nyt: $at)."
    }
    return word + (if (approaching) " on matkalla pysäkille " else " on pysäkillä ") + at + "."
}

private fun routeBannerText(tl: TripTimeline): String {
    val c = tl.vehicleStopIndices.size
    if (c == 0) return "Ei liikkeellä olevia vuoroja juuri nyt — alla reitti ja seuraavat lähtöajat."
    return c.toString() + (if (c == 1) " vuoro liikkeellä" else " vuoroa liikkeellä") +
        " — sijainti korostettu. Ajat = seuraava lähtö kultakin pysäkiltä."
}

@Composable
private fun TransitDetailOverlay(detail: TransitDetail, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    BackHandler(onBack = onClose)

    var routePatterns by remember(detail) { mutableStateOf<RoutePatterns?>(null) }
    var patternIdx by remember(detail) { mutableIntStateOf(0) }
    var timeline by remember(detail) { mutableStateOf<TripTimeline?>(null) }
    var banner by remember(detail) {
        mutableStateOf(if (detail is TransitDetail.Route) "Haetaan linjan tietoja…" else "Haetaan vuoron tietoja…")
    }
    val liveHolder = remember(detail) { mutableStateOf<LiveTracking?>(null) }
    var detailAlert by remember(detail) { mutableStateOf<TransitAlert?>(null) }

    // Linjanäkymä: hae suunnat kerran (vastaa openRoute-iota).
    LaunchedEffect(detail) {
        if (detail !is TransitDetail.Route) return@LaunchedEffect
        val rp = withContext(Dispatchers.IO) {
            try { DigitransitApi.routePatterns(detail.routeGtfsId) } catch (e: Exception) { null }
        }
        if (rp == null || rp.patterns.isEmpty()) {
            banner = "Linjan tietoja ei saatu."
            return@LaunchedEffect
        }
        routePatterns = rp
        patternIdx = preferredPatternIndexOf(rp, detail.preferredPattern, detail.preferredHeadsign)
    }

    // Aikajanan haku heti + 25 s välein VAIN etualalla (vastaa reloadTimeline + tick + onResume).
    LaunchedEffect(detail, routePatterns, patternIdx) {
        val isRoute = detail is TransitDetail.Route
        if (isRoute && routePatterns == null) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (isRoute) banner = banner.takeIf { timeline != null } ?: "Haetaan aikataulua…"
                val tl = withContext(Dispatchers.IO) {
                    try {
                        when (detail) {
                            is TransitDetail.Trip ->
                                DigitransitApi.tripTimeline(detail.tripGtfsId, detail.patternCode, detail.boardStop)
                            is TransitDetail.Route ->
                                DigitransitApi.patternTimetable(
                                    routePatterns!!.patterns[patternIdx].code, detail.shortName, detail.mode,
                                )
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (tl == null || tl.stops.isEmpty()) {
                    banner = if (isRoute) "Linjan aikataulua ei saatu." else "Vuoron tietoja ei saatu."
                } else {
                    timeline = tl
                    banner = if (isRoute) routeBannerText(tl) else tripBannerText(tl, detail.mode)
                    liveHolder.value = if (!isRoute && tl.boardStopIndex >= 0 &&
                        !tl.vehicleId.isNullOrEmpty() && tl.shape.size >= 2
                    ) {
                        LiveTracking(tl)
                    } else {
                        null
                    }
                }
                delay(TRANSIT_AUTO_REFRESH_MS)
            }
        }
    }

    // MQTT-livetilaus: vain vuoronäkymälle, vain etualalla; katkeaa overlayn sulkeutuessa/taustalla.
    var resumed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val liveVehicleId = if (detail is TransitDetail.Trip) liveHolder.value?.tl?.vehicleId else null
    DisposableEffect(liveVehicleId, resumed) {
        if (liveVehicleId.isNullOrEmpty() || !resumed) {
            onDispose { }
        } else {
            val client = HslMqttClient()
            val main = Handler(Looper.getMainLooper())
            client.subscribeVehicle(liveVehicleId) { lat, lon, _, _, loc, _, tsi ->
                main.post {
                    val holder = liveHolder.value ?: return@post
                    val text = holder.bannerFor(detail.mode, lat, lon, loc, tsi)
                    if (text != null) banner = text
                }
            }
            onDispose { client.disconnect() }
        }
    }

    val tl = timeline
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Header: takaisin + linjabadge + määränpää + suunnanvaihto.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.mobile_ic_arrow_back),
                    contentDescription = "Takaisin",
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .height(32.dp)
                    .widthIn(min = 42.dp)
                    .background(transitModeColor(detail.mode), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    detail.shortName.ifEmpty { "?" },
                    color = transitOnModeColor(detail.mode),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            val dest = when (detail) {
                is TransitDetail.Trip -> detail.headsign
                is TransitDetail.Route ->
                    routePatterns?.patterns?.getOrNull(patternIdx)?.directionLabel()
                        ?: detail.preferredHeadsign
            }
            Text(
                dest,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val rp = routePatterns
            if (detail is TransitDetail.Route && rp != null && rp.patterns.size > 1) {
                TextButton(onClick = {
                    patternIdx = (patternIdx + 1) % rp.patterns.size
                    timeline = null
                    banner = "Haetaan aikataulua…"
                }) {
                    Text("Valitse suunta", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        // Banneri ("Bussi on N pysäkin päässä…" / live-MQTT-teksti).
        Text(
            banner,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(14.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 21.sp,
        )
        // Linjan häiriötiedotteet (vain linjanäkymässä; vakavin ensin).
        val routeAlerts = routePatterns?.alerts
        if (detail is TransitDetail.Route && !routeAlerts.isNullOrEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                routeAlerts.sortedByDescending { it.severityRank() }.forEach { a ->
                    TransitAlertRow(a) { detailAlert = a }
                }
            }
        }
        // Aikajana.
        val vehicles = remember(tl) { tl?.vehicleStopIndices?.toSet() ?: emptySet() }
        val passedBefore = if (tl != null && detail is TransitDetail.Trip && tl.vehicleStopIndices.size == 1) {
            tl.vehicleStopIndices[0]
        } else {
            -1
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 20.dp),
        ) {
            val stops = tl?.stops ?: emptyList()
            // Pysyvä avain pysäkin gtfsId:stä; i mukana koska reitti voi käydä samalla pysäkillä
            // kahdesti (rengaslinja) → silti yksilöllinen.
            items(stops.size, key = { i -> "${stops[i].gtfsId}:$i" }) { i ->
                TimelineStopRow(
                    s = stops[i],
                    isVehicle = vehicles.contains(i),
                    isBoard = i == (tl?.boardStopIndex ?: -1),
                    passed = passedBefore >= 0 && i < passedBefore,
                    approaching = tl?.vehicleIncoming == true && detail is TransitDetail.Trip,
                    mode = detail.mode,
                )
            }
        }
    }

    detailAlert?.let { a ->
        TransitAlertDialog(a) { detailAlert = null }
    }
}

private fun preferredPatternIndexOf(patterns: RoutePatterns, patternCode: String, headsign: String): Int {
    if (patterns.patterns.isEmpty()) return 0
    if (patternCode.isNotEmpty()) {
        for (i in patterns.patterns.indices) {
            if (patternCode == patterns.patterns[i].code) return i
        }
    }
    if (headsign.isNotEmpty()) {
        for (i in patterns.patterns.indices) {
            if (headsign.equals(patterns.patterns[i].headsign, ignoreCase = true)) return i
        }
    }
    return 0
}

@Composable
private fun TimelineStopRow(
    s: TimelineStop,
    isVehicle: Boolean,
    isBoard: Boolean,
    passed: Boolean,
    approaching: Boolean,
    mode: String,
) {
    val dotColor = when {
        isVehicle -> transitModeColor(mode)
        isBoard -> colorResource(R.color.mobile_accent)
        passed -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    var label = s.name ?: ""
    if (isVehicle) label += "  • " + timelineModeWord(mode) + (if (approaching) " tulossa" else " tässä")
    else if (isBoard) label += "  • oma pysäkki"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(dotColor, CircleShape),
        )
        Text(
            label,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
            fontSize = 15.sp,
            fontWeight = if (isVehicle || isBoard) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isVehicle || isBoard -> MaterialTheme.colorScheme.onSurface
                passed -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (s.depEpochSec > 0) TR_CLOCK.format(Date(s.depEpochSec * 1000L)) else "",
            modifier = Modifier.padding(start = 8.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ===================== Häiriöt ja muutokset (kaikki HSL-häiriöt) =====================

private val TR_DATETIME = SimpleDateFormat("d.M. 'klo' HH:mm", FI_TR)

/** Listan severity-ikonin väri (teema-adaptoituva AAA-tekstiväri neutraalilla kortilla). */
private fun alertIconColorRes(rank: Int): Int = when {
    rank >= 3 -> R.color.mobile_warning_red
    rank == 2 -> R.color.mobile_warning_orange
    else -> R.color.mobile_warning_yellow
}

private fun effectiveRangeText(a: TransitAlert): String {
    val s = if (a.startEpochSec > 0) TR_DATETIME.format(Date(a.startEpochSec * 1000L)) else ""
    val e = if (a.endEpochSec > 0) TR_DATETIME.format(Date(a.endEpochSec * 1000L)) else ""
    return when {
        s.isNotEmpty() && e.isNotEmpty() -> "$s – $e"
        s.isNotEmpty() -> "alkaen $s"
        e.isNotEmpty() -> "$e asti"
        else -> ""
    }
}

/** Häiriölistan suodatus (puhdas, yksikkötestattava): moodi (valittu moodi vaatii täsmäyksen;
 *  yleiset mode=="" näkyvät vain "Kaikki"-näkymässä), voimassaolo nyt, ja tekstihaku
 *  linja/pysäkki/otsikko/kuvaus. */
internal fun filterDisruptions(
    list: List<TransitAlert>,
    mode: String?,
    activeOnly: Boolean,
    query: String,
    nowSec: Long,
): List<TransitAlert> {
    val q = query.trim().lowercase(FI_TR)
    return list.filter { a ->
        val modeOk = mode == null || a.mode.equals(mode, ignoreCase = true)
        val activeOk = !activeOnly || a.isActiveAt(nowSec)
        // Linjanumero osuu tarkasti tai alkuosalla (myös yksikirjaimiset junalinjat A/P/T) ja ≥2
        // merkillä myös osumana. Vapaa tekstihaku (pysäkki/otsikko/kuvaus) vasta ≥2 merkillä, ettei
        // yksi kirjain tulvi proosasta (suomenkielinen teksti sisältää lähes aina kysytyn kirjaimen).
        val rsn = a.routeShortName.lowercase(FI_TR)
        val routeOk = q.isNotEmpty() && (rsn == q || rsn.startsWith(q) || (q.length >= 2 && rsn.contains(q)))
        val textOk = q.isEmpty() || routeOk || (
            q.length >= 2 && (
                a.stopName.lowercase(FI_TR).contains(q) ||
                    a.header.lowercase(FI_TR).contains(q) ||
                    a.description.lowercase(FI_TR).contains(q)
                )
            )
        modeOk && activeOk && textOk
    }
}

/** Kertaluonteinen navigointivihje: HSL-suosikkihäiriö-ilmoituksen napautus → avaa Häiriöt-sivu
 *  suoraan "Vain suosikit" -suodatettuna. [MobileComposeMainActivity] asettaa intentistä, näkymä kuluttaa. */
internal object DisruptionNav {
    @Volatile private var focusFavorites = false
    fun requestFocusFavorites() { focusFavorites = true }
    fun consumeFocusFavorites(): Boolean {
        val v = focusFavorites
        focusFavorites = false
        return v
    }
}

@Composable
internal fun HslDisruptionsScreen() {
    val refreshTick = LocalRefreshTick.current
    var all by remember { mutableStateOf<List<TransitAlert>?>(null) }
    var status by remember { mutableStateOf("Haetaan häiriöitä…") }
    var query by remember { mutableStateOf("") }
    var modeFilter by remember { mutableStateOf<String?>(null) }
    var activeOnly by remember { mutableStateOf(true) }
    var dialogFor by remember { mutableStateOf<TransitAlert?>(null) }
    val context = LocalContext.current
    val favLines = remember { TransitFavorites.getLines(context).map { it.shortName }.filter { it.isNotEmpty() }.toSet() }
    val favStops = remember { TransitFavorites.getStops(context).map { it.name }.filter { it.isNotEmpty() }.toSet() }
    val hasFavorites = favLines.isNotEmpty() || favStops.isNotEmpty()
    // Ilmoituksesta avattaessa (HSL-suosikkihäiriö): näytä heti vain suosikkien häiriöt.
    var favoritesOnly by remember { mutableStateOf(DisruptionNav.consumeFocusFavorites() && hasFavorites) }

    LaunchedEffect(refreshTick) {
        if (all == null) status = "Haetaan häiriöitä…"
        val result = withContext(Dispatchers.IO) {
            try { DigitransitApi.serviceAlerts() } catch (e: Exception) { null }
        }
        if (result == null) {
            if (all == null) status = "Häiriöiden haku epäonnistui. Kokeile Päivitä-nappia."
        } else {
            all = result
        }
    }

    val shown = remember(all, query, modeFilter, activeOnly, favoritesOnly) {
        all?.let { list0 ->
            val base = filterDisruptions(list0, modeFilter, activeOnly, query, System.currentTimeMillis() / 1000L)
            if (favoritesOnly) {
                base.filter { a ->
                    (a.routeShortName.isNotEmpty() && favLines.contains(a.routeShortName)) ||
                        (a.stopName.isNotEmpty() && favStops.contains(a.stopName))
                }
            } else {
                base
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Häiriöt ja muutokset",
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        SearchTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Hae linja tai pysäkki",
            modifier = Modifier.padding(horizontal = 14.dp),
            onClear = { query = "" },
        )
        Row(
            modifier = Modifier
                .padding(start = 14.dp, end = 14.dp, top = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = modeFilter == null,
                onClick = { modeFilter = null },
                label = { Text("Kaikki") },
            )
            listOf(
                "BUS" to "Bussit", "TRAM" to "Ratikat", "RAIL" to "Junat",
                "SUBWAY" to "Metro", "FERRY" to "Lautat",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = modeFilter == mode,
                    onClick = { modeFilter = if (modeFilter == mode) null else mode },
                    label = { Text(label) },
                )
            }
        }
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = activeOnly,
                onClick = { activeOnly = !activeOnly },
                label = { Text("Vain voimassa olevat") },
            )
            if (hasFavorites) {
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = !favoritesOnly },
                    label = { Text("Vain suosikit") },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            shown?.let {
                Text(
                    "${it.size} häiriötä",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            val list = shown
            if (list != null && list.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 20.dp),
                ) {
                    // TransitAlertilla ei ole omaa id:tä → koostetaan pysyvä avain sisällöstä
                    // (otsikko + alkuaika + linja) + i duplikaattisuojaksi.
                    items(
                        list.size,
                        key = { i -> "${list[i].header}:${list[i].startEpochSec}:${list[i].routeShortName}:$i" },
                    ) { i -> DisruptionRow(list[i]) { dialogFor = it } }
                }
            } else {
                Text(
                    if (list == null) status else "Ei häiriöitä valituilla suodattimilla.",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    dialogFor?.let { a ->
        TransitAlertDialog(a) { dialogFor = null }
    }
}

@Composable
private fun DisruptionRow(a: TransitAlert, onClick: (TransitAlert) -> Unit) {
    ArkiCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable { onClick(a) }
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painterResource(R.drawable.mobile_ic_info_24),
                contentDescription = null,
                tint = colorResource(alertIconColorRes(a.severityRank())),
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                if (a.routeShortName.isNotEmpty()) {
                    TransitLineBadge(a.routeShortName, a.mode)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    a.displayText(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 19.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOf(effectiveRangeText(a), a.stopName)
                    .filter { it.isNotEmpty() }
                    .joinToString("  ·  ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        modifier = Modifier.padding(top = 3.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
