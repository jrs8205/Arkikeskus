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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.R
import java.util.Collections

/**
 * Etusivun korttien järjestely raahaamalla (drag & drop kahvasta) ja näkyvyyden valinta
 * checkboxilla — Compose-versio, korvaa aiemman View/RecyclerView+ItemTouchHelper-toteutuksen.
 * Luokkanimi, manifest-merkintä ja asetusten Intent-kytkentä ennallaan. Lukee ja tallentaa
 * SAMAT SharedPreferences-avaimet ([KEY_HOME_ORDER], [KEY_HOME_SHOW_PREFIX]) samalla
 * logiikalla ([allHomeWidgetIds], [homeWidgetTitleForId], [defaultVisibleForId]).
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

private const val ROW_HEIGHT_DP = 60

private class WidgetRowData(val id: String, val title: String, visible: Boolean) {
    var visible by mutableStateOf(visible)
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

    fun persist() {
        val editor = prefs.edit()
        editor.putString(KEY_HOME_ORDER, rows.joinToString(",") { it.id })
        rows.forEach { editor.putBoolean(KEY_HOME_SHOW_PREFIX + it.id, it.visible) }
        editor.apply()
    }

    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT_DP.dp.toPx() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            rows.forEachIndexed { index, row ->
                key(row.id) {
                    WidgetOrderRow(
                        row = row,
                        dragging = index == draggingIndex,
                        offsetY = if (index == draggingIndex) dragOffset else 0f,
                        onDragStart = {
                            draggingIndex = rows.indexOf(row)
                            dragOffset = 0f
                        },
                        onDragBy = { dy ->
                            dragOffset += dy
                            // Raahaus: vaihda viereiseen kun ylitetään reilu puolikas rivistä;
                            // dragOffset korjataan rivin verran → kortti pysyy sormen alla.
                            while (dragOffset > rowHeightPx * 0.6f && draggingIndex < rows.lastIndex) {
                                Collections.swap(rows, draggingIndex, draggingIndex + 1)
                                draggingIndex++
                                dragOffset -= rowHeightPx
                            }
                            while (dragOffset < -rowHeightPx * 0.6f && draggingIndex > 0) {
                                Collections.swap(rows, draggingIndex, draggingIndex - 1)
                                draggingIndex--
                                dragOffset += rowHeightPx
                            }
                        },
                        onDragEnd = {
                            draggingIndex = -1
                            dragOffset = 0f
                            persist()
                        },
                        onToggle = { v ->
                            row.visible = v
                            persist()
                        },
                    )
                }
            }
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(ROW_HEIGHT_DP.dp)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = offsetY },
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    painterResource(R.drawable.mobile_ic_drag_handle_24),
                    contentDescription = "Raahaa järjestääksesi",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                row.title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Checkbox(checked = row.visible, onCheckedChange = onToggle)
        }
    }
}
