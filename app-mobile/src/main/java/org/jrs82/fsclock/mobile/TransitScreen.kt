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
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
private const val KEY_TRANSIT_REGION = "transit_region"
private const val KEY_TRANSIT_REGION_EXPLICIT = "transit_region_explicit"
private const val LOC_FALLBACK_MAX_AGE_MS = 2L * 60_000L
private const val LOC_FALLBACK_MAX_ACCURACY_M = 250f
private const val MAX_PER_STOP = 5
private const val MAX_NEARBY_STOPS = 10
private const val MAX_PER_FAV_STOP = 5
private const val MAX_PER_SELECTED_STOP = 8

// Kellonaika HH:mm kaikilla kielillä → kieliriippumaton Locale.
private val TR_CLOCK = SimpleDateFormat("HH:mm", Locale.ROOT)

// ===================== Listan rivimalli (vastaa TransitFragment.buildItems-tyyppejä) =====================

private sealed class TransitRow(val key: String) {
    // stop != null pysäkkiotsikoilla (avaa koko päivän aikataulun); null ryhmäotsikoilla.
    class HeaderRow(val title: String, val mode: String, key: String, val zone: String,
                    val stop: NearbyStop? = null) : TransitRow("h|$key")
    class DepRow(val d: Departure, key: String) : TransitRow("d|$key")
    class AlertRow(val alert: TransitAlert, key: String) : TransitRow("a|$key")
    class RouteRow(val r: RouteHit) : TransitRow("r|${r.gtfsId}")
    class PlaceRow(val p: PlaceHit) : TransitRow("p|${p.gtfsId}|${p.name}")
    // Suosikkipysäkki kompaktina rivinä (kuten RouteRow); napautus avaa koko päivän näkymän.
    class FavStopRow(val stop: NearbyStop) : TransitRow("fs|${stop.gtfsId}")
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

    // Joukkoliikennealue (HSL / Tampere). Ohjaa lähilähtöjä, hakua ja reittihakua. Valinta muistetaan;
    // ilman valintaa ensioletus määräytyy sijainnista (maybeAutoRegion). Avatut kohteet (suosikit/
    // pysäkit/linjat/vuorot) haetaan gtfsId:n perusteella oikealta reitittimeltä (regionForGtfsId).
    var region by mutableStateOf(
        TransitRegion.fromKey(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
                .getString(KEY_TRANSIT_REGION, null),
        ),
    )
        private set
    private var regionExplicit =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
            .getBoolean(KEY_TRANSIT_REGION_EXPLICIT, false)

    /** Käyttäjän manuaalinen aluevalinta — muistetaan, ei GPS-overridea; nollaa alueriippuvaisen tilan. */
    fun updateRegion(r: TransitRegion) {
        if (region == r && regionExplicit) return
        region = r
        regionExplicit = true
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putString(KEY_TRANSIT_REGION, r.name)
            .putBoolean(KEY_TRANSIT_REGION_EXPLICIT, true)
            .apply()
        lastStops = emptyList(); favStops = emptyList()
        searchRoutes = null; searchPlaces = null; selectedStop = null; query = ""
        refresh()
    }

