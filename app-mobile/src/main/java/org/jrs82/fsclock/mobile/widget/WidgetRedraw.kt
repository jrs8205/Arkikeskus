package org.jrs82.fsclock.mobile.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState

/**
 * Pakotettu uudelleenpiirto. Glance 1.1.1 lähettää elävälle (~45 s) sessiolle vain UpdateGlanceState-
 * tapahtuman, ja muuttumaton Preferences-tila ei recomposaa sisältöä → pelkkä update()/updateAll() ei
 * päivitä kompositiossa luettuja kelloa, prefsejä eikä resurssitekstejä (kielenvaihto). Piirtolaskuria
 * kasvatetaan ennen update-kutsua, ja widget lukee sen kompositiossa ([RenderRevision]).
 */
object WidgetRedraw {
    internal val RENDER_REVISION = longPreferencesKey("render_revision")

    suspend fun redraw(context: Context, widget: GlanceAppWidget) {
        val app = context.applicationContext
        val ids = try { GlanceAppWidgetManager(app).getGlanceIds(widget.javaClass) } catch (e: Exception) { emptyList() }
        for (id in ids) {
            try {
                updateAppWidgetState(app, id) { it[RENDER_REVISION] = (it[RENDER_REVISION] ?: 0L) + 1L }
                widget.update(app, id)
            } catch (e: Exception) {
            }
        }
    }
}

/** Sisältö luetaan piirtolaskurin avaimella → elävä Glance-sessio recomposaa, kun laskuri kasvaa. */
@Composable
internal fun RenderRevision(content: @Composable () -> Unit) {
    key(currentState<Preferences>()[WidgetRedraw.RENDER_REVISION]) { content() }
}
