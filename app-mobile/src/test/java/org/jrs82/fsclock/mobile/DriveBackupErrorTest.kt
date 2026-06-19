package org.jrs82.fsclock.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [isPermanentDriveError] ratkaisee jääkö [DriveBackupWorker] yrittämään loputtomasti
 * (retry) vai luovuttaako se siististi (failure). Pysyvä virhe → failure, ohimenevä → retry.
 */
class DriveBackupErrorTest {

    @Test fun httpAuthAndClientErrorsArePermanent() {
        // 401/403/4xx eivät korjaannu uudelleenyrityksellä → failure (ei ikuista silmukkaa).
        assertTrue(isPermanentDriveError(DriveBackup.DriveHttpException(401, "Unauthorized")))
        assertTrue(isPermanentDriveError(DriveBackup.DriveHttpException(403, "Forbidden")))
        assertTrue(isPermanentDriveError(DriveBackup.DriveHttpException(400, "Bad Request")))
        assertTrue(isPermanentDriveError(DriveBackup.DriveHttpException(404, "Not Found")))
    }

    @Test fun httpServerAndThrottleErrorsAreTransient() {
        // 5xx + 408/429 ovat ohimeneviä → retry myöhemmin.
        assertFalse(isPermanentDriveError(DriveBackup.DriveHttpException(500, "Server Error")))
        assertFalse(isPermanentDriveError(DriveBackup.DriveHttpException(503, "Unavailable")))
        assertFalse(isPermanentDriveError(DriveBackup.DriveHttpException(429, "Too Many Requests")))
        assertFalse(isPermanentDriveError(DriveBackup.DriveHttpException(408, "Timeout")))
    }

    @Test fun missingAccountIsPermanent() {
        // token() heittää tämän kun tiliä ei ole / Account-objekti puuttuu → ei ole järkeä yrittää.
        assertTrue(isPermanentDriveError(IllegalStateException("Ei Drive-tilia")))
    }

    @Test fun plainNetworkErrorIsTransient() {
        assertFalse(isPermanentDriveError(IOException("network down")))
        assertFalse(isPermanentDriveError(RuntimeException("unknown")))
    }
}
