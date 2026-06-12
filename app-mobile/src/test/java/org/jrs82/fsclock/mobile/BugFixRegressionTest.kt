package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream

/** Bugiraportin (12.6.2026) korjausten regressiotestit. */
class BugFixRegressionTest {

    @Test
    fun `paceText ei nayta nollatahtia ajattomalle lenkille`() {
        // Tuotu GPX ilman aikaleimoja → movingMs 0 → tahti viiva, EI "0:00 min/km"
        assertEquals("–", paceText(5000.0, 0))
        assertEquals("–", paceText(5000.0, -1))
        // Normaali tapaus toimii yhä: 1 km / 6 min
        assertEquals("6:00 min/km", paceText(1000.0, 360_000))
    }

    @Test
    fun `gpx-parseri hyvaksyy nollakoordinaatin`() {
        // lat/lon 0.0 ovat laillisia (päiväntasaaja/Greenwich) — eivät "puuttuvan arvon" merkkejä
        val gpx = """<?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
            <trkpt lat="0.0" lon="6.5"><time>2026-06-12T10:00:00Z</time></trkpt>
            <trkpt lat="0.001" lon="6.5"><time>2026-06-12T10:00:10Z</time></trkpt>
            <trkpt lat="60.2" lon="24.6"><time>2026-06-12T10:00:20Z</time></trkpt>
            </trkseg></trk></gpx>"""
        val parsed = WorkoutFileImporter.parseGpx(gpx, KXmlParser())
        assertEquals(3, parsed.points.size)
        assertEquals(0.0, parsed.points.first().lat, 1e-9)
    }

    @Test
    fun `gpx-parseri hylkaa pisteen jolta puuttuu koordinaatti`() {
        val gpx = """<?xml version="1.0"?>
            <gpx version="1.1"><trk><trkseg>
            <trkpt lat="60.2"><time>2026-06-12T10:00:00Z</time></trkpt>
            <trkpt lat="60.2" lon="24.6"><time>2026-06-12T10:00:10Z</time></trkpt>
            </trkseg></trk></gpx>"""
        val parsed = WorkoutFileImporter.parseGpx(gpx, KXmlParser())
        assertEquals(1, parsed.points.size)
    }

    @Test
    fun `readAllLimited sallii rajan sisaan jaavan ja katkaisee ylittavan`() {
        val small = ByteArray(1000) { 'a'.code.toByte() }
        assertEquals(1000, readAllLimited(ByteArrayInputStream(small), 1000).size)
        try {
            readAllLimited(ByteArrayInputStream(ByteArray(1001)), 1000)
            fail("Olisi pitänyt heittää FileTooLargeException")
        } catch (e: FileTooLargeException) {
            assertTrue(e.message!!.contains("liian suuri"))
        }
    }
}
