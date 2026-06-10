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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/* ---------------- Kellon teksti (live, sekuntitarkkuus) ---------------- */

private data class ClockText(val time: String, val sec: String, val date: String)

private val helsinkiZone: ZoneId = ZoneId.of("Europe/Helsinki")
private val fiLocale = Locale.Builder().setLanguage("fi").setRegion("FI").build()
private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", fiLocale)
private val secondFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("ss", fiLocale)
private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d.M.yyyy", fiLocale)

@Composable
private fun rememberClockText(): ClockText {
    var now by remember { mutableStateOf(ZonedDateTime.now(helsinkiZone)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L - System.currentTimeMillis() % 1000L)
            now = ZonedDateTime.now(helsinkiZone)
        }
    }
    return ClockText(
        time = now.format(timeFormatter),
        sec = now.format(secondFormatter),
        date = now.format(dateFormatter),
    )
}

/* ---------------- Runko: yläpalkki + sivu ---------------- */

@Composable
fun HomeScreen(
    ui: HomeUi,
    page: Page,
    onPage: (Page) -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> },
    onBrightnessChanged: () -> Unit = {},
    ensureBleScan: () -> Boolean = { true },
    onSensorsChanged: () -> Unit = {},
) {
    var renaming by remember { mutableStateOf<SensorUi?>(null) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(Ark.BgGrad1.copy(alpha = 0.76f), Ark.Bg, Ark.BgGrad2.copy(alpha = 0.68f))
            )
        )
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val heightPx = constraints.maxHeight / 100f
            val widthPx = constraints.maxWidth / 100f
            val textPx = minOf(heightPx, constraints.maxWidth / 150f)
            val s = Scale(heightPx, widthPx, textPx, LocalDensity.current)
            Column(Modifier.fillMaxSize()) {
                TopBar(ui, s, page, onPage)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (page) {
                        Page.HOME -> HomePage(ui, s) { renaming = it }
                        Page.INFO -> InfoPage(ui, s)
                        Page.FORECAST -> ForecastPage(ui, s)
                        Page.ELECTRICITY -> ElectricityPage(ui, s)
                        Page.SETTINGS -> SettingsPage(
                            s = s,
                            onOpenHistory = { onPage(Page.HISTORY) },
                            onBrightnessChanged = onBrightnessChanged,
                            ensureBleScan = ensureBleScan,
                            onSensorsChanged = onSensorsChanged,
                        )
                        Page.HISTORY -> HistoryPage(s)
                    }
                }
            }
        }
        // Yön punasävy: lämmin suodatin koko ruudun päälle (ei nappaa kosketuksia).
        if (ui.redTint) {
            Box(Modifier.fillMaxSize().background(Color(0x4DFF2A00)))
        }
    }
    renaming?.let { sensor -> RenameDialog(sensor, onRename) { renaming = null } }
}

/* ---------------- Yläpalkki ---------------- */

