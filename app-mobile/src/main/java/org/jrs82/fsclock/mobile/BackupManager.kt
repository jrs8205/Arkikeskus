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

    data class ExportResult(val workouts: Int, val prefs: Int)
    data class RestoreResult(val workouts: Int, val prefs: Int, val skipped: Int)

    fun export(context: Context, out: OutputStream): ExportResult {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prefsJson = JSONObject()
        var prefCount = 0
        for ((key, value) in prefs.all) {
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
        out.write(root.toString().toByteArray(Charsets.UTF_8))
        out.flush()
        return ExportResult(all.size, prefCount)
    }

    /** Palauttaa varmuuskopion. Asetukset kirjoitetaan olemassa olevien päälle (EI clear() —
     *  laitekohtainen tila kuten lupaliput säilyy); lenkit dedupataan (startedAtMs, type) -parilla,
     *  joten saman tiedoston voi tuoda useasti ilman duplikaatteja. Kutsu IO-säikeestä; kutsujan
     *  vastuulla on varmistaa ettei lenkki ole käynnissä (WorkoutTracker IDLE). */
    fun restore(context: Context, input: InputStream): RestoreResult {
        val root = JSONObject(input.readBytes().toString(Charsets.UTF_8))
        require(root.optString("format") == FORMAT) { "Tuntematon tiedostomuoto" }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
        val prefsJson = root.optJSONObject("prefs") ?: JSONObject()
        var prefCount = 0
        for (key in prefsJson.keys()) {
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
        editor.apply()

        val dao = FsClockDb.get(context).workoutDao()
        val existing = HashSet<String>()
        for (w in dao.allFinishedWorkouts()) existing.add(w.startedAtMs.toString() + ":" + w.type)
        val arr = root.optJSONArray("workouts") ?: JSONArray()
        var imported = 0
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
            w.updatedAtMs = System.currentTimeMillis()
            val id = dao.insertWorkout(w)
            val pts = wj.optJSONArray("points") ?: JSONArray()
            val pointList = ArrayList<WorkoutPointEntity>(pts.length())
            for (j in 0 until pts.length()) {
                val row = pts.optJSONArray(j) ?: continue
                val p = WorkoutPointEntity()
                p.workoutId = id
                p.tMs = row.optLong(0)
                p.lat = row.optDouble(1)
                p.lon = row.optDouble(2)
                p.altM = if (row.isNull(3)) null else row.optDouble(3)
                p.speedMps = row.optDouble(4, 0.0).toFloat()
                p.accuracyM = row.optDouble(5, 0.0).toFloat()
                p.segment = row.optInt(6)
                pointList.add(p)
            }
            if (pointList.isNotEmpty()) dao.insertPoints(pointList)
            val sps = wj.optJSONArray("splits") ?: JSONArray()
            for (j in 0 until sps.length()) {
                val row = sps.optJSONArray(j) ?: continue
                val s = WorkoutSplitEntity()
                s.workoutId = id
                s.splitIndex = row.optInt(0)
                s.durationMs = row.optLong(1)
                s.endLat = row.optDouble(2)
                s.endLon = row.optDouble(3)
                dao.insertSplit(s)
            }
            imported++
        }
        return RestoreResult(imported, prefCount, skipped)
    }
}
