package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.SettingsManager
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
 */
object WeatherWarningNotifier {

    const val KEY_ENABLED = "notify_weather_warnings"
    private const val KEY_SEEN = "notify_weather_seen"
    private const val SEEN_CAP = 100

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
        val warnings = try { WarningsClient().fetch() } catch (e: Exception) { return }
        val result = selectNewWarnings(warnings, homePlace, homeRegion, loadSeen(prefs))
        android.util.Log.i(
            "WeatherWarningNotifier",
            "home=$homePlace region=$homeRegion warnings=${warnings.size} uusia=${result.toNotify.size}",
        )
        if (result.toNotify.isNotEmpty()) postFor(context, homePlace, result.toNotify)
        saveSeen(prefs, result.newSeen)
    }

    private fun postFor(context: Context, homePlace: String, toNotify: List<WeatherWarning>) {
        if (toNotify.size == 1) {
            val w = toNotify[0]
            val lvl = if (w.level.fiName.isNotEmpty()) " (${w.level.fiName})" else ""
            Notifications.post(
                context, Notifications.CHANNEL_WEATHER, Notifications.NOTIF_ID_WEATHER,
                "Säävaroitus: ${w.event}$lvl",
                if (w.description.isNotEmpty()) w.description else w.areaDesc,
                "WEATHER_WARNINGS",
            )
        } else {
            Notifications.post(
                context, Notifications.CHANNEL_WEATHER, Notifications.NOTIF_ID_WEATHER,
                "Säävaroituksia paikkakunnalla $homePlace",
                "${toNotify.size} voimassa olevaa säävaroitusta. Avaa nähdäksesi.",
                "WEATHER_WARNINGS",
            )
        }
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
