package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.db.WorkoutEntity
import org.jrs82.fsclock.db.WorkoutPointEntity
import org.jrs82.fsclock.db.WorkoutSplitEntity
import java.util.Locale

/**
 * TCX-vienti (Garmin Training Center XML): GPX:ää rikkaampi harjoitusmuoto, jossa
 * km-väliajat (Lap per kilometri) ja kalorit kulkevat natiivisti mukana. Strava ja
 * Garmin Connect tuovat suoraan. Pisteet jaetaan lapeille kumulatiivisella matkalla
 * (haversine, sama laskenta kuin trackerissa); TotalTimeSeconds = sovelluksen
 * km-väliaika (liikkeellä-aikaa, tauot pois).
 */
internal object WorkoutTcxExporter {

    fun buildTcx(
        workout: WorkoutEntity,
        points: List<WorkoutPointEntity>,
        splits: List<WorkoutSplitEntity>,
    ): String {
        // Kumulatiivinen matka per piste (segmenttirajan yli ei kerrytetä — tauon siirtymä
        // ei ole lenkkimatkaa, kuten trackerissakaan).
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            cum[i] = cum[i - 1] +
                if (a.segment == b.segment) haversineM(a.lat, a.lon, b.lat, b.lon) else 0.0
        }

        // Lapit: yksi per täysi kilometri (splits) + vajaa loppulap jos matkaa jää.
        val fullLaps = splits.size
        val partialDistM = (workout.distanceM - fullLaps * 1000.0).coerceAtLeast(0.0)
        val hasPartial = fullLaps == 0 || partialDistM > 1.0
        val lapCount = fullLaps + if (hasPartial) 1 else 0
        val lapPoints = List(lapCount) { mutableListOf<Int>() }
        for (i in points.indices) {
            val lap = (cum[i] / 1000.0).toInt().coerceAtMost(lapCount - 1)
            lapPoints[lap].add(i)
        }
        val splitTotalMs = splits.sumOf { it.durationMs }
        val partialMs = (workout.movingTimeMs - splitTotalMs).coerceAtLeast(0L)

        val sb = StringBuilder(points.size * 160 + 1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<TrainingCenterDatabase ")
            .append("xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" ")
            .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            .append("xsi:schemaLocation=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2 ")
            .append("http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd\">\n")
        sb.append("  <Activities>\n")
        // TCX-Sport tuntee vain Running/Biking/Other → kävely = Other (palvelussa voi vaihtaa).
        sb.append("    <Activity Sport=\"")
            .append(if (workout.type == WorkoutEntity.TYPE_BIKE) "Biking" else "Other")
            .append("\">\n")
        sb.append("      <Id>").append(WorkoutGpxExporter.iso(workout.startedAtMs)).append("</Id>\n")

        for (lap in 0 until lapCount) {
            val idx = lapPoints[lap]
            val startMs = if (idx.isNotEmpty()) points[idx.first()].tMs
                else if (lap == 0) workout.startedAtMs else workout.startedAtMs + lap
            val isPartial = hasPartial && lap == lapCount - 1
            val timeSec: Double
            val distM: Double
            if (fullLaps == 0) {
                timeSec = workout.movingTimeMs / 1000.0
                distM = workout.distanceM
            } else if (isPartial) {
                timeSec = partialMs / 1000.0
                distM = partialDistM
            } else {
                timeSec = splits[lap].durationMs / 1000.0
                distM = 1000.0
            }
            sb.append("      <Lap StartTime=\"").append(WorkoutGpxExporter.iso(startMs)).append("\">\n")
            sb.append("        <TotalTimeSeconds>").append(num(timeSec)).append("</TotalTimeSeconds>\n")
            sb.append("        <DistanceMeters>").append(num(distM)).append("</DistanceMeters>\n")
            // Kalorit ovat lenkkikohtainen arvio → koko summa ensimmäiseen lapiin (yleinen tapa).
            sb.append("        <Calories>").append(if (lap == 0) workout.kcal.coerceAtLeast(0) else 0)
                .append("</Calories>\n")
            sb.append("        <Intensity>Active</Intensity>\n")
            sb.append("        <TriggerMethod>")
                .append(if (isPartial || fullLaps == 0) "Manual" else "Distance")
                .append("</TriggerMethod>\n")
            sb.append("        <Track>\n")
            for (i in idx) {
                val p = points[i]
                sb.append("          <Trackpoint>")
                sb.append("<Time>").append(WorkoutGpxExporter.iso(p.tMs)).append("</Time>")
                sb.append("<Position><LatitudeDegrees>").append(WorkoutGpxExporter.coord(p.lat))
                    .append("</LatitudeDegrees><LongitudeDegrees>").append(WorkoutGpxExporter.coord(p.lon))
                    .append("</LongitudeDegrees></Position>")
                val alt = p.altM
                if (alt != null) {
                    sb.append("<AltitudeMeters>").append(String.format(Locale.US, "%.1f", alt))
                        .append("</AltitudeMeters>")
                }
                sb.append("<DistanceMeters>").append(num(cum[i])).append("</DistanceMeters>")
                sb.append("</Trackpoint>\n")
            }
            sb.append("        </Track>\n")
            sb.append("      </Lap>\n")
        }

        sb.append("      <Notes>").append(WorkoutGpxExporter.xmlEscape(workoutDisplayName(workout)))
            .append("</Notes>\n")
        sb.append("    </Activity>\n")
        sb.append("  </Activities>\n")
        sb.append("</TrainingCenterDatabase>\n")
        return sb.toString()
    }

    private fun num(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
