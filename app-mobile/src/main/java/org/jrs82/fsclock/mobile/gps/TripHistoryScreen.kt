package org.jrs82.fsclock.mobile.gps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.jrs82.fsclock.R
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.db.TripEntity
import org.jrs82.fsclock.mobile.AppLocale
import org.jrs82.fsclock.mobile.historyCarIcon
import org.jrs82.fsclock.mobile.historyColors
import java.time.LocalDate
import java.time.ZoneId

internal enum class GpsPage { HISTORY, DASHBOARD, PERMISSION }

internal fun gpsPage(historyOpen: Boolean, fineGranted: Boolean): GpsPage = when {
    historyOpen -> GpsPage.HISTORY
    fineGranted -> GpsPage.DASHBOARD
    else -> GpsPage.PERMISSION
}

@Composable
private fun historyFormat(): HistoryFormat {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    fun currentCalendar() = ZoneId.systemDefault().let { it to LocalDate.now(it) }
    var calendar by remember { mutableStateOf(currentCalendar()) }
    LifecycleStartEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { calendar = currentCalendar() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        calendar = currentCalendar()
        // Pysäytys ja sitä seuraava komposition purku voivat kutsua samaa siivousta.
        var registered = true
        onStopOrDispose {
            if (registered) {
                context.unregisterReceiver(receiver)
                registered = false
            }
        }
    }
    val use24h = DateFormat.is24HourFormat(context)
    return remember(context, config, calendar, use24h) {
        HistoryFormat(context, AppLocale.current(context), calendar.first, calendar.second, use24h)
    }
}

private val historySelectionSaver = mapSaver(
    save = { selection: HistorySelection -> mapOf("day" to selection.dayKey, "trip" to selection.tripId) },
    restore = { HistorySelection(it["day"] as String?, it["trip"] as String?) },
)

@Composable
internal fun TripHistoryScreen(visible: Boolean, onBack: () -> Unit, requestedTrip: String? = null, onTripOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val format = historyFormat()
    val history = remember(context, format.zone) {
        flow {
            emitAll(FsClockDb.get(context).tripDao().observe().asFlow())
        }.map { tripDays(it, format.zone) }.flowOn(Dispatchers.IO)
    }
    val days by history.collectAsStateWithLifecycle(initialValue = null)
    var selection by rememberSaveable(stateSaver = historySelectionSaver) { mutableStateOf(HistorySelection()) }
    LaunchedEffect(requestedTrip) {
        if (requestedTrip != null) {
            selection = HistorySelection(tripId = requestedTrip)
            onTripOpened()
        }
    }
    var undoRow by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val dayState = key(selection.dayKey) { rememberLazyListState() }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val back = {
        if (selection == HistorySelection()) onBack() else selection = selection.parent()
    }
    BackHandler(enabled = visible, onBack = back)
    val error = stringResource(R.string.gps_history_error)
    val deleted = stringResource(R.string.gps_history_deleted)
    val undo = stringResource(R.string.gps_history_undo)
    LaunchedEffect(undoRow) {
        val entry = undoRow?.let { JSONObject(it).tripOrNull() } ?: return@LaunchedEffect
        val result = snack.showSnackbar(deleted, actionLabel = undo, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) {
            try {
                TripTracker.archive.undo(entry).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snack.showSnackbar(error)
            }
        }
        undoRow = null
    }

    val detail = remember(days, selection) { historyDetail(days, selection.dayKey, selection.tripId) }
    LaunchedEffect(days, selection, requestedTrip) {
        if (requestedTrip == null && days != null) {
            when (detail) {
                null -> selection = HistorySelection()
                is HistoryDetail.Day -> selection = selection.copy(tripId = null)
                is HistoryDetail.Trip -> Unit
            }
        }
    }
    val title = when (detail) {
        is HistoryDetail.Trip -> {
            val start = java.time.Instant.ofEpochMilli(detail.row.startedAtMs).atZone(format.zone)
            format.dateAndInterval(start.toLocalDate(), detail.row.historyInterval())
        }
        is HistoryDetail.Day -> {
            val interval = remember(detail) { dayInterval(detail.rows) }
            "${stringResource(R.string.gps_history_day)} · ${format.dateAndInterval(detail.date, interval)}"
        }
        null -> stringResource(R.string.gps_history)
    }

    Column(Modifier.fillMaxSize().background(historyColors().page).consumeWindowInsets(WindowInsets.systemBars)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) {
                Icon(painterResource(R.drawable.mobile_ic_arrow_back), stringResource(R.string.common_back))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        val body = Modifier.weight(1f).fillMaxWidth()
        val groups = days
        when {
            groups == null -> Text(stringResource(R.string.common_loading), body)
            detail is HistoryDetail.Day -> {
                LazyColumn(body, state = dayState, verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tripCount(detail.rows.size))
                            FullTiles(remember(detail) { daySnapshot(detail.rows) }, format.locale, TileStyle.HISTORY)
                        }
                    }
                    items(detail.rows, key = { it.id }) { trip ->
                        TripCard(detail.date, trip, format) { selection = selection.copy(tripId = trip.id) }
                    }
                }
            }
            detail is HistoryDetail.Trip -> {
                val selected = detail.row
                Column(body.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val origins = listOfNotNull(
                        if (selected.startedAutomatically) stringResource(R.string.car_started) else null,
                        if (selected.endedAutomatically) stringResource(R.string.car_ended) else null,
                    )
                    if (origins.isNotEmpty()) Text(origins.joinToString(" · "))
                    FullTiles(selected.snapshot(), format.locale, TileStyle.HISTORY)
                    TextButton(onClick = {
                        val removal = TripTracker.archive.remove(selected)
                        scope.launch {
                            try {
                                val removed = removal.await() ?: return@launch
                                if (selection.tripId == selected.id) selection = selection.parent()
                                undoRow = removed.toJson().toString()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                snack.showSnackbar(error)
                            }
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.gps_history_delete))
                    }
                }
            }
            groups.isEmpty() -> Text(stringResource(R.string.gps_history_empty, stringResource(R.string.gps_action_finish)), body)
            else -> {
                val months = remember(groups) { historyMonths(groups) }
                LazyColumn(body, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)) {
                    for ((month, dates) in months) {
                        item(key = "month:$month", contentType = "month") {
                            Text(format.month(month).uppercase(format.locale),
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(dates, key = { "day:$it" }, contentType = { "day" }) { date ->
                            val rows = groups.getValue(date)
                            DayCard(date, rows, format) { selection = openHistoryDay(date, rows) }
                        }
                    }
                }
            }
        }
        SnackbarHost(snack)
    }
}

