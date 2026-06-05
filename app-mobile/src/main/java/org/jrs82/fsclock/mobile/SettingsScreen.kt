package org.jrs82.fsclock.mobile

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.ruuvi.RuuviRepository
import org.jrs82.fsclock.ruuvi.RuuviSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Arkikeskuksen asetusnäkymä (Jetpack Compose + Material 3). Korvaa vanhan
 * [MobileSettingsFragment] + mobile_preferences.xml -parin. Kaikki asetukset luetaan ja
 * kirjoitetaan SAMOIHIN SharedPreferences-avaimiin, joita muu (yhä View-pohjainen) sovellus
 * lukee, joten käytös säilyy identtisenä — vain ulkoasu uudistuu.
 *
 * Osiot vastaavat vanhaa preferences-XML:ää: Sää (auto-sijainti), Etusivu (widget-järjestys),
 * Uutislähteet (10 kytkintä), Omat uutissyötteet (lisää/muokkaa/poista), Ruuvi-anturit
 * (BLE-skannaus + 3 slottia + nimet), Pörssisähkö (huomio/raja/ajankohta), Sovellus
 * (päivitysväli/teema/versiotiedot).
 */

private val FI = Locale("fi", "FI")

private val THEME_OPTIONS = listOf(
    MobileThemeController.VALUE_SYSTEM to "Järjestelmän mukaan",
    MobileThemeController.VALUE_LIGHT to "Vaalea",
    MobileThemeController.VALUE_DARK to "Tumma AMOLED",
)
private val CHEAP_MODE_OPTIONS = listOf(
    MobileThemeController.CHEAP_MODE_CURRENT to "Nykyinen vartti",
    MobileThemeController.CHEAP_MODE_REMAINING_DAY to "Koko loppupäivä",
    MobileThemeController.CHEAP_MODE_ALL_DAY to "Koko päivä",
)
private val INTERVAL_OPTIONS = listOf(
    "10" to "10 minuuttia",
    "15" to "15 minuuttia",
    "30" to "30 minuuttia",
    "60" to "60 minuuttia",
    "120" to "120 minuuttia",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val repo = remember { RuuviRepository.get(context) }

    // Kasvatetaan kun jokin näkymän ulkopuolinen tila muuttuu (slot-liitos, anturin nimi,
    // oma syöte) → keyatut remember-lukijat lukevat arvot uudelleen.
    var refreshTick by remember { mutableStateOf(0) }

    // --- Ruuvi-skannaus: dialogin näyttötila + lupavirta ---
    var scanTargetSlot by remember { mutableStateOf<String?>(null) }
    var showScanDialog by remember { mutableStateOf(false) }
    var pendingScanSlot by remember { mutableStateOf<String?>(null) }

    val showScanFor: (String?) -> Unit = { slot ->
        scanTargetSlot = slot
        showScanDialog = true
    }
    val scanPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (isBluetoothOn(context)) {
                repo.start()
                showScanFor(pendingScanSlot)
            } else {
                toast(context, "Bluetooth on pois päältä. Kytke se päälle ja yritä uudelleen.")
            }
        } else {
            toast(context, "Bluetooth-skannauslupa tarvitaan antureiden etsimiseen.")
        }
    }
    val requestScan: (String?) -> Unit = { slot ->
        val perm = bluetoothScanPermission()
        if (hasPermission(context, perm)) {
            if (isBluetoothOn(context)) {
                repo.start()
                showScanFor(slot)
            } else {
                toast(context, "Bluetooth on pois päältä. Kytke se päälle ja yritä uudelleen.")
            }
        } else {
            pendingScanSlot = slot
            scanPermLauncher.launch(perm)
        }
    }

    // --- Sää: automaattinen sijainti + lupavirta ---
    var autoLocation by remember {
        mutableStateOf(prefs.getBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true))
    }
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val precise = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!precise) SettingsManager.get().clearHomeCoordinates()
        prefs.edit()
            .putBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, true)
            .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, precise)
            .remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME)
            .apply()
        autoLocation = precise
        if (precise) resetAutoLocationThrottle()
        toast(
            context,
            if (precise) "Automaattinen sijainti on käytössä."
            else "Tarkkaa sijaintilupaa ei annettu. Kaupunkihaku toimii normaalisti.",
        )
    }

    // --- Dialogien näkyvyystilat ---
    var showThemeDialog by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showThresholdDialog by remember { mutableStateOf(false) }
    var editSensorSlot by remember { mutableStateOf<String?>(null) }
    var addFeed by remember { mutableStateOf(false) }
    var editFeed by remember { mutableStateOf<NewsFeed?>(null) }

    // --- Luettavat arvot (keyattu refreshTickillä) ---
    val themeMode = prefs.getString(MobileThemeController.KEY_THEME_MODE, MobileThemeController.VALUE_SYSTEM)
        ?: MobileThemeController.VALUE_SYSTEM
    val cheapMode = prefs.getString(MobileThemeController.KEY_CHEAP_ELECTRICITY_MODE, MobileThemeController.CHEAP_MODE_ALL_DAY)
        ?: MobileThemeController.CHEAP_MODE_ALL_DAY
    val interval = prefs.getString(MobileThemeController.KEY_UPDATE_INTERVAL_MINUTES, MobileThemeController.DEFAULT_UPDATE_INTERVAL_MINUTES)
        ?: MobileThemeController.DEFAULT_UPDATE_INTERVAL_MINUTES
    val threshold = prefs.getString(MobileThemeController.KEY_CHEAP_ELECTRICITY_THRESHOLD, MobileThemeController.DEFAULT_CHEAP_ELECTRICITY_THRESHOLD)
        ?: MobileThemeController.DEFAULT_CHEAP_ELECTRICITY_THRESHOLD
    val builtinFeeds = remember { NewsFeedStore.allFeeds(prefs).filter { it.builtin } }
    val customFeeds = remember(refreshTick) { NewsFeedStore.customFeeds(prefs) }

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
            // ---------- Sää ----------
            item { SectionHeader("Sää") }
            item {
                SettingsCard {
                    SwitchRow(
                        title = "Automaattinen sijainti",
                        subtitle = "Päivitä paikkakunta laitteen sijainnin perusteella, jos lupa on annettu",
                        checked = autoLocation,
                    ) { enable ->
                        if (enable) {
                            if (hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                                autoLocation = true
                                prefs.edit()
                                    .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true)
                                    .apply()
                                resetAutoLocationThrottle()
                            } else {
                                prefs.edit()
                                    .putBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, true)
                                    .apply()
                                locationPermLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            }
                        } else {
                            SettingsManager.get().clearHomeCoordinates()
                            prefs.edit()
                                .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, false)
                                .remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME)
                                .apply()
                            autoLocation = false
                        }
                    }
                }
            }

            // ---------- Etusivu ----------
            item { SectionHeader("Etusivu") }
            item {
                SettingsCard {
                    ClickableRow(
                        title = "Etusivun widgetit",
                        subtitle = "Valitse näkyvät kortit ja järjestä ne (kello, sää, sähkö, anturit, uutiset, lähilähdöt…)",
                    ) {
                        context.startActivity(Intent(context, HomeCustomizeActivity::class.java))
                    }
                }
            }

            // ---------- Uutislähteet ----------
            item { SectionHeader("Uutislähteet") }
            item {
                SettingsCard {
                    builtinFeeds.forEachIndexed { index, feed ->
                        PrefSwitchRow(prefs, feed.enabledKey(), feed.name, default = true)
                        if (index < builtinFeeds.lastIndex) RowDivider()
                    }
                }
            }

            // ---------- Omat uutissyötteet ----------
            item { SectionHeader("Omat uutissyötteet") }
            item {
                SettingsCard {
                    customFeeds.forEach { feed ->
                        ClickableRow(title = feed.name, subtitle = feed.url) { editFeed = feed }
                        RowDivider()
                    }
                    ClickableRow(
                        title = "Lisää oma syöte",
                        subtitle = "Lisää oma RSS- tai Atom-syöte (nimi + osoite)",
                    ) { addFeed = true }
                }
            }

            // ---------- Ruuvi-anturit ----------
            item { SectionHeader("Ruuvi-anturit") }
            item {
                SettingsCard {
                    ClickableRow(
                        title = "Etsi antureita (Bluetooth)",
                        subtitle = "Skannaa lähellä olevat RuuviTagit ja liitä huoneeseen",
                    ) { requestScan(null) }
                }
            }
            items3Sensors().forEach { slot ->
                item {
                    val name = remember(refreshTick) { sensorName(prefs, slot) }
                    val mac = remember(refreshTick) { SettingsManager.get().getRuuviMac(slot) }
                    val macSummary = remember(refreshTick) { slotSummary(repo, mac) }
                    SettingsCard {
                        ClickableRow(title = "Anturin nimi", subtitle = name) { editSensorSlot = slot }
                        RowDivider()
                        ClickableRow(title = "Liitetty Ruuvi", subtitle = macSummary) { requestScan(slot) }
                    }
                }
            }

            // ---------- Pörssisähkö ----------
            item { SectionHeader("Pörssisähkö") }
            item {
                SettingsCard {
                    PrefSwitchRow(
                        prefs,
                        MobileThemeController.KEY_CHEAP_ELECTRICITY_NOTICE,
                        "Halvan sähkön huomio",
                        subtitle = "Näytä huomio etusivulla kun sähkö on halpaa",
                        default = true,
                    )
                    RowDivider()
                    ClickableRow(
                        title = "Halvan sähkön raja",
                        subtitle = "$threshold c/kWh (ALV 0 %)",
                    ) { showThresholdDialog = true }
                    RowDivider()
                    ClickableRow(
                        title = "Milloin huomio näytetään",
                        subtitle = labelFor(CHEAP_MODE_OPTIONS, cheapMode),
                    ) { showModeDialog = true }
                }
            }

            // ---------- Sovellus ----------
            item { SectionHeader("Sovellus") }
            item {
                SettingsCard {
                    ClickableRow(
                        title = "Automaattinen päivitysväli",
                        subtitle = labelFor(INTERVAL_OPTIONS, interval),
                    ) { showIntervalDialog = true }
                    RowDivider()
                    ClickableRow(title = "Teema", subtitle = labelFor(THEME_OPTIONS, themeMode)) {
                        showThemeDialog = true
                    }
                    RowDivider()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        DynamicColorRow(prefs, context)
                        RowDivider()
                    }
                    InfoRow(title = "Viimeisin sääpäivitys", value = lastUpdateText())
                }
            }

            // ---------- Tietoja sovelluksesta (versio + päivitys + GitHub) ----------
            item { AppInfoSection(context) }
        }
    }

    // ---------- Dialogit ----------
    if (showThemeDialog) {
        RadioDialog(
            title = "Teema",
            options = THEME_OPTIONS,
            current = themeMode,
            onPick = { value ->
                showThemeDialog = false
                prefs.edit().putString(MobileThemeController.KEY_THEME_MODE, value).apply()
                // Pakottaa yötilan + recreatee Activityn → Compose renderöityy uudella teemalla.
                MobileThemeController.applyValue(value)
            },
            onDismiss = { showThemeDialog = false },
        )
    }
    if (showModeDialog) {
        RadioDialog(
            title = "Milloin huomio näytetään",
            options = CHEAP_MODE_OPTIONS,
            current = cheapMode,
            onPick = { value ->
                showModeDialog = false
                prefs.edit().putString(MobileThemeController.KEY_CHEAP_ELECTRICITY_MODE, value).apply()
                refreshTick++
            },
            onDismiss = { showModeDialog = false },
        )
    }
    if (showIntervalDialog) {
        RadioDialog(
            title = "Automaattinen päivitysväli",
            options = INTERVAL_OPTIONS,
            current = interval,
            onPick = { value ->
                showIntervalDialog = false
                prefs.edit().putString(MobileThemeController.KEY_UPDATE_INTERVAL_MINUTES, value).apply()
                refreshTick++
            },
            onDismiss = { showIntervalDialog = false },
        )
    }
    if (showThresholdDialog) {
        TextFieldDialog(
            title = "Halvan sähkön raja",
            initial = threshold,
            label = "c/kWh (ALV 0 %)",
            keyboardType = KeyboardType.Decimal,
            onSave = { value ->
                showThresholdDialog = false
                val cleaned = value.trim().replace(',', '.')
                if (cleaned.toDoubleOrNull() != null) {
                    prefs.edit().putString(MobileThemeController.KEY_CHEAP_ELECTRICITY_THRESHOLD, cleaned).apply()
                    refreshTick++
                } else {
                    toast(context, "Anna luku, esim. 5.0")
                }
            },
            onDismiss = { showThresholdDialog = false },
        )
    }
    editSensorSlot?.let { slot ->
        TextFieldDialog(
            title = "Anturin nimi",
            initial = sensorName(prefs, slot),
            label = "Nimi",
            keyboardType = KeyboardType.Text,
            onSave = { value ->
                editSensorSlot = null
                val name = value.trim().ifEmpty { defaultSensorName(slot) }
                prefs.edit().putString(sensorNameKey(slot), name).apply()
                refreshTick++
            },
            onDismiss = { editSensorSlot = null },
        )
    }
    if (addFeed) {
        CustomFeedDialog(
            existing = null,
            onSave = { name, url ->
                if (isValidFeedUrl(url)) {
                    NewsFeedStore.addCustom(prefs, name, url)
                    addFeed = false
                    refreshTick++
                } else {
                    toast(context, "Virheellinen osoite. Käytä http(s)://-alkuista osoitetta.")
                }
            },
            onDelete = null,
            onDismiss = { addFeed = false },
        )
    }
    editFeed?.let { feed ->
        CustomFeedDialog(
            existing = feed,
            onSave = { name, url ->
                if (isValidFeedUrl(url)) {
                    NewsFeedStore.updateCustom(prefs, feed.id, name, url)
                    editFeed = null
                    refreshTick++
                } else {
                    toast(context, "Virheellinen osoite. Käytä http(s)://-alkuista osoitetta.")
                }
            },
            onDelete = {
                NewsFeedStore.removeCustom(prefs, feed.id)
                editFeed = null
                refreshTick++
            },
            onDismiss = { editFeed = null },
        )
    }
    if (showScanDialog) {
        RuuviScanDialog(
            targetSlot = scanTargetSlot,
            prefs = prefs,
            onChanged = { refreshTick++ },
            onClose = { showScanDialog = false },
        )
    }
}

