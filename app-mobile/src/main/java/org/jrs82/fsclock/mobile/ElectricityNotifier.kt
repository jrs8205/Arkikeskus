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
 * Ilmoitetaan kerran per päivä ([KEY_LAST_DAY]). Opt-in (oletus pois). Hinnat snt/kWh samasta
 * [ElectricityClient]-lähteestä kuin sähkösivulla (sama yksikkö/ALV kuin sovelluksessa muutenkin).
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

    /** Halvin/kallein vartti + keskihinta annetuista varteista näytettävinä hintoina (puhdas,
     *  yksikkötestattava). ALV lisätään varttikohtaisesti ennen keskiarvoa, koska se ei koske
     *  negatiivisia hintoja. */
    fun summarize(quarters: List<ElectricityData.Quarter>, vat: Boolean = false): DayStats? {
        if (quarters.isEmpty()) return null
        var min = quarters[0]
        var max = quarters[0]
        var sum = 0.0
        for (q in quarters) {
            if (q.sntPerKwh < min.sntPerKwh) min = q
            if (q.sntPerKwh > max.sntPerKwh) max = q
            sum += ElectricityVat.apply(q.sntPerKwh, vat)
        }
        return DayStats(
            ElectricityVat.apply(min.sntPerKwh, vat), hm(min),
            ElectricityVat.apply(max.sntPerKwh, vat), hm(max),
            sum / quarters.size,
        )
    }

    /** Ilmoitusteksti [summarize]n näytettävistä hinnoista, aina kolmella desimaalilla; [vat] valitsee
     *  vain ALV-merkinnän (puhdas, yksikkötestattava). */
    fun message(stats: DayStats, vat: Boolean): String = String.format(
        FI,
        "Halvin klo %s (%s snt), kallein klo %s (%s snt). Keskihinta %s snt/kWh, %s.",
        stats.minHm, ElectricityVat.format(stats.minSnt),
        stats.maxHm, ElectricityVat.format(stats.maxSnt),
        ElectricityVat.format(stats.avgSnt), ElectricityVat.label(vat),
    )

    private fun hm(q: ElectricityData.Quarter) =
        String.format(Locale.US, "%02d:%02d", q.hour, q.minute) + ElectricityTime.dstSuffix(q.timestamp)

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
        // Vaadi koko päivä ILTAAN ASTI (tunti >= 20 Suomen aikaa). Pelkkä CET-aikavyöhykkeen ~1 h
        // aamuyön ylivuoto (huomisen 00:00–00:45 = TÄMÄN päivän CET-hintojen häntä, julkaistu eilen)
        // EI ole vielä huomisen julkaisu → muuten ilmoitus laukeaisi joka päivä jo n. klo 14, ennen
        // oikeiden hintojen julkaisua. Kesä/talvi ei vaikuta: ylivuoto on aina vain aamuyötä.
        if (tomorrowQs.none { it.hour >= 20 }) return // täysi huomispäivä ei vielä julki
        val vat = ElectricityVat.enabled(prefs)
        val stats = summarize(tomorrowQs, vat) ?: return // ei vielä julkaistu → yritä seuraavalla tunnilla
        android.util.Log.i(
            "ElectricityNotifier",
            "huomisen hinnat ilmoitettu $tomorrowKey: min=${stats.minSnt} max=${stats.maxSnt} ka=${stats.avgSnt}",
        )
        Notifications.post(
            context, Notifications.CHANNEL_ELECTRICITY, Notifications.NOTIF_ID_ELECTRICITY,
            "Huomisen sähköhinnat saapuivat",
            message(stats, vat),
            "ELECTRICITY",
        )
        prefs.edit().putString(KEY_LAST_DAY, tomorrowKey).apply()
    }
}
