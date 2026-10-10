package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.annotation.StringRes
import org.jrs82.fsclock.FmiCounties
import org.jrs82.fsclock.R
import org.jrs82.fsclock.WeatherWarning
import org.jrs82.fsclock.WeatherWarning.AwarenessType
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Säävaroitusten näyttöapurit. Puhtaat funktiot palauttavat resurssi-id:n tai [WarningUiText]-rakenteen
 *  → yksikkötestattavissa ilman Composea; tekstiksi vasta UI:ssa [WarningUiText.resolve]-kutsulla. */

private val HELSINKI_WARN: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

/** Näytettävä teksti: resurssi + argumentit tai syötteestä tullut valmis teksti. */
internal sealed class WarningUiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : WarningUiText()
    data class Raw(val text: String) : WarningUiText()

    fun resolve(context: Context): String = when (this) {
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
        is Raw -> text
    }
}

/** Ilmiötyyppi → drawable-ikoni. Tuntemattomille varoituskolmio. */
internal fun awarenessIconRes(type: AwarenessType): Int = when (type) {
    AwarenessType.WIND -> R.drawable.mobile_ic_wind_24
    AwarenessType.THUNDERSTORM -> R.drawable.mobile_ic_wx_thunder
    AwarenessType.SNOW_ICE, AwarenessType.LOW_TEMPERATURE -> R.drawable.mobile_ic_wx_snow
    AwarenessType.RAIN, AwarenessType.FLOOD -> R.drawable.mobile_ic_rain_24
    AwarenessType.FOG -> R.drawable.mobile_ic_wx_fog
    AwarenessType.HIGH_TEMPERATURE, AwarenessType.UV -> R.drawable.mobile_ic_wx_hot
    AwarenessType.FOREST_FIRE -> R.drawable.mobile_ic_wx_fire
    else -> R.drawable.mobile_ic_warning_24
}

/** CAP-vakavuus → nimi, null jos tuntematon. */
@StringRes
internal fun severityRes(raw: String?): Int? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
    "minor" -> R.string.warning_severity_minor
    "moderate" -> R.string.warning_severity_moderate
    "severe" -> R.string.warning_severity_severe
    "extreme" -> R.string.warning_severity_extreme
    else -> null
}

@StringRes
internal fun certaintyRes(raw: String?): Int? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
    "observed" -> R.string.warning_certainty_observed
    "likely" -> R.string.warning_certainty_likely
    "possible" -> R.string.warning_certainty_possible
    "unlikely" -> R.string.warning_certainty_unlikely
    else -> null
}

@StringRes
internal fun urgencyRes(raw: String?): Int? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
    "immediate" -> R.string.warning_urgency_immediate
    "expected" -> R.string.warning_urgency_expected
    "future" -> R.string.warning_urgency_future
    "past" -> R.string.warning_urgency_past
    else -> null
}

/** "Alkaen X" / "Voimassa asti Y" / "X – Y" / null — Suomen aikavyöhyke, [pattern] kielen mukaan. */
internal fun warningPeriod(onsetMs: Long, expiresMs: Long, pattern: String, locale: Locale): WarningUiText? {
    if (onsetMs <= 0L && expiresMs <= 0L) return null
    val fmt = SimpleDateFormat(pattern, locale)
    fmt.timeZone = HELSINKI_WARN
    return when {
        onsetMs <= 0L -> WarningUiText.Res(R.string.warning_period_until, listOf(fmt.format(Date(expiresMs))))
        expiresMs <= 0L -> WarningUiText.Res(R.string.warning_period_from, listOf(fmt.format(Date(onsetMs))))
        else -> WarningUiText.Raw(fmt.format(Date(onsetMs)) + " – " + fmt.format(Date(expiresMs)))
    }
}

/** Fyysinen arvo tekstinä: luku kielen desimaalierottimella, kokonaisluku ilman desimaaleja. */
internal fun formatPhysicalValue(value: Double, locale: Locale): String =
    if (value == Math.rint(value) && !value.isInfinite()) value.toLong().toString()
    else DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale)).format(value)

/** FMI:n fyysinen arvo yksikön (ja pakkasella tyypin) mukaan; null jos arvoa ei ole. */
internal fun physicalText(w: WeatherWarning, locale: Locale): WarningUiText? {
    val d = w.details
    if (!d.hasPhysical()) return null
    val v = formatPhysicalValue(d.physicalValue, locale)
    val res = when (d.physicalUnit) {
        "celsius" -> if (w.awarenessType == AwarenessType.LOW_TEMPERATURE) R.string.warning_physical_cold
            else R.string.warning_physical_temperature
        "mm/h" -> R.string.warning_physical_rain
        "m/s" -> R.string.warning_physical_gust
        "index" -> R.string.warning_physical_uv
        else -> return WarningUiText.Raw(v + " " + d.physicalUnit)
    }
    return WarningUiText.Res(res, listOf(v))
}

/** Toteutumis-todennäköisyys, null jos ei tiedossa. */
internal fun probabilityText(probabilityPct: Int): WarningUiText? =
    if (probabilityPct >= 0) WarningUiText.Res(R.string.warning_probability, listOf(probabilityPct)) else null

/**
 * Alueteksti näyttökielellä: pilkulla erotetuista osista tunnetut maakunnat käännetään [nameOf]-funktiolla,
 * muut (kunnat, merialueet, osa-alueet) jäävät syötteen muotoon. Konekäyttö (suodatus, kotipaikan osuma)
 * tehdään aina alkuperäisellä suomenkielisellä [areaDesc]:lla.
 */
internal fun localizeAreaDesc(areaDesc: String, nameOf: (String) -> String?): String {
    if (areaDesc.isEmpty()) return ""
    return areaDesc.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(", ") { part -> nameOf(part) ?: part }
}

/** Maakunnan näyttönimi; tuntematon nimi palautetaan sellaisenaan. */
internal fun regionDisplayName(context: Context, region: String): String {
    val res = FmiCounties.nameRes(region)
    return if (res != 0) context.getString(res) else region
}

internal fun localizedAreaDesc(context: Context, areaDesc: String): String =
    localizeAreaDesc(areaDesc) { part -> FmiCounties.nameRes(part).takeIf { it != 0 }?.let(context::getString) }
