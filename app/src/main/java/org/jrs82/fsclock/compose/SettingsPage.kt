package org.jrs82.fsclock.compose

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.db.CsvExporter
import org.jrs82.fsclock.db.HistoryRepository
import org.jrs82.fsclock.ruuvi.RuuviRepository
import org.jrs82.fsclock.ruuvi.RuuviSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------------- Asetussivu (Compose, korvaa SettingsActivityn) ----------------
 * Korkea kontrasti: isot Ink-tekstit tummalla paneelilla → luettavissa sekä
 * kirkkaalla että pimeällä. Ei kotipaikkakuntaa (GPS hoitaa), ei NTP-vertailua,
 * ei testitiloja. */

private val FI = Locale.Builder().setLanguage("fi").setRegion("FI").build()

@Composable
fun SettingsPage(
    s: Scale,
    onOpenHistory: () -> Unit,
    onBrightnessChanged: () -> Unit,
    ensureBleScan: () -> Boolean,
    onSensorsChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val sm = remember { SettingsManager.get().also { it.init(ctx.applicationContext) } }

    Row(Modifier.fillMaxSize().padding(horizontal = s.dw(2.6f), vertical = s.dh(2f))) {
        // Vasen sarake: näyttö + sijainti/varoitukset + sovellus
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(s.dh(2f))
        ) {
            DisplaySection(sm, s, onBrightnessChanged)
            LocationSection(sm, s)
            AppInfoSection(sm, s, ctx)
        }
        Spacer(Modifier.width(s.dw(2.4f)))
        // Oikea sarake: anturit + tietokanta
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(s.dh(2f))
        ) {
            RuuviSection(sm, s, ensureBleScan, onSensorsChanged)
            DatabaseSection(sm, s, onOpenHistory)
        }
    }
}

/* ---------------- Yhteiset rakennuspalikat ---------------- */

@Composable
internal fun SectionCard(title: String, s: Scale, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Ark.Panel, RoundedCornerShape(18.dp))
            .border(s.dh(0.16f), Ark.Line, RoundedCornerShape(18.dp))
            .padding(horizontal = s.dw(2f), vertical = s.dh(1.8f))
    ) {
        Text(title.uppercase(), color = Ark.Accent, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.1f))
        Spacer(Modifier.height(s.dh(1.2f)))
        content()
    }
}

@Composable
private fun RowLabel(title: String, subtitle: String?, s: Scale, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(2.5f))
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(1.9f))
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, s: Scale, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = s.dh(0.7f)), verticalAlignment = Alignment.CenterVertically) {
        RowLabel(title, subtitle, s, Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Ark.Accent, checkedThumbColor = Color.White,
                uncheckedTrackColor = Ark.Line, uncheckedThumbColor = Ark.Muted
            )
        )
    }
}

@Composable
internal fun ActionButton(label: String, s: Scale, accent: Color = Ark.Accent, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = s.dh(0.5f))
            .background(if (enabled) accent.copy(alpha = 0.16f) else Ark.Line.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .border(s.dh(0.14f), if (enabled) accent else Ark.Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = s.dh(1.3f)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) Ark.Ink else Ark.Faint, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.3f))
    }
}

/* ---------------- Näyttö ja kirkkaus ---------------- */

