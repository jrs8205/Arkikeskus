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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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

/** Yksi tunti: FMI- ja Open-Meteo-lämpötila (NaN/tyhjä jos lähde ei anna kyseiselle tunnille) +
 *  säätyyppi per lähde (WeatherCondition.Type.name) ikonia varten + yö-lippu. */
private data class WxHour(
    val h: Int,
    val fmi: Double, val om: Double,
    val fmiCond: String, val omCond: String,
    val night: Boolean,
)

private fun parseWeatherHours(json: String): List<WxHour> = try {
    val arr = org.json.JSONArray(json)
    (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        WxHour(
            o.optInt("h"),
            o.optDouble("f", Double.NaN), o.optDouble("o", Double.NaN),
            o.optString("fc", ""), o.optString("oc", ""),
            o.optBoolean("night", false),
        )
    }
} catch (e: Exception) {
    emptyList()
}

private fun wxTemp(v: Double): String {
    if (v.isNaN()) return "–"
    val s = if (Math.abs(v) < 0.5) 0.0 else v
    return "${Math.round(s)}°"
}

/** Säätyyppi (WeatherCondition.Type.name) + yö -> (ikoni, väri). Tyyppipohjainen → ei riipu
 *  käännöksistä eikä avainsanoista. */
private fun weatherIcon(condType: String, isNight: Boolean): Pair<Int, ColorProvider> =
    when (condType.uppercase()) {
        "THUNDER" -> R.drawable.mobile_ic_wx_thunder to WidgetColors.warn
        "SNOW", "SLEET" -> R.drawable.mobile_ic_wx_snow to WidgetColors.frost
        "RAIN" -> R.drawable.mobile_ic_rain_24 to WidgetColors.c1
        "FOG" -> R.drawable.mobile_ic_wx_fog to WidgetColors.dim
        "CLEAR" -> if (isNight) R.drawable.mobile_ic_wx_clear_night to WidgetColors.night
                   else R.drawable.mobile_ic_weather_24 to WidgetColors.warn
        "PARTLY_CLOUDY" -> if (isNight) R.drawable.mobile_ic_wx_partly_night to WidgetColors.night
                           else R.drawable.mobile_ic_wx_partly_day to WidgetColors.c1
        else -> R.drawable.mobile_ic_wx_cloud to WidgetColors.dim // CLOUDY / UNKNOWN / oletus
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
    // Hero-ikoni: FMI nykytilan tyyppi, fallback Open-Meteo (molemmat tallennettu type.name-muodossa).
    val fmiType = WidgetCache.weatherCondType(context)
    val condType = fmiType.ifBlank { WidgetCache.weatherOmCondType(context) }
    val condNight = if (fmiType.isNotBlank()) WidgetCache.weatherCondNight(context)
                    else WidgetCache.weatherOmCondNight(context)
    val (iconRes, iconColor) = weatherIcon(condType, condNight)
    val atMs = WidgetCache.weatherUpdatedAt(context).takeIf { it > 0 } ?: WidgetCache.weatherOmUpdatedAt(context)
    val hourLabel = if (atMs > 0)
        " · klo " + Instant.ofEpochMilli(atMs).atZone(ZoneId.of("Europe/Helsinki")).hour else ""
    val hours = parseWeatherHours(WidgetCache.weatherHoursJson(context))

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
            // Iso sääikoni + nykylämpötila (ei lähderivejä — vertailu on sarakkeissa alla)
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
            Spacer(GlanceModifier.height(16.dp))
            // Erotinviiva
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(WidgetColors.rowline)) {}
            Spacer(GlanceModifier.height(12.dp))
            // Sarakeotsikot: Ilmatieteen laitos (c1) | Open-Meteo (c2)
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                ColumnHeader(GlanceModifier.defaultWeight(), "Ilmatieteen laitos", WidgetColors.c1)
                ColumnHeader(GlanceModifier.defaultWeight(), "Open-Meteo", WidgetColors.c2)
            }
            Spacer(GlanceModifier.height(6.dp))
            // Tuntiennuste: seuraavat 24 h, kaksi saraketta, vierittyy
            if (hours.isEmpty()) {
                Text(
                    "Tuntiennustetta ei juuri nyt saatavilla",
                    style = TextStyle(color = WidgetColors.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    items(hours, itemId = { it.h.toLong() }) { h ->
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.Vertical.CenterVertically,
                        ) {
                            WxHalf(GlanceModifier.defaultWeight(), h.h, h.fmi, h.fmiCond, h.night, WidgetColors.c1)
                            Spacer(GlanceModifier.width(10.dp))
                            Box(modifier = GlanceModifier.width(1.dp).height(22.dp).background(WidgetColors.rowline)) {}
                            Spacer(GlanceModifier.width(10.dp))
                            WxHalf(GlanceModifier.defaultWeight(), h.h, h.om, h.omCond, h.night, WidgetColors.c2)
                        }
                    }
                }
            }
        }
    }
}

/** Sarakeotsikko: väripallo + lähteen nimi. */
@Composable
private fun ColumnHeader(modifier: GlanceModifier, title: String, color: ColorProvider) {
    Row(modifier = modifier, verticalAlignment = Alignment.Vertical.CenterVertically) {
        Box(modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp).background(color)) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(
            title,
            style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

/** Yksi tuntirivin puolikas: klo (vasen) + sääikoni + lämpötila (oikealla). */
@Composable
private fun WxHalf(
    modifier: GlanceModifier,
    hour: Int,
    temp: Double,
    condType: String,
    night: Boolean,
    tempColor: ColorProvider,
) {
    val (icon, iconColor) = weatherIcon(condType, night)
    Row(modifier = modifier, verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(
            "klo %02d".format(hour),
            style = TextStyle(color = WidgetColors.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Spacer(GlanceModifier.defaultWeight())
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconColor),
            modifier = GlanceModifier.size(19.dp),
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            wxTemp(temp),
            style = TextStyle(color = tempColor, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()
}
