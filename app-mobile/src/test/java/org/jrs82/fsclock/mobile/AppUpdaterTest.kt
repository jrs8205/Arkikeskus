package org.jrs82.fsclock.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4b-ilmoitus #7 + päivitysbanneri nojaavat [AppUpdater.isNewer]-versiovertailuun. */
class AppUpdaterTest {

    @Test fun newerVersionDetected() {
        assertTrue(AppUpdater.isNewer("2.11.0-mobile", "2.12.0-mobile"))
        assertTrue(AppUpdater.isNewer("2.11.0", "2.11.1"))
        assertTrue(AppUpdater.isNewer("2.9.0", "2.11.0"))
    }

    @Test fun sameOrOlderNotNewer() {
        assertFalse(AppUpdater.isNewer("2.11.0-mobile", "2.11.0-mobile"))
        assertFalse(AppUpdater.isNewer("2.11.0", "2.10.0")) // 2.10.0 EI uudempi kuin 2.11.0
        assertFalse(AppUpdater.isNewer("2.11.0", "2.11.0"))
    }

    @Test fun ignoresVPrefixAndSuffix() {
        assertTrue(AppUpdater.isNewer("v2.11.0-mobile", "v2.11.1-beta"))
        assertFalse(AppUpdater.isNewer("2.11.0", "v2.11.0"))
    }
}
