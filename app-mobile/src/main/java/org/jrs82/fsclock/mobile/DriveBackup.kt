package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * "WhatsApp-tyylinen" Drive-varmuuskopio: kirjoittaa [BackupManager]in JSON-varmuuskopion suoraan
 * Google Driven piilotettuun **appDataFolder**-kansioon Drive REST API v3:lla — ei tiedostovalitsinta
 * (SAF/DocumentsUI), joten ei Driven providerin hitautta. Kansio ei näy käyttäjän Drive-listassa;
 * vain tämä sovellus pääsee siihen (`drive.appdata`-scope, ei-arkaluontoinen → ei verifiointia, skaalautuu).
 *
 * Tilin valinta hoidetaan GoogleSignInillä; OAuth-token haetaan laitteella [GoogleAuthUtil]illa (ei
 * raskasta google-api-services-drive-kirjastoa). Kaikki verkko-operaatiot ([backupNow]/[restore]) on
 * kutsuttava taustasäikeestä.
 *
 * Drive-tila ([prefs], frekvenssi + viimeisin kopio) on omassa prefs-tiedostossaan, joka suljetaan pois
 * Androidin Auto Backupista (laite-/tilikohtaista; ei saa palautua toiselle laitteelle).
 */
internal object DriveBackup {

    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val APP_FOLDER = "appDataFolder"
    private const val FILE_NAME = "arkikeskus-backup.json"

    const val PREFS = "drive_backup_prefs"
    const val KEY_FREQ = "drive_freq"
    const val KEY_LAST_MS = "drive_last_ms"
    const val KEY_LAST_BYTES = "drive_last_bytes"
    const val KEY_ERROR = "drive_error"

    const val FREQ_OFF = "off"
    const val FREQ_MANUAL = "manual"
    const val FREQ_DAILY = "daily"
    const val FREQ_WEEKLY = "weekly"
    const val FREQ_MONTHLY = "monthly"

    private val scopeObj = Scope(SCOPE)

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun frequency(context: Context): String =
        prefs(context).getString(KEY_FREQ, FREQ_MANUAL) ?: FREQ_MANUAL

    private const val WORK_NAME = "drive_backup"

    fun setFrequency(context: Context, freq: String) {
        prefs(context).edit().putString(KEY_FREQ, freq).apply()
        applySchedule(context)
    }

    /** Ajastaa (tai peruu) periodisen Drive-varmuuskopiotyön valitun frekvenssin mukaan. */
    fun applySchedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val f = frequency(context)
        if (!isSignedIn(context) || f == FREQ_OFF || f == FREQ_MANUAL) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val days = when (f) {
            FREQ_WEEKLY -> 7L
            FREQ_MONTHLY -> 30L
            else -> 1L
        }
        val req = PeriodicWorkRequestBuilder<DriveBackupWorker>(days, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(scopeObj)
            .build()

    fun signInClient(context: Context): GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions())

    /** Kirjautunut tili JOLLA on drive.appdata-oikeus, tai null. */
    fun account(context: Context): GoogleSignInAccount? {
        val acc = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return if (GoogleSignIn.hasPermissions(acc, scopeObj)) acc else null
    }

    fun accountEmail(context: Context): String? = account(context)?.email

    fun isSignedIn(context: Context): Boolean = account(context) != null

    data class BackupInfo(val timeMs: Long, val bytes: Long)

    /** Viimeisin tällä laitteella tehty kopio (paikallinen välimuisti — ei verkkokutsua). */
    fun cachedInfo(context: Context): BackupInfo? {
        val p = prefs(context)
        val ms = p.getLong(KEY_LAST_MS, 0L)
        return if (ms > 0L) BackupInfo(ms, p.getLong(KEY_LAST_BYTES, 0L)) else null
    }

    // ---------- OAuth-token ----------
    private fun token(context: Context): String {
        val acc = account(context) ?: throw IllegalStateException("Ei Drive-tiliä")
        val androidAcc = acc.account ?: throw IllegalStateException("Tilillä ei Account-objektia")
        return GoogleAuthUtil.getToken(context, androidAcc, "oauth2:$SCOPE")
    }

    private class DriveHttpException(val code: Int, msg: String) : IOException("Drive HTTP $code: $msg")

