@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package org.jrs82.fsclock.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.jrs82.fsclock.R

/* ---- Fontit (bundlatut variable-fontit, sama ilme kuin home_a) ---- */

private fun vw(weight: Int) = FontVariation.Settings(FontVariation.weight(weight))

val HankenGrotesk = FontFamily(
    Font(R.font.hanken_grotesk, FontWeight.Normal, variationSettings = vw(400)),
    Font(R.font.hanken_grotesk, FontWeight.Medium, variationSettings = vw(500)),
    Font(R.font.hanken_grotesk, FontWeight.SemiBold, variationSettings = vw(600)),
    Font(R.font.hanken_grotesk, FontWeight.Bold, variationSettings = vw(700)),
)

val BigShoulders = FontFamily(
    Font(R.font.big_shoulders_display, FontWeight.Medium, variationSettings = vw(500)),
    Font(R.font.big_shoulders_display, FontWeight.SemiBold, variationSettings = vw(600)),
    Font(R.font.big_shoulders_display, FontWeight.Bold, variationSettings = vw(700)),
)

/* ---- Värit (home_a-paletti) ---- */

object Ark {
    val Bg = Color(0xFF08090C)
    val BgGrad1 = Color(0xFF14202E)
    val BgGrad2 = Color(0xFF161019)
    val Panel = Color(0xFF111722)
    val Line = Color(0xFF303847)
    val TopBarStart = Color(0xFF111C2B)
    val TopBarEnd = Color(0xFF17131D)
    val TopBarLine = Color(0xFF506079)
    val BandStart = Color(0xFF121722)
    val BandEnd = Color(0xFF0C0F16)
    val SensorPanel = Color(0xFF1A1B20)
    val SensorBorder = Color(0xFF8A7445)
    val SensorLabel = Color(0xFFD8C58E)
    val Ink = Color(0xFFF8FBFF)
    val Muted = Color(0xFFB8C4D8)
    val Faint = Color(0xFF8B98AD)
    val Warm = Color(0xFFFFC23D)
    val Cold = Color(0xFF23D6FF)
    val Good = Color(0xFF1FD6A6)
    val Warn = Color(0xFFFF4D8D)
    val Accent = Color(0xFF23D6FF)
    val PriceText = Color(0xFF1C1200)
    val PriceLab = Color(0xFF241400)
    val PriceUnit = Color(0xFF301A00)
    val SourceText = Color(0xFF04221A)
    val OpenMeteoText = Color(0xFF041B22)
    val WarnMain = Color(0xFFFFD0E0)
}

/* ---- Värilogiikka (sama kuin home_a JS) ---- */

private fun lerp(a: Int, b: Int, f: Float): Int = Math.round(a + (b - a) * f.coerceIn(0f, 1f)).toFloat().toInt()

/** Lämpötilaväri: -20 syvänsininen → -5 sininen → +5 turkoosi → +15 vihreä → +25 keltainen → +35 punainen. */
fun tempColor(t: Float?): Color {
    if (t == null || t.isNaN()) return Color(0xFFAAAAAA)
    val stops = floatArrayOf(-20f, -5f, 5f, 15f, 25f, 35f)
    val cols = arrayOf(
        intArrayOf(40, 120, 255), intArrayOf(58, 160, 240), intArrayOf(80, 235, 224),
        intArrayOf(123, 230, 120), intArrayOf(255, 210, 90), intArrayOf(255, 95, 110)
    )
    if (t <= stops[0]) return rgb(cols[0])
    if (t >= stops[5]) return rgb(cols[5])
    for (i in 1 until 6) {
        if (t <= stops[i]) {
            val f = (t - stops[i - 1]) / (stops[i] - stops[i - 1])
            val a = cols[i - 1]; val b = cols[i]
            return Color(lerp(a[0], b[0], f), lerp(a[1], b[1], f), lerp(a[2], b[2], f))
        }
    }
    return rgb(cols[5])
}

/** Sähkön hinta: matala vihreä → korkea punainen (pillerin tausta). */
fun priceColor(snt: Float): Color {
    fun c(r: Int, g: Int, b: Int) = Color(r, g, b)
    return when {
        snt <= 5f -> c(0x5F, 0xD0, 0x8A)
        snt <= 10f -> c(lerp(0x5F, 0xFF, (snt - 5) / 5), lerp(0xD0, 0xC2, (snt - 5) / 5), lerp(0x8A, 0x3D, (snt - 5) / 5))
        snt <= 15f -> c(lerp(0xFF, 0xFF, (snt - 10) / 5), lerp(0xC2, 0x9A, (snt - 10) / 5), lerp(0x3D, 0x2E, (snt - 10) / 5))
        snt <= 20f -> c(lerp(0xFF, 0xFF, (snt - 15) / 5), lerp(0x9A, 0x5C, (snt - 15) / 5), lerp(0x2E, 0x5C, (snt - 15) / 5))
        else -> c(0xFF, 0x5C, 0x5C)
    }
}

/** WiFi-palkin väri tasolle 1..5: punainen → keltainen → vihreä. */
fun wifiColor(level: Int): Color {
    val t = (level - 1) / 4f
    return if (t <= 0.5f)
        Color(lerp(0xEE, 0xE6, t / 0.5f), lerp(0x44, 0xC2, t / 0.5f), lerp(0x44, 0x00, t / 0.5f))
    else
        Color(lerp(0xE6, 0x5F, (t - 0.5f) / 0.5f), lerp(0xC2, 0xD0, (t - 0.5f) / 0.5f), lerp(0x00, 0x8A, (t - 0.5f) / 0.5f))
}

private fun rgb(c: IntArray) = Color(c[0], c[1], c[2])
