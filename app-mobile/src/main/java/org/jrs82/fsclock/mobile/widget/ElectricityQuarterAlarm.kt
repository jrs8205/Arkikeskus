package org.jrs82.fsclock.mobile.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Piirtää sähköwidgetin uudelleen jokaisen vartin alussa. WorkManagerin 15 min jaksotyö ajautuu
 * (Doze, flex-ikkuna) eikä osu varttirajoihin, joten widget näytti edellisen vartin hintaa jopa
 * 10 min uuden vartin alettua. Herätys asetetaan vain kun sähköwidgettejä on ruudulla.
 *
 * Tarkoituksella EI allow-while-idle: Android 11:llä idle-poikkeuksen 9 min minimiväli on UID-kohtainen
 * ja veisi lähtömuistutusten ([org.jrs82.fsclock.mobile.DepartureReminder]) kiintiön. Näkymätöntä
 * widgettiä ei tarvitse piirtää Dozessa — RTC-hälytys toimitetaan kun laite herää.
 */
object ElectricityQuarterAlarm {
    private const val REQUEST_CODE = 7150
    private const val GRACE_MS = 5_000L
    private const val FALLBACK_WINDOW_MS = 60_000L

    fun scheduleIfWidgets(context: Context) {
        if (!hasWidgets(context)) { cancel(context); return }
        val trigger = WidgetElectricity.nextQuarterBoundary(System.currentTimeMillis()) + GRACE_MS
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        try {
            am.setExact(AlarmManager.RTC, trigger, pi)
        } catch (e: SecurityException) {
            // Ilman tarkka-ajastuslupaa: minuutin ikkuna riittää varttihinnalle.
            am.setWindow(AlarmManager.RTC, trigger, FALLBACK_WINDOW_MS, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    private fun hasWidgets(context: Context): Boolean = try {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ElectricityWidgetReceiver::class.java))
            .isNotEmpty()
    } catch (e: Exception) {
        false
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, ElectricityQuarterReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class ElectricityQuarterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ElectricityWidgetRedraw.redraw(app)
            } catch (e: Exception) {
            } finally {
                try { ElectricityQuarterAlarm.scheduleIfWidgets(app) } catch (e: Exception) { }
                pending.finish()
            }
        }
    }
}
