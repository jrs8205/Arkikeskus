package org.jrs82.fsclock.mobile.widget

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Piirtää sähköwidgetit uudelleen pelkästä paikallisesta cachesta (ei verkkoa). Glance 1.1.1 lähettää
 * elävälle (~45 s) sessiolle vain UpdateGlanceState-tapahtuman, ja muuttumaton Preferences-tila ei
 * recomposaa sisältöä → piirtolaskuria kasvatetaan ennen jokaista update-kutsua, ja widget lukee sen
 * kompositiossa. Käytetään varttiherätyksessä, workerissa ja ALV-kytkimessä.
 */
object ElectricityWidgetRedraw {
    internal val RENDER_REVISION = longPreferencesKey("electricity_render_revision")

    suspend fun redraw(context: Context) {
        val app = context.applicationContext
        val widget = ElectricityWidget()
        val ids = try { GlanceAppWidgetManager(app).getGlanceIds(ElectricityWidget::class.java) } catch (e: Exception) { emptyList() }
        for (id in ids) {
            try {
                updateAppWidgetState(app, id) { it[RENDER_REVISION] = (it[RENDER_REVISION] ?: 0L) + 1L }
                widget.update(app, id)
            } catch (e: Exception) {
            }
        }
    }

    /** Asetusnäkymästä: piirto ei saa riippua asetussivun elinkaaresta eikä workerin verkkohauista. */
    fun redrawAsync(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try { redraw(app) } catch (e: Exception) { }
        }
    }
}
