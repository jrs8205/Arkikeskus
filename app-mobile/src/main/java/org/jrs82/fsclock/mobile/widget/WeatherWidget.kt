package org.jrs82.fsclock.mobile.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.jrs82.fsclock.R
import java.io.File
import java.time.ZoneId

class WeatherWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WeatherContent(context) }
    }
}

@Composable
private fun WeatherContent(context: Context) {
    val place = WidgetCache.weatherPlace(context).ifBlank { "Sää" }
    val fmiTemp = WidgetFormat.tempLabel(WidgetCache.weatherTempC(context))
    val fmiWind = WidgetCache.weatherWind(context)
    val omTemp = WidgetFormat.tempLabel(WidgetCache.weatherOmTempC(context))
    val omWind = WidgetCache.weatherOmWind(context)
    val updated = WidgetCache.weatherUpdatedAt(context)

    // FMI- ja Open-Meteo-ikonit erikseen (worker piirtää molemmat tiedostoon); fallback geneeriseen.
    val fmiIcon = loadWeatherIcon(context, "widget_weather_icon.png")
    val omIcon = loadWeatherIcon(context, "widget_weather_icon_om.png")

    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(20.dp)
                .padding(12.dp)
                .clickable(WidgetDeepLink.openSection("FORECAST")),
            verticalAlignment = Alignment.Vertical.Top,
        ) {
            // Paikannimi ylhäällä
            Text(
                place,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(6.dp))
            // Kaksi lähdettä vierekkäin: FMI vasen, Open-Meteo oikea
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                WeatherSourceColumn(GlanceModifier.defaultWeight(), "FMI", fmiIcon, fmiTemp, fmiWind)
                Spacer(GlanceModifier.width(8.dp))
                WeatherSourceColumn(GlanceModifier.defaultWeight(), "Open-Meteo", omIcon, omTemp, omWind)
            }
            // Päivitysaika
            if (updated > 0) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    "päiv. ${WidgetFormat.clockLabel(updated, ZoneId.of("Europe/Helsinki"))}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun WeatherSourceColumn(
    modifier: GlanceModifier,
    source: String,
    icon: ImageProvider,
    temp: String,
    wind: Double,
) {
    Column(modifier = modifier) {
        Text(
            source,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            Image(
                provider = icon,
                contentDescription = null,
                modifier = GlanceModifier.size(30.dp),
            )
            Spacer(GlanceModifier.width(4.dp))
            Text(
                temp,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
        if (!wind.isNaN()) {
            Text(
                String.format(java.util.Locale("fi", "FI"), "%.1f m/s", wind),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/** Lataa workerin piirtämän ikonibittikartan; fallback geneeriseen vektoriin jos puuttuu. */
private fun loadWeatherIcon(context: Context, fileName: String): ImageProvider {
    val f = File(context.filesDir, fileName)
    if (f.exists()) {
        try {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            if (bmp != null) return ImageProvider(bmp)
        } catch (_: Exception) { }
    }
    return ImageProvider(R.drawable.mobile_ic_weather_24)
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()
}
