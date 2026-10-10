package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.FmiWarningDetailsClient
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WarningEnricher
import org.jrs82.fsclock.WarningsClient
import org.jrs82.fsclock.WeatherWarning
import org.json.JSONArray

/**
 * 4b-ilmoitus #2: ilmoittaa kun KÄYTTÄJÄN KOTIPAIKKAA koskeva uusi FMI-säävaroitus on voimassa
 * (sade/tuuli/pakkanen ym.). Käyttää valmista [WarningsClient]-infraa (MeteoAlarm-feed, jo suodattaa
 * Actual + fi-FI + voimassa). Tila default-prefsissä → mukana varmuuskopiossa. Opt-in (oletus pois).
 *
 * "Oma paikka": MeteoAlarmin `areaDesc` listaa kunnat/maakunnat pilkulla → osuma kun kotipaikan nimi
 * ([SettingsManager.getHomePlace]) sisältyy johonkin alueosaan. Merivaroitukset ([WeatherWarning.marine])
 * jätetään pois. Identiteetti = varoituksen vakaa `identifier` (fallback event|area|onset).
 * Tekstit haetaan sovelluksen kielellä; `areaDesc` on kaikilla kielillä suomeksi, joten osuma ei muutu.
 */
object WeatherWarningNotifier {

    const val KEY_ENABLED = "notify_weather_warnings"
    private const val KEY_SEEN = "notify_weather_seen"
    private const val SEEN_CAP = 100

    /** Ilmoituksen sisältö: FMI:n pidempi teksti jos rikkaampi, muuten MeteoAlarm-kuvaus/alue. */
    internal fun bestBody(w: WeatherWarning): String {
        val fmi = w.details.detailText
        val base = if (w.description.isNotEmpty()) w.description else w.areaDesc
        return if (fmi.length > base.length) fmi else base
    }

    /** Ilmoituksen sisältö rakenteena (resurssi-id:t + argumentit) → valinta testattavissa ilman Contextia. */
    internal sealed class Notice {
        data class Single(
            val event: String,
            @StringRes val levelRes: Int,
            val body: String,
            val probabilityPct: Int,
        ) : Notice()

        data class Many(val homePlace: String, val count: Int) : Notice()
    }

    internal fun noticeFor(toNotify: List<WeatherWarning>, homePlace: String): Notice =
        if (toNotify.size == 1) {
            val w = toNotify[0]
            Notice.Single(w.event, w.level.nameRes, bestBody(w), w.details.probabilityPct)
        } else {
            Notice.Many(homePlace, toNotify.size)
        }

    /** Varoituksen identiteettiavain (MeteoAlarm tarjoaa vakaan identifierin). */
    fun warningKey(w: WeatherWarning): String =
        if (w.identifier.isNotEmpty()) w.identifier
        else w.event + "|" + w.areaDesc + "|" + w.onsetMs

    /**
     * Koskeeko varoitus kotipaikkaa. FMI antaa varoitukset MAAKUNNITTAIN (esim. "Uusimaa") → ensisijainen
     * osuma on kotipaikan maakunta ([homeRegion]); kuntanimi-match ([homePlace]) kattaa pohjoisen
     * kuntalistaukset ("Kemi, Tornio, …").
     */
    fun areaMatchesHome(areaDesc: String, homePlace: String, homeRegion: String?): Boolean {
        if (areaDesc.isEmpty()) return false
        val home = homePlace.trim().lowercase()
        val region = homeRegion?.trim()?.lowercase()
        for (raw in areaDesc.split(",")) {
            val p = raw.trim().lowercase()
            if (p.isEmpty()) continue
            if (home.isNotEmpty() && (p.contains(home) || home.contains(p))) return true
            if (!region.isNullOrEmpty() && regionMatchesPart(p, region)) return true
        }
        return false
    }

