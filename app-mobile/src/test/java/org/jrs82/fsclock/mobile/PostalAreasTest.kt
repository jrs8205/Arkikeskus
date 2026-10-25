package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Postinumeroalueen nimi kaupunginosaksi, kun Androidin Geocoder ei anna kaupunginosaa (MML-katko 10/2026):
 *  Google palautti Raappavuorelle vain "Raappavuorenreuna 2b, 01620 Vantaa" ilman subLocalitya. */
class PostalAreasTest {

    private val asset = File("src/main/assets/postinumeroalueet.tsv")

    private fun area(name: String, vararg municipalities: String) = PostalAreas.Area(name, municipalities.toList())

    @Test fun assetCoversFinlandWithValidRows() {
        val areas = PostalAreas.parse(asset.readLines(Charsets.UTF_8).asSequence())
        assertTrue("alueita ${areas.size}", areas.size > 2_900)
        assertTrue(areas.keys.all { it.length == 5 && it.all(Char::isDigit) })
        assertTrue(areas.values.all { it.name.isNotBlank() && it.municipalities.isNotEmpty() })
        assertEquals(area("Martinlaakso", "Vantaa"), areas["01620"])
        assertEquals(area("Maarianhamina", "Maarianhamina", "Mariehamn"), areas["22100"])
    }

    @Test fun assetNamesTheSourceAndLicence() {
        val header = asset.useLines { lines -> lines.takeWhile { it.startsWith("#") }.joinToString("\n") }
        assertTrue(header.contains("Tilastokeskus"))
        assertTrue(header.contains("CC BY 4.0"))
    }

    @Test fun parseSkipsCommentsBlankAndBrokenLines() {
        val areas = PostalAreas.parse(
            sequenceOf("# kommentti", "", "01620\tMartinlaakso\tVantaa", "0162\tLyhyt\tVantaa", "01630\tVain nimi", "abcde\tX\tY"),
        )
        assertEquals(setOf("01620"), areas.keys)
    }

    @Test fun districtIsAreaNameInTheSameMunicipality() {
        assertEquals("Martinlaakso", PostalAreas.districtFor(area("Martinlaakso", "Vantaa"), "Vantaa"))
        assertEquals("Martinlaakso", PostalAreas.districtFor(area("Martinlaakso", "Vantaa"), "vantaa"))
    }

    @Test fun areaInAnotherMunicipalityGivesNoDistrict() {
        assertNull(PostalAreas.districtFor(area("Martinlaakso", "Vantaa"), "Espoo"))
    }

    @Test fun municipalityPrefixIsStrippedOnlyAtWordBoundary() {
        assertEquals("Keskus", PostalAreas.districtFor(area("Turku Keskus", "Turku"), "Turku"))
        assertEquals("Keskusta - Etu-Töölö", PostalAreas.districtFor(area("Helsinki keskusta - Etu-Töölö", "Helsinki"), "Helsinki"))
        assertEquals("Sairaala-alue", PostalAreas.districtFor(area("Hyvinkää, sairaala-alue", "Hyvinkää"), "Hyvinkää"))
        assertEquals("Vantaanlaakso", PostalAreas.districtFor(area("Vantaanlaakso", "Vantaa"), "Vantaa"))
        assertEquals("Espoon Keskus", PostalAreas.districtFor(area("Espoon Keskus", "Espoo"), "Espoo"))
    }

    @Test fun abbreviationsAreWrittenOut() {
        assertEquals("Kirkonkylä", PostalAreas.districtFor(area("Kärkölä kk", "Kärkölä"), "Kärkölä"))
        assertEquals("Kirkonkylä", PostalAreas.districtFor(area("Pyhtää kk.", "Pyhtää"), "Pyhtää"))
        assertEquals("Asemanseutu", PostalAreas.districtFor(area("Kauhava as", "Kauhava"), "Kauhava"))
    }

    @Test fun areaNamedLikeTheMunicipalityGivesNoDistrict() {
        assertNull(PostalAreas.districtFor(area("Pornainen", "Pornainen"), "Pornainen"))
        // Kaksikielinen kunta: Geocoder voi antaa kumman tahansa nimen.
        assertNull(PostalAreas.districtFor(area("Maarianhamina", "Maarianhamina", "Mariehamn"), "Mariehamn"))
        assertEquals("Centrum", PostalAreas.districtFor(area("Mariehamn Centrum", "Maarianhamina", "Mariehamn"), "Maarianhamina"))
    }

    @Test fun lowercaseNameIsCapitalised() {
        assertEquals("Itäinen", PostalAreas.districtFor(area("Porvoo itäinen", "Porvoo"), "Porvoo"))
    }
}