@Composable
private fun TopBar(ui: HomeUi, s: Scale, page: Page, onPage: (Page) -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(s.dh(11f)).background(
            Brush.horizontalGradient(listOf(Ark.TopBarStart, Ark.TopBarEnd))
        )
    ) {
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = s.dw(2.4f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Paikka kahdella rivillä (kaupunki / kaupunginosa)
            Icon(Icons.Filled.Place, null, tint = Ark.Accent, modifier = Modifier.size(s.dh(3.2f)))
            Spacer(Modifier.width(s.dw(0.8f)))
            Column {
                Text(ui.city, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(2.7f), maxLines = 1)
                if (ui.district.isNotEmpty()) {
                    Text(ui.district, color = Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = s.sh(2.1f), maxLines = 1)
                }
            }
            Spacer(Modifier.width(s.dw(2.4f)))
            // WiFi-palkit + nopeus/kaista kahdella rivillä
            WifiBars(ui.wifiLevel, s)
            Spacer(Modifier.width(s.dw(0.8f)))
            Column {
                Text(if (ui.wifiMbps > 0) "${ui.wifiMbps} Mb" else "–", color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(1.9f), maxLines = 1)
                Text(ui.wifiBand, color = Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = s.sh(1.9f), maxLines = 1)
            }
            Spacer(Modifier.width(s.dw(1.6f)))
            BatteryGlyph(ui.battPct, ui.battCharging, s)

            Spacer(Modifier.weight(1f))

            PricePill(ui.priceSnt, s) { onPage(Page.ELECTRICITY) }
            Spacer(Modifier.width(s.dw(1.4f)))
            NavButton("Koti", page == Page.HOME, s, Icons.Filled.Home) { onPage(Page.HOME) }
            NavButton("Tiedot", page == Page.INFO, s, Icons.Filled.Info) { onPage(Page.INFO) }
            NavButton("7 vrk", page == Page.FORECAST, s, null) { onPage(Page.FORECAST) }
            NavButton("Sähkö", page == Page.ELECTRICITY, s, null) { onPage(Page.ELECTRICITY) }
            Spacer(Modifier.width(s.dw(0.4f)))
            // Iso tap-target (48dp-luokka) — pelkkä 3.4dh-ikoni oli liian pieni sormelle.
            Box(
                Modifier.size(s.dh(5.6f)).clickable { onPage(Page.SETTINGS) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Settings, "Asetukset",
                    tint = if (page == Page.SETTINGS || page == Page.HISTORY) Ark.Accent else Ark.Faint,
                    modifier = Modifier.size(s.dh(3.4f))
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(s.dh(0.14f)).background(Ark.TopBarLine))
    }
}

@Composable
private fun WifiBars(level: Int, s: Scale) {
    val heights = floatArrayOf(0.34f, 0.52f, 0.70f, 0.86f, 1.0f)
    val col = wifiColor(if (level < 1) 1 else level)
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 5) {
            Box(
                Modifier.width(s.dw(0.55f)).height(s.dh(2.8f * heights[i]))
                    .background(if (i < level) col else Ark.Faint.copy(alpha = 0.4f), RoundedCornerShape(1.dp))
            )
            if (i < 4) Spacer(Modifier.width(s.dw(0.35f)))
        }
    }
}

@Composable
private fun BatteryGlyph(pct: Int, charging: Boolean, s: Scale) {
    val fillCol = when { pct <= 15 -> Color(0xFFFF5C5C); pct <= 35 -> Ark.Warm; else -> Ark.Good }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(s.dw(2.6f)).height(s.dh(2.0f)).border(s.dh(0.18f), Ark.Muted, RoundedCornerShape(2.dp)).padding(s.dh(0.3f)),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(pct.coerceIn(0, 100) / 100f).background(fillCol, RoundedCornerShape(1.dp)))
        }
        Spacer(Modifier.width(s.dw(0.6f)))
        if (charging) {
            Text("⚡", color = Ark.Good, fontSize = s.sh(2f))
            Spacer(Modifier.width(s.dw(0.3f)))
        }
        Text("$pct %", color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2f), maxLines = 1)
    }
}

@Composable
private fun PricePill(snt: Float?, s: Scale, onClick: () -> Unit) {
    val bg = if (snt != null) priceColor(snt) else Ark.Warm
    Row(
        Modifier.clickable(onClick = onClick).background(bg, RoundedCornerShape(999.dp)).padding(horizontal = s.dw(1.4f), vertical = s.dh(0.7f)),
        verticalAlignment = Alignment.Bottom
    ) {
        Text("Sähkö nyt", color = Ark.PriceLab, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(1.7f))
        Spacer(Modifier.width(s.dw(0.6f)))
        Text(fi(snt, 3), color = Ark.PriceText, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.9f))
        Spacer(Modifier.width(s.dw(0.4f)))
        Text("snt/kWh", color = Ark.PriceUnit, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(1.9f))
    }
}

@Composable
private fun NavButton(label: String, active: Boolean, s: Scale, leading: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    val mod = if (active)
        Modifier.background(Brush.linearGradient(listOf(Color(0xFF3D7BFF), Ark.Accent)), RoundedCornerShape(10.dp))
    else Modifier
    Row(mod.clickable(onClick = onClick).height(s.dh(5.6f)).padding(horizontal = s.dw(1.3f)), verticalAlignment = Alignment.CenterVertically) {
        if (active && leading != null) {
            Icon(leading, null, tint = Color.White, modifier = Modifier.size(s.dh(2.6f)))
            Spacer(Modifier.width(s.dw(0.5f)))
        }
        Text(label, color = if (active) Color.White else Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(2.2f), maxLines = 1)
    }
    Spacer(Modifier.width(s.dw(0.5f)))
}

