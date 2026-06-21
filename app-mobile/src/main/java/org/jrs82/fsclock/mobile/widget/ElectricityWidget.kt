package org.jrs82.fsclock.mobile.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import androidx.glance.text.TextStyle
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.R
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ElectricityWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { ElectricityContent(context) }
    }
}

@Composable
private fun ElectricityContent(context: Context) {
    val snt = WidgetCache.electricitySnt(context)
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val threshold = (prefs.getString("mobile_cheap_electricity_threshold", "5.0") ?: "5.0")
        .trim().replace(',', '.').toDoubleOrNull() ?: 5.0
    val level = WidgetFormat.priceLevel(snt, threshold)
    val sntSafe = if (!snt.isNaN() && Math.abs(snt) < 0.0005) 0.0 else snt
    val priceText = if (sntSafe.isNaN()) "–" else String.format(Locale("fi", "FI"), "%.3f", sntSafe)

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

    // Osoittimen paikka absoluuttisella asteikolla 0..30 c/kWh (SPEC: marker = hinta/30, leikataan).
    // Nain marker vastaa varikoodattuja vyohykkeita (halpa vasen / kallis oikea), ei paivan vaihtelua.
    val pos01 = if (snt.isFinite()) (snt / 30.0).coerceIn(0.0, 1.0).toFloat() else 0f
    val innerW = (LocalSize.current.width.value - 40f).coerceAtLeast(0f) // kortin sisaleveys (padding 20*2)
    val markerLeft = (pos01 * (innerW - 16f)).coerceIn(0f, (innerW - 16f).coerceAtLeast(0f))

    // Aktiivinen 15 min vartti + seuraavan vartin alku (Suomen aika). Paivittyy widgetin redrawissa.
    val now = LocalTime.now(ZoneId.of("Europe/Helsinki"))
    val qStart = now.withMinute((now.minute / 15) * 15).withSecond(0).withNano(0)
    val qEnd = qStart.plusMinutes(15)
    val hm = DateTimeFormatter.ofPattern("H.mm")
    val quarterText = "Vartti klo ${qStart.format(hm)}–${qEnd.format(hm)} · seuraava ${qEnd.format(hm)}"

    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(ImageProvider(R.drawable.widget_card_bg))
                .cornerRadius(26.dp)
                .padding(20.dp)
                .clickable(WidgetDeepLink.openSection(context, "ELECTRICITY")),
        ) {
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
                Text(
                    "Pörssisähkö nyt",
                    style = TextStyle(color = WidgetColors.dim, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.defaultWeight())
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
            Spacer(GlanceModifier.height(14.dp))
            // Iso hinta
            Row(verticalAlignment = Alignment.Vertical.Bottom) {
                Text(
                    priceText,
                    style = TextStyle(color = levelColor, fontSize = 44.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(7.dp))
                Text(
                    "c/kWh",
                    style = TextStyle(color = WidgetColors.dim, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(9.dp))
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
            Spacer(GlanceModifier.height(18.dp))
            // Mittari: gradienttipalkki + osoitin hinnan kohdalla
            Box(
                modifier = GlanceModifier.fillMaxWidth().height(16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = GlanceModifier.fillMaxWidth().height(8.dp).cornerRadius(99.dp)
                        .background(ImageProvider(R.drawable.widget_price_gauge)),
                ) {}
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Spacer(GlanceModifier.width(markerLeft.dp))
                    Box(
                        modifier = GlanceModifier.size(16.dp)
                            .background(ImageProvider(R.drawable.widget_gauge_marker)),
                    ) {}
                }
            }
            Spacer(GlanceModifier.height(9.dp))
            // Asteikkotekstit
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text("Halpa", style = TextStyle(color = WidgetColors.pos, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.defaultWeight())
                Text("Normaali", style = TextStyle(color = WidgetColors.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.defaultWeight())
                Text("Kallis", style = TextStyle(color = WidgetColors.neg, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

class ElectricityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ElectricityWidget()
}
