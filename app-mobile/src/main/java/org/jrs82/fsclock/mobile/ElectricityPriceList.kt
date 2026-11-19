package org.jrs82.fsclock.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.jrs82.fsclock.ElectricityData

/**
 * Sähkösivun hintalistan rivi. [rangeText] on muotoa "08.30–08.45"; talviaikaan siirtymisen
 * toistuvan tunnin jälkimmäisellä kierroksella alkuajan perässä on tähti ("03.00*–03.15").
 * [displayPrice] on näytettävä hinta, [scalePrice] lämpövärin asteikon arvo
 * ([ElectricityPricing.scaleValue]: veroton pörssihinta, tai omilla hinnoilla näytetty hinta).
 * [fixedMark] on kiinteän sopimuksen vertailumerkki, muuten null.
 */
internal data class PriceRow(
    val timestamp: Long,
    val rangeText: String,
    val displayPrice: Double,
    val scalePrice: Double,
    val isNow: Boolean,
    val fixedMark: FixedMark? = null,
)

/**
 * Rivit näytettävistä jaksoista (vartit tai tunnit, pituus [slotMs]). [nowMs] null = ei kuluvaa jaksoa (Huomenna).
 * Kellonsiirtoyön rivi näyttää jakson pituiselta ([ElectricityTime.slotClockTexts]).
 */
internal fun electricityPriceRows(
    slots: List<ElectricityData.Quarter>,
    slotMs: Long,
    pricing: ElectricityPricing,
    nowMs: Long?,
): List<PriceRow> = slots.map { slot ->
    val (start, end) = ElectricityTime.slotClockTexts(slot.timestamp, slotMs)
    val star = if (ElectricityTime.isRepeatedPass(slot.timestamp)) "*" else ""
    PriceRow(
        timestamp = slot.timestamp,
        rangeText = "$start$star–$end",
        displayPrice = pricing.shown(slot),
        scalePrice = pricing.scaleValue(slot.timestamp, slot.sntPerKwh),
        isNow = nowMs != null && slot.timestamp <= nowMs && nowMs < slot.timestamp + slotMs,
        fixedMark = fixedMark(slot, pricing),
    )
}

/**
 * Onko päivältä näytettäviä hintoja. Huomisen hinnat näytetään vasta, kun data ulottuu iltaan
 * (tunti ≥ 20): pelkkä keskiyön CET-ylivuoto (huomisen 00.00–00.45) ei riitä. Toimii myös
 * kellonsiirtopäivinä, toisin kuin kiinteä varttimäärä.
 */
internal fun electricityDayHasPrices(dayOffset: Int, quarters: List<ElectricityData.Quarter>?): Boolean = when {
    quarters.isNullOrEmpty() -> false
    dayOffset == 1 -> quarters.any { it.hour >= 20 }
    else -> true
}

/** Päivän keskihinta näytetyistä varttihinnoista (ALV vain positiivisiin pörssihintoihin, omat lisät
 *  jokaiseen varttiin). Tyhjä lista → NaN. */
internal fun electricityDayAverage(quarters: List<ElectricityData.Quarter>, pricing: ElectricityPricing): Double =
    quarters.map { pricing.shown(it) }.average()

/**
 * Jaksojen halvin ja kallein [ElectricityPricing.scaleValue]n mukaan; tasatilanteessa ensimmäinen.
 * Omilla hinnoilla järjestys on kokonaishinnan järjestys: halvempi yösiirto voi tehdä yövartista
 * halvemman kuin päivän halvin pörssivartti. Tyhjä lista → null.
 */
internal fun electricitySlotRange(
    slots: List<ElectricityData.Quarter>,
    pricing: ElectricityPricing,
): Pair<ElectricityData.Quarter, ElectricityData.Quarter>? {
    val min = slots.minByOrNull { pricing.scaleValue(it.timestamp, it.sntPerKwh) } ?: return null
    val max = slots.maxByOrNull { pricing.scaleValue(it.timestamp, it.sntPerKwh) } ?: return null
    return min to max
}

/** Yläkortin hintalaatat. */
internal enum class SummaryTile { CHEAPEST, AVERAGE, MOST_EXPENSIVE }

/** Huomenna yläkortin iso hinta on jo päivän keskihinta, joten keskihintalaattaa ei toisteta. */
internal fun electricitySummaryTiles(dayOffset: Int): List<SummaryTile> =
    if (dayOffset == 0) listOf(SummaryTile.CHEAPEST, SummaryTile.AVERAGE, SummaryTile.MOST_EXPENSIVE)
    else listOf(SummaryTile.CHEAPEST, SummaryTile.MOST_EXPENSIVE)

/** Tunti on valittu, mutta päivä näytetään vartteina, koska jokin tunti on vajaa. */
internal fun electricityHourlyFallback(hourlySetting: Boolean, day: ElectricityHourly.DaySlots): Boolean =
    hourlySetting && day.slots.isNotEmpty() && !day.hourly

/** Aika (ms) seuraavan [slotMs]-jakson alkuun; jakson alussa koko jakson pituus (aina > 0). */
internal fun nextSlotBoundaryDelayMs(nowMs: Long, slotMs: Long): Long = slotMs - Math.floorMod(nowMs, slotMs)

/** Siirry nykyhetkeen -painikkeen nuolen suunta. */
internal enum class JumpDirection { UP, DOWN }

/**
 * Suunta kuluvan rivin luo, tai null kun painiketta ei tarvita: kuluvaa riviä ei ole ([nowIndex] < 0)
 * tai se on näkyvien kohteiden välillä. Indeksit ovat listan kohdeindeksejä.
 */
internal fun jumpToNowDirection(nowIndex: Int, firstVisible: Int, lastVisible: Int): JumpDirection? = when {
    nowIndex < 0 -> null
    nowIndex < firstVisible -> JumpDirection.UP
    nowIndex > lastVisible -> JumpDirection.DOWN
    else -> null
}

/** Hintarivin tekstin muste: täysin peittävä tumma, luettava koko [rowHeatColor]-asteikolla. */
internal val PriceRowInk: Color = Color(0xFF080E12)

/**
 * Hintarivin tausta päivän sisäisellä asteikolla: päivän halvin jakso [ArkiColors.priceRowCheap],
 * keskikohta [ArkiColors.priceRowNormal], kallein [ArkiColors.priceRowExpensive]. Hinnat ovat samaa
 * yksikköä ([PriceRow.scalePrice]) kuin [dayMin] ja [dayMax]. Tasainen päivä (kallein ei ylitä halvinta) on
 * halvan sävyinen.
 */
internal fun rowHeatColor(price: Double, dayMin: Double, dayMax: Double, colors: ArkiColors): Color {
    val n = if (dayMax > dayMin) ((price - dayMin) / (dayMax - dayMin)).toFloat().coerceIn(0f, 1f) else 0f
    return if (n < 0.5f) lerp(colors.priceRowCheap, colors.priceRowNormal, n / 0.5f)
    else lerp(colors.priceRowNormal, colors.priceRowExpensive, (n - 0.5f) / 0.5f)
}
