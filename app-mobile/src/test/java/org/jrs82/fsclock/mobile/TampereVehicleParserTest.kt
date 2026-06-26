package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Puhdas (Android-vapaa) GTFS-RT-protobuf-parseri Tampereen/Nyssen MQTT-live-payloadille.
 * Fixture = oikea Digitransit-viesti (mqtt.digitransit.fi /gtfsrt/vp/tampere/#, taltioitu 26.6.2026):
 * trip 122_20815_16362552, linja 15, stop 5200, IN_TRANSIT_TO, lat 61.511929 lon 23.921028,
 * seq 14, bearing 52, ts 1782462472, ajoneuvo 6990_415. Kentät varmistettu Digitransit GraphQL:ää
 * vasten (trip.gtfsId "tampere:122_20815_16362552", route.gtfsId "tampere:15", stop.gtfsId "tampere:5200").
 */
class TampereVehicleParserTest {

    // 153-tavuinen FeedMessage (yksi VehiclePosition-entity).
    private val sampleB64 =
        "Cg0KAzEuMBABGIrw+NEGEocBCgg2OTkwXzQxNSJ7CjQKEjEyMl8yMDgxNV8xNjM2MjU1MhIIMTE6MTY6MDAa" +
            "CDIwMjYwNjI2IAAqBjE1Njk5MDABEhQNNwx2QhVEXr9BHQAAUEItHMc9QRgOIAIoiPD40QYwADoENTIwMEIb" +
            "Cgg2OTkwXzQxNRIGU29yaWxhGgdaUEwtODc2"

    private fun sampleBytes(): ByteArray = Base64.getDecoder().decode(sampleB64)

    @Test
    fun `parse poimii ajoneuvon kentat fixturesta`() {
        val vps = TampereVehicleParser.parse(sampleBytes())
        assertEquals(1, vps.size)
        val vp = vps[0]
        assertEquals("122_20815_16362552", vp.tripId)
        assertEquals("5200", vp.stopId)
        assertEquals(14, vp.stopSequence)
        assertEquals(1, vp.directionId)
        assertEquals(1782462472L, vp.timestampSec)
        // IN_TRANSIT_TO → lähestyy pysäkkiä (ei pysäkillä).
        assertTrue(vp.incoming)
        assertEquals(61.5119, vp.lat, 0.001)
        assertEquals(23.9210, vp.lon, 0.001)
        assertEquals(52.0, vp.bearing, 0.5)
    }

    @Test
    fun `parse palauttaa tyhjan eika heita roskasyotteelle`() {
        assertTrue(TampereVehicleParser.parse(byteArrayOf(1, 2, 3, 4, 5)).isEmpty())
        assertTrue(TampereVehicleParser.parse(ByteArray(0)).isEmpty())
    }

    @Test
    fun `incoming on epatosi vain STOPPED_AT-tilassa`() {
        // Puhdas statuslogiikka (sama kuin HSL-GraphQL-polku: incoming = !STOPPED_AT).
        assertFalse(TampereVehicleParser.incomingFor("STOPPED_AT"))
        assertTrue(TampereVehicleParser.incomingFor("IN_TRANSIT_TO"))
        assertTrue(TampereVehicleParser.incomingFor("INCOMING_AT"))
        assertTrue(TampereVehicleParser.incomingFor(""))
    }
}
