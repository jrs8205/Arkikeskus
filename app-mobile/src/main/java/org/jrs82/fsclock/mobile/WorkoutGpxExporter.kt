package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.db.WorkoutEntity
import org.jrs82.fsclock.db.WorkoutPointEntity
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * GPX 1.1 -vienti lenkistä. GPX on urheilupalveluiden de facto -vaihtomuoto: Strava, Komoot ja
 * Garmin Connect tuovat sen suoraan. Pisteissä aika (UTC) ja korkeus → palvelut laskevat
 * nopeudet/vauhdin itse. Yksi <trkseg> per tauko-/katkossegmentti (sama segment-kenttä,
 * jolla yhteenvetokarttakin katkaisee viivan).
 */
internal object WorkoutGpxExporter {

    fun buildGpx(workout: WorkoutEntity, points: List<WorkoutPointEntity>): String {
        val sb = StringBuilder(points.size * 96 + 512)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Arkikeskus\" ")
            .append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            .append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
            .append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <metadata><time>").append(iso(workout.startedAtMs)).append("</time></metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(xmlEscape(workoutDisplayName(workout))).append("</name>\n")
        sb.append("    <type>")
            .append(if (workout.type == WorkoutEntity.TYPE_BIKE) "cycling" else "walking")
            .append("</type>\n")
        var seg = Int.MIN_VALUE
        var open = false
        for (p in points) {
            if (p.segment != seg) {
                if (open) sb.append("    </trkseg>\n")
                sb.append("    <trkseg>\n")
                seg = p.segment
                open = true
            }
            sb.append("      <trkpt lat=\"").append(coord(p.lat))
                .append("\" lon=\"").append(coord(p.lon)).append("\">")
            val alt = p.altM
            if (alt != null) {
                sb.append("<ele>").append(String.format(Locale.US, "%.1f", alt)).append("</ele>")
            }
            sb.append("<time>").append(iso(p.tMs)).append("</time>")
            sb.append("</trkpt>\n")
        }
        if (open) sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** ISO-8601 UTC sekuntitarkkuudella, esim. 2026-06-12T10:15:30Z. */
    fun iso(ms: Long): String =
        Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

    /** Koordinaatti pisteellä (ei locale-pilkkua), 6 desimaalia ≈ 0,1 m. */
    fun coord(v: Double): String = String.format(Locale.US, "%.6f", v)

    fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
