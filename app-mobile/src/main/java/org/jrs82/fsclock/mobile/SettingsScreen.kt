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
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.mobile.widget.ElectricityWidgetRedraw
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.ruuvi.RuuviRepository
import org.jrs82.fsclock.ruuvi.RuuviSample
import java.text.NumberFormat
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

/** Yhtenäinen pyöristetty laatikkomuoto valikon ja asetusten kohdille (sama molemmilla sivuilla). */
internal val ItemBoxShape = RoundedCornerShape(20.dp)

/** Asetusten keskussivu + alasivut. Järjestys = keskussivun kategorioiden järjestys. */
enum class SettingsPage(@StringRes val titleRes: Int) {
    HUB(R.string.common_settings),
    WEATHER(R.string.settings_page_weather),
    ELECTRICITY(R.string.settings_page_electricity),
    NEWS(R.string.settings_page_news),
    TRANSIT(R.string.settings_page_transit),
    FITNESS(R.string.settings_page_fitness),
    RUUVI(R.string.settings_page_ruuvi),
    APPEARANCE(R.string.settings_page_appearance),
    APP_DATA(R.string.settings_page_app_data),
    ABOUT(R.string.settings_page_about),
}

/** Sivun talletus Activityn uudelleenluontiin (teemanvaihto) — käyttäjä jää samalle alasivulle. */
internal val SettingsPageSaver = Saver<SettingsPage, String>(
    save = { it.name },
    restore = { runCatching { SettingsPage.valueOf(it) }.getOrDefault(SettingsPage.HUB) },
)

private val THEME_OPTIONS = listOf(
    MobileThemeController.VALUE_SYSTEM to R.string.settings_theme_system,
    MobileThemeController.VALUE_LIGHT to R.string.settings_theme_light,
    MobileThemeController.VALUE_DARK to R.string.settings_theme_dark,
)
private val CHEAP_MODE_OPTIONS = listOf(
    MobileThemeController.CHEAP_MODE_CURRENT to R.string.settings_cheap_mode_current,
    MobileThemeController.CHEAP_MODE_REMAINING_DAY to R.string.settings_cheap_mode_rest_of_day,
    MobileThemeController.CHEAP_MODE_ALL_DAY to R.string.settings_cheap_mode_all_day,
)
// Arvot tallentuvat prefseihin merkkijonoina ("10", "7000") kuten ennenkin; vain näkyvä nimi muotoillaan kielen mukaan.
private val INTERVAL_MINUTES = listOf(10, 15, 30, 60, 120)
private val STEP_GOALS = listOf(7000, 8000, 10000, 12000, 15000, 20000)

/** Prefs-arvo → näkyvä nimi nykyisellä kielellä. */
@Composable
private fun resolvedOptions(options: List<Pair<String, Int>>): List<Pair<String, String>> =
    options.map { (value, res) -> value to stringResource(res) }

@Composable
private fun intervalOptionList(): List<Pair<String, String>> =
    INTERVAL_MINUTES.map { it.toString() to pluralStringResource(R.plurals.settings_interval_minutes, it, it) }

