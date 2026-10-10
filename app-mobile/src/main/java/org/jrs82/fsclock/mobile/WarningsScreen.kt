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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.FmiCounties
import org.jrs82.fsclock.FmiWarningsClient
import org.jrs82.fsclock.FmiWarningsRepository
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WeatherWarning

private const val KEY_WARN5_REGION = "warn5_region"
// Koko maan valinnan tunniste prefsissä (vanha tallennettu arvo); näyttöteksti resursseista.
private const val REGION_ALL = "Koko Suomi"

/** "Sää" → Säävaroitukset: FMI:n 5 vrk -näkymä. Valitse päivä (ylärivi) + maakunta (pudotusvalikko),
 *  näytä valitun päivän + maakunnan varoitukset (Koko Suomi = kaikki maakunnat koottuna). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun WarningsSection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val refresh = LocalRefreshTick.current
    val repo = remember { FmiWarningsRepository.get() }
    val locale = AppLocale.current(context)
    val infoKey = AppLocale.fmiInfoKey(locale)
    val datePattern = stringResource(R.string.warning_date_pattern)

    var tick by remember { mutableStateOf(0) }
    DisposableEffect(infoKey) {
        val main = Handler(Looper.getMainLooper())
        val l = FmiWarningsRepository.Listener { main.post { tick++ } }
        repo.addListener(l)
        repo.refreshIfStale(infoKey)
        onDispose { repo.removeListener(l) }
    }
    LaunchedEffect(refresh) { if (refresh > 0) repo.refreshNow(infoKey) }

    val all = remember(tick, infoKey) { repo.getLatest(infoKey) }
    val days = remember(tick, datePattern, locale) { daysFrom(System.currentTimeMillis(), datePattern, locale) }
    var dayIndex by remember { mutableStateOf(0) }

    // maakuntavaihtoehdot: koko maa + 19 maakuntaa (suomenkieliset tunnisteet, näyttönimi erikseen)
    val regions = remember { listOf(REGION_ALL) + FmiCounties.ALL_REGIONS }
    val homeRegion = remember { FinnishRegions.regionForPlace(SettingsManager.get().homePlace) }
    var region by remember {
        val saved = prefs.getString(KEY_WARN5_REGION, null)
        val initial = saved?.takeIf { it in regions } ?: homeRegion?.takeIf { it in regions } ?: REGION_ALL
        mutableStateOf(initial)
    }
    var menuOpen by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.warning_region_all)
    fun regionLabel(r: String): String = if (r == REGION_ALL) allLabel else regionDisplayName(context, r)

    val day = days.getOrNull(dayIndex) ?: days.first()
    val shown = remember(all, dayIndex, region, tick) {
        warningsFor(all, day, if (region == REGION_ALL) null else region)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.warning_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        // Päivärivi
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEachIndexed { i, d ->
                FilterChip(selected = i == dayIndex, onClick = { dayIndex = i },
                    label = { Text(d.chipLabel().resolve(context)) })
            }
        }
        Spacer(Modifier.height(10.dp))
        // Maakunta-pudotusvalikko
        ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
            OutlinedTextField(
                value = regionLabel(region), onValueChange = {}, readOnly = true,
                label = { Text(stringResource(R.string.warning_region_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                regions.forEach { r ->
                    DropdownMenuItem(text = { Text(regionLabel(r)) }, onClick = {
                        region = r; menuOpen = false
                        prefs.edit().putString(KEY_WARN5_REGION, r).apply()
                    })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.warning_none, regionLabel(region), day.inlineLabel().resolve(context)),
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
    val locale = AppLocale.current(context)
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArkiIconChip(painterResource(awarenessIconRes(w.awarenessType)), levelColor)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        // event = FMI:n warning_context; tuntematon laji saa yleisnimen
                        stringResource(FmiWarningsClient.eventFor(w.event).nameRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (w.areaDesc.isNotEmpty()) {
                        Text(
                            localizedAreaDesc(context, w.areaDesc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (w.level.nameRes != 0) ArkiPill(stringResource(w.level.nameRes), levelColor)
            }
            if (w.marine) {
                Spacer(Modifier.height(8.dp))
                ArkiPill(stringResource(R.string.warning_marine), arki.weatherAccent)
            }
            val period = warningPeriod(w.onsetMs, w.expiresMs, stringResource(R.string.warning_period_pattern), locale)
                ?.resolve(context).orEmpty()
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
            val highlight = listOfNotNull(physicalText(w, locale), probabilityText(w.details.probabilityPct))
                .joinToString("  ·  ") { it.resolve(context) }
            if (highlight.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    highlight,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ArkiTheme.colors.weatherAccent,
                )
            }
            val meta = listOf(
                R.string.warning_meta_severity to severityRes(w.severity),
                R.string.warning_meta_certainty to certaintyRes(w.certainty),
                R.string.warning_meta_urgency to urgencyRes(w.urgency),
            ).mapNotNull { (label, value) -> value?.let { context.getString(label, context.getString(it)) } }
                .joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    w.senderName.ifEmpty { stringResource(R.string.warning_sender_fmi) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (w.web.isNotEmpty()) {
                    Text(
                        stringResource(R.string.warning_more_info),
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
