package org.jrs82.fsclock.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import org.jrs82.fsclock.SettingsManager

/**
 * Compose-pääruudun ESIKATSELU-Activity (Vaihe 2/3 migraatiossa). Erillinen nykyisestä
 * [MobileMainActivity]sta, avataan valikon "Uusi ulkoasu (esikatselu)" -kohdasta, jotta
 * navigaatiorunkoa + uutta ulkoasua voi testata rikkomatta toimivaa sovellusta.
 *
 * AppCompatActivity + sama night mode -pakotus kuin [MobileMainActivity]ssa, jotta sovelluksen
 * teema-asetus (light/dark/system) toimii ja Composen isSystemInDarkTheme() lukee sen oikein.
 * Teema manifestissa = MobileComposeTheme (NoActionBar, edge-to-edge).
 */
class MobileComposeMainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MobileThemeController.apply(this)
        delegate.setLocalNightMode(MobileThemeController.nightMode(this))
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SettingsManager.get().init(applicationContext)
        setContent {
            ArkikeskusTheme {
                ComposeMainScreen()
            }
        }
    }
}
