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
import org.jrs82.fsclock.R
import java.text.NumberFormat
import java.util.Locale

class StepsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { StepsContent(context) }
    }
}

@Composable
private fun StepsContent(context: Context) {
    val steps = WidgetCache.steps(context)
    val goal = WidgetCache.stepsGoal(context).coerceAtLeast(1)
    val pct = WidgetFormat.stepsPercent(steps, goal)
    val fiFmt = NumberFormat.getInstance(Locale("fi", "FI"))
    val filled = (steps / 1000).coerceIn(0, 10) // joka palkki = 1000 askelta

    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(ImageProvider(R.drawable.widget_card_bg))
                .cornerRadius(26.dp)
                .padding(20.dp)
                .clickable(WidgetDeepLink.openSection(context, "STEPS")),
        ) {
            // Otsikkorivi: ikoni-chip + "Askeleet"  ...  prosenttilappu oikealla
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Box(
                    modifier = GlanceModifier.size(40.dp).cornerRadius(12.dp)
                        .background(WidgetColors.c2TintIcon),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.mobile_ic_transit_walk),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(WidgetColors.c2),
                        modifier = GlanceModifier.size(23.dp),
                    )
                }
                Spacer(GlanceModifier.width(11.dp))
                Text(
                    "Askeleet",
                    style = TextStyle(color = WidgetColors.dim, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.defaultWeight())
                Box(
                    modifier = GlanceModifier.cornerRadius(8.dp).background(WidgetColors.c2TintChip)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "$pct %",
                        style = TextStyle(color = WidgetColors.c2, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
            }
            Spacer(GlanceModifier.height(16.dp))
            // Iso luku / tavoite
            Row(verticalAlignment = Alignment.Vertical.Bottom) {
                Text(
                    fiFmt.format(steps),
                    style = TextStyle(color = WidgetColors.strong, fontSize = 40.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    "/ ${fiFmt.format(goal)}",
                    style = TextStyle(color = WidgetColors.dim, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(16.dp))
            // 10 segmenttipalkkia (taytetyt = c2)
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                for (i in 0 until 10) {
                    Box(
                        modifier = GlanceModifier.defaultWeight().height(16.dp).cornerRadius(5.dp)
                            .background(if (i < filled) WidgetColors.c2 else WidgetColors.track),
                    ) {}
                    if (i < 9) Spacer(GlanceModifier.width(5.dp))
                }
            }
            Spacer(GlanceModifier.height(12.dp))
            Text(
                "Tavoite ${fiFmt.format(goal)} · joka palkki = 1 000",
                style = TextStyle(color = WidgetColors.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

class StepsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepsWidget()
}
