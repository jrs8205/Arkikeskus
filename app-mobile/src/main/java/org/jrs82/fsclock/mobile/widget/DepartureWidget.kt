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
import androidx.glance.appwidget.GlanceAppWidgetManager
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
import org.jrs82.fsclock.R
import org.jrs82.fsclock.mobile.transitModeIconRes
import java.time.ZoneId

class DepartureWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // Self-heal: konfiguroitu mutta dataton widget -> kaynnista worker kerran (ks. vanha selitys).
        if (WidgetCache.departureMode(context, appWidgetId).isNotBlank() &&
            WidgetCache.departureUpdatedAt(context, appWidgetId) == 0L) {
            WidgetUpdateWorker.refreshNowForce(context)
        }
        provideContent { DepartureContent(context, appWidgetId) }
    }
}

@Composable
private fun DepartureContent(context: Context, appWidgetId: Int) {
    val rawName = WidgetCache.departureStopLabel(context, appWidgetId)
    val code = WidgetCache.departureStopCode(context, appWidgetId)
    val stop = when {
        rawName.isBlank() -> "Seuraava lähtö"
        code.isBlank() -> rawName
        else -> "$rawName $code"
    }
    val deps = WidgetFormat.decodeDepartures(WidgetCache.departureJson(context, appWidgetId))
    val now = System.currentTimeMillis() / 1000L
    val updated = WidgetCache.departureUpdatedAt(context, appWidgetId)

    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(ImageProvider(R.drawable.widget_card_bg))
                .cornerRadius(26.dp)
                .padding(20.dp)
                .clickable(WidgetDeepLink.openSection(context, "TRANSIT")),
        ) {
            Text(
                stop,
                style = TextStyle(color = WidgetColors.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(14.dp))

            if (deps.isEmpty()) {
                Text(
                    "Ei lähtöjä juuri nyt",
                    style = TextStyle(color = WidgetColors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
            } else {
                // Hero: seuraava lahto
                val first = deps.first()
                val firstMin = WidgetFormat.minutesLabel(WidgetFormat.minutesUntil(first.epochSec, now))
                Box(
                    modifier = GlanceModifier.fillMaxWidth().cornerRadius(18.dp)
                        .background(WidgetColors.transitModeTint(first.mode)).padding(16.dp),
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Box(
                            modifier = GlanceModifier.cornerRadius(11.dp).background(WidgetColors.transitMode(first.mode))
                                .height(42.dp).padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                first.line,
                                style = TextStyle(color = WidgetColors.transitOnMode(first.mode), fontSize = 20.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                            )
                        }
                        Spacer(GlanceModifier.width(14.dp))
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                "seuraava lähtö",
                                style = TextStyle(color = WidgetColors.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                            )
                            Text(
                                firstMin,
                                style = TextStyle(color = WidgetColors.transitMode(first.mode), fontSize = 34.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                            )
                        }
                        Image(
                            provider = ImageProvider(transitModeIconRes(first.mode)),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(WidgetColors.transitMode(first.mode)),
                            modifier = GlanceModifier.size(36.dp),
                        )
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                // Loput lahdot
                deps.drop(1).take(4).forEach { d ->
                    val m = WidgetFormat.minutesLabel(WidgetFormat.minutesUntil(d.epochSec, now))
                    Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(WidgetColors.rowline)) {}
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Image(
                            provider = ImageProvider(transitModeIconRes(d.mode)),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(WidgetColors.transitMode(d.mode)),
                            modifier = GlanceModifier.size(20.dp),
                        )
                        Spacer(GlanceModifier.width(10.dp))
                        Box(
                            modifier = GlanceModifier.cornerRadius(8.dp).background(WidgetColors.transitMode(d.mode))
                                .height(27.dp).padding(horizontal = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                d.line,
                                style = TextStyle(color = WidgetColors.transitOnMode(d.mode), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                            )
                        }
                        Spacer(GlanceModifier.defaultWeight())
                        Text(
                            m,
                            style = TextStyle(color = WidgetColors.strong, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                    }
                }
            }

            if (updated > 0) {
                Spacer(GlanceModifier.height(10.dp))
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Image(
                        provider = ImageProvider(R.drawable.mobile_ic_clock_24),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(WidgetColors.dim),
                        modifier = GlanceModifier.size(15.dp),
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        "Päivitetty ${WidgetFormat.clockLabel(updated, ZoneId.of("Europe/Helsinki"))}",
                        style = TextStyle(color = WidgetColors.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

class DepartureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DepartureWidget()
}
