package org.jrs82.fsclock.mobile

import androidx.annotation.StringRes
import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.R

/** Teksti sähkösivun päivälle, jolla ei ole näytettäviä vartteja. Epäonnistunut haku kerrotaan
 *  erikseen, jotta sitä ei luule vielä julkaisemattomiksi hinnoiksi. [dayOffset] 0 = tänään, 1 = huomenna. */
@StringRes
internal fun electricityEmptyTextRes(dayOffset: Int, fetchFailed: Boolean): Int = when {
    fetchFailed -> R.string.electricity_fetch_failed
    dayOffset == 0 -> R.string.electricity_today_unavailable
    else -> R.string.electricity_tomorrow_pending
}

/** Lähdemaininta sen mukaan, kummasta lähteestä näytettävät vartit ovat ([ElectricityData.source]). */
@StringRes
internal fun electricitySourceFooterRes(source: String?): Int =
    if (source == ElectricityData.SOURCE_SPOT_HINTA) R.string.electricity_source_footer_spot_hinta
    else R.string.electricity_source_footer

/** Sähkösivun pääkortin sisältö. */
internal enum class ElectricityHero {
    /** Tänään: kuluvan jakson (vartin tai tunnin) hinta. */
    CURRENT,

    /** Tänään, mutta kuluvalle jaksolle ei ole hintaa, vaikka päivältä on muita hintoja. */
    NO_CURRENT,

    /** Huomenna: päivän keskihinta. */
    DAY_AVERAGE,
}

/** Tänään-välilehti ei saa pudota huomisen esitykseen (Huomenna-tunnus + päivän keskihinta), kun
 *  kuluvan jakson hinta puuttuu. Niin käy esimerkiksi tuntitilassa, kun kuluvalta tunnilta puuttuu
 *  vartti: tunnin keskihintaa ei silloin näytetä, vaikka päivän muilla tunneilla on hinnat. */
internal fun electricityHero(dayOffset: Int, hasCurrent: Boolean): ElectricityHero = when {
    dayOffset != 0 -> ElectricityHero.DAY_AVERAGE
    hasCurrent -> ElectricityHero.CURRENT
    else -> ElectricityHero.NO_CURRENT
}

/**
 * Jaksot, joiden kaikkien pitää alittaa raja, jotta "sähkö on halpaa" -huomio näytetään: kuluva
 * jakso, loppupäivän jaksot tai koko päivän jaksot ([mode]). Jaksot ovat tunteja vain, jos tuntitila
 * on voimassa tälle päivälle ([ElectricityHourly.daySlots]); muuten vartteja.
 */
internal fun cheapNoticeSlots(
    today: List<ElectricityData.Quarter>,
    mode: String,
    hourlySetting: Boolean,
    vat: Boolean,
    nowMs: Long,
): List<ElectricityData.Quarter> {
    val day = ElectricityHourly.daySlots(today, hourlySetting, vat)
    return day.slots.filter { slot ->
        when (mode) {
            MobileThemeController.CHEAP_MODE_CURRENT -> slot.timestamp <= nowMs && slot.timestamp + day.slotMs > nowMs
            MobileThemeController.CHEAP_MODE_REMAINING_DAY -> slot.timestamp + day.slotMs > nowMs
            else -> true
        }
    }
}

/** True jos jaksoja on ja jokaisen NÄYTETTY hinta alittaa rajan. */
internal fun allBelowThreshold(slots: List<ElectricityData.Quarter>, threshold: Double, vat: Boolean): Boolean =
    slots.isNotEmpty() && slots.all { ElectricityVat.apply(it.sntPerKwh, vat) < threshold }

/** Teksti puuttuvalle kuluvan jakson hinnalle näytettävän tarkkuuden mukaan. */
@StringRes
internal fun electricityNoCurrentTextRes(hourly: Boolean): Int =
    if (hourly) R.string.electricity_no_current_hour_price else R.string.electricity_no_current_price
