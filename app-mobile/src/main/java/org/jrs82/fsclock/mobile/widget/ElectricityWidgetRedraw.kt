package org.jrs82.fsclock.mobile.widget

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Piirtää sähköwidgetit uudelleen pelkästä paikallisesta cachesta (ei verkkoa), piirtolaskurin kautta
 * ([WidgetRedraw]). Käytetään varttiherätyksessä, workerissa ja ALV-kytkimessä.
 */
object ElectricityWidgetRedraw {
    suspend fun redraw(context: Context) = WidgetRedraw.redraw(context, ElectricityWidget())

    /** Asetusnäkymästä: piirto ei saa riippua asetussivun elinkaaresta eikä workerin verkkohauista. */
    fun redrawAsync(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try { redraw(app) } catch (e: Exception) { }
        }
    }
}
