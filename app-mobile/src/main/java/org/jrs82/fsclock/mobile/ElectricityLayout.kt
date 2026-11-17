package org.jrs82.fsclock.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer

/** Fonttikoko, jota suuremmalla vierekkäiset osat (välilehdet, valitsin, laatat) asetellaan allekkain. */
internal const val LARGE_FONT_SCALE = 1.25f

/** Mahtuvatko osat yhdelle riville: leveyksien summa ja välit enintään [maxWidthPx]. */
internal fun fitsInRow(widthsPx: List<Float>, gapPx: Float, maxWidthPx: Float): Boolean =
    widthsPx.sum() + gapPx * (widthsPx.size - 1).coerceAtLeast(0) <= maxWidthPx

/** Kuten [fitsInRow], mutta osat ovat yhtä leveitä (weight 1f): jokaisen pitää mahduttaa leveimmän sisältö. */
internal fun fitsEqualColumns(widthsPx: List<Float>, gapPx: Float, maxWidthPx: Float): Boolean =
    fitsInRow(List(widthsPx.size) { widthsPx.maxOrNull() ?: 0f }, gapPx, maxWidthPx)

/** Rinnakkain vain, kun osat mahtuvat eikä fontti ole iso. */
internal fun sideBySide(fontScale: Float, fits: Boolean): Boolean = fontScale <= LARGE_FONT_SCALE && fits

/**
 * Suurin fonttikoko [maxSp]:stä alaspäin [stepSp]:n askelin, jolla teksti mahtuu [availablePx]:ään
 * ([widthAt] = tekstin leveys kyseisellä koolla). Jos mikään ei mahdu, palautetaan [minSp].
 * Korvaa Compose-version puuttuvan automaattisen tekstikoon.
 */
internal fun fitFontSizeSp(maxSp: Float, minSp: Float, stepSp: Float, availablePx: Float, widthAt: (Float) -> Float): Float {
    var size = maxSp
    while (size >= minSp) {
        if (widthAt(size) <= availablePx) return size
        size -= stepSp
    }
    return minSp
}

/** Tekstin leveys pikseleinä yhdellä rivillä annetulla tyylillä (fonttikoko huomioiden). */
@Composable
internal fun rememberTextWidthPx(text: String, style: TextStyle): Float {
    val measurer = rememberTextMeasurer()
    return remember(text, style, measurer) {
        measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toFloat()
    }
}
