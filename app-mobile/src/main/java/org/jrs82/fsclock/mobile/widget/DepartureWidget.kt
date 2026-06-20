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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.jrs82.fsclock.mobile.transitModeIconRes
import java.time.ZoneId

class DepartureWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // Fix B (self-heal): widget on tässä kohtaa sidottu (bound), joten GlanceId on validi.
        // Jos widget on konfiguroitu mutta sillä ei ole vielä yhtään dataa (d_at == 0),
        // käynnistetään worker heti — se hakee lähdöt ja kutsuu updateAll, jolloin widget
        // päivittyy ilman ylimääräistä käyttäjän toimintaa.
        // Loop-esto: worker kirjoittaa tyhjän merkin (d_at != 0) myös epäonnistuneesta hausta
        // kun dataa ei ole aiemmin ollut, joten tämä haara laukeaa vain KERRAN per widget.
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
        else -> "$rawName  $code"
    }
    val deps = WidgetFormat.decodeDepartures(WidgetCache.departureJson(context, appWidgetId))
    val now = System.currentTimeMillis() / 1000L
    val updated = WidgetCache.departureUpdatedAt(context, appWidgetId)
    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(actionStartActivity(WidgetDeepLink.deepLinkIntent(context, "TRANSIT"))),
        ) {
            Text(
                stop,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (deps.isEmpty()) {
                Text(
                    "Ei lähtöjä",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 13.sp,
                    ),
                )
            } else {
                deps.take(3).forEach { d ->
                    val min = WidgetFormat.minutesLabel(WidgetFormat.minutesUntil(d.epochSec, now))
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Image(
                            provider = ImageProvider(transitModeIconRes(d.mode)),
                            contentDescription = null,
                            modifier = GlanceModifier.size(18.dp),
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
                        )
                        Spacer(GlanceModifier.width(4.dp))
                        Text(
                            d.line,
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Text(
                            "  $min",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 14.sp,
                            ),
                        )
                    }
                }
            }
            if (updated > 0) {
                Text(
                    "päiv. ${WidgetFormat.clockLabel(updated, ZoneId.of("Europe/Helsinki"))}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                    ),
                    modifier = GlanceModifier.padding(top = 4.dp),
                )
            }
        }
    }
}

class DepartureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DepartureWidget()
}
