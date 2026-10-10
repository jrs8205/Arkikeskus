package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WarningsClient.parse]: uusien CAP-kenttien poiminta + Cancel-suodatus + kielilohkon valinta. */
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

    // Sama merivaroitus kolmella kielellä; sv-lohkon kuvaus tyhjä → fi-teksti.
    private val multiJson = """
    {"warnings":[
      {"alert":{"identifier":"id-3","status":"Actual","msgType":"Alert","info":[
        {"language":"fi-FI","event":"Huomautus veneilijöille","onset":"2020-01-01T00:00:00+03:00",
         "expires":"2099-01-01T00:00:00+03:00","senderName":"Ilmatieteen laitos",
         "web":"https://www.ilmatieteenlaitos.fi/varoitukset","description":"Länsituulta 11 m/s.",
         "parameter":[{"valueName":"awareness_level","value":"2; yellow; Moderate"},
                      {"valueName":"awareness_type","value":"1; wind"}],
         "area":[{"areaDesc":"Suomenlahden itäosa"}]},
        {"language":"sv-SE","event":"Meddelande för båtförare","senderName":"Meteorologiska institutet",
         "web":"https://sv.ilmatieteenlaitos.fi/varningar","description":"",
         "area":[{"areaDesc":"Suomenlahden itäosa"}]},
        {"language":"en-GB","event":"Strong wind advisory","senderName":"Finnish Meteorological Institute",
         "web":"https://en.ilmatieteenlaitos.fi/warnings","description":"West wind 11 m/s.",
         "area":[{"areaDesc":"Suomenlahden itäosa"}]}
      ]}}
    ]}
    """.trimIndent()

    @Test fun parsesNewFieldsAndDropsCancel() {
        val list = WarningsClient().parse(json, "fi")
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

    @Test fun swedishBlockShownButMarineAndAreaFromFinnish() {
        val w = WarningsClient().parse(multiJson, "sv").single()
        assertEquals("Meddelande för båtförare", w.event)
        assertEquals("Meteorologiska institutet", w.senderName)
        assertEquals("https://sv.ilmatieteenlaitos.fi/varningar", w.web)
        // tyhjä sv-kuvaus → suomenkielinen, ei tyhjää korttia
        assertEquals("Länsituulta 11 m/s.", w.description)
        // merivaroitus tunnistetaan suomenkielisestä lohkosta kielestä riippumatta
        assertTrue(w.marine)
        assertEquals("Suomenlahden itäosa", w.areaDesc)
        assertEquals(WeatherWarning.Level.YELLOW, w.level)
        assertEquals("id-3", w.identifier)
    }

    @Test fun englishBlockAndFallbackWhenLanguageMissing() {
        val en = WarningsClient().parse(multiJson, "en").single()
        assertEquals("Strong wind advisory", en.event)
        assertEquals("West wind 11 m/s.", en.description)
        // fi-only -syöte englanniksi pyydettynä → suomenkielinen teksti
        val fallback = WarningsClient().parse(json, "en").single()
        assertEquals("Maastopalovaroitus", fallback.event)
        assertEquals("Maastopalovaroitus on voimassa.", fallback.description)
    }
}
