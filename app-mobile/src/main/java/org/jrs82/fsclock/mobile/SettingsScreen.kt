package org.jrs82.fsclock.mobile

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
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
 * Rakenne: **keskussivu (HUB) + 10 alasivua** ([SettingsPage]). Keskussivulta avataan alasivu, jolla
 * kyseisen ominaisuuden asetukset ja sitä koskevat ilmoituskytkimet ovat vierekkäin (oma
 * "Ilmoitukset"-aliotsikko). Navigaatio on host-tilaohjattu kuten muutkin näkymät ("Lenkki",
 * "Häiriöt ja muutokset"): [page] kertoo nykyisen sivun, [onPageChange] vaihtaa sen, ja
 * [BackHandler] palaa alasivulta keskussivulle (keskussivulta [onClose] sulkee asetukset). Ei
 * erillistä NavHostia eikä modaaleja → järjestelmän takaisin-ele toimii ja alapalkki jää näkyviin.
 */

private val FI = Locale("fi", "FI")

/** Yhtenäinen pyöristetty laatikkomuoto valikon ja asetusten kohdille (sama molemmilla sivuilla). */
internal val ItemBoxShape = RoundedCornerShape(20.dp)

/** Asetusten keskussivu + alasivut. Järjestys = keskussivun kategorioiden järjestys. */
enum class SettingsPage(val title: String) {
    HUB("Asetukset"),
    WEATHER("Sää & sijainti"),
    ELECTRICITY("Pörssisähkö"),
    NEWS("Uutiset"),
    TRANSIT("Joukkoliikenne"),
    FITNESS("Lenkki & askeleet"),
    RUUVI("Ruuvi-anturit"),
    APPEARANCE("Ulkoasu"),
    APP_DATA("Sovellus & data"),
    ABOUT("Tietoja"),
}

