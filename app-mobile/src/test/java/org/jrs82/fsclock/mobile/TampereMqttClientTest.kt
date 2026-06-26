package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tampereen MQTT-clientin puhdas topic-rakennuslogiikka. Topic-suodatin varmistettu live-datalla
 * 26.6.2026: `/gtfsrt/vp/tampere/<agency_id=tyhjä>/<agency_name=tyhjä>/<mode>/<route_id>/<dir_id>/#`,
 * missä route_id ja dir_id saadaan patternCode-segmenteistä [1] ja [2] (esim. "tampere:15:1:01").
 */
class TampereMqttClientTest {

    @Test
    fun `topicForPattern rakentaa reitti+suunta-suodattimen`() {
        assertEquals(
            "/gtfsrt/vp/tampere/+/+/+/15/1/#",
            TampereMqttClient.topicForPattern("tampere:15:1:01"),
        )
    }

    @Test
    fun `topicForPattern sailyttaa kirjainlinjan ja suunnan 0`() {
        assertEquals(
            "/gtfsrt/vp/tampere/+/+/+/8B/0/#",
            TampereMqttClient.topicForPattern("tampere:8B:0:99"),
        )
    }

    @Test
    fun `topicForPattern palauttaa null vajaalle tai tyhjalle koodille`() {
        assertNull(TampereMqttClient.topicForPattern(null))
        assertNull(TampereMqttClient.topicForPattern(""))
        assertNull(TampereMqttClient.topicForPattern("tampere:15")) // suunta puuttuu
        assertNull(TampereMqttClient.topicForPattern("garbage"))
    }
}
