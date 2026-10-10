package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Asetussivujen otsikot ovat resursseja; keskussivu käyttää yhteistä "Asetukset"-tekstiä. */
class SettingsPageTest {

    @Test fun hubUsesCommonSettingsTitle() {
        assertEquals(R.string.common_settings, SettingsPage.HUB.titleRes)
    }

    @Test fun everyPageHasItsOwnTitle() {
        val ids = SettingsPage.entries.map { it.titleRes }
        assertEquals(ids.size, ids.toSet().size)
    }
}
