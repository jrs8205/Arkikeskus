package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Selitteet kertovat, onko näytetty luku pörssihinta vai oma kokonaishinta. Kytkin pois = nykyiset tekstit. */
class ElectricityPricingLabelsTest {
    private val transfer = OwnTransfer(TransferProduct.GENERAL, 4.10, 0.0)
    private fun spot(vat: Boolean) = ElectricityPricing(vat, OwnPrices(OwnContract.SPOT, 0.59, transfer, 2.918))
    private fun fixed(vat: Boolean) = ElectricityPricing(vat, OwnPrices(OwnContract.FIXED, 7.0, transfer, 2.918))

    @Test fun off_usesTheExistingVatTexts() {
        for (vat in listOf(true, false)) {
            val p = ElectricityPricing(vat)
            assertEquals(ElectricityVat.priceNoteRes(vat), p.priceNoteRes())
            assertEquals(ElectricityVat.labelRes(vat), p.labelRes())
            assertEquals(ElectricityVat.widgetNoteRes(vat), p.widgetNoteRes())
        }
        assertEquals(R.string.settings_threshold_label_vat, ElectricityPricing(true).thresholdLabelRes())
        assertEquals(R.string.settings_threshold_label_no_vat, ElectricityPricing(false).thresholdLabelRes())
    }

    @Test fun spotContract_saysOwnTotalPrice() {
        assertEquals(R.string.electricity_own_note_incl, spot(true).priceNoteRes())
        assertEquals(R.string.electricity_own_note_excl, spot(false).priceNoteRes())
        assertEquals(R.string.electricity_own_label_incl, spot(true).labelRes())
        assertEquals(R.string.electricity_own_label_excl, spot(false).labelRes())
        assertEquals(R.string.electricity_own_widget, spot(true).widgetNoteRes())
        assertEquals(R.string.electricity_own_widget, spot(false).widgetNoteRes())
    }

    @Test fun fixedContract_saysSpotWithOwnTransferAndTax() {
        assertEquals(R.string.electricity_fixed_note_incl, fixed(true).priceNoteRes())
        assertEquals(R.string.electricity_fixed_note_excl, fixed(false).priceNoteRes())
        assertEquals(R.string.electricity_fixed_label_incl, fixed(true).labelRes())
        assertEquals(R.string.electricity_fixed_label_excl, fixed(false).labelRes())
        assertEquals(R.string.electricity_fixed_widget, fixed(true).widgetNoteRes())
    }

    @Test fun thresholdLabel_saysSpotPrice_whenOwnPricesAreOn() {
        // Halvan sähkön raja verrataan aina pörssihintaan; omilla hinnoilla selite sanoo sen.
        assertEquals(R.string.settings_threshold_label_spot_vat, spot(true).thresholdLabelRes())
        assertEquals(R.string.settings_threshold_label_spot_no_vat, spot(false).thresholdLabelRes())
        assertEquals(R.string.settings_threshold_label_spot_vat, fixed(true).thresholdLabelRes())
    }

    @Test fun cheapNotice_namesSpotPrice_onlyWhenOwnPricesAreOn() {
        val now = MobileThemeController.CHEAP_MODE_CURRENT
        val rest = MobileThemeController.CHEAP_MODE_REMAINING_DAY
        val all = MobileThemeController.CHEAP_MODE_ALL_DAY
        assertEquals(R.string.electricity_cheap_notice_now, cheapNoticeRes(now, ownPrices = false))
        assertEquals(R.string.electricity_cheap_notice_rest_of_day, cheapNoticeRes(rest, ownPrices = false))
        assertEquals(R.string.electricity_cheap_notice_all_day, cheapNoticeRes(all, ownPrices = false))
        assertEquals(R.string.electricity_cheap_notice_now_spot, cheapNoticeRes(now, ownPrices = true))
        assertEquals(R.string.electricity_cheap_notice_rest_of_day_spot, cheapNoticeRes(rest, ownPrices = true))
        assertEquals(R.string.electricity_cheap_notice_all_day_spot, cheapNoticeRes(all, ownPrices = true))
    }
}