/* ---------------- Etusivu ---------------- */

@Composable
private fun HomePage(ui: HomeUi, s: Scale, onSensorClick: (SensorUi) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ClockCol(ui, s, Modifier.weight(1.42f).fillMaxHeight())
            Box(Modifier.fillMaxHeight().width(s.dh(0.12f)).background(Ark.Line))
            WeatherCol(ui, s, Modifier.weight(1f).fillMaxHeight())
        }
        SensorBand(ui, s, onSensorClick)
    }
}

@Composable
private fun ClockCol(ui: HomeUi, s: Scale, modifier: Modifier) {
    val clock = rememberClockText()
    Column(
        modifier.padding(horizontal = s.dw(3f)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(clock.time, color = Color(0xFFEAF3FF), fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(30f), maxLines = 1)
            Spacer(Modifier.width(s.dw(1f)))
            // Sekunnit kiinteäleveyksisinä numeropaikkoina → ei mene päällekkäin millään arvolla.
            Row(Modifier.padding(top = s.ds(2.2f)), verticalAlignment = Alignment.Top) {
                for (ch in clock.sec) {
                    Box(Modifier.width(s.ds(4.0f)), contentAlignment = Alignment.Center) {
                        Text(ch.toString(), color = Ark.Accent, fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(8.5f), maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(s.dh(2.2f)))
        Text(clock.date, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(4.4f), maxLines = 1)
        if (ui.testOffline) {
            Spacer(Modifier.height(s.dh(1.2f)))
            Text("⚠ OFFLINE-TESTITILA — verkkohaut ohitetaan", color = Ark.Warm, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.2f), maxLines = 1)
        }
        if (ui.holiday != null) {
            Spacer(Modifier.height(s.dh(1.8f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(s.dw(0.8f)).background(Ark.Warm, CircleShape))
                Spacer(Modifier.width(s.dw(0.8f)))
                Text(ui.holiday, color = Ark.Warm, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(2.5f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun WeatherCol(ui: HomeUi, s: Scale, modifier: Modifier) {
    Column(
        modifier.padding(horizontal = s.dw(3.4f)),
        verticalArrangement = Arrangement.Center
    ) {
        WeatherBlock("Ilmatieteen laitos", ui.fmi, Ark.Good, Ark.SourceText, s)
        Spacer(Modifier.height(s.dh(1.6f)))
        Box(Modifier.fillMaxWidth().height(s.dh(0.12f)).background(Ark.Line))
        Spacer(Modifier.height(s.dh(1.6f)))
        WeatherBlock("OPEN-METEO", ui.om, Ark.Cold, Ark.OpenMeteoText, s)
    }
}

@Composable
private fun WeatherBlock(
    source: String,
    weather: WeatherUi?,
    sourceColor: Color,
    sourceTextColor: Color,
    s: Scale,
) {
    Column {
        Text(
            source, color = sourceTextColor, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(1.7f),
            modifier = Modifier.background(sourceColor, RoundedCornerShape(6.dp)).padding(horizontal = s.dw(0.7f), vertical = s.dh(0.35f))
        )
        Spacer(Modifier.height(s.dh(1.1f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AndroidView(
                factory = { org.jrs82.fsclock.WeatherIconView(it) },
                update = { v -> v.setCondition(weather?.condition) },
                modifier = Modifier.size(s.dh(11.5f))
            )
            Spacer(Modifier.width(s.dw(2.4f)))
            Column {
                Text(fiUnit(weather?.t, 0, "°"), color = tempColor(weather?.t), fontFamily = BigShoulders, fontWeight = FontWeight.SemiBold, fontSize = s.sh(13f), maxLines = 1)
                Text(weather?.cond ?: "", color = Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = s.sh(2.5f), maxLines = 1)
            }
        }
        Spacer(Modifier.height(s.dh(1.3f)))
        WxRows(weather, s)
    }
}

@Composable
private fun WxRows(w: WeatherUi?, s: Scale) {
    Column(verticalArrangement = Arrangement.spacedBy(s.dh(0.9f))) {
        Row(horizontalArrangement = Arrangement.spacedBy(s.dw(2.4f))) {
            MetricCell("feels", "Tuntuu", fiUnit(w?.feels, 0, "°"), s, Modifier.weight(1f))
            MetricCell("wind", "Tuuli", fiUnit(w?.wind, 0, " m/s"), s, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(s.dw(2.4f))) {
            MetricCell("hum", "Kosteus", fiUnit(w?.hum, 0, " %"), s, Modifier.weight(1f))
            MetricCell("rain", "Sade", fiUnit(w?.precip, 1, " mm"), s, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCell(type: String, label: String, value: String, s: Scale, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MetricIcon(type, Modifier.size(s.dh(2.3f)))
        Spacer(Modifier.width(s.dw(0.9f)))
        Text(label + " ", color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(2.3f))
        Text(value, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(2.3f), maxLines = 1)
    }
}

/* ---------------- Anturipalkki (isot kortit, dynaaminen reuna) ---------------- */

@Composable
private fun SensorBand(ui: HomeUi, s: Scale, onSensorClick: (SensorUi) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(Ark.BandStart, Ark.BandEnd))
        )
    ) {
        Box(Modifier.fillMaxWidth().height(s.dh(0.14f)).background(Ark.Line))
        if (ui.sensors.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = s.dw(3.4f), vertical = s.dh(3f)), verticalAlignment = Alignment.CenterVertically) {
                Text("Ei antureita määritetty — avaa asetukset (⚙) ja yhdistä Ruuvi-anturit", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(2.4f))
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = s.dw(3f), vertical = s.dh(2.2f)),
                horizontalArrangement = Arrangement.spacedBy(s.dw(2.2f))
            ) {
                for (sn in ui.sensors) BigSensorCard(sn, s, Modifier.weight(1f), onSensorClick)
            }
        }
    }
}

@Composable
private fun BigSensorCard(sn: SensorUi, s: Scale, modifier: Modifier, onClick: (SensorUi) -> Unit) {
    val accent = tempColor(sn.t)
    Row(
        modifier.clickable { onClick(sn) }.background(Ark.SensorPanel, RoundedCornerShape(18.dp))
            .border(s.dh(0.42f), accent, RoundedCornerShape(18.dp)).padding(horizontal = s.dw(2.2f), vertical = s.dh(2.2f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(sn.name.uppercase(), color = accent, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(s.dh(0.6f)))
            Text(
                if (sn.rh != null) fi(sn.rh, 0) + " % kosteus" else "—",
                color = Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = s.sh(2.3f), maxLines = 1
            )
        }
        Spacer(Modifier.width(s.dw(1f)))
        Text(fiUnit(sn.t, 2, "°"), color = accent, fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(7.2f), maxLines = 1)
    }
}

/* ---------------- Sääikonit (Canvas, jaettu sivujen kesken) ---------------- */

@Composable
internal fun MetricIcon(type: String, modifier: Modifier) {
    val col = when (type) {
        "feels" -> Ark.Warm
        "wind" -> Ark.Cold
        "hum" -> Color(0xFF5AC8FF)
        else -> Color(0xFF7FB2FF)
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val st = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        when (type) {
            "feels" -> {
                drawLine(col, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.6f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawCircle(col, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.74f))
            }
            "wind" -> {
                drawLine(col, Offset(w * 0.12f, h * 0.38f), Offset(w * 0.7f, h * 0.38f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(col, Offset(w * 0.12f, h * 0.62f), Offset(w * 0.78f, h * 0.62f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawCircle(col, radius = w * 0.13f, center = Offset(w * 0.78f, h * 0.30f), style = st)
            }
            "hum" -> {
                drawCircle(col, radius = w * 0.26f, center = Offset(w * 0.5f, h * 0.62f), style = st)
                drawLine(col, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.5f), strokeWidth = st.width, cap = StrokeCap.Round)
            }
            else -> {
                drawLine(col, Offset(w * 0.3f, h * 0.5f), Offset(w * 0.22f, h * 0.78f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(col, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.42f, h * 0.78f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(col, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.62f, h * 0.78f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(col, Offset(w * 0.18f, h * 0.36f), Offset(w * 0.8f, h * 0.36f), strokeWidth = st.width, cap = StrokeCap.Round)
            }
        }
    }
}

/* ---------------- Anturin nimeäminen ---------------- */

@Composable
private fun RenameDialog(sensor: SensorUi, onRename: (String, String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(sensor.slot) { mutableStateOf(sensor.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nimeä anturi") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(sensor.slot, text.trim()); onDismiss() }) { Text("Tallenna") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Peruuta") } }
    )
}
