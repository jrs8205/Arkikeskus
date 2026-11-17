package org.jrs82.fsclock.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.jrs82.fsclock.ElectricityData
import java.util.Locale

/**
 * Sähkösivun hintalistan rivi. [rangeText] on muotoa "08.30–08.45"; talviaikaan siirtymisen
 * toistuvan tunnin jälkimmäisellä kierroksella alkuajan perässä on tähti ("03.00*–03.15").
 * [displayPrice] on näytettävä hinta ALV-asetuksen mukaan, [rawPrice] veroton (lämpövärin asteikko).
 */
internal data class PriceRow(
    val timestamp: Long,
    val rangeText: String,
    val displayPrice: Double,
    val rawPrice: Double,
    val isNow: Boolean,
)

/**
 * Rivit näytettävistä jaksoista (vartit tai tunnit, pituus [slotMs]). [nowMs] null = ei kuluvaa jaksoa (Huomenna).
 * Jakson loppu muotoillaan alun UTC-offsetilla, jotta kellonsiirtoyön rivi näyttää jakson pituiselta:
 * syksyllä "03.00–04.00" (ei "03.00–03.00") ja keväällä "02.00–03.00" (ei "02.00–04.00").
 */
internal fun electricityPriceRows(
    slots: List<ElectricityData.Quarter>,
    slotMs: Long,
    vat: Boolean,
    nowMs: Long?,
): List<PriceRow> = slots.map { slot ->
    val (start, slotEnd) = ElectricityTime.slotBounds(slot.timestamp, slotMs)
    val end = slotEnd.toInstant().atOffset(start.offset)
    val star = if (ElectricityTime.isRepeatedPass(slot.timestamp)) "*" else ""
    val range = String.format(Locale.ROOT, "%02d.%02d", start.hour, start.minute) + star + "–" +
        String.format(Locale.ROOT, "%02d.%02d", end.hour, end.minute)
    PriceRow(
        timestamp = slot.timestamp,
        rangeText = range,
        displayPrice = ElectricityVat.apply(slot.sntPerKwh, vat),
        rawPrice = slot.sntPerKwh,
        isNow = nowMs != null && slot.timestamp <= nowMs && nowMs < slot.timestamp + slotMs,
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

/** Päivän keskihinta näytetyistä varttihinnoista (ALV vain positiivisiin). Tyhjä lista → NaN. */
internal fun electricityDayAverage(quarters: List<ElectricityData.Quarter>, vat: Boolean): Double =
    quarters.map { ElectricityVat.apply(it.sntPerKwh, vat) }.average()

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
 * yksikköä (verottomia) kuin [dayMin] ja [dayMax]. Tasainen päivä (kallein ei ylitä halvinta) on
 * halvan sävyinen.
 */
internal fun rowHeatColor(price: Double, dayMin: Double, dayMax: Double, colors: ArkiColors): Color {
    val n = if (dayMax > dayMin) ((price - dayMin) / (dayMax - dayMin)).toFloat().coerceIn(0f, 1f) else 0f
    return if (n < 0.5f) lerp(colors.priceRowCheap, colors.priceRowNormal, n / 0.5f)
    else lerp(colors.priceRowNormal, colors.priceRowExpensive, (n - 0.5f) / 0.5f)
}