/** Sivun talletus Activityn uudelleenluontiin (teemanvaihto) — käyttäjä jää samalle alasivulle. */
internal val SettingsPageSaver = Saver<SettingsPage, String>(
    save = { it.name },
    restore = { runCatching { SettingsPage.valueOf(it) }.getOrDefault(SettingsPage.HUB) },
)

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
private val STEP_GOAL_OPTIONS = listOf(
    "7000" to "7 000 askelta",
    "8000" to "8 000 askelta",
    "10000" to "10 000 askelta",
    "12000" to "12 000 askelta",
    "15000" to "15 000 askelta",
    "20000" to "20 000 askelta",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    page: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
    onClose: () -> Unit,
) {
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

    // Ilmoituslupa (Android 13+): MIKÄ TAHANSA ilmoituskytkin pyytää POST_NOTIFICATIONS jos se puuttuu
    // (ilman tätä Notifications.post vaikenee hiljaa eikä yhtään ilmoitusta tule). Luvan jälkeen runOnce.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            Notifications.runOnce(context)
        } else {
            toast(context, "Salli ilmoitukset, jotta sovellus voi lähettää ilmoituksia.")
        }
    }
    val enableNotif: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Notifications.runOnce(context)
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
    // Drive-varmuuskopiosivu (WhatsApp-tyyli) avataan täysnäyttö-dialogina.
    var showDriveBackup by remember { mutableStateOf(false) }
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
    val backupRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) runRestoreFrom(uri)
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
    var showStepGoalDialog by remember { mutableStateOf(false) }
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
    val stepGoal = prefs.getString(StepGoalNotifier.KEY_GOAL, StepGoalNotifier.DEFAULT_GOAL)
        ?: StepGoalNotifier.DEFAULT_GOAL
    val builtinFeeds = remember { NewsFeedStore.allFeeds(prefs).filter { it.builtin } }
    val customFeeds = remember(refreshTick) { NewsFeedStore.customFeeds(prefs) }
    val namedSensors = remember(refreshTick) { SettingsManager.get().sensorNamesByMac().entries.toList() }

    // Takaisin-ele/-nappi: alasivulta keskussivulle, keskussivulta sulje asetukset. Drive-overlayn
    // ollessa auki tämä on pois käytöstä → Drive-sivun oma BackHandler (komposoituu myöhemmin) sulkee
    // sen ensin. AlertDialogit kuluttavat takaisin-eleen itse.
    BackHandler(enabled = !showDriveBackup) {
        if (page != SettingsPage.HUB) onPageChange(SettingsPage.HUB) else onClose()
    }

    // Overlay sisältöalueen päällä (kuten valikko): peittävä Surface, ulompi Scaffold hoitaa insetit
    // ja alapalkki jää näkyviin.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .padding(top = 8.dp, bottom = 32.dp),
        ) {
            when (page) {
                // ============================ KESKUSSIVU ============================
                SettingsPage.HUB -> {
                    SettingsPageTitle("Asetukset")
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val ruuviSubtitle = if (namedSensors.isNotEmpty())
                            "${namedSensors.size} anturia · etsi uusia" else "Etsi antureita Bluetoothilla"
                        // Etusivu ylimpänä; avaa SUORAAN korttijärjestelynäkymän (ei turhaa välisivua).
                        HubRow("Etusivu", "Näkyvät kortit ja järjestys",
                            R.drawable.mobile_ic_dashboard_24) {
                            context.startActivity(Intent(context, MobileWidgetOrderActivity::class.java))
                        }
                        HubRow("Sää & sijainti", "Sijainti · säävaroitukset",
                            R.drawable.mobile_ic_location_24) { onPageChange(SettingsPage.WEATHER) }
                        HubRow("Pörssisähkö", "Halvan sähkön huomio ja rajat",
                            R.drawable.mobile_ic_bolt_24) { onPageChange(SettingsPage.ELECTRICITY) }
                        HubRow("Uutiset", "10 lähdettä · 9 kategoriaa · omat syötteet",
                            R.drawable.mobile_ic_news_24) { onPageChange(SettingsPage.NEWS) }
                        HubRow("Joukkoliikenne", "HSL-häiriöilmoitukset suosikeille",
                            R.drawable.mobile_ic_bus_24) { onPageChange(SettingsPage.TRANSIT) }
                        HubRow("Lenkki & askeleet", "Kilometri-ilmoitus · askeltavoite",
                            R.drawable.mobile_ic_transit_walk) { onPageChange(SettingsPage.FITNESS) }
                        HubRow("Ruuvi-anturit", ruuviSubtitle,
                            R.drawable.mobile_ic_thermometer_24) { onPageChange(SettingsPage.RUUVI) }
                        HubRow("Ulkoasu", "Teema · laitteen värit",
                            R.drawable.mobile_ic_palette_24) { onPageChange(SettingsPage.APPEARANCE) }
                        HubRow("Sovellus & data", "Päivitysväli · varmuuskopiointi",
                            R.drawable.mobile_ic_tune_24) { onPageChange(SettingsPage.APP_DATA) }
                        HubRow("Tietoja", "Versio ${appVersion(context)} · GitHub",
                            R.drawable.mobile_ic_info_24) { onPageChange(SettingsPage.ABOUT) }
                    }
                }

                // ============================ Sää & sijainti ============================
                SettingsPage.WEATHER -> {
                    SettingsPageTitle("Sää & sijainti")
                    SubHeader("Sijainti")
                    GroupCard {
                        GroupRowSwitch(
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
                        GroupDivider()
                        GroupRowInfo(
                            title = "Viimeisin sääpäivitys",
                            value = lastUpdateText(),
                            leadingIconRes = R.drawable.mobile_ic_clock_24,
                        )
                    }
                    SubHeader("Ilmoitukset", R.drawable.mobile_ic_info_24)
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, WeatherWarningNotifier.KEY_ENABLED, "Säävaroitukset (oma paikkakunta)",
                            subtitle = "Ilmoita FMI:n säävaroituksista kotipaikkakunnallasi",
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = false,
                            onChange = { if (it) enableNotif() },
                        )
                    }
                }

                // ============================ Pörssisähkö ============================
                SettingsPage.ELECTRICITY -> {
                    SettingsPageTitle("Pörssisähkö")
                    SubHeader("Halpa sähkö")
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, MobileThemeController.KEY_CHEAP_ELECTRICITY_NOTICE, "Halvan sähkön huomio",
                            subtitle = "Näytä huomio etusivulla kun sähkö on halpaa",
                            leadingIconRes = R.drawable.mobile_ic_bolt_24, default = true,
                        )
                        GroupDivider()
                        GroupRowClickable(
                            title = "Halvan sähkön raja",
                            leadingIconRes = R.drawable.mobile_ic_bolt_24,
                            valueText = "$threshold c/kWh",
                            showChevron = true,
                        ) { showThresholdDialog = true }
                        GroupDivider()
                        GroupRowClickable(
                            title = "Milloin huomio näytetään",
                            leadingIconRes = R.drawable.mobile_ic_clock_24,
                            valueText = labelFor(CHEAP_MODE_OPTIONS, cheapMode),
                            showChevron = true,
                        ) { showModeDialog = true }
                    }
                    SubHeader("Ilmoitukset", R.drawable.mobile_ic_info_24)
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, ElectricityNotifier.KEY_ENABLED, "Huomisen hinnat",
                            subtitle = "Ilmoita kun huomisen sähköhinnat saapuvat (n. klo 14)",
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = false,
                            onChange = { if (it) enableNotif() },
                        )
                    }
                }

                // ============================ Uutiset ============================
                SettingsPage.NEWS -> {
                    SettingsPageTitle("Uutiset")
                    SubHeader("Uutislähteet · ${builtinFeeds.size}")
                    GroupCard {
                        builtinFeeds.forEachIndexed { index, feed ->
                            GroupPrefSwitchRow(
                                prefs, feed.enabledKey(), feed.name,
                                leadingIconRes = R.drawable.mobile_ic_news_24, default = true,
                            )
                            if (index < builtinFeeds.lastIndex) GroupDivider()
                        }
                    }
                    SubHeader("Omat uutissyötteet")
                    GroupCard {
                        customFeeds.forEach { feed ->
                            GroupRowClickable(
                                title = feed.name, subtitle = feed.url,
                                leadingIconRes = R.drawable.mobile_ic_rss_24,
                            ) { editFeed = feed }
                            GroupDivider()
                        }
                        GroupRowClickable(
                            title = "Lisää oma syöte",
                            subtitle = "Lisää oma RSS- tai Atom-syöte (nimi + osoite)",
                            leadingIconRes = R.drawable.mobile_ic_add_24,
                        ) { addFeed = true }
                    }
                    SubHeader("Uutiskategoriat (Kotimaat + Ulkomaat) · ${FOREIGN_CATEGORY_TAGS.size}")
                    GroupCard {
                        FOREIGN_CATEGORY_TAGS.forEachIndexed { index, pair ->
                            GroupPrefSwitchRow(
                                prefs, NewsProfile.catVisibleKey(pair.first), pair.second,
                                leadingIconRes = R.drawable.mobile_ic_news_24, default = true,
                            )
                            if (index < FOREIGN_CATEGORY_TAGS.lastIndex) GroupDivider()
                        }
                    }
                }

                // ============================ Joukkoliikenne ============================
                SettingsPage.TRANSIT -> {
                    SettingsPageTitle(
                        "Joukkoliikenne",
                        "HSL-häiriöilmoitukset suosikkilinjoillesi ja -pysäkeillesi.",
                    )
                    SubHeader("Ilmoitukset", R.drawable.mobile_ic_info_24)
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, HslAlertNotifier.KEY_ENABLED, "HSL-häiriöt suosikeilla",
                            subtitle = "Ilmoita kun suosikkilinjalle tai -pysäkille tulee uusi häiriö",
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = false,
                            onChange = { if (it) enableNotif() },
                        )
                    }
                }

                // ============================ Lenkki & askeleet ============================
                SettingsPage.FITNESS -> {
                    SettingsPageTitle("Lenkki & askeleet")
                    SubHeader("Askeltavoite")
                    GroupCard {
                        GroupRowClickable(
                            title = "Tavoite",
                            leadingIconRes = R.drawable.mobile_ic_transit_walk,
                            valueText = labelFor(STEP_GOAL_OPTIONS, stepGoal),
                            showChevron = true,
                        ) { showStepGoalDialog = true }
                    }
                    SubHeader("Ilmoitukset", R.drawable.mobile_ic_info_24)
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, WorkoutTrackingService.KEY_WORKOUT_KM_NOTIFY, "Kilometri-ilmoitus",
                            subtitle = "Ääni-ilmoitus joka täyden kilometrin täyttyessä lenkillä",
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = true,
                        )
                        GroupDivider()
                        GroupPrefSwitchRow(
                            prefs, StepGoalNotifier.KEY_ENABLED, "Askeltavoite saavutettu",
                            subtitle = "Ilmoita kun saavutat päivän askeltavoitteen",
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = false,
                            onChange = { if (it) enableNotif() },
                        )
                    }
                }

                // ============================ Ruuvi-anturit ============================
                SettingsPage.RUUVI -> {
                    SettingsPageTitle(
                        "Ruuvi-anturit",
                        "Skannaa ja nimeä RuuviTag-anturit. Lukemat näkyvät etusivulla ja Anturit-näkymässä.",
                    )
                    SubHeader("Anturit")
                    GroupCard {
                        GroupRowClickable(
                            title = "Etsi antureita (Bluetooth)",
                            subtitle = "Skannaa lähellä olevat RuuviTagit ja nimeä napauttamalla",
                            leadingIconRes = R.drawable.mobile_ic_bluetooth_24,
                        ) { requestScan() }
                        namedSensors.forEach { e ->
                            GroupDivider()
                            GroupRowClickable(
                                title = e.value,
                                subtitle = macSummary(repo, e.key),
                                leadingIconRes = R.drawable.mobile_ic_thermometer_24,
                            ) { editSensorMac = e.key }
                        }
                    }
                    if (namedSensors.isEmpty()) {
                        Text(
                            "Antureita voi nimetä rajattomasti. Nimetyt anturit näkyvät tässä.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                        )
                    }
                }

                // ============================ Ulkoasu ============================
                SettingsPage.APPEARANCE -> {
                    SettingsPageTitle("Ulkoasu")
                    SubHeader("Teema")
                    GroupCard {
                        GroupRowClickable(
                            title = "Teema",
                            leadingIconRes = R.drawable.mobile_ic_palette_24,
                            valueText = labelFor(THEME_OPTIONS, themeMode),
                            showChevron = true,
                        ) { showThemeDialog = true }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            GroupDivider()
                            var dynamic by remember {
                                mutableStateOf(prefs.getBoolean(MobileThemeController.KEY_DYNAMIC_COLOR, false))
                            }
                            GroupRowSwitch(
                                title = "Käytä laitteen värejä",
                                subtitle = "Material You -värit taustakuvasta. Pois päältä: oma kirkas brändipaletti.",
                                leadingIconRes = R.drawable.mobile_ic_palette_24,
                                checked = dynamic,
                            ) {
                                dynamic = it
                                prefs.edit().putBoolean(MobileThemeController.KEY_DYNAMIC_COLOR, it).apply()
                                (context as? android.app.Activity)?.recreate()
                            }
                        }
                    }
                }

                // ============================ Sovellus & data ============================
                SettingsPage.APP_DATA -> {
                    SettingsPageTitle("Sovellus & data")
                    SubHeader("Tietojen päivitys")
                    GroupCard {
                        // Koskee KAIKKIA automaattisesti haettavia tietoja (sää, sähkö, uutiset,
                        // liikenne…) — kuinka usein näkyvä sivu virkistetään sovelluksen ollessa auki.
                        GroupRowClickable(
                            title = "Tietojen päivitysväli",
                            subtitle = "Kaikki tiedot (sää, sähkö, uutiset…)",
                            leadingIconRes = R.drawable.mobile_ic_clock_24,
                            valueText = labelFor(INTERVAL_OPTIONS, interval),
                            showChevron = true,
                        ) { showIntervalDialog = true }
                    }
                    SubHeader("Ilmoitukset", R.drawable.mobile_ic_info_24)
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, AppUpdateNotifier.KEY_ENABLED, "Sovelluspäivitykset",
                            subtitle = "Ilmoita kun Arkikeskuksesta on uusi versio",
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = false,
                            onChange = { if (it) enableNotif() },
                        )
                    }
                    SubHeader("Varmuuskopiointi", R.drawable.mobile_ic_backup_24)
                    GroupCard {
                        GroupRowClickable(
                            title = "Varmuuskopiointi Driveen",
                            subtitle = "WhatsApp-tyylinen Google Drive -varmuuskopio",
                            leadingIconRes = R.drawable.mobile_ic_backup_24,
                            showChevron = true,
                        ) { showDriveBackup = true }
                        GroupDivider()
                        GroupRowClickable(
                            title = "Vie varmuuskopio (tiedostoon)",
                            subtitle = "Asetukset ja lenkit yhteen tiedostoon (esim. Driveen)",
                            leadingIconRes = R.drawable.mobile_ic_backup_24,
                        ) {
                            backupExportLauncher.launch(
                                "Arkikeskus-varmuuskopio-" +
                                    SimpleDateFormat("yyyy-MM-dd", FI).format(Date()) + ".json")
                        }
                        GroupDivider()
                        GroupRowClickable(
                            title = "Palauta varmuuskopio (tiedostosta)",
                            subtitle = "Tuo aiemmin viety varmuuskopiotiedosto",
                            leadingIconRes = R.drawable.mobile_ic_restore_24,
                        ) {
                            backupRestoreLauncher.launch(
                                arrayOf("application/json", "application/octet-stream"))
                        }
                    }
                }

                // ============================ Tietoja ============================
                SettingsPage.ABOUT -> {
                    SettingsPageTitle("Tietoja")
                    SubHeader("Sovellus")
                    AboutGroup(context)
                }
            }
        }
    }

    // ---------- Dialogit ----------
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
    if (showDriveBackup) {
        DriveBackupScreen(onClose = { showDriveBackup = false })
        BackHandler { showDriveBackup = false }
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
    if (showStepGoalDialog) {
        RadioDialog(
            title = "Askeltavoite",
            options = STEP_GOAL_OPTIONS,
            current = stepGoal,
            onPick = { value ->
                showStepGoalDialog = false
                prefs.edit().putString(StepGoalNotifier.KEY_GOAL, value).apply()
                refreshTick++
            },
            onDismiss = { showStepGoalDialog = false },
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

// ===================== Otsikko- ja ryhmäkomponentit =====================

/** Varmuuskopion koko luettavana: "11,6 kt" / "1,2 Mt" — kasvava luku kertoo että dataa kertyy. */
private fun formatBackupBytes(bytes: Int): String =
    if (bytes < 1024 * 1024) String.format(FI, "%.1f kt", bytes / 1024.0)
    else String.format(FI, "%.1f Mt", bytes / (1024.0 * 1024.0))

/** Iso vasemmalle tasattu sivuotsikko (sama tyyli kuin "Lenkki"/"Häiriöt ja muutokset"); ei nuolta/ikonia. */
@Composable
private fun SettingsPageTitle(title: String, description: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (description != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Pieni harmaa aliotsikko (versaalit). Ilmoitukset-otsikossa pieni kuvake edessä. */
@Composable
private fun SubHeader(text: String, leadingIconRes: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIconRes != null) {
            Icon(
                painter = painterResource(leadingIconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text.uppercase(FI),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
    }
}

/** Yksi pyöristetty kortti, jonka sisälle ladotaan tiiviit rivit + [GroupDivider]it (säästää tilaa). */
@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    ArkiCard(modifier = Modifier.fillMaxWidth(), shape = ItemBoxShape, content = content)
}

/**
 * Ohut sisennetty jakaja ryhmäkortin rivien välissä. Väri johdetaan kortin SISÄLLÖN väristä
 * (onSurfaceVariant) matalalla alfalla → erottuu kortin pinnasta (surfaceVariant) sekä vaalealla
 * ETTÄ tummalla teemalla. (outlineVariant on tummassa identtinen surfaceVariantin kanssa → näkymätön.)
 */
@Composable
private fun GroupDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** Keskussivun kategoriarivi: oma pyöristetty kortti + chevron oikealla. */
@Composable
private fun HubRow(
    title: String,
    subtitle: String,
    leadingIconRes: Int,
    onClick: () -> Unit,
) {
    ClickableRow(
        title = title,
        subtitle = subtitle,
        leadingIconRes = leadingIconRes,
        trailingIconRes = R.drawable.mobile_ic_chevron_right_24,
        onClick = onClick,
    )
}

/** Klikattava ryhmärivi (ei omaa korttia). Valinnainen arvoteksti + chevron oikealla. */
@Composable
private fun GroupRowClickable(
    title: String,
    subtitle: String? = null,
    leadingIconRes: Int? = null,
    valueText: String? = null,
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIconRes != null) RowLeadingIcon(leadingIconRes)
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
        if (valueText != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.mobile_ic_chevron_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Ei-klikattava arvorivi (otsikko vasemmalla, arvo oikealla). */
@Composable
private fun GroupRowInfo(title: String, value: String, leadingIconRes: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIconRes != null) RowLeadingIcon(leadingIconRes)
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Kytkinrivi ryhmäkortissa (ei omaa korttia). */
@Composable
private fun GroupRowSwitch(
    title: String,
    subtitle: String? = null,
    leadingIconRes: Int? = null,
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
        if (leadingIconRes != null) RowLeadingIcon(leadingIconRes)
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

/** SharedPreferences-boolean-kytkin ryhmäkortissa (lukee/kirjoittaa avaimen itse). */
@Composable
private fun GroupPrefSwitchRow(
    prefs: SharedPreferences,
    key: String,
    title: String,
    subtitle: String? = null,
    leadingIconRes: Int? = null,
    default: Boolean,
    onChange: ((Boolean) -> Unit)? = null,
) {
    var checked by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    GroupRowSwitch(title = title, subtitle = subtitle, leadingIconRes = leadingIconRes, checked = checked) {
        checked = it
        prefs.edit().putBoolean(key, it).apply()
        onChange?.invoke(it)
    }
}

// ===================== Jaetut rivikomponentit (myös muiden näkymien käytössä) =====================

@Composable
internal fun ClickableRow(
    title: String,
    subtitle: String? = null,
    leadingIconRes: Int? = null,
    trailingIconRes: Int? = null,
    onClick: () -> Unit,
) {
    ArkiCard(modifier = Modifier.fillMaxWidth(), shape = ItemBoxShape) {
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
internal fun RowLeadingIcon(iconRes: Int) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
    )
    Spacer(Modifier.width(16.dp))
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String? = null,
    leadingIconRes: Int? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ArkiCard(modifier = Modifier.fillMaxWidth(), shape = ItemBoxShape) {
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
 * Tietoja sovelluksesta (ABOUT-alasivu): nykyinen versio, päivitystarkistus (GitHub-julkaisut) ja
 * GitHub-linkki. Jos uudempi versio löytyy, se voidaan ladata ja asentaa suoraan ([AppUpdater]).
 */
@Composable
private fun AboutGroup(context: Context) {
    val current = remember { appVersion(context) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdater.ReleaseInfo?>(null) }

    GroupCard {
        GroupRowInfo(
            title = "Sovelluksen versio",
            value = current,
            leadingIconRes = R.drawable.mobile_ic_info_24,
        )
        GroupDivider()
        GroupRowClickable(
            title = if (checking) "Tarkistetaan päivityksiä…" else "Tarkista päivitykset",
            subtitle = status,
            leadingIconRes = R.drawable.mobile_ic_refresh_24,
        ) {
            if (checking || downloading) return@GroupRowClickable
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
            GroupDivider()
            GroupRowClickable(
                title = if (downloading) "Ladataan ja asennetaan…" else "Lataa ja asenna ${avail.versionName}",
                subtitle = "Haetaan GitHub-julkaisusta. Asennukseen tarvitaan lupa asentaa tuntemattomista lähteistä.",
                leadingIconRes = R.drawable.mobile_ic_download_24,
            ) {
                if (downloading) return@GroupRowClickable
                downloading = true
                AppUpdater.downloadAndInstall(context, avail.apkUrl) { msg ->
                    downloading = false
                    status = msg
                }
            }
        }
        GroupDivider()
        GroupRowClickable(
            title = "GitHub – lähdekoodi ja julkaisut",
            subtitle = "github.com/jrs8205/Arkikeskus",
            leadingIconRes = R.drawable.mobile_ic_code_24,
        ) {
            openUrl(context, AppUpdater.REPO_URL)
        }
    }
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
