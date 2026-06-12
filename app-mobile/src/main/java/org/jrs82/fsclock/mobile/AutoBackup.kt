package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/**
 * Automaattinen varmuuskopiointi "WhatsApp-tyyliin" ilman Google-rajapintoja: käyttäjä valitsee
 * kohdetiedoston KERRAN (SAF, esim. Google Drive) ja sovellus ottaa siihen pysyvän
 * kirjoitusluvan; WorkManager kirjoittaa BackupManagerin JSON-varmuuskopion tiedoston päälle
 * kerran vuorokaudessa — Drive synkkaa sen pilveen itse. Tila (viimeisin ajo / virhe)
 * tallennetaan prefseihin ja näytetään asetuksissa.
 */
internal object AutoBackup {

    const val KEY_URI = "auto_backup_uri"
    const val KEY_LAST_MS = "auto_backup_last_ms"
    const val KEY_ERROR = "auto_backup_error"
    private const val WORK_NAME = "auto_backup"

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_URI, null) != null

    /** Ottaa pysyvän luvan valittuun tiedostoon, ajastaa päivittäisen työn ja ajaa heti kerran
     *  (jotta käyttäjä näkee toimivuuden välittömästi). */
    fun enable(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_URI, uri.toString())
            .remove(KEY_ERROR)
            .apply()
        val periodic = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(KEY_URI)
            .remove(KEY_ERROR)
            .apply()
    }

    /** Kirjoittaa varmuuskopion persistoituun URIin. True jos onnistui; virhe talteen prefseihin
     *  (näytetään asetuksissa — esim. kohdetiedosto poistettu Drivestä → valitse uudelleen). */
    fun runBackup(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val uriStr = prefs.getString(KEY_URI, null) ?: return false
        return try {
            val uri = Uri.parse(uriStr)
            // "wt" = truncate: vanha sisältö korvataan kokonaan (ilman tätä lyhyempi
            // kirjoitus jättäisi vanhaa JSONia hännille → rikkinäinen tiedosto).
            context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                BackupManager.export(context, os)
            } ?: throw FileNotFoundException("openOutputStream null")
            prefs.edit()
                .putLong(KEY_LAST_MS, System.currentTimeMillis())
                .remove(KEY_ERROR)
                .apply()
            true
        } catch (e: Exception) {
            prefs.edit().putString(KEY_ERROR, e.javaClass.simpleName).apply()
            false
        }
    }
}

/** Päivittäinen varmuuskopiotyö. Palauttaa aina success — virhetila kirjataan prefseihin ja
 *  periodinen työ yrittää joka tapauksessa uudelleen seuraavalla kierroksella. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        AutoBackup.runBackup(applicationContext)
        return Result.success()
    }
}
