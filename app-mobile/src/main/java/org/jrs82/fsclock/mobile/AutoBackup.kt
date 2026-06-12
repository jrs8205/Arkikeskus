package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
 * kerran vuorokaudessa — Drive synkkaa sen pilveen itse.
 *
 * Tila on OMASSA prefs-tiedostossaan (auto_backup_prefs), joka on suljettu pois Androidin
 * Auto Backupista (backup_rules.xml / data_extraction_rules.xml): SAF-URI ja WorkManager-ajastus
 * ovat laitekohtaisia — toiselle laitteelle palautunut URI ilman persistoitua lupaa näyttäisi
 * muuten valheellisesti "päällä"-tilaa. Lisäksi [isEnabled] validoi luvan joka luennalla ja
 * siivoaa vanhentuneen tilan (kattaa myös D2D-siirron).
 */
internal object AutoBackup {

    private const val PREFS_NAME = "auto_backup_prefs"
    const val KEY_URI = "auto_backup_uri"
    const val KEY_LAST_MS = "auto_backup_last_ms"
    const val KEY_LAST_BYTES = "auto_backup_last_bytes"
    const val KEY_ERROR = "auto_backup_error"
    private const val WORK_NAME = "auto_backup"

    /** Automaattibackupin oma prefs-tiedosto (+ kertamigraatio vanhoista default-prefs-avaimista,
     *  jotka olivat 2.7.0-kehitysversioissa). */
    fun prefs(context: Context): SharedPreferences {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val old = PreferenceManager.getDefaultSharedPreferences(context)
        if (!p.contains(KEY_URI) && old.contains(KEY_URI)) {
            p.edit()
                .putString(KEY_URI, old.getString(KEY_URI, null))
                .putLong(KEY_LAST_MS, old.getLong(KEY_LAST_MS, 0L))
                .putInt(KEY_LAST_BYTES, old.getInt(KEY_LAST_BYTES, 0))
                .apply()
        }
        if (old.contains(KEY_URI) || old.contains(KEY_LAST_MS)) {
            old.edit().remove(KEY_URI).remove(KEY_LAST_MS).remove(KEY_LAST_BYTES)
                .remove(KEY_ERROR).apply()
        }
        return p
    }

    /** Onko persistoitu kirjoituslupa URIin yhä voimassa (käyttäjä voi perua sen, tiedosto voi
     *  kadota, tai URI voi tulla toiselta laitteelta Auto Backup/D2D-palautuksessa). */
    private fun hasWritePermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }

    /** Tila + itsesiivous: jos URI on tallessa mutta lupa puuttuu, kytketään pois ja
     *  raportoidaan virhe — kytkin ei saa näyttää päällä-tilaa joka ei oikeasti kirjoita. */
    fun isEnabled(context: Context): Boolean {
        val p = prefs(context)
        val uriStr = p.getString(KEY_URI, null) ?: return false
        if (!hasWritePermission(context, Uri.parse(uriStr))) {
            android.util.Log.w("AutoBackup", "Persistoitu lupa puuttuu ($uriStr) — kytketään pois")
            disable(context)
            p.edit().putString(KEY_ERROR, "lupa puuttuu").apply()
            return false
        }
        return true
    }

    /** Ottaa pysyvän luvan valittuun tiedostoon, ajastaa päivittäisen työn ja ajaa heti kerran.
     *  False jos provider ei myönnä persistoitavaa lupaa (esim. eräät pilvitarjoajat) —
     *  UI EI saa jäädä päällä-tilaan silloin. */
    fun enable(context: Context, uri: Uri): Boolean {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            android.util.Log.w("AutoBackup", "takePersistableUriPermission epäonnistui", e)
            return false
        }
        if (!hasWritePermission(context, uri)) {
            android.util.Log.w("AutoBackup", "Kirjoituslupaa ei myönnetty: $uri")
            return false
        }
        prefs(context).edit()
            .putString(KEY_URI, uri.toString())
            .remove(KEY_ERROR)
            .apply()
        val periodic = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
        return true
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        prefs(context).edit()
            .remove(KEY_URI)
            .remove(KEY_ERROR)
            .apply()
    }

    /** Kirjoittaa varmuuskopion persistoituun URIin. True jos onnistui; virhe talteen prefseihin
     *  (näytetään asetuksissa — esim. kohdetiedosto poistettu Drivestä → valitse uudelleen). */
    fun runBackup(context: Context): Boolean {
        val p = prefs(context)
        val uriStr = p.getString(KEY_URI, null) ?: return false
        return try {
            val uri = Uri.parse(uriStr)
            // "wt" = truncate: vanha sisältö korvataan kokonaan (ilman tätä lyhyempi
            // kirjoitus jättäisi vanhaa JSONia hännille → rikkinäinen tiedosto).
            val res = context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                BackupManager.export(context, os)
            } ?: throw FileNotFoundException("openOutputStream null")
            p.edit()
                .putLong(KEY_LAST_MS, System.currentTimeMillis())
                .putInt(KEY_LAST_BYTES, res.bytes)
                .remove(KEY_ERROR)
                .apply()
            true
        } catch (e: Exception) {
            android.util.Log.w("AutoBackup", "Varmuuskopion kirjoitus epäonnistui", e)
            p.edit().putString(KEY_ERROR, e.javaClass.simpleName).apply()
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