    /** GPS-pohjainen ensioletus (vain jos käyttäjä ei ole valinnut aluetta). Kirjoittaa prefsin, jotta
     *  irralliset detalji-/häiriönäkymät näkevät alueen, muttei explicit-lippua. */
    private fun maybeAutoRegion(lat: Double, lon: Double) {
        if (regionExplicit) return
        val auto = TransitRegion.forLocation(lat, lon)
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putString(KEY_TRANSIT_REGION, auto.name).apply()
        if (auto != region) {
            region = auto
            lastStops = emptyList(); favStops = emptyList()
        }
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
            transientStatus = appContext.getString(R.string.transit_status_allow_location)
            requestPermission()
            return
        }
        if (inFlight) return
        inFlight = true
        if (lastStops.isEmpty() && favStops.isEmpty()) {
            transientStatus = appContext.getString(R.string.transit_status_fetching_nearby)
        }
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
            onFetchFail(appContext.getString(R.string.transit_status_no_location_permission))
        } catch (e: Exception) {
            onFetchFail(appContext.getString(R.string.transit_status_location_read_failed))
        }
    }

    private fun fetchFallbackLocation(client: FusedLocationProviderClient) {
        if (disposed) { inFlight = false; return }
        val errorMessage = appContext.getString(R.string.transit_status_location_unavailable)
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
            onFetchFail(appContext.getString(R.string.transit_status_no_location_permission))
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
        maybeAutoRegion(lat, lon)
        val r = region
        io.execute {
            try {
                val stops = TransitRepository.get().fetch(lat, lon, r)
                val fav = ArrayList<NearbyStop>()
                for (fs in TransitFavorites.getStops(appContext)) {
                    try {
                        // Suosikki voi olla pysäkki TAI asema (metro/juna) → asema-fallback.
                        // Alue suosikin gtfsId:stä (HSL- ja Tampere-suosikit voivat olla samassa listassa).
                        val fr = regionForGtfsId(fs.gtfsId)
                        (DigitransitApi.stopDepartures(fs.gtfsId, fr)
                            ?: DigitransitApi.stationDepartures(fs.gtfsId, fr))?.let { fav.add(it) }
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
                ui.post { onFetchFail(appContext.getString(R.string.transit_status_departures_failed)) }
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
            val r = region
            val routes = try { DigitransitApi.searchRoutes(q, r) } catch (e: Exception) { ArrayList() }
            // Yksi merkki (esim. junalinjat A/P/I/K/T/E/L/U) → vain linjahaku; paikkahaku (geokoodaus)
            // vaatii ≥2 merkkiä ettei se tuota roskatuloksia.
            val places = if (q.length >= 2) {
                try { DigitransitApi.searchPlaces(q, lastLat, lastLon, r) } catch (e: Exception) { ArrayList() }
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

    /** Haun pysäkki-/asematuloksen avaus: suoraan pysäkin koko päivän näkymään (sis. suosikkitähti).
     *  Overlay hakee lähdöt itse (stop/station-fallback). */
    fun openPlace(p: PlaceHit) {
        if (p.gtfsId.isNullOrEmpty()) {
            Toast.makeText(appContext, R.string.transit_toast_no_departure_info, Toast.LENGTH_SHORT).show()
            return
        }
        fullDayStop = NearbyStop(
            p.gtfsId, p.name ?: "", p.code ?: "", "",
            p.modes?.firstOrNull() ?: "", Double.NaN,
            emptyList(), emptyList(),
        )
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
            items.add(TransitRow.HeaderRow(stopHeaderText(ctx, sel), headerModeOf(sel), sectionKey, zoneLabelOf(sel), sel))
            addAlertRows(items, sel, sectionKey)
            addDepRows(items, deps, minOf(deps.size, MAX_PER_SELECTED_STOP), sectionKey)
            return items
        }

        if (query.trim().isNotEmpty()) {
            val routes = searchRoutes
            if (!routes.isNullOrEmpty()) {
                items.add(TransitRow.HeaderRow(ctx.getString(R.string.transit_header_lines), "FAV", "search-routes", ""))
                routes.forEach { items.add(TransitRow.RouteRow(it)) }
            }
            val places = searchPlaces
            if (!places.isNullOrEmpty()) {
                items.add(
                    TransitRow.HeaderRow(ctx.getString(R.string.transit_header_stops_stations), "BUS", "search-places", ""),
                )
                places.forEach { items.add(TransitRow.PlaceRow(it)) }
            }
            return items
        }

        // Suosikit (linjat + pysäkit) yhtenäisesti ylimpänä. Suosikkipysäkit näytetään KOMPAKTEINA
        // riveinä (kuten suosikkilinjat) — ei isoina lähtölistoina, jotka veisivät koko näkymän ja
        // työntäisivät suosikkilinjat alas. Suodatetaan haetut favStops nykyisten suosikkien mukaan,
        // jotta poisto näkyy heti (favStops päivittyy vasta seuraavassa haussa).
        val favStopIdsNow = TransitFavorites.getStops(ctx).map { it.gtfsId }.toHashSet()
        val shownFavStops = favStops.filter { it.gtfsId in favStopIdsNow }
        val favLines = TransitFavorites.getLines(ctx)
        if (favLines.isNotEmpty() || shownFavStops.isNotEmpty()) {
            items.add(TransitRow.HeaderRow(ctx.getString(R.string.transit_header_favourites), "FAV", "favorites", ""))
            favLines.forEach { items.add(TransitRow.RouteRow(it)) }
            shownFavStops.forEach { items.add(TransitRow.FavStopRow(it)) }
        }

        // Lähimmät pysäkit — suosikkipysäkit jätetään pois, ettei sama pysäkki näy kahdesti.
        var stopsShown = 0
        for (stop in lastStops) {
            if (stop.gtfsId in favStopIdsNow) continue
            val deps = stop.departures.filter { depMatches(it) }.sortedBy { it.departureEpochSec }
            if (deps.isEmpty()) continue
            if (stopsShown >= MAX_NEARBY_STOPS) break
            val sectionKey = "nearby-stop|${stop.gtfsId}"
            items.add(TransitRow.HeaderRow(stopHeaderText(ctx, stop), headerModeOf(stop), sectionKey, zoneLabelOf(stop), stop))
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

/** Alue gtfsId-prefiksistä: "tampere:..." → TAMPERE, muuten HSL. Avatut kohteet (suosikit, pysäkit,
 *  linjat, vuorot) haetaan oikealta reitittimeltä riippumatta valitusta alueesta. */
private fun regionForGtfsId(id: String?): TransitRegion = TransitRegion.forGtfsId(id)

/** Valittu alue SharedPreferenceista — irrallisille detalji-/häiriönäkymille, joilla ei ole TransitStatea. */
private fun currentRegion(ctx: Context): TransitRegion =
    TransitRegion.fromKey(
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(KEY_TRANSIT_REGION, null),
    )

private fun headerModeOf(s: NearbyStop): String {
    if (!s.vehicleMode.isNullOrEmpty()) return s.vehicleMode
    for (d in s.departures) {
        if (!d.mode.isNullOrEmpty()) return d.mode
    }
    return "BUS"
}

private fun stopHeaderText(ctx: Context, s: NearbyStop): String {
    val name = if (s.name.isNullOrEmpty()) ctx.getString(R.string.transit_stop) else s.name
    val code = if (s.code.isNullOrEmpty()) "" else " " + s.code
    if (s.distanceMeters.isNaN() || s.distanceMeters < 0) return name + code
    val m = Math.round(s.distanceMeters)
    return if (m < 1000) "$name$code · $m m"
    else "$name$code · " + String.format(AppLocale.current(ctx), "%.1f km", m / 1000.0)
}

private fun zoneLabelOf(s: NearbyStop?): String {
    var z = s?.zoneId?.trim() ?: return ""
    if (z.isEmpty()) return ""
    val colon = z.lastIndexOf(':')
    if (colon >= 0 && colon + 1 < z.length) z = z.substring(colon + 1)
    return z.uppercase(Locale.ROOT)
}

private fun depTimeText(ctx: Context, d: Departure): String {
    val nowSec = System.currentTimeMillis() / 1000L
    val diffSec = d.departureEpochSec - nowSec
    if (diffSec <= 30) return ctx.getString(R.string.transit_dep_now)
    val minutes = (diffSec / 60L).toInt()
    return if (minutes < 60) {
        ctx.resources.getQuantityString(R.plurals.transit_minutes, minutes, minutes)
    } else {
        TR_CLOCK.format(Date(d.departureEpochSec * 1000L))
    }
}

/** Kulkuväline lauseen subjektina ("Bussi" / "Bussen" / "The bus"); tuntematon → bussi kuten ennen. */
@StringRes
internal fun transitVehicleRes(mode: String?): Int = when (mode) {
    "TRAM" -> R.string.transit_vehicle_tram
    "RAIL" -> R.string.transit_vehicle_train
    "SUBWAY" -> R.string.transit_vehicle_metro
    "FERRY" -> R.string.transit_vehicle_ferry
    else -> R.string.transit_vehicle_bus
}

/** Kulkuvälineen nimi (Bussi / Buss / Bus), esim. ikonin saavutettavuuskuvaus. */
@StringRes
private fun transitModeNameRes(mode: String?): Int = when (mode) {
    "TRAM" -> R.string.transit_mode_tram
    "RAIL" -> R.string.transit_mode_train
    "SUBWAY" -> R.string.transit_mode_metro
    "FERRY" -> R.string.transit_mode_ferry
    else -> R.string.transit_mode_bus
}

@StringRes
private fun timelineVehicleRes(mode: String?, approaching: Boolean): Int = when (mode) {
    "TRAM" -> if (approaching) R.string.transit_timeline_tram_approaching else R.string.transit_timeline_tram_here
    "RAIL" -> if (approaching) R.string.transit_timeline_train_approaching else R.string.transit_timeline_train_here
    "SUBWAY" -> if (approaching) R.string.transit_timeline_metro_approaching else R.string.transit_timeline_metro_here
    "FERRY" -> if (approaching) R.string.transit_timeline_ferry_approaching else R.string.transit_timeline_ferry_here
    else -> if (approaching) R.string.transit_timeline_bus_approaching else R.string.transit_timeline_bus_here
}

private fun liveClockText(epochSec: Long): String = TR_CLOCK.format(Date(epochSec * 1000L))

private fun formatMeters(locale: Locale, m: Double): String {
    if (m >= 1000) return String.format(locale, "%.1f km", m / 1000.0)
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

/** Aluevalitsin (HSL / Tampere): segmentoidut sirut. */
@Composable
private fun RegionSelectorRow(selected: TransitRegion, onSelect: (TransitRegion) -> Unit) {
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TransitRegion.values().forEach { r ->
            FilterChip(
                selected = r == selected,
                onClick = { onSelect(r) },
                label = { Text(stringResource(r.labelRes)) },
            )
        }
    }
}

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
        else state.transientStatus = context.getString(R.string.transit_status_location_needed)
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
        state.selectedStop != null -> stringResource(R.string.transit_empty_stop)
        trimmedQuery.isNotEmpty() -> when {
            state.searchRoutes == null && state.searchPlaces == null ->
                stringResource(R.string.transit_searching, trimmedQuery)
            else -> stringResource(R.string.transit_no_results, trimmedQuery)
        }
        state.modeFilter != null && (state.lastStops.isNotEmpty() || state.favStops.isNotEmpty()) ->
            stringResource(R.string.transit_empty_mode)
        else -> null
    }
    val statusText = state.transientStatus ?: emptyStatus

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.transit_title_nearby),
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            RegionSelectorRow(selected = state.region) { state.updateRegion(it) }
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
                placeholder = stringResource(R.string.transit_search_hint),
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
                    ModeFilterChip(
                        selected = state.modeFilter == null,
                        label = stringResource(R.string.transit_filter_all),
                        mode = null,
                    ) { state.updateModeFilter(null) }
                    MODE_FILTERS.forEach { (mode, labelRes) ->
                        ModeFilterChip(
                            selected = state.modeFilter == mode,
                            label = stringResource(labelRes),
                            mode = mode,
                        ) { state.updateModeFilter(if (state.modeFilter == mode) null else mode) }
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
                                stringResource(R.string.transit_reminder_tip),
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
                            is TransitRow.FavStopRow -> TransitFavStopRow(row.stop, state)
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
                // Suosikin lisäys/poisto: virkistä lista, jotta uusi suosikkipysäkki ilmestyy heti.
                onFavoritesChanged = { state.favVersion++; state.refresh() },
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
    val context = LocalContext.current
    val isFavStop = stop != null && TransitFavorites.isStopFav(context, stop.gtfsId)
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
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.title,
                modifier = Modifier.weight(1f, fill = false),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Suosikkipysäkin keltainen tähti (sama kuin reittien tähdet; drawablen oma väri).
            if (isFavStop) {
                Icon(
                    painterResource(R.drawable.mobile_ic_star),
                    contentDescription = stringResource(R.string.transit_cd_favourite),
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp),
                )
            }
        }
        if (stop != null) {
            Text(
                stringResource(R.string.transit_full_day_link),
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
                    depTimeText(context, d),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(
                        if (d.realtime) R.color.mobile_transit_tram else R.color.mobile_text_secondary,
                    ),
                )
                if (d.realtime && Math.abs(d.delaySeconds) >= 60) {
                    val min = Math.round(d.delaySeconds / 60.0f)
                    Text(
                        if (d.delaySeconds > 0) {
                            pluralStringResource(R.plurals.transit_delay_late, min, min)
                        } else {
                            pluralStringResource(R.plurals.transit_delay_early, -min, -min)
                        },
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
                        if (now) R.string.transit_toast_line_added else R.string.transit_toast_line_removed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    state.favVersion++
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painterResource(if (fav) R.drawable.mobile_ic_star else R.drawable.mobile_ic_star_outline),
                    contentDescription = stringResource(R.string.transit_cd_favourite),
                    tint = Color.Unspecified,
                )
            }
        }
    }
}

/** Pieni tyyppimerkki ("LINJA" / "PYSÄKKI" / "ASEMA") ikonilla — kertoo heti suosikkirivin tyypin. */
@Composable
private fun TransitTypeEyebrow(iconRes: Int, label: String) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp),
        )
        Text(
            label.uppercase(AppLocale.current(LocalContext.current)),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.primary,
        )
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                TransitTypeEyebrow(transitModeIconRes(r.mode), stringResource(R.string.transit_line))
                // Vanhat suosikit tallensivat pitkäksi nimeksi generoidun "Linja NNN" -tekstin → näytetään
                // kielen mukainen nimi; sama kun pitkä nimi puuttuu.
                val legacyLabel = remember { Regex("^Linja \\S+$") }
                val routeText = r.longName?.takeUnless { it.isBlank() || legacyLabel.matches(it) }
                    ?: stringResource(R.string.transit_line_number, r.shortName ?: "")
                if (routeText.isNotBlank()) {
                    Text(
                        routeText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            IconButton(
                onClick = {
                    TransitFavorites.toggleLineFav(context, r.gtfsId, r.shortName, r.longName, r.mode)
                    state.favVersion++
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painterResource(if (fav) R.drawable.mobile_ic_star else R.drawable.mobile_ic_star_outline),
                    contentDescription = stringResource(R.string.transit_cd_favourite),
                    tint = Color.Unspecified,
                )
            }
        }
    }
}

