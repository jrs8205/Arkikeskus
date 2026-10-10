package org.jrs82.fsclock.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jrs82.fsclock.mobile.widget.DepartureWidget
import org.jrs82.fsclock.mobile.widget.ElectricityWidget
import org.jrs82.fsclock.mobile.widget.StepsWidget
import org.jrs82.fsclock.mobile.widget.WeatherWidget
import org.jrs82.fsclock.mobile.widget.WidgetRedraw

/** Ilmoituskanavien nimet ja widgetien tekstit eivät päivity itsestään, kun laitteen kieli vaihtuu:
 *  kanava luodaan uudelleen samalla id:llä (nimi päivittyy) ja Glance-widgetit piirretään uudelleen
 *  piirtolaskurin kautta, koska pelkkä updateAll() ei recomposaa elävää sessiota (ks. [WidgetRedraw]). */
class LocaleChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // LOCALE_CHANGED = laitteen kieli, APPLICATION_LOCALE_CHANGED = sovelluskohtainen kieli (Android 13+).
        if (intent.action != Intent.ACTION_LOCALE_CHANGED && intent.action != Intent.ACTION_APPLICATION_LOCALE_CHANGED) return
        val app = context.applicationContext
        Notifications.ensureChannels(app)
        WorkoutTrackingService.createChannels(app)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetRedraw.redraw(app, WeatherWidget())
                WidgetRedraw.redraw(app, ElectricityWidget())
                WidgetRedraw.redraw(app, StepsWidget())
                WidgetRedraw.redraw(app, DepartureWidget())
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
