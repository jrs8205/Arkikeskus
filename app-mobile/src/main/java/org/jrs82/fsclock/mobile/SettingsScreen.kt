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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.ruuvi.RuuviRepository
import org.jrs82.fsclock.ruuvi.RuuviSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Arkikeskuksen asetusnäkymä (Jetpack Compose + Material 3). Korvasi vanhan
 * MobileSettingsFragment + mobile_preferences.xml -parin (poistettu). Kaikki asetukset luetaan ja
 * kirjoitetaan SAMOIHIN SharedPreferences-avaimiin kuin ennenkin, joten käytös säilyy identtisenä.
 *
 * Osiot vastaavat vanhaa preferences-XML:ää: Sää (auto-sijainti), Etusivu (widget-järjestys),
 * Uutislähteet (10 kytkintä), Omat uutissyötteet (lisää/muokkaa/poista), Ruuvi-anturit
 * (BLE-skannaus + 3 slottia + nimet), Pörssisähkö (huomio/raja/ajankohta), Sovellus
 * (päivitysväli/teema/versiotiedot).
 */

private val FI = Locale("fi", "FI")

/** Yhtenäinen pyöristetty laatikkomuoto valikon ja asetusten kohdille (sama molemmilla sivuilla). */
internal val ItemBoxShape = RoundedCornerShape(20.dp)

