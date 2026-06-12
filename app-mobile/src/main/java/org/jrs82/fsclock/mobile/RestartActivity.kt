package org.jrs82.fsclock.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Sovelluksen aito uudelleenkäynnistys (mini-ProcessPhoenix): tämä activity ajetaan ERILLISESSÄ
 * prosessissa (manifest android:process=":restart"). Pääprosessi käynnistää tämän ja kuolee;
 * tämä käynnistää pääactivityn hetken päästä UUDESTA prosessista käsin → järjestelmä tekee
 * aidon kylmäkäynnistyksen splash-ruutuineen. (Jos kuoleva prosessi käynnistäisi itsensä
 * suoraan, Android ohittaa splashin — käyttäjän palaute: "splash logoa ei näy ollenkaan".)
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(
                Intent(this, MobileComposeMainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            // Tapa myös :restart-prosessi heti kun käynnistys on jätetty järjestelmälle.
            Runtime.getRuntime().exit(0)
        }, 300L)
    }

    companion object {
        /** Käynnistää sovelluksen kokonaan uudelleen (prosessi vaihtuu, splash näkyy). */
        fun restartApp(context: Context) {
            context.startActivity(
                Intent(context, RestartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            Runtime.getRuntime().exit(0)
        }
    }
}
