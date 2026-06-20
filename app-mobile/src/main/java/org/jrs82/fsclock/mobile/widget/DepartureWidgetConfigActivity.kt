package org.jrs82.fsclock.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.mobile.ArkikeskusTheme
import org.jrs82.fsclock.mobile.TransitFavorites

/**
 * Konfiguraationakyma lahto-widgetille: kayttaja valitsee suosikkipysakin tai "lahimmaan".
 * Jarjestelma avaa taman Activityn automaattisesti kun widget lisataan kotinaytolle
 * (AppWidgetProviderInfo.configure -viittaus manifestissa).
 */
class DepartureWidgetConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sovella teema-asetus (light/dark/system) samoin kuin MobileThemeController.apply,
        // mutta luetaan prefs suoraan (MobileThemeController on package-private).
        applyThemeMode()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Oletus: peruttu, kunnes kayttaja valitsee.
        setResult(RESULT_CANCELED)

        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            val dynamicColor = remember { readDynamicColor() }
            ArkikeskusTheme(dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val stops = remember { TransitFavorites.getStops(this@DepartureWidgetConfigActivity) }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Valitse pysäkki",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

                        // "Lähin pysäkki" -vaihtoehto
                        Text(
                            text = "Lähin pysäkki (GPS)",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    confirm(widgetId, "NEAREST", "", "Lähin pysäkki")
                                }
                                .padding(vertical = 14.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        HorizontalDivider()

                        // Suosikkipysäkit
                        stops.forEach { s ->
                            Text(
                                text = s.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        confirm(widgetId, "FAVORITE", s.gtfsId, s.name)
                                    }
                                    .padding(vertical = 14.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            HorizontalDivider()
                        }

                        if (stops.isEmpty()) {
                            Text(
                                text = "Ei suosikkipysäkkejä — lisää suosikki Lähilähdöt-näkymässä, tai valitse Lähin.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun confirm(widgetId: Int, mode: String, stopId: String, stopName: String) {
        WidgetCache.setDepartureConfig(this, widgetId, mode, stopId, stopName)
        WidgetUpdateWorker.refreshNowForce(this)
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
        finish()
    }

    /** Soveltaa kayttajan teema-asetuksen (light/dark/system) AppCompat-yotilatekniikalla.
     *  Vastaa MobileThemeController.apply(ctx) — kirjoitettu uudelleen (luokka package-private). */
    private fun applyThemeMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val mode = when (prefs.getString("mobile_theme_mode", "system")) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
            else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Palauttaa dynamic color -kytkimen tilan (SharedPreferences).
     *  Vastaa MobileThemeController.dynamicColor(ctx) — luokka package-private. */
    private fun readDynamicColor(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean("mobile_dynamic_color", false)
}
