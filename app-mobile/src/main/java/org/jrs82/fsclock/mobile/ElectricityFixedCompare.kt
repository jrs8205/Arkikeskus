package org.jrs82.fsclock.mobile

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.R

/*
 * Kiinteän sähkösopimuksen vertailutila ([ElectricityPricing.fixedCompare]). Näkymät näyttävät, mitä
 * pörssisähkö maksaisi käyttäjälle omalla siirrolla ja verolla, ja nämä funktiot kertovat, miten se
 * suhteutuu käyttäjän omaan kiinteään kokonaishintaan ([ElectricityPricing.ownFixedAt]). Ilman
 * vertailutilaa, ja kunnes kiinteä hinta on syötetty ([ElectricityPricing.fixedPriceSet]), kaikki
 * palauttavat null.
 *
 * Kortin teksti, hintalistan rivin merkki ja päivän laskuri käyttävät samaa vertailua
 * ([shownFixedDiff]): hintoja verrataan sellaisina kuin ne näytetään, kolmeen desimaaliin
 * pyöristettyinä. Näkyvästi eri hinnat (10,213 ja 10,214) eivät siis koskaan ole "samanhintaisia",
 * eivätkä samalta näyttävät hinnat koskaan "halvempi" tai "kalliimpi".
 */

/**
 * Oman kiinteän kokonaishinnan ja pörssisähkön kokonaishinnan ero näytetyistä luvuista: molemmat
 * pyöristetään kuten ne näytetään ([shownDecimal]) ja ero on näiden erotus, eli aina 0,001:n
 * monikerta ja sama luku, jonka käyttäjä saa vähentämällä näkyvät hinnat toisistaan. Positiivinen =
 * pörssisähkö on halvempi, nolla = hinnat näkyvät samoina. NaN, jos jompikumpi hinta puuttuu.
 */
internal fun shownFixedDiff(ownPrice: Double, spotPrice: Double): Double {
    if (ownPrice.isNaN() || ownPrice.isInfinite() || spotPrice.isNaN() || spotPrice.isInfinite()) return Double.NaN
    return (shownDecimal(ownPrice) - shownDecimal(spotPrice)).toDouble()
}

/**
 * Yläkortin vertailurivi. [ownPrice] = oma kiinteä kokonaishinta ja [spotPrice] = kortin iso hinta
 * (pörssisähkö omalla siirrolla ja verolla); [dayAverage] = luvut ovat päivän keskiarvoja.
 */
internal data class FixedHeroCompare(val ownPrice: Double, val spotPrice: Double, val dayAverage: Boolean) {
    /** Näytettyjen hintojen ero ([shownFixedDiff]): > 0 = pörssisähkö olisi halvempi, < 0 = oma sopimus on halvempi. */
    val diff: Double get() = shownFixedDiff(ownPrice, spotPrice)
}

/**
 * Vertailu yläkortin isolle hinnalle: kuluvalle jaksolle ([ElectricityHero.CURRENT]) tai päivän
 * varttien keskiarvoille ([ElectricityHero.DAY_AVERAGE]). null, kun kuluvan jakson hinta puuttuu.
 */
internal fun fixedHeroCompare(
    hero: ElectricityHero,
    current: ElectricityData.Quarter?,
    quarters: List<ElectricityData.Quarter>,
    pricing: ElectricityPricing,
): FixedHeroCompare? {
    if (!pricing.fixedPriceSet) return null
    val compare = when (hero) {
        ElectricityHero.CURRENT -> current?.let {
            FixedHeroCompare(pricing.ownFixedAt(it.timestamp), pricing.shown(it), dayAverage = false)
        }
        ElectricityHero.DAY_AVERAGE -> if (quarters.isEmpty()) null else FixedHeroCompare(
            quarters.map { pricing.ownFixedAt(it.timestamp) }.average(),
            electricityDayAverage(quarters, pricing),
            dayAverage = true,
        )
        ElectricityHero.NO_CURRENT -> null
    }
    // Puuttuvaa hintaa ei verrata.
    return compare?.takeIf { !it.diff.isNaN() }
}

