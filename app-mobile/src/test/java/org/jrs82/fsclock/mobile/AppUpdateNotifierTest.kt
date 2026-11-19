package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Päivitysilmoituksen teksti: julkaisukuvauksen ensimmäinen rivi tai null → käännetty yleisteksti. */
class AppUpdateNotifierTest {

    @Test fun previewIsFirstNonBlankLineTrimmed() {
        assertEquals("2.27.0", AppUpdateNotifier.notesPreview("\n   \n  ## 2.27.0  \nsecond line", "fi"))
    }

    @Test fun previewHasNoMarkdown() {
        assertEquals("Pörssisähkö", AppUpdateNotifier.notesPreview("**Pörssisähkö**\n- uusi asetus", "fi"))
    }

    @Test fun blankNotesFallBackToGenericText() {
        assertNull(AppUpdateNotifier.notesPreview("", "fi"))
        assertNull(AppUpdateNotifier.notesPreview("  \n\t\n", "sv"))
    }

    @Test fun previewIsCappedAt160Chars() {
        assertEquals(160, AppUpdateNotifier.notesPreview("x".repeat(300), "en")!!.length)
    }

    @Test fun previewIsTheFirstLineOfTheAppLanguageSection() {
        val notes = "Suomeksi\n\nOminaisuusversio.\n- yksi\n\nPå svenska\n\nFunktionsversion.\n- ett\n\nIn English\n\nFeature release.\n- one"
        assertEquals("Ominaisuusversio.", AppUpdateNotifier.notesPreview(notes, "fi"))
        assertEquals("Funktionsversion.", AppUpdateNotifier.notesPreview(notes, "sv"))
        assertEquals("Feature release.", AppUpdateNotifier.notesPreview(notes, "en"))
    }

    // --- Yhteinen johdanto ennen kieliosioita (katselmointi 2.30.0, kierros 5) ---

    private val withIntro = "Arkikeskus 2.31.0\n\nSuomeksi\n\nOminaisuusversio.\n- yksi\n\nIn English\n\nFeature release.\n- one"

    @Test fun previewSkipsTheSharedIntro_whenTheOwnSectionExists() {
        // Ikkuna näyttää johdannon ja oman osion; ilmoitukseen poimitaan oman osion ensimmäinen rivi.
        assertEquals("Ominaisuusversio.", AppUpdateNotifier.notesPreview(withIntro, "fi"))
        assertEquals("Feature release.", AppUpdateNotifier.notesPreview(withIntro, "en"))
        assertEquals("Arkikeskus 2.31.0\n\nFeature release.\n- one", AppUpdater.localizedNotes(withIntro, "en"))
    }

    @Test fun previewUsesTheSharedIntro_whenTheOwnSectionIsMissing() {
        assertEquals("Arkikeskus 2.31.0", AppUpdateNotifier.notesPreview(withIntro, "sv"))
    }

    @Test fun previewIsNeverASectionHeading_whenOnlyOtherLanguagesExist() {
        // Ei omaa osiota eikä johdantoa: yleinen käännetty teksti, ei toisen kielen otsikkoriviä "Suomeksi".
        val fiOnly = "Suomeksi\n\nOminaisuusversio.\n- yksi"
        assertNull(AppUpdateNotifier.notesPreview(fiOnly, "en"))
        assertNull(AppUpdateNotifier.notesPreview(fiOnly, "sv"))
        assertEquals("Ominaisuusversio.", AppUpdateNotifier.notesPreview(fiOnly, "fi"))
        // Tyhjä oma osio on sama kuin puuttuva.
        assertNull(AppUpdateNotifier.notesPreview("$fiOnly\n\nIn English\n", "en"))
    }
}
