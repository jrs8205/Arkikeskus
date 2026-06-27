package org.jrs82.fsclock.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WarningsRepository
import org.jrs82.fsclock.WeatherWarning

private const val KEY_WARNINGS_SCOPE_OWN = "warnings_scope_own"

/** "Sää" → Säävaroitukset: skrollattava sivu, joka näyttää kaikki MeteoAlarm/FMI-varoituskentät.
 *  Oma alue / Koko Suomi -valitsin suodattaa samaa repo-välimuistia (ei lisähakuja). */
@Composable
internal fun WarningsSection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val refresh = LocalRefreshTick.current
    val arki = ArkiTheme.colors
    val repo = remember { WarningsRepository.get() }

    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val l = WarningsRepository.Listener { main.post { tick++ } }
        repo.addListener(l) // kutsuu heti nykyisellä listalla
        repo.refreshIfStale()
        onDispose { repo.removeListener(l) }
    }
    LaunchedEffect(refresh) { if (refresh > 0) repo.refreshNow() }

    var scopeOwn by remember { mutableStateOf(prefs.getBoolean(KEY_WARNINGS_SCOPE_OWN, true)) }
    val all = remember(tick) { repo.getLatest() }
    val homePlace = remember(tick) { SettingsManager.get().homePlace ?: "" }
    val homeRegion = remember(homePlace) { FinnishRegions.regionForPlace(homePlace) }
    val shown = remember(all, scopeOwn, homePlace, homeRegion) {
        if (scopeOwn && homePlace.isNotBlank()) {
            all.filter { WeatherWarningNotifier.areaMatchesHome(it.areaDesc, homePlace, homeRegion) }
        } else {
            all
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Säävaroitukset",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (shown.isNotEmpty()) ArkiPill("${shown.size} voimassa", arki.warning)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = scopeOwn,
                onClick = { scopeOwn = true; prefs.edit().putBoolean(KEY_WARNINGS_SCOPE_OWN, true).apply() },
                label = { Text("Oma alue") },
            )
            FilterChip(
                selected = !scopeOwn,
                onClick = { scopeOwn = false; prefs.edit().putBoolean(KEY_WARNINGS_SCOPE_OWN, false).apply() },
                label = { Text("Koko Suomi") },
            )
        }
        Spacer(Modifier.height(12.dp))
        if (shown.isEmpty()) {
            val msg = when {
                scopeOwn && homePlace.isBlank() ->
                    "Aseta kotipaikka asetuksissa nähdäksesi oman alueesi varoitukset."
                scopeOwn -> "Ei voimassa olevia varoituksia alueellasi ($homePlace)."
                else -> "Ei voimassa olevia säävaroituksia Suomessa."
            }
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.identifier.ifEmpty { it.event + it.areaDesc + it.onsetMs } }) { w ->
                    WarningCard(context, w)
                }
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
