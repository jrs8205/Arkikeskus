package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Lenkin jakotiedostojen apurit: kirjoitus välimuistiin (cacheDir/share/, FileProvider-polku
 * "share" file_paths.xml:ssä) ja Androidin jakovalikon avaus ACTION_SENDillä.
 */
internal object ShareFiles {

    /** Kirjoittaa jakotiedoston välimuistiin ja palauttaa FileProvider-URIn.
     *  Edelliset jaot siivotaan samalla (vain uusin tiedosto pidetään). */
    fun writeShareFile(context: Context, fileName: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "share")
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()?.forEach { if (it.name != fileName) it.delete() }
        val f = File(dir, fileName)
        f.writeBytes(bytes)
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
    }

    fun launchShare(context: Context, uri: Uri, mime: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
    }

    /** Poistaa tiedostonimestä kielletyt merkit ("Koiralenkki / ilta" → "Koiralenkki _ ilta"). */
    fun sanitizeFileName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifEmpty { "Lenkki" }
}
