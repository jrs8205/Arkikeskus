package org.jrs82.fsclock.mobile.gps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import java.util.Locale
import kotlinx.coroutines.delay
import org.jrs82.fsclock.R
import org.jrs82.fsclock.mobile.AppLocale
import org.jrs82.fsclock.mobile.ArkiCard
import org.jrs82.fsclock.mobile.ArkiTheme
import org.jrs82.fsclock.mobile.GpsSpeedometerView

/** Kaikki, mitä näkymät tarvitsevat piirtämiseen: yksi olio, jotta kolme näkymää näyttävät samat luvut. */
private data class DashboardData(
    val speedKmh: Int?,
    val trip: TripSnapshot,
    val reading: GpsReading,
    val nowRtMs: Long,
    val locale: Locale,
)

/**
 * GPS-mittaristo: kolme näkymää (Perus, Matka, Täysi) samasta mittauksesta pysty- ja vaaka-asetteluina.
 * Näkymän avaaminen näyttää vain nopeuden: matka ja sen taustapalvelu käynnistyvät vasta painikkeesta.
 * [onBack] vie etusivulle; nuoli näkyy vaakana, jolloin sovelluksen alapalkki on piilossa.
 */
@Composable
internal fun GpsDashboardScreen(onBack: () -> Unit, onHistory: () -> Unit, visible: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var view by remember { mutableStateOf(DashboardPrefs.view(prefs)) }
    var keepScreenOn by remember { mutableStateOf(DashboardPrefs.keepScreenOn(prefs)) }
    val reading by GpsSource.reading.collectAsStateWithLifecycle()
    val trip by TripTracker.state.collectAsStateWithLifecycle()
    var nowRtMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // Viimeisin kosketus: matkan aikana ylärivi piiloutuu, kun näyttöön ei ole koskettu hetkeen.
    var lastTouchRtMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // Näppäimistön kohdistus ylärivin painikkeessa: rivi ei silloin piiloudu.
    var headerFocused by remember { mutableStateOf(false) }
    // Rivi on tuotu esiin esteettömyystoiminnolla: se pysyy näkyvissä seuraavaan kosketukseen asti.
    var headerPinned by remember { mutableStateOf(false) }
    val markInteraction = {
        val now = SystemClock.elapsedRealtime()
        lastTouchRtMs = now
        nowRtMs = now
    }

    // GPS on päällä vain, kun näkymä on näkyvissä ja sovellus etualalla (tai matkan palvelu pitää sitä).
    // [visible] on epätosi, kun valikko tai asetukset peittävät näkymän: se pysyy silloin kompositiossa,
    // mutta ei saa pitää GPS:ää eikä näyttöä päällä.
    val sourceOwner = remember { Any() }
    LifecycleStartEffect(visible) {
        if (visible) GpsSource.acquire(context, sourceOwner)
        onStopOrDispose { GpsSource.release(sourceOwner) }
    }
    // Sekuntitikki: kokonaisaika kulkee ja signaalin vanheneminen näkyy, vaikka mittauksia ei tulisi.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowRtMs = SystemClock.elapsedRealtime()
                TripTracker.refresh()
                delay(1000L)
            }
        }
    }
    // Näyttö pysyy päällä vain kytkimestä ja vain tämän näkymän ollessa näkyvissä.
    val hostView = LocalView.current
    DisposableEffect(keepScreenOn, visible) {
        hostView.keepScreenOn = keepScreenOn && visible
        onDispose { hostView.keepScreenOn = false }
    }

    // Matkan aloitus ja jatkaminen käynnistävät taustapalvelun. Ilmoituslupa pyydetään ensin, mutta matka
    // käynnistyy vastauksesta riippumatta.
    var pendingServiceAction by rememberSaveable { mutableStateOf<String?>(null) }
    // Järjestelmä voi estää palvelun käynnistyksen jo kutsussa: silloin matkan tila ei muutu ja siitä kerrotaan.
    fun launchService(action: String) {
        if (!TripTrackingService.start(context, action)) {
            Toast.makeText(context, R.string.gps_trip_start_failed, Toast.LENGTH_LONG).show()
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingServiceAction?.let { launchService(it) }
        pendingServiceAction = null
    }
    fun startService(action: String) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            pendingServiceAction = action
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchService(action)
        }
    }
    val onAction: (TripAction) -> Unit = { action ->
        // Heti tilan muutoksen jälkeen napautus ohitetaan: painikkeet vaihtuivat juuri sormen alla.
        if (TripTracker.acceptsCommands()) {
            when (action) {
                TripAction.START, TripAction.NEW_TRIP -> startService(TripTrackingService.ACTION_START)
                TripAction.RESUME, TripAction.CONTINUE -> startService(TripTrackingService.ACTION_RESUME)
                TripAction.PAUSE -> TripTracker.pause(context)
                TripAction.FINISH -> TripTracker.finish(context)
                TripAction.RESET -> TripTracker.reset(context)
            }
        }
    }
    val selectView: (DashboardView) -> Unit = { v ->
        view = v
        prefs.edit().putString(GpsDashboardModel.KEY_VIEW, v.name).apply()
    }

    val conf = LocalConfiguration.current
    val landscape = GpsDashboardModel.isLandscape(conf.screenWidthDp, conf.screenHeightDp)
    val data = DashboardData(
        speedKmh = GpsReadout.displaySpeedKmh(reading.fix, nowRtMs),
        trip = trip,
        reading = reading,
        nowRtMs = nowRtMs,
        locale = AppLocale.current(context),
    )

    // Ylärivin piilotusaika. Järjestelmän esteettömyysasetus voi pidentää sitä tai poistaa sen, ja
    // ruudunlukijan kosketustutkinnan aikana riviä ei piiloteta: piilossa olevaa hallintaa ei voi löytää.
    val accessibility = LocalAccessibilityManager.current
    val touchExploration = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
    }?.isTouchExplorationEnabled == true
    // Näppäimistöllä tai suuntanäppäimillä käytettäessä (ei kosketustilassa) riviä ei myöskään piiloteta:
    // sen painikkeisiin pitää päästä kohdistuksella, eikä näppäimistökäyttäjä voi "napauttaa" riviä esiin.
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val hideAfterMs = if (touchExploration || keyboardMode) {
        Long.MAX_VALUE
    } else {
        accessibility?.calculateRecommendedTimeoutMillis(
            GpsDashboardModel.HEADER_HIDE_MS, containsIcons = true, containsText = true, containsControls = true,
        ) ?: GpsDashboardModel.HEADER_HIDE_MS
    }
    val headerVisible =
        GpsDashboardModel.headerVisible(trip.phase, lastTouchRtMs, nowRtMs, hideAfterMs, headerFocused, headerPinned)
    val showControlsLabel = stringResource(R.string.gps_show_controls)

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Kehys on jo väistänyt järjestelmäpalkit; jäljelle jää kameran aukko vaakana.
            .consumeWindowInsets(WindowInsets.systemBars)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            // Jokainen kosketus tuo ylärivin takaisin. Kosketusta vain seurataan (ei kuluteta), joten
            // painikkeet toimivat ensimmäisellä napautuksella myös rivin ollessa piilossa.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // Oikea kosketus palauttaa tavallisen aikarajan.
                    headerPinned = false
                    markInteraction()
                }
            }
            // Apupalvelu (esim. kytkinohjaus tai puheohjaus ilman kosketustutkintaa) ei tuota kosketuksia
            // eikä näppäintapahtumia. Rivin ollessa piilossa mittaristo tarjoaa sille toiminnon, joka tuo rivin
            // esiin ja pitää sen näkyvissä; toiminto on vain esteettömyyspuussa, kosketus ei käytä sitä.
            .then(
                if (headerVisible) {
                    Modifier
                } else {
                    Modifier.semantics {
                        contentDescription = showControlsLabel
                        onClick(label = showControlsLabel) {
                            headerPinned = true
                            markInteraction()
                            true
                        }
                    }
                },
            )
            // Näppäimistökäyttö: näppäimen painallus tai kohdistuksen siirtyminen mittaristoon tuo rivin
            // takaisin samoin kuin kosketus. Näppäintä ei kuluteta, joten kohdistus liikkuu normaalisti.
            .onPreviewKeyEvent {
                markInteraction()
                false
            }
            .onFocusChanged { if (it.hasFocus) markInteraction() }
            .padding(horizontal = 16.dp, vertical = if (landscape) 6.dp else 12.dp),
    ) {
        AnimatedVisibility(
            visible = headerVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.onFocusChanged { headerFocused = it.hasFocus }) {
                DashboardHeader(
                    landscape = landscape,
                    view = view,
                    onView = selectView,
                    keepScreenOn = keepScreenOn,
                    onKeepScreenOn = { on ->
                        keepScreenOn = on
                        prefs.edit().putBoolean(GpsDashboardModel.KEY_KEEP_SCREEN_ON, on).apply()
                    },
                    onBack = onBack,
                    onHistory = onHistory,
                )
                Spacer(Modifier.height(if (landscape) 4.dp else 10.dp))
            }
        }
        val body = Modifier.fillMaxWidth().weight(1f)
        when (view) {
            DashboardView.BASIC ->
                if (landscape) BasicLandscape(data, body) { selectView(DashboardView.TRIP) }
                else BasicPortrait(data, body) { selectView(DashboardView.TRIP) }
            DashboardView.TRIP ->
                if (landscape) TripLandscape(data, onAction, body) else TripPortrait(data, onAction, body)
            DashboardView.FULL ->
                if (landscape) FullLandscape(data, onAction, body) else FullPortrait(data, onAction, body)
        }
    }
}