    /** Suorittaa Drive-operaation; 401:llä mitätöi vanhentuneen tokenin ja yrittää kerran uudelleen. */
    private fun <T> withToken(context: Context, block: (String) -> T): T {
        var tok = token(context)
        return try {
            block(tok)
        } catch (e: DriveHttpException) {
            if (e.code == 401) {
                GoogleAuthUtil.clearToken(context, tok)
                tok = token(context)
                block(tok)
            } else throw e
        }
    }

    // ---------- Julkiset operaatiot (kutsu IO-säikeestä) ----------

    /** Vie varmuuskopio Driven appDataFolderiin (luo uuden tai korvaa olemassa olevan). */
    fun backupNow(context: Context): BackupInfo {
        val bos = ByteArrayOutputStream()
        BackupManager.export(context, bos)
        val data = bos.toByteArray()
        withToken(context) { tok ->
            val id = findFileId(tok)
            if (id == null) createFile(tok, data) else updateFile(tok, id, data)
        }
        val info = BackupInfo(System.currentTimeMillis(), data.size.toLong())
        prefs(context).edit()
            .putLong(KEY_LAST_MS, info.timeMs)
            .putLong(KEY_LAST_BYTES, info.bytes)
            .remove(KEY_ERROR)
            .apply()
        return info
    }

    /** Lataa varmuuskopion Drivestä ja palauttaa sen ([BackupManager.restore] asettaa restart-lipun;
     *  kutsujan vastuulla käynnistää prosessi uudelleen). Heittää jos kopiota ei ole. */
    fun restore(context: Context): BackupManager.RestoreResult {
        val data = withToken(context) { tok ->
            val id = findFileId(tok)
                ?: throw IllegalStateException("Driveen ei ole tallennettu varmuuskopiota")
            downloadFile(tok, id)
        }
        return BackupManager.restore(context, ByteArrayInputStream(data))
    }

    fun recordError(context: Context, e: Throwable) {
        prefs(context).edit().putString(KEY_ERROR, e.javaClass.simpleName).apply()
    }

    // ---------- Drive REST v3 -apurit ----------

    private fun findFileId(token: String): String? {
        val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=" +
            URLEncoder.encode("files(id,name,modifiedTime,size)", "UTF-8")
        val resp = httpGet(token, url)
        val files = JSONObject(resp).optJSONArray("files") ?: return null
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            if (f.optString("name") == FILE_NAME) return f.optString("id").ifEmpty { null }
        }
        return null
    }

    private fun createFile(token: String, data: ByteArray) {
        val boundary = "arkikeskus_" + System.currentTimeMillis() + "_boundary"
        val meta = JSONObject()
            .put("name", FILE_NAME)
            .put("parents", JSONArray().put(APP_FOLDER))
            .toString()
        val conn = open("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", token)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        conn.outputStream.use { os ->
            os.write(
                ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                    "--$boundary\r\nContent-Type: application/json\r\n\r\n").toByteArray(Charsets.UTF_8))
            os.write(data)
            os.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }
        checkOk(conn)
    }

    private fun updateFile(token: String, id: String, data: ByteArray) {
        val conn = open("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media", token)
        // HttpURLConnection ei tue PATCH-metodia → POST + method-override (Google hyväksyy tämän).
        conn.requestMethod = "POST"
        conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(data) }
        checkOk(conn)
    }

    private fun downloadFile(token: String, id: String): ByteArray {
        val conn = open("https://www.googleapis.com/drive/v3/files/$id?alt=media", token)
        conn.requestMethod = "GET"
        return readBytes(conn)
    }

    private fun httpGet(token: String, url: String): String {
        val conn = open(url, token)
        conn.requestMethod = "GET"
        return String(readBytes(conn), Charsets.UTF_8)
    }

    private fun open(url: String, token: String): HttpsURLConnection {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        return conn
    }

    private fun checkOk(conn: HttpsURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            conn.disconnect()
            throw DriveHttpException(code, err.take(300))
        }
        conn.disconnect()
    }

    private fun readBytes(conn: HttpsURLConnection): ByteArray {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            conn.disconnect()
            throw DriveHttpException(code, err.take(300))
        }
        return conn.inputStream.use { it.readBytes() }
    }
}

/** Periodinen Drive-varmuuskopiotyö (WorkManager). Virhe kirjataan prefseihin ja työ yrittää
 *  myöhemmin uudelleen — taustakopio ei saa kaataa mitään. */
class DriveBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            DriveBackup.backupNow(applicationContext)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("DriveBackup", "Periodinen varmuuskopio epaonnistui", e)
            DriveBackup.recordError(applicationContext, e)
            Result.retry()
        }
    }
}