private val THEME_OPTIONS = listOf(
    MobileThemeController.VALUE_SYSTEM to "Järjestelmän mukaan",
    MobileThemeController.VALUE_LIGHT to "Vaalea",
    MobileThemeController.VALUE_DARK to "Tumma",
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
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val repo = remember { RuuviRepository.get(context) }

    // Kasvatetaan kun jokin näkymän ulkopuolinen tila muuttuu (slot-liitos, anturin nimi,
    // oma syöte) → keyatut remember-lukijat lukevat arvot uudelleen.
    var refreshTick by remember { mutableStateOf(0) }

    // --- Ruuvi-skannaus: dialogin näyttötila + lupavirta ---
    var showScanDialog by remember { mutableStateOf(false) }

    val scanPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (isBluetoothOn(context)) {
                repo.start()
                showScanDialog = true
            } else {
                toast(context, "Bluetooth on pois päältä. Kytke se päälle ja yritä uudelleen.")
            }
        } else {
            toast(context, "Bluetooth-skannauslupa tarvitaan antureiden etsimiseen.")
        }
    }
    val requestScan: () -> Unit = {
        val perm = bluetoothScanPermission()
        if (hasPermission(context, perm)) {
            if (isBluetoothOn(context)) {
                repo.start()
                showScanDialog = true
            } else {
                toast(context, "Bluetooth on pois päältä. Kytke se päälle ja yritä uudelleen.")
            }
        } else {
            scanPermLauncher.launch(perm)
        }
    }

    // --- Varmuuskopiointi: vienti/palautus SAF:lla (käyttäjä voi valita esim. Driven) ---
    var restoreDone by remember { mutableStateOf<BackupManager.RestoreResult?>(null) }
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            Thread {
                try {
                    val res = context.contentResolver.openOutputStream(uri)?.use { os ->
                        BackupManager.export(context, os)
                    }
                    Handler(Looper.getMainLooper()).post {
                        toast(
                            context,
                            if (res != null)
                                "Varmuuskopio tallennettu: ${res.workouts} lenkkiä, " +
                                    "${res.prefs} asetusta (${formatBackupBytes(res.bytes)})."
                            else "Tallennus epäonnistui.",
                        )
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        toast(context, "Varmuuskopiointi epäonnistui: ${e.message}")
                    }
                }
            }.start()
        }
    }
    // Jaettu palautuspolku: SAF-valitsimesta TAI suoraan automaattibackupin persistoidusta URIsta.
    val runRestoreFrom: (android.net.Uri) -> Unit = { uri ->
        if (WorkoutTracker.state.value.phase != WorkoutTracker.Phase.IDLE) {
            toast(context, "Lopeta käynnissä oleva lenkki ennen palautusta.")
        } else {
            Thread {
                try {
                    val res = context.contentResolver.openInputStream(uri)?.use { ins ->
                        BackupManager.restore(context, ins)
                    }
                    Handler(Looper.getMainLooper()).post {
                        if (res != null) restoreDone = res
                        else toast(context, "Palautus epäonnistui.")
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        toast(context, "Palautus epäonnistui: ${e.message}")
                    }
                }
            }.start()
        }
    }
    var showRestoreChoice by remember { mutableStateOf(false) }
    val backupRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) runRestoreFrom(uri)
    }

    // --- Automaattinen varmuuskopiointi: kohdetiedosto valitaan kerran, WorkManager ajaa.
    // Tila on AutoBackupin OMASSA prefs-tiedostossa (ei Androidin Auto Backupin piirissä). ---
    val abPrefs = remember { AutoBackup.prefs(context) }
    // isEnabled validoi persistoidun luvan ja siivoaa vanhentuneen tilan (esim. toiselta
    // laitteelta palautunut URI) — kytkin ei näytä päällä-tilaa joka ei oikeasti kirjoita.
    var autoBackupOn by remember { mutableStateOf(AutoBackup.isEnabled(context)) }
    // Tilarivi päivittyy heti kun taustatyö kirjoittaa aikaleiman/virheen prefseihin
    // (ilman kuuntelijaa "ensimmäinen kopio tekeillä…" jäisi näkyviin kunnes sivu avataan uudelleen).
    var autoBackupTick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key.startsWith("auto_backup_")) autoBackupTick++
        }
        abPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { abPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val autoBackupStatus = remember(autoBackupOn, autoBackupTick) {
        autoBackupSubtitle(abPrefs, autoBackupOn)
    }
    val autoBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            // Päälle vasta kun persistoitu lupa + ajastus ovat varmasti kunnossa — eräät
            // tarjoajat eivät myönnä pysyvää lupaa (SecurityException enablen sisällä).
            if (AutoBackup.enable(context, uri)) {
                autoBackupOn = true
                toast(context, "Automaattinen varmuuskopiointi käytössä — ensimmäinen kopio tehdään nyt.")
            } else {
                autoBackupOn = false
                toast(context, "Sijainti ei kelpaa automaattiseen varmuuskopiointiin — " +
                    "valitse toinen sijainti (esim. Drive tai puhelimen muisti).")
            }
        } else {
            autoBackupOn = false
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
    var editSensorMac by remember { mutableStateOf<String?>(null) }
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

    // Hostataan overlayna ComposeMainScreenin sisältöalueella → ulompi Scaffold hoitaa
    // status-/navigaatiopalkin insetit; nollataan omat, ettei yläreunaan tule tuplapehmustetta.
    // Ei takaisin-nuolta: overlay suljetaan takaisin-eleellä, ratas-napilla tai Koti-napilla.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Asetukset") },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // Kapeat reunamarginaalit → kortit lähes koko sivun levyisiä (kuten HSL), enemmän tilaa.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- Sää ----------
            item { SectionHeader("Sää", R.drawable.mobile_ic_location_24) }
            item {
                SettingsCard {
                    SwitchRow(
                        title = "Automaattinen sijainti",
                        subtitle = "Päivitä paikkakunta laitteen sijainnin perusteella, jos lupa on annettu",
                        leadingIconRes = R.drawable.mobile_ic_location_24,
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
                    RowDivider()
                    InfoRow(
                        title = "Viimeisin sääpäivitys",
                        value = lastUpdateText(),
                        leadingIconRes = R.drawable.mobile_ic_clock_24,
                    )
                }
            }

            // ---------- Etusivu ----------
            item { SectionHeader("Etusivu", R.drawable.mobile_ic_dashboard_24) }
            item {
                SettingsCard {
                    ClickableRow(
                        title = "Etusivun kortit",
                        subtitle = "Valitse näkyvät kortit ja järjestä ne raahaamalla (kello, sää, sähkö, anturit, uutiset, lähilähdöt…)",
                        leadingIconRes = R.drawable.mobile_ic_dashboard_24,
                    ) {
                        context.startActivity(Intent(context, MobileWidgetOrderActivity::class.java))
                    }
                }
            }

            // ---------- Uutislähteet ----------
            item { SectionHeader("Uutislähteet", R.drawable.mobile_ic_news_24) }
            item {
                SettingsCard {
                    builtinFeeds.forEachIndexed { index, feed ->
                        PrefSwitchRow(
                            prefs, feed.enabledKey(), feed.name,
                            leadingIconRes = R.drawable.mobile_ic_news_24, default = true,
                        )
                        if (index < builtinFeeds.lastIndex) RowDivider()
                    }
                }
            }

            // ---------- Omat uutissyötteet ----------
            item { SectionHeader("Omat uutissyötteet", R.drawable.mobile_ic_rss_24) }
            item {
                SettingsCard {
                    customFeeds.forEach { feed ->
                        ClickableRow(
                            title = feed.name, subtitle = feed.url,
                            leadingIconRes = R.drawable.mobile_ic_rss_24,
                        ) { editFeed = feed }
                        RowDivider()
                    }
                    ClickableRow(
                        title = "Lisää oma syöte",
                        subtitle = "Lisää oma RSS- tai Atom-syöte (nimi + osoite)",
                        leadingIconRes = R.drawable.mobile_ic_add_24,
                    ) { addFeed = true }
                }
            }

            // ---------- Ruuvi-anturit ----------
            item { SectionHeader("Ruuvi-anturit", R.drawable.mobile_ic_thermometer_24) }
            item {
                SettingsCard {
                    ClickableRow(
                        title = "Etsi antureita (Bluetooth)",
                        subtitle = "Skannaa lähellä olevat RuuviTagit ja nimeä napauttamalla",
                        leadingIconRes = R.drawable.mobile_ic_bluetooth_24,
                    ) { requestScan() }
                }
            }
            item {
                // Rajaton nimeäminen: kaikki nimetyt anturit (MAC → nimi) dynaamisena listana.
                val named = remember(refreshTick) {
                    SettingsManager.get().sensorNamesByMac().entries.toList()
                }
                SettingsCard {
                    if (named.isEmpty()) {
                        ClickableRow(
                            title = "Ei nimettyjä antureita",
                            subtitle = "Etsi antureita ja napauta löytynyttä antaaksesi sille nimen. " +
                                "Antureita voi nimetä rajattomasti.",
                            leadingIconRes = R.drawable.mobile_ic_thermometer_24,
                        ) { requestScan() }
                    } else {
                        named.forEachIndexed { i, e ->
                            if (i > 0) RowDivider()
                            ClickableRow(
                                title = e.value,
                                subtitle = macSummary(repo, e.key),
                                leadingIconRes = R.drawable.mobile_ic_thermometer_24,
                            ) { editSensorMac = e.key }
                        }
                    }
                }
            }

            // ---------- Pörssisähkö ----------
            item { SectionHeader("Pörssisähkö", R.drawable.mobile_ic_bolt_24) }
            item {
                SettingsCard {
                    PrefSwitchRow(
                        prefs,
                        MobileThemeController.KEY_CHEAP_ELECTRICITY_NOTICE,
                        "Halvan sähkön huomio",
                        subtitle = "Näytä huomio etusivulla kun sähkö on halpaa",
                        leadingIconRes = R.drawable.mobile_ic_bolt_24,
                        default = true,
                    )
                    RowDivider()
                    ClickableRow(
                        title = "Halvan sähkön raja",
                        subtitle = "$threshold c/kWh (ALV 0 %)",
                        leadingIconRes = R.drawable.mobile_ic_bolt_24,
                    ) { showThresholdDialog = true }
                    RowDivider()
                    ClickableRow(
                        title = "Milloin huomio näytetään",
                        subtitle = labelFor(CHEAP_MODE_OPTIONS, cheapMode),
                        leadingIconRes = R.drawable.mobile_ic_clock_24,
                    ) { showModeDialog = true }
                }
            }

            // ---------- Teema ----------
            item { SectionHeader("Teema", R.drawable.mobile_ic_palette_24) }
            item {
                SettingsCard {
                    ClickableRow(
                        title = "Teema",
                        subtitle = labelFor(THEME_OPTIONS, themeMode),
                        leadingIconRes = R.drawable.mobile_ic_palette_24,
                    ) {
                        showThemeDialog = true
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        RowDivider()
                        DynamicColorRow(prefs, context)
                    }
                }
            }

            // ---------- Sovellus ----------
            item { SectionHeader("Sovellus", R.drawable.mobile_ic_tune_24) }
            item {
                SettingsCard {
                    // Koskee KAIKKIA automaattisesti haettavia tietoja (sää, sähkö, uutiset,
                    // liikenne…) — kuinka usein näkyvä sivu virkistetään sovelluksen ollessa auki.
                    ClickableRow(
                        title = "Tietojen päivitysväli",
                        subtitle = "Kaikki tiedot (sää, sähkö, uutiset…) · " + labelFor(INTERVAL_OPTIONS, interval),
                        leadingIconRes = R.drawable.mobile_ic_clock_24,
                    ) { showIntervalDialog = true }
                }
            }

            // ---------- Varmuuskopiointi ----------
            item { SectionHeader("Varmuuskopiointi", R.drawable.mobile_ic_backup_24) }
            item {
                SettingsCard {
                    ClickableRow(
                        title = "Vie varmuuskopio",
                        subtitle = "Asetukset ja lenkit yhteen tiedostoon (esim. Driveen)",
                        leadingIconRes = R.drawable.mobile_ic_backup_24,
                    ) {
                        backupExportLauncher.launch(
                            "Arkikeskus-varmuuskopio-" +
                                SimpleDateFormat("yyyy-MM-dd", FI).format(Date()) + ".json")
                    }
                    RowDivider()
                    ClickableRow(
                        title = "Palauta varmuuskopio",
                        subtitle = if (autoBackupOn)
                            "Automaattisesta varmuuskopiosta tai tiedostosta"
                        else "Tuo aiemmin viety varmuuskopiotiedosto",
                        leadingIconRes = R.drawable.mobile_ic_restore_24,
                    ) {
                        // Kun automaattibackup on käytössä, palautus onnistuu suoraan ilman
                        // tiedostovalitsinta (käyttäjän palaute: valitsimen back-nappi
                        // kulkee Drive-kansiopuuta, ei takaisin sovellukseen).
                        if (autoBackupOn) {
                            showRestoreChoice = true
                        } else {
                            backupRestoreLauncher.launch(
                                arrayOf("application/json", "application/octet-stream"))
                        }
                    }
                    RowDivider()
                    SwitchRow(
                        title = "Automaattinen varmuuskopiointi",
                        subtitle = autoBackupStatus,
                        leadingIconRes = R.drawable.mobile_ic_backup_24,
                        checked = autoBackupOn,
                    ) { on ->
                        if (on) {
                            // Kohdetiedosto valitaan kerran (esim. Drive) → pysyvä kirjoituslupa.
                            toast(context, "Drive löytyy valitsimen ☰-valikosta vasemmasta yläkulmasta.")
                            autoBackupLauncher.launch("Arkikeskus-autovarmuuskopio.json")
                        } else {
                            AutoBackup.disable(context)
                            autoBackupOn = false
                            toast(context, "Automaattinen varmuuskopiointi pois käytöstä.")
                        }
                    }
                }
            }

            // ---------- Tietoja sovelluksesta (versio + päivitys + GitHub) ----------
            item { AppInfoSection(context) }
        }
    }

    // ---------- Dialogit ----------
    if (showRestoreChoice) {
        val lastMs = abPrefs.getLong(AutoBackup.KEY_LAST_MS, 0L)
        val lastBytes = abPrefs.getInt(AutoBackup.KEY_LAST_BYTES, 0)
        AlertDialog(
            onDismissRequest = { showRestoreChoice = false },
            title = { Text("Palauta varmuuskopio") },
            text = {
                Text(
                    "Palautetaanko suoraan automaattisesta varmuuskopiosta?" +
                        (if (lastMs > 0)
                            "\n\nViimeisin kopio: " +
                                SimpleDateFormat("d.M. HH:mm", FI).format(Date(lastMs)) +
                                (if (lastBytes > 0) " (${formatBackupBytes(lastBytes)})" else "")
                        else "") +
                        "\n\nVoit myös valita varmuuskopiotiedoston itse.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreChoice = false
                    val uriStr = abPrefs.getString(AutoBackup.KEY_URI, null)
                    if (uriStr != null) runRestoreFrom(android.net.Uri.parse(uriStr))
                    else toast(context, "Automaattista varmuuskopiota ei löytynyt.")
                }) { Text("Automaattisesta") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreChoice = false
                    backupRestoreLauncher.launch(
                        arrayOf("application/json", "application/octet-stream"))
                }) { Text("Valitse tiedosto…") }
            },
        )
    }
    restoreDone?.let { res ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Varmuuskopio palautettu") },
            text = {
                Text(
                    "Palautettu ${res.workouts} lenkkiä ja ${res.prefs} asetusta." +
                        (if (res.skipped > 0) " ${res.skipped} lenkkiä oli jo laitteella." else "") +
                        "\n\nSovellus käynnistetään uudelleen, jotta teema ja asetukset tulevat " +
                        "voimaan. Anturien nimet näkyvät seuraavasta skannauksesta.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreDone = null
                    // Aito uudelleenkäynnistys erillisen prosessin kautta → splash näkyy
                    // (suoraan itsensä käynnistävältä kuolevalta prosessilta Android ohittaa sen).
                    RestartActivity.restartApp(context)
                }) { Text("OK") }
            },
        )
    }
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
                // Isäntä-Activity (ComposeMainScreen-overlay) pakottaa PAIKALLISEN yötilan
                // onCreatessa → default-tilan vaihto ei yksin riitä; päivitä paikallinen tila
                // suoraan, jolloin AppCompat recreatee tarvittaessa ja teema vaihtuu heti.
                (context as? androidx.appcompat.app.AppCompatActivity)?.delegate?.localNightMode =
                    MobileThemeController.nightMode(context)
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
            title = "Tietojen päivitysväli",
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
    editSensorMac?.let { mac ->
        TextFieldDialog(
            title = "Anturin nimi ($mac)",
            initial = SettingsManager.get().sensorNameFor(mac),
            label = "Nimi (tyhjä poistaa nimeämisen)",
            keyboardType = KeyboardType.Text,
            onSave = { value ->
                editSensorMac = null
                SettingsManager.get().setSensorName(mac, value)
                refreshTick++
            },
            onDismiss = { editSensorMac = null },
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
                    RssRepository.get().invalidate(feed.id)
                    editFeed = null
                    refreshTick++
                } else {
                    toast(context, "Virheellinen osoite. Käytä http(s)://-alkuista osoitetta.")
                }
            },
            onDelete = {
                NewsFeedStore.removeCustom(prefs, feed.id)
                RssRepository.get().invalidate(feed.id)
                editFeed = null
                refreshTick++
            },
            onDismiss = { editFeed = null },
        )
    }
    if (showScanDialog) {
        RuuviScanDialog(
            onPickMac = { mac ->
                showScanDialog = false
                editSensorMac = mac
            },
            onClose = { showScanDialog = false },
        )
    }
}

