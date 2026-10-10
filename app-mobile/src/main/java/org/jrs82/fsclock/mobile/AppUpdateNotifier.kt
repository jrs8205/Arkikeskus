package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.BuildConfig
import org.jrs82.fsclock.R

/**
 * 4b-ilmoitus #7: ilmoittaa kun Arkikeskuksesta on uusi versio GitHubissa (nyt vain in-app-banneri).
 * Käyttää valmista [AppUpdater]-hakua. Tarkistaa enintään 6 h välein, ilmoittaa kustakin versiosta
 * kerran. Tallentaa löydetyn version myös bannerin prefseihin → avattaessa banneri näkyy heti.
 * Opt-in (oletus pois).
 */
object AppUpdateNotifier {

    const val KEY_ENABLED = "notify_app_update"
    private const val KEY_LAST_VERSION = "notify_app_update_version"
    private const val KEY_LAST_CHECK = "notify_app_update_check_ms"
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    fun check(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        val rel = AppUpdater.fetchLatestSync() ?: return
        val current = BuildConfig.VERSION_NAME
        if (!AppUpdater.isNewer(current, rel.versionName)) return
        if (prefs.getString(KEY_LAST_VERSION, "") == rel.versionName) return // jo ilmoitettu
        android.util.Log.i("AppUpdateNotifier", "uusi versio ${rel.versionName} (nyt $current)")
        // Tallenna saataville → in-app-banneri näkyy heti kun appi avataan napautuksesta.
        prefs.edit()
            .putString(AppUpdater.PREF_AVAILABLE_VERSION, rel.versionName)
            .putString(AppUpdater.PREF_AVAILABLE_URL, rel.apkUrl ?: "")
            .putString(AppUpdater.PREF_AVAILABLE_HTML, rel.htmlUrl)
            .putString(AppUpdater.PREF_AVAILABLE_NOTES, rel.notes)
            .putString(KEY_LAST_VERSION, rel.versionName)
            .apply()
        Notifications.post(
            context, Notifications.CHANNEL_UPDATE, Notifications.NOTIF_ID_UPDATE,
            context.getString(R.string.settings_update_notif_title, rel.versionName),
            notesPreview(rel.notes) ?: context.getString(R.string.settings_update_notif_text),
            null,
        )
    }

    /** Ilmoituksen teksti julkaisukuvauksesta (releasetekstit ovat suomeksi): ensimmäinen ei-tyhjä rivi,
     *  enintään 160 merkkiä; null → yleinen käännetty teksti. */
    fun notesPreview(notes: String): String? =
        notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160)
}
