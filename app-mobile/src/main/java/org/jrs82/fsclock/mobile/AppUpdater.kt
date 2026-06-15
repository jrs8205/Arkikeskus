package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sovelluksen itsepäivitys GitHub-julkaisuista. Tarkistaa uusimman releasen GitHubin julkisesta
 * REST-rajapinnasta (ei autentikointia), vertaa versiota nykyiseen ja voi ladata + käynnistää
 * APK-asennuksen suoraan (FileProvider + ACTION_VIEW). Asennus vaatii saman allekirjoituksen
 * (release.keystore) → päivittyy paikalleen ilman poistoa, ja käyttäjän luvan asentaa tuntemattomista
 * lähteistä (järjestelmä pyytää sen install-intentissä).
 */
object AppUpdater {

    private const val API = "https://api.github.com/repos/jrs8205/Arkikeskus/releases/latest"
    const val REPO_URL = "https://github.com/jrs8205/Arkikeskus"

    // Automaattinen tarkistus käynnistyksessä: verkkokutsu max 6 h välein, mutta löydetty
    // versio talletetaan prefseihin → banneri näkyy heti myös välikäynnistyksillä.
    const val PREF_LAST_AUTO_CHECK = "appupdate_last_check_ms"
    const val PREF_AVAILABLE_VERSION = "appupdate_available_version"
    const val PREF_AVAILABLE_URL = "appupdate_available_url"
    const val PREF_AVAILABLE_HTML = "appupdate_available_html"
    const val PREF_AVAILABLE_NOTES = "appupdate_available_notes"
    const val PREF_DISMISSED_VERSION = "appupdate_dismissed_version"
    const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val main = Handler(Looper.getMainLooper())

    data class ReleaseInfo(
        val tag: String,
        val versionName: String,
        val apkUrl: String?,
        val htmlUrl: String,
        val notes: String = "",
    )

    /** Hakee uusimman julkaisun taustasäikeessä; callback pääsäikeessä (release, onUudempi). */
    fun checkLatest(current: String, cb: (ReleaseInfo?, Boolean) -> Unit) {
        Thread {
            val rel = try {
                fetchLatest()
            } catch (e: Exception) {
                null
            }
            val newer = rel != null && isNewer(current, rel.versionName)
            main.post { cb(rel, newer) }
        }.start()
    }

    private fun fetchLatest(): ReleaseInfo? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Arkikeskus-Android")
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            if (tag.isEmpty()) return null
            val html = json.optString("html_url", REPO_URL)
            // Julkaisukuvaus (markdown) → "Mitä uutta" -dialogi. JSON-null suojattu erikseen.
            val notes = if (json.isNull("body")) "" else json.optString("body", "").trim()
            var apk: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url", "").ifEmpty { null }
                        break
                    }
                }
            }
            return ReleaseInfo(tag, tag.trimStart('v', 'V'), apk, html, notes)
        } finally {
            conn.disconnect()
        }
    }

    /** True jos latest-versio on suurempi kuin current (numeerinen ydinosa ennen "-", esim. 1.15.1). */
    fun isNewer(current: String, latest: String): Boolean {
        val c = versionParts(current)
        val l = versionParts(latest)
        for (i in 0 until maxOf(c.size, l.size)) {
            val cv = c.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    private fun versionParts(v: String): List<Int> =
        v.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    /** Lataa APK:n taustasäikeessä ja käynnistää asennuksen; callback pääsäikeessä (tilateksti). */
    fun downloadAndInstall(context: Context, apkUrl: String, cb: (String) -> Unit) {
        val app = context.applicationContext
        Thread {
            val msg = try {
                val file = download(app, apkUrl)
                val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(intent)
                "Avataan asennus…"
            } catch (e: Exception) {
                "Lataus epäonnistui: " + (e.message ?: e.javaClass.simpleName)
            }
            main.post { cb(msg) }
        }.start()
    }

    private fun download(context: Context, urlStr: String): File {
        var url = URL(urlStr)
        var conn = open(url)
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            url = URL(url, loc)
            conn = open(url)
            redirects++
        }
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw Exception("HTTP $code")
        }
        val dir = File(context.cacheDir, "updates")
        dir.mkdirs()
        val file = File(dir, "arkikeskus-update.apk")
        conn.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
        conn.disconnect()
        return file
    }

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("User-Agent", "Arkikeskus-Android")
        }
}