/** Tuhaterotin kielen mukaan: fi/sv "7 000", en "7,000". */
@Composable
private fun stepGoalOptionList(locale: Locale): List<Pair<String, String>> {
    val nf = NumberFormat.getIntegerInstance(locale)
    return STEP_GOALS.map {
        it.toString() to pluralStringResource(R.plurals.settings_step_goal_steps, it, nf.format(it))
    }
}

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
    val locale = AppLocale.current(context)

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
                toast(context, R.string.settings_bt_off)
            }
        } else {
            toast(context, R.string.settings_bt_scan_permission)
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
            toast(context, R.string.settings_notif_permission_needed)
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
                toast(context, R.string.settings_bt_off)
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
                            if (res != null) context.getString(
                                R.string.backup_saved_file,
                                context.resources.getQuantityString(R.plurals.backup_workouts, res.workouts, res.workouts),
                                context.resources.getQuantityString(R.plurals.backup_settings, res.prefs, res.prefs),
                                formatBackupSize(context, res.bytes.toLong()),
                            )
                            else context.getString(R.string.backup_save_failed),
                        )
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        toast(context, context.getString(R.string.backup_failed_reason, e.message ?: e.javaClass.simpleName))
                    }
                }
            }.start()
        }
    }
    // Jaettu palautuspolku: SAF-valitsimesta TAI suoraan automaattibackupin persistoidusta URIsta.
    val runRestoreFrom: (android.net.Uri) -> Unit = { uri ->
        if (WorkoutTracker.state.value.phase != WorkoutTracker.Phase.IDLE) {
            toast(context, R.string.backup_stop_workout_first)
        } else {
            Thread {
                try {
                    val res = context.contentResolver.openInputStream(uri)?.use { ins ->
                        BackupManager.restore(context, ins)
                    }
                    Handler(Looper.getMainLooper()).post {
                        if (res != null) restoreDone = res
                        else toast(context, R.string.backup_restore_failed)
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        toast(context, context.getString(R.string.backup_restore_failed_reason, e.displayMessage(context)))
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
    // Taustasijainti ("Salli aina"): kotinäytön widgetit voivat päivittää paikan myös sovelluksen ollessa
    // kiinni vain jos tämä on myönnetty. Android vaatii erillisen pyynnön etualan luvan jälkeen.
    var bgLocationGranted by remember {
        mutableStateOf(hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }
    // Etualan lupa luetaan uudelleen joka paluulla (esim. järjestelmän asetuksista): kytkin ei saa näyttää
    // päällä-tilaa, jos lupaa ei ole, eikä puuttuvan luvan ohje saa jäädä näkyviin luvan myöntämisen jälkeen.
    var locationGranted by remember { mutableStateOf(hasAnyLocationPermission(context)) }
    val settingsLifecycle = LocalLifecycleOwner.current
    DisposableEffect(settingsLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationGranted = hasAnyLocationPermission(context)
                bgLocationGranted = hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        settingsLifecycle.lifecycle.addObserver(observer)
        onDispose { settingsLifecycle.lifecycle.removeObserver(observer) }
    }
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        bgLocationGranted = hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        toast(
            context,
            if (bgLocationGranted) R.string.settings_bg_location_granted
            else R.string.settings_bg_location_denied,
        )
    }
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val precise = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        locationGranted = hasAnyLocationPermission(context)
        if (!precise) SettingsManager.get().clearHomeCoordinates()
        DeviceLocalPrefs.markLocationPermissionAsked(context)
        prefs.edit()
            .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, precise)
            .remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME)
            .apply()
        autoLocation = precise
        if (precise) {
            resetAutoLocationThrottle()
            // Etualan lupa saatu → pyydä heti myös taustasijainti, jotta widgetit päivittyvät taustalla.
            if (!hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        toast(
            context,
            if (precise) R.string.settings_auto_location_on
            else R.string.settings_precise_location_denied,
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
    val vatOn = remember(refreshTick) { ElectricityVat.enabled(prefs) }
    val stepGoal = prefs.getString(StepGoalNotifier.KEY_GOAL, StepGoalNotifier.DEFAULT_GOAL)
        ?: StepGoalNotifier.DEFAULT_GOAL
    val builtinFeeds = remember { NewsFeedStore.allFeeds(prefs).filter { it.builtin } }
    val customFeeds = remember(refreshTick) { NewsFeedStore.customFeeds(prefs) }
    val namedSensors = remember(refreshTick) { SettingsManager.get().sensorNamesByMac().entries.toList() }
    val themeOptions = resolvedOptions(THEME_OPTIONS)
    val cheapModeOptions = resolvedOptions(CHEAP_MODE_OPTIONS)
    val intervalOptions = intervalOptionList()
    val stepGoalOptions = stepGoalOptionList(locale)

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
                    SettingsPageTitle(stringResource(SettingsPage.HUB.titleRes))
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val ruuviSubtitle = if (namedSensors.isNotEmpty())
                            pluralStringResource(R.plurals.settings_hub_ruuvi_sensors, namedSensors.size, namedSensors.size)
                        else stringResource(R.string.settings_hub_ruuvi_find)
                        // Etusivu ylimpänä; avaa SUORAAN korttijärjestelynäkymän (ei turhaa välisivua).
                        HubRow(R.string.settings_hub_home, stringResource(R.string.settings_hub_home_sub),
                            R.drawable.mobile_ic_dashboard_24) {
                            context.startActivity(Intent(context, MobileWidgetOrderActivity::class.java))
                        }
                        HubRow(SettingsPage.WEATHER.titleRes, stringResource(R.string.settings_hub_weather_sub),
                            R.drawable.mobile_ic_location_24) { onPageChange(SettingsPage.WEATHER) }
                        HubRow(SettingsPage.ELECTRICITY.titleRes, stringResource(R.string.settings_hub_electricity_sub),
                            R.drawable.mobile_ic_bolt_24) { onPageChange(SettingsPage.ELECTRICITY) }
                        HubRow(SettingsPage.NEWS.titleRes, stringResource(R.string.settings_hub_news_sub),
                            R.drawable.mobile_ic_news_24) { onPageChange(SettingsPage.NEWS) }
                        HubRow(SettingsPage.TRANSIT.titleRes, stringResource(R.string.settings_hub_transit_sub),
                            R.drawable.mobile_ic_bus_24) { onPageChange(SettingsPage.TRANSIT) }
                        HubRow(SettingsPage.FITNESS.titleRes, stringResource(R.string.settings_hub_fitness_sub),
                            R.drawable.mobile_ic_transit_walk) { onPageChange(SettingsPage.FITNESS) }
                        HubRow(SettingsPage.RUUVI.titleRes, ruuviSubtitle,
                            R.drawable.mobile_ic_thermometer_24) { onPageChange(SettingsPage.RUUVI) }
                        HubRow(SettingsPage.APPEARANCE.titleRes, stringResource(R.string.settings_hub_appearance_sub),
                            R.drawable.mobile_ic_palette_24) { onPageChange(SettingsPage.APPEARANCE) }
                        HubRow(SettingsPage.APP_DATA.titleRes, stringResource(R.string.settings_hub_app_data_sub),
                            R.drawable.mobile_ic_tune_24) { onPageChange(SettingsPage.APP_DATA) }
                        HubRow(SettingsPage.ABOUT.titleRes, stringResource(R.string.settings_hub_about_sub, appVersion(context)),
                            R.drawable.mobile_ic_info_24) { onPageChange(SettingsPage.ABOUT) }
                    }
                }

                // ============================ Sää & sijainti ============================
                SettingsPage.WEATHER -> {
                    SettingsPageTitle(stringResource(SettingsPage.WEATHER.titleRes))
                    SubHeader(stringResource(R.string.settings_location_header))
                    GroupCard {
                        val locationSwitch = LocationPermissionPolicy.switchState(autoLocation, locationGranted)
                        GroupRowSwitch(
                            title = stringResource(R.string.settings_auto_location),
                            subtitle = stringResource(
                                if (locationSwitch.permissionMissing) R.string.settings_auto_location_permission_missing
                                else R.string.settings_auto_location_sub,
                            ),
                            leadingIconRes = R.drawable.mobile_ic_location_24,
                            checked = locationSwitch.checked,
                        ) { enable ->
                            if (enable) {
                                if (hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                                    autoLocation = true
                                    prefs.edit()
                                        .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true)
                                        .apply()
                                    resetAutoLocationThrottle()
                                } else {
                                    DeviceLocalPrefs.markLocationPermissionAsked(context)
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
                        if (locationSwitch.checked && !bgLocationGranted) {
                            GroupDivider()
                            GroupRowClickable(
                                title = stringResource(R.string.settings_bg_widgets),
                                subtitle = stringResource(R.string.settings_bg_widgets_sub),
                                leadingIconRes = R.drawable.mobile_ic_location_24,
                                showChevron = true,
                            ) {
                                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        }
                        GroupDivider()
                        GroupRowInfo(
                            title = stringResource(R.string.settings_last_weather_update),
                            value = lastUpdateText(context),
                            leadingIconRes = R.drawable.mobile_ic_clock_24,
                        )
                    }
                    SubHeader(stringResource(R.string.settings_notifications_header), R.drawable.mobile_ic_info_24)
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, WeatherWarningNotifier.KEY_ENABLED, stringResource(R.string.settings_notify_warnings),
                            subtitle = stringResource(R.string.settings_notify_warnings_sub),
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = false,
                            onChange = { if (it) enableNotif() },
                        )
                    }
                }

                // ============================ Pörssisähkö ============================
                SettingsPage.ELECTRICITY -> {
                    SettingsPageTitle(stringResource(SettingsPage.ELECTRICITY.titleRes))
                    SubHeader(stringResource(R.string.settings_prices_header))
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, ElectricityVat.KEY, stringResource(R.string.settings_vat_title),
                            subtitle = stringResource(if (vatOn) R.string.settings_vat_on else R.string.settings_vat_off),
                            leadingIconRes = R.drawable.mobile_ic_bolt_24, default = ElectricityVat.DEFAULT,
                            onChange = {
                                refreshTick++
                                // Pelkkä cache-piirto: ei saa odottaa yleisen workerin verkkohakuja.
                                ElectricityWidgetRedraw.redrawAsync(context)
                            },
                        )
                    }
                    SubHeader(stringResource(R.string.settings_cheap_header))
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, MobileThemeController.KEY_CHEAP_ELECTRICITY_NOTICE, stringResource(R.string.settings_cheap_notice),
                            subtitle = stringResource(R.string.settings_cheap_notice_sub),
                            leadingIconRes = R.drawable.mobile_ic_bolt_24, default = true,
                        )
                        GroupDivider()
                        GroupRowClickable(
                            title = stringResource(R.string.settings_cheap_threshold),
                            leadingIconRes = R.drawable.mobile_ic_bolt_24,
                            valueText = "$threshold c/kWh",
                            showChevron = true,
                        ) { showThresholdDialog = true }
                        GroupDivider()
                        GroupRowClickable(
                            title = stringResource(R.string.settings_cheap_mode),
                            leadingIconRes = R.drawable.mobile_ic_clock_24,
                            valueText = labelFor(cheapModeOptions, cheapMode),
                            showChevron = true,
                        ) { showModeDialog = true }
                    }
                    SubHeader(stringResource(R.string.settings_notifications_header), R.drawable.mobile_ic_info_24)
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, ElectricityNotifier.KEY_ENABLED, stringResource(R.string.settings_notify_tomorrow_prices),
                            subtitle = stringResource(R.string.settings_notify_tomorrow_prices_sub),
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = false,
                            onChange = { if (it) enableNotif() },
                        )
                    }
                }

                // ============================ Uutiset ============================
                SettingsPage.NEWS -> {
                    SettingsPageTitle(stringResource(SettingsPage.NEWS.titleRes))
                    SubHeader(stringResource(R.string.settings_news_sources_header, builtinFeeds.size))
                    GroupCard {
                        builtinFeeds.forEachIndexed { index, feed ->
                            GroupPrefSwitchRow(
                                prefs, feed.enabledKey(), feed.name,
                                leadingIconRes = R.drawable.mobile_ic_news_24, default = true,
                            )
                            if (index < builtinFeeds.lastIndex) GroupDivider()
                        }
                    }
                    SubHeader(stringResource(R.string.settings_custom_feeds_header))
                    GroupCard {
                        customFeeds.forEach { feed ->
                            GroupRowClickable(
                                title = feed.name, subtitle = feed.url,
                                leadingIconRes = R.drawable.mobile_ic_rss_24,
                            ) { editFeed = feed }
                            GroupDivider()
                        }
                        GroupRowClickable(
                            title = stringResource(R.string.settings_add_feed),
                            subtitle = stringResource(R.string.settings_add_feed_sub),
                            leadingIconRes = R.drawable.mobile_ic_add_24,
                        ) { addFeed = true }
                    }
                    SubHeader(stringResource(R.string.settings_news_categories_header, FOREIGN_CATEGORY_TAGS.size))
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
                        stringResource(SettingsPage.TRANSIT.titleRes),
                        stringResource(R.string.settings_transit_desc),
                    )
                    SubHeader(stringResource(R.string.settings_notifications_header), R.drawable.mobile_ic_info_24)
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, HslAlertNotifier.KEY_ENABLED, stringResource(R.string.settings_notify_hsl),
                            subtitle = stringResource(R.string.settings_notify_hsl_sub),
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = false,
                            onChange = { if (it) enableNotif() },
                        )
                    }
                }

                // ============================ Lenkki & askeleet ============================
                SettingsPage.FITNESS -> {
                    SettingsPageTitle(stringResource(SettingsPage.FITNESS.titleRes))
                    SubHeader(stringResource(R.string.settings_step_goal))
                    GroupCard {
                        GroupRowClickable(
                            title = stringResource(R.string.settings_goal),
                            leadingIconRes = R.drawable.mobile_ic_transit_walk,
                            valueText = labelFor(stepGoalOptions, stepGoal),
                            showChevron = true,
                        ) { showStepGoalDialog = true }
                    }
                    SubHeader(stringResource(R.string.settings_notifications_header), R.drawable.mobile_ic_info_24)
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, WorkoutTrackingService.KEY_WORKOUT_KM_NOTIFY, stringResource(R.string.settings_km_announcement),
                            subtitle = stringResource(R.string.settings_km_announcement_sub),
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = true,
                        )
                        GroupDivider()
                        GroupPrefSwitchRow(
                            prefs, StepGoalNotifier.KEY_ENABLED, stringResource(R.string.settings_notify_step_goal),
                            subtitle = stringResource(R.string.settings_notify_step_goal_sub),
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = false,
                            onChange = { if (it) enableNotif() },
                        )
                    }
                }

                // ============================ Ruuvi-anturit ============================
                SettingsPage.RUUVI -> {
                    SettingsPageTitle(
                        stringResource(SettingsPage.RUUVI.titleRes),
                        stringResource(R.string.settings_ruuvi_desc),
                    )
                    SubHeader(stringResource(R.string.settings_sensors_header))
                    GroupCard {
                        GroupRowClickable(
                            title = stringResource(R.string.settings_find_sensors_bt),
                            subtitle = stringResource(R.string.settings_find_sensors_sub),
                            leadingIconRes = R.drawable.mobile_ic_bluetooth_24,
                        ) { requestScan() }
                        namedSensors.forEach { e ->
                            GroupDivider()
                            GroupRowClickable(
                                title = e.value,
                                subtitle = macSummary(context, repo, e.key),
                                leadingIconRes = R.drawable.mobile_ic_thermometer_24,
                            ) { editSensorMac = e.key }
                        }
                    }
                    if (namedSensors.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_sensors_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                        )
                    }
                }

                // ============================ Ulkoasu ============================
                SettingsPage.APPEARANCE -> {
                    SettingsPageTitle(stringResource(SettingsPage.APPEARANCE.titleRes))
                    SubHeader(stringResource(R.string.settings_theme))
                    GroupCard {
                        GroupRowClickable(
                            title = stringResource(R.string.settings_theme),
                            leadingIconRes = R.drawable.mobile_ic_palette_24,
                            valueText = labelFor(themeOptions, themeMode),
                            showChevron = true,
                        ) { showThemeDialog = true }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            GroupDivider()
                            var dynamic by remember {
                                mutableStateOf(prefs.getBoolean(MobileThemeController.KEY_DYNAMIC_COLOR, false))
                            }
                            GroupRowSwitch(
                                title = stringResource(R.string.settings_dynamic_color),
                                subtitle = stringResource(R.string.settings_dynamic_color_sub),
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
                    SettingsPageTitle(stringResource(SettingsPage.APP_DATA.titleRes))
                    SubHeader(stringResource(R.string.settings_data_update_header))
                    GroupCard {
                        // Koskee KAIKKIA automaattisesti haettavia tietoja (sää, sähkö, uutiset,
                        // liikenne…) — kuinka usein näkyvä sivu virkistetään sovelluksen ollessa auki.
                        GroupRowClickable(
                            title = stringResource(R.string.settings_update_interval),
                            subtitle = stringResource(R.string.settings_update_interval_sub),
                            leadingIconRes = R.drawable.mobile_ic_clock_24,
                            valueText = labelFor(intervalOptions, interval),
                            showChevron = true,
                        ) { showIntervalDialog = true }
                    }
                    SubHeader(stringResource(R.string.settings_notifications_header), R.drawable.mobile_ic_info_24)
                    GroupCard {
                        GroupPrefSwitchRow(
                            prefs, AppUpdateNotifier.KEY_ENABLED, stringResource(R.string.settings_notify_app_update),
                            subtitle = stringResource(R.string.settings_notify_app_update_sub),
                            leadingIconRes = R.drawable.mobile_ic_info_24, default = false,
                            onChange = { if (it) enableNotif() },
                        )
                    }
                    SubHeader(stringResource(R.string.backup_title), R.drawable.mobile_ic_backup_24)
                    GroupCard {
                        GroupRowClickable(
                            title = stringResource(R.string.backup_drive_row),
                            subtitle = stringResource(R.string.backup_drive_row_sub),
                            leadingIconRes = R.drawable.mobile_ic_backup_24,
                            showChevron = true,
                        ) { showDriveBackup = true }
                        GroupDivider()
                        GroupRowClickable(
                            title = stringResource(R.string.backup_export_row),
                            subtitle = stringResource(R.string.backup_export_row_sub),
                            leadingIconRes = R.drawable.mobile_ic_backup_24,
                        ) {
                            // Päiväys konemuodossa (lajittuu tiedostolistassa), vain etuliite käännetään.
                            backupExportLauncher.launch(
                                context.getString(
                                    R.string.backup_file_name,
                                    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()),
                                ),
                            )
                        }
                        GroupDivider()
                        GroupRowClickable(
                            title = stringResource(R.string.backup_import_row),
                            subtitle = stringResource(R.string.backup_import_row_sub),
                            leadingIconRes = R.drawable.mobile_ic_restore_24,
                        ) {
                            backupRestoreLauncher.launch(
                                arrayOf("application/json", "application/octet-stream"))
                        }
                    }
                }

                // ============================ Tietoja ============================
                SettingsPage.ABOUT -> {
                    SettingsPageTitle(stringResource(SettingsPage.ABOUT.titleRes))
                    SubHeader(stringResource(R.string.settings_app_header))
                    AboutGroup(context)
                }
            }
        }
    }

    // ---------- Dialogit ----------
    restoreDone?.let { res ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.backup_restored_title)) },
            text = { Text(restoreSummaryText(res, R.string.backup_restart_note_full)) },
            confirmButton = {
                TextButton(onClick = {
                    restoreDone = null
                    // Aito uudelleenkäynnistys erillisen prosessin kautta → splash näkyy
                    // (suoraan itsensä käynnistävältä kuolevalta prosessilta Android ohittaa sen).
                    RestartActivity.restartApp(context)
                }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
    if (showDriveBackup) {
        DriveBackupScreen(onClose = { showDriveBackup = false })
        BackHandler { showDriveBackup = false }
    }
    if (showThemeDialog) {
        RadioDialog(
            title = stringResource(R.string.settings_theme),
            options = themeOptions,
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
            title = stringResource(R.string.settings_cheap_mode),
            options = cheapModeOptions,
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
            title = stringResource(R.string.settings_update_interval),
            options = intervalOptions,
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
            title = stringResource(R.string.settings_step_goal),
            options = stepGoalOptions,
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
            title = stringResource(R.string.settings_cheap_threshold),
            initial = threshold,
            label = stringResource(
                if (vatOn) R.string.settings_threshold_label_vat else R.string.settings_threshold_label_no_vat,
            ),
            keyboardType = KeyboardType.Decimal,
            onSave = { value ->
                showThresholdDialog = false
                val cleaned = value.trim().replace(',', '.')
                if (cleaned.toDoubleOrNull() != null) {
                    prefs.edit().putString(MobileThemeController.KEY_CHEAP_ELECTRICITY_THRESHOLD, cleaned).apply()
                    refreshTick++
                } else {
                    toast(context, R.string.settings_threshold_invalid)
                }
            },
            onDismiss = { showThresholdDialog = false },
        )
    }
    editSensorMac?.let { mac ->
        TextFieldDialog(
            title = stringResource(R.string.settings_sensor_name_title, mac),
            initial = SettingsManager.get().sensorNameFor(mac),
            label = stringResource(R.string.settings_sensor_name_label),
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
                    toast(context, R.string.settings_feed_invalid_url)
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
                    toast(context, R.string.settings_feed_invalid_url)
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
    val locale = AppLocale.current(context)
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
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) } },
        title = { Text(stringResource(R.string.settings_scan_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (samples.isEmpty()) {
                    Text(stringResource(R.string.settings_scan_searching), style = MaterialTheme.typography.bodyLarge)
                } else {
                    samples.forEach { (mac, s) ->
                        val temp = s.temperatureC()?.let { String.format(locale, "%.1f °C", it) } ?: "– °C"
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
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
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
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
        confirmButton = { TextButton(onClick = { onSave(name, url) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        title = {
            Text(stringResource(if (existing == null) R.string.settings_add_feed else R.string.settings_edit_feed))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_feed_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_feed_url_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.settings_delete_feed), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    )
}

// ===================== Otsikko- ja ryhmäkomponentit =====================

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
            text.uppercase(AppLocale.current(LocalContext.current)),
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
    @StringRes titleRes: Int,
    subtitle: String,
    leadingIconRes: Int,
    onClick: () -> Unit,
) {
    ClickableRow(
        title = stringResource(titleRes),
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
            title = stringResource(R.string.settings_app_version),
            value = current,
            leadingIconRes = R.drawable.mobile_ic_info_24,
        )
        GroupDivider()
        GroupRowClickable(
            title = stringResource(if (checking) R.string.settings_checking_updates else R.string.settings_check_updates),
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
                    rel == null -> context.getString(R.string.settings_update_check_failed)
                    newer -> context.getString(R.string.settings_update_available, rel.versionName)
                    else -> context.getString(R.string.settings_up_to_date)
                }
            }
        }
        val avail = update
        if (avail?.apkUrl != null) {
            GroupDivider()
            GroupRowClickable(
                title = if (downloading) stringResource(R.string.settings_downloading_installing)
            else stringResource(R.string.settings_download_install, avail.versionName),
                subtitle = stringResource(R.string.settings_download_install_sub),
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
            title = stringResource(R.string.settings_github),
            subtitle = "github.com/jrs8205/Arkikeskus",
            leadingIconRes = R.drawable.mobile_ic_code_24,
        ) {
            openUrl(context, AppUpdater.REPO_URL)
        }
        GroupDivider()
        GroupRowClickable(
            title = stringResource(R.string.settings_place_sources),
            subtitle = stringResource(R.string.settings_place_sources_sub),
            leadingIconRes = R.drawable.mobile_ic_location_24,
        ) {
            openUrl(context, PostalAreas.SOURCE_URL)
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        toast(context, R.string.settings_no_browser)
    }
}

// ===================== Apufunktiot =====================

private fun macSummary(context: Context, repo: RuuviRepository, mac: String?): String {
    if (mac.isNullOrEmpty()) return context.getString(R.string.settings_no_sensor)
    val s = repo.getLatest(mac)
    val temp = s?.temperatureC()
    return if (temp != null) "$mac · ${String.format(AppLocale.current(context), "%.1f °C", temp)}" else mac
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

private fun lastUpdateText(context: Context): String {
    val ts = SettingsManager.get().getLastSuccessfulFmiUpdate()
    if (ts <= 0L) return "—"
    return SimpleDateFormat(context.getString(R.string.settings_datetime_pattern), AppLocale.current(context))
        .format(Date(ts))
}

private fun bluetoothScanPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN
    else Manifest.permission.ACCESS_FINE_LOCATION

private fun hasPermission(context: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

/** Kuten sijainnin päivitys ([maybeRefreshDeviceLocation]): myös likimääräinen sijaintilupa riittää. */
private fun hasAnyLocationPermission(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun isBluetoothOn(context: Context): Boolean = try {
    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    bm?.adapter?.isEnabled == true
} catch (e: RuntimeException) {
    false
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

private fun toast(context: Context, @StringRes msg: Int) = toast(context, context.getString(msg))