    /** Maakuntaosuma sanarajalla, ettei "Pohjanmaa" osu virheellisesti "Etelä-Pohjanmaahan". */
    private fun regionMatchesPart(part: String, region: String): Boolean {
        if (part == region) return true
        if (part.startsWith("$region ")) return true                                  // "Uusimaa itäosa"
        if (region.endsWith("maa") && part.startsWith("${region}n ")) return true      // "Pirkanmaan eteläosa"
        return false
    }

    data class Selection(val toNotify: List<WeatherWarning>, val newSeen: Set<String>)

    /**
     * Puhdas valinta (yksikkötestattava): kotipaikkaa koskevat ei-meri-varoitukset, joita ei ole nähty.
     * newSeen = kaikki nyt osuvat avaimet (kattorajattu) → mennyt varoitus tippuu ja toistuessaan ilmoittaa.
     */
    fun selectNewWarnings(
        warnings: List<WeatherWarning>,
        homePlace: String,
        homeRegion: String?,
        seen: Set<String>,
    ): Selection {
        val matchedKeys = LinkedHashSet<String>()
        val toNotify = ArrayList<WeatherWarning>()
        for (w in warnings) {
            if (w.marine) continue
            if (!areaMatchesHome(w.areaDesc, homePlace, homeRegion)) continue
            val key = warningKey(w)
            if (!matchedKeys.add(key)) continue
            if (!seen.contains(key)) toNotify.add(w)
        }
        val newSeen = if (matchedKeys.size <= SEEN_CAP) matchedKeys.toSet()
        else matchedKeys.toList().takeLast(SEEN_CAP).toSet()
        return Selection(toNotify, newSeen)
    }

    fun check(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        SettingsManager.get().init(context.applicationContext)
        val homePlace = SettingsManager.get().homePlace ?: ""
        if (homePlace.isBlank()) return
        val homeRegion = FinnishRegions.regionForPlace(homePlace)
        val locale = AppLocale.current(context)
        val raw = try { WarningsClient().fetch(locale.language) } catch (e: Exception) { return }
        val warnings = try {
            WarningEnricher.enrich(raw, FmiWarningDetailsClient().fetch(AppLocale.fmiInfoKey(locale)))
        } catch (e: Exception) {
            raw
        }
        val result = selectNewWarnings(warnings, homePlace, homeRegion, loadSeen(prefs))
        android.util.Log.i(
            "WeatherWarningNotifier",
            "home=$homePlace region=$homeRegion warnings=${warnings.size} uusia=${result.toNotify.size}",
        )
        if (result.toNotify.isNotEmpty()) postFor(context, homePlace, result.toNotify)
        saveSeen(prefs, result.newSeen)
    }

    private fun postFor(context: Context, homePlace: String, toNotify: List<WeatherWarning>) {
        val title: String
        val body: String
        when (val n = noticeFor(toNotify, homePlace)) {
            is Notice.Single -> {
                title = if (n.levelRes != 0) {
                    context.getString(R.string.warning_notif_title_level, n.event, context.getString(n.levelRes))
                } else {
                    context.getString(R.string.warning_notif_title, n.event)
                }
                body = if (n.probabilityPct >= 0) {
                    context.getString(R.string.warning_notif_body_probability, n.body, n.probabilityPct)
                } else {
                    n.body
                }
            }
            is Notice.Many -> {
                title = context.getString(R.string.warning_notif_title_many, n.homePlace)
                body = context.resources.getQuantityString(R.plurals.warning_notif_body_many, n.count, n.count)
            }
        }
        Notifications.post(
            context, Notifications.CHANNEL_WEATHER, Notifications.NOTIF_ID_WEATHER,
            title, body, "WEATHER_WARNINGS",
        )
    }

    private fun loadSeen(prefs: SharedPreferences): Set<String> = try {
        val arr = JSONArray(prefs.getString(KEY_SEEN, "[]"))
        val set = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotEmpty()) set.add(s)
        }
        set
    } catch (e: Exception) {
        emptySet()
    }

    private fun saveSeen(prefs: SharedPreferences, seen: Set<String>) {
        val arr = JSONArray()
        for (s in seen) arr.put(s)
        prefs.edit().putString(KEY_SEEN, arr.toString()).apply()
    }
}
