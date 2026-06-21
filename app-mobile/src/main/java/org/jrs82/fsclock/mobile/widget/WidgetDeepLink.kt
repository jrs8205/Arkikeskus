package org.jrs82.fsclock.mobile.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import org.jrs82.fsclock.mobile.MobileComposeMainActivity
import org.jrs82.fsclock.mobile.WorkoutTrackingService

/**
 * Widgetin tap -> avaa sovellus oikeaan sektioon.
 *
 * Pixel-korjaus: käytetään Glancen appwidget-Intent-overloadia ja annetaan intentille UNIIKKI
 * data-URI ([SCHEME]://widget/<sektio>). `Intent.filterEquals` EI vertaile extroja, joten pelkkä
 * extra ei tee PendingIntenteistä eri instansseja -> Androidin/Glancen PendingIntent-cache voi
 * palauttaa saman tokenin kaikille widgeteille ja toimittaa vanhentuneet/tyhjät extrat (oire:
 * kaikki widgetit avaavat etusivun). Data-URI sen sijaan on osa PendingIntent-identiteettiä ja
 * toimitetaan luotettavasti -> sektio luetaan ensisijaisesti URIsta, extra jää fallbackiksi.
 *
 * Sektio = [org.jrs82.fsclock.mobile.HomeSection]-enumin nimi (esim. "FORECAST"), jonka
 * MobileComposeMainActivity lukee (resolveWidgetSection -> HomeSection.valueOf).
 */
object WidgetDeepLink {
    /** Widget-deep-linkin URI-skeema. MobileComposeMainActivity tunnistaa tämän sektionavigoinniksi
     *  (ja jättää sen tiedostotuonnin ulkopuolelle). */
    const val SCHEME = "arkikeskus"

    fun openSection(context: Context, section: String): Action {
        val intent = Intent(context, MobileComposeMainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("$SCHEME://widget/$section")
            putExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION, section)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return actionStartActivity(intent)
    }
}
