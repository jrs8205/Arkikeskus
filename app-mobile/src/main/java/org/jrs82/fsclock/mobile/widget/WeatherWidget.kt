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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
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
    val temp = WidgetFormat.tempLabel(WidgetCache.weatherTempC(context))
    val cond = WidgetCache.weatherCondition(context)
    val wind = WidgetCache.weatherWind(context)
    val updated = WidgetCache.weatherUpdatedAt(context)

    // Ladataan etukäteen piirretty ikoni tiedostosta; jos ei löydy, käytetään geneeristä.
    val iconFile = File(context.filesDir, "widget_weather_icon.png")
    val iconProvider: ImageProvider = if (iconFile.exists()) {
        try {
            val bmp = BitmapFactory.decodeFile(iconFile.absolutePath)
            if (bmp != null) ImageProvider(bmp) else ImageProvider(R.drawable.mobile_ic_weather_24)
        } catch (_: Exception) {
            ImageProvider(R.drawable.mobile_ic_weather_24)
        }
    } else {
        ImageProvider(R.drawable.mobile_ic_weather_24)
    }

    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(actionStartActivity(WidgetDeepLink.deepLinkIntent(context, "HOME"))),
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
            // Ikoni + lämpötila vierekkäin
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                Image(
                    provider = iconProvider,
                    contentDescription = cond.ifBlank { null },
                    modifier = GlanceModifier.size(52.dp),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    temp,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            // Säätila-teksti (luettava, ei toString)
            if (cond.isNotBlank()) {
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    cond,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                    maxLines = 1,
                )
            }
            // Tuuli
            if (!wind.isNaN()) {
                Spacer(GlanceModifier.height(2.dp))
                val windStr = String.format(java.util.Locale("fi", "FI"), "Tuuli %.1f m/s", wind)
                Text(
                    windStr,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }
            // Päivitysaika
            if (updated > 0) {
                Spacer(GlanceModifier.height(2.dp))
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

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()
}
