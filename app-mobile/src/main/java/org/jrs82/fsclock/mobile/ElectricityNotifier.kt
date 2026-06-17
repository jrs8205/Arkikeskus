package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.ElectricityClient
import org.jrs82.fsclock.ElectricityData
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 4b-ilmoitus #3: ilmoittaa kun HUOMISEN pörssisähköhinnat ovat saapuneet (NordPool day-ahead ~klo 14)
 * — halvin/kallein vartti + keskihinta. Käyttää valmista [ElectricityClient]-hakua (Elering spot).
 * Ilmoitetaan kerran per päivä ([KEY_LAST_DAY]). Opt-in (oletus pois). Hinnat snt/kWh ilman ALV:tä.
 */
object ElectricityNotifier {

    const val KEY_ENABLED = "notify_electricity"
    private const val KEY_LAST_DAY = "notify_electricity_last_day"
    private const val PUBLISH_HOUR = 14 // huomisen hinnat NordPoolista n. klo 14
    private val FI = Locale("fi", "FI")
    private val HEL: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

    data class DayStats(
        val minSnt: Double, val minHm: String,
        val maxSnt: Double, val maxHm: String,
        val avgSnt: Double,
    )

    /** Halvin/kallein vartti + keskihinta annetuista varteista (puhdas, yksikkötestattava). */
    fun summarize(quarters: List<ElectricityData.Quarter>): DayStats? {
        if (quarters.isEmpty()) return null
        var min = quarters[0]
        var max = quarters[0]
        var sum = 0.0
        for (q in quarters) {
            if (q.sntPerKwh < min.sntPerKwh) min = q
            if (q.sntPerKwh > max.sntPerKwh) max = q
            sum += q.sntPerKwh
        }
        return DayStats(min.sntPerKwh, hm(min), max.sntPerKwh, hm(max), sum / quarters.size)
    }

    private fun hm(q: ElectricityData.Quarter) = String.format(Locale.US, "%02d:%02d", q.hour, q.minute)

    private fun dateKey(c: Calendar) = String.format(
        Locale.US, "%04d-%02d-%02d",
        c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
    )

    fun check(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val now = Calendar.getInstance(HEL)
        if (now.get(Calendar.HOUR_OF_DAY) < PUBLISH_HOUR) return // huomisen hinnat eivät vielä julki
        val tomorrow = Calendar.getInstance(HEL).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowKey = dateKey(tomorrow)
        if (prefs.getString(KEY_LAST_DAY, "") == tomorrowKey) return // jo ilmoitettu tälle päivälle
        // Hae nykyhetkestä huomisen loppuun ja suodata huomisen vartit.
        val end = Calendar.getInstance(HEL).apply {
            add(Calendar.DAY_OF_YEAR, 2)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val data = try { ElectricityClient().fetchRange(now.timeInMillis, end.timeInMillis) } catch (e: Exception) { return }
        val tY = tomorrow.get(Calendar.YEAR)
        val tM = tomorrow.get(Calendar.MONTH) + 1
        val tD = tomorrow.get(Calendar.DAY_OF_MONTH)
        val tomorrowQs = data.quarters.filter { it.year == tY && it.month == tM && it.dayOfMonth == tD }
        val stats = summarize(tomorrowQs) ?: return // ei vielä julkaistu → yritä seuraavalla tunnilla
        android.util.Log.i(
            "ElectricityNotifier",
            "huomisen hinnat ilmoitettu $tomorrowKey: min=${stats.minSnt} max=${stats.maxSnt} ka=${stats.avgSnt}",
        )
        Notifications.post(
            context, Notifications.CHANNEL_ELECTRICITY, Notifications.NOTIF_ID_ELECTRICITY,
            "Huomisen sähköhinnat saapuivat",
            String.format(
                FI,
                "Halvin klo %s (%.1f snt), kallein klo %s (%.1f snt). Keskihinta %.1f snt/kWh.",
                stats.minHm, stats.minSnt, stats.maxHm, stats.maxSnt, stats.avgSnt,
            ),
            "ELECTRICITY",
        )
        prefs.edit().putString(KEY_LAST_DAY, tomorrowKey).apply()
    }
}
