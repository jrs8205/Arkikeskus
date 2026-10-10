package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleTest {
    @Test fun finnishStaysFinnish() = assertEquals("fi", AppLocale.resolve(listOf("fi-FI")).language)
    @Test fun swedishFinlandIsSwedish() = assertEquals("sv", AppLocale.resolve(listOf("sv-FI")).language)
    @Test fun swedishSwedenIsSwedish() = assertEquals("sv", AppLocale.resolve(listOf("sv-SE")).language)
    @Test fun germanFallsBackToEnglish() = assertEquals("en", AppLocale.resolve(listOf("de-DE")).language)
    @Test fun listPicksFirstSupported() =
        assertEquals("sv", AppLocale.resolve(listOf("de-DE", "sv-FI", "fi-FI")).language)
    @Test fun emptyListIsEnglish() = assertEquals("en", AppLocale.resolve(emptyList()).language)
    @Test fun englishUsIsEnglish() = assertEquals("en", AppLocale.resolve(listOf("en-US")).language)

    @Test fun fmiKeyFollowsLanguage() {
        assertEquals("info_sv", AppLocale.fmiInfoKey(AppLocale.resolve(listOf("sv"))))
        assertEquals("info_en", AppLocale.fmiInfoKey(AppLocale.resolve(listOf("en-GB"))))
        assertEquals("info_fi", AppLocale.fmiInfoKey(AppLocale.resolve(listOf("fi"))))
    }

    @Test fun digitransitHasNoEnglish() {
        assertEquals("fi", AppLocale.digitransitLanguage(AppLocale.resolve(listOf("en"))))
        assertEquals("sv", AppLocale.digitransitLanguage(AppLocale.resolve(listOf("sv"))))
        assertEquals("fi", AppLocale.digitransitLanguage(AppLocale.resolve(listOf("fi"))))
    }

    @Test fun almanakkaTypeSwedishOnlyForSwedish() {
        assertEquals("ruotsi", AppLocale.almanakkaType(AppLocale.resolve(listOf("sv"))))
        assertEquals("suomi", AppLocale.almanakkaType(AppLocale.resolve(listOf("en"))))
        assertEquals("suomi", AppLocale.almanakkaType(AppLocale.resolve(listOf("fi"))))
    }
}
