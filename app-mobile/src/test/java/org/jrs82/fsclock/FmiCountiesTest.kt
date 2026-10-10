package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmiCountiesTest {
    @Test fun knownCodes() {
        assertEquals("Uusimaa", FmiCounties.regionFor(1))
        assertEquals("Pirkanmaa", FmiCounties.regionFor(6))
        assertEquals("Lappi", FmiCounties.regionFor(19))
        assertEquals("Ahvenanmaa", FmiCounties.regionFor(21))
    }
    @Test fun unknownCode() { assertEquals("", FmiCounties.regionFor(3)) }
    @Test fun parsesRef() {
        assertEquals("Uusimaa", FmiCounties.regionForRef("http://gml.fmi.fi/static/2025/FI/county.xml#county.1"))
        assertEquals("", FmiCounties.regionForRef("nonsense"))
    }
    @Test fun allRegionsHas19() { assertEquals(19, FmiCounties.ALL_REGIONS.size) }
    @Test fun displayNamesAreResources() {
        assertEquals(R.string.region_uusimaa, FmiCounties.nameRes("Uusimaa"))
        assertEquals(R.string.region_ahvenanmaa, FmiCounties.nameRes("Ahvenanmaa"))
        assertEquals(0, FmiCounties.nameRes("Suomenlahden itäosa"))
        assertEquals(0, FmiCounties.nameRes(null))
        val all = FmiCounties.ALL_REGIONS.map { FmiCounties.nameRes(it) }
        assertTrue(all.none { it == 0 })
        assertEquals(19, all.toSet().size)
    }

    @Test fun indexOfOrdersByList() {
        assertTrue(FmiCounties.indexOf("Uusimaa") < FmiCounties.indexOf("Lappi"))
        assertEquals(-1, FmiCounties.indexOf("Mordor"))
    }
}
