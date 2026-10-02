package org.jrs82.fsclock.mobile.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.R
import org.jrs82.fsclock.mobile.ElectricityTime
import org.jrs82.fsclock.mobile.ElectricityVat
import org.jrs82.fsclock.mobile.PriceScale
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class ElectricityWidget : GlanceAppWidget() {
    // Asettelu ja osoitinkuvan mittasuhde valitaan widgetin todellisesta koosta. Oletus (Single) antaisi
    // LocalSizeen aina providerin minimikoon (110 dp).
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Jokainen piirto varmistaa seuraavan varttirajan herätyksen (idempotentti PendingIntent).
        try { ElectricityQuarterAlarm.scheduleIfWidgets(context) } catch (e: Exception) { }
        provideContent {
            // Piirtolaskuri luetaan kompositiossa → elävä Glance-sessio recomposaa kun se kasvaa.
            key(currentState<Preferences>()[ElectricityWidgetRedraw.RENDER_REVISION]) {
                ElectricityContent(context)
            }
        }
    }
}

@Composable
private fun ElectricityContent(context: Context) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val vat = ElectricityVat.enabled(prefs)
    // Kuluva vartti haetaan piirtohetkellä varttilistasta (veroton) → hinta ja varttiteksti samasta vartista.
    val nowMs = System.currentTimeMillis()
    val quarters = WidgetElectricity.decode(WidgetCache.electricityQuartersJson(context))
    val rawSnt = WidgetElectricity.currentPrice(quarters, nowMs)
    val snt = ElectricityVat.apply(rawSnt, vat)
    val threshold = (prefs.getString("mobile_cheap_electricity_threshold", "5.0") ?: "5.0")
        .trim().replace(',', '.').toDoubleOrNull() ?: 5.0
    val level = WidgetFormat.priceLevel(snt, threshold)
    val priceText = ElectricityVat.format(snt)

    val levelColor = when (level) {
        PriceLevel.CHEAP -> WidgetColors.pos
        PriceLevel.NORMAL -> WidgetColors.warn
        PriceLevel.EXPENSIVE -> WidgetColors.neg
    }
    val chipBg = when (level) {
        PriceLevel.CHEAP -> WidgetColors.posTintChip
        PriceLevel.NORMAL -> WidgetColors.warnTintChip
        PriceLevel.EXPENSIVE -> WidgetColors.negTintChip
    }
    val chipText = when (level) {
        PriceLevel.CHEAP -> "Halpaa"
        PriceLevel.NORMAL -> "Normaali"
        PriceLevel.EXPENSIVE -> "Kallis"
    }
    val chipIcon = when (level) {
        PriceLevel.CHEAP -> R.drawable.mobile_ic_arrow_down_24
        PriceLevel.NORMAL -> R.drawable.mobile_ic_arrow_right_alt_24
        PriceLevel.EXPENSIVE -> R.drawable.mobile_ic_arrow_up_24
    }

    // Jana = päivän halvin–kallein vartti, sama asteikko kuin sovelluksen etusivun kortissa ja sähkösivulla.
    val range = WidgetElectricity.dayRange(quarters, nowMs)
    val dayMin = range?.first ?: Double.NaN
    val dayMax = range?.second ?: Double.NaN
    val pos01 = PriceScale.fraction(rawSnt, dayMin, dayMax, vat)
    val (minText, midText, maxText) = PriceScale.labels(dayMin, dayMax, vat)

    // Asettelu valitaan ilmoitetun korkeuden ja fonttiskaalan mukaan: matalassa widgetissä rivejä pudotetaan,
    // jotta jana ja sen hinnat mahtuvat aina kokonaan widgetin sisään.
    val size = LocalSize.current
    val spec = ElectricityWidgetLayout.choose(size.height.value, context.resources.configuration.fontScale)
    val narrow = ElectricityWidgetLayout.isNarrow(size.width.value)
    val innerW = (size.width.value - 40f).coerceAtLeast(0f) // kortin sisaleveys (padding 20*2)

    // Aktiivinen 15 min vartti + seuraavan vartin alku (Suomen aika), samasta hetkestä kuin hinta.
    val (qStart, qEnd) = ElectricityTime.quarterBounds(nowMs)
    val hm = DateTimeFormatter.ofPattern("H.mm")
    val quarterShort = "klo ${qStart.format(hm)}–${qEnd.format(hm)}"
    val quarterText = if (narrow) "Vartti $quarterShort" else "Vartti $quarterShort · seuraava ${qEnd.format(hm)}"

    // Otsikko, hinta ja varttirivi. Janan yläpuolinen osa.
    val top: @Composable () -> Unit = {
        if (spec.showHeader) {
            // Otsikkorivi: bolt-chip + "Porssisahko nyt"  ...  tasolappu
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Box(
                    modifier = GlanceModifier.size(40.dp).cornerRadius(12.dp)
                        .background(WidgetColors.posTintIcon),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.mobile_ic_bolt_24),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(WidgetColors.pos),
                        modifier = GlanceModifier.size(23.dp),
                    )
                }
                Spacer(GlanceModifier.width(11.dp))
                // Otsikko + ALV-tila kahdella rivillä 40 dp ikonilaatikon korkeudessa → ei lisää korttiin tilaa.
                // Paino: otsikko lyhenee tarvittaessa, tasolappu pysyy aina kokonaan näkyvissä.
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        "Pörssisähkö nyt",
                        style = TextStyle(color = WidgetColors.dim, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    Text(
                        ElectricityVat.widgetNote(vat),
                        style = TextStyle(color = WidgetColors.dim, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                }
                if (!narrow) {
                    Box(
                        modifier = GlanceModifier.cornerRadius(9.dp).background(chipBg)
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                            Image(
                                provider = ImageProvider(chipIcon),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(levelColor),
                                modifier = GlanceModifier.size(16.dp),
                            )
                            Spacer(GlanceModifier.width(5.dp))
                            Text(
                                chipText,
                                style = TextStyle(color = levelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(GlanceModifier.height(spec.gapHeader.dp))
        }
        // Iso hinta; kun varttirivi ei mahdu omalle rivilleen, vartin kellonaika näytetään hinnan oikealla puolella.
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.Bottom) {
            Text(
                priceText,
                style = TextStyle(color = levelColor, fontSize = spec.priceSp.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(7.dp))
            Text(
                "c/kWh",
                style = TextStyle(color = WidgetColors.dim, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (!spec.showQuarter && !narrow) {
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    quarterShort,
                    style = TextStyle(color = WidgetColors.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
        if (spec.showQuarter) {
            Spacer(GlanceModifier.height(spec.gapQuarter.dp))
            // Aktiivinen vartti (15 min varttihinta)
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                Image(
                    provider = ImageProvider(R.drawable.mobile_ic_clock_24),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(WidgetColors.dim),
                    modifier = GlanceModifier.size(15.dp),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    quarterText,
                    style = TextStyle(color = WidgetColors.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }

    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(ImageProvider(R.drawable.widget_card_bg))
                .cornerRadius(26.dp)
                .padding(horizontal = 20.dp, vertical = spec.padV.dp)
                .clickable(WidgetDeepLink.openSection(context, "ELECTRICITY")),
        ) {
            if (spec.showGauge && spec !== ElectricityWidgetLayout.ROOMY) {
                // Yläosa joustaa: jana ja sen hinnat saavat tilansa ensin ja pysyvät alareunan sisällä,
                // vaikka launcherin ilmoittama korkeus tai fonttien todellinen korkeus poikkeaisi arviosta.
                Column(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) { top() }
            } else {
                top()
            }
            if (spec.showGauge) {
                Spacer(GlanceModifier.height(spec.gapGauge.dp))
                // Mittari: gradienttipalkki + osoitin hinnan kohdalla
                Box(
                    modifier = GlanceModifier.fillMaxWidth().height(ElectricityWidgetLayout.GAUGE_DP.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = GlanceModifier.fillMaxWidth().height(8.dp).cornerRadius(99.dp)
                            .background(ImageProvider(R.drawable.widget_price_gauge)),
                    ) {}
                    if (pos01 != null) {
                        // Valkoinen piste hintatason värisellä renkaalla (sama kuin sovelluksen janassa).
                        val (ring, core) = gaugeDotBitmaps(context, innerW, pos01)
                        val dotModifier = GlanceModifier.fillMaxWidth().height(ElectricityWidgetLayout.GAUGE_DP.dp)
                        Image(
                            provider = ImageProvider(ring),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            colorFilter = ColorFilter.tint(levelColor),
                            modifier = dotModifier,
                        )
                        Image(
                            provider = ImageProvider(core),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = dotModifier,
                        )
                    }
                }
                Spacer(GlanceModifier.height(spec.gapLabels.dp))
                // Janan alun, keskikohdan ja lopun hinnat (päivän halvin – keskikohta – kallein)
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        minText,
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = WidgetColors.scaleMin, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Start),
                        maxLines = 1,
                    )
                    Text(
                        midText,
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = WidgetColors.scaleMid, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                        maxLines = 1,
                    )
                    Text(
                        maxText,
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = WidgetColors.scaleMax, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Janan osoitin kahtena janan levyisenä kuvana: rengas (sävytetään tasovärillä) ja valkoinen sisus.
 * Kuvat venytetään janan todelliseen leveyteen, joten piste on oikeassa suhteellisessa kohdassa ja
 * janan sisällä silloinkin, kun launcherin ilmoittama leveys poikkeaa todellisesta.
 */
private fun gaugeDotBitmaps(context: Context, widthDp: Float, fraction: Float): Pair<Bitmap, Bitmap> {
    val density = context.resources.displayMetrics.density
    val h = (ElectricityWidgetLayout.GAUGE_DP * density).roundToInt().coerceAtLeast(1)
    val w = (widthDp * density).roundToInt().coerceIn(h, 4096)
    val cx = ElectricityWidgetLayout.dotCenterPx(fraction, w, h)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    fun circle(radius: Float): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawCircle(cx, h / 2f, radius, paint)
    }
    return circle(h / 2f) to circle(h * 5f / 16f)
}

class ElectricityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ElectricityWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        try { ElectricityQuarterAlarm.scheduleIfWidgets(context) } catch (e: Exception) { }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        try { ElectricityQuarterAlarm.cancel(context) } catch (e: Exception) { }
    }
}
