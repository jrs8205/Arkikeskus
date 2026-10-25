package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Ensikäynnistyksen sijaintilupa ja asetusten kytkin. Tausta 23.10.2026: Pixeliltä uudelle puhelimelle
 *  palautetut asetukset toivat mukanaan tiedon "lupa jo kysytty", joten lupaa ei kysytty koskaan ja sää
 *  näytti varmuuskopion kotipaikkaa. */
class LocationPermissionPolicyTest {

    @Test fun asksOnFreshDeviceWithAutoLocationOn() {
        assertTrue(LocationPermissionPolicy.shouldAskInitially(askedOnThisDevice = false, autoLocationOn = true, granted = false))
    }

    @Test fun doesNotAskTwiceOnSameDevice() {
        assertFalse(LocationPermissionPolicy.shouldAskInitially(askedOnThisDevice = true, autoLocationOn = true, granted = false))
    }

    @Test fun doesNotAskWhenUserTurnedAutoLocationOff() {
        assertFalse(LocationPermissionPolicy.shouldAskInitially(askedOnThisDevice = false, autoLocationOn = false, granted = false))
    }

    @Test fun doesNotAskWhenAlreadyGranted() {
        assertFalse(LocationPermissionPolicy.shouldAskInitially(askedOnThisDevice = false, autoLocationOn = true, granted = true))
    }

    @Test fun switchOnOnlyWhenPermissionExists() {
        assertEquals(
            LocationPermissionPolicy.SwitchState(checked = true, permissionMissing = false),
            LocationPermissionPolicy.switchState(autoLocationOn = true, granted = true),
        )
    }

    @Test fun switchShowsMissingPermissionInsteadOfFakeOn() {
        assertEquals(
            LocationPermissionPolicy.SwitchState(checked = false, permissionMissing = true),
            LocationPermissionPolicy.switchState(autoLocationOn = true, granted = false),
        )
    }

    @Test fun switchOffWithoutWarningWhenUserTurnedItOff() {
        assertEquals(
            LocationPermissionPolicy.SwitchState(checked = false, permissionMissing = false),
            LocationPermissionPolicy.switchState(autoLocationOn = false, granted = false),
        )
    }

    @Test fun deviceLocalPrefsAreExcludedFromEveryBackupRoute() {
        val file = DeviceLocalPrefs.FILE + ".xml"
        assertEquals(setOf("full-backup-content"), excludingSections(File("src/main/res/xml/backup_rules.xml"), file))
        assertEquals(
            setOf("cloud-backup", "device-transfer"),
            excludingSections(File("src/main/res/xml/data_extraction_rules.xml"), file),
        )
    }

    /** Osiot, joissa on `<exclude domain="sharedpref" path="[path]">`. */
    private fun excludingSections(xml: File, path: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val excludes = doc.getElementsByTagName("exclude")
        val sections = HashSet<String>()
        for (i in 0 until excludes.length) {
            val e = excludes.item(i) as Element
            if (e.getAttribute("domain") == "sharedpref" && e.getAttribute("path") == path) {
                sections.add((e.parentNode as Element).tagName)
            }
        }
        return sections
    }
}
