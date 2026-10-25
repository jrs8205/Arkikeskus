package org.jrs82.fsclock.mobile

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.mobile.widget.WidgetDeepLink

/** Käynnistyskuvan minimikesto millisekunteina, jotta sen ehtii nähdä (muuten vilahtaa ohi).
 *  450 ms = logo ehtii näkyä, mutta avaus tuntuu selvästi nopeammalta kuin 900 ms:lla. */
private const val SPLASH_MIN_MS = 450L

/** Process-static throttle widget-workerin onResume-virkistykselle. Activity voi relaunchata
 *  (night mode / dynamic color) → onResume laukeaa kahdesti; ei ajeta workeria turhaan tuplana.
 *  Top-level (ei kenttä), jotta se säilyy Activityn uudelleenluonnin yli samassa prosessissa. */
private var sLastWidgetRefreshMs = 0L

/**
 * Sovelluksen pääruutu (launcher) — koko UI on Compose ([ComposeMainScreen]). Korvasi
 * View-pohjaisen MobileMainActivityn (poistettu Compose-migraation valmistuttua).
 *
 * AppCompatActivity + night mode -pakotus, jotta sovelluksen teema-asetus (light/dark/system)
 * toimii ja Composen isSystemInDarkTheme() lukee sen oikein. Teema manifestissa =
 * Theme.Arkikeskus.Splash → MobileComposeTheme (NoActionBar, edge-to-edge).
 */
class MobileComposeMainActivity : AppCompatActivity() {

    /** Sovelluksen luonnissa luettu dynamic-color-tila; jos käyttäjä vaihtaa kytkimen
     *  asetuksissa, [onResume] recreatee Activityn → uusi väriteema otetaan käyttöön. */
    private var appliedDynamicColor = false

