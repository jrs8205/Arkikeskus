package org.jrs82.fsclock

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.jrs82.fsclock.compose.HomeDataController
import org.jrs82.fsclock.compose.HomeScreen
import org.jrs82.fsclock.compose.Page

/** Compose-etusivun isäntä. Pakotettu vaakanäyttö + always-on kioskiasetukset.
 *  Data tulee HomeDataControllerista (reusaa olemassa olevat repositoriot). */
class ComposeHomeActivity : ComponentActivity() {

    private lateinit var data: HomeDataController
    private lateinit var pixelShift: PixelShiftController
    private lateinit var brightness: BrightnessController

    private val locationPerm =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Tarkista todellinen lupatila (myös likimääräinen/COARSE kelpaa), ei pelkkää grantedia.
            if (data.hasLocationPermission()) data.fetchLocation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        SettingsManager.get().init(applicationContext)
        brightness = BrightnessController(window, SettingsManager.get())
        data = HomeDataController(this)
        setContent {
            var page by remember { mutableStateOf(Page.HOME) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    ui = data.uiState,
                    page = page,
                    onPage = { page = it },
                    onSettings = { openSettings() },
                    onRename = { slot, name -> data.setSensorName(slot, name) }
                )
            }
        }
        pixelShift = PixelShiftController(findViewById(android.R.id.content))
    }

    override fun onStart() {
        super.onStart()
        data.start()
        // Päivä/yö-kirkkaus + testipäivä/yö (asetuksista). reapply() pakottaa
        // Window-attribuutin uudelleen, koska asetuksia on voitu muuttaa välissä.
        brightness.reapply()
        brightness.start()
        if (!UiMetrics.isCompactHeight(resources)) pixelShift.start()
        if (!data.hasLocationPermission()) {
            locationPerm.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    override fun onStop() {
        pixelShift.stop()
        brightness.stop()
        data.stop()
        super.onStop()
    }

    /** Ratas avaa natiivin asetukset (Ruuvi-konffaus, anturien nimet, kirkkaus jne). */
    private fun openSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java))
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            // Järjestelmä on voinut palauttaa kirkkauden dialogien/asetusten jälkeen.
            if (::brightness.isInitialized) brightness.reapply()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
