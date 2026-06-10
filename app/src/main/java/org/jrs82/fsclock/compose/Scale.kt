package org.jrs82.fsclock.compose

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/** cqh/cqw-tyylinen skaalaus: dh/dw ovat % lavan korkeudesta/leveydestä,
 *  sh on lavan mukaan skaalattu fonttikoko, ds on fonttiyksikköön sidottu Dp
 *  (kiinteäleveyksisiä numeropaikkoja varten — pysyy samassa suhteessa fonttiin). */
class Scale(
    val chPx: Float,
    val cwPx: Float,
    private val textPx: Float,
    val density: Density,
) {
    fun dh(n: Float): Dp = with(density) { (chPx * n).toDp() }
    fun dw(n: Float): Dp = with(density) { (cwPx * n).toDp() }
    fun sh(n: Float): TextUnit = with(density) { (textPx * n).toSp() }
    fun ds(n: Float): Dp = with(density) { (textPx * n).toDp() }
}