@Composable
private fun DisplaySection(sm: SettingsManager, s: Scale, onBrightnessChanged: () -> Unit) {
    var day by remember { mutableStateOf(sm.dayBrightness.toFloat()) }
    var night by remember { mutableStateOf(sm.nightBrightness.toFloat()) }
    var morning by remember { mutableStateOf(sm.morningHour) }
    var evening by remember { mutableStateOf(sm.eveningHour) }
    var redTint by remember { mutableStateOf(sm.isNightRedTint) }

    SectionCard("Näyttö ja kirkkaus", s) {
        BrightnessSlider("Päivän kirkkaus", day, s) { v, done ->
            day = v
            if (done) { sm.dayBrightness = v.toInt(); onBrightnessChanged() }
        }
        Spacer(Modifier.height(s.dh(1f)))
        BrightnessSlider("Yön kirkkaus", night, s) { v, done ->
            night = v
            if (done) { sm.nightBrightness = v.toInt(); onBrightnessChanged() }
        }
        Spacer(Modifier.height(s.dh(1.2f)))
        HourStepper("Päivä alkaa", morning, s) { h ->
            morning = h; sm.morningHour = h; onBrightnessChanged()
        }
        Spacer(Modifier.height(s.dh(0.8f)))
        HourStepper("Yö alkaa", evening, s) { h ->
            evening = h; sm.eveningHour = h; onBrightnessChanged()
        }
        Spacer(Modifier.height(s.dh(0.6f)))
        SettingSwitch("Yön punainen sävy", "Punertaa näytön yökirkkauden aikana", redTint, s) {
            redTint = it; sm.setNightRedTint(it)
        }
    }
}

@Composable
private fun BrightnessSlider(label: String, value: Float, s: Scale, onChange: (Float, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(2.5f), modifier = Modifier.weight(1f))
            Text("${value.toInt()} %", color = Ark.Accent, fontFamily = BigShoulders, fontWeight = FontWeight.Bold, fontSize = s.sh(3.4f))
        }
        Slider(
            value = value,
            onValueChange = { onChange(it, false) },
            onValueChangeFinished = { onChange(value, true) },
            valueRange = 1f..100f,
            colors = SliderDefaults.colors(
                thumbColor = Ark.Accent, activeTrackColor = Ark.Accent, inactiveTrackColor = Ark.Line
            )
        )
    }
}

@Composable
private fun HourStepper(label: String, hour: Int, s: Scale, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(2.5f), modifier = Modifier.weight(1f))
        StepButton("−", s) { onChange((hour + 23) % 24) }
        Text(
            String.format(FI, "%02d:00", hour), color = Ark.Ink, fontFamily = BigShoulders, fontWeight = FontWeight.Bold,
            fontSize = s.sh(3.4f), modifier = Modifier.padding(horizontal = s.dw(1.2f))
        )
        StepButton("+", s) { onChange((hour + 1) % 24) }
    }
}

@Composable
private fun StepButton(glyph: String, s: Scale, onClick: () -> Unit) {
    Box(
        Modifier.size(s.dh(5.2f)).background(Ark.SensorPanel, CircleShape)
            .border(s.dh(0.14f), Ark.Line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = Ark.Accent, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(3.2f))
    }
}

/* ---------------- Sijainti ja varoitukset ---------------- */

@Composable
private fun LocationSection(sm: SettingsManager, s: Scale) {
    var followGps by remember { mutableStateOf(sm.isFollowGpsLocation) }
    var autoScroll by remember { mutableStateOf(sm.warningsAutoScroll) }
    SectionCard("Sijainti ja varoitukset", s) {
        SettingSwitch("Kotipaikka seuraa GPS-sijaintia", "Sää ja paikannimi päivittyvät laitteen sijainnista", followGps, s) {
            followGps = it; sm.setFollowGpsLocation(it)
        }
        SettingSwitch("Varoitusten automaattinen vieritys", "Vierittää pitkät varoituslistat Tiedot-sivulla", autoScroll, s) {
            autoScroll = it; sm.setWarningsAutoScroll(it)
        }
    }
}

/* ---------------- Sovellus ---------------- */

@Composable
private fun AppInfoSection(sm: SettingsManager, s: Scale, ctx: android.content.Context) {
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
    }
    val lastUpdate = remember {
        val ts = sm.lastSuccessfulFmiUpdate
        if (ts <= 0) "—" else SimpleDateFormat("d.M.yyyy HH:mm", FI).format(Date(ts))
    }
    SectionCard("Sovellus", s) {
        InfoRow("Versio", version, s)
        InfoRow("Viimeisin sääpäivitys", lastUpdate, s)
    }
}

@Composable
internal fun InfoRow(label: String, value: String, s: Scale) {
    Row(Modifier.fillMaxWidth().padding(vertical = s.dh(0.6f)), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(2.3f), modifier = Modifier.weight(1f))
        Text(value, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = s.sh(2.3f))
    }
}

