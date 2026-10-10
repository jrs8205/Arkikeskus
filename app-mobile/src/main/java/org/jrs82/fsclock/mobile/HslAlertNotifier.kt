package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.R
import org.json.JSONArray

/**
 * 4b-ilmoitus #1: ilmoittaa kun KÄYTTÄJÄN SUOSIKKILINJALLE tai -PYSÄKILLE tulee uusi HSL-häiriö.
 * Käyttää valmista 2.9.0-dataa: [DigitransitApi.serviceAlerts] (kaikki HSL-häiriöt) + [TransitFavorites].
 * Tila (nähdyt häiriöt) on default-prefsissä → mukana varmuuskopiossa. Opt-in (oletus pois).
 *
 * Häiriöllä ei ole stabiilia ID:tä Digitransitissa → identiteetti on header|description|linja|pysäkki
 * (sama kuin serviceAlertsin oma dedup). "Nähdyt" = nyt aktiiviset osumat → kun häiriö ratkeaa, se
 * tippuu setistä, ja jos se toistuu myöhemmin, siitä ilmoitetaan uudelleen.
 */
object HslAlertNotifier {

    const val KEY_ENABLED = "notify_hsl_alerts"
    private const val KEY_SEEN = "notify_hsl_seen"
    private const val SEEN_CAP = 200

    /** Häiriön identiteettiavain (ei stabiilia ID:tä API:ssa). */
    fun alertKey(a: TransitAlert): String =
        a.header + "|" + a.description + "|" + a.routeShortName + "|" + a.stopName

    data class Selection(val toNotify: List<TransitAlert>, val newSeen: Set<String>)

    data class NotificationText(val title: TransitText, val body: TransitText)

    /**
     * Puhdas valinta (yksikkötestattava): aktiiviset häiriöt jotka osuvat suosikkilinjaan (shortName)
     * tai suosikkipysäkkiin (nimi), joita ei ole vielä nähty. Palauttaa myös uuden "nähdyt"-joukon =
     * nyt aktiiviset osumat (kattorajattu), jotta setti ei kasva ja ratkennut häiriö voi toistua.
     * Pysäkki osuu ensisijaisesti gtfsId:llä ([favStopIds]), koska nimi vaihtuu sovelluksen kielen mukaan.
     */
    fun selectNewAlerts(
        alerts: List<TransitAlert>,
        favLines: Set<String>,
        favStops: Set<String>,
        seen: Set<String>,
        nowSec: Long,
        favStopIds: Set<String> = emptySet(),
    ): Selection {
        val matchedKeys = LinkedHashSet<String>()
        val toNotify = ArrayList<TransitAlert>()
        for (a in alerts) {
            if (!a.isActiveAt(nowSec)) continue
            val lineHit = a.routeShortName.isNotEmpty() && favLines.contains(a.routeShortName)
            val stopHit = a.matchesStop(favStopIds, favStops)
            if (!lineHit && !stopHit) continue
            val key = alertKey(a)
            if (!matchedKeys.add(key)) continue // sama häiriö kahdesti tällä kierroksella → kerran
            if (!seen.contains(key)) toNotify.add(a)
        }
        val newSeen = if (matchedKeys.size <= SEEN_CAP) {
            matchedKeys.toSet()
        } else {
            matchedKeys.toList().takeLast(SEEN_CAP).toSet()
        }
        return Selection(toNotify, newSeen)
    }

    /** Tarkistus taustatyöstä. Gate kytkimellä + ohita jos ei suosikkeja. */
    fun check(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val favLines = TransitFavorites.getLines(context)
            .mapNotNull { it.shortName.takeIf { s -> s.isNotEmpty() } }.toSet()
        val stops = TransitFavorites.getStops(context)
        val favStops = stops.mapNotNull { it.name.takeIf { s -> s.isNotEmpty() } }.toSet()
        val favStopIds = stops.mapNotNull { it.gtfsId.takeIf { s -> s.isNotEmpty() } }.toSet()
        if (favLines.isEmpty() && favStops.isEmpty() && favStopIds.isEmpty()) return
        val alerts = try { DigitransitApi.serviceAlerts(TransitRegion.HSL) } catch (e: Exception) { return }
        val nowSec = System.currentTimeMillis() / 1000L
        val result = selectNewAlerts(alerts, favLines, favStops, loadSeen(prefs), nowSec, favStopIds)
        android.util.Log.i(
            "HslAlertNotifier",
            "favLines=${favLines.size} favStops=${favStops.size} alerts=${alerts.size} uusia=${result.toNotify.size}",
        )
        if (result.toNotify.isNotEmpty()) postFor(context, result.toNotify)
        saveSeen(prefs, result.newSeen)
    }

    /** Ilmoituksen otsikko ja teksti rakenteena (yksikkötestattava ilman Contextia). */
    fun notificationText(toNotify: List<TransitAlert>): NotificationText {
        if (toNotify.size == 1) {
            val a = toNotify[0]
            val title = if (a.routeShortName.isNotEmpty()) {
                TransitText.Res(R.string.transit_hsl_alert_title_line, listOf(a.routeShortName))
            } else {
                TransitText.Res(R.string.transit_hsl_alert_title_stop, listOf(a.stopName))
            }
            return NotificationText(title, TransitText.Raw(a.displayText()))
        }
        return NotificationText(
            TransitText.Res(R.string.transit_hsl_alert_multi_title),
            TransitText.Plural(R.plurals.transit_hsl_alert_multi_body, toNotify.size, listOf(toNotify.size)),
        )
    }

    private fun postFor(context: Context, toNotify: List<TransitAlert>) {
        val text = notificationText(toNotify)
        Notifications.post(
            context, Notifications.CHANNEL_HSL_ALERTS, Notifications.NOTIF_ID_HSL,
            text.title.resolve(context), text.body.resolve(context), "HSL_DISRUPTIONS", favoritesFocus = true,
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
