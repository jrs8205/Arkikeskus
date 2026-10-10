package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.FmiCounties
import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kunta → maakunta -haun yksikkötestit. */
class FinnishRegionsTest {

    @Test fun majorCitiesMapToRegion() {
        assertEquals("Uusimaa", FinnishRegions.regionForPlace("Vantaa"))
        assertEquals("Uusimaa", FinnishRegions.regionForPlace("Helsinki"))
        assertEquals("Pirkanmaa", FinnishRegions.regionForPlace("Tampere"))
        assertEquals("Varsinais-Suomi", FinnishRegions.regionForPlace("Turku"))
        assertEquals("Pohjois-Pohjanmaa", FinnishRegions.regionForPlace("Oulu"))
        assertEquals("Päijät-Häme", FinnishRegions.regionForPlace("Lahti"))
        assertEquals("Lappi", FinnishRegions.regionForPlace("Rovaniemi"))
        assertEquals("Pohjanmaa", FinnishRegions.regionForPlace("Vaasa"))
    }

    @Test fun caseAndWhitespaceInsensitive() {
        assertEquals("Uusimaa", FinnishRegions.regionForPlace("  vantaa  "))
        assertEquals("Varsinais-Suomi", FinnishRegions.regionForPlace("Koski Tl"))
    }

    @Test fun compoundPlaceUsesFirstPart() {
        assertEquals("Uusimaa", FinnishRegions.regionForPlace("Vantaa, Tikkurila"))
    }

    @Test fun everyRegionHasDisplayName() {
        // regionForPlace-tulos näytetään FmiCountiesin nimiresurssilla → avainten pitää täsmätä
        assertEquals(19, FinnishRegions.regions.size)
        for (r in FinnishRegions.regions) {
            assertNotEquals(r, 0, FmiCounties.nameRes(r))
        }
        assertEquals(R.string.region_pirkanmaa, FmiCounties.nameRes(FinnishRegions.regionForPlace("Tampere")))
    }

    @Test fun unknownPlaceReturnsNull() {
        assertNull(FinnishRegions.regionForPlace("Tukholma"))
        assertNull(FinnishRegions.regionForPlace(""))
        assertNull(FinnishRegions.regionForPlace(null))
    }
}
