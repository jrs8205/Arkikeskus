package org.jrs82.fsclock.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.ElectricityRepository
import org.jrs82.fsclock.R
import org.jrs82.fsclock.mobile.widget.ElectricityWidgetRedraw
import java.time.Instant
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val PAGE_FI: Locale = Locale.forLanguageTag("fi-FI")
private val PAGE_HELSINKI: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

/** Välilehdet: 0 = Tänään, 1 = Huomenna, 2 = Vertailu. */
private const val TAB_COMPARE = 2

/**
 * Pörssisähkö-sivu. Yksi [LazyColumn], jotta pitkä hintalista piirretään laiskasti ja kuluvaan
 * riviin voi vierittää. Vierekkäiset osat asettuvat allekkain, kun ne eivät mahdu tai fontti on iso.
 */
@Composable
internal fun ElectricitySection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val repo = remember { ElectricityRepository.get(context) }
    val settingsRevision = LocalHomeDataRevision.current
    val threshold = remember(settingsRevision) { cheapThreshold(prefs) }
    val vat = remember(settingsRevision) { ElectricityVat.enabled(prefs) }
    val hourly = remember(settingsRevision) { ElectricityHourly.enabled(prefs) }
    val refresh = LocalRefreshTick.current
    var dayOffset by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    // Kuluva hetki: NYT-rivi, yläkortin nykyhinta ja Siirry nykyhetkeen. Päivittyy jakson vaihtuessa
    // (silmukka alla), sovelluksen palatessa näkyviin sekä Päivitä-painalluksesta ja ON_RESUME-virkistyksestä.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            try {
                repo.fetchIfStale()
            } catch (e: Exception) {
                // cachen varaan
            }
        }
        nowMs = System.currentTimeMillis()
        tick++
    }
    // Kun Huomenna-välilehti avataan eikä huomisen TÄYTTÄ päivää ole vielä cachessa (mutta klo ≥14,
    // jolloin NordPool-päivädata pitäisi olla julki), pakota tuore haku OHI 55 min cachen — muuten sivu
    // näyttäisi tyhjää vaikka palvelimella on jo huomisen hinnat. HUOM: hasFullTomorrow() (ei hasTomorrow)
    // koska CET-aikavyöhykkeen ~1 h aamuyön ylivuoto pitäisi hasTomorrow():n aina tosena → pakkohaku ei
    // laukeaisi koskaan. hasFullTomorrow vaatii iltavartin → ohittaa ylivuodon (kesä/talvi-turvallinen).
    LaunchedEffect(dayOffset, refresh) {
        if (dayOffset == 1 && !repo.hasFullTomorrow() &&
            Calendar.getInstance(PAGE_HELSINKI, PAGE_FI).get(Calendar.HOUR_OF_DAY) >= 14
        ) {
            withContext(Dispatchers.IO) {
                try { repo.fetchNow() } catch (e: Exception) { }
            }
            tick++
        }
    }
    // Päivän vaihtuessa myös päivän vartit luetaan uudelleen.
    val dateKey = remember(nowMs) { Instant.ofEpochMilli(nowMs).atZone(ElectricityTime.HELSINKI).toLocalDate() }
    val quarters = remember(tick, dayOffset, dateKey) { if (dayOffset == TAB_COMPARE) null else dayQuarters(repo, dayOffset) }
    val day = remember(quarters, hourly, vat) { quarters?.let { ElectricityHourly.daySlots(it, hourly, vat) } }
    val slotMs = day?.slotMs ?: ElectricityHourly.QUARTER_MS
    // NYT seuraa aikaa ilman Päivitä-painallusta. delay ei etene laitteen syväunen aikana, joten silmukka
    // käynnistetään uudelleen (ja nowMs luetaan heti) aina, kun sovellus palaa näkyviin; taustalla se ei pyöri.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(slotMs, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(nextSlotBoundaryDelayMs(nowMs, slotMs) + SLOT_TICK_MARGIN_MS)
            }
        }
    }
    val hasPrices = electricityDayHasPrices(dayOffset, quarters)
    val data = remember(tick) { repo.peek() }
    val fetchFailed = remember(tick) { repo.lastFetchFailed() }
    val current = remember(nowMs, dayOffset, day) {
        when {
            dayOffset != 0 || day == null -> null
            else -> ElectricityHourly.current(day.slots, nowMs, day.slotMs)
        }
    }
    val rows = remember(day, vat, nowMs, dayOffset) {
        day?.let { electricityPriceRows(it.slots, it.slotMs, vat, if (dayOffset == 0) nowMs else null) }.orEmpty()
    }
    val showList = dayOffset != TAB_COMPARE && hasPrices && day != null && day.slots.isNotEmpty()
    val showUnit = dayOffset != TAB_COMPARE
    val showNotice = hasPrices && day != null && electricityHourlyFallback(hourly, day)
    // Rivejä edeltävät kohteet: title, tabs, (unit), (notice), hero, list-title.
    val headerCount = 2 + (if (showUnit) 1 else 0) + (if (showNotice) 1 else 0) + 2
    val nowRow = rows.indexOfFirst { it.isNow }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val jump by remember(nowRow, headerCount) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (nowRow < 0 || visible.isEmpty()) null
            else jumpToNowDirection(headerCount + nowRow, visible.first().index, visible.last().index)
        }
    }
    val rowLayout = rememberPriceRowLayout(rows)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rowsSideBySide = rowLayout.sideBySide(this.constraints.maxWidth.toFloat(), density)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.electricity_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            item(key = "tabs") {
                ChoiceGroup(
                    labels = listOf(
                        stringResource(R.string.common_today),
                        stringResource(R.string.common_tomorrow),
                        stringResource(R.string.electricity_tab_compare),
                    ),
                    selected = dayOffset,
                    stackOnLargeFont = true,
                    role = Role.Tab,
                ) { dayOffset = it }
                Spacer(Modifier.height(10.dp))
            }
            if (showUnit) {
                item(key = "unit") {
                    // Sama asetus kuin Asetukset → Pörssisähkö → Näytä tunnin keskihinta: vaikuttaa myös
                    // etusivuun, widgettiin ja ilmoitukseen. Sivu päivittyy prefs-kuuntelijan kautta.
                    ChoiceGroup(
                        labels = listOf(stringResource(R.string.electricity_unit_quarter), stringResource(R.string.electricity_unit_hour)),
                        selected = if (hourly) 1 else 0,
                        stackOnLargeFont = false,
                        role = Role.RadioButton,
                        segmented = true,
                    ) { index ->
                        val hour = index == 1
                        if (hour != hourly) {
                            prefs.edit { putBoolean(ElectricityHourly.KEY, hour) }
                            ElectricityWidgetRedraw.redrawAsync(context)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            if (showNotice) {
                item(key = "notice") {
                    Text(
                        stringResource(R.string.electricity_hourly_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            when {
                dayOffset == TAB_COMPARE -> item(key = "compare") {
                    Column { ElectricityCompare(context, refresh, vat) }
                }
                !showList || quarters == null || day == null -> item(key = "empty") {
                    Text(
                        stringResource(electricityEmptyTextRes(dayOffset, fetchFailed)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    item(key = "hero") {
                        ElectricityHeroCard(quarters, day, current, dayOffset, threshold, vat, data)
                    }
                    item(key = "list-title") {
                        PriceListTitle(day.hourly, vat)
                    }
                    val dayMin = day.slots.minOf { it.sntPerKwh }
                    val dayMax = day.slots.maxOf { it.sntPerKwh }
                    items(rows, key = { "row-${it.timestamp}" }) { row ->
                        PriceListRow(row, dayMin, dayMax, rowsSideBySide)
                    }
                    if (rows.any { it.rangeText.contains('*') }) {
                        item(key = "dst-footnote") {
                            Text(
                                stringResource(R.string.electricity_dst_footnote),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    // Listan lopussa: pitkältä varttilistalta pääsee takaisin sivun alkuun ilman pitkää
                    // vieritystä. Jää kelluvan Siirry nykyhetkeen -painikkeen yläpuolelle (alareunan tyhjä tila).
                    item(key = "back-to-top") {
                        BackToTopButton { scope.launch { listState.animateScrollToItem(0) } }
                    }
                }
            }
        }
        if (dayOffset == 0) {
            JumpToNowButton(
                direction = jump,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            ) {
                val contextPx = with(density) { JUMP_CONTEXT.roundToPx() }
                scope.launch { listState.animateScrollToItem((headerCount + nowRow).coerceAtLeast(0), -contextPx) }
            }
        }
    }
}

/** Marginaali jakson vaihdon jälkeen, ettei ajastin herää juuri ennen rajaa. */
private const val SLOT_TICK_MARGIN_MS = 500L

/** Kuluvan rivin yläpuolelle jäävä tila Siirry nykyhetkeen -vierityksen jälkeen. */
private val JUMP_CONTEXT = 64.dp

/** Hintalistan otsikko: "Kaikki vartit" / "Kaikki tunnit" ja oikealla yksikkö + ALV. */
@Composable
private fun PriceListTitle(hourly: Boolean, vat: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            stringResource(if (hourly) R.string.electricity_all_hours else R.string.electricity_all_quarters),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$UNIT · " + stringResource(ElectricityVat.labelRes(vat)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/** Rivien yhteinen asettelupäätös: leveimmän ajan (+ NYT-merkintä) ja leveimmän hinnan mitat. */
private class PriceRowLayout(val timePx: Float, val pricePx: Float) {
    /** Mahtuvatko aika ja hinta rinnakkain listan levyisellä rivillä. */
    fun sideBySide(listWidthPx: Float, density: Density): Boolean {
        // Kuluvan rivin kehys kaventaa riviä; varataan se kaikille, jotta rivit asettuvat samoin.
        val inner = listWidthPx - with(density) { (16.dp * 2 + ROW_PADDING_H * 2 + NOW_RING_INSET * 2).toPx() }
        return fitsInRow(listOf(timePx, pricePx), with(density) { ROW_GAP.toPx() }, inner)
    }
}

@Composable
private fun rememberPriceRowLayout(rows: List<PriceRow>): PriceRowLayout {
    val loc = AppLocale.current(LocalContext.current)
    val timeStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
    val priceStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    val nowStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
    val density = LocalDensity.current
    val widestTime = rows.maxByOrNull { it.rangeText.length }?.rangeText ?: "00.00–00.00"
    // Levein hinta mitataan: merkkimäärä ei riitä, koska esim. miinusmerkki on numeroa kapeampi.
    val prices = rows.map { ElectricityVat.format(it.displayPrice, loc) }.distinct().ifEmpty { listOf("00,000") }
    val priceMeasurer = rememberTextMeasurer()
    val pricePx = remember(prices, priceStyle, priceMeasurer) {
        prices.maxOf { priceMeasurer.measure(it, priceStyle, softWrap = false, maxLines = 1).size.width.toFloat() }
    }
    val nowPx = rememberTextWidthPx(stringResource(R.string.electricity_now_short), nowStyle) +
        with(density) { (NOW_PILL_PADDING_H * 2 + ROW_GAP).toPx() }
    val timePx = rememberTextWidthPx(widestTime, timeStyle) + nowPx
    return PriceRowLayout(timePx, pricePx)
}

private val ROW_PADDING_H = 14.dp
private val ROW_GAP = 12.dp
private val NOW_PILL_PADDING_H = 8.dp
private val ROW_SHAPE = RoundedCornerShape(12.dp)
private val NOW_RING = 3.dp
private val NOW_RING_INSET = 6.dp
private val NOW_RING_SHAPE = RoundedCornerShape(17.dp)

/**
 * Hintalistan rivi: aikaväli (+ NYT) vasemmalla, hinta isona oikealla, taustana päivän sisäinen
 * lämpöväri. Kuluva rivi saa korostusvärin (sininen) kehyksen pienen raon päähän ja tumman
 * NYT-pillerin. Sinistä ei ole lämpöasteikossa, joten kehys erottuu kaikista riveistä molemmissa
 * teemoissa (rivin reunaan piirretty valkoinen tai tumma reunus ei erottunut).
 */
@Composable
private fun PriceListRow(row: PriceRow, dayMin: Double, dayMax: Double, sideBySide: Boolean) {
    val loc = AppLocale.current(LocalContext.current)
    val price = ElectricityVat.format(row.displayPrice, loc)
    val nowLabel = stringResource(R.string.electricity_now_short)
    val description = row.rangeText + ", " + price + " " + UNIT + if (row.isNow) ", $nowLabel" else ""
    val time: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.rangeText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = PriceRowInk)
            if (row.isNow) {
                Spacer(Modifier.width(ROW_GAP))
                Text(
                    nowLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(PriceRowInk)
                        .padding(horizontal = NOW_PILL_PADDING_H, vertical = 2.dp),
                )
            }
        }
    }
    val priceText: @Composable () -> Unit = {
        Text(price, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = PriceRowInk, maxLines = 1, softWrap = false)
    }
    val ring = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .then(if (row.isNow) Modifier.border(NOW_RING, ring, NOW_RING_SHAPE).padding(NOW_RING_INSET) else Modifier),
    ) {
        val base = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(rowHeatColor(row.rawPrice, dayMin, dayMax, ArkiTheme.colors))
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_PADDING_H, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
        if (sideBySide) {
            Row(base, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { time() }
                Spacer(Modifier.width(ROW_GAP))
                priceText()
            }
        } else {
            Column(base, verticalArrangement = Arrangement.Center) {
                time()
                priceText()
            }
        }
    }
}

/** Kelluva painike, joka vierittää kuluvan rivin näkyviin; näkyy vain kun rivi ei ole näkyvissä. */
@Composable
private fun JumpToNowButton(direction: JumpDirection?, modifier: Modifier, onClick: () -> Unit) {
    // Häivytyksen aikana direction on jo null: nuoli piirretään viimeisimmästä suunnasta, ettei se käänny.
    var lastDirection by remember { mutableStateOf(JumpDirection.DOWN) }
    if (direction != null) lastDirection = direction
    val arrow = direction ?: lastDirection
    AnimatedVisibility(visible = direction != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val cs = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .shadow(6.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(cs.primaryContainer)
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(if (arrow == JumpDirection.UP) R.drawable.mobile_ic_arrow_up_24 else R.drawable.mobile_ic_arrow_down_24),
                contentDescription = null,
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.electricity_jump_to_now),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = cs.onPrimaryContainer,
            )
        }
    }
}

/** Hintalistan viimeinen kohde: vierittää sivun alkuun. */
@Composable
private fun BackToTopButton(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
        OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(
                painterResource(R.drawable.mobile_ic_arrow_up_24),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.common_back_to_top),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Yläkortti: taso ja kuluva jakso, iso hinta, hintajana, hintalaatat ja lähde. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ElectricityHeroCard(
    quarters: List<ElectricityData.Quarter>,
    day: ElectricityHourly.DaySlots,
    current: ElectricityData.Quarter?,
    dayOffset: Int,
    threshold: Double,
    vat: Boolean,
    data: ElectricityData?,
) {
    val arki = ArkiTheme.colors
    val cs = MaterialTheme.colorScheme
    val slots = day.slots
    val min = slots.minByOrNull { it.sntPerKwh }
    val max = slots.maxByOrNull { it.sntPerKwh }
    val dayMin = min?.sntPerKwh ?: 0.0
    val dayMax = max?.sntPerKwh ?: 0.0
    val hero = electricityHero(dayOffset, current != null)
    val heroPrice = when {
        hero == ElectricityHero.CURRENT && current != null -> ElectricityVat.apply(current.sntPerKwh, vat)
        hero == ElectricityHero.DAY_AVERAGE -> electricityDayAverage(quarters, vat)
        else -> Double.NaN // NO_CURRENT: hinnan paikalla viiva, ei päivän keskiarvoa
    }
    val heroLevel = priceLevel(heroPrice, threshold)
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    hero == ElectricityHero.CURRENT && current != null -> {
                        val level = priceLevel(ElectricityVat.apply(current.sntPerKwh, vat), threshold)
                        ArkiPill(
                            text = stringResource(priceLabelRes(level)),
                            accent = priceAccent(level, arki),
                            leadingIcon = priceTrendIcon(level)?.let { painterResource(it) },
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        Text(
                            if (day.hourly) {
                                val (from, to) = slotClockRange(current.timestamp, ElectricityHourly.HOUR_MS)
                                stringResource(R.string.electricity_hour_now_at, from, to)
                            } else {
                                stringResource(
                                    R.string.electricity_now_at,
                                    String.format(Locale.ROOT, "%02d:%02d", current.hour, current.minute),
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    hero == ElectricityHero.NO_CURRENT -> Text(
                        stringResource(electricityNoCurrentTextRes(day.hourly)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    else -> ArkiPill(text = stringResource(R.string.common_tomorrow), accent = cs.primary)
                }
            }
            Spacer(Modifier.height(12.dp))
            HeroPrice(ElectricityVat.format(heroPrice, AppLocale.current(LocalContext.current)), priceAccent(heroLevel, arki))
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(ElectricityVat.priceNoteRes(vat)),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (min != null && max != null) {
                Spacer(Modifier.height(16.dp))
                // Hintajana + nyt-osoitin kuten etusivun kortissa (vain Tänään, kun kuluva jakso tunnetaan).
                if (dayOffset == 0 && current != null && dayMax > dayMin) {
                    PriceMeter(
                        current.sntPerKwh, dayMin, dayMax, vat, priceAccent(heroLevel, arki), arki,
                        labelStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                SummaryTiles(quarters, day, min, max, dayOffset, vat)
            }
            Spacer(Modifier.height(14.dp))
            val updated = if (data != null && data.fetchedAt > 0) {
                stringResource(R.string.common_updated_at, hhmm(data.fetchedAt)) + " · "
            } else {
                ""
            }
            Text(
                updated + stringResource(electricitySourceFooterRes(data?.source), stringResource(ElectricityVat.labelRes(vat))),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

private const val HERO_MAX_SP = 48f
private const val HERO_MIN_SP = 32f
private const val HERO_STEP_SP = 2f

/**
 * Iso hinta ja yksikkö. Hinta kutistuu porrastetusti (48 → 32 sp), jos hinta ja yksikkö eivät mahdu
 * samalle riville; jos eivät mahdu alarajallakaan, yksikkö siirtyy hinnan alle. Hinta ei katkea.
 */
@Composable
private fun HeroPrice(price: String, color: Color) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val unitStyle = MaterialTheme.typography.titleMedium
    val priceStyle = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp)
    val unitWidth = rememberTextWidthPx(UNIT, unitStyle)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth.toFloat()
        val gapPx = with(density) { 8.dp.toPx() }
        val widthAt = { sp: Float ->
            measurer.measure(price, priceStyle.copy(fontSize = sp.sp), softWrap = false, maxLines = 1).size.width.toFloat()
        }
        val sizeSp = remember(price, maxPx, unitWidth, measurer) {
            fitFontSizeSp(HERO_MAX_SP, HERO_MIN_SP, HERO_STEP_SP, maxPx - unitWidth - gapPx, widthAt)
        }
        val unitBeside = remember(price, maxPx, unitWidth, sizeSp, measurer) { fitsInRow(listOf(widthAt(sizeSp), unitWidth), gapPx, maxPx) }
        val priceText: @Composable () -> Unit = {
            Text(price, style = priceStyle.copy(fontSize = sizeSp.sp), color = color, maxLines = 1, softWrap = false)
        }
        if (unitBeside) {
            Row(verticalAlignment = Alignment.Bottom) {
                priceText()
                Spacer(Modifier.width(8.dp))
                Text(UNIT, style = unitStyle, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
        } else {
            Column {
                priceText()
                Text(UNIT, style = unitStyle, color = cs.onSurfaceVariant)
            }
        }
    }
}

private const val UNIT = "c/kWh"

/**
 * Halvin / Keskihinta / Kallein ([electricitySummaryTiles]). Rinnakkain, kun 22 sp:n hinnat ja otsikot
 * mahtuvat eikä fontti ole iso; muuten allekkain koko levyisinä riveinä (otsikko vasemmalla, hinta oikealla).
 */
@Composable
private fun SummaryTiles(
    quarters: List<ElectricityData.Quarter>,
    day: ElectricityHourly.DaySlots,
    min: ElectricityData.Quarter,
    max: ElectricityData.Quarter,
    dayOffset: Int,
    vat: Boolean,
) {
    val arki = ArkiTheme.colors
    val cs = MaterialTheme.colorScheme
    val loc = AppLocale.current(LocalContext.current)

    val tiles = electricitySummaryTiles(dayOffset).map { tile ->
        when (tile) {
            SummaryTile.CHEAPEST -> TileData(
                R.drawable.mobile_ic_arrow_down_24, arki.priceCheap, stringResource(R.string.electricity_cheapest),
                ElectricityVat.format(ElectricityVat.apply(min.sntPerKwh, vat), loc),
                electricityPriceRows(listOf(min), day.slotMs, vat, null).first().rangeText,
            )
            SummaryTile.AVERAGE -> TileData(
                null, cs.onSurfaceVariant, stringResource(R.string.electricity_average),
                ElectricityVat.format(electricityDayAverage(quarters, vat), loc),
                UNIT,
            )
            SummaryTile.MOST_EXPENSIVE -> TileData(
                R.drawable.mobile_ic_arrow_up_24, arki.priceExpensive, stringResource(R.string.electricity_most_expensive),
                ElectricityVat.format(ElectricityVat.apply(max.sntPerKwh, vat), loc),
                electricityPriceRows(listOf(max), day.slotMs, vat, null).first().rangeText,
            )
        }
    }
    val valueStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val noteStyle = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val tileWidths = tiles.map { t ->
        val icon = if (t.iconRes != null) with(density) { (TILE_ICON + TILE_ICON_GAP).toPx() } else 0f
        maxOf(
            rememberTextWidthPx(t.value, valueStyle),
            rememberTextWidthPx(t.label.uppercase(loc), labelStyle) + icon,
            rememberTextWidthPx(t.note, noteStyle),
        ) + with(density) { (TILE_PADDING * 2).toPx() }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gapPx = with(density) { TILE_GAP.toPx() }
        val inRow = sideBySide(density.fontScale, fitsEqualColumns(tileWidths, gapPx, constraints.maxWidth.toFloat()))
        if (inRow) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                tiles.forEach { SummaryTileView(it, horizontal = false, Modifier.weight(1f).fillMaxHeight()) }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.forEach { SummaryTileView(it, horizontal = true, Modifier.fillMaxWidth()) }
            }
        }
    }
}

private data class TileData(val iconRes: Int?, val accent: Color, val label: String, val value: String, val note: String)

private val TILE_GAP = 10.dp
private val TILE_PADDING = 12.dp
private val TILE_ICON = 16.dp
private val TILE_ICON_GAP = 6.dp

@Composable
private fun SummaryTileView(t: TileData, horizontal: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val locale = AppLocale.current(LocalContext.current)
    val label: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.iconRes != null) {
                Icon(painterResource(t.iconRes), contentDescription = null, tint = t.accent, modifier = Modifier.size(TILE_ICON))
                Spacer(Modifier.width(TILE_ICON_GAP))
            }
            Text(
                t.label.uppercase(locale),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurfaceVariant,
            )
        }
    }
    val value: @Composable () -> Unit = {
        Text(t.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1, softWrap = false)
    }
    val note: @Composable () -> Unit = {
        Text(t.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(t.accent.copy(alpha = 0.12f))
            .padding(horizontal = TILE_PADDING, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        if (horizontal) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { label(); note() }
                Spacer(Modifier.width(10.dp))
                value()
            }
        } else {
            Column { label(); value(); note() }
        }
    }
}

/** Päivän vartit välilehden mukaan (0 = tänään, 1 = huomenna) Suomen ajassa. */
private fun dayQuarters(repo: ElectricityRepository, dayOffset: Int): List<ElectricityData.Quarter>? {
    val c = Calendar.getInstance(PAGE_HELSINKI, PAGE_FI)
    c.add(Calendar.DAY_OF_YEAR, dayOffset)
    return repo.dayQuarters(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

/**
 * Valintaryhmä (välilehdet tai Vartti | Tunti). Vaihtoehdot ovat rinnakkain yhtä leveinä, jos pisin
 * teksti mahtuu; muuten allekkain koko levyisinä. [stackOnLargeFont]: allekkain aina isolla fontilla.
 * [segmented]: vaihtoehdot yhteisen pohjan sisällä (Vartti | Tunti), jotta valitsin erottuu välilehdistä.
 */
@Composable
private fun ChoiceGroup(
    labels: List<String>,
    selected: Int,
    stackOnLargeFont: Boolean,
    role: Role,
    segmented: Boolean = false,
    onSelect: (Int) -> Unit,
) {
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
    val density = LocalDensity.current
    val widest = labels.maxOf { rememberTextWidthPx(it, style) }
    val gap = if (segmented) 4.dp else CHOICE_GAP
    val outer = if (segmented) {
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    } else {
        Modifier.fillMaxWidth()
    }
    BoxWithConstraints(outer) {
        val gapPx = with(density) { gap.toPx() }
        val itemPx = widest + with(density) { (CHOICE_PADDING * 2).toPx() }
        val fits = fitsInRow(List(labels.size) { itemPx }, gapPx, constraints.maxWidth.toFloat())
        val inRow = if (stackOnLargeFont) sideBySide(density.fontScale, fits) else fits
        if (inRow) {
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                labels.forEachIndexed { i, label ->
                    ChoiceChip(label, i == selected, role, segmented, Modifier.weight(1f)) { onSelect(i) }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(if (segmented) 4.dp else 6.dp)) {
                labels.forEachIndexed { i, label ->
                    ChoiceChip(label, i == selected, role, segmented, Modifier.fillMaxWidth()) { onSelect(i) }
                }
            }
        }
    }
}

private val CHOICE_GAP = 8.dp
private val CHOICE_PADDING = 12.dp

@Composable
private fun ChoiceChip(label: String, selected: Boolean, role: Role, segmented: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val unselected = if (segmented) Color.Transparent else cs.surfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) cs.primaryContainer else unselected)
            .selectable(selected = selected, role = role, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = CHOICE_PADDING, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
