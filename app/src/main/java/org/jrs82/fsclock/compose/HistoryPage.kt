package org.jrs82.fsclock.compose

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.db.CsvExporter
import org.jrs82.fsclock.db.DailyStat
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.db.HistoryRepository
import org.jrs82.fsclock.db.WeatherSample
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/* ---------------- Historia: yksi sivu, välilehdet Järjestelmä + Säädata ---------------- */

private val FI = Locale.Builder().setLanguage("fi").setRegion("FI").build()
private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", FI)
private val WEEKDAYS = arrayOf("ma", "ti", "ke", "to", "pe", "la", "su")

private enum class HistoryTab { SYSTEM, WEATHER }

@Composable
fun HistoryPage(s: Scale) {
    var tab by remember { mutableStateOf(HistoryTab.SYSTEM) }
    Column(Modifier.fillMaxSize().padding(horizontal = s.dw(2.6f), vertical = s.dh(1.6f))) {
        Row(horizontalArrangement = Arrangement.spacedBy(s.dw(1.2f))) {
            TabChip("Järjestelmä", tab == HistoryTab.SYSTEM, s) { tab = HistoryTab.SYSTEM }
            TabChip("Säädata", tab == HistoryTab.WEATHER, s) { tab = HistoryTab.WEATHER }
        }
        Spacer(Modifier.height(s.dh(1.6f)))
        when (tab) {
            HistoryTab.SYSTEM -> SystemTab(s)
            HistoryTab.WEATHER -> WeatherTab(s)
        }
    }
}

@Composable
private fun TabChip(label: String, active: Boolean, s: Scale, onClick: () -> Unit) {
    val bg = if (active) Ark.Accent else Ark.SensorPanel
    val fg = if (active) Color(0xFF06222B) else Ark.Muted
    Box(
        Modifier.clickable(onClick = onClick).background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = s.dw(2.6f), vertical = s.dh(1.1f)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.4f))
    }
}

/* ---------------- Järjestelmä-välilehti ---------------- */

private data class SystemData(
    val samples: List<WeatherSample> = emptyList(),
    val rangeStart: Long = 0L,
    val rangeEnd: Long = 0L,
    val todayStat: DailyStat? = null,
    val totalCount: Long = 0L,
)

