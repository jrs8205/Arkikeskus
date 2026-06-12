package org.jrs82.fsclock.mobile

import android.content.Context
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.db.WorkoutEntity
import org.jrs82.fsclock.db.WorkoutPointEntity
import org.jrs82.fsclock.db.WorkoutSplitEntity
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Jaetun lenkkitiedoston (GPX 1.x / TCX) tuonti "Jaettu lenkki" -merkinnällä (shared=true).
 * Matka, kesto, väliajat, nousu/lasku ja maksiminopeus lasketaan uudelleen pisteistä samoilla
 * periaatteilla kuin trackerissa, joten yhteenveto ja kartta toimivat täsmälleen kuten omilla
 * lenkeillä. Dedup (startedAtMs, type) — saman tiedoston voi avata useasti ilman duplikaatteja.
 */
internal object WorkoutFileImporter {

    data class ImportedWorkout(val workoutId: Long, val name: String, val alreadyExists: Boolean)

    private class ParsedPoint(
        val tMs: Long, val lat: Double, val lon: Double, val altM: Double?, val segment: Int)

    private class Parsed {
        var name: String? = null
        var type = WorkoutEntity.TYPE_WALK
        var kcal = 0
        val points = ArrayList<ParsedPoint>()
    }

    /** Kutsu IO-säikeestä. Palauttaa null jos tiedosto ei ole GPX/TCX tai siinä ei ole reittiä. */
    fun importStream(context: Context, input: InputStream): ImportedWorkout? {
        val text = input.readBytes().toString(Charsets.UTF_8)
        val parsed = when {
            text.contains("<gpx") -> parseGpx(text)
            text.contains("<TrainingCenterDatabase") -> parseTcx(text)
            else -> return null
        }
        if (parsed.points.size < 2) return null
        return insert(context, parsed)
    }

    // ---------- GPX ----------

