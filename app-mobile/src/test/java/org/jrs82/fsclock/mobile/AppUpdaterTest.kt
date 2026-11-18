package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun suffixlessReleaseIsNewerThanLastSuffixedOne() {
        // 2.26.0 on ensimmäinen julkaisu ilman päätettä: 2.25.0-mobile-asennuksen pitää tunnistaa se päivitykseksi.
        assertTrue(AppUpdater.isNewer("2.25.0-mobile", "2.26.0"))
        assertTrue(AppUpdater.isNewer("2.25.0-mobile", "v2.26.0"))
        assertFalse(AppUpdater.isNewer("2.26.0", "v2.25.0-mobile"))
    }

    @Test fun downloadIntegrityCheck() {
        assertEquals(AppUpdater.DownloadCheck.OK, AppUpdater.checkDownload(100, 100))
        // Palvelin ei ilmoittanut pituutta → kelvollista latausta ei hylätä.
        assertEquals(AppUpdater.DownloadCheck.OK, AppUpdater.checkDownload(100, -1))
        assertEquals(AppUpdater.DownloadCheck.INCOMPLETE, AppUpdater.checkDownload(50, 100))
        assertEquals(AppUpdater.DownloadCheck.EMPTY, AppUpdater.checkDownload(0, -1))
    }

    // --- Mitä uutta -teksti: GitHubin markdown näytetään tavallisena tekstinä ---

    @Test fun plainNotesStripsBoldHeadingsAndCode() {
        val md = "Ominaisuusversio (2.27.1 → 2.28.0).\n\n**Pörssisähkö**\n- Tuntihinta on varttien keskiarvo.\n\n" +
            "## Uutiset\n- Otsikot alkukielellä.\n\nAPK on ARM-laitteille (`arm64-v8a` / `armeabi-v7a`)."
        val expected = "Ominaisuusversio (2.27.1 → 2.28.0).\n\nPörssisähkö\n- Tuntihinta on varttien keskiarvo.\n\n" +
            "Uutiset\n- Otsikot alkukielellä.\n\nAPK on ARM-laitteille (arm64-v8a / armeabi-v7a)."
        assertEquals(expected, AppUpdater.plainNotes(md))
    }

    @Test fun plainNotesKeepsAlreadyPlainTextUnchanged() {
        val plain = "Ominaisuusversio (2.28.0 → 2.29.0).\n\n" +
            "- Sähkösivun hinnat näkyvät nyt selkeänä hintalistana: yksi vartti tai tunti per rivi\n" +
            "- Rivin väri kertoo, onko hinta päivän halvimmasta vai kalleimmasta päästä\n\n" +
            "Vaatii Android 11 tai uudemman."
        assertEquals(plain, AppUpdater.plainNotes(plain))
    }

    @Test fun plainNotesUnwrapsLinksEmphasisAndQuotes() {
        assertEquals("Katso GitHub.", AppUpdater.plainNotes("Katso [GitHub](https://github.com/jrs8205/Arkikeskus)."))
        assertEquals("tärkeä huomio", AppUpdater.plainNotes("*tärkeä* __huomio__"))
        assertEquals("lainaus", AppUpdater.plainNotes("> lainaus"))
        assertEquals("Ennen\n\nJälkeen", AppUpdater.plainNotes("Ennen\n<!-- piilotettu -->\nJälkeen"))
        assertEquals("Kuva:", AppUpdater.plainNotes("Kuva:![kuvakaappaus](https://x/y.png)"))
    }

    @Test fun plainNotesNormalisesBulletsRulesAndFences() {
        assertEquals("- yksi\n  - kaksi", AppUpdater.plainNotes("* yksi\n  + kaksi"))
        assertEquals("Ylä\n\nAla", AppUpdater.plainNotes("Ylä\n\n---\n\nAla"))
        assertEquals("koodi", AppUpdater.plainNotes("```\nkoodi\n```"))
    }

    @Test fun plainNotesLeavesIdentifiersAndArithmeticAlone() {
        assertEquals("app_mobile_x ja 2 * 3 = 6", AppUpdater.plainNotes("app_mobile_x ja 2 * 3 = 6"))
        assertEquals("tähti * jää", AppUpdater.plainNotes("tähti \\* jää"))
    }

    // --- Päivitystarkistuksen tulos talteen: sama polku automaattiselle ja Asetusten tarkistukselle ---

    private fun release(apk: String? = "https://x/Arkikeskus-2.30.0.apk", notes: String = "uusi") =
        AppUpdater.ReleaseInfo("v2.30.0", "2.30.0", apk, "https://x/releases/v2.30.0", notes)

    @Test fun saveNewerReleaseStoresItAndStampsCheckTime() {
        val prefs = FakeSharedPreferences()
        AppUpdater.saveCheckResult(prefs, release(), newer = true, nowMs = 1234L)
        assertEquals("2.30.0", prefs.getString(AppUpdater.PREF_AVAILABLE_VERSION, null))
        assertEquals("https://x/Arkikeskus-2.30.0.apk", prefs.getString(AppUpdater.PREF_AVAILABLE_URL, null))
        assertEquals("https://x/releases/v2.30.0", prefs.getString(AppUpdater.PREF_AVAILABLE_HTML, null))
        assertEquals("uusi", prefs.getString(AppUpdater.PREF_AVAILABLE_NOTES, null))
        assertEquals(1234L, prefs.getLong(AppUpdater.PREF_LAST_AUTO_CHECK, 0L))
    }

    @Test fun saveNewerReleaseReplacesOutdatedNotes() {
        val prefs = FakeSharedPreferences()
        AppUpdater.saveCheckResult(prefs, release(notes = "**vanha**"), newer = true, nowMs = 1L)
        AppUpdater.saveCheckResult(prefs, release(notes = "korjattu"), newer = true, nowMs = 2L)
        assertEquals("korjattu", prefs.getString(AppUpdater.PREF_AVAILABLE_NOTES, null))
    }

    @Test fun saveNewerReleaseWithoutApkDropsStaleApkUrl() {
        val prefs = FakeSharedPreferences()
        AppUpdater.saveCheckResult(prefs, release(), newer = true, nowMs = 1L)
        AppUpdater.saveCheckResult(prefs, release(apk = null), newer = true, nowMs = 2L)
        assertNull(prefs.getString(AppUpdater.PREF_AVAILABLE_URL, null))
    }

    @Test fun saveUpToDateClearsStoredReleaseButStampsCheckTime() {
        val prefs = FakeSharedPreferences()
        AppUpdater.saveCheckResult(prefs, release(), newer = true, nowMs = 1L)
        AppUpdater.saveCheckResult(prefs, release(), newer = false, nowMs = 99L)
        assertNull(prefs.getString(AppUpdater.PREF_AVAILABLE_VERSION, null))
        assertNull(prefs.getString(AppUpdater.PREF_AVAILABLE_URL, null))
        assertNull(prefs.getString(AppUpdater.PREF_AVAILABLE_HTML, null))
        assertNull(prefs.getString(AppUpdater.PREF_AVAILABLE_NOTES, null))
        assertEquals(99L, prefs.getLong(AppUpdater.PREF_LAST_AUTO_CHECK, 0L))
    }

    @Test fun loadSavedReturnsStoredNewerRelease() {
        val prefs = FakeSharedPreferences()
        AppUpdater.saveCheckResult(prefs, release(), newer = true, nowMs = 1L)
        assertEquals(release(), AppUpdater.loadSaved(prefs, "2.29.0"))
    }

    @Test fun loadSavedHidesMissingDismissedOrInstalledVersion() {
        val prefs = FakeSharedPreferences()
        assertNull(AppUpdater.loadSaved(prefs, "2.29.0"))
        AppUpdater.saveCheckResult(prefs, release(), newer = true, nowMs = 1L)
        assertNull(AppUpdater.loadSaved(prefs, "2.30.0")) // päivitys jo asennettu
        prefs.edit().putString(AppUpdater.PREF_DISMISSED_VERSION, "2.30.0").apply()
        assertNull(AppUpdater.loadSaved(prefs, "2.29.0"))
    }

    @Test fun bannerFollowsEveryStoredUpdateKey() {
        // Etusivun banneri luetaan uudelleen näiden muuttuessa (Asetusten tarkistus, taustailmoitus, hylkäys).
        for (k in listOf(
            AppUpdater.PREF_AVAILABLE_VERSION, AppUpdater.PREF_AVAILABLE_URL, AppUpdater.PREF_AVAILABLE_HTML,
            AppUpdater.PREF_AVAILABLE_NOTES, AppUpdater.PREF_DISMISSED_VERSION,
        )) assertTrue(k, AppUpdater.isUpdatePrefKey(k))
        // Pelkkä tarkistusaika ei muuta banneria; null = clear() (Android 11+).
        assertFalse(AppUpdater.isUpdatePrefKey(AppUpdater.PREF_LAST_AUTO_CHECK))
        assertFalse(AppUpdater.isUpdatePrefKey(null))
    }

    @Test fun loadSavedTreatsEmptyApkUrlAsMissing() {
        // Taustailmoitus tallensi ennen 2.29.1:tä puuttuvan APK-osoitteen tyhjänä merkkijonona.
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(AppUpdater.PREF_AVAILABLE_VERSION, "2.30.0").putString(AppUpdater.PREF_AVAILABLE_URL, "").apply()
        val info = AppUpdater.loadSaved(prefs, "2.29.0")!!
        assertNull(info.apkUrl)
        assertEquals(AppUpdater.REPO_URL, info.htmlUrl)
        assertEquals("", info.notes)
    }
}
