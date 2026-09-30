package org.jrs82.fsclock.mobile

import android.content.SharedPreferences
import java.util.Locale

/**
 * Pörssisähkön arvonlisävero näyttökerroksessa. Elering/Nord Pool -hinnat tallennetaan aina
 * verottomina (ALV 0 %) — repo, keskiarvocache ja widget-cache pysyvät verottomina, ja ALV lisätään
 * vasta hintaa näytettäessä tai rajaan verrattaessa. Näin kytkin vaikuttaa heti ilman uutta hakua.
 */
object ElectricityVat {
    const val KEY = "mobile_electricity_vat"
    const val DEFAULT = true
    const val RATE_PERCENT = 25.5
    private const val FACTOR = 1.255
    private val FI = Locale("fi", "FI")

    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun factor(enabled: Boolean): Double = if (enabled) FACTOR else 1.0

    fun apply(snt: Double, enabled: Boolean): Double = if (enabled) snt * FACTOR else snt

    fun label(enabled: Boolean): String = if (enabled) "sis. ALV 25,5 %" else "ALV 0 %"

    /** Näkyvä ilmoitus hinnan alla (etusivun kortti, sähkösivun hero). */
    fun priceNote(enabled: Boolean): String =
        if (enabled) "Hinta sisältää ALV:n 25,5 %" else "Hinta ei sisällä ALV:tä (ALV 0 %)"

    /** Widgetin tiivis muoto otsikon alle. */
    fun widgetNote(enabled: Boolean): String = if (enabled) "sis. ALV 25,5 %" else "ilman ALV:tä"

    /** Aina kolme desimaalia suomalaisella pilkulla; NaN → "–"; −0,000 siivotaan nollaksi. */
    fun format(snt: Double): String {
        if (snt.isNaN()) return "–"
        val safe = if (Math.abs(snt) < 0.0005) 0.0 else snt
        return String.format(FI, "%.3f", safe)
    }
}
