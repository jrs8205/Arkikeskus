package org.jrs82.fsclock.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import org.jrs82.fsclock.SettingsManager

/**
 * Etusivun widgettien muokkausnäkymä (näkyvyys + järjestys). Erillinen Activity, jonka voi avata
 * etusivun "Muokkaa etusivua" -painikkeesta tai asetuksista. Kirjoittaa samoihin
 * SharedPreferences-avaimiin, joita [HomeDashboard] lukee → muutokset näkyvät heti kun palaa
 * etusivulle. Sama teema- ja yötilakäsittely kuin [MobileComposeMainActivity]ssa.
 */
class HomeCustomizeActivity : AppCompatActivity() {

    private var appliedDynamicColor = false

    override fun onCreate(savedInstanceState: Bundle?) {
        MobileThemeController.apply(this)
        delegate.setLocalNightMode(MobileThemeController.nightMode(this))
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SettingsManager.get().init(applicationContext)
        appliedDynamicColor = MobileThemeController.dynamicColor(this)
        setContent {
            ArkikeskusTheme(dynamicColor = appliedDynamicColor) {
                HomeCustomizeScreen(onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (appliedDynamicColor != MobileThemeController.dynamicColor(this)) {
            recreate()
        }
    }
}
