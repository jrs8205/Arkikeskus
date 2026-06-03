package org.jrs82.fsclock.mobile

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.R

/**
 * Compose-asetusnäkymä (PILOTTI / toolchain-todistus). Tämä on välivaiheen versio: se todistaa
 * että Compose + Material 3 kääntyy ja toimii (TopAppBar, kortit, kytkimet, dialogi, teemanvaihto).
 * Täysi versio (kaikki asetukset + omat syötteet + Ruuvi-skannaus) tulee seuraavassa kierroksessa.
 * Asetukset luetaan/kirjoitetaan SAMOIHIN SharedPreferences-avaimiin, jotta muu sovellus toimii.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Asetukset") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.mobile_ic_arrow_back),
                            contentDescription = "Takaisin",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader("Etusivu") }
            item {
                SettingsCard {
                    ClickableRow(
                        title = "Etusivun widgetit",
                        subtitle = "Järjestä raahaamalla ja valitse näkyvät",
                    ) {
                        context.startActivity(Intent(context, MobileWidgetOrderActivity::class.java))
                    }
                }
            }

            item { SectionHeader("Sovellus") }
            item {
                SettingsCard {
                    // Teema
                    var themeMode by remember {
                        mutableStateOf(
                            prefs.getString(MobileThemeController.KEY_THEME_MODE, MobileThemeController.VALUE_SYSTEM)
                                ?: MobileThemeController.VALUE_SYSTEM,
                        )
                    }
                    var showThemeDialog by remember { mutableStateOf(false) }
                    ClickableRow(title = "Teema", subtitle = themeLabel(themeMode)) {
                        showThemeDialog = true
                    }
                    if (showThemeDialog) {
                        ThemeDialog(
                            current = themeMode,
                            onDismiss = { showThemeDialog = false },
                            onPick = { value ->
                                showThemeDialog = false
                                themeMode = value
                                prefs.edit().putString(MobileThemeController.KEY_THEME_MODE, value).apply()
                                // Pakottaa yötilan ja recreatee Activityn → Compose renderöityy uudella teemalla.
                                MobileThemeController.applyValue(value)
                            },
                        )
                    }

                    RowDivider()

                    // Halvan sähkön ilmoitus
                    var notice by remember {
                        mutableStateOf(prefs.getBoolean(MobileThemeController.KEY_CHEAP_ELECTRICITY_NOTICE, true))
                    }
                    SwitchRow(
                        title = "Halvan sähkön ilmoitus",
                        subtitle = "Näytä huomio kun sähkö on halpaa",
                        checked = notice,
                    ) {
                        notice = it
                        prefs.edit().putBoolean(MobileThemeController.KEY_CHEAP_ELECTRICITY_NOTICE, it).apply()
                    }
                }
            }
        }
    }
}

private fun themeLabel(value: String): String = when (value) {
    MobileThemeController.VALUE_LIGHT -> "Vaalea"
    MobileThemeController.VALUE_DARK -> "Tumma AMOLED"
    else -> "Järjestelmän mukaan"
}

@Composable
private fun ThemeDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val options = listOf(
        MobileThemeController.VALUE_SYSTEM to "Järjestelmän mukaan",
        MobileThemeController.VALUE_LIGHT to "Vaalea",
        MobileThemeController.VALUE_DARK to "Tumma AMOLED",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Sulje") } },
        title = { Text("Teema") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == current, onClick = { onPick(value) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun ClickableRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RowDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant,
        thickness = 1.dp,
    )
}
