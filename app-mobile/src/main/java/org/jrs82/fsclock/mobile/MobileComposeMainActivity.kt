package org.jrs82.fsclock.mobile

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
import org.jrs82.fsclock.SettingsManager

/** Käynnistyskuvan minimikesto millisekunteina, jotta sen ehtii nähdä (muuten vilahtaa ohi). */
private const val SPLASH_MIN_MS = 900L

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
        externalSection.value = intent?.getStringExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION)
        maybeRecoverWorkout()
        maybeImportWorkoutFile(intent)
        setContent {
            ArkikeskusTheme(dynamicColor = appliedDynamicColor) {
                ComposeMainScreen(externalSection = externalSection)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION)?.let {
            externalSection.value = it
        }
        maybeImportWorkoutFile(intent)
    }

    /** Jaetun lenkkitiedoston (GPX/TCX) avaus toisesta sovelluksesta: ACTION_VIEW (esim.
     *  Tiedostot/Gmail) tai ACTION_SEND (jakovalikko) → tuonti "Jaettu lenkki" -merkinnällä
     *  ja Lenkki-sivun avaus. */
    private fun maybeImportWorkoutFile(intent: android.content.Intent?) {
        val uri: android.net.Uri = when (intent?.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
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
        Thread {
            val res = try {
                contentResolver.openInputStream(uri)?.use {
                    WorkoutFileImporter.importStream(this, it)
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (res == null) {
                    android.widget.Toast.makeText(
                        this, "Tiedostosta ei löytynyt lenkkiä (GPX/TCX).",
                        android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        if (res.alreadyExists) "Lenkki on jo laitteella: ${res.name}"
                        else "Jaettu lenkki tuotu: ${res.name}",
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
     * Pyytää sijaintiluvan KERRAN heti ensimmäisellä käynnistyksellä, jotta etusivun sää ja
     * Lähilähdöt toimivat suoraan asennuksen jälkeen (automaattinen sijainti on oletuksena päällä).
     * [MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED] estää uusintakyselyn — jos
     * käyttäjä kieltää, ei jankuteta joka avauksella.
     */
    private fun maybeAskInitialLocationPermission() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, false)) return
        prefs.edit().putBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, true).apply()
        val alreadyGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (appliedDynamicColor != MobileThemeController.dynamicColor(this)) {
            recreate()
        }
    }
}
