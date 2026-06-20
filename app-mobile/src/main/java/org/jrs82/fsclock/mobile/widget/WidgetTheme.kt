package org.jrs82.fsclock.mobile.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as GlanceM3ColorProviders

/** Brandivarit Glancelle (vaalea/tumma). Arvot ArkikeskusTheme-paletista; ei Material You v1:ssa. */
object WidgetColors {
    private val Light = lightColorScheme(
        primary = Color(0xFF1B53C0),
        onPrimary = Color(0xFFFFFFFF),
        background = Color(0xFFFBFCFF),
        onBackground = Color(0xFF1A1B20),
        surface = Color(0xFFEDF0F9),
        onSurface = Color(0xFF1A1B20),
        onSurfaceVariant = Color(0xFF43474E),
    )
    private val Dark = darkColorScheme(
        primary = Color(0xFFB0C6FF),
        onPrimary = Color(0xFF002A78),
        background = Color(0xFF1D2026),
        onBackground = Color(0xFFE3E6ED),
        surface = Color(0xFF272A31),
        onSurface = Color(0xFFE3E6ED),
        onSurfaceVariant = Color(0xFFC4C6D0),
    )
    val providers: ColorProviders = GlanceM3ColorProviders(light = Light, dark = Dark)

    // Sahkon liikennevalovarit (vaalea/tumma) — ColorProvider valitsee teeman mukaan.
    val cheap = ColorProvider(day = Color(0xFF1E7D32), night = Color(0xFF7FD894))
    val normal = ColorProvider(day = Color(0xFF43474E), night = Color(0xFFC4C6D0))
    val expensive = ColorProvider(day = Color(0xFFC12018), night = Color(0xFFFFB4AB))
}
