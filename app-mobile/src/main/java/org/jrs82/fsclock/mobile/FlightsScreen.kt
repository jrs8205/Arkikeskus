package org.jrs82.fsclock.mobile

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import org.jrs82.fsclock.R

private fun timeHm(ms: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    fmt.timeZone = java.util.TimeZone.getTimeZone("Europe/Helsinki")
    return fmt.format(java.util.Date(ms))
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun FlightsSection() {
    val refresh = LocalRefreshTick.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val l = FlightsRepository.Listener { main.post { tick++ } }
        FlightsRepository.addListener(l)
        FlightsRepository.refreshIfStale()
        onDispose { FlightsRepository.removeListener(l) }
    }
    LaunchedEffect(refresh) { if (refresh > 0) FlightsRepository.refreshNow() }
    LaunchedEffect(Unit) { while (true) { delay(60_000); FlightsRepository.refreshIfStale() } }

    val data = remember(tick) { FlightsRepository.getLatest() }
    var airport by rememberSaveable { mutableStateOf("HEL") }
    var dir by rememberSaveable { mutableStateOf(FlightDir.DEP) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val list = remember(data, airport, dir, query, tick) {
        if (query.isNotBlank()) FlightsFilter.search(data, query)
        else FlightsFilter.board(data, airport, dir)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lennot", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val updated = data?.updatedMs ?: 0L
            if (updated > 0L) {
                Text("Päivitetty ${timeHm(updated)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { FlightsRepository.refreshNow() }) {
                Icon(painterResource(R.drawable.mobile_ic_refresh_24), contentDescription = "Päivitä")
            }
        }
        Spacer(Modifier.height(6.dp))
        if (query.isBlank()) {
            ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                OutlinedTextField(
                    value = "${FinaviaAirports.name(airport)} ($airport)",
                    onValueChange = {}, readOnly = true,
                    label = { Text("Lentokenttä") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    FinaviaAirports.ALL.forEach { ap ->
                        DropdownMenuItem(text = { Text("${ap.name} (${ap.iata})") }, onClick = { airport = ap.iata; menuOpen = false })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = dir == FlightDir.DEP, onClick = { dir = FlightDir.DEP }, label = { Text("Lähtevät") })
                FilterChip(selected = dir == FlightDir.ARR, onClick = { dir = FlightDir.ARR }, label = { Text("Saapuvat") })
            }
            Spacer(Modifier.height(8.dp))
        }
        SearchTextField(value = query, onValueChange = { query = it }, placeholder = "Hae lentonumerolla (esim. AY1731)", onClear = { query = "" })
        if (query.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("Haku kattaa koko Suomen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        when {
            data == null -> Text("Ladataan lentoja…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text(
                if (query.isNotBlank()) "Ei osumia haulle \"$query\"."
                else "Ei lentoja — ${FinaviaAirports.name(airport)}, ${if (dir == FlightDir.DEP) "lähtevät" else "saapuvat"}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.dir.name + it.airport + it.flightNo + it.scheduledMs }) { fl -> FlightCard(fl, showAirport = query.isNotBlank()) }
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Tiedot: Finavia", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun FlightCard(f: Flight, showAirport: Boolean) {
    val arki = ArkiTheme.colors
    val cat = FlightDisplay.category(f)
    val color = when (cat) {
        FlightStatusCat.CANCELLED -> Color(0xFFD32F2F)
        FlightStatusCat.DELAYED -> Color(0xFFE08A00)
        FlightStatusCat.ATTENTION -> arki.weatherAccent
        FlightStatusCat.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
        FlightStatusCat.ON_TIME -> MaterialTheme.colorScheme.primary
    }
    val dimmed = cat == FlightStatusCat.COMPLETED
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp).alpha(if (dimmed) 0.6f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    val timeChanged = f.delayMin != 0L && (f.actualMs != null || f.estimatedMs != null)
                    if (timeChanged) {
                        Text(timeHm(f.scheduledMs), style = MaterialTheme.typography.bodySmall,
                            textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(timeHm(f.effectiveMs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = if (cat == FlightStatusCat.DELAYED) color else MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.flightNo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val place = if (f.city.isNotBlank()) "${f.city} (${f.otherAirport})" else f.otherAirport
                    Text((if (f.dir == FlightDir.ARR) "Saapuu: " else "Määränpää: ") + place,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showAirport) {
                        Text("Kenttä: ${FinaviaAirports.name(f.airport)} (${f.airport})",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (f.status.isNotBlank()) ArkiPill(f.status, color)
            }
            val details = buildList {
                f.gate?.let { add("Portti $it") }
                f.belt?.let { add("Hihna $it") }
                f.checkin?.let { add("Lähtöselvitys $it") }
                f.stand?.let { add("Asemapaikka $it") }
                f.aircraft?.let { add("Kone $it") }
            }
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(details.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            }
            if (f.codeshares.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Myös: ${f.codeshares.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
