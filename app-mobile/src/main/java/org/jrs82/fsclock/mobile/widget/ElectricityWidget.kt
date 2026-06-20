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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.preference.PreferenceManager
import java.time.ZoneId
import java.util.Locale

class ElectricityWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { ElectricityContent(context) }
    }
}

@Composable
private fun ElectricityContent(context: Context) {
    val snt = WidgetCache.electricitySnt(context)
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    // KEY ja DEFAULT kovakoodattu merkkijonona (MobileThemeController on package-private).
    val threshold = (prefs.getString("mobile_cheap_electricity_threshold", "5.0") ?: "5.0")
        .trim().replace(',', '.').toDoubleOrNull() ?: 5.0
    val level = WidgetFormat.priceLevel(snt, threshold)
    val color = when (level) {
        PriceLevel.CHEAP -> WidgetColors.cheap
        PriceLevel.NORMAL -> WidgetColors.normal
        PriceLevel.EXPENSIVE -> WidgetColors.expensive
    }
    val sntSafe = if (!snt.isNaN() && Math.abs(snt) < 0.0005) 0.0 else snt
    val priceText = if (sntSafe.isNaN()) "–" else String.format(Locale("fi", "FI"), "%.3f c/kWh", sntSafe)
    val updated = WidgetCache.electricityUpdatedAt(context)
    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(actionStartActivity(WidgetDeepLink.deepLinkIntent(context, "ELECTRICITY"))),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                "Pörssisähkö",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Text(
                priceText,
                style = TextStyle(
                    color = color,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                WidgetFormat.priceLabel(level),
                style = TextStyle(
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (updated > 0) {
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

class ElectricityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ElectricityWidget()
}
