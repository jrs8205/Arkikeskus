package org.jrs82.fsclock.mobile

import androidx.annotation.StringRes
import org.jrs82.fsclock.R

/*
 * Selitteet, jotka kertovat, mikä näytetty sähkön hinta on: pörssihinta ALV-tietoineen (ilman omia
 * hintoja, nykyiset ElectricityVat-tekstit), oma kokonaishinta (pörssisähkösopimus) tai pörssisähkö
 * omalla siirrolla ja verolla (kiinteä sopimus, vertailutila). Palautetaan resurssi-id:t, jotta
 * valinta on JVM-testattava ilman Contextia.
 */

/** Hinnan alla näytettävä huomautus (etusivun kortti, sähkösivun yläkortti). */
@StringRes
fun ElectricityPricing.priceNoteRes(): Int = when {
    own == null -> ElectricityVat.priceNoteRes(vat)
    fixedCompare -> if (vat) R.string.electricity_fixed_note_incl else R.string.electricity_fixed_note_excl
    else -> if (vat) R.string.electricity_own_note_incl else R.string.electricity_own_note_excl
}

/** Lyhyt muoto lauseen sisään (hintalistan otsikko, ilmoituksen loppu). */
@StringRes
fun ElectricityPricing.labelRes(): Int = when {
    own == null -> ElectricityVat.labelRes(vat)
    fixedCompare -> if (vat) R.string.electricity_fixed_label_incl else R.string.electricity_fixed_label_excl
    else -> if (vat) R.string.electricity_own_label_incl else R.string.electricity_own_label_excl
}

/** Widgetin tiivis muoto otsikon alle. */
@StringRes
fun ElectricityPricing.widgetNoteRes(): Int = when {
    own == null -> ElectricityVat.widgetNoteRes(vat)
    fixedCompare -> R.string.electricity_fixed_widget
    else -> R.string.electricity_own_widget
}

/** Halvan sähkön rajan selite. Raja verrataan aina pörssihintaan; omilla hinnoilla selite sanoo sen,
 *  koska viereiset luvut ovat silloin kokonaishintoja. */
@StringRes
fun ElectricityPricing.thresholdLabelRes(): Int = when {
    own == null -> if (vat) R.string.settings_threshold_label_vat else R.string.settings_threshold_label_no_vat
    else -> if (vat) R.string.settings_threshold_label_spot_vat else R.string.settings_threshold_label_spot_no_vat
}

/** Halvan sähkön huomion teksti: koko lause per tila, koska ajanilmaus taipuu eri kielissä eri kohtaan
 *  lausetta. [ownPrices] = näytetäänkö oma kokonaishinta; silloin huomio nimeää
 *  hintansa pörssihinnoiksi. */
@StringRes
internal fun cheapNoticeRes(mode: String, ownPrices: Boolean): Int = when (mode) {
    MobileThemeController.CHEAP_MODE_CURRENT ->
        if (ownPrices) R.string.electricity_cheap_notice_now_spot else R.string.electricity_cheap_notice_now
    MobileThemeController.CHEAP_MODE_REMAINING_DAY ->
        if (ownPrices) R.string.electricity_cheap_notice_rest_of_day_spot else R.string.electricity_cheap_notice_rest_of_day
    else ->
        if (ownPrices) R.string.electricity_cheap_notice_all_day_spot else R.string.electricity_cheap_notice_all_day
}