// ===================== Otsikkorivi: nimi, näkymän valitsin, Näyttö päällä =====================

@Composable
private fun DashboardHeader(
    landscape: Boolean,
    view: DashboardView,
    onView: (DashboardView) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    onBack: () -> Unit,
    onHistory: () -> Unit,
) {
    val config = LocalConfiguration.current
    val singleRow = landscape && config.screenWidthDp >= 360 + (250 * config.fontScale)
    if (singleRow) {
        // Vaakana kaikki yhdellä rivillä, jotta korkeutta säästyy luvuille.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.mobile_ic_arrow_back),
                    contentDescription = stringResource(R.string.common_back),
                )
            }
            Text(
                stringResource(R.string.home_gps_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
            ViewSelector(view, onView, Modifier.weight(1f).widthIn(max = 360.dp))
            HistoryButton(onHistory)
            KeepScreenToggle(keepScreenOn, onKeepScreenOn)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (landscape) IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.mobile_ic_arrow_back), stringResource(R.string.common_back))
            }
            Text(
                stringResource(R.string.home_gps_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (!landscape) {
                HistoryButton(onHistory)
                KeepScreenToggle(keepScreenOn, onKeepScreenOn)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (landscape) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ViewSelector(view, onView, Modifier.weight(1f))
                HistoryButton(onHistory)
                KeepScreenToggle(keepScreenOn, onKeepScreenOn)
            }
        } else ViewSelector(view, onView, Modifier.fillMaxWidth())
    }
}

