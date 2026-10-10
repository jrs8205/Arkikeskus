package org.jrs82.fsclock.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileHolidayProviderTest {

    @Test fun localizedNames_keepsOnlyNonEmptySwedishAndEnglish() {
        val names = JSONObject()
            .put("fi", "Vappu")
            .put("sv", "Första maj")
            .put("en", "  ")
            .put("ru", "Первое мая")
        assertEquals(mapOf("sv" to "Första maj"), MobileHolidayProvider.localizedNames(names))
    }

    @Test fun localizedNames_trimsValues() {
        val names = JSONObject().put("fi", "Joulupäivä").put("sv", " Juldagen ").put("en", "Christmas Day")
        assertEquals(
            mapOf("sv" to "Juldagen", "en" to "Christmas Day"),
            MobileHolidayProvider.localizedNames(names),
        )
    }
}