@Composable
private fun TransitFavStopRow(stop: NearbyStop, state: TransitState) {
    val context = LocalContext.current
    val mode = stop.vehicleMode ?: ""
    // Best-effort: metro-/juna-pysäkki = asema (oma moodi-ikoni), muut = pysäkki (pylväsikoni).
    val isStation = mode == "SUBWAY" || mode == "RAIL"
    val typeIcon = if (isStation) transitModeIconRes(mode) else R.drawable.mobile_ic_signpost_24
    val typeLabel = stringResource(if (isStation) R.string.transit_station else R.string.transit_stop)
    val name = if (stop.name.isNullOrEmpty()) stringResource(R.string.transit_stop) else stop.name
    val code = stop.code ?: ""
    ArkiCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable { state.fullDayStop = stop }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tyyppi-ikoni (pylväs/asema) vaalealla pohjalla — erottuu linjan numerolapusta.
            Box(
                modifier = Modifier
                    .height(46.dp)
                    .widthIn(min = 52.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(typeIcon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                TransitTypeEyebrow(typeIcon, typeLabel)
                Text(
                    name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                if (code.isNotBlank()) {
                    Text(
                        code,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            IconButton(
                onClick = {
                    TransitFavorites.toggleStopFav(context, stop.gtfsId, name)
                    Toast.makeText(context, R.string.transit_toast_removed_from_favourites, Toast.LENGTH_SHORT).show()
                    state.favVersion++
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painterResource(R.drawable.mobile_ic_star),
                    contentDescription = stringResource(R.string.transit_cd_remove_favourite),
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
                if (!p.locality.isNullOrEmpty() || !p.code.isNullOrEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (!p.locality.isNullOrEmpty()) {
                            Text(
                                p.locality,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        // Pysäkkikoodi pienessä reunustetussa laatikossa (kuten HSL).
                        if (!p.code.isNullOrEmpty()) {
                            Box(
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    p.code,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
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
                            contentDescription = stringResource(transitModeNameRes(m)),
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        text = {
            // Samat täpät/rivit kuin asetussivulla (SwitchRow/ClickableRow) → yhtenäinen ulkoasu.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow(
                    title = stringResource(R.string.transit_fav_line),
                    subtitle = stringResource(R.string.transit_line_number, d.routeShortName ?: ""),
                    leadingIconRes = R.drawable.mobile_ic_star_outline,
                    checked = lineFav,
                ) {
                    lineFav = TransitFavorites.toggleLineFav(context, d.routeGtfsId, d.routeShortName, "", d.mode)
                    state.favVersion++
                }
                SwitchRow(
                    title = stringResource(R.string.transit_fav_stop),
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
                        title = stringResource(R.string.transit_add_reminder),
                        subtitle = stringResource(R.string.transit_add_reminder_sub),
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
                    if (ok) {
                        context.resources.getQuantityString(
                            R.plurals.transit_reminder_set_toast, leadMin, leadMin,
                            TR_CLOCK.format(Date(remindAtSec * 1000L)),
                        )
                    } else {
                        context.getString(R.string.transit_reminder_too_close)
                    },
                    Toast.LENGTH_LONG,
                ).show()
                onDismiss()
            }) { Text(stringResource(R.string.transit_reminder_set)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        title = {
            val depClock = TR_CLOCK.format(Date(d.departureEpochSec * 1000L))
            Text(
                if (!d.routeShortName.isNullOrEmpty()) {
                    stringResource(R.string.transit_line_at, d.routeShortName, depClock)
                } else {
                    stringResource(R.string.transit_departure_at, depClock)
                },
            )
        },
        text = {
            Column {
                Text(
                    pluralStringResource(R.plurals.transit_reminder_lead, leadMin, leadMin),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.transit_reminder_at, TR_CLOCK.format(Date(remindAtSec * 1000L))),
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
                        stringResource(R.string.transit_reminder_from_stop, d.stopName),
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        title = {
            Text(
                if (a.header.isNotEmpty()) a.header else stringResource(R.string.transit_alert_title),
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

private val TR_HOUR = SimpleDateFormat("HH", Locale.ROOT)

/** Tunti ("08") jokaiselle (aikajärjestyksessä olevalle) lähdölle, tai null jos sama tunti kuin
 *  edellinen. UI muotoilee otsikon (transit_hour_header). Puhdas funktio yksikkötestiä varten. */
internal fun fullDayHourHeaders(epochSecs: List<Long>): List<String?> {
    val out = ArrayList<String?>(epochSecs.size)
    var last = ""
    for (e in epochSecs) {
        val h = TR_HOUR.format(Date(e * 1000L))
        if (h != last) {
            out.add(h)
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
    onFavoritesChanged: () -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var fav by remember(stop) { mutableStateOf(TransitFavorites.isStopFav(context, stop.gtfsId)) }
    var rows by remember(stop) { mutableStateOf<List<FullDayRow>?>(null) }
    var status by remember(stop) { mutableStateOf(context.getString(R.string.transit_full_day_loading)) }
    LaunchedEffect(stop) {
        val ns = withContext(Dispatchers.IO) {
            try {
                // stop(id) palauttaa nullin asema-id:lle → fallback station(id):iin.
                val r = regionForGtfsId(stop.gtfsId)
                DigitransitApi.stopDeparturesFullDay(stop.gtfsId, r)
                    ?: DigitransitApi.stationDeparturesFullDay(stop.gtfsId, r)
            } catch (e: Exception) {
                null
            }
        }
        if (ns == null || ns.departures.isEmpty()) {
            status = context.getString(R.string.transit_full_day_failed)
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
                Icon(
                    painterResource(R.drawable.mobile_ic_arrow_back),
                    contentDescription = stringResource(R.string.common_back),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            ) {
                Text(
                    if (stop.name.isNullOrEmpty()) stringResource(R.string.transit_stop) else stop.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.transit_full_day_title),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Suosikkitähti: lisää/poista pysäkki suosikeista (näkyy Lähilähdöissä ja widgetin valinnassa).
            IconButton(
                onClick = {
                    val label = if (stop.name.isNullOrEmpty()) context.getString(R.string.transit_stop) else stop.name
                    fav = TransitFavorites.toggleStopFav(context, stop.gtfsId, label)
                    Toast.makeText(
                        context,
                        if (fav) R.string.transit_toast_stop_added else R.string.transit_toast_stop_removed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    onFavoritesChanged()
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painterResource(if (fav) R.drawable.mobile_ic_star else R.drawable.mobile_ic_star_outline),
                    contentDescription = stringResource(
                        if (fav) R.string.transit_cd_remove_favourite else R.string.transit_cd_add_favourite,
                    ),
                    // Sama keltainen kuin reittien tähdissä (drawablen oma väri).
                    tint = Color.Unspecified,
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
                            stringResource(R.string.transit_hour_header, row.label),
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

/** Yhden VP-päivityksen rakenteellinen ETA: etäisyys nousupysäkkiin reittiä pitkin, ohitustila,
 *  nousupysäkin lähtöaika. ETA-kortti renderöi tästä; banneriteksti muotoillaan tästä [liveBannerText]:llä. */
private class LiveEta(
    val toBoardMeters: Double,   // etäisyys nousupysäkkiin (m); negatiivinen jos jo ohitettu
    val passed: Boolean,         // bussi ohittanut nousupysäkin (toBoard < -30)
    val boardEpochSec: Long,     // nousupysäkin lähtöaika (0 jos tuntematon)
    val gps: Boolean,            // sijainti aitoa GPS:ää (ei "arvio")
)

/** Live-MQTT-projektion tila (vastaa TransitFragmentin liveCum/liveStopDist/liveStopVertex/liveLastAlong). */
private class LiveTracking(val tl: TripTimeline) {
    val cum: DoubleArray = RouteProjection.cumulative(tl.shape)
    val stopVertex = IntArray(tl.stops.size)
    val stopDist: DoubleArray = RouteProjection.stopDistances(tl.shape, cum, tl.stops, stopVertex)
    var lastAlong = Double.NaN

    /** VP-päivitys → rakenteellinen ETA tai null jos päivitys hylätään (sama suodatus kuin ennen). */
    fun etaFor(lat: Double, lon: Double, loc: String?, tsi: Long): LiveEta? {
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
        return LiveEta(toBoard, toBoard < -30, tl.stops[board].depEpochSec,
            "GPS".equals(loc, ignoreCase = true))
    }

    /** VP-päivitys → banneriteksti (entinen muotoilu säilytetty) tai null jos hylätään. */
    fun bannerFor(ctx: Context, mode: String, lat: Double, lon: Double, loc: String?, tsi: Long): String? {
        val e = etaFor(lat, lon, loc, tsi) ?: return null
        return liveBannerText(ctx, mode, e)
    }
}

/** [LiveEta] → sama banneriteksti kuin ennen (kuvausrivi varatilana + vanha tekstipolku). */
private fun liveBannerText(ctx: Context, mode: String, e: LiveEta): String {
    val word = ctx.getString(transitVehicleRes(mode))
    val src = if (e.gps) "" else " " + ctx.getString(R.string.transit_live_estimate)
    if (e.passed) return ctx.getString(R.string.transit_live_passed, word, src)
    var eta = ""
    if (e.boardEpochSec > 0) {
        val secs = e.boardEpochSec - System.currentTimeMillis() / 1000L
        val clock = liveClockText(e.boardEpochSec)
        eta = " " + if (secs in 0 until 3600) {
            val min = maxOf(1, Math.round(secs / 60.0).toInt())
            ctx.resources.getQuantityString(R.plurals.transit_live_eta_min, min, clock, min)
        } else {
            ctx.getString(R.string.transit_live_eta, clock)
        }
    }
    val distance = formatMeters(AppLocale.current(ctx), maxOf(0.0, e.toBoardMeters))
    return ctx.getString(R.string.transit_live_distance, word, distance, eta, src)
}

internal fun tripBannerText(tl: TripTimeline, mode: String, region: TransitRegion): TransitText {
    val word = TransitText.Res(transitVehicleRes(mode))
    if (tl.vehicleStopIndices.isEmpty()) {
        // HSL: GraphQL on auktoriteetti → tyhjä = vuoro ei liikkeellä. Tampere: live tulee MQTT:stä
        // (tämä GraphQL-pohjainen teksti on vain varatila kunnes MQTT-overlay täyttää bannerin) →
        // neutraali "haetaan", ei valhetta. Muut alueet: ei live-sijaintia lainkaan.
        return TransitText.Res(
            when {
                region.liveVehiclePositions -> R.string.transit_banner_trip_not_started
                region.liveViaMqtt -> R.string.transit_banner_fetching_live
                else -> R.string.transit_banner_trip_no_live_region
            },
        )
    }
    val relIdx = tl.vehicleStopIndices[0]
    val approaching = tl.vehicleIncoming
    val at = if (relIdx >= 0 && relIdx < tl.stops.size) tl.stops[relIdx].name ?: "" else ""
    // Lähestyttäessä bussi on vielä edellisellä välillä → efektiivinen "viimeksi ohitettu" = relIdx-1.
    val effIdx = if (approaching) relIdx - 1 else relIdx
    if (tl.boardStopIndex >= 0) {
        val n = tl.boardStopIndex - effIdx
        if (n > 0) {
            val pos = TransitText.Res(
                if (approaching) R.string.transit_pos_en_route else R.string.transit_pos_at,
                listOf(at),
            )
            return TransitText.Plural(R.plurals.transit_banner_stops_away, n, listOf(word, n, pos))
        }
        if (n == 0) return TransitText.Res(R.string.transit_banner_at_your_stop, listOf(word, at))
        return TransitText.Res(R.string.transit_banner_passed_now, listOf(word, at))
    }
    return TransitText.Res(
        if (approaching) R.string.transit_banner_en_route_to else R.string.transit_banner_at_stop,
        listOf(word, at),
    )
}

internal fun routeBannerText(tl: TripTimeline, region: TransitRegion): TransitText {
    val c = tl.vehicleStopIndices.size
    if (c == 0) {
        // HSL: GraphQL auktoriteetti. Tampere: live tulee MQTT:stä (tämä on varatila kunnes MQTT-overlay
        // päivittää bannerin live-vuorojen määrällä) → neutraali "haetaan". Muut: ei livea.
        return TransitText.Res(
            when {
                region.liveVehiclePositions -> R.string.transit_banner_route_none
                region.liveViaMqtt -> R.string.transit_banner_route_fetching
                else -> R.string.transit_banner_route_no_live_region
            },
        )
    }
    return routeRunningText(c)
}

/** "N vuoroa liikkeellä — …" (GraphQL-vehiclePositions ja Tampereen MQTT-laskuri). */
internal fun routeRunningText(count: Int): TransitText =
    TransitText.Plural(R.plurals.transit_banner_route_running, count, listOf(count))

/** Vuoronäkymän yläosa (UI-spec-A, löyhästi sovellettu): app bar (linja + lähtö→määränpää, EI
 *  takaisin-nuolta — järjestelmän paluuele riittää) + ETA-kortti. Iso saapumisaika nousupysäkin
 *  lähtöajasta; sykkivä live-piste + etäisyys kun GPS-dataa on; degradoituu (kortti aina). Kaikki
 *  nykyiset bannerin tilat säilyvät kortin kuvausrivinä. Adaptoituu vaaleaan/tummaan teemaan. */
@Composable
private fun TripEtaHeader(
    detail: TransitDetail.Trip,
    tl: TripTimeline?,
    liveEta: LiveEta?,
    bannerText: String,
) {
    val dark = isSystemInDarkTheme()
    val accent = if (dark) Color(0xFF2F7EF0) else Color(0xFF1565C0)
    val cardBrush = if (dark) {
        Brush.linearGradient(listOf(Color(0xFF15233B), Color(0xFF101722)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFE7F0FE), Color(0xFFF6F9FF)))
    }
    val cardBorder = accent.copy(alpha = 0.35f)
    val labelColor = if (dark) Color(0xFF7FB0FF) else accent
    val bigColor = if (dark) Color.White else Color(0xFF0B1F3A)
    val midColor = if (dark) Color(0xFFCDD2DA) else Color(0xFF42474E)
    val dimColor = if (dark) Color(0xFF9AA0A8) else Color(0xFF6B7079)

    val boardStop = tl?.stops?.getOrNull(tl.boardStopIndex)
    val boardEpoch = boardStop?.depEpochSec ?: 0L
    val minutes = if (boardEpoch > 0) {
        ((boardEpoch - System.currentTimeMillis() / 1000L) / 60L).toInt()
    } else {
        null
    }
    val klo = if (boardEpoch > 0) liveClockText(boardEpoch) else null
    // Headsign on Tampereella muotoa "Lähtö → Määränpää", HSL:llä pelkkä määränpää. Jaetaan
    // himmeäksi lähtöriviksi + korostetuksi määränpääksi; HSL:llä lähtö = reitin ensimmäinen pysäkki.
    val arrowIdx = detail.headsign.indexOf('→')
    val originText = if (arrowIdx >= 0) {
        detail.headsign.substring(0, arrowIdx).trim()
    } else {
        (tl?.stops?.firstOrNull()?.name) ?: ""
    }
    val destText = if (arrowIdx >= 0) detail.headsign.substring(arrowIdx + 1).trim() else detail.headsign
    val boardName = (boardStop?.name) ?: ""
    val passed = liveEta?.passed == true
    val live = liveEta != null && !liveEta.passed
    val locale = AppLocale.current(LocalContext.current)
    val distanceText = if (live && liveEta != null) formatMeters(locale, maxOf(0.0, liveEta.toBoardMeters)) else null
    // Kuvausrivi (kaikki bannerin tilat säilyvät) näytetään kun ei "siistissä" live-tilassa.
    val showDescriptive = liveEta == null || passed

    Column(modifier = Modifier.fillMaxWidth()) {
        // App bar (ei takaisin-nuolta).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .defaultMinSize(minWidth = 42.dp)
                    .background(transitModeColor(detail.mode), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    detail.shortName.ifEmpty { "?" },
                    color = transitOnModeColor(detail.mode),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (originText.isNotBlank()) {
                    Text(
                        "${originText.uppercase(locale)} →",
                        color = dimColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.3.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    destText,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // ETA-kortti.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(cardBrush)
                .border(1.dp, cardBorder, RoundedCornerShape(18.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(if (passed) R.string.transit_eta_passed else R.string.transit_eta_arriving),
                        color = labelColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    if (!passed && minutes != null && minutes in 0..180) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("≈", color = dimColor, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            Text("$minutes", color = bigColor, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text("min", color = midColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            if (klo != null) {
                                Spacer(Modifier.width(8.dp))
                                Text("·", color = dimColor, fontSize = 20.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.transit_clock_label),
                                    color = dimColor,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(klo, color = midColor, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else if (klo != null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                stringResource(R.string.transit_clock_label),
                                color = dimColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(klo, color = bigColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (live) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PulsingLiveDot(accent)
                        if (distanceText != null) {
                            Text(distanceText, color = dimColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            if (boardName.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(labelColor))
                    Spacer(Modifier.width(8.dp))
                    Text(boardName, color = midColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (showDescriptive) {
                Spacer(Modifier.height(12.dp))
                Text(bannerText, color = dimColor, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

/** Sykkivä live-piste: staattinen piste + laajeneva/häipyvä rengas (1.8 s loop). */
@Composable
private fun PulsingLiveDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "livedot")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(animation = tween(1800), repeatMode = RepeatMode.Restart),
        label = "scale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(1800), repeatMode = RepeatMode.Restart),
        label = "alpha",
    )
    Box(modifier = Modifier.size(13.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(13.dp)
                .scale(scale)
                .alpha(pulseAlpha)
                .clip(CircleShape)
                .background(color),
        )
        Box(modifier = Modifier.size(13.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun TransitDetailOverlay(detail: TransitDetail, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    BackHandler(onBack = onClose)

    var routePatterns by remember(detail) { mutableStateOf<RoutePatterns?>(null) }
    var patternIdx by remember(detail) { mutableIntStateOf(0) }
    var timeline by remember(detail) { mutableStateOf<TripTimeline?>(null) }
    var banner by remember(detail) {
        mutableStateOf(
            context.getString(
                if (detail is TransitDetail.Route) R.string.transit_loading_route else R.string.transit_loading_trip,
            ),
        )
    }
    val liveHolder = remember(detail) { mutableStateOf<LiveTracking?>(null) }
    var detailAlert by remember(detail) { mutableStateOf<TransitAlert?>(null) }
    // Tampereen MQTT-live (Waltti ei anna GraphQL-livea): overlay GraphQL-pohjaisen tilan päälle.
    // liveBanner ohittaa GraphQL-bannerin kun MQTT-dataa on; liveStopIndex korostaa oikean pysäkin.
    var liveBanner by remember(detail) { mutableStateOf<String?>(null) }
    var liveStopIndex by remember(detail) { mutableIntStateOf(-1) }
    var liveIncoming by remember(detail) { mutableStateOf(false) }
    // Rakenteellinen live-ETA (HSL + Tampere): ETA-kortin iso luku/etäisyys/sykkivä piste.
    var liveEta by remember(detail) { mutableStateOf<LiveEta?>(null) }
    // Linjanäkymän Tampere-MQTT-live (B3): tripId → (pysäkki-indeksi, viimeksi nähty ms). Useampi
    // vuoro yhtä aikaa korostettuna; vanhenee 90 s (vrt. HSL GraphQL-vehiclePositions). Nollautuu suuntaa
    // vaihdettaessa (patternIdx). Kerätään mappiin jotta sama vuoro päivittyy eikä kerry duplikaatteja.
    var liveRouteStops by remember(detail, patternIdx) {
        mutableStateOf<Map<String, Pair<Int, Long>>>(emptyMap())
    }

    // Linjanäkymä: hae suunnat kerran (vastaa openRoute-iota).
    LaunchedEffect(detail) {
        if (detail !is TransitDetail.Route) return@LaunchedEffect
        val rp = withContext(Dispatchers.IO) {
            try { DigitransitApi.routePatterns(detail.routeGtfsId, regionForGtfsId(detail.routeGtfsId)) } catch (e: Exception) { null }
        }
        if (rp == null || rp.patterns.isEmpty()) {
            banner = context.getString(R.string.transit_line_failed)
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
                if (isRoute) {
                    banner = banner.takeIf { timeline != null } ?: context.getString(R.string.transit_loading_timetable)
                }
                val tl = withContext(Dispatchers.IO) {
                    try {
                        when (detail) {
                            is TransitDetail.Trip ->
                                DigitransitApi.tripTimeline(detail.tripGtfsId, detail.patternCode, detail.boardStop,
                                    regionForGtfsId(detail.tripGtfsId))
                            is TransitDetail.Route ->
                                DigitransitApi.patternTimetable(
                                    routePatterns!!.patterns[patternIdx].code, detail.shortName, detail.mode,
                                    regionForGtfsId(detail.routeGtfsId),
                                )
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (tl == null || tl.stops.isEmpty()) {
                    banner = context.getString(
                        if (isRoute) R.string.transit_line_timetable_failed else R.string.transit_trip_failed,
                    )
                } else {
                    timeline = tl
                    val bannerRegion = regionForGtfsId(
                        when (detail) {
                            is TransitDetail.Trip -> detail.tripGtfsId
                            is TransitDetail.Route -> detail.routeGtfsId
                        }
                    )
                    banner = (
                        if (isRoute) routeBannerText(tl, bannerRegion)
                        else tripBannerText(tl, detail.mode, bannerRegion)
                        ).resolve(context)
                    // HSL: vaatii GraphQL:stä saadun vehicleId:n (HFP-MQTT-tilausta varten).
                    // Tampere (liveViaMqtt): vehicleId on aina tyhjä → riittää reittimuoto + nousupysäkki,
                    // live-GPS tulee TampereMqttClientilta route-topicista.
                    liveHolder.value = if (!isRoute && tl.boardStopIndex >= 0 && tl.shape.size >= 2 &&
                        (!tl.vehicleId.isNullOrEmpty() || bannerRegion.liveViaMqtt)
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
                    val e = holder.etaFor(lat, lon, loc, tsi) ?: return@post
                    liveEta = e
                    banner = liveBannerText(context, detail.mode, e)
                }
            }
            onDispose { client.disconnect() }
        }
    }

    // Tampereen MQTT-livetilaus (Waltti ei anna GraphQL-livea): vain vuoronäkymälle Tampere-alueella,
    // vain etualalla. Tilaa reitin (patternCode) vp-virran ja suodattaa tämän vuoron ajoneuvon →
    // päivittää banner-overlayn (GPS-projektio, sama bannerFor kuin HSL) + aikajanan pysäkkikorostuksen.
    val tampereTrip = detail as? TransitDetail.Trip
    val tampereTrackKey = if (tampereTrip != null && liveHolder.value != null &&
        regionForGtfsId(tampereTrip.tripGtfsId).liveViaMqtt
    ) tampereTrip.tripGtfsId else null
    DisposableEffect(tampereTrackKey, resumed) {
        if (tampereTrackKey == null || !resumed || tampereTrip == null) {
            onDispose { }
        } else {
            val client = TampereMqttClient()
            val main = Handler(Looper.getMainLooper())
            val mode = tampereTrip.mode
            val tripId = tampereTrip.tripGtfsId.removePrefix("tampere:")
            client.subscribe(tampereTrip.patternCode, tripId) { lat, lon, _, incoming, stopId, _, tsi ->
                main.post {
                    val holder = liveHolder.value ?: return@post
                    val e = holder.etaFor(lat, lon, "GPS", tsi)
                    if (e != null) {
                        liveEta = e
                        liveBanner = liveBannerText(context, mode, e)
                    }
                    val idx = holder.tl.stops.indexOfFirst { it.gtfsId == "tampere:$stopId" }
                    if (idx >= 0) {
                        liveStopIndex = idx
                        liveIncoming = incoming
                    }
                }
            }
            onDispose { client.disconnect() }
        }
    }

    // Tampereen MQTT-live LINJANÄKYMÄLLE (B3): tilaa valitun suunnan vp-virta ja korosta KAIKKI
    // liikkeellä olevat vuorot aikajanalla (kuten HSL:n GraphQL-vehiclePositions). Vain etualalla;
    // re-tilaus suuntaa vaihdettaessa (avain = patternin code), katkeaa overlayn sulkeutuessa.
    val tampereRoute = detail as? TransitDetail.Route
    val routeTrackKey = if (tampereRoute != null && routePatterns != null &&
        regionForGtfsId(tampereRoute.routeGtfsId).liveViaMqtt
    ) {
        routePatterns?.patterns?.getOrNull(patternIdx)?.code
    } else {
        null
    }
    DisposableEffect(routeTrackKey, resumed) {
        if (routeTrackKey == null || !resumed) {
            onDispose { }
        } else {
            liveBanner = null // nollaa edellisen suunnan laskuri; uusi tilaus täyttää
            val client = TampereMqttClient()
            val main = Handler(Looper.getMainLooper())
            client.subscribeRoute(routeTrackKey) { tripId, _, _, _, _, stopId, _, _ ->
                main.post {
                    val t = timeline ?: return@post
                    val idx = t.stops.indexOfFirst { it.gtfsId == "tampere:$stopId" }
                    if (idx >= 0) {
                        liveRouteStops = liveRouteStops + (tripId to (idx to System.currentTimeMillis()))
                        val active = liveRouteStops.values.count {
                            System.currentTimeMillis() - it.second < 90_000L
                        }
                        liveBanner = routeRunningText(active).resolve(context)
                    }
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
        if (detail is TransitDetail.Trip) {
            // Vuoronäkymä: uusi yläosa = ETA-kortti (UI-spec-A). Ei takaisin-nuolta — järjestelmän
            // paluuele/-nappi (BackHandler) sulkee. Kaikki bannerin tilat säilyvät kortin kuvausrivinä.
            TripEtaHeader(detail, tl, liveEta, liveBanner ?: banner)
        } else {
            // Linjanäkymä: entinen header + tekstipalkki ennallaan.
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
                        contentDescription = stringResource(R.string.common_back),
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
                        banner = context.getString(R.string.transit_loading_timetable)
                    }) {
                        Text(
                            stringResource(R.string.transit_choose_direction),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                liveBanner ?: banner,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(14.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 21.sp,
            )
        }
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
        // Korostettavat pysäkit: vuoronäkymä Tampere = liveStopIndex (yksi); linjanäkymä Tampere =
        // liveRouteStops (kaikki tuoreet vuorot, vanhenee 90 s); muuten HSL:n GraphQL-vehicleStopIndices.
        val routeLiveSet = if (detail is TransitDetail.Route) {
            val now = System.currentTimeMillis()
            liveRouteStops.values.asSequence().filter { now - it.second < 90_000L }.map { it.first }.toSet()
        } else {
            emptySet()
        }
        val vehicles = when {
            liveStopIndex >= 0 -> setOf(liveStopIndex)
            routeLiveSet.isNotEmpty() -> routeLiveSet
            else -> tl?.vehicleStopIndices?.toSet() ?: emptySet()
        }
        val passedBefore = when {
            liveStopIndex >= 0 -> liveStopIndex
            tl != null && detail is TransitDetail.Trip && tl.vehicleStopIndices.size == 1 ->
                tl.vehicleStopIndices[0]
            else -> -1
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
                    approaching = if (liveStopIndex >= 0) liveIncoming
                        else tl?.vehicleIncoming == true && detail is TransitDetail.Trip,
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
    if (isVehicle) label += "  • " + stringResource(timelineVehicleRes(mode, approaching))
    else if (isBoard) label += "  • " + stringResource(R.string.transit_timeline_your_stop)
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

/** Listan severity-ikonin väri (teema-adaptoituva AAA-tekstiväri neutraalilla kortilla). */
private fun alertIconColorRes(rank: Int): Int = when {
    rank >= 3 -> R.color.mobile_warning_red
    rank == 2 -> R.color.mobile_warning_orange
    else -> R.color.mobile_warning_yellow
}

private fun effectiveRangeText(ctx: Context, fmt: SimpleDateFormat, a: TransitAlert): String {
    val s = if (a.startEpochSec > 0) fmt.format(Date(a.startEpochSec * 1000L)) else ""
    val e = if (a.endEpochSec > 0) fmt.format(Date(a.endEpochSec * 1000L)) else ""
    return when {
        s.isNotEmpty() && e.isNotEmpty() -> "$s – $e"
        s.isNotEmpty() -> ctx.getString(R.string.transit_range_from, s)
        e.isNotEmpty() -> ctx.getString(R.string.transit_range_until, e)
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
    val q = query.trim().lowercase(Locale.ROOT)
    return list.filter { a ->
        val modeOk = mode == null || a.mode.equals(mode, ignoreCase = true)
        val activeOk = !activeOnly || a.isActiveAt(nowSec)
        // Linjanumero osuu tarkasti tai alkuosalla (myös yksikirjaimiset junalinjat A/P/T) ja ≥2
        // merkillä myös osumana. Vapaa tekstihaku (pysäkki/otsikko/kuvaus) vasta ≥2 merkillä, ettei
        // yksi kirjain tulvi proosasta (suomenkielinen teksti sisältää lähes aina kysytyn kirjaimen).
        val rsn = a.routeShortName.lowercase(Locale.ROOT)
        val routeOk = q.isNotEmpty() && (rsn == q || rsn.startsWith(q) || (q.length >= 2 && rsn.contains(q)))
        val textOk = q.isEmpty() || routeOk || (
            q.length >= 2 && (
                a.stopName.lowercase(Locale.ROOT).contains(q) ||
                    a.header.lowercase(Locale.ROOT).contains(q) ||
                    a.description.lowercase(Locale.ROOT).contains(q)
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
    val context = LocalContext.current
    var status by remember { mutableStateOf(context.getString(R.string.transit_disruptions_loading)) }
    var query by remember { mutableStateOf("") }
    var modeFilter by remember { mutableStateOf<String?>(null) }
    var activeOnly by remember { mutableStateOf(true) }
    var dialogFor by remember { mutableStateOf<TransitAlert?>(null) }
    // Alue (HSL / Tampere) — oma valinta häiriösivulla; oletus = Lähilähtöjen valinta. Helsingissä
    // asuva ei tarvitse Tampereen häiriöitä eikä toisin päin → ei sekoiteta samaan listaan.
    var region by remember { mutableStateOf(currentRegion(context)) }
    val favLines = remember { TransitFavorites.getLines(context).map { it.shortName }.filter { it.isNotEmpty() }.toSet() }
    val favStops = remember { TransitFavorites.getStops(context).map { it.name }.filter { it.isNotEmpty() }.toSet() }
    // Pysäkin nimi riippuu kielestä (Accept-Language) → vertailu ensisijaisesti gtfsId:llä.
    val favStopIds = remember { TransitFavorites.getStops(context).map { it.gtfsId }.filter { it.isNotEmpty() }.toSet() }
    val hasFavorites = favLines.isNotEmpty() || favStops.isNotEmpty()
    // Ilmoituksesta avattaessa (HSL-suosikkihäiriö): näytä heti vain suosikkien häiriöt.
    var favoritesOnly by remember { mutableStateOf(DisruptionNav.consumeFocusFavorites() && hasFavorites) }

    LaunchedEffect(refreshTick, region) {
        if (all == null) status = context.getString(R.string.transit_disruptions_loading)
        val result = withContext(Dispatchers.IO) {
            try { DigitransitApi.serviceAlerts(region) } catch (e: Exception) { null }
        }
        if (result == null) {
            if (all == null) status = context.getString(R.string.transit_disruptions_failed)
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
                        a.matchesStop(favStopIds, favStops)
                }
            } else {
                base
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.transit_title_disruptions),
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        // Alue vaihtuu → nollaa lista, jotta vanhan alueen häiriöt eivät jää näkyviin haun ajaksi.
        RegionSelectorRow(selected = region) { region = it; all = null }
        SearchTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.transit_disruptions_search_hint),
            modifier = Modifier.padding(horizontal = 14.dp),
            onClear = { query = "" },
        )
        Row(
            modifier = Modifier
                .padding(start = 14.dp, end = 14.dp, top = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeFilterChip(
                selected = modeFilter == null,
                label = stringResource(R.string.transit_filter_all),
                mode = null,
            ) { modeFilter = null }
            MODE_FILTERS.forEach { (mode, labelRes) ->
                ModeFilterChip(
                    selected = modeFilter == mode,
                    label = stringResource(labelRes),
                    mode = mode,
                ) { modeFilter = if (modeFilter == mode) null else mode }
            }
        }
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = activeOnly,
                onClick = { activeOnly = !activeOnly },
                label = { Text(stringResource(R.string.transit_filter_active_only)) },
            )
            if (hasFavorites) {
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = !favoritesOnly },
                    label = { Text(stringResource(R.string.transit_filter_favourites_only)) },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            shown?.let {
                Text(
                    pluralStringResource(R.plurals.transit_disruption_count, it.size, it.size),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            val list = shown
            val datePattern = stringResource(R.string.transit_datetime_pattern)
            val locale = AppLocale.current(context)
            val dateFmt = remember(datePattern, locale) { SimpleDateFormat(datePattern, locale) }
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
                    ) { i -> DisruptionRow(list[i], dateFmt) { dialogFor = it } }
                }
            } else {
                Text(
                    if (list == null) status else stringResource(R.string.transit_disruptions_empty),
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

/** Kulkuvälinesuodattimet (moodi → chipin teksti), sama järjestys Lähilähdöissä ja Häiriöissä. */
private val MODE_FILTERS: List<Pair<String, Int>> = listOf(
    "BUS" to R.string.transit_filter_bus,
    "TRAM" to R.string.transit_filter_tram,
    "RAIL" to R.string.transit_filter_train,
    "SUBWAY" to R.string.transit_filter_metro,
    "FERRY" to R.string.transit_filter_ferry,
)

@Composable
/** Kulkuvälinesuodattimen chip-ikoni: "Kaikki" (mode=null) → done_all, muut kortin moodi-ikoni. */
private fun transitModeChipIcon(mode: String?): Int =
    if (mode == null) R.drawable.mobile_ic_done_all_24 else transitModeIconRes(mode)

/** Suodatinchip ikonilla + tekstillä (sama tyyli Lähilähdöissä ja Häiriöissä). Ikoni perii chipin
 *  tekstivärin (FilterChip-oletus), 18 dp. Suodatuslogiikka säilyy kutsujalla. */
@Composable
private fun ModeFilterChip(selected: Boolean, label: String, mode: String?, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                painterResource(transitModeChipIcon(mode)),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun DisruptionRow(a: TransitAlert, dateFmt: SimpleDateFormat, onClick: (TransitAlert) -> Unit) {
    val context = LocalContext.current
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(transitModeIconRes(a.mode)),
                            contentDescription = null,
                            tint = transitModeColor(a.mode),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        TransitLineBadge(a.routeShortName, a.mode)
                    }
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
                val sub = listOf(effectiveRangeText(context, dateFmt, a), a.stopName)
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
