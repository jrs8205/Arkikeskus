package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WarningsClient.parse]: uusien CAP-kenttien poiminta + Cancel-suodatus. */
class WarningsClientParseTest {

    // Yksi voimassa oleva Update-varoitus + yksi Cancel-varoitus (pitää suodattua pois).
    private val json = """
    {"warnings":[
      {"alert":{"identifier":"id-1","status":"Actual","msgType":"Update","sender":"cap@fmi.fi","info":[
        {"language":"fi-FI","event":"Maastopalovaroitus","severity":"Moderate","certainty":"Likely","urgency":"Future",
         "effective":"2026-06-22T20:24:38+03:00","onset":"2020-01-01T00:00:00+03:00","expires":"2099-01-01T00:00:00+03:00",
         "senderName":"Ilmatieteen laitos","web":"https://www.ilmatieteenlaitos.fi/varoitukset",
         "description":"Maastopalovaroitus on voimassa.",
         "parameter":[{"valueName":"awareness_level","value":"2; yellow; Moderate"},
                      {"valueName":"awareness_type","value":"8; forest-fire"}],
         "area":[{"areaDesc":"Etelä-Pohjanmaa, Keski-Pohjanmaa","geocode":[{"valueName":"EMMA_ID","value":"FI030"}]}]}
      ]}},
      {"alert":{"identifier":"id-2","status":"Actual","msgType":"Cancel","info":[
        {"language":"fi-FI","event":"Hellevaroitus","onset":"2020-01-01T00:00:00+03:00","expires":"2099-01-01T00:00:00+03:00",
         "area":[{"areaDesc":"Uusimaa"}]}
      ]}}
    ]}
    """.trimIndent()

    @Test fun parsesNewFieldsAndDropsCancel() {
        val list = WarningsClient().parse(json)
        assertEquals(1, list.size)
        val w = list[0]
        assertEquals("Maastopalovaroitus", w.event)
        assertEquals(WeatherWarning.AwarenessType.FOREST_FIRE, w.awarenessType)
        assertEquals(WeatherWarning.Level.YELLOW, w.level)
        assertEquals("Moderate", w.severity)
        assertEquals("Likely", w.certainty)
        assertEquals("Future", w.urgency)
        assertEquals("Ilmatieteen laitos", w.senderName)
        assertTrue(w.web.contains("ilmatieteenlaitos"))
        assertTrue(w.effectiveMs > 0L)
    }
}
