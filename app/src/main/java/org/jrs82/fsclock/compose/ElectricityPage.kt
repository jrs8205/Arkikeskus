package org.jrs82.fsclock.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/* ---------------- Sähkö-sivu: tänään/huomenna, tiivistelmä, graafi, varttilista ---------------- */

@Composable
fun ElectricityPage(ui: HomeUi, s: Scale) {
    var showTomorrow by remember { mutableStateOf(false) }
    val day = (if (showTomorrow) ui.elTomorrow else ui.elToday) ?: ui.elToday
    Column(Modifier.fillMaxSize().padding(horizontal = s.dw(2.6f), vertical = s.dh(2f))) {
        // Tänään / Huomenna + nyt-hinta
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DayToggle("Tänään", !showTomorrow, s) { showTomorrow = false }
            Spacer(Modifier.width(s.dw(1f)))
            DayToggle("Huomenna", showTomorrow, s, enabled = ui.elTomorrow != null) { if (ui.elTomorrow != null) showTomorrow = true }
            Spacer(Modifier.weight(1f))
            if (ui.priceSnt != null) {
                Text("NYT", color = Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.2f))
                Spacer(Modifier.width(s.dw(1f)))
                Text(fi(ui.priceSnt, 3), color = priceColor(ui.priceSnt), fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(5f))
                Spacer(Modifier.width(s.dw(0.6f)))
                Text("snt/kWh", color = Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(2.2f))
            }
        }
        Spacer(Modifier.height(s.dh(2f)))
        if (day == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (showTomorrow) "Huomisen hinnat eivät vielä saatavilla" else "Hintoja ei saatavilla", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(3f))
            }
            return
        }
        // Tiivistelmäkortit
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s.dw(2f))) {
            SummaryCard("HALVIN", fi(day.min, 3), day.minAt, Ark.Good, s, Modifier.weight(1f))
            SummaryCard("KALLEIN", fi(day.max, 3), day.maxAt, Color(0xFFFF5C5C), s, Modifier.weight(1f))
            SummaryCard("KESKIARVO", fi(day.avg, 3), "snt/kWh", Ark.Accent, s, Modifier.weight(1f))
        }
        Spacer(Modifier.height(s.dh(2f)))
        // Pylväsgraafi
        PriceBars(day.quarters, s, Modifier.fillMaxWidth().height(s.dh(22f)))
        Spacer(Modifier.height(s.dh(1.6f)))
        // Varttilista
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(s.dh(0.5f))) {
            for (q in day.quarters) QuarterRow(q, day.max, s)
        }
    }
}

@Composable
private fun DayToggle(label: String, active: Boolean, s: Scale, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (active) Ark.Accent else Ark.SensorPanel
    val fg = when { !enabled -> Ark.Faint.copy(alpha = 0.5f); active -> Color(0xFF06222B); else -> Ark.Muted }
    Box(
        Modifier.clickable(enabled = enabled, onClick = onClick).background(bg, RoundedCornerShape(12.dp)).padding(horizontal = s.dw(2.4f), vertical = s.dh(1.1f)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.5f), maxLines = 1)
    }
}

@Composable
private fun SummaryCard(label: String, value: String, sub: String, color: Color, s: Scale, modifier: Modifier) {
    Column(
        modifier.background(Ark.Panel, RoundedCornerShape(16.dp)).border(s.dh(0.16f), Ark.Line, RoundedCornerShape(16.dp)).padding(horizontal = s.dw(1.8f), vertical = s.dh(1.6f)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2f))
        Spacer(Modifier.height(s.dh(0.6f)))
        Text(value, color = color, fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(5.4f), maxLines = 1)
        Spacer(Modifier.height(s.dh(0.3f)))
        Text(sub, color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(1.9f), maxLines = 1)
    }
}

@Composable
private fun PriceBars(quarters: List<QuarterUi>, s: Scale, modifier: Modifier) {
    if (quarters.isEmpty()) return
    val maxV = quarters.maxOf { it.snt }.coerceAtLeast(0.001f)
    val minV = minOf(0f, quarters.minOf { it.snt })
    val span = (maxV - minV).coerceAtLeast(0.001f)
    Box(modifier.background(Ark.Panel, RoundedCornerShape(14.dp)).padding(s.dw(1.2f))) {
        Canvas(Modifier.fillMaxSize()) {
            val n = quarters.size
            val gap = size.width * 0.0015f
            val bw = (size.width - gap * (n - 1)) / n
            val zeroY = size.height * (maxV / span)
            quarters.forEachIndexed { i, q ->
                val x = i * (bw + gap)
                val barTop = size.height * ((maxV - q.snt) / span)
                val top = minOf(barTop, zeroY)
                val h = kotlin.math.abs(zeroY - barTop).coerceAtLeast(size.height * 0.004f)
                val c = priceColor(q.snt)
                drawRect(if (q.isNow) Color.White else c, topLeft = Offset(x, top), size = Size(bw.coerceAtLeast(1f), h))
                if (q.isNow) drawRect(c, topLeft = Offset(x, top), size = Size(bw.coerceAtLeast(1f), h * 0.7f))
            }
        }
    }
}

@Composable
private fun QuarterRow(q: QuarterUi, maxV: Float, s: Scale) {
    val bg = if (q.isNow) Ark.Accent.copy(alpha = 0.18f) else Ark.SensorPanel.copy(alpha = 0.5f)
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(9.dp)).padding(horizontal = s.dw(1.4f), vertical = s.dh(0.7f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(q.label, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = if (q.isNow) FontWeight.Bold else FontWeight.Medium, fontSize = s.sh(2.3f), maxLines = 1, modifier = Modifier.width(s.dw(7f)))
        Spacer(Modifier.width(s.dw(1.4f)))
        // Pieni palkki
        Box(Modifier.weight(1f).height(s.dh(2.2f)).background(Ark.Bg, RoundedCornerShape(4.dp))) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth((q.snt / maxV.coerceAtLeast(0.001f)).coerceIn(0.02f, 1f))
                    .background(priceColor(q.snt), RoundedCornerShape(4.dp))
            )
        }
        Spacer(Modifier.width(s.dw(1.4f)))
        Text(fi(q.snt, 3), color = priceColor(q.snt), fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.4f), maxLines = 1, modifier = Modifier.width(s.dw(7f)))
    }
}
