package org.jrs82.fsclock.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/* ---------------- Tietoja-sivu: aurinko + kuu + säävaroitukset ---------------- */

@Composable
fun InfoPage(ui: HomeUi, s: Scale) {
    Row(Modifier.fillMaxSize().padding(horizontal = s.dw(3f), vertical = s.dh(3f))) {
        // Vasen: aurinko + kuu
        Column(Modifier.weight(1.05f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(s.dh(3f))) {
            SunCard(ui, s, Modifier.weight(1.25f))
            MoonCard(ui, s, Modifier.weight(1f))
        }
        Spacer(Modifier.width(s.dw(3f)))
        // Oikea: säävaroitukset
        WarningsCard(ui, s, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun SunCard(ui: HomeUi, s: Scale, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().background(Ark.Panel, RoundedCornerShape(20.dp))
            .border(s.dh(0.16f), Ark.Line, RoundedCornerShape(20.dp)).padding(s.dw(2.4f))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("☀", color = Ark.Warm, fontSize = s.sh(4.2f))
            Spacer(Modifier.width(s.dw(1.2f)))
            Text("Aurinko", color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(3.4f))
        }
        Spacer(Modifier.height(s.dh(1.6f)))
        // Aurinkokaari
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
            SunArc(ui.sunriseMin, ui.sunsetMin, s, Modifier.fillMaxWidth().fillMaxHeight())
        }
        Spacer(Modifier.height(s.dh(1.6f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SunStat("Nousu", ui.sunRise, Ark.Warm, s)
            SunStat("Päivän pituus", ui.dayLen, Ark.Ink, s)
            SunStat("Lasku", ui.sunSet, Ark.Cold, s)
        }
    }
}

@Composable
private fun SunStat(label: String, value: String, color: Color, s: Scale) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = s.sh(2.1f))
        Spacer(Modifier.height(s.dh(0.4f)))
        Text(value, color = color, fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(4.4f), maxLines = 1)
    }
}

/** Puoliympyräkaari, jolla aurinko liikkuu nousun ja laskun välillä nykyhetken mukaan. */
@Composable
private fun SunArc(sunriseMin: Int, sunsetMin: Int, s: Scale, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val baseY = h * 0.94f
        val cx = w / 2f
        val rx = w * 0.44f
        val ry = h * 0.78f
        // Maa-viiva
        drawLine(Ark.Line, Offset(w * 0.04f, baseY), Offset(w * 0.96f, baseY), strokeWidth = h * 0.012f, cap = StrokeCap.Round)
        // Kaari (katkoviivamainen, piirretään segmentteinä)
        val steps = 48
        var prev: Offset? = null
        for (i in 0..steps) {
            val frac = i / steps.toFloat()
            val ang = Math.PI * (1.0 - frac) // 180° → 0°
            val x = cx + (rx * cos(ang)).toFloat()
            val y = baseY - (ry * sin(ang)).toFloat()
            val p = Offset(x, y)
            if (prev != null && i % 2 == 0) {
                drawLine(Ark.Faint.copy(alpha = 0.5f), prev!!, p, strokeWidth = h * 0.01f, cap = StrokeCap.Round)
            }
            prev = p
        }
        // Auringon nykysijainti
        if (sunriseMin in 0..1440 && sunsetMin in 0..1440 && sunsetMin > sunriseMin) {
            val nowMin = nowMinutes()
            val frac = ((nowMin - sunriseMin).toFloat() / (sunsetMin - sunriseMin)).coerceIn(0f, 1f)
            val ang = Math.PI * (1.0 - frac)
            val x = cx + (rx * cos(ang)).toFloat()
            val y = baseY - (ry * sin(ang)).toFloat()
            val up = nowMin in sunriseMin..sunsetMin
            val col = if (up) Ark.Warm else Ark.Faint
            drawCircle(col.copy(alpha = 0.25f), radius = h * 0.11f, center = Offset(x, y))
            drawCircle(col, radius = h * 0.07f, center = Offset(x, y))
        }
    }
}