/* ---------------- RuuviTag-anturit ---------------- */

private data class SlotInfo(val slot: String, val defaultName: String)

private val SLOTS = listOf(
    SlotInfo(SettingsManager.RUUVI_SLOT_BEDROOM, "Anturi 1"),
    SlotInfo(SettingsManager.RUUVI_SLOT_LIVINGROOM, "Anturi 2"),
    SlotInfo(SettingsManager.RUUVI_SLOT_BALCONY, "Anturi 3"),
)

@Composable
private fun RuuviSection(sm: SettingsManager, s: Scale, ensureBleScan: () -> Boolean, onSensorsChanged: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var scanSlot by remember { mutableStateOf<String?>(null) }   // slot johon valitaan, tai null
    var scanOpen by remember { mutableStateOf(false) }
    var renameSlot by remember { mutableStateOf<SlotInfo?>(null) }

    SectionCard("RuuviTag-anturit", s) {
        @Suppress("UNUSED_EXPRESSION") refresh
        for (info in SLOTS) {
            val mac = sm.getRuuviMac(info.slot)
            val name = sm.getRuuviName(info.slot, info.defaultName)
            val sample = if (mac != null) RuuviRepository.get(ctx).getLatest(mac) else null
            val temp = sample?.temperatureC()
            SensorSlotRow(
                name = name,
                detail = when {
                    mac == null -> "Ei kytketty"
                    temp != null -> "$mac · " + fi(temp.toFloat(), 1) + " °C"
                    else -> "$mac · ei mittausta"
                },
                connected = mac != null,
                s = s,
                onRename = { renameSlot = info },
                onAssign = { scanSlot = info.slot; scanOpen = true }
            )
            Spacer(Modifier.height(s.dh(0.9f)))
        }
        Spacer(Modifier.height(s.dh(0.3f)))
        ActionButton("Etsi antureita", s) { scanSlot = null; scanOpen = true }
    }

    if (scanOpen) {
        RuuviScanDialog(sm, s, scanSlot, ensureBleScan,
            onAssigned = { refresh++; onSensorsChanged() },
            onDismiss = { scanOpen = false })
    }
    renameSlot?.let { info ->
        var text by remember(info.slot) { mutableStateOf(sm.getRuuviName(info.slot, info.defaultName)) }
        AlertDialog(
            onDismissRequest = { renameSlot = null },
            title = { Text("Anturin näkyvä nimi") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    sm.setRuuviName(info.slot, text.trim())
                    refresh++; onSensorsChanged(); renameSlot = null
                }) { Text("Tallenna") }
            },
            dismissButton = { TextButton(onClick = { renameSlot = null }) { Text("Peruuta") } }
        )
    }
}

@Composable
private fun SensorSlotRow(name: String, detail: String, connected: Boolean, s: Scale, onRename: () -> Unit, onAssign: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ark.SensorPanel, RoundedCornerShape(12.dp))
            .border(s.dh(0.12f), if (connected) Ark.Good.copy(alpha = 0.55f) else Ark.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = s.dw(1.4f), vertical = s.dh(1f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onRename)) {
            Text(name, color = Ark.Ink, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = if (connected) Ark.Muted else Ark.Faint, fontFamily = HankenGrotesk, fontSize = s.sh(1.9f), maxLines = 1)
        }
        Spacer(Modifier.width(s.dw(1f)))
        Text(
            "Nimeä", color = Ark.Accent, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2f),
            modifier = Modifier.clickable(onClick = onRename).padding(s.dw(0.6f))
        )
        Text(
            if (connected) "Vaihda" else "Kytke", color = Ark.Accent, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2f),
            modifier = Modifier.clickable(onClick = onAssign).padding(s.dw(0.6f))
        )
    }
}

