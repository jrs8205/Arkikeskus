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
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
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
    val goal = WidgetCache.stepsGoal(context)
    val pct = WidgetFormat.stepsPercent(steps, goal)
    val fiFmt = NumberFormat.getInstance(Locale("fi", "FI"))
    val stepsText = fiFmt.format(steps)
    val goalText = fiFmt.format(goal)
    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(WidgetDeepLink.openSection(context, "STEPS")),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                "Askeleet",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Text(
                stepsText,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            // LinearProgressIndicator varmennettu: Glance 1.1.1 tukee parametrit
            // progress: Float, modifier, color: ColorProvider, backgroundColor: ColorProvider.
            LinearProgressIndicator(
                progress = pct / 100f,
                modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.onSurfaceVariant,
            )
            Text(
                "$pct % · tavoite $goalText",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

class StepsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepsWidget()
}
