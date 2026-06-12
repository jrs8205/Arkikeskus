package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.jrs82.fsclock.db.WorkoutEntity
import org.jrs82.fsclock.db.WorkoutPointEntity

/** GPX-viennin rakenne + tuontiparserin roundtrip (vienti → tuonti → samat tiedot). */
class WorkoutGpxExporterTest {

    private fun point(t: Long, lat: Double, lon: Double, alt: Double?, seg: Int) =
        WorkoutPointEntity().also {
            it.tMs = t; it.lat = lat; it.lon = lon; it.altM = alt; it.segment = seg
        }

    private fun workout(type: Int, name: String?) = WorkoutEntity().also {
        it.type = type
        it.name = name
        it.startedAtMs = 1781258400000L // 2026-06-12T10:00:00Z
        it.distanceM = 500.0
        it.movingTimeMs = 300_000
    }

    private val points = listOf(
        point(1781258400000L, 60.2055, 24.6559, 12.5, 1),
        point(1781258410000L, 60.2060, 24.6565, 13.0, 1),
        point(1781258420000L, 60.2065, 24.6570, null, 1),
        // tauko → uusi segmentti
        point(1781258500000L, 60.2070, 24.6580, 14.0, 2),
        point(1781258510000L, 60.2075, 24.6585, 14.5, 2),
    )

    @Test
    fun `gpx rakenne ja escapeukset`() {
        val gpx = WorkoutGpxExporter.buildGpx(workout(WorkoutEntity.TYPE_WALK, "Koira & <ilta>"), points)
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("creator=\"Arkikeskus\""))
        assertTrue(gpx.contains("<name>Koira &amp; &lt;ilta&gt;</name>"))
        assertTrue(gpx.contains("<type>walking</type>"))
        // Kaksi segmenttiä → kaksi trkseg-lohkoa
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        // Koordinaatit pisteellä (ei locale-pilkkua), aika UTC sekuntitarkkuudella
        assertTrue(gpx.contains("lat=\"60.205500\" lon=\"24.655900\""))
        assertTrue(gpx.contains("<time>2026-06-12T10:00:00Z</time>"))
        assertTrue(gpx.contains("<ele>12.5</ele>"))
        // Korkeudeton piste EI saa ele-elementtiä mutta saa ajan
        assertTrue(gpx.contains("lat=\"60.206500\" lon=\"24.657000\"><time>"))
    }

    @Test
    fun `pyoraily merkitaan cycling-tyypiksi`() {
        val gpx = WorkoutGpxExporter.buildGpx(workout(WorkoutEntity.TYPE_BIKE, "Pyörä"), points)
        assertTrue(gpx.contains("<type>cycling</type>"))
    }

    @Test
    fun `roundtrip vienti-tuonti sailyttaa tiedot`() {
        val gpx = WorkoutGpxExporter.buildGpx(workout(WorkoutEntity.TYPE_BIKE, "Koira & <ilta>"), points)
        val parsed = WorkoutFileImporter.parseGpx(gpx, KXmlParser())
        assertEquals("Koira & <ilta>", parsed.name)
        assertEquals(WorkoutEntity.TYPE_BIKE, parsed.type)
        assertEquals(5, parsed.points.size)
        assertEquals(1781258400000L, parsed.points.first().tMs)
        assertEquals(60.2055, parsed.points.first().lat, 1e-6)
        assertEquals(12.5, parsed.points.first().altM!!, 1e-6)
        // Segmenttijako säilyy (tauko ei yhdisty)
        assertEquals(2, parsed.points.map { it.segment }.distinct().size)
        assertTrue(parsed.points[2].altM == null)
    }
}
