package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.ElectricityFetcher
import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.R
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 4b-ilmoitus #3: ilmoittaa kun HUOMISEN pörssisähköhinnat ovat saapuneet (NordPool day-ahead ~klo 14)
 * — halvin/kallein vartti + keskihinta. Käyttää samaa [ElectricityFetcher]-hakua kuin sähkösivu
 * (Elering, varalähteenä spot-hinta.fi). Ilmoitetaan kerran per päivä ([KEY_LAST_DAY]). Opt-in
 * (oletus pois). Hinnat snt/kWh, sama yksikkö/ALV kuin sovelluksessa muutenkin.
 */
object ElectricityNotifier {

    const val KEY_ENABLED = "notify_electricity"
    private const val KEY_LAST_DAY = "notify_electricity_last_day"
    private const val PUBLISH_HOUR = 14 // huomisen hinnat NordPoolista n. klo 14
    private val HEL: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

    /**
     * [minRange]/[maxRange] = halvimman ja kalleimman jakson kellonajat muodossa "03.15–03.30" (vartti)
     * tai "04.00–05.00" (tunti); [hourly] kertoo, kumpaa jaksot ovat.
     * [minDstRes]/[maxDstRes] = kesä-/talviaikatarkenne toistuvalla tunnilla (ks. [ElectricityTime.dstSuffixRes]).
     */
    data class DayStats(
        val minSnt: Double, val minRange: String,
        val maxSnt: Double, val maxRange: String,
        val avgSnt: Double,
        @StringRes val minDstRes: Int? = null,
        @StringRes val maxDstRes: Int? = null,
        val hourly: Boolean = false,
    )

    /** Halvin ja kallein jakso sekä päivän keskihinta näytettävinä hintoina (puhdas, yksikkötestattava).
     *
     *  [quarters] on yhden päivän vartit. Halvin ja kallein valitaan näytettävän tarkkuuden
     *  jaksoista: varteista, tai [hourly]-asetuksella tunneista, jos päivän jokainen tunti on täysi
     *  ([ElectricityHourly.daySlots]); vajaa päivä käsitellään vartteina, ja [DayStats.hourly] kertoo
     *  toteutuneen jakson, jotta ilmoitusteksti nimeää sen oikein. Keskihinta lasketaan AINA
     *  kaikista annetuista varteista, jotta se on sama luku kuin sähkösivulla. ALV lisätään
     *  varttikohtaisesti ennen keskiarvoa, koska se ei koske negatiivisia hintoja.
     *
     *  Hinnat ovat [pricing]in näytettyjä hintoja. Omilla hinnoilla halvin ja kallein valitaan
     *  kokonaishinnasta ([electricitySlotRange]), koska siirtohinta voi riippua kellonajasta. */
    fun summarize(
        quarters: List<ElectricityData.Quarter>,
        pricing: ElectricityPricing = ElectricityPricing(false),
        hourly: Boolean = false,
    ): DayStats? {
        if (quarters.isEmpty()) return null
        val day = ElectricityHourly.daySlots(quarters, hourly, pricing.vat)
        val (min, max) = electricitySlotRange(day.slots, pricing) ?: return null
        return DayStats(
            pricing.shown(min), range(min, day.slotMs),
            pricing.shown(max), range(max, day.slotMs),
            electricityDayAverage(quarters, pricing),
            ElectricityTime.dstSuffixRes(min.timestamp),
            ElectricityTime.dstSuffixRes(max.timestamp),
            day.hourly,
        )
    }

    @StringRes val TITLE_RES: Int = R.string.electricity_notify_title

    /** Ilmoitusteksti nimeää jakson: halvin ja kallein vartti tai tunti. */
    @StringRes
    fun messageRes(hourly: Boolean): Int =
        if (hourly) R.string.electricity_notify_text_hour else R.string.electricity_notify_text_quarter

    /** Ilmoitustekstin hinnat (halvin, kallein, keskihinta) aina kolmella desimaalilla kielen
     *  desimaalierottimella (puhdas, yksikkötestattava). */
    fun messagePrices(stats: DayStats, locale: Locale): List<String> = listOf(
        ElectricityVat.format(stats.minSnt, locale),
        ElectricityVat.format(stats.maxSnt, locale),
        ElectricityVat.format(stats.avgSnt, locale),
    )

    /** Ilmoitusteksti [summarize]n näytettävistä hinnoista; [pricing] valitsee hinnan selitteen (ALV, oma kokonaishinta). */
    fun message(context: Context, stats: DayStats, pricing: ElectricityPricing): String {
        val prices = messagePrices(stats, AppLocale.current(context))
        return context.getString(
            messageRes(stats.hourly),
            stats.minRange + ElectricityTime.dstSuffixText(context, stats.minDstRes), prices[0],
            stats.maxRange + ElectricityTime.dstSuffixText(context, stats.maxDstRes), prices[1],
            prices[2], context.getString(pricing.labelRes()),
        )
    }

    private fun range(slot: ElectricityData.Quarter, slotMs: Long): String {
        val (start, end) = ElectricityTime.slotClockTexts(slot.timestamp, slotMs)
        return "$start–$end"
    }

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
        val data = try { ElectricityFetcher.shared().fetch(now.timeInMillis, end.timeInMillis) } catch (e: Exception) { return }
        val tY = tomorrow.get(Calendar.YEAR)
        val tM = tomorrow.get(Calendar.MONTH) + 1
        val tD = tomorrow.get(Calendar.DAY_OF_MONTH)
        val tomorrowQs = data.quarters.filter { it.year == tY && it.month == tM && it.dayOfMonth == tD }
        // Vaadi koko päivä ILTAAN ASTI (tunti >= 20 Suomen aikaa). Pelkkä CET-aikavyöhykkeen ~1 h
        // aamuyön ylivuoto (huomisen 00:00–00:45 = TÄMÄN päivän CET-hintojen häntä, julkaistu eilen)
        // EI ole vielä huomisen julkaisu → muuten ilmoitus laukeaisi joka päivä jo n. klo 14, ennen
        // oikeiden hintojen julkaisua. Kesä/talvi ei vaikuta: ylivuoto on aina vain aamuyötä.
        if (tomorrowQs.none { it.hour >= 20 }) return // täysi huomispäivä ei vielä julki
        // Sama hintamalli kuin sovelluksen näkymissä: ALV-asetus ja käyttäjän omat hinnat.
        val pricing = ElectricityOwnSettings.pricing(prefs)
        // Asetuksella "Näytä tunnin keskihinta" halvin ja kallein ovat tunteja; keskihinta lasketaan
        // aina kaikista varteista.
        val stats = summarize(tomorrowQs, pricing, ElectricityHourly.enabled(prefs))
            ?: return // ei vielä julkaistu → yritä seuraavalla tunnilla
        android.util.Log.i(
            "ElectricityNotifier",
            "huomisen hinnat ilmoitettu $tomorrowKey: min=${stats.minSnt} max=${stats.maxSnt} ka=${stats.avgSnt}",
        )
        Notifications.post(
            context, Notifications.CHANNEL_ELECTRICITY, Notifications.NOTIF_ID_ELECTRICITY,
            context.getString(TITLE_RES),
            message(context, stats, pricing),
            "ELECTRICITY",
        )
        prefs.edit().putString(KEY_LAST_DAY, tomorrowKey).apply()
    }
}
