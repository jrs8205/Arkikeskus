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
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
        provideContent { RenderRevision { DepartureContent(context, appWidgetId) } }
    }
}

@Composable
private fun DepartureContent(context: Context, appWidgetId: Int) {
    val rawName = WidgetCache.departureStopLabel(context, appWidgetId)
    val code = WidgetCache.departureStopCode(context, appWidgetId)
    val stop = when {
        rawName.isBlank() -> context.getString(R.string.transit_widget_next_departure)
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
                style = TextStyle(color = WidgetColors.dim, fontSize = widgetSp(13), fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(14.dp))

            if (deps.isEmpty()) {
                Text(
                    context.getString(R.string.transit_widget_no_departures),
                    style = TextStyle(color = WidgetColors.text, fontSize = widgetSp(14), fontWeight = FontWeight.Medium),
                )
            } else {
                // Hero: seuraava lahto
                val first = deps.first()
                val firstMin = departureMinutesLabel(context, WidgetFormat.minutesUntil(first.epochSec, now))
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
                                style = TextStyle(color = WidgetColors.transitOnMode(first.mode), fontSize = widgetSp(20), fontWeight = FontWeight.Bold),
                                maxLines = 1,
                            )
                        }
                        Spacer(GlanceModifier.width(14.dp))
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                context.getString(R.string.transit_widget_next_departure_label),
                                style = TextStyle(color = WidgetColors.dim, fontSize = widgetSp(12), fontWeight = FontWeight.Medium),
                                maxLines = 1,
                            )
                            Text(
                                firstMin,
                                style = TextStyle(color = WidgetColors.transitMode(first.mode), fontSize = widgetSp(34), fontWeight = FontWeight.Bold),
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
                // Loput lahdot — oma Column, koska Glance-kontilla on max 10 LASTA ja paa-Column
                // tayttyisi muuten (hero + 4×(viiva+rivi) + Paivitetty > 10 -> osa tippuisi).
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    deps.drop(1).take(4).forEach { d ->
                        val m = departureMinutesLabel(context, WidgetFormat.minutesUntil(d.epochSec, now))
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
                                    style = TextStyle(color = WidgetColors.transitOnMode(d.mode), fontSize = widgetSp(14), fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                )
                            }
                            Spacer(GlanceModifier.defaultWeight())
                            Text(
                                m,
                                style = TextStyle(color = WidgetColors.strong, fontSize = widgetSp(16), fontWeight = FontWeight.Bold),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // Alarivi: "Päivitetty" vasemmalla, refresh-nappi alaoikealla.
            Spacer(GlanceModifier.height(10.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                if (updated > 0) {
                    Image(
                        provider = ImageProvider(R.drawable.mobile_ic_clock_24),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(WidgetColors.dim),
                        modifier = GlanceModifier.size(15.dp),
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        context.getString(
                            R.string.common_updated_at,
                            WidgetFormat.clockLabel(updated, ZoneId.of("Europe/Helsinki")),
                        ),
                        style = TextStyle(color = WidgetColors.dim, fontSize = widgetSp(12), fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.defaultWeight())
                // Päivitysnappi: ajaa workerin heti (hakee lähdöt uudelleen + piirtää widgetit).
                Box(
                    modifier = GlanceModifier.size(30.dp).cornerRadius(8.dp).background(WidgetColors.track)
                        .clickable(actionRunCallback<DepartureRefreshAction>()),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.mobile_ic_refresh_24),
                        contentDescription = context.getString(R.string.common_refresh),
                        colorFilter = ColorFilter.tint(WidgetColors.c1),
                        modifier = GlanceModifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** "nyt"/"nu"/"now" tai "N min" sovelluksen kielellä — WidgetFormat.minutesLabel on Context-vapaa
 *  eikä siksi voi lukea käännöstä. */
private fun departureMinutesLabel(context: Context, minutes: Int): String =
    if (minutes <= 0) {
        context.getString(R.string.transit_dep_now)
    } else {
        context.resources.getQuantityString(R.plurals.transit_minutes, minutes, minutes)
    }

/** Refresh-napin toiminto: ajaa widget-workerin heti (hakee lähdöt uudelleen + updateAll). */
class DepartureRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetUpdateWorker.refreshNow(context)
    }
}

class DepartureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DepartureWidget()
}
