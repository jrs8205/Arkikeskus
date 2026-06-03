package org.jrs82.fsclock.mobile

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Arkikeskuksen Compose-teema (Material 3). Käyttää Android 12+ dynamic coloria (Material You)
 * ja putoaa brändipalettiin vanhemmilla. Vaalea/tumma seuraa Activityn yötilaa, jota
 * [MobileThemeController] ohjaa (light/dark/system) AppCompatin kautta → isSystemInDarkTheme() lukee sen.
 */
private val BrandBlue = Color(0xFF1D4ED8)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF1565C0),
    tertiary = Color(0xFF00897B),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE6ECF5),
    onSurfaceVariant = Color(0xFF44474E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C2FF),
    onPrimary = Color(0xFF0A1B3D),
    secondary = Color(0xFF9FB8E8),
    tertiary = Color(0xFF6FD3C4),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE3E6EC),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE3E6EC),
    surfaceVariant = Color(0xFF232A35),
    onSurfaceVariant = Color(0xFFC0C6D0),
)

@Composable
fun ArkikeskusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