    /** Ulkoinen navigointipyyntö (lenkkinotifikaation napautus / lenkin palautus avauksessa).
     *  ComposeMainScreen kuluttaa arvon ja nollaa sen. */
    private val externalSection = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** Sijaintiluvan kysyntä ensikäynnistyksessä. Tulos käsitellään automaattisesti
     *  seuraavalla resume/päivityksellä ([maybeRefreshDeviceLocation]); ei tarvita callbackia. */
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Luvan myöntyessä etusivun sää + Lähilähdöt hakevat sijainnin onResumessa.
            resetAutoLocationThrottle()
        }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(FontScaleCap.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        // Käynnistyskuva (bränditausta + sovelluksen ikoni). Asennettava ennen super.onCreatea;
        // postSplashScreenTheme vaihtaa varsinaiseen teemaan kun ensimmäinen ruutu on valmis.
        // Pidetään splash näkyvissä lyhyen minimiajan, jotta sen ehtii nähdä (muuten vilahtaa ohi).
        val splash = installSplashScreen()
        val splashStart = System.currentTimeMillis()
        splash.setKeepOnScreenCondition { System.currentTimeMillis() - splashStart < SPLASH_MIN_MS }
        MobileThemeController.apply(this)
        delegate.setLocalNightMode(MobileThemeController.nightMode(this))
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SettingsManager.get().init(applicationContext)
        appliedDynamicColor = MobileThemeController.dynamicColor(this)
        maybeAskInitialLocationPermission()
        // Widget-/ilmoitus-deep-link: sektio luetaan ENSISIJAISESTI data-URIsta (arkikeskus://widget/
        // <sektio>) — se on PendingIntent-identiteetin osa ja toimitetaan luotettavasti — extra jää
        // fallbackiksi. EI kuluteta tässä: kylmässä käynnistyksessä Activity relaunchaa (night mode /
        // dynamic color) ENNEN kuin Compose ehtii navigoida, joten varhainen kulutus hukkaisi sektion
        // -> etusivu. Kulutus tehdään vasta navigoinnin jälkeen (onSectionConsumed -> setIntent(tyhjä)),
        // mikä myös estää myöhemmän teemanvaihto-"bouncen".
        val widgetSection = resolveWidgetSection(intent)
        externalSection.value =
            widgetSection ?: intent?.getStringExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION)
        if (intent?.getBooleanExtra(Notifications.EXTRA_DISRUPTION_FAV, false) == true) {
            DisruptionNav.requestFocusFavorites()
            intent?.removeExtra(Notifications.EXTRA_DISRUPTION_FAV)
        }
        maybeRecoverWorkout()
        maybeImportWorkoutFile(intent)
        // Palautuksen jälkeinen vahvistus: prosessi käynnistettiin uudelleen → kerro käyttäjälle.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("restore_done_pending", false)) {
            prefs.edit().remove("restore_done_pending").apply()
            android.widget.Toast.makeText(
                this, getString(R.string.main_backup_restored), android.widget.Toast.LENGTH_LONG).show()
        }
        // Kertamigraatio: vanha SAF-pohjainen automaattivarmuuskopio on korvattu Drive-varmuuskopiolla
        // -> perutaan upgrade-kayttajien vanha ajastettu tyo kerran, ettei se jaa roikkumaan taustalle.
        if (!prefs.getBoolean("saf_autobackup_removed", false)) {
            AutoBackup.disable(applicationContext)
            prefs.edit().putBoolean("saf_autobackup_removed", true).apply()
        }
        // Kertamigraatio: reittihaun "Vain suorat" -suodatin nollataan pois paalta. Oletus on aina
        // ollut false, mutta arvo on voinut jaada persistoituneena paalle -> halutaan pois oletuksena
        // ja vain kayttajan itse valittavissa. Tehdaan vain kerran, joten myohempi uudelleenvalinta sailyy.
        if (!prefs.getBoolean("route_direct_only_reset_done", false)) {
            prefs.edit()
                .putBoolean("route_direct_only", false)
                .putBoolean("route_direct_only_reset_done", true)
                .apply()
        }
        setContent {
            ArkikeskusTheme(dynamicColor = appliedDynamicColor) {
                ComposeMainScreen(
                    externalSection = externalSection,
                    // Sektioon navigoinnin jälkeen tyhjennetään launch-intent, jottei relaunch/recreate
                    // toista deep-linkkiä (kylmän käynnistyksen sektio säilyy, mutta teemanvaihto ei bounssaa).
                    onSectionConsumed = { setIntent(android.content.Intent()) },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(Notifications.EXTRA_DISRUPTION_FAV, false)) {
            DisruptionNav.requestFocusFavorites()
        }
        val widgetSection = resolveWidgetSection(intent)
        (widgetSection ?: intent.getStringExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION))?.let {
            externalSection.value = it
        }
        // EXTRA_OPEN_SECTION / data kulutetaan vasta navigoinnin jälkeen (onSectionConsumed). DISRUPTION_FAV
        // on oma kertakäyttöinen ohjeensa -> kulutetaan heti.
        intent.removeExtra(Notifications.EXTRA_DISRUPTION_FAV)
        maybeImportWorkoutFile(intent)
    }

    /** Jaetun lenkkitiedoston (GPX/TCX) avaus toisesta sovelluksesta: ACTION_VIEW (esim.
     *  Tiedostot/Gmail) tai ACTION_SEND (jakovalikko) → tuonti "Jaettu lenkki" -merkinnällä
     *  ja Lenkki-sivun avaus. */
    /** Widget-tapin data-URI ([WidgetDeepLink.SCHEME]://widget/<sektio>) -> HomeSection-enumin nimi,
     *  tai null jos intent ei ole widget-deep-link. Itse HomeSection.valueOf tehdään ComposeMainScreenissä. */
    private fun resolveWidgetSection(intent: android.content.Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != WidgetDeepLink.SCHEME) return null
        return data.lastPathSegment?.takeIf { it.isNotBlank() }
    }

    private fun maybeImportWorkoutFile(intent: android.content.Intent?) {
        val uri: android.net.Uri = when (intent?.action) {
            // Widget-deep-link (arkikeskus://) EI ole tiedostotuonti -> ohitetaan, jotta ACTION_VIEW
            // ei yritä avata sektio-URIa lenkkitiedostona.
            android.content.Intent.ACTION_VIEW ->
                intent.data?.takeIf { it.scheme != WidgetDeepLink.SCHEME }
            android.content.Intent.ACTION_SEND ->
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
                }
            else -> null
        } ?: return
        android.util.Log.i("WorkoutImport",
            "intent action=${intent?.action} type=${intent?.type} uri=$uri")
        // Kuluta intent heti: Activityn recreate (teemanvaihto, prosessikuolema) toimittaa
        // alkuperäisen intentin uudelleen → sama tiedosto käsiteltäisiin ja toast/navigointi
        // toistuisi. URI on jo poimittu talteen.
        setIntent(android.content.Intent())
        Thread {
            var tooLarge: String? = null
            val res = try {
                contentResolver.openInputStream(uri)?.use {
                    WorkoutFileImporter.importStream(this, it)
                }
            } catch (e: FileTooLargeException) {
                tooLarge = e.userMessage(this)
                null
            } catch (e: Exception) {
                android.util.Log.w("WorkoutImport", "Tuonti epäonnistui", e)
                null
            }
            runOnUiThread {
                if (res == null) {
                    android.widget.Toast.makeText(
                        this, tooLarge ?: getString(R.string.main_workout_import_none),
                        android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        if (res.alreadyExists) getString(R.string.main_workout_import_exists, res.name)
                        else getString(R.string.main_workout_import_done, res.name),
                        android.widget.Toast.LENGTH_LONG).show()
                    externalSection.value = "WORKOUT"
                }
            }
        }.start()
    }

    /** Sovelluksen avaus kun kannassa on keskeneräinen lenkki mutta seuranta ei ole muistissa
     *  (prosessi ehti kuolla eikä järjestelmä restartannut palvelua): käynnistetään palvelu
     *  RECOVER-tilassa (sallittua — ollaan etualalla). recover() finalisoi vanhentuneet jäänteet
     *  itse; vain tuore lenkki avaa Lenkki-sivun automaattisesti. */
    private fun maybeRecoverWorkout() {
        if (WorkoutTracker.state.value.phase != WorkoutTracker.Phase.IDLE) return
        Thread {
            try {
                val active = org.jrs82.fsclock.db.FsClockDb.get(applicationContext)
                    .workoutDao().activeWorkout()
                if (active != null) {
                    val fresh = System.currentTimeMillis() - active.updatedAtMs < 10L * 60_000L
                    runOnUiThread {
                        WorkoutTrackingService.command(this, WorkoutTrackingService.ACTION_RECOVER)
                        if (fresh) externalSection.value = "WORKOUT"
                    }
                }
            } catch (e: Exception) { }
        }.start()
    }

    /**
     * Pyytää sijaintiluvan KERRAN per laite heti ensimmäisellä käynnistyksellä, jotta etusivun sää ja
     * Lähilähdöt toimivat suoraan asennuksen jälkeen (automaattinen sijainti on oletuksena päällä).
     * Tieto kysymisestä on laitekohtaisessa [DeviceLocalPrefs]issa: aiemmin se oli oletusasetuksissa ja
     * siirtyi varmuuskopion mukana uudelle puhelimelle, jolloin lupaa ei kysytty siellä koskaan.
     */
    private fun maybeAskInitialLocationPermission() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Vanha, varmuuskopioihin päätynyt avain ei kerro tämän laitteen tilasta → siivotaan pois.
        if (prefs.contains(MobileThemeController.LEGACY_KEY_INITIAL_LOCATION_PERMISSION_ASKED)) {
            prefs.edit().remove(MobileThemeController.LEGACY_KEY_INITIAL_LOCATION_PERMISSION_ASKED).apply()
        }
        val device = DeviceLocalPrefs.get(this)
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val ask = LocationPermissionPolicy.shouldAskInitially(
            askedOnThisDevice = device.getBoolean(DeviceLocalPrefs.KEY_LOCATION_PERMISSION_ASKED, false),
            autoLocationOn = prefs.getBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true),
            granted = granted,
        )
        DeviceLocalPrefs.markLocationPermissionAsked(this)
        if (ask) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (appliedDynamicColor != MobileThemeController.dynamicColor(this)) {
            recreate()
            return
        }
        maybeRunNotificationCheck()
        // Paivita kotinäytön widgetit heti kun sovellus tulee etualalle. Throttle estää relaunchin
        // (night mode/dynamic color) aiheuttaman tupla-onResumen turhan toisen virkistyksen.
        val nowMs = System.currentTimeMillis()
        if (nowMs - sLastWidgetRefreshMs > 10_000L) {
            sLastWidgetRefreshMs = nowMs
            org.jrs82.fsclock.mobile.widget.WidgetUpdateWorker.refreshNow(this)
        }
    }

    /**
     * Aja ilmoitustarkistus heti sovellusta avattaessa (throttlattu 15 min). Periodinen
     * taustatyö ([Notifications.schedule]) ajaa vain ~tunnittain ja on verkko-/akkurajoitettu +
     * Dozen eräajamaa, joten esim. askeltavoitteen puolimatka-ilmoitus tuli ennen vasta seuraavalla
     * kierroksella (tai kun käyttäjä kytki asetuksen pois/päälle → runOnce). Nyt avaus laukaisee
     * rajoitteettoman kertatyön → ilmoitukset ovat ajantasaisia. Moduulit deduplikoivat itse
     * (kerran/pv, "nähdyt"-setit), joten tiheä ajo ei tuota tupla-ilmoituksia.
     */
    private fun maybeRunNotificationCheck() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_FOREGROUND_NOTIF_CHECK_MS, 0L) < FOREGROUND_NOTIF_THROTTLE_MS) return
        prefs.edit().putLong(KEY_FOREGROUND_NOTIF_CHECK_MS, now).apply()
        Notifications.runOnce(applicationContext)
    }
}

private const val KEY_FOREGROUND_NOTIF_CHECK_MS = "notif_foreground_check_ms"
private const val FOREGROUND_NOTIF_THROTTLE_MS = 15L * 60 * 1000
