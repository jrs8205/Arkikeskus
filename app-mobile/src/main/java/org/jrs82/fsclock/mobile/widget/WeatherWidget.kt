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
import androidx.glance.unit.ColorProvider
import org.jrs82.fsclock.R
import java.time.Instant
import java.time.ZoneId

/** Yksi tunti: FMI- ja Open-Meteo-lampotila (NaN jos lahde ei anna kyseiselle tunnille). */
private data class WxHour(val h: Int, val fmi: Double, val om: Double)

private fun parseWeatherHours(json: String): List<WxHour> = try {
    val arr = org.json.JSONArray(json)
    (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        WxHour(o.optInt("h"), o.optDouble("f", Double.NaN), o.optDouble("o", Double.NaN))
    }
} catch (e: Exception) {
    emptyList()
}

private fun wxTemp(v: Double): String {
    if (v.isNaN()) return "–"
    val s = if (Math.abs(v) < 0.5) 0.0 else v
    return "${Math.round(s)}°"
}

/** Saatilan teksti -> (ikoni, vari). Avainsanat suomeksi (FMI/Open-Meteo labelit). */
private fun weatherIcon(condLabel: String): Pair<Int, ColorProvider> {
    val c = condLabel.lowercase()
    return when {
        c.contains("selke") || c.contains("aurin") || c.contains("clear") || c.contains("sunny") ->
            R.drawable.mobile_ic_weather_24 to WidgetColors.warn
        c.contains("sade") || c.contains("kuuro") || c.contains("tihku") || c.contains("ranta") ||
            c.contains("räntä") || c.contains("rain") || c.contains("drizzle") || c.contains("ukkos") ->
            R.drawable.mobile_ic_rain_24 to WidgetColors.c1
        else -> // pilvinen / puolipilvinen / sumu / lumi / oletus
            R.drawable.mobile_ic_wx_cloud to WidgetColors.dim
    }
}

class WeatherWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WeatherContent(context) }
    }
}

@Composable
private fun WeatherContent(context: Context) {
    val place = WidgetCache.weatherPlace(context).ifBlank { "Sää" }
    val fmiNow = WidgetCache.weatherTempC(context)
    val omNow = WidgetCache.weatherOmTempC(context)
    val heroTemp = if (fmiNow.isFinite()) fmiNow else omNow
    val cond = WidgetCache.weatherCondition(context).ifBlank { WidgetCache.weatherOmCondition(context) }
    val (iconRes, iconColor) = weatherIcon(cond)
    val atMs = WidgetCache.weatherUpdatedAt(context).takeIf { it > 0 } ?: WidgetCache.weatherOmUpdatedAt(context)
    val hourLabel = if (atMs > 0)
        " · klo " + Instant.ofEpochMilli(atMs).atZone(ZoneId.of("Europe/Helsinki")).hour else ""
    val hours = parseWeatherHours(WidgetCache.weatherHoursJson(context)).take(6)

    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(ImageProvider(R.drawable.widget_card_bg))
                .cornerRadius(26.dp)
                .padding(20.dp)
                .clickable(WidgetDeepLink.openSection(context, "FORECAST")),
        ) {
            // Sijaintirivi
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                Image(
                    provider = ImageProvider(R.drawable.mobile_ic_location_24),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(WidgetColors.c1),
                    modifier = GlanceModifier.size(18.dp),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    "$place$hourLabel",
                    style = TextStyle(color = WidgetColors.dim, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(14.dp))
            // Iso sääikoni + nykylampotila
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                Image(
                    provider = ImageProvider(iconRes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(iconColor),
                    modifier = GlanceModifier.size(54.dp),
                )
                Spacer(GlanceModifier.width(16.dp))
                Text(
                    wxTemp(heroTemp),
                    style = TextStyle(color = WidgetColors.strong, fontSize = 54.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            // Lahderivit
            Row {
                Text(
                    "Open-M. ${wxTemp(omNow)}",
                    style = TextStyle(color = WidgetColors.c2, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(14.dp))
                Text(
                    "Ilmatieteen laitos ${wxTemp(fmiNow)}",
                    style = TextStyle(color = WidgetColors.c1, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(16.dp))
            // Erotinviiva
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(WidgetColors.rowline)) {}
            Spacer(GlanceModifier.height(16.dp))
            // Tuntiennuste: 6 saraketta (FMI sininen, OM vihrea)
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                hours.forEach { h ->
                    Column(
                        modifier = GlanceModifier.defaultWeight(),
                        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                    ) {
                        Text("${h.h}", style = TextStyle(color = WidgetColors.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                        Spacer(GlanceModifier.height(9.dp))
                        Text(wxTemp(h.fmi), style = TextStyle(color = WidgetColors.c1, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        Spacer(GlanceModifier.height(7.dp))
                        Text(wxTemp(h.om), style = TextStyle(color = WidgetColors.c2, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()
}
