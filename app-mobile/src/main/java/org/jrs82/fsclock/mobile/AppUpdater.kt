package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

    // Lazy: ei luoda Handleria objektin alustuksessa → isNewer ym. puhtaat funktiot toimivat
    // JVM-yksikkötesteissä ilman Android-Looperia.
    private val main by lazy { Handler(Looper.getMainLooper()) }

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

    /** Synkroninen haku taustatyölle (kutsuttava taustasäikeestä). Null jos epäonnistuu. */
    fun fetchLatestSync(): ReleaseInfo? = try { fetchLatest() } catch (e: Exception) { null }

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

    /** Lataa APK:n taustasäikeessä ja käynnistää asennuksen; callback pääsäikeessä (tilateksti).
     *  Kutsuttava pääsäikeestä (UI-painike). */
    fun downloadAndInstall(context: Context, apkUrl: String, cb: (String) -> Unit) {
        val app = context.applicationContext
        // Android 8+: asennus vaatii per-sovellus "asenna tuntemattomista lähteistä" -luvan. Ilman
        // sitä koko lataus menisi hukkaan ja asennus epäonnistuisi selittämättä → ohjataan käyttäjä
        // asetukseen ja yritetään uudelleen vasta luvan myöntämisen jälkeen. (Happy-path: lupa jo
        // myönnetty → tämä ohitetaan ja toiminta on identtinen ennen muutosta.)
        if (!app.packageManager.canRequestPackageInstalls()) {
            try {
                val settings = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + app.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(settings)
            } catch (e: Exception) {
                // Joillain laitteilla asetusnäkymää ei ole — annetaan vain ohjeteksti.
            }
            cb("Salli ensin asennus tuntemattomista lähteistä, sitten yritä uudelleen.")
            return
        }
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
        val expected = conn.contentLengthLong // -1 jos palvelin ei ilmoita pituutta
        val dir = File(context.cacheDir, "updates")
        dir.mkdirs()
        // Siivoa vanhat/keskeneräiset lataukset ennen uutta → cacheDir ei paisu.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "arkikeskus-update.apk")
        val part = File(dir, "arkikeskus-update.apk.part")
        var written = 0L
        try {
            conn.inputStream.use { input -> part.outputStream().use { out -> written = input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
        // Eheys: jos palvelin ILMOITTI pituuden eikä se täsmää, lataus katkesi → ei asenneta vajaata.
        // Kun pituutta ei ilmoiteta (expected <= 0), tarkistus ohitetaan ettei kelvollinen lataus esty.
        if (expected > 0L && written != expected) {
            part.delete()
            throw Exception("Epätäydellinen lataus ($written/$expected tavua)")
        }
        if (written <= 0L) {
            part.delete()
            throw Exception("Tyhjä lataus")
        }
        file.delete()
        if (!part.renameTo(file)) {
            // Varakeino jos rename ei onnistu: kopioi ja poista keskeneräinen.
            part.copyTo(file, overwrite = true)
            part.delete()
        }
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