@Composable
private fun SystemTab(s: Scale) {
    val ctx = LocalContext.current
    var battPct by remember { mutableStateOf(-1) }
    var battTemp by remember { mutableStateOf(Double.NaN) }
    var data by remember { mutableStateOf(SystemData()) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var exportingKind by remember { mutableStateOf<CsvExporter.Kind?>(null) }

    // Live-akku 5 s välein (sticky intent, ei receiveriä).
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (bi != null) {
                    val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val tT = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    battPct = if (lvl >= 0 && scale > 0) Math.round(lvl * 100f / scale) else -1
                    battTemp = if (tT >= 0) tT / 10.0 else Double.NaN
                }
            } catch (_: Exception) {}
            delay(5000)
        }
    }
    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.IO) {
            try {
                val repo = HistoryRepository.get(ctx.applicationContext)
                val now = System.currentTimeMillis()
                val start = now - 24L * 3_600_000L
                SystemData(
                    samples = repo.getSamplesBetween("battery", start, now),
                    rangeStart = start, rangeEnd = now,
                    todayStat = FsClockDb.get(ctx.applicationContext).dailyStatDao()
                        .get("battery", LocalDate.now(java.time.ZoneId.of("Europe/Helsinki")).toString()),
                    totalCount = repo.sampleCount()
                )
            } catch (e: Exception) { SystemData() }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(s.dh(1.8f))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s.dw(2f))) {
            // Akku nyt
            Column(
                Modifier.weight(1f).background(Ark.Panel, RoundedCornerShape(18.dp))
                    .border(s.dh(0.16f), Ark.Line, RoundedCornerShape(18.dp)).padding(s.dw(2f))
            ) {
                Text("AKKU NYT", color = Ark.Accent, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.1f))
                Spacer(Modifier.height(s.dh(0.8f)))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (battPct >= 0) "$battPct %" else "—", color = Ark.Good, fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(7f))
                    Spacer(Modifier.width(s.dw(2f)))
                    Text(
                        if (!battTemp.isNaN()) fi(battTemp.toFloat(), 1) + " °C" else "",
                        color = tempColor(if (!battTemp.isNaN()) battTemp.toFloat() else null),
                        fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(5f),
                        modifier = Modifier.padding(bottom = s.dh(0.5f))
                    )
                }
            }
            // Tänään min/max
            Column(
                Modifier.weight(1.4f).background(Ark.Panel, RoundedCornerShape(18.dp))
                    .border(s.dh(0.16f), Ark.Line, RoundedCornerShape(18.dp)).padding(s.dw(2f))
            ) {
                Text("AKUN LÄMPÖTILA TÄNÄÄN", color = Ark.Accent, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.1f))
                Spacer(Modifier.height(s.dh(0.8f)))
                val st = data.todayStat
                if (st == null) {
                    Text("Ei mittauksia vielä tänään", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(2.3f))
                } else {
                    val hm = java.text.SimpleDateFormat("HH:mm", FI)
                    Row {
                        StatCell("Min", fi(st.minTemp.toFloat(), 1) + "°", "klo " + hm.format(java.util.Date(st.minTempAt)), Ark.Cold, s, Modifier.weight(1f))
                        StatCell("Max", fi(st.maxTemp.toFloat(), 1) + "°", "klo " + hm.format(java.util.Date(st.maxTempAt)), Ark.Warm, s, Modifier.weight(1f))
                        StatCell("Mittauksia", String.format(FI, "%,d", data.totalCount), "tietokannassa", Ark.Ink, s, Modifier.weight(1.2f))
                    }
                }
            }
        }
        // 24 h lämpögraafi
        Column(
            Modifier.fillMaxWidth().background(Ark.Panel, RoundedCornerShape(18.dp))
                .border(s.dh(0.16f), Ark.Line, RoundedCornerShape(18.dp)).padding(s.dw(2f))
        ) {
            Text("AKUN LÄMPÖTILA 24 H", color = Ark.Accent, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.1f))
            Spacer(Modifier.height(s.dh(1f)))
            if (data.samples.isEmpty()) {
                Text("Ei mittauksia viimeisen vuorokauden ajalta", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(2.3f), modifier = Modifier.padding(vertical = s.dh(3f)))
            } else {
                BatteryTempChart(data.samples, data.rangeStart, data.rangeEnd, s, Modifier.fillMaxWidth().height(s.dh(24f)))
            }
        }
        // CSV-viennit
        Column(
            Modifier.fillMaxWidth().background(Ark.Panel, RoundedCornerShape(18.dp))
                .border(s.dh(0.16f), Ark.Line, RoundedCornerShape(18.dp)).padding(s.dw(2f))
        ) {
            Text("CSV-VIENTI (Download/Arkikeskus)", color = Ark.Accent, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.1f))
            Spacer(Modifier.height(s.dh(0.8f)))
            Row(horizontalArrangement = Arrangement.spacedBy(s.dw(1.6f))) {
                Box(Modifier.weight(1f)) { ExportButton("Raakadata", CsvExporter.Kind.RAW_WEATHER_BATTERY, exportingKind, s) { exportingKind = it } }
                Box(Modifier.weight(1f)) { ExportButton("Sää (luettava)", CsvExporter.Kind.WEATHER_HUMAN, exportingKind, s) { exportingKind = it } }
                Box(Modifier.weight(1f)) { ExportButton("Akku (luettava)", CsvExporter.Kind.BATTERY_HUMAN, exportingKind, s) { exportingKind = it } }
            }
            exportingKind?.let { kind ->
                LaunchedEffect(kind) {
                    val result = withContext(Dispatchers.IO) {
                        CsvExporter.export(ctx.applicationContext, kind, CsvExporter.buildFileName(kind))
                    }
                    exportStatus = if (result.ok) "Tallennettu: ${result.fileName}"
                    else if (result.rowCount == 0) "Ei vietävää dataa" else "Vienti epäonnistui"
                    exportingKind = null
                }
            }
            exportStatus?.let {
                Spacer(Modifier.height(s.dh(0.6f)))
                Text(it, color = if (it.startsWith("Tallennettu")) Ark.Good else Ark.Warn, fontFamily = HankenGrotesk, fontSize = s.sh(2f))
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, sub: String, color: Color, s: Scale, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(2f))
        Text(value, color = color, fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(4.6f), maxLines = 1)
        Text(sub, color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f), maxLines = 1)
    }
}

