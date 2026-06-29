package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightsClientTest {
    private val json = """
        {"updated":"2026-06-29T12:23:00Z",
         "dep":[{"apt":"HEL","fno":"AY1731","sch":"2026-06-29T05:55:00Z",
                 "est":"2026-06-29T06:10:00Z","act":null,"scode":"SCH","st":"Aikataulussa",
                 "apt2":"FNC","city":"Funchal","gate":"28","stand":"28","belt":null,
                 "chk":"2 / 207","ac":"321","cs":["AY123","QF8238"],"gatePrev":"18","via":["Lissabon"],"acreg":"OHLZH","callsign":"FIN36M","cgate":null,"cboard":"2026-06-29T05:30:00Z","cfinal":null,"cclosed":null}],
         "arr":[{"apt":"OUL","fno":"AY432","sch":"2026-06-29T05:40:00Z",
                 "est":null,"act":"2026-06-29T05:40:00Z","scode":"LAN","st":"Laskeutunut",
                 "apt2":"HEL","city":"Helsinki","gate":null,"stand":"22","area":"2A","belt":"6","beltStatus":"not-started","terminal":"2",
                 "chk":null,"ac":"E90","cs":[]}]}
    """.trimIndent()

    @Test fun parsoiKentatJaAjat() {
        val data = FlightsClient.parse(json)
        assertEquals(1, data.dep.size)
        assertEquals(1, data.arr.size)
        val d = data.dep[0]
        assertEquals("AY1731", d.flightNo)
        assertEquals(FlightDir.DEP, d.dir)
        assertEquals("Funchal", d.city)
        assertEquals(listOf("AY123", "QF8238"), d.codeshares)
        assertEquals("18", d.gatePrev)
        assertEquals(listOf("Lissabon"), d.via)
        assertEquals("OHLZH", d.aircraftReg)
        assertEquals("FIN36M", d.callsign)
        assertNotNull(d.callBoardingMs)
        assertNull(d.actualMs)
        // est 06:10 vs sch 05:55 -> 15 min myöhässä, effective = est
        assertEquals(15L, d.delayMin)
        assertEquals(d.estimatedMs, d.effectiveMs)
        val a = data.arr[0]
        assertEquals(FlightDir.ARR, a.dir)
        assertEquals("2A", a.baggageArea)
        assertEquals("6", a.belt)
        assertEquals("not-started", a.beltStatus)
        assertEquals("2", a.terminal)
        assertNull(a.gate)
        // act asetettu -> effective = act
        assertEquals(a.actualMs, a.effectiveMs)
    }

    @Test fun sietaaTyhjanJaNullin() {
        val data = FlightsClient.parse("""{"updated":null,"dep":[],"arr":[]}""")
        assertEquals(0L, data.updatedMs)
        assertTrue(data.dep.isEmpty())
        assertTrue(data.arr.isEmpty())
    }
}
