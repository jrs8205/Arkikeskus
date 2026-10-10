package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Varmuuskopioinnin näkyvät tekstit valitaan rakenteena (resurssi-id + arvo), jotta kieli ja
 * yksikkö (fi kt/Mt, sv/en kB/MB) ratkeavat vasta resursseista eikä logiikkaan jää suomea.
 */
class BackupI18nTest {

    @Test fun smallSizesUseBytes() {
        assertEquals(BackupSize(R.string.backup_size_bytes, 512L), backupSize(512))
    }

    @Test fun kilobytesAndMegabytes() {
        val kb = backupSize(11_878)
        assertEquals(R.string.backup_size_kb, kb.res)
        assertEquals(11.6, kb.value.toDouble(), 0.01)
        val mb = backupSize(3L * 1024 * 1024 / 2)
        assertEquals(R.string.backup_size_mb, mb.res)
        assertEquals(1.5, mb.value.toDouble(), 1e-9)
    }

    @Test fun unitBoundaries() {
        assertEquals(R.string.backup_size_bytes, backupSize(1023).res)
        assertEquals(R.string.backup_size_kb, backupSize(1024).res)
        assertEquals(R.string.backup_size_kb, backupSize(1024L * 1024 - 1).res)
        assertEquals(R.string.backup_size_mb, backupSize(1024L * 1024).res)
    }

    @Test fun validBackupFilePassesValidation() {
        assertNull(BackupManager.validationError("arkikeskus-backup", 1))
    }

    @Test fun unknownFormatAndNewerVersionAreRejected() {
        assertEquals(R.string.backup_unknown_format, BackupManager.validationError("something-else", 1))
        assertEquals(R.string.backup_newer_version, BackupManager.validationError("arkikeskus-backup", 2))
    }

    @Test fun driveFrequencyLabels() {
        assertEquals(R.string.backup_freq_daily, driveFrequencyLabelRes(DriveBackup.FREQ_DAILY))
        assertEquals(R.string.backup_freq_weekly, driveFrequencyLabelRes(DriveBackup.FREQ_WEEKLY))
        assertEquals(R.string.backup_freq_monthly, driveFrequencyLabelRes(DriveBackup.FREQ_MONTHLY))
        assertEquals(R.string.backup_freq_manual, driveFrequencyLabelRes(DriveBackup.FREQ_MANUAL))
        assertEquals(R.string.backup_freq_off, driveFrequencyLabelRes(DriveBackup.FREQ_OFF))
        // Tuntematon (esim. tulevan version) arvo näytetään pois käytöstä -tilana.
        assertEquals(R.string.backup_freq_off, driveFrequencyLabelRes("hourly"))
    }
}