@Composable
private fun ExportButton(label: String, kind: CsvExporter.Kind, current: CsvExporter.Kind?, s: Scale, onClick: (CsvExporter.Kind) -> Unit) {
    val busy = current != null
    Box(
        Modifier.fillMaxWidth()
            .background(if (busy) Ark.Line.copy(alpha = 0.3f) else Ark.Accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .border(s.dh(0.14f), if (busy) Ark.Line else Ark.Accent, RoundedCornerShape(12.dp))
            .clickable(enabled = !busy) { onClick(kind) }
            .padding(vertical = s.dh(1.3f)),
        contentAlignment = Alignment.Center
    ) {
        Text(if (current == kind) "Viedään…" else label, color = if (busy) Ark.Faint else Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.1f), maxLines = 1)
    }
}

/** 24 h akkulämpö: viiva + min/max-asteikko + tuntiruudukko. */
@Composable
private fun BatteryTempChart(samples: List<WeatherSample>, startMs: Long, endMs: Long, s: Scale, modifier: Modifier) {
    Row(modifier) {
        val minT = samples.minOf { it.temperature }
        val maxT = samples.maxOf { it.temperature }
        val span = (maxT - minT).coerceAtLeast(0.5)
        // Y-akselin labelit
        Column(Modifier.fillMaxHeight().padding(end = s.dw(0.8f)), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
            Text(fi(maxT.toFloat(), 1) + "°", color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f))
            Text(fi(((maxT + minT) / 2).toFloat(), 1) + "°", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f))
            Text(fi(minT.toFloat(), 1) + "°", color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f))
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val w = size.width; val h = size.height
                // ruudukko: 6 h välein
                for (i in 0..4) {
                    val x = w * i / 4f
                    drawLine(Ark.Line.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
                }
                for (i in 0..2) {
                    val y = h * i / 2f
                    drawLine(Ark.Line.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
                }
                // lämpöviiva
                var prev: Offset? = null
                val range = (endMs - startMs).coerceAtLeast(1L).toFloat()
                for (sm in samples) {
                    val x = (sm.timestamp - startMs) / range * w
                    val y = h - ((sm.temperature - minT) / span).toFloat() * h
                    val p = Offset(x, y.toFloat())
                    prev?.let { drawLine(Ark.Warm, it, p, strokeWidth = h * 0.012f, cap = StrokeCap.Round) }
                    prev = p
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("-24 h", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f))
                Text("-12 h", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f))
                Text("nyt", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f))
            }
        }
    }
}

/* ---------------- Säädata-välilehti ---------------- */

private data class Channel(val id: String, val label: String)

