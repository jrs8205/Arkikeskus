package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.BuildConfig
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.db.WorkoutEntity
import org.jrs82.fsclock.db.WorkoutPointEntity
import org.jrs82.fsclock.db.WorkoutSplitEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Manuaalinen varmuuskopiointi: kaikki SharedPreferences-asetukset + valmiit lenkit
 * (reitteineen ja väliaikoineen) yhteen JSON-tiedostoon, jonka käyttäjä tallentaa SAF:lla
 * minne haluaa (esim. Google Driveen) — ei vaadi mitään Google-kirjautumista.
 *
 * Tietoisesti JSON eikä raaka SQLite-kopio: tuonti tehdään DAO-inserteillä, jolloin palautus
 * menee aina NYKYISEN skeeman läpi (vanha backup uuteen versioon toimii; puuttuvat kentät
 * saavat oletukset, tuntemattomat ohitetaan). Askel-baseline-kentät jätetään pois — ne ovat
 * laitteen boot-kohtaista kirjanpitoa, merkityksettömiä toisella laitteella.
 */
internal object BackupManager {

    private const val FORMAT = "arkikeskus-backup"
    private const val VERSION = 1

    data class ExportResult(val workouts: Int, val prefs: Int, val bytes: Int)
    data class RestoreResult(val workouts: Int, val prefs: Int, val skipped: Int)

    fun export(context: Context, out: OutputStream): ExportResult {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prefsJson = JSONObject()
        var prefCount = 0
        for ((key, value) in prefs.all) {
            // Automaattibackupin oma tila (kohde-URI ym.) on laitekohtainen — ei mukaan.
            if (key.startsWith("auto_backup_")) continue
            val entry = JSONObject()
            when (value) {
                is String -> { entry.put("t", "string"); entry.put("v", value) }
                is Boolean -> { entry.put("t", "bool"); entry.put("v", value) }
                is Int -> { entry.put("t", "int"); entry.put("v", value) }
                is Long -> { entry.put("t", "long"); entry.put("v", value) }
                is Float -> { entry.put("t", "float"); entry.put("v", value.toDouble()) }
                is Set<*> -> { entry.put("t", "set"); entry.put("v", JSONArray(value.toList())) }
                else -> continue
            }
            prefsJson.put(key, entry)
            prefCount++
        }

        val dao = FsClockDb.get(context).workoutDao()
        val all = dao.allFinishedWorkouts()
        val workouts = JSONArray()
        for (w in all) {
            val wj = JSONObject()
            wj.put("type", w.type)
            wj.put("startedAtMs", w.startedAtMs)
            wj.put("endedAtMs", w.endedAtMs ?: JSONObject.NULL)
            wj.put("movingTimeMs", w.movingTimeMs)
            wj.put("distanceM", w.distanceM)
            wj.put("maxSpeedMps", w.maxSpeedMps.toDouble())
            wj.put("steps", w.steps)
            wj.put("elevGainM", w.elevGainM)
            wj.put("elevLossM", w.elevLossM)
            wj.put("kcal", w.kcal)
            wj.put("autoStopped", w.autoStopped)
            wj.put("name", w.name ?: JSONObject.NULL)
            wj.put("shared", w.shared)
            // Pisteet kompakteina taulukkoriveinä [tMs, lat, lon, altM|null, speed, acc, segment].
            val pts = JSONArray()
            for (p in dao.pointsFor(w.id)) {
                val row = JSONArray()
                row.put(p.tMs); row.put(p.lat); row.put(p.lon)
                row.put(p.altM ?: JSONObject.NULL)
                row.put(p.speedMps.toDouble()); row.put(p.accuracyM.toDouble()); row.put(p.segment)
                pts.put(row)
            }
            wj.put("points", pts)
            val sps = JSONArray()
            for (s in dao.splitsFor(w.id)) {
                val row = JSONArray()
                row.put(s.splitIndex); row.put(s.durationMs); row.put(s.endLat); row.put(s.endLon)
                sps.put(row)
            }
            wj.put("splits", sps)
            workouts.put(wj)
        }

        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("createdAtMs", System.currentTimeMillis())
        root.put("appVersion", BuildConfig.VERSION_NAME)
        root.put("prefs", prefsJson)
        root.put("workouts", workouts)
        val data = root.toString().toByteArray(Charsets.UTF_8)
        out.write(data)
        out.flush()
        return ExportResult(all.size, prefCount, data.size)
    }

    /** Suurin sallittu varmuuskopiotiedoston koko (100 lenkkiä ≈ 5–8 Mt → 30 Mt reilu marginaali). */
    private const val MAX_RESTORE_BYTES = 30 * 1024 * 1024

    private class PendingWorkout(
        val w: WorkoutEntity,
        val points: List<WorkoutPointEntity>,
        val splits: List<WorkoutSplitEntity>,
    )

