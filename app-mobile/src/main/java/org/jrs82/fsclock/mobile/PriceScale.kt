package org.jrs82.fsclock.mobile

import java.util.Locale

/**
 * Hintajanan asteikko = päivän halvin–kallein jakso. Etusivun kortti, sähkösivu ja widget laskevat
 * osoittimen paikan ja asteikon hinnat täältä, jotta kaikki kolme janaa näyttävät saman.
 *
 * Kaikki arvot annetaan NÄYTETTYINÄ hintoina ([ElectricityPricing.shown]). ALV ei koske negatiivisia
 * hintoja ja omat lisät voivat riippua kellonajasta, joten verottomista pörssihinnoista laskettu paikka
 * ei osuisi janan hintoihin.
 */
object PriceScale {
    /** Osoittimen paikka janalla (0 = päivän halvin, 1 = kallein); null kun vaihteluväliä ei ole. */
    fun fraction(current: Double, min: Double, max: Double): Float? {
        if (current.isNaN() || min.isNaN() || max.isNaN() || max <= min) return null
        return ((current - min) / (max - min)).toFloat().coerceIn(0.02f, 0.98f)
    }

    /** Janan alun, keskikohdan ja lopun hinnat, desimaalit [locale]n mukaan. */
    fun labels(shownMin: Double, shownMax: Double, locale: Locale): Triple<String, String, String> = Triple(
        ElectricityVat.format(shownMin, locale),
        ElectricityVat.format((shownMin + shownMax) / 2.0, locale),
        ElectricityVat.format(shownMax, locale),
    )
}
