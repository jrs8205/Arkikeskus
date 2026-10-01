package org.jrs82.fsclock.mobile.widget

/**
 * Sähköwidgetin asettelun valinta launcherin ilmoittaman koon ja fonttiskaalan mukaan. Rivit pudotetaan
 * tärkeysjärjestyksessä (varttirivi → jana → otsikko), jotta sisältö ei ylitä widgetin alareunaa.
 */
internal object ElectricityWidgetLayout {
    data class Spec(
        val padV: Int,
        val gapHeader: Int,
        val gapQuarter: Int,
        val gapGauge: Int,
        val gapLabels: Int,
        val priceSp: Int,
        val showHeader: Boolean,
        val showQuarter: Boolean,
        val showGauge: Boolean,
    )

    val ROOMY = Spec(20, 14, 9, 18, 6, 44, showHeader = true, showQuarter = true, showGauge = true)
    val COMPACT = Spec(14, 8, 4, 10, 4, 44, showHeader = true, showQuarter = true, showGauge = true)
    val TIGHT = Spec(12, 6, 0, 8, 4, 36, showHeader = true, showQuarter = false, showGauge = true)
    val NO_GAUGE = Spec(12, 6, 0, 0, 0, 36, showHeader = true, showQuarter = false, showGauge = false)
    val PRICE_ONLY = Spec(8, 0, 0, 0, 0, 36, showHeader = false, showQuarter = false, showGauge = false)
    private val BY_PREFERENCE = listOf(ROOMY, COMPACT, TIGHT, NO_GAUGE)

    const val GAUGE_DP = 16
    private const val HEADER_ICON_DP = 40f

    // Tekstirivin korkeus dp:nä ≈ fonttikoko × 1,5 × fonttiskaala. Mitattu yläraja laitteilta
    // (Pixel 8a: 44 sp → 64 dp, 12 sp → 18 dp); emulaattorin Roboto jää tämän alle.
    private const val LINE_FACTOR = 1.5f

    fun neededHeight(spec: Spec, fontScale: Float): Float {
        fun line(sp: Int) = sp * LINE_FACTOR * fontScale
        var h = 2f * spec.padV + line(spec.priceSp)
        if (spec.showHeader) h += maxOf(HEADER_ICON_DP, line(14) + line(11)) + spec.gapHeader
        if (spec.showQuarter) h += spec.gapQuarter + line(12)
        if (spec.showGauge) h += spec.gapGauge + GAUGE_DP + spec.gapLabels + line(12)
        return h
    }

    fun choose(heightDp: Float, fontScale: Float): Spec =
        BY_PREFERENCE.firstOrNull { neededHeight(it, fontScale) <= heightDp } ?: PRICE_ONLY

    /** Kapeassa widgetissä tasolappu ja pitkä varttiteksti eivät mahdu otsikon ja hinnan rinnalle. */
    fun isNarrow(widthDp: Float): Boolean = widthDp < 300f

    /** Osoittimen keskikohta janan levyisessä kuvassa: sama suhde kuin sovelluksen janassa
     *  (vasen reuna = osuus × (leveys − piste)), joten piste pysyy aina kuvan sisällä. */
    fun dotCenterPx(fraction: Float, widthPx: Int, dotPx: Int): Float =
        fraction.coerceIn(0f, 1f) * (widthPx - dotPx) + dotPx / 2f
}