@Composable
private fun RuuviScanDialog(
    sm: SettingsManager,
    s: Scale,
    targetSlot: String?,
    ensureBleScan: () -> Boolean,
    onAssigned: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var found by remember { mutableStateOf<List<RuuviSample>>(emptyList()) }
    var permissionOk by remember { mutableStateOf(true) }
    var pickSlotFor by remember { mutableStateOf<String?>(null) }  // MAC jolle valitaan slot

    DisposableEffect(Unit) {
        permissionOk = ensureBleScan()
        val repo = RuuviRepository.get(ctx)
        // start() on refcountattu (scanClients) → paritetaan stop() onDisposeen.
        repo.start()
        fun snapshotList(): List<RuuviSample> =
            repo.snapshot().values.sortedBy { it.mac }
        found = snapshotList()
        val listener = RuuviRepository.Listener { _, _ -> found = snapshotList() }
        repo.addListener(listener)
        onDispose {
            repo.removeListener(listener)
            repo.stop()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (targetSlot == null) "Havaitut RuuviTagit" else "Valitse anturi") },
        text = {
            Column {
                if (!permissionOk) {
                    Text("Bluetooth-skannauslupa puuttuu. Myönnä lupa ja avaa haku uudelleen.")
                } else if (found.isEmpty()) {
                    Text("Etsitään antureita…")
                } else {
                    for (sample in found) {
                        val slot = sm.slotForMac(sample.mac)
                        val slotLabel = slot?.let { sl -> SLOTS.firstOrNull { it.slot == sl } }
                            ?.let { " · " + sm.getRuuviName(it.slot, it.defaultName) } ?: ""
                        val temp = sample.temperatureC()
                        Text(
                            sample.mac + (if (temp != null) "  " + fi(temp.toFloat(), 1) + " °C" else "") +
                                "  (${sample.rssi} dBm)$slotLabel",
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (targetSlot != null) {
                                    assignMac(sm, sample.mac, targetSlot)
                                    onAssigned(); onDismiss()
                                } else {
                                    pickSlotFor = sample.mac
                                }
                            }.padding(vertical = s.dh(1f))
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Sulje") } }
    )

    pickSlotFor?.let { mac ->
        AlertDialog(
            onDismissRequest = { pickSlotFor = null },
            title = { Text("Kytke $mac") },
            text = {
                Column {
                    for (info in SLOTS) {
                        Text(
                            sm.getRuuviName(info.slot, info.defaultName),
                            modifier = Modifier.fillMaxWidth().clickable {
                                assignMac(sm, mac, info.slot)
                                pickSlotFor = null; onAssigned(); onDismiss()
                            }.padding(vertical = s.dh(1f))
                        )
                    }
                    Text(
                        "Irrota anturi",
                        modifier = Modifier.fillMaxWidth().clickable {
                            val slot = sm.slotForMac(mac)
                            if (slot != null) sm.setRuuviMac(slot, null)
                            pickSlotFor = null; onAssigned(); onDismiss()
                        }.padding(vertical = s.dh(1f))
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickSlotFor = null }) { Text("Peruuta") } }
        )
    }
}

/** Kytkee MACin slottiin; poistaa sen mahdollisesta vanhasta slotista (yksi anturi per slot). */
private fun assignMac(sm: SettingsManager, mac: String, slot: String) {
    val existing = sm.slotForMac(mac)
    if (existing != null && existing != slot) sm.setRuuviMac(existing, null)
    sm.setRuuviMac(slot, mac)
}

/* ---------------- Tietokanta ja historia ---------------- */

@Composable
private fun DatabaseSection(sm: SettingsManager, s: Scale, onOpenHistory: () -> Unit) {
    val ctx = LocalContext.current
    var dbInfo by remember { mutableStateOf("Lasketaan…") }
    var dbReload by remember { mutableStateOf(0) }
    var retention by remember { mutableStateOf(sm.retentionDays) }
    var retentionOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(dbReload) {
        dbInfo = withContext(Dispatchers.IO) {
            try {
                val repo = HistoryRepository.get(ctx.applicationContext)
                val count = repo.sampleCount()
                val f = ctx.getDatabasePath("fsclock.db")
                val size = if (f.exists()) f.length() else 0L
                val sizeStr = when {
                    size < 1024 -> "$size B"
                    size < 1024 * 1024 -> String.format(FI, "%.1f kB", size / 1024.0)
                    else -> String.format(FI, "%.2f MB", size / (1024.0 * 1024.0))
                }
                String.format(FI, "%,d mittausta · %s", count, sizeStr)
            } catch (e: Exception) { "—" }
        }
    }

    val retentionLabels = listOf(30 to "30 päivää", 90 to "3 kuukautta", 365 to "1 vuosi", 1095 to "3 vuotta", 3650 to "10 vuotta")

    SectionCard("Tietokanta ja historia", s) {
        InfoRow("Tietokanta", dbInfo, s)
        Row(Modifier.fillMaxWidth().clickable { retentionOpen = true }.padding(vertical = s.dh(0.6f)), verticalAlignment = Alignment.CenterVertically) {
            Text("Säilytysaika", color = Ark.Muted, fontFamily = HankenGrotesk, fontSize = s.sh(2.3f), modifier = Modifier.weight(1f))
            Text(
                retentionLabels.firstOrNull { it.first == retention }?.second ?: "$retention pv",
                color = Ark.Accent, fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold, fontSize = s.sh(2.3f)
            )
        }
        Spacer(Modifier.height(s.dh(0.6f)))
        ActionButton("Avaa historia", s) { onOpenHistory() }
        ActionButton(if (exporting) "Viedään…" else "Vie raakadata CSV", s, enabled = !exporting) {
            exporting = true; exportStatus = null
        }
        if (exporting) {
            LaunchedEffect(Unit) {
                val result = withContext(Dispatchers.IO) {
                    CsvExporter.export(ctx.applicationContext, CsvExporter.Kind.RAW_WEATHER_BATTERY,
                        CsvExporter.buildFileName(CsvExporter.Kind.RAW_WEATHER_BATTERY))
                }
                exportStatus = if (result.ok) "Tallennettu: ${result.fileName}" else "Vienti epäonnistui"
                exporting = false
            }
        }
        exportStatus?.let {
            Text(it, color = if (it.startsWith("Tallennettu")) Ark.Good else Ark.Warn, fontFamily = HankenGrotesk, fontSize = s.sh(1.9f), modifier = Modifier.padding(top = s.dh(0.4f)))
        }
        ActionButton("Tyhjennä tietokanta", s, accent = Ark.Warn) { clearOpen = true }
    }

    if (retentionOpen) {
        AlertDialog(
            onDismissRequest = { retentionOpen = false },
            title = { Text("Mittaushistorian säilytysaika") },
            text = {
                Column {
                    for ((days, label) in retentionLabels) {
                        Text(
                            (if (days == retention) "●  " else "○  ") + label,
                            modifier = Modifier.fillMaxWidth().clickable {
                                retention = days; sm.setRetentionDays(days); retentionOpen = false
                            }.padding(vertical = s.dh(1f))
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { retentionOpen = false }) { Text("Sulje") } }
        )
    }

    if (clearOpen) {
        var confirmed by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("Tyhjennä tietokanta") },
            text = {
                Column {
                    Text("Poistaa pysyvästi kaikki tallennetut sää-, akku- ja anturimittaukset sekä päiväkohtaiset tilastot.")
                    Spacer(Modifier.height(s.dh(1f)))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text("Ymmärrän, että tietoja ei voi palauttaa")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = confirmed, onClick = {
                    clearOpen = false
                    clearing = true
                }) { Text("Tyhjennä") }
            },
            dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("Peruuta") } }
        )
    }

    if (clearing) {
        LaunchedEffect(Unit) {
            // dbReload vasta tyhjennyksen valmistuttua — irrallinen Thread hävisi
            // kilpailun ja Tietokanta-rivi jäi näyttämään vanhaa määrää/kokoa.
            withContext(Dispatchers.IO) {
                try { HistoryRepository.get(ctx.applicationContext).clearAll() } catch (_: Exception) {}
            }
            clearing = false
            dbReload++
        }
    }
}
