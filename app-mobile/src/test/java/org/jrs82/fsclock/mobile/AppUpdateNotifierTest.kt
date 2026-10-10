package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Päivitysilmoituksen teksti: julkaisukuvauksen ensimmäinen rivi tai null → käännetty yleisteksti. */
class AppUpdateNotifierTest {

    @Test fun previewIsFirstNonBlankLineTrimmed() {
        assertEquals("## 2.27.0", AppUpdateNotifier.notesPreview("\n   \n  ## 2.27.0  \nsecond line"))
    }

    @Test fun blankNotesFallBackToGenericText() {
        assertNull(AppUpdateNotifier.notesPreview(""))
        assertNull(AppUpdateNotifier.notesPreview("  \n\t\n"))
    }

    @Test fun previewIsCappedAt160Chars() {
        assertEquals(160, AppUpdateNotifier.notesPreview("x".repeat(300))!!.length)
    }
}