@Composable
private fun MoonCard(ui: HomeUi, s: Scale, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().background(Ark.Panel, RoundedCornerShape(20.dp))
            .border(s.dh(0.16f), Ark.Line, RoundedCornerShape(20.dp)).padding(s.dw(2.4f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MoonGlyph(ui.moonPhase, ui.moonIllum, Modifier.size(s.dh(13f)))
        Spacer(Modifier.width(s.dw(2.4f)))
        Column {
            Text("Kuu", color = Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = s.sh(2.3f))
            Spacer(Modifier.height(s.dh(0.4f)))
            Text(
                ui.moonLabel.ifEmpty { "—" }.replaceFirstChar { it.uppercase() },
                color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(3.4f), maxLines = 1
            )
            if (ui.moonIllum >= 0) {
                Spacer(Modifier.height(s.dh(0.6f)))
                Text("Valaistus ${ui.moonIllum} %", color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(2.3f))
            }
        }
    }
}

/** Kuun vaihe: oikea terminaattori — valaistu puolikas + ellipsi keskellä.
 *  Vaihe p: 0 = uusikuu, 0.25 = ens. neljännes (valo oikealla), 0.5 = täysikuu,
 *  0.75 = viim. neljännes (valo vasemmalla), kuten pohjoisella pallonpuoliskolla. */
@Composable
private fun MoonGlyph(phase: Float, illum: Int, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val lit = Color(0xFFEBE8C8)
        val dark = Color(0xFF1B2330)
        drawCircle(dark, radius = r, center = center)
        if (phase in 0f..1f) {
            val p = phase
            val waxing = p < 0.5f
            val k = cos(2.0 * Math.PI * p).toFloat()   // 1=uusi, 0=neljännes, -1=täysi
            val litFraction = (1f - k) / 2f
            val ew = abs(k) * r                          // terminaattoriellipsin puolileveys
            val circle = Path().apply { addOval(Rect(center.x - r, center.y - r, center.x + r, center.y + r)) }
            clipPath(circle) {
                // Valaistu puolikas: kasvava kuu valaisee oikean, vähenevä vasemman.
                val halfLeft = if (waxing) center.x else center.x - r
                drawRect(lit, topLeft = Offset(halfLeft, center.y - r), size = Size(r, 2f * r))
                // Terminaattoriellipsi: gibbous-vaiheessa valaistu, sirpissä tumma.
                drawOval(
                    if (litFraction > 0.5f) lit else dark,
                    topLeft = Offset(center.x - ew, center.y - r),
                    size = Size(2f * ew, 2f * r)
                )
            }
        } else if (illum >= 0) {
            // Vaihetta ei tiedossa mutta valaistus on — näytä tasainen kiekko.
            drawCircle(lit.copy(alpha = (illum / 100f).coerceIn(0.1f, 1f)), radius = r, center = center)
        }
        // kehä
        drawCircle(Ark.Faint.copy(alpha = 0.5f), radius = r, center = center, style = Stroke(width = r * 0.05f))
    }
}

@Composable
private fun WarningsCard(ui: HomeUi, s: Scale, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().background(Ark.Panel, RoundedCornerShape(20.dp))
            .border(s.dh(0.16f), Ark.Line, RoundedCornerShape(20.dp)).padding(s.dw(2.2f))
    ) {
        Text("⚠ SÄÄVAROITUKSET", color = Ark.Warn, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.4f), maxLines = 1)
        Spacer(Modifier.height(s.dh(1.4f)))
        val scroll = rememberScrollState()
        var lastTouchMs by remember { mutableStateOf(0L) }
        // Automaattinen vieritys (asetus), jos sisältö ei mahdu. Kosketus tauottaa
        // pyörityksen 15 sekunniksi, ja kesto lasketaan Long-aritmetiikalla
        // (maxValue voi olla Int.MAX_VALUE ennen ensimmäistä mittausta).
        LaunchedEffect(ui.warnAutoScroll, ui.warnings.size) {
            if (!ui.warnAutoScroll) return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(2500)
                val max = scroll.maxValue
                if (max <= 0 || max == Int.MAX_VALUE) continue
                if (System.currentTimeMillis() - lastTouchMs < TOUCH_PAUSE_MS) continue
                scroll.animateScrollTo(max, tween((max.toLong() * 14L).coerceIn(1500L, 9000L).toInt()))
                kotlinx.coroutines.delay(2500)
                if (System.currentTimeMillis() - lastTouchMs < TOUCH_PAUSE_MS) continue
                scroll.animateScrollTo(0, tween(1200))
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            lastTouchMs = System.currentTimeMillis()
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(s.dh(1.4f))
        ) {
            if (ui.warnings.isEmpty()) {
                Text("Ei voimassa olevia varoituksia", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(2.4f))
            }
            for (w in ui.warnings) WarningRow(w, s)
        }
    }
}

@Composable
private fun WarningRow(w: WarnUi, s: Scale) {
    Row(
        Modifier.fillMaxWidth().background(Ark.SensorPanel, RoundedCornerShape(12.dp)).padding(s.dw(1.6f)),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.padding(top = s.dh(0.5f)).size(s.dw(1.1f)).height(s.dh(4f)).background(w.color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(s.dw(1.4f)))
        Column(Modifier.weight(1f)) {
            Text(w.main, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.6f))
            if (w.area.isNotEmpty()) {
                Spacer(Modifier.height(s.dh(0.4f)))
                Text(w.area, color = Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = s.sh(2.1f))
            }
            if (w.validity.isNotEmpty()) {
                Spacer(Modifier.height(s.dh(0.3f)))
                Text(w.validity, color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(1.9f))
            }
            if (w.description.isNotEmpty()) {
                Spacer(Modifier.height(s.dh(0.6f)))
                Text(w.description, color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(2.0f))
            }
        }
    }
}

/** Kosketuksen jälkeinen tauko automaattipyöritykselle. */
private const val TOUCH_PAUSE_MS = 15_000L

private fun nowMinutes(): Int {
    val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Helsinki"))
    return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
}

private fun tween(durationMillis: Int): androidx.compose.animation.core.TweenSpec<Float> =
    androidx.compose.animation.core.tween(durationMillis = durationMillis, easing = androidx.compose.animation.core.LinearEasing)
