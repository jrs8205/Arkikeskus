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
        setContent {
            ArkikeskusTheme(dynamicColor = appliedDynamicColor) {
                ComposeMainScreen()
            }
        }
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