// ===================== Ruuvi-skannausdialogi =====================

@Composable
private fun RuuviScanDialog(
    onPickMac: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { RuuviRepository.get(context) }
    val sm = remember { SettingsManager.get() }
    var samples by remember { mutableStateOf(sortedSnapshot(repo)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val main = Handler(Looper.getMainLooper())
        val listener = RuuviRepository.Listener { _, _ -> main.post { samples = sortedSnapshot(repo) } }
        repo.addListener(listener)
        // Skannaus sidottu elinkaareen: pysähtyy myös koti-painikkeesta / näytön lukituksesta (ON_STOP),
        // ei vain dialogia suljettaessa → BLE ei jää taustalle akkua syömään.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> try { repo.start() } catch (e: Exception) { }
                Lifecycle.Event.ON_STOP -> repo.stop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            try { repo.start() } catch (e: Exception) { }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            repo.removeListener(listener)
            repo.stop()
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Sulje") } },
        title = { Text("Etsi antureita") },
        text = {
            Column {
                Text(
                    "Napauta anturia antaaksesi sille nimen. Nimettyjä antureita voi olla rajattomasti.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (samples.isEmpty()) {
                    Text("Etsitään RuuviTageja…", style = MaterialTheme.typography.bodyLarge)
                } else {
                    samples.forEach { (mac, s) ->
                        val temp = s.temperatureC()?.let { String.format(FI, "%.1f °C", it) } ?: "– °C"
                        val existing = sm.sensorNameFor(mac)
                        val suffix = if (existing.isEmpty()) "" else "  [$existing]"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickMac(mac) }
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

/** Automaattibackupin tilateksti: viimeisin onnistunut ajo tai virheohje. */
private fun autoBackupSubtitle(prefs: android.content.SharedPreferences, on: Boolean): String {
    if (!on) return "Päivittäin valitsemaasi tiedostoon (Drive: valitsimen ☰-valikko)"
    if (prefs.getString(AutoBackup.KEY_ERROR, null) != null) {
        return "Edellinen ajo epäonnistui — valitse kohde uudelleen (pois ja päälle)"
    }
    val last = prefs.getLong(AutoBackup.KEY_LAST_MS, 0L)
    return if (last > 0) {
        val bytes = prefs.getInt(AutoBackup.KEY_LAST_BYTES, 0)
        "Päivittäin · viimeisin " + SimpleDateFormat("d.M. HH:mm", FI).format(Date(last)) +
            (if (bytes > 0) " · " + formatBackupBytes(bytes) else "")
    } else {
        "Päivittäin · ensimmäinen kopio tekeillä…"
    }
}

/** Varmuuskopion koko luettavana: "11,6 kt" / "1,2 Mt" — kasvava luku kertoo että dataa kertyy. */
private fun formatBackupBytes(bytes: Int): String =
    if (bytes < 1024 * 1024) String.format(FI, "%.1f kt", bytes / 1024.0)
    else String.format(FI, "%.1f Mt", bytes / (1024.0 * 1024.0))

@Composable
private fun SectionHeader(text: String, iconRes: Int? = null) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Osion sisältö: kukin rivi on nyt OMA pyöristetty laatikko (ClickableRow/SwitchRow/InfoRow),
 *  joten tämä on vain väljästi välistetty Column (ei enää yhtä korttia + erotinviivoja). */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ClickableRow(
    title: String,
    subtitle: String? = null,
    leadingIconRes: Int? = null,
    trailingIconRes: Int? = null,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = ItemBoxShape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIconRes != null) {
                RowLeadingIcon(leadingIconRes)
            }
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
}

/** HSL-tyylinen sininen leading-ikoni asetusrivin vasemmalla (kutsutaan RowScopessa). */
@Composable
private fun RowLeadingIcon(iconRes: Int) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
    )
    Spacer(Modifier.width(16.dp))
}

@Composable
private fun InfoRow(title: String, value: String, leadingIconRes: Int? = null) {
    Card(modifier = Modifier.fillMaxWidth(), shape = ItemBoxShape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIconRes != null) {
                RowLeadingIcon(leadingIconRes)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    leadingIconRes: Int? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = ItemBoxShape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIconRes != null) {
                RowLeadingIcon(leadingIconRes)
            }
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
        leadingIconRes = R.drawable.mobile_ic_palette_24,
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
    leadingIconRes: Int? = null,
    default: Boolean,
) {
    var checked by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    SwitchRow(title = title, subtitle = subtitle, leadingIconRes = leadingIconRes, checked = checked) {
        checked = it
        prefs.edit().putBoolean(key, it).apply()
    }
}

@Composable
private fun RowDivider() {
    // Ei enää erotinviivaa: kukin rivi on oma pyöristetty laatikko, väli tulee Columnin spacingista.
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

    SectionHeader("Tietoja sovelluksesta", R.drawable.mobile_ic_info_24)
    Spacer(Modifier.height(4.dp))
    SettingsCard {
        InfoRow(
            title = "Sovelluksen versio",
            value = current,
            leadingIconRes = R.drawable.mobile_ic_info_24,
        )
        RowDivider()
        ClickableRow(
            title = if (checking) "Tarkistetaan päivityksiä…" else "Tarkista päivitykset",
            subtitle = status,
            leadingIconRes = R.drawable.mobile_ic_refresh_24,
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
                leadingIconRes = R.drawable.mobile_ic_download_24,
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
            leadingIconRes = R.drawable.mobile_ic_code_24,
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

private fun macSummary(repo: RuuviRepository, mac: String?): String {
    if (mac.isNullOrEmpty()) return "Ei anturia"
    val s = repo.getLatest(mac)
    val temp = s?.temperatureC()
    return if (temp != null) "$mac · ${String.format(FI, "%.1f °C", temp)}" else mac
}

private fun sortedSnapshot(repo: RuuviRepository): List<Pair<String, RuuviSample>> =
    repo.snapshot().entries.sortedBy { it.key }.map { it.key to it.value }

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
