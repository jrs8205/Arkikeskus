package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.R
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Sähkösivun yläkortin erittely: mistä kuluvan jakson oma kokonaishinta koostuu. Parit ovat osan
 * nimen resurssi ja hinta (snt/kWh, ALV-asetuksen mukaan) järjestyksessä pörssi, marginaali (vain
 * pörssisähkösopimuksessa), siirto ja sähkövero (vain kun vero on mukana). Syöttämättömät osat
 * näkyvät nollina, jotta käyttäjä näkee, mitä luvussa on.
 *
 * Osat palautetaan kolmeen desimaaliin pyöristettyinä niin, että niiden summa on täsmälleen sama
 * kuin isona näytetty kokonaishinta: omat osat pyöristetään erikseen ja pörssiosa on näytetty
 * kokonaishinta miinus omat osat. Jos jokainen osa pyöristettäisiin itsenäisesti, summa voisi poiketa
 * kokonaishinnasta 0,001 (esim. 0,376 + 0,590 + 4,100 + 2,918 = 7,984, kun kokonaishinta on 7,985).
 *
 * null, kun omat hinnat eivät ole käytössä tai iso hinta ei ole kuluvan jakson hinta: päivän
 * keskihinnalle (Huomenna) ja puuttuvalle hinnalle erittelyä ei näytetä.
 */
internal fun electricityBreakdown(
    hero: ElectricityHero,
    current: ElectricityData.Quarter?,
    pricing: ElectricityPricing,
): List<Pair<Int, Double>>? {
    val own = pricing.own ?: return null
    if (hero != ElectricityHero.CURRENT || current == null) return null
    val parts = pricing.parts(current.timestamp, current.sntPerKwh)
    if (parts.total.isNaN() || parts.total.isInfinite()) return null
    val energy = shownDecimal(parts.energy)
    val transfer = shownDecimal(parts.transfer)
    val tax = shownDecimal(parts.tax)
    val spot = shownDecimal(parts.total) - energy - transfer - tax
    val out = ArrayList<Pair<Int, Double>>(4)
    out.add(R.string.electricity_part_spot to spot.toDouble())
    if (own.contract == OwnContract.SPOT) out.add(R.string.electricity_part_margin to energy.toDouble())
    out.add(R.string.electricity_part_transfer to transfer.toDouble())
    if (own.taxSnt > 0.0) out.add(R.string.electricity_part_tax to tax.toDouble())
    return out
}

/**
 * Luku kolmella desimaalilla samalla pyöristyksellä kuin [ElectricityVat.format] ("%.3f"). Muotoilija
 * pyöristää luvun lyhimmästä desimaaliesityksestä (7.9845 → 7,985), ei tarkasta binääriarvosta
 * (7,98449999… → 7,984), joten sama tehdään tässä [BigDecimal.valueOf]illa.
 */
internal fun shownDecimal(value: Double): BigDecimal = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP)