/** Onko pörssisähkö jaksolla omaa kiinteää hintaa halvempi, kalliimpi vai saman hintainen. */
internal enum class FixedMark { SPOT_CHEAPER, SPOT_DEARER, EQUAL }

/** Jakson vertailumerkki; null ilman vertailutilaa tai kun jakson hinta puuttuu. */
internal fun fixedMark(slot: ElectricityData.Quarter, pricing: ElectricityPricing): FixedMark? {
    if (!pricing.fixedPriceSet) return null
    val diff = shownFixedDiff(pricing.ownFixedAt(slot.timestamp), pricing.shown(slot))
    return if (diff.isNaN()) null else markOf(diff)
}

/** [diff] on [shownFixedDiff]in tulos: nolla täsmälleen silloin, kun hinnat näkyvät samoina. */
private fun markOf(diff: Double): FixedMark = when {
    diff > 0.0 -> FixedMark.SPOT_CHEAPER
    diff < 0.0 -> FixedMark.SPOT_DEARER
    else -> FixedMark.EQUAL
}

/** Yläkortin vertailurivin teksti: argumentit oma hinta ja eron itseisarvo (saman hintaisessa vain oma hinta). */
@StringRes
internal fun fixedHeroRes(compare: FixedHeroCompare): Int = when (markOf(compare.diff)) {
    FixedMark.SPOT_CHEAPER ->
        if (compare.dayAverage) R.string.electricity_fixed_hero_avg_cheaper else R.string.electricity_fixed_hero_cheaper
    FixedMark.SPOT_DEARER ->
        if (compare.dayAverage) R.string.electricity_fixed_hero_avg_dearer else R.string.electricity_fixed_hero_dearer
    FixedMark.EQUAL ->
        if (compare.dayAverage) R.string.electricity_fixed_hero_avg_equal else R.string.electricity_fixed_hero_equal
}

/** Päivän yhteenveto: argumentit halvempien jaksojen määrä ja jaksojen määrä. */
@StringRes
internal fun fixedSummaryRes(hourly: Boolean): Int =
    if (hourly) R.string.electricity_fixed_summary_hours else R.string.electricity_fixed_summary_quarters

/** Hintalistan rivin vertailumerkin ruudunlukijateksti. */
@StringRes
internal fun fixedRowDescriptionRes(mark: FixedMark): Int = when (mark) {
    FixedMark.SPOT_CHEAPER -> R.string.electricity_fixed_row_cheaper
    FixedMark.SPOT_DEARER -> R.string.electricity_fixed_row_dearer
    FixedMark.EQUAL -> R.string.electricity_fixed_row_equal
}

/** Hintalistan rivin vertailumerkki: nuoli alas (pörssi halvempi), ylös (kalliimpi) tai sivulle (sama).
 *  Merkitys on kuvakkeen muodossa, ei värissä. */
@DrawableRes
internal fun fixedMarkIconRes(mark: FixedMark): Int = when (mark) {
    FixedMark.SPOT_CHEAPER -> R.drawable.mobile_ic_arrow_down_24
    FixedMark.SPOT_DEARER -> R.drawable.mobile_ic_arrow_up_24
    FixedMark.EQUAL -> R.drawable.mobile_ic_arrow_right_alt_24
}

/** Jaksot, joilla pörssisähkö on omaa hintaa halvempi, ja jaksojen määrä. null ilman vertailutilaa. */
internal fun fixedCheaperCount(slots: List<ElectricityData.Quarter>, pricing: ElectricityPricing): Pair<Int, Int>? {
    if (!pricing.fixedPriceSet) return null
    return slots.count { fixedMark(it, pricing) == FixedMark.SPOT_CHEAPER } to slots.size
}