// ===================== Ruuvi-skannausdialogi =====================

@Composable
private fun RuuviScanDialog(
    targetSlot: String?,
    prefs: android.content.SharedPreferences,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { RuuviRepository.get(context) }
    val sm = remember { SettingsManager.get() }
    var samples by remember { mutableStateOf(sortedSnapshot(repo)) }
    var assignMac by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val listener = RuuviRepository.Listener { _, _ -> main.post { samples = sortedSnapshot(repo) } }
        repo.addListener(listener)
        onDispose { repo.removeListener(listener) }
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Sulje") } },
        title = { Text("Etsi antureita") },
        text = {
            Column {
                if (targetSlot != null) {
                    Text(
                        "Napauta anturia liittääksesi sen kohtaan \"${sensorName(prefs, targetSlot)}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                } else {
                    Text(
                        "Napauta anturia liittääksesi sen huoneeseen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (samples.isEmpty()) {
                    Text("Etsitään RuuviTageja…", style = MaterialTheme.typography.bodyLarge)
                } else {
                    samples.forEach { (mac, s) ->
                        val temp = s.temperatureC()?.let { String.format(FI, "%.1f °C", it) } ?: "– °C"
                        val slot = sm.slotForMac(mac)
                        val suffix = slot?.let { "  [${sensorName(prefs, it)}]" } ?: ""
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (targetSlot != null) {
                                        assignMacToSlot(sm, mac, targetSlot)
                                        onChanged()
                                        onClose()
                                    } else {
                                        assignMac = mac
                                    }
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                "$mac · $temp · ${s.rssi} dBm$suffix",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        },
    )

    assignMac?.let { mac ->
        AssignSlotDialog(
            mac = mac,
            prefs = prefs,
            onPick = { slot ->
                if (slot == null) clearMacAssignment(sm, mac) else assignMacToSlot(sm, mac, slot)
                assignMac = null
                onChanged()
                onClose()
            },
            onDismiss = { assignMac = null },
        )
    }
}

@Composable
private fun AssignSlotDialog(
    mac: String,
    prefs: android.content.SharedPreferences,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Peruuta") } },
        title = { Text("Liitä anturi $mac") },
        text = {
            Column {
                items3Sensors().forEach { slot ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(slot) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(sensorName(prefs, slot), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(null) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        "Poista liitos",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

// ===================== Yleiset dialogit =====================

@Composable
private fun RadioDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Sulje") } },
        title = { Text(title) },
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
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
    )
}

@Composable
private fun TextFieldDialog(
    title: String,
    initial: String,
    label: String,
    keyboardType: KeyboardType,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Tallenna") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Peruuta") } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun CustomFeedDialog(
    existing: NewsFeed?,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(name, url) }) { Text("Tallenna") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Peruuta") } },
        title = { Text(if (existing == null) "Lisää oma syöte" else "Muokkaa syötettä") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nimi (esim. Oma blogi)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Osoite (https://…)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDelete) {
                        Text("Poista syöte", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    )
}

// ===================== Rivikomponentit =====================

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
private fun ClickableRow(
    title: String,
    subtitle: String? = null,
    trailingIconRes: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
        if (trailingIconRes != null) {
            Spacer(Modifier.width(12.dp))
            Icon(
                painter = painterResource(trailingIconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * Dynamic color -kytkin: brändipaletti (oletus) ↔ Material You. Vaihto recreatee Activityn,
 * jotta uusi väriteema otetaan käyttöön heti (myös etusivun Compose-Activity lukee arvon onResumessa).
 * Näytetään vain Android 12+:lla, koska dynaaminen paletti vaatii sen.
 */
@Composable
private fun DynamicColorRow(prefs: android.content.SharedPreferences, context: Context) {
    var checked by remember {
        mutableStateOf(prefs.getBoolean(MobileThemeController.KEY_DYNAMIC_COLOR, false))
    }
    SwitchRow(
        title = "Käytä laitteen värejä",
        subtitle = "Material You -värit taustakuvasta. Pois päältä: oma kirkas brändipaletti.",
        checked = checked,
    ) {
        checked = it
        prefs.edit().putBoolean(MobileThemeController.KEY_DYNAMIC_COLOR, it).apply()
        (context as? android.app.Activity)?.recreate()
    }
}

/** Kytkinrivi joka lukee/kirjoittaa SharedPreferences-boolean-avaimen itse. */
@Composable
private fun PrefSwitchRow(
    prefs: android.content.SharedPreferences,
    key: String,
    title: String,
    subtitle: String? = null,
    default: Boolean,
) {
    var checked by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    SwitchRow(title = title, subtitle = subtitle, checked = checked) {
        checked = it
        prefs.edit().putBoolean(key, it).apply()
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant,
        thickness = 1.dp,
    )
}

/**
 * Tietoja sovelluksesta: nykyinen versio, päivitystarkistus (GitHub-julkaisut) ja GitHub-linkki.
 * Jos uudempi versio löytyy, se voidaan ladata ja asentaa suoraan ([AppUpdater]).
 */
@Composable
private fun AppInfoSection(context: Context) {
    val current = remember { appVersion(context) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdater.ReleaseInfo?>(null) }

    SectionHeader("Tietoja sovelluksesta")
    Spacer(Modifier.height(4.dp))
    SettingsCard {
        InfoRow(title = "Sovelluksen versio", value = current)
        RowDivider()
        ClickableRow(
            title = if (checking) "Tarkistetaan päivityksiä…" else "Tarkista päivitykset",
            subtitle = status,
            trailingIconRes = R.drawable.mobile_ic_refresh_24,
        ) {
            if (checking || downloading) return@ClickableRow
            checking = true
            status = null
            update = null
            AppUpdater.checkLatest(current) { rel, newer ->
                checking = false
                update = if (newer) rel else null
                status = when {
                    rel == null -> "Päivitystarkistus epäonnistui. Tarkista verkkoyhteys."
                    newer -> "Uudempi versio ${rel.versionName} on saatavilla."
                    else -> "Sovellus on ajan tasalla."
                }
            }
        }
        val avail = update
        if (avail?.apkUrl != null) {
            RowDivider()
            ClickableRow(
                title = if (downloading) "Ladataan ja asennetaan…" else "Lataa ja asenna ${avail.versionName}",
                subtitle = "Haetaan GitHub-julkaisusta. Asennukseen tarvitaan lupa asentaa tuntemattomista lähteistä.",
            ) {
                if (downloading) return@ClickableRow
                downloading = true
                AppUpdater.downloadAndInstall(context, avail.apkUrl) { msg ->
                    downloading = false
                    status = msg
                }
            }
        }
        RowDivider()
        ClickableRow(
            title = "GitHub – lähdekoodi ja julkaisut",
            subtitle = "github.com/jrs8205/Arkikeskus",
        ) {
            openUrl(context, AppUpdater.REPO_URL)
        }
    }
    Spacer(Modifier.height(8.dp))
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        toast(context, "Selainta ei löytynyt")
    }
}

// ===================== Apufunktiot =====================

private fun items3Sensors(): List<String> = listOf(
    SettingsManager.RUUVI_SLOT_BEDROOM,
    SettingsManager.RUUVI_SLOT_LIVINGROOM,
    SettingsManager.RUUVI_SLOT_BALCONY,
)

private fun sensorNameKey(slot: String): String = when (slot) {
    SettingsManager.RUUVI_SLOT_BEDROOM -> MobileThemeController.KEY_SENSOR_NAME_BEDROOM
    SettingsManager.RUUVI_SLOT_LIVINGROOM -> MobileThemeController.KEY_SENSOR_NAME_LIVINGROOM
    else -> MobileThemeController.KEY_SENSOR_NAME_BALCONY
}

private fun defaultSensorName(slot: String): String = when (slot) {
    SettingsManager.RUUVI_SLOT_BEDROOM -> "Anturi 1"
    SettingsManager.RUUVI_SLOT_LIVINGROOM -> "Anturi 2"
    else -> "Anturi 3"
}

private fun sensorName(prefs: android.content.SharedPreferences, slot: String): String =
    prefs.getString(sensorNameKey(slot), defaultSensorName(slot)) ?: defaultSensorName(slot)

private fun slotSummary(repo: RuuviRepository, mac: String?): String {
    if (mac.isNullOrEmpty()) return "Ei liitettyä anturia — napauta etsiäksesi"
    val s = repo.getLatest(mac)
    val temp = s?.temperatureC()
    return if (temp != null) "$mac · ${String.format(FI, "%.1f °C", temp)}" else mac
}

private fun sortedSnapshot(repo: RuuviRepository): List<Pair<String, RuuviSample>> =
    repo.snapshot().entries.sortedBy { it.key }.map { it.key to it.value }

private fun assignMacToSlot(sm: SettingsManager, mac: String, slot: String) {
    val existing = sm.slotForMac(mac)
    if (existing != null && existing != slot) sm.setRuuviMac(existing, null)
    sm.setRuuviMac(slot, mac)
}

private fun clearMacAssignment(sm: SettingsManager, mac: String) {
    val slot = sm.slotForMac(mac) ?: return
    sm.setRuuviMac(slot, null)
}

private fun labelFor(options: List<Pair<String, String>>, value: String): String =
    options.firstOrNull { it.first == value }?.second ?: value

private fun isValidFeedUrl(url: String?): Boolean {
    if (url == null) return false
    val u = url.trim().lowercase()
    return u.startsWith("http://") || u.startsWith("https://")
}

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (e: Exception) {
    "?"
}

private fun lastUpdateText(): String {
    val ts = SettingsManager.get().getLastSuccessfulFmiUpdate()
    return if (ts <= 0L) "—" else SimpleDateFormat("d.M.yyyy HH:mm", FI).format(Date(ts))
}

private fun bluetoothScanPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN
    else Manifest.permission.ACCESS_FINE_LOCATION

private fun hasPermission(context: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

private fun isBluetoothOn(context: Context): Boolean = try {
    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    bm?.adapter?.isEnabled == true
} catch (e: RuntimeException) {
    false
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
