package org.jrs82.fsclock.compose

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/* ---------------- 7 vrk -ennuste: päivävalitsin + FMI/OM tunnit ---------------- */

@Composable
fun ForecastPage(ui: HomeUi, s: Scale) {
    val days = ui.forecast
    var sel by remember(days.size) { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = s.dw(2.6f), vertical = s.dh(2f))) {
        if (days.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ennustetta ei vielä saatavilla", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(3f))
            }
            return
        }
        // Päivävalitsin
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s.dw(1.2f))) {
            days.forEachIndexed { i, d ->
                DayChip(d.label, i == sel, s, Modifier.weight(1f)) { sel = i }
            }
        }
        Spacer(Modifier.height(s.dh(2f)))
        val day = days[sel.coerceIn(0, days.size - 1)]
        // Lähde-otsikot
        Row(Modifier.fillMaxWidth()) {
            SourceTag("Ilmatieteen laitos", Ark.Good, Ark.SourceText, s, Modifier.weight(1f))
            Spacer(Modifier.width(s.dw(2f)))
            SourceTag("Open-Meteo", Ark.Cold, Ark.OpenMeteoText, s, Modifier.weight(1f))
        }
        Spacer(Modifier.height(s.dh(1.2f)))
        // Tuntirivit (jaettu vieritys, FMI vasen / OM oikea)
        val scroll = rememberScrollState()
        Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(s.dh(0.5f))) {
                for (h in day.hours) FmiHourRow(h, day.fmi[h], s)
            }
            Spacer(Modifier.width(s.dw(2f)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(s.dh(0.5f))) {
                for (h in day.hours) OmHourRow(h, day.om[h], s)
            }
        }
    }
}

@Composable
private fun DayChip(label: String, active: Boolean, s: Scale, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (active) Ark.Accent else Ark.SensorPanel
    val fg = if (active) Color(0xFF06222B) else Ark.Muted
    Box(
        modifier.clickable(onClick = onClick).background(bg, RoundedCornerShape(12.dp)).padding(vertical = s.dh(1.2f)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.3f), maxLines = 1)
    }
}

@Composable
private fun SourceTag(text: String, bg: Color, fg: Color, s: Scale, modifier: Modifier) {
    Box(modifier) {
        Text(
            text, color = fg, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2f),
            modifier = Modifier.background(bg, RoundedCornerShape(7.dp)).padding(horizontal = s.dw(1f), vertical = s.dh(0.5f))
        )
    }
}

@Composable
private fun FmiHourRow(hour: Int, row: HourRowUi?, s: Scale) {
    Row(
        Modifier.fillMaxWidth().background(Ark.SensorPanel.copy(alpha = 0.5f), RoundedCornerShape(9.dp)).padding(horizontal = s.dw(1.2f), vertical = s.dh(0.7f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HourLabel(hour, s)
        WxIcon(row, s)
        Spacer(Modifier.width(s.dw(0.8f)))
        Text(fiUnit(row?.temp, 0, "°"), color = tempColor(row?.temp), fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(3.4f), maxLines = 1, modifier = Modifier.width(s.dw(6f)))
        Spacer(Modifier.weight(1f))
        Text(windText(row?.wind), color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(2f), maxLines = 1)
        Spacer(Modifier.width(s.dw(1.2f)))
        Text(rainText(row?.precip), color = Ark.Cold, fontFamily = HankenGrotesk, fontSize = s.sh(2f), maxLines = 1)
    }
}

@Composable
private fun OmHourRow(hour: Int, row: HourRowUi?, s: Scale) {
    Row(
        Modifier.fillMaxWidth().background(Ark.SensorPanel.copy(alpha = 0.5f), RoundedCornerShape(9.dp)).padding(horizontal = s.dw(1.2f), vertical = s.dh(0.7f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HourLabel(hour, s)
        WxIcon(row, s)
        Spacer(Modifier.width(s.dw(0.8f)))
        Text(fiUnit(row?.temp, 0, "°"), color = tempColor(row?.temp), fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(3.4f), maxLines = 1, modifier = Modifier.width(s.dw(6f)))
        Spacer(Modifier.weight(1f))
        Text(humText(row?.hum), color = Color(0xFF5AC8FF), fontFamily = HankenGrotesk, fontSize = s.sh(2f), maxLines = 1)
        Spacer(Modifier.width(s.dw(1f)))
        Text(windText(row?.wind), color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(2f), maxLines = 1)
        Spacer(Modifier.width(s.dw(1f)))
        Text(rainText(row?.precip), color = Ark.Cold, fontFamily = HankenGrotesk, fontSize = s.sh(2f), maxLines = 1)
    }
}

@Composable
private fun HourLabel(hour: Int, s: Scale) {
    Text(String.format("%02d", hour), color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.3f), maxLines = 1, modifier = Modifier.width(s.dw(3.4f)))
}

@Composable
private fun WxIcon(row: HourRowUi?, s: Scale) {
    Spacer(Modifier.width(s.dw(0.6f)))
    if (row == null) {
        // Tunnilta ei dataa → ei ikonia (mapCondition(null) piirtäisi harhaanjohtavan pilven).
        Spacer(Modifier.size(s.dh(3.6f)))
    } else {
        AndroidView(
            factory = { org.jrs82.fsclock.WeatherIconView(it) },
            update = { v -> v.setCondition(row.condition) },
            modifier = Modifier.size(s.dh(3.6f))
        )
    }
}

private fun windText(w: Float?): String = if (w == null || w.isNaN()) "" else "💨 " + fi(w, 0) + " m/s"
private fun rainText(p: Float?): String = if (p == null || p.isNaN() || p < 0.05f) "" else "☔ " + fi(p, 1) + " mm"
private fun humText(h: Float?): String = if (h == null || h.isNaN()) "" else "💧 " + fi(h, 0) + " %"
