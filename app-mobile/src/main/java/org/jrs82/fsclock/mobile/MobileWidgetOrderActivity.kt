package org.jrs82.fsclock.mobile

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jrs82.fsclock.R
import java.util.Collections

/**
 * Etusivun korttien järjestely raahaamalla (drag & drop kahvasta) ja näkyvyyden valinta
 * checkboxilla — Compose-versio, korvaa aiemman View/RecyclerView+ItemTouchHelper-toteutuksen.
 * Luokkanimi, manifest-merkintä ja asetusten Intent-kytkentä ennallaan. Lukee ja tallentaa
 * SAMAT SharedPreferences-avaimet ([KEY_HOME_ORDER], [KEY_HOME_SHOW_PREFIX]) samalla
 * logiikalla ([allHomeWidgetIds], [homeWidgetTitleForId], [defaultVisibleForId]).
 *
 * Palaute: raahaus alkaa/pudottaa → haptinen napsahdus; pudotuksen JÄLKEEN lyhyt snackbar
 * "Järjestys tallennettu" (näkyvyyden vaihto: "Tallennettu"). Snackbar korvautuu — ei jonoa.
 * Tallennus pysyy automaattisena (ei erillistä Tallenna-painiketta).
 */
class MobileWidgetOrderActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MobileThemeController.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ArkikeskusTheme(dynamicColor = MobileThemeController.dynamicColor(this)) {
                WidgetOrderScreen(onBack = { finish() })
            }
        }
    }
}

private const val ROW_HEIGHT_DP = 64

private class WidgetRowData(val id: String, val title: String, visible: Boolean) {
    var visible by mutableStateOf(visible)
}

/** Kortin oma ikoni (sama jota kortti käyttää etusivulla) — rivin tunnistettavuus. */
private fun homeWidgetIconForId(id: String): Int = when {
    id.startsWith("news:") -> R.drawable.mobile_ic_news_24 // per-lähde-uutiskortti
    else -> when (id) {
        "clock" -> R.drawable.mobile_ic_clock_24
        "holiday" -> R.drawable.mobile_ic_flag_24
        "weather" -> R.drawable.mobile_ic_weather_24
        "electricity" -> R.drawable.mobile_ic_bolt_24
        "warnings" -> R.drawable.mobile_ic_warning_24
        "sensors" -> R.drawable.mobile_ic_thermometer_24
        "traffic" -> R.drawable.mobile_ic_transit_tram
        "news", "news_foreign" -> R.drawable.mobile_ic_news_24
        "transit" -> R.drawable.mobile_ic_bus_24
        else -> R.drawable.mobile_ic_dashboard_24
    }
}