@Composable
private fun tripCount(count: Int) = pluralStringResource(R.plurals.gps_history_count, count, count)

@Composable
private fun DayCard(date: LocalDate, rows: List<TripEntity>, format: HistoryFormat, onClick: () -> Unit) {
    val total = remember(rows) { daySnapshot(rows) }
    val interval = remember(rows) { dayInterval(rows) }
    val count = tripCount(rows.size)
    val distance = TripFormat.distance(total.distanceM, format.locale)
    val spoken = listOf(format.date(date, true), format.interval(interval, true), count, format.spokenQuantity(distance),
        format.spokenDuration(total.totalMs)).joinToString(", ")
    HistoryCard(description = spoken, title = format.date(date), subtitle = format.interval(interval),
        pills = listOf(count, distance.toString(), TripFormat.clock(total.totalMs)), onClick = onClick,
        leadingIcon = {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.mobile_ic_car_24), contentDescription = null,
                    modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.historyCarIcon)
            }
        })
}

@Composable
private fun TripCard(date: LocalDate, trip: TripEntity, format: HistoryFormat, onClick: () -> Unit) {
    val distance = TripFormat.distance(trip.distanceM, format.locale)
    val average = TripFormat.avgKmh(trip.snapshot().avgTotalKmh, format.locale)
    val top = TripFormat.wholeKmh(trip.maxSpeedMps)
    val spoken = listOf(format.date(date, true), format.interval(trip, true), format.spokenQuantity(distance),
        format.spokenDuration(trip.totalMs), stringResource(R.string.gps_label_avg) + " " + format.spokenQuantity(average),
        stringResource(R.string.gps_label_top_speed) + " " + format.spokenQuantity(top)).joinToString(", ")
    HistoryCard(description = spoken, title = format.interval(trip), onClick = onClick,
        pills = listOf(distance.toString(), TripFormat.clock(trip.totalMs),
            stringResource(R.string.gps_history_pill_avg, average), stringResource(R.string.gps_history_pill_top, top)))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryCard(
    description: String,
    title: String,
    pills: List<String>,
    onClick: () -> Unit,
    subtitle: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = historyColors()
    Surface(shape = RoundedCornerShape(18.dp), color = colors.card, contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, colors.border), shadowElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics { contentDescription = description }
                .heightIn(min = 48.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leadingIcon?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (pill in pills) {
                        Text(pill, Modifier.background(colors.pill, RoundedCornerShape(9.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                    }
                }
            }
            Icon(painterResource(R.drawable.mobile_ic_chevron_right_24), contentDescription = null,
                modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
