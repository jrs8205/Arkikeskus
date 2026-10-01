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

    /** Janan alun, keskikohdan ja lopun hinnat ALV-asetuksen mukaan. */
    fun labels(min: Double, max: Double, vat: Boolean): Triple<String, String, String> = Triple(
        ElectricityVat.format(ElectricityVat.apply(min, vat)),
        ElectricityVat.format(ElectricityVat.apply((min + max) / 2.0, vat)),
        ElectricityVat.format(ElectricityVat.apply(max, vat)),
    )
}
