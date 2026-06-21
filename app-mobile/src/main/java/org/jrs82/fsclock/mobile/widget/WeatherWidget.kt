package org.jrs82.fsclock.mobile.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/** Yksi tunti: FMI- ja Open-Meteo-lämpötila (NaN jos lähde ei anna kyseiselle tunnille). */
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

class WeatherWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WeatherContent(context) }
    }
}

@Composable
private fun WeatherContent(context: Context) {
    val place = WidgetCache.weatherPlace(context).ifBlank { "Sää" }
    val hours = parseWeatherHours(WidgetCache.weatherHoursJson(context))
    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(20.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clickable(WidgetDeepLink.openSection(context, "FORECAST")),
            verticalAlignment = Alignment.Vertical.Top,
        ) {
            // Otsikkorivi: paikka + sarakeotsikot (FMI / Open-Meteo)
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    place,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                WxColHeader("FMI")
                WxColHeader("Open-M.")
            }
            if (hours.isEmpty()) {
                Text(
                    "Ladataan säätä…",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                    modifier = GlanceModifier.padding(top = 8.dp),
                )
            } else {
                // Koko päivän tunnit 00–23, skrollattava.
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(hours.size) { i -> WxHourRow(hours[i]) }
                }
            }
        }
    }
}

@Composable
private fun WxColHeader(label: String) {
    Box(modifier = GlanceModifier.width(54.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun WxHourRow(h: WxHour) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            "klo %02d".format(h.h),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        WxTempCell(wxTemp(h.fmi))
        WxTempCell(wxTemp(h.om))
    }
}

@Composable
private fun WxTempCell(text: String) {
    Box(modifier = GlanceModifier.width(54.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()
}