/** Tallennettu järjestys + kaikki tunnetut kortit jotka puuttuvat (sama kuin View-versio). */
private fun loadWidgetOrder(prefs: SharedPreferences): List<String> {
    val known = allHomeWidgetIds(prefs)
    val order = ArrayList<String>()
    prefs.getString(KEY_HOME_ORDER, null)?.split(",")?.forEach { token ->
        val id = token.trim()
        if (known.contains(id) && !order.contains(id)) order.add(id)
    }
    known.forEach { if (!order.contains(it)) order.add(it) }
    return order
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetOrderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val rows = remember {
        mutableStateListOf<WidgetRowData>().apply {
            loadWidgetOrder(prefs).forEach { id ->
                add(
                    WidgetRowData(
                        id,
                        homeWidgetTitleForId(prefs, id),
                        prefs.getBoolean(KEY_HOME_SHOW_PREFIX + id, defaultVisibleForId(id)),
                    ),
                )
            }
        }
    }

    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarJob by remember { mutableStateOf<Job?>(null) }

    fun persist() {
        val editor = prefs.edit()
        editor.putString(KEY_HOME_ORDER, rows.joinToString(",") { it.id })
        rows.forEach { editor.putBoolean(KEY_HOME_SHOW_PREFIX + it.id, it.visible) }
        editor.apply()
    }

    // Lyhyt (~1,5 s) snackbar joka KORVAA edellisen (ei jonoa): peruu aiemman työn ja näyttää uuden.
    fun showSaved(message: String) {
        snackbarJob?.cancel()
        snackbarJob = scope.launch {
            val shown = launch {
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Indefinite)
            }
            delay(1500)
            shown.cancel()
        }
    }

    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT_DP.dp.toPx() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var didReorder by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Etusivun kortit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.mobile_ic_arrow_back), contentDescription = "Takaisin")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            Text(
                "Vedä kahvasta järjestääksesi. Valinta näyttää kortin etusivulla.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            )
            rows.forEachIndexed { index, row ->
                key(row.id) {
                    WidgetOrderRow(
                        row = row,
                        dragging = index == draggingIndex,
                        offsetY = if (index == draggingIndex) dragOffset else 0f,
                        onDragStart = {
                            draggingIndex = rows.indexOf(row)
                            dragOffset = 0f
                            didReorder = false
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onDragBy = { dy ->
                            dragOffset += dy
                            // Raahaus: vaihda viereiseen kun ylitetään reilu puolikas rivistä;
                            // dragOffset korjataan rivin verran → kortti pysyy sormen alla.
                            while (dragOffset > rowHeightPx * 0.6f && draggingIndex < rows.lastIndex) {
                                Collections.swap(rows, draggingIndex, draggingIndex + 1)
                                draggingIndex++
                                dragOffset -= rowHeightPx
                                didReorder = true
                            }
                            while (dragOffset < -rowHeightPx * 0.6f && draggingIndex > 0) {
                                Collections.swap(rows, draggingIndex, draggingIndex - 1)
                                draggingIndex--
                                dragOffset += rowHeightPx
                                didReorder = true
                            }
                        },
                        onDragEnd = {
                            val reordered = didReorder
                            draggingIndex = -1
                            dragOffset = 0f
                            didReorder = false
                            // Snackbar + vahvempi napsahdus VAIN kun järjestys oikeasti muuttui
                            // (pelkkä kahvan kosketus ilman siirtoa ei tallenna turhaan).
                            if (reordered) {
                                persist()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showSaved("Järjestys tallennettu")
                            }
                        },
                        onToggle = { v ->
                            row.visible = v
                            persist()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showSaved("Tallennettu")
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WidgetOrderRow(
    row: WidgetRowData,
    dragging: Boolean,
    offsetY: Float,
    onDragStart: () -> Unit,
    onDragBy: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    // rememberUpdatedState → pointerInput(Unit)-ele käyttää aina tuoreimpia callbackeja
    // (rivit vaihtavat paikkaa kesken eleen).
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    // Nostettu kortti = vaaleampi pinta; piilotettu = haaleampi pinta; muuten normaali korttiväri.
    val containerColor = when {
        dragging -> MaterialTheme.colorScheme.surfaceBright
        row.visible -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    // Piilotettu rivi himmenee (paitsi raahattaessa, jolloin se on selvästi nostettu).
    val contentAlpha = if (row.visible || dragging) 1f else 0.55f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(ROW_HEIGHT_DP.dp)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = offsetY
                if (dragging) {
                    scaleX = 1.03f
                    scaleY = 1.03f
                }
            },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Kahva = ainoa raahauksen tartuntakohta (lista vierittyy normaalisti muualta).
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { currentOnDragStart() },
                            onDrag = { change, amount ->
                                change.consume()
                                currentOnDragBy(amount.y)
                            },
                            onDragEnd = { currentOnDragEnd() },
                            onDragCancel = { currentOnDragEnd() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.mobile_ic_drag_indicator_24),
                    contentDescription = "Raahaa järjestääksesi",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Kortin oma ikoni.
            Icon(
                painterResource(homeWidgetIconForId(row.id)),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                row.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Checkbox(checked = row.visible, onCheckedChange = onToggle)
        }
    }
}
