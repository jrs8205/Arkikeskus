package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.jrs82.fsclock.R
import java.util.Locale

/**
 * Pörssisähkön arvonlisävero näyttökerroksessa. Elering/Nord Pool -hinnat tallennetaan aina
 * verottomina (ALV 0 %) — repo, keskiarvocache ja widget-cache pysyvät verottomina, ja ALV lisätään
 * vasta hintaa näytettäessä tai rajaan verrattaessa. Näin kytkin vaikuttaa heti ilman uutta hakua.
 * Tekstit palautetaan resurssi-id:inä, jotta logiikka pysyy JVM-testattavana ilman Contextia.
 */
object ElectricityVat {
    const val KEY = "mobile_electricity_vat"
    const val DEFAULT = true
    const val RATE_PERCENT = 25.5
    private const val FACTOR = 1.255

    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    /** Negatiivisesta hinnasta ei synny arvonlisäverotettavaa kulua (hyvitys on veroton), joten vero
     *  lisätään vain nollaa suurempiin hintoihin. Keskiarvot lasketaan siksi näytetyistä hinnoista. */
    fun apply(snt: Double, enabled: Boolean): Double = if (enabled && snt > 0.0) snt * FACTOR else snt

    @StringRes
    fun labelRes(enabled: Boolean): Int =
        if (enabled) R.string.electricity_vat_label_incl else R.string.electricity_vat_label_zero

    fun label(context: Context, enabled: Boolean): String = context.getString(labelRes(enabled))

    /** Kompositiosta kutsuttava muoto (asetusten rajadialogi kutsuu tätä suoraan). */
    @Composable
    fun label(enabled: Boolean): String = stringResource(labelRes(enabled))

    /** Näkyvä ilmoitus hinnan alla (etusivun kortti, sähkösivun hero). */
    @StringRes
    fun priceNoteRes(enabled: Boolean): Int =
        if (enabled) R.string.electricity_vat_note_incl else R.string.electricity_vat_note_excl

    /** Widgetin tiivis muoto otsikon alle. */
    @StringRes
    fun widgetNoteRes(enabled: Boolean): Int =
        if (enabled) R.string.electricity_vat_label_incl else R.string.electricity_vat_widget_excl

    /** Aina kolme desimaalia kielen desimaalierottimella; NaN → "–"; −0,000 siivotaan nollaksi. */
    fun format(snt: Double, locale: Locale): String {
        if (snt.isNaN()) return "–"
        val safe = if (Math.abs(snt) < 0.0005) 0.0 else snt
        return String.format(locale, "%.3f", safe)
    }
}
