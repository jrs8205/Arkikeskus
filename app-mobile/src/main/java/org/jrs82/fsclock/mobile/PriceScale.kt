package org.jrs82.fsclock.mobile

/**
 * Hintajanan asteikko = päivän halvin–kallein vartti. Etusivun kortti, sähkösivu ja widget laskevat
 * osoittimen paikan ja asteikon hinnat täältä, jotta kaikki kolme janaa näyttävät saman.
 */
object PriceScale {
    /** Osoittimen paikka janalla (0 = päivän halvin, 1 = kallein); null kun vaihteluväliä ei ole. */
    fun fraction(current: Double, min: Double, max: Double): Float? {
        if (current.isNaN() || min.isNaN() || max.isNaN() || max <= min) return null
        return ((current - min) / (max - min)).toFloat().coerceIn(0.02f, 0.98f)
    }

    /** Osoittimen paikka näytetyistä hinnoista: ALV ei koske negatiivisia hintoja, joten
     *  verottomista arvoista laskettu paikka ei osuisi janan hintoihin, kun päivän halvin on miinuksella. */
    fun fraction(current: Double, min: Double, max: Double, vat: Boolean): Float? = fraction(
        ElectricityVat.apply(current, vat), ElectricityVat.apply(min, vat), ElectricityVat.apply(max, vat),
    )

    /** Janan alun, keskikohdan ja lopun hinnat ALV-asetuksen mukaan. */
    fun labels(min: Double, max: Double, vat: Boolean): Triple<String, String, String> {
        val shownMin = ElectricityVat.apply(min, vat)
        val shownMax = ElectricityVat.apply(max, vat)
        return Triple(
            ElectricityVat.format(shownMin),
            ElectricityVat.format((shownMin + shownMax) / 2.0),
            ElectricityVat.format(shownMax),
        )
    }
}
