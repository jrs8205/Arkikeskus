package org.jrs82.fsclock.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import org.jrs82.fsclock.SettingsManager

/**
 * Asetus-Activity. UI on Compose ([SettingsScreen]); pysyy AppCompatActivityna jotta
 * [MobileThemeController]in night mode -pakotus (light/dark/system) toimii ja heijastuu
 * Composen isSystemInDarkTheme():iin. Teema manifestissa = MobileComposeTheme (NoActionBar).
 */
class MobileSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MobileThemeController.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SettingsManager.get().init(applicationContext)
        setContent {
            ArkikeskusTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}