@Composable
private fun WeatherTab(s: Scale) {
    val ctx = LocalContext.current
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var stats by remember { mutableStateOf<List<DailyStat>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        channels = withContext(Dispatchers.IO) {
            try {
                val sm = SettingsManager.get()
                val out = ArrayList<Channel>()
                val weather = FsClockDb.get(ctx.applicationContext).weatherDao().listChannels()
                for (ch in weather) {
                    if (ch == "battery") continue
                    // Kanava on muotoa "fmi_vantaa" → näytä "Vantaa".
                    val label = ch.removePrefix("fmi_").replace('_', ' ')
                        .replaceFirstChar { it.uppercase() }
                    out.add(Channel(ch, label))
                }
                val macs = LinkedHashSet<String>()
                HistoryRepository.get(ctx.applicationContext).listRuuviMacs()?.let { macs.addAll(it) }
                for (slot in listOf(SettingsManager.RUUVI_SLOT_BEDROOM, SettingsManager.RUUVI_SLOT_LIVINGROOM, SettingsManager.RUUVI_SLOT_BALCONY)) {
                    sm.getRuuviMac(slot)?.let { macs.add(it.uppercase(FI)) }
                }
                for (mac in macs) {
                    val slot = sm.slotForMac(mac)
                    val name = when (slot) {
                        SettingsManager.RUUVI_SLOT_BEDROOM -> sm.getRuuviName(slot, "Anturi 1")
                        SettingsManager.RUUVI_SLOT_LIVINGROOM -> sm.getRuuviName(slot, "Anturi 2")
                        SettingsManager.RUUVI_SLOT_BALCONY -> sm.getRuuviName(slot, "Anturi 3")
                        else -> "Anturi"
                    }
                    out.add(Channel("ruuvi:$mac", name))
                }
                out
            } catch (e: Exception) { emptyList() }
        }
        if (selected == null) selected = channels.firstOrNull()?.id
    }
    LaunchedEffect(selected, month) {
        val ch = selected ?: return@LaunchedEffect
        loaded = false
        stats = withContext(Dispatchers.IO) {
            try {
                val repo = HistoryRepository.get(ctx.applicationContext)
                if (ch.startsWith("ruuvi:")) repo.getRuuviMonth(ch.removePrefix("ruuvi:"), month.year, month.monthValue)
                else repo.getMonth(ch, month.year, month.monthValue)
            } catch (e: Exception) { emptyList() }
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize()) {
        // Kanavavalitsin + kuukausinavigointi
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(s.dw(1f))) {
                for (ch in channels) {
                    val active = ch.id == selected
                    Box(
                        Modifier.clickable { selected = ch.id }
                            .background(if (active) Ark.Accent else Ark.SensorPanel, RoundedCornerShape(10.dp))
                            .padding(horizontal = s.dw(1.6f), vertical = s.dh(0.9f))
                    ) {
                        Text(ch.label, color = if (active) Color(0xFF06222B) else Ark.Muted, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.1f), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.width(s.dw(1.6f)))
            MonthNavButton("‹", s) { month = month.minusMonths(1) }
            Text(
                month.format(MONTH_FMT).replaceFirstChar { it.uppercase() },
                color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.5f),
                modifier = Modifier.padding(horizontal = s.dw(1.2f))
            )
            MonthNavButton("›", s, enabled = month.isBefore(YearMonth.now())) { month = month.plusMonths(1) }
        }
        Spacer(Modifier.height(s.dh(1.4f)))

        if (channels.isEmpty()) {
            Text("Ei tallennettua historiaa vielä", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(2.6f))
            return@Column
        }
        if (loaded && stats.isEmpty()) {
            Text("Ei mittauksia valitulta kuukaudelta", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(2.6f), modifier = Modifier.padding(vertical = s.dh(2f)))
            return@Column
        }

        // Kuukausigraafi: min–max-jana per päivä
        if (stats.isNotEmpty()) {
            MonthChart(stats, month, s, Modifier.fillMaxWidth().height(s.dh(22f)))
            Spacer(Modifier.height(s.dh(1.4f)))
        }

        // Päivälista
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(s.dh(0.5f))) {
            items(stats.sortedByDescending { it.date }) { st -> DayRow(st, s) }
        }
    }
}

@Composable
private fun MonthNavButton(glyph: String, s: Scale, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(s.dh(5.4f)).background(Ark.SensorPanel, CircleShape)
            .border(s.dh(0.14f), if (enabled) Ark.Line else Ark.Line.copy(alpha = 0.3f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = if (enabled) Ark.Accent else Ark.Faint.copy(alpha = 0.4f), fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(3.4f))
    }
}