@Composable
private fun HistoryButton(onHistory: () -> Unit) {
    IconButton(onClick = onHistory) {
        Icon(painterResource(R.drawable.mobile_ic_clock_24), stringResource(R.string.gps_history))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSelector(view: DashboardView, onView: (DashboardView) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(
        DashboardView.BASIC to R.string.gps_view_basic,
        DashboardView.TRIP to R.string.gps_view_trip,
        DashboardView.FULL to R.string.gps_view_full,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, (option, labelRes) ->
            SegmentedButton(
                selected = view == option,
                onClick = { onView(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // Ei valintamerkkiä: se veisi kapealla näytöllä tilan tekstiltä; valinta näkyy väristä
                // ja ruudunlukijalle valittu-tilasta.
                icon = { },
            ) {
                Text(stringResource(labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun KeepScreenToggle(checked: Boolean, onChecked: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChecked(!checked) },
        label = { Text(stringResource(R.string.gps_keep_screen_on), maxLines = 1) },
    )
}

// ===================== A · Perus =====================

@Composable
private fun BasicPortrait(data: DashboardData, modifier: Modifier, onOpenTrip: () -> Unit) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Gauge(data.speedKmh, Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(
            (data.speedKmh?.toString() ?: TripFormat.EMPTY) + " km/h",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        GpsLine(data)
        TripChip(data, onOpenTrip)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun BasicLandscape(data: DashboardData, modifier: Modifier, onOpenTrip: () -> Unit) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(0.46f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Gauge(data.speedKmh, Modifier.fillMaxHeight().aspectRatio(1f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(0.54f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            BigSpeed(data.speedKmh, Modifier.fillMaxWidth().weight(1f))
            GpsLine(data)
            TripChip(data, onOpenTrip)
        }
    }
}

// ===================== B · Matka =====================

@Composable
private fun TripPortrait(data: DashboardData, onAction: (TripAction) -> Unit, modifier: Modifier) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BigSpeed(data.speedKmh, Modifier.fillMaxWidth().height(170.dp))
            Spacer(Modifier.height(8.dp))
            TripTiles(data)
            Spacer(Modifier.height(10.dp))
            GpsLine(data)
            Spacer(Modifier.height(8.dp))
        }
        TripButtons(data.trip.phase, onAction, buttonHeight = 48.dp)
    }
}

@Composable
private fun TripLandscape(data: DashboardData, onAction: (TripAction) -> Unit, modifier: Modifier) {
    Row(modifier = modifier) {
        Column(Modifier.weight(0.44f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            BigSpeed(data.speedKmh, Modifier.fillMaxWidth().weight(1f))
            GpsLine(data)
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(0.56f).fillMaxHeight()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                TripTiles(data)
                Spacer(Modifier.height(8.dp))
            }
            TripButtons(data.trip.phase, onAction, buttonHeight = 56.dp)
        }
    }
}

/** Matka-näkymän neljä tärkeintä lukua 2 × 2 -ruudukkona. */
@Composable
private fun TripTiles(data: DashboardData) {
    val t = data.trip
    val has = t.phase != TripPhase.NONE
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TileRow {
            StatTile(R.string.gps_label_distance, distanceOf(data.trip, data.locale), Modifier.weight(1f))
            StatTile(R.string.gps_label_time, timeOf(has, t.totalMs), Modifier.weight(1f))
        }
        TileRow {
            StatTile(R.string.gps_label_avg, avgOf(has, t.avgTotalKmh, data.locale), Modifier.weight(1f))
            StatTile(R.string.gps_label_top, topOf(t), Modifier.weight(1f))
        }
    }
}

// ===================== C · Täysi =====================

@Composable
private fun FullPortrait(data: DashboardData, onAction: (TripAction) -> Unit, modifier: Modifier) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BigSpeed(data.speedKmh, Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(8.dp))
            FullTiles(data.trip, data.locale)
            Spacer(Modifier.height(8.dp))
            GpsCard(data, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        TripButtons(data.trip.phase, onAction, buttonHeight = 48.dp)
    }
}

@Composable
private fun FullLandscape(data: DashboardData, onAction: (TripAction) -> Unit, modifier: Modifier) {
    Row(modifier = modifier) {
        Column(Modifier.weight(0.36f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            BigSpeed(data.speedKmh, Modifier.fillMaxWidth().weight(1f))
            GpsCard(data, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(0.64f).fillMaxHeight()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                FullTiles(data.trip, data.locale)
                Spacer(Modifier.height(8.dp))
            }
            TripButtons(data.trip.phase, onAction, buttonHeight = 56.dp)
        }
    }
}

/** Täysi-näkymän kaikki luvut: neljä laattaa ja kolme aikalaattaa. */
@Composable
internal fun FullTiles(t: TripSnapshot, locale: Locale) {
    val has = t.phase != TripPhase.NONE
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TileRow {
            StatTile(R.string.gps_label_distance, distanceOf(t, locale), Modifier.weight(1f))
            StatTile(R.string.gps_label_top_speed, topOf(t), Modifier.weight(1f))
        }
        TileRow {
            StatTile(R.string.gps_label_avg_total, avgOf(has, t.avgTotalKmh, locale), Modifier.weight(1f))
            StatTile(R.string.gps_label_avg_moving, avgOf(has, t.avgMovingKmh, locale), Modifier.weight(1f))
        }
        TimeTileRow(
            listOf(
                R.string.gps_label_total_time to timeOf(has, t.totalMs),
                R.string.gps_label_moving to timeOf(has, t.movingMs),
                R.string.gps_label_stopped to timeOf(has, TripFormat.stoppedForDisplay(t.totalMs, t.movingMs)),
            ),
        )
    }
}

// ===================== Yhteiset palat =====================

/** Ilman matkaa luvut ovat tyhjiä. */
private fun distanceOf(trip: TripSnapshot, locale: Locale): Quantity =
    if (trip.phase == TripPhase.NONE) Quantity(TripFormat.EMPTY, "km")
    else TripFormat.distance(trip.distanceM, locale)

private fun timeOf(has: Boolean, ms: Long): Quantity =
    Quantity(if (has) TripFormat.clock(ms) else TripFormat.EMPTY, "")

private fun avgOf(has: Boolean, kmh: Double?, locale: Locale): Quantity =
    TripFormat.avgKmh(if (has) kmh else null, locale)

private fun topOf(t: TripSnapshot): Quantity =
    TripFormat.wholeKmh(if (t.phase == TripPhase.NONE) null else t.maxSpeedMps)

@Composable
private fun Gauge(speedKmh: Int?, modifier: Modifier) {
    AndroidView(
        factory = { GpsSpeedometerView(it) },
        update = { it.setSpeedKmh(speedKmh?.toFloat() ?: 0f) },
        modifier = modifier,
    )
}

/**
 * Iso nopeusnumero. Koko mitoitetaan käytettävissä olevasta tilasta eikä fonttikokoasetuksesta, ja tilaa
 * varataan aina kolmelle numerolle, jotta lukema ei hypi eikä leikkaudu nopeuden kasvaessa.
 */
@Composable
private fun BigSpeed(speedKmh: Int?, modifier: Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // Lihavan numeron leveys on noin 0,6 × fonttikoko; yksikkörivi tarvitsee oman korkeutensa.
        val byWidth = maxWidth / (3 * 0.62f)
        val byHeight = (maxHeight - 28.dp) * 0.82f
        val size: Dp = minOf(byWidth, byHeight).coerceIn(24.dp, 240.dp)
        val fontSize = with(LocalDensity.current) { size.toSp() }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Tyhjä arvo piirretään ohuena ja himmeänä: lihava viiva näyttäisi tässä koossa mustalta palkilta.
            Text(
                speedKmh?.toString() ?: TripFormat.EMPTY,
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = if (speedKmh != null) FontWeight.ExtraBold else FontWeight.Light,
                color = if (speedKmh != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                "km/h",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private val TILE_GAP = 8.dp
private val TILE_PADDING_H = 12.dp

@Composable
private fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP), modifier = Modifier.fillMaxWidth(), content = content)
}

/**
 * Kolme kapeaa aikalaattaa rinnakkain. Otsikoille ja arvoille haetaan rivin yhteinen koko, jotta rivi
 * pysyy tasaisena: pitkä sana ei katkea kesken (iso fontti) eikä pitkä aika leikkaudu (yli 100 h).
 */
@Composable
private fun TimeTileRow(tiles: List<Pair<Int, Quantity>>) {
    val labelStyle = MaterialTheme.typography.labelMedium
    val valueStyle = tileValueStyle()
    val unitStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = tiles.map { stringResource(it.first) }
    val quantities = tiles.map { it.second }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Yhden laatan sisäleveys: rivin leveys ilman välejä jaettuna laatoille, miinus laatan reunukset.
        val inner = with(density) {
            (constraints.maxWidth - TILE_GAP.roundToPx() * (tiles.size - 1)) / tiles.size -
                TILE_PADDING_H.roundToPx() * 2
        }
        val labelScale = remember(labels, inner, labelStyle, density) {
            val words = labels.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
            GpsDashboardModel.fitScale(inner, GpsDashboardModel.LABEL_MIN_SCALE) { scale ->
                val style = labelStyle.scaledBy(scale, density)
                words.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0
            }
        }
        val valueScale = remember(quantities, inner, valueStyle, unitStyle, density) {
            GpsDashboardModel.fitScale(inner) { scale ->
                quantities.maxOfOrNull { quantityWidth(measurer, it, valueStyle, unitStyle, scale, density) } ?: 0
            }
        }
        TileRow {
            tiles.forEach { (labelRes, quantity) ->
                StatTile(labelRes, quantity, Modifier.weight(1f), labelScale, valueScale)
            }
        }
    }
}

@Composable
private fun StatTile(
    labelRes: Int,
    quantity: Quantity,
    modifier: Modifier = Modifier,
    labelScale: Float = 1f,
    valueScale: Float = 1f,
) {
    ArkiCard(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = TILE_PADDING_H, vertical = 8.dp)) {
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium.scaledBy(labelScale, LocalDensity.current),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            QuantityLine(quantity, valueScale)
        }
    }
}

@Composable
private fun tileValueStyle(): TextStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)

/**
 * Tyyli pienennettynä. Kerroin kohdistuu pikselikokoon eikä fonttikoon lukuarvoon, koska järjestelmän
 * fonttiskaalaus ei ole lineaarinen: pieni teksti suurenee enemmän kuin iso.
 */
private fun TextStyle.scaledBy(scale: Float, density: Density): TextStyle =
    if (scale >= 1f) {
        this
    } else {
        with(density) {
            copy(
                fontSize = (fontSize.toPx() * scale).toSp(),
                lineHeight = if (lineHeight.isSp) (lineHeight.toPx() * scale).toSp() else lineHeight,
            )
        }
    }

/** Arvon ja yksikön yhteisleveys pikseleinä annetulla pienennyskertoimella. */
private fun quantityWidth(
    measurer: TextMeasurer,
    quantity: Quantity,
    valueStyle: TextStyle,
    unitStyle: TextStyle,
    scale: Float,
    density: Density,
): Int {
    val value = measurer.measure(
        quantity.value, valueStyle.scaledBy(scale, density), maxLines = 1, softWrap = false,
    ).size.width
    if (quantity.unit.isEmpty()) return value
    val unit = measurer.measure(
        quantity.unit, unitStyle.scaledBy(scale, density), maxLines = 1, softWrap = false,
    ).size.width
    return value + with(density) { 4.dp.roundToPx() } + unit
}

/**
 * Arvo ja yksikkö yhdellä rivillä. Jos rivi ei mahdu laattaan (pitkä aika, iso fontti, kapea näyttö),
 * tekstiä pienennetään juuri sen verran, että se mahtuu: mikään luku ei saa leikkautua. [maxScale] on
 * rivin yhteinen koko, jota tämä laatta ei ylitä.
 */
@Composable
private fun QuantityLine(quantity: Quantity, maxScale: Float = 1f) {
    val valueStyle = tileValueStyle()
    val unitStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        val own = remember(quantity, available, valueStyle, unitStyle, density) {
            GpsDashboardModel.fitScale(available) { scale ->
                quantityWidth(measurer, quantity, valueStyle, unitStyle, scale, density)
            }
        }
        val scale = minOf(own, maxScale)
        Row {
            Text(
                quantity.value,
                style = valueStyle.scaledBy(scale, density),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            if (quantity.unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    quantity.unit,
                    style = unitStyle.scaledBy(scale, density),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

/** GPS:n tila tekstinä; väri on vain lisä (pallo), ei ainoa viesti. */
@Composable
private fun gpsStatusText(data: DashboardData): String =
    when (GpsReadout.status(data.reading, data.nowRtMs)) {
        GpsStatus.UNAVAILABLE -> stringResource(R.string.gps_unavailable)
        GpsStatus.OFF -> stringResource(R.string.gps_location_off)
        GpsStatus.WAITING -> stringResource(R.string.home_gps_waiting)
        GpsStatus.LOST -> stringResource(R.string.gps_signal_lost)
        // Mittauksia tulee, mutta ilman nopeutta: nopeutta ei voi näyttää eikä matkaa mitata.
        GpsStatus.NO_SPEED -> stringResource(R.string.gps_no_speed)
        GpsStatus.OK -> stringResource(
            when (GpsReadout.quality(data.reading.fix, data.nowRtMs)) {
                GpsQuality.GOOD -> R.string.gps_quality_good
                GpsQuality.FAIR -> R.string.gps_quality_fair
                else -> R.string.gps_quality_weak
            },
        )
    }

@Composable
private fun gpsQualityColor(data: DashboardData): Color =
    when (GpsReadout.quality(data.reading.fix, data.nowRtMs)) {
        GpsQuality.GOOD -> ArkiTheme.colors.priceCheap
        GpsQuality.FAIR -> ArkiTheme.colors.priceNormal
        GpsQuality.WEAK -> ArkiTheme.colors.priceExpensive
        GpsQuality.NONE -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun QualityDot(data: DashboardData) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(gpsQualityColor(data)))
}

/** Yhden rivin GPS-tila: laatu tai syy siihen, ettei nopeutta ole, ja tuoreen mittauksen sijaintitarkkuus. */
@Composable
private fun GpsLine(data: DashboardData) {
    // Tarkkuus kuuluu mittaukseen: se näkyy myös silloin, kun mittauksessa ei ole nopeutta.
    val fresh = GpsReadout.hasFreshFix(data.reading, data.nowRtMs)
    val text = if (fresh) {
        gpsStatusText(data) + " · " + TripFormat.accuracy(data.reading.fix?.accuracyM)
    } else {
        gpsStatusText(data)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        QualityDot(data)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Täysi-näkymän GPS-kortti: laatu, sijainnin ja nopeuden tarkkuus sekä satelliitit. */
@Composable
private fun GpsCard(data: DashboardData, modifier: Modifier) {
    val fix = data.reading.fix
    // Tarkkuusrivit näkyvät tuoreesta mittauksesta riippumatta siitä, antoiko vastaanotin nopeuden: muuten
    // rivit katoaisivat ja palaisivat, kun nopeus tulee vain osassa mittauksista.
    val fresh = GpsReadout.hasFreshFix(data.reading, data.nowRtMs)
    ArkiCard(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QualityDot(data)
                Spacer(Modifier.width(6.dp))
                Text(
                    gpsStatusText(data),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val detail = MaterialTheme.typography.bodySmall
            val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
            if (fresh) {
                Text(
                    stringResource(R.string.gps_detail_position, TripFormat.accuracy(fix?.accuracyM)),
                    style = detail, color = detailColor,
                )
                Text(
                    stringResource(
                        R.string.gps_detail_speed,
                        TripFormat.speedAccuracy(fix?.speedAccuracyMps, data.locale),
                    ),
                    style = detail, color = detailColor,
                )
            }
            Text(
                stringResource(R.string.gps_detail_satellites, data.reading.satUsed, data.reading.satVisible),
                style = detail, color = detailColor,
            )
        }
    }
}

/** Perus-näkymän merkki käynnissä tai tauolla olevasta matkasta. Napautus avaa Matka-näkymän. */
@Composable
private fun TripChip(data: DashboardData, onOpenTrip: () -> Unit) {
    val phase = data.trip.phase
    if (!GpsDashboardModel.chipVisible(phase)) return
    val distance = TripFormat.distance(data.trip.distanceM, data.locale).toString()
    val textRes = if (phase == TripPhase.RUNNING) R.string.gps_chip_running else R.string.gps_chip_paused
    Spacer(Modifier.height(6.dp))
    AssistChip(onClick = onOpenTrip, label = { Text(stringResource(textRes, distance), maxLines = 1) })
}

/** Painikkeet matkan tilan mukaan. Pääpainikkeet rinnakkain, lisätoiminto niiden alla. */
@Composable
private fun TripButtons(phase: TripPhase, onAction: (TripAction) -> Unit, buttonHeight: Dp) {
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (action in GpsDashboardModel.primaryActions(phase)) {
                val m = Modifier.weight(1f).heightIn(min = buttonHeight)
                when (action) {
                    TripAction.START, TripAction.NEW_TRIP ->
                        Button(onClick = { onAction(action) }, modifier = m) { ActionLabel(action) }
                    TripAction.FINISH ->
                        FilledTonalButton(
                            onClick = { onAction(action) },
                            modifier = m,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) { ActionLabel(action) }
                    else ->
                        FilledTonalButton(onClick = { onAction(action) }, modifier = m) { ActionLabel(action) }
                }
            }
        }
        GpsDashboardModel.secondaryAction(phase)?.let { action ->
            TextButton(
                onClick = { onAction(action) },
                modifier = Modifier.fillMaxWidth().heightIn(min = buttonHeight),
            ) { ActionLabel(action) }
        }
    }
}

@Composable
private fun ActionLabel(action: TripAction) {
    val res = when (action) {
        TripAction.START -> R.string.gps_action_start
        TripAction.PAUSE -> R.string.gps_action_pause
        TripAction.RESUME -> R.string.gps_action_resume
        TripAction.FINISH -> R.string.gps_action_finish
        TripAction.RESET -> R.string.gps_action_reset
        TripAction.NEW_TRIP -> R.string.gps_action_new_trip
        TripAction.CONTINUE -> R.string.gps_action_continue
    }
    Text(stringResource(res), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
