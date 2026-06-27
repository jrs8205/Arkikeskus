package org.jrs82.fsclock.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.FmiCounties
import org.jrs82.fsclock.FmiWarningsRepository
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WeatherWarning

private const val KEY_WARN5_REGION = "warn5_region"

/** "Sää" → Säävaroitukset: FMI:n 5 vrk -näkymä. Valitse päivä (ylärivi) + maakunta (pudotusvalikko),
 *  näytä valitun päivän + maakunnan varoitukset (Koko Suomi = kaikki maakunnat koottuna). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun WarningsSection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val refresh = LocalRefreshTick.current
    val repo = remember { FmiWarningsRepository.get() }

    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val l = FmiWarningsRepository.Listener { main.post { tick++ } }
        repo.addListener(l)
        repo.refreshIfStale()
        onDispose { repo.removeListener(l) }
    }
    LaunchedEffect(refresh) { if (refresh > 0) repo.refreshNow() }

    val all = remember(tick) { repo.getLatest() }
    val days = remember(tick) { daysFrom(System.currentTimeMillis()) }
    var dayIndex by remember { mutableStateOf(0) }

    // maakuntavaihtoehdot: "Koko Suomi" + 19 maakuntaa
    val regions = remember { listOf("Koko Suomi") + FmiCounties.ALL_REGIONS }
    val homeRegion = remember { FinnishRegions.regionForPlace(SettingsManager.get().homePlace) }
    var region by remember {
        val saved = prefs.getString(KEY_WARN5_REGION, null)
        val initial = saved?.takeIf { it in regions } ?: homeRegion?.takeIf { it in regions } ?: "Koko Suomi"
        mutableStateOf(initial)
    }
    var menuOpen by remember { mutableStateOf(false) }

    val day = days.getOrNull(dayIndex) ?: days.first()
    val shown = remember(all, dayIndex, region, tick) {
        warningsFor(all, day, if (region == "Koko Suomi") null else region)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Säävaroitukset", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        // Päivärivi
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEachIndexed { i, d ->
                FilterChip(selected = i == dayIndex, onClick = { dayIndex = i }, label = { Text(d.label) })
            }
        }
        Spacer(Modifier.height(10.dp))
        // Maakunta-pudotusvalikko
        ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
            OutlinedTextField(
                value = region, onValueChange = {}, readOnly = true,
                label = { Text("Alue") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                regions.forEach { r ->
                    DropdownMenuItem(text = { Text(r) }, onClick = {
                        region = r; menuOpen = false
                        prefs.edit().putString(KEY_WARN5_REGION, r).apply()
                    })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (shown.isEmpty()) {
            Text(
                "Ei varoituksia — $region, ${day.label.lowercase()}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.event + it.areaDesc + it.onsetMs }) { w -> WarningCard(context, w) }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun WarningCard(context: Context, w: WeatherWarning) {
    val arki = ArkiTheme.colors
    val levelColor = Color(w.level.color)
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArkiIconChip(painterResource(awarenessIconRes(w.awarenessType)), levelColor)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (w.event.isNotEmpty()) w.event else w.awarenessType.fiName.ifEmpty { "Varoitus" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (w.areaDesc.isNotEmpty()) {
                        Text(
                            w.areaDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (w.level.fiName.isNotEmpty()) ArkiPill(w.level.fiName, levelColor)
            }
            if (w.marine) {
                Spacer(Modifier.height(8.dp))
                ArkiPill("Veneily", arki.weatherAccent)
            }
            val period = warningPeriod(w.onsetMs, w.expiresMs)
            if (period.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(period, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            val bodyText = if (w.details.detailText.length > w.description.length)
                w.details.detailText else w.description
            if (bodyText.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(bodyText, style = MaterialTheme.typography.bodyMedium)
            }
            if (w.details.probabilityPct >= 0 || w.details.physicalText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val highlight = buildList {
                    if (w.details.physicalText.isNotEmpty()) add(w.details.physicalText)
                    if (w.details.probabilityPct >= 0) add("Todennäköisyys ${w.details.probabilityPct} %")
                }.joinToString("  ·  ")
                Text(
                    highlight,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ArkiTheme.colors.weatherAccent,
                )
            }
            val meta = listOf(
                severityFi(w.severity).let { if (it.isNotEmpty()) "Vakavuus: $it" else "" },
                certaintyFi(w.certainty).let { if (it.isNotEmpty()) "Varmuus: $it" else "" },
                urgencyFi(w.urgency).let { if (it.isNotEmpty()) "Kiireellisyys: $it" else "" },
            ).filter { it.isNotEmpty() }.joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (w.senderName.isNotEmpty()) w.senderName else "Ilmatieteen laitos",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (w.web.isNotEmpty()) {
                    Text(
                        "Lisätietoja",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { openUrl(context, w.web) },
                    )
                }
            }
        }
    }
}