    private fun parseGpx(text: String): Parsed {
        val out = Parsed()
        val p = android.util.Xml.newPullParser()
        p.setInput(StringReader(text))
        var segment = 0
        var inTrkpt = false
        var lat = 0.0; var lon = 0.0; var alt: Double? = null; var tMs = 0L
        var inTrk = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "trk" -> inTrk = true
                    "trkseg" -> segment++
                    "trkpt" -> {
                        inTrkpt = true
                        lat = p.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = p.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        alt = null; tMs = 0L
                    }
                    "ele" -> if (inTrkpt) alt = p.nextText().toDoubleOrNull()
                    "time" -> if (inTrkpt) tMs = parseIsoMs(p.nextText())
                    "name" -> if (inTrk && out.name == null) out.name = p.nextText().trim()
                    "type" -> if (inTrk) {
                        val t = p.nextText().lowercase()
                        if ("cycl" in t || "bik" in t || t == "1") out.type = WorkoutEntity.TYPE_BIKE
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "trkpt" -> {
                        if (inTrkpt && lat != 0.0 && lon != 0.0) {
                            out.points.add(ParsedPoint(tMs, lat, lon, alt, segment))
                        }
                        inTrkpt = false
                    }
                    "trk" -> inTrk = false
                }
            }
            event = p.next()
        }
        return out
    }

    // ---------- TCX ----------

    private fun parseTcx(text: String): Parsed {
        val out = Parsed()
        val p = android.util.Xml.newPullParser()
        p.setInput(StringReader(text))
        var segment = 0
        var inTrackpoint = false
        var lat = 0.0; var lon = 0.0; var alt: Double? = null; var tMs = 0L
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "Activity" -> {
                        val sport = p.getAttributeValue(null, "Sport") ?: ""
                        if (sport.equals("Biking", true)) out.type = WorkoutEntity.TYPE_BIKE
                    }
                    "Track" -> segment++
                    "Trackpoint" -> { inTrackpoint = true; lat = 0.0; lon = 0.0; alt = null; tMs = 0L }
                    "Time" -> if (inTrackpoint) tMs = parseIsoMs(p.nextText())
                    "LatitudeDegrees" -> if (inTrackpoint) lat = p.nextText().toDoubleOrNull() ?: 0.0
                    "LongitudeDegrees" -> if (inTrackpoint) lon = p.nextText().toDoubleOrNull() ?: 0.0
                    "AltitudeMeters" -> if (inTrackpoint) alt = p.nextText().toDoubleOrNull()
                    "Calories" -> out.kcal += p.nextText().toDoubleOrNull()?.toInt() ?: 0
                    "Notes" -> if (out.name == null) out.name = p.nextText().trim().ifEmpty { null }
                }
                XmlPullParser.END_TAG -> if (p.name == "Trackpoint") {
                    if (inTrackpoint && lat != 0.0 && lon != 0.0) {
                        out.points.add(ParsedPoint(tMs, lat, lon, alt, segment))
                    }
                    inTrackpoint = false
                }
            }
            event = p.next()
        }
        return out
    }

    private fun parseIsoMs(s: String): Long = try {
        Instant.parse(s.trim()).toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    // ---------- Tunnuslukujen laskenta + tallennus ----------

    private fun insert(context: Context, parsed: Parsed): ImportedWorkout {
        val pts = parsed.points
        val startedAt = pts.firstOrNull { it.tMs > 0 }?.tMs ?: System.currentTimeMillis()
        val endedAt = pts.lastOrNull { it.tMs > 0 }?.tMs ?: startedAt

        val dao = FsClockDb.get(context).workoutDao()
        val displayName = parsed.name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: workoutDefaultName(parsed.type, startedAt)
        // Dedup: sama lenkki (alkuaika+laji) on jo laitteella → ei tuplatuontia.
        val dup = dao.allFinishedWorkouts().firstOrNull {
            it.startedAtMs == startedAt && it.type == parsed.type
        }
        if (dup != null) return ImportedWorkout(dup.id, workoutDisplayName(dup), true)

        // Matka + liikkeellä-aika + maksiminopeus + km-väliajat. Aikadeltat leikataan 30 s:iin
        // (sama periaate kuin trackerin katkosäännössä) → tauot eivät vuoda kestoon.
        var dist = 0.0
        var movingMs = 0L
        var maxSpeed = 0f
        val splits = ArrayList<WorkoutSplitEntity>()
        var nextSplitM = 1000.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (a.segment != b.segment) continue
            val d = haversineM(a.lat, a.lon, b.lat, b.lon)
            val dtRaw = if (a.tMs > 0 && b.tMs > 0) b.tMs - a.tMs else 0L
            val dt = dtRaw.coerceIn(0L, 30_000L)
            val before = dist
            dist += d
            movingMs += dt
            if (dtRaw in 1..30_000 && d / (dtRaw / 1000.0) < 45.0) {
                maxSpeed = max(maxSpeed, (d / (dtRaw / 1000.0)).toFloat())
            }
            while (dist >= nextSplitM && d > 0) {
                // Interpoloi km-rajan ylityspiste ja sen "liikkeellä-aika".
                val f = (nextSplitM - before) / d
                val s = WorkoutSplitEntity()
                s.splitIndex = (nextSplitM / 1000.0).toInt()
                s.durationMs = movingMs - dt + (dt * f).toLong() -
                    (splits.sumOf { it.durationMs })
                s.endLat = a.lat + (b.lat - a.lat) * f
                s.endLon = a.lon + (b.lon - a.lon) * f
                splits.add(s)
                nextSplitM += 1000.0
            }
        }

        // Nousu/lasku 2 m hystereesillä (sama periaate kuin trackerissa).
        var gain = 0.0; var loss = 0.0
        var base: Double? = null
        for (pt in pts) {
            val alt = pt.altM ?: continue
            val b = base
            if (b == null) { base = alt; continue }
            if (alt - b > 2.0) { gain += alt - b; base = alt }
            else if (b - alt > 2.0) { loss += b - alt; base = alt }
        }

        val w = WorkoutEntity()
        w.type = parsed.type
        w.status = WorkoutEntity.STATUS_FINISHED
        w.startedAtMs = startedAt
        w.endedAtMs = endedAt
        w.movingTimeMs = movingMs
        w.distanceM = dist
        w.maxSpeedMps = maxSpeed
        w.elevGainM = gain
        w.elevLossM = loss
        w.kcal = parsed.kcal
        w.name = parsed.name?.trim().takeUnless { it.isNullOrEmpty() }
        w.shared = true
        w.updatedAtMs = System.currentTimeMillis()
        val id = dao.insertWorkout(w)

        val rows = ArrayList<WorkoutPointEntity>(pts.size)
        for (pt in pts) {
            val r = WorkoutPointEntity()
            r.workoutId = id
            r.tMs = pt.tMs
            r.lat = pt.lat
            r.lon = pt.lon
            r.altM = pt.altM
            r.segment = pt.segment
            rows.add(r)
        }
        dao.insertPoints(rows)
        for (s in splits) {
            s.workoutId = id
            dao.insertSplit(s)
        }
        return ImportedWorkout(id, displayName, false)
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