    /** Palauttaa varmuuskopion KAKSIVAIHEISESTI: ensin koko sisältö validoidaan ja rakennetaan
     *  muistiin, vasta sitten kirjoitetaan — virheellinen tiedosto ei jätä osittaista palautusta.
     *  Asetukset kirjoitetaan commit()-kutsulla (EI apply()), koska palautusta seuraa välitön
     *  prosessin tappo (RestartActivity + exit(0)) — asynkroninen kirjoitus voisi hukkua.
     *  Asetukset kirjoitetaan olemassa olevien päälle (EI clear()); lenkit dedupataan
     *  (startedAtMs, type) -parilla. Kutsu IO-säikeestä; kutsujan vastuulla on varmistaa
     *  ettei lenkki ole käynnissä (WorkoutTracker IDLE). */
    fun restore(context: Context, input: InputStream): RestoreResult {
        val root = JSONObject(readAllLimited(input, MAX_RESTORE_BYTES).toString(Charsets.UTF_8))
        require(root.optString("format") == FORMAT) { "Tuntematon tiedostomuoto" }
        val version = root.optInt("version", 0)
        require(version in 1..VERSION) {
            "Varmuuskopio on uudemmasta sovellusversiosta (v$version) — päivitä sovellus ensin"
        }

        // ---- VAIHE 1: validoi ja rakenna kaikki muistiin, EI vielä kirjoituksia ----
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit() // editor on vain puskuri — mitään ei kirjoitu ennen commit()ia
        val prefsJson = root.optJSONObject("prefs") ?: JSONObject()
        var prefCount = 0
        for (key in prefsJson.keys()) {
            if (key.startsWith("auto_backup_")) continue
            val entry = prefsJson.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "string" -> editor.putString(key, entry.optString("v"))
                "bool" -> editor.putBoolean(key, entry.optBoolean("v"))
                "int" -> editor.putInt(key, entry.optInt("v"))
                "long" -> editor.putLong(key, entry.optLong("v"))
                "float" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "set" -> {
                    val arr = entry.optJSONArray("v") ?: JSONArray()
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.optString(i))
                    editor.putStringSet(key, set)
                }
                else -> continue
            }
            prefCount++
        }

        val db = FsClockDb.get(context)
        val dao = db.workoutDao()
        val existing = HashSet<String>()
        for (w in dao.allFinishedWorkouts()) existing.add(w.startedAtMs.toString() + ":" + w.type)
        val arr = root.optJSONArray("workouts") ?: JSONArray()
        val pending = ArrayList<PendingWorkout>()
        var skipped = 0
        for (i in 0 until arr.length()) {
            val wj = arr.optJSONObject(i) ?: continue
            val key = wj.optLong("startedAtMs").toString() + ":" + wj.optInt("type")
            if (!existing.add(key)) { skipped++; continue }
            val w = WorkoutEntity()
            w.type = wj.optInt("type")
            w.status = WorkoutEntity.STATUS_FINISHED
            w.startedAtMs = wj.optLong("startedAtMs")
            w.endedAtMs = if (wj.isNull("endedAtMs")) null else wj.optLong("endedAtMs")
            w.movingTimeMs = wj.optLong("movingTimeMs")
            w.distanceM = wj.optDouble("distanceM", 0.0)
            w.maxSpeedMps = wj.optDouble("maxSpeedMps", 0.0).toFloat()
            w.steps = wj.optLong("steps")
            w.elevGainM = wj.optDouble("elevGainM", 0.0)
            w.elevLossM = wj.optDouble("elevLossM", 0.0)
            w.kcal = wj.optInt("kcal")
            w.autoStopped = wj.optBoolean("autoStopped")
            w.name = if (wj.isNull("name")) null else wj.optString("name")
            w.shared = wj.optBoolean("shared")
            w.updatedAtMs = System.currentTimeMillis()
            val pts = wj.optJSONArray("points") ?: JSONArray()
            val pointList = ArrayList<WorkoutPointEntity>(pts.length())
            for (j in 0 until pts.length()) {
                val row = pts.optJSONArray(j) ?: continue
                val p = WorkoutPointEntity()
                p.tMs = row.optLong(0)
                p.lat = row.optDouble(1)
                p.lon = row.optDouble(2)
                p.altM = if (row.isNull(3)) null else row.optDouble(3)
                p.speedMps = row.optDouble(4, 0.0).toFloat()
                p.accuracyM = row.optDouble(5, 0.0).toFloat()
                p.segment = row.optInt(6)
                pointList.add(p)
            }
            val splitList = ArrayList<WorkoutSplitEntity>()
            val sps = wj.optJSONArray("splits") ?: JSONArray()
            for (j in 0 until sps.length()) {
                val row = sps.optJSONArray(j) ?: continue
                val s = WorkoutSplitEntity()
                s.splitIndex = row.optInt(0)
                s.durationMs = row.optLong(1)
                s.endLat = row.optDouble(2)
                s.endLon = row.optDouble(3)
                splitList.add(s)
            }
            pending.add(PendingWorkout(w, pointList, splitList))
        }

        // ---- VAIHE 2: kirjoitukset — synkronisesti, koska prosessi tapetaan kohta ----
        // Lenkit kirjoitetaan YHTEEN Room-transaktioon: jos jokin insert heittää (esim. levy
        // täynnä), KOKO lenkkijoukko peruuntuu → ei osittaista palautusta. Asetukset kirjoitetaan
        // vasta kun lenkit on saatu talteen, joten DB ja asetukset palautuvat joko molemmat tai
        // ei kumpikaan (poikkeus etenee kutsujan virhekäsittelyyn ilman puolittaista tilaa).
        var imported = 0
        db.runInTransaction(Runnable {
            for (pw in pending) {
                val id = dao.insertWorkout(pw.w)
                pw.points.forEach { it.workoutId = id }
                if (pw.points.isNotEmpty()) dao.insertPoints(pw.points)
                for (s in pw.splits) {
                    s.workoutId = id
                    dao.insertSplit(s)
                }
                imported++
            }
        })
        check(editor.commit()) { "Asetusten kirjoitus levylle epäonnistui" }
        // Uudelleenkäynnistyksen jälkeinen vahvistus-toast (MobileComposeMainActivity lukee).
        // commit(): restart tappaa prosessin heti — apply() voisi hukata lipun JA palautetut arvot.
        prefs.edit().putBoolean("restore_done_pending", true).commit()
        return RestoreResult(imported, prefCount, skipped)
    }
}
