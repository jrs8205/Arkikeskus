package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.jrs82.fsclock.db.WorkoutEntity
import org.jrs82.fsclock.db.WorkoutPointEntity
import org.jrs82.fsclock.db.WorkoutSplitEntity
import kotlin.math.cos

/** TCX-viennin lap-jako (täydet kilometrit + vajaa loppulap) ja roundtrip-tuonti. */
class WorkoutTcxExporterTest {

    /** 26 pistettä 100 m välein itään leveyspiirillä 60° → kumulatiivinen matka ~2 500 m. */
    private fun line(): List<WorkoutPointEntity> {
        val dLon = 100.0 / (111_320.0 * cos(Math.toRadians(60.0)))
        return (0..25).map { i ->
            WorkoutPointEntity().also {
                it.tMs = 1781258400000L + i * 60_000L
                it.lat = 60.0
                it.lon = 24.0 + i * dLon
                it.altM = 20.0 + i
                it.segment = 1
            }
        }
    }

    private fun workout(type: Int) = WorkoutEntity().also {
        it.type = type
        it.name = "Testilenkki"
        it.startedAtMs = 1781258400000L
        it.distanceM = 2500.0
        it.movingTimeMs = 1_500_000 // 25 min
        it.kcal = 120
    }

    private fun splits() = listOf(
        WorkoutSplitEntity().also { it.splitIndex = 1; it.durationMs = 600_000; it.endLat = 60.0; it.endLon = 24.018 },
        WorkoutSplitEntity().also { it.splitIndex = 2; it.durationMs = 620_000; it.endLat = 60.0; it.endLon = 24.036 },
    )

    @Test
    fun `lapit kilometreittain ja vajaa loppulap`() {
        val tcx = WorkoutTcxExporter.buildTcx(workout(WorkoutEntity.TYPE_WALK), line(), splits())
        // 2 täyttä km + vajaa = 3 lapia
        assertEquals(3, Regex("<Lap StartTime=").findAll(tcx).count())
        // Lap-ajat sovelluksen km-väliajoista; vajaa = 1500 − 600 − 620 = 280 s
        assertTrue(tcx.contains("<TotalTimeSeconds>600.0</TotalTimeSeconds>"))
        assertTrue(tcx.contains("<TotalTimeSeconds>620.0</TotalTimeSeconds>"))
        assertTrue(tcx.contains("<TotalTimeSeconds>280.0</TotalTimeSeconds>"))
        // Täydet lapit 1000 m, kalorit kokonaisuutena ensimmäisessä lapissa
        assertEquals(2, Regex("<DistanceMeters>1000\\.0</DistanceMeters>").findAll(tcx).count())
        assertEquals(1, Regex("<Calories>120</Calories>").findAll(tcx).count())
        assertEquals(2, Regex("<Calories>0</Calories>").findAll(tcx).count())
        // Kävely = Other (TCX-enum ei tunne Walkingia)
        assertTrue(tcx.contains("<Activity Sport=\"Other\">"))
        assertTrue(tcx.contains("<Notes>Testilenkki</Notes>"))
    }

    @Test
    fun `pyoraily on Biking`() {
        val tcx = WorkoutTcxExporter.buildTcx(workout(WorkoutEntity.TYPE_BIKE), line(), splits())
        assertTrue(tcx.contains("<Activity Sport=\"Biking\">"))
    }

    @Test
    fun `ilman splitteja yksi manual-lap koko lenkista`() {
        val tcx = WorkoutTcxExporter.buildTcx(workout(WorkoutEntity.TYPE_WALK), line(), emptyList())
        assertEquals(1, Regex("<Lap StartTime=").findAll(tcx).count())
        assertTrue(tcx.contains("<TotalTimeSeconds>1500.0</TotalTimeSeconds>"))
        assertTrue(tcx.contains("<DistanceMeters>2500.0</DistanceMeters>"))
        assertTrue(tcx.contains("<TriggerMethod>Manual</TriggerMethod>"))
    }

    @Test
    fun `iso gps-hyppy ei tuota tyhjia track-osuuksia`() {
        // 2 pistettä 3 km päässä toisistaan → lap 1 jää pisteettömäksi (km 1–2 välillä ei pisteitä)
        val dLon3km = 3000.0 / (111_320.0 * cos(Math.toRadians(60.0)))
        val pts = listOf(
            WorkoutPointEntity().also {
                it.tMs = 1781258400000L; it.lat = 60.0; it.lon = 24.0; it.segment = 1
            },
            WorkoutPointEntity().also {
                it.tMs = 1781258400000L + 600_000L; it.lat = 60.0; it.lon = 24.0 + dLon3km; it.segment = 1
            },
        )
        val w = workout(WorkoutEntity.TYPE_WALK).also { it.distanceM = 3000.0 }
        val tcx = WorkoutTcxExporter.buildTcx(w, pts, splits())
        assertEquals(3, Regex("<Lap StartTime=").findAll(tcx).count())
        // Vain 2 lapilla on pisteitä → tasan 2 Track-lohkoa, ei yhtään tyhjää
        assertEquals(2, Regex("<Track>").findAll(tcx).count())
        // Pisteetön lap 1 perii edellisen lapin StartTimen → sama aikaleima kahdesti;
        // lap 2 alkaa jälkimmäisen pisteen ajasta
        val t0 = WorkoutGpxExporter.iso(1781258400000L)
        assertEquals(2, Regex("<Lap StartTime=\"$t0\">").findAll(tcx).count())
        assertTrue(tcx.contains("<Lap StartTime=\"${WorkoutGpxExporter.iso(1781258400000L + 600_000L)}\">"))
    }

    @Test
    fun `roundtrip tuontiparserilla`() {
        val tcx = WorkoutTcxExporter.buildTcx(workout(WorkoutEntity.TYPE_WALK), line(), splits())
        val parsed = WorkoutFileImporter.parseTcx(tcx, KXmlParser())
        assertEquals(26, parsed.points.size)
        assertEquals(120, parsed.kcal)
        assertEquals(WorkoutEntity.TYPE_WALK, parsed.type)
        assertEquals("Testilenkki", parsed.name)
        assertEquals(1781258400000L, parsed.points.first().tMs)
        assertEquals(20.0, parsed.points.first().altM!!, 1e-6)
    }
}