@Composable
private fun MonthChart(stats: List<DailyStat>, month: YearMonth, s: Scale, modifier: Modifier) {
    val minT = stats.minOf { it.minTemp }
    val maxT = stats.maxOf { it.maxTemp }
    val span = (maxT - minT).coerceAtLeast(1.0)
    Row(
        modifier.background(Ark.Panel, RoundedCornerShape(14.dp))
            .border(s.dh(0.16f), Ark.Line, RoundedCornerShape(14.dp)).padding(s.dw(1.4f))
    ) {
        Column(Modifier.fillMaxHeight().padding(end = s.dw(0.8f)), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
            Text(fi(maxT.toFloat(), 0) + "°", color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f))
            Text(fi(minT.toFloat(), 0) + "°", color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f))
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val w = size.width; val h = size.height
            val days = month.lengthOfMonth()
            val slot = w / days
            val bw = (slot * 0.45f).coerceAtLeast(2f)
            // nollaviiva jos alueella
            if (minT < 0 && maxT > 0) {
                val zy = h - ((0.0 - minT) / span).toFloat() * h
                drawLine(Ark.Faint.copy(alpha = 0.6f), Offset(0f, zy), Offset(w, zy), strokeWidth = 2f)
            }
            for (st in stats) {
                val day = try { LocalDate.parse(st.date).dayOfMonth } catch (e: Exception) { continue }
                val cx = slot * (day - 0.5f)
                val yMin = h - ((st.minTemp - minT) / span).toFloat() * h
                val yMax = h - ((st.maxTemp - minT) / span).toFloat() * h
                drawLine(
                    tempColor(((st.minTemp + st.maxTemp) / 2).toFloat()),
                    Offset(cx, yMin), Offset(cx, yMax.coerceAtMost(yMin - 2f)),
                    strokeWidth = bw, cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun DayRow(st: DailyStat, s: Scale) {
    val date = try { LocalDate.parse(st.date) } catch (e: Exception) { null }
    val label = if (date != null)
        WEEKDAYS[date.dayOfWeek.value - 1] + " " + date.dayOfMonth + "." + date.monthValue + "."
    else st.date
    val hm = java.text.SimpleDateFormat("HH:mm", FI)
    Row(
        Modifier.fillMaxWidth().background(Ark.SensorPanel.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = s.dw(1.6f), vertical = s.dh(0.9f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.3f), modifier = Modifier.width(s.dw(8f)), maxLines = 1)
        TempCell("▼", st.minTemp, st.minTempAt, hm, Ark.Cold, s)
        TempCell("▲", st.maxTemp, st.maxTempAt, hm, Ark.Warm, s)
        Text("ka " + fi(st.avgTemp.toFloat(), 1) + "°", color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(2.1f), modifier = Modifier.width(s.dw(8f)), maxLines = 1)
        Spacer(Modifier.weight(1f))
        st.totalPrecip?.let { p ->
            if (p >= 0.05) Text("☔ " + fi(p.toFloat(), 1) + " mm", color = Ark.Cold, fontFamily = HankenGrotesk, fontSize = s.sh(2.1f), maxLines = 1, modifier = Modifier.padding(end = s.dw(1.6f)))
        }
        st.maxWindGust?.let { g ->
            if (g > 0) Text("💨 " + fi(g.toFloat(), 0) + " m/s", color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(2.1f), maxLines = 1, modifier = Modifier.padding(end = s.dw(1.6f)))
        }
        if (st.isPartial) {
            Text("osittainen", color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f), maxLines = 1)
        }
    }
}

@Composable
private fun TempCell(glyph: String, temp: Double, atMs: Long, hm: java.text.SimpleDateFormat, color: Color, s: Scale) {
    Row(Modifier.width(s.dw(13f)), verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, color = color, fontFamily = HankenGrotesk, fontSize = s.sh(1.9f))
        Spacer(Modifier.width(s.dw(0.4f)))
        Text(fi(temp.toFloat(), 1) + "°", color = color, fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(3f), maxLines = 1)
        Spacer(Modifier.width(s.dw(0.6f)))
        Text("klo " + hm.format(java.util.Date(atMs)), color = Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(1.8f), maxLines = 1)
    }
}
