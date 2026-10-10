package org.jrs82.fsclock

import org.jrs82.fsclock.mobile.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nimipäivävastauksesta poimitaan vain UI-kielen kalenteri (suomi / suomenruotsalainen). */
class AlmanakkaClientParseTest {
    // Lyhennetty näyte 9.10. vastauksesta: mukana muitakin kalentereita, jotka pitää ohittaa.
    private val sample = """
        {"success": true, "data": {"hits": [
          {"document": {"type": "ortod", "name": "Aapo"}},
          {"document": {"type": "suomi", "name": "Ilona"}},
          {"document": {"type": "hevonen", "name": "Ilo"}},
          {"document": {"type": "ruotsi", "name": "Marina"}},
          {"document": {"type": "saame", "name": "Máren"}},
          {"document": {"type": "suomi", "name": " Ilo "}},
          {"document": {"type": "ruotsi", "name": ""}}
        ]}}
    """.trimIndent()

    @Test fun finnishCalendar_onlySuomiNames() {
        assertEquals("Ilona, Ilo", AlmanakkaClient.parseNames(sample, AlmanakkaClient.TYPE_FINNISH))
    }

    @Test fun swedishCalendar_onlyRuotsiNames() {
        assertEquals("Marina", AlmanakkaClient.parseNames(sample, AlmanakkaClient.TYPE_SWEDISH))
    }

    @Test fun failedResponse_isEmpty() {
        assertEquals("", AlmanakkaClient.parseNames("""{"success": false}""", AlmanakkaClient.TYPE_FINNISH))
        assertEquals("", AlmanakkaClient.parseNames("""{"success": true, "data": {}}""", AlmanakkaClient.TYPE_FINNISH))
    }

    @Test fun appLocaleTypes_matchClientTypes() {
        assertEquals(AlmanakkaClient.TYPE_FINNISH, AppLocale.almanakkaType(AppLocale.FINNISH))
        assertEquals(AlmanakkaClient.TYPE_SWEDISH, AppLocale.almanakkaType(AppLocale.SWEDISH))
        assertEquals(AlmanakkaClient.TYPE_FINNISH, AppLocale.almanakkaType(AppLocale.ENGLISH))
    }
}
