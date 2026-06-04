package org.jrs82.fsclock.mobile

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/**
 * Arkikeskuksen Compose-teema (Material 3) — OSA B: kirkas, eloisa brändipaletti.
 *
 * Värilinja (lukittu 4.6.2026, ohje ARKIKESKUS_OHJE.md OSA B): oma **kirkas brändipaletti on
 * OLETUS**, dynamic color (Material You) on valinnainen kytkin ([MobileThemeController.KEY_DYNAMIC_COLOR],
 * oletus false). Brändi-identiteetti tulee sovelluksen ikonista: sininen→vihreä gradientti.
 * Primary = kirkas sininen, secondary = vihreä, tertiary = lämmin oranssi (eloisuus/kontrasti).
 *
 * Semanttiset korttivärit ([ArkiColors]) ovat brändikohtaisia ja PYSYVÄT samoina vaikka käyttäjä
 * vaihtaisi dynaamisiin väreihin → "sama korttiväri = sama asia" säilyy (OSA B / B2, B7). Container/
 * on-container-parit noudattavat M3:n tone 90/10 (vaalea) ja 30/90 (tumma) -konventiota → WCAG-turvallisia.
 *
 * Vaalea/tumma seuraa Activityn yötilaa, jota [MobileThemeController] ohjaa (light/dark/system)
 * AppCompatin kautta → isSystemInDarkTheme() lukee sen.
 */

// ----------------------------------------------------------------------------
// Brändipaletti — VAALEA
// ----------------------------------------------------------------------------
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B53C0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001551),
    secondary = Color(0xFF1E7D43),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB8F0C4),
    onSecondaryContainer = Color(0xFF00210E),
    tertiary = Color(0xFFB5530F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF341100),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFCFF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFBFCFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFDEE2F0),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = Color(0xFF1B53C0),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    surfaceBright = Color(0xFFFBFCFF),
    surfaceDim = Color(0xFFD9DDE8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5FC),
    surfaceContainer = Color(0xFFEDF0F9),
    surfaceContainerHigh = Color(0xFFE7EBF5),
    surfaceContainerHighest = Color(0xFFE1E6F1),
)

// ----------------------------------------------------------------------------
// Brändipaletti — TUMMA (ei täysmustaa; värisävytetyt pinnat — OSA B / B5)
// ----------------------------------------------------------------------------
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF00419E),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF8FD89E),
    onSecondary = Color(0xFF00391B),
    secondaryContainer = Color(0xFF00522B),
    onSecondaryContainer = Color(0xFFABF5B8),
    tertiary = Color(0xFFFFB68A),
    onTertiary = Color(0xFF552100),
    tertiaryContainer = Color(0xFF783200),
    onTertiaryContainer = Color(0xFFFFDCC2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E6ED),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E6ED),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = Color(0xFFB0C6FF),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF43474E),
    surfaceBright = Color(0xFF37393F),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C22),
    surfaceContainer = Color(0xFF1D2026),
    surfaceContainerHigh = Color(0xFF272A31),
    surfaceContainerHighest = Color(0xFF32353C),
)

// ----------------------------------------------------------------------------
// Semanttiset korttivärit (OSA B / B2) — aihekohtainen värikoodaus.
// Väri ei ole KOSKAAN ainoa informaation kantaja: aina ikoni/teksti mukana (B7).
// ----------------------------------------------------------------------------
@Immutable
data class ArkiColors(
    // Pörssisähkö — liikennevalo
    val priceCheap: Color,
    val priceCheapContainer: Color,
    val onPriceCheapContainer: Color,
    val priceNormal: Color,
    val priceNormalContainer: Color,
    val onPriceNormalContainer: Color,
    val priceExpensive: Color,
    val priceExpensiveContainer: Color,
    val onPriceExpensiveContainer: Color,
    // Varoitukset (liikennetiedot ym.)
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    // Sää
    val weatherAccent: Color,
    val weatherContainer: Color,
    val onWeatherContainer: Color,
    val weatherSunny: Color,
    val weatherRain: Color,
    val weatherFrost: Color,
    // Anturit — lämpötilagradientti (kylmä → lämmin)
    val sensorCold: Color,
    val sensorWarm: Color,
    // Terveys
    val health: Color,
    val healthContainer: Color,
    val onHealthContainer: Color,
    // Uutiset
    val newsAccent: Color,
    val newsContainer: Color,
    val onNewsContainer: Color,
    // HSL
    val transitBus: Color,
    // Kello-gradientti (kaksi sinisen sävyä, OSA B / B6)
    val clockTop: Color,
    val clockBottom: Color,
) {
    /** Lämpötilan (°C) mukainen anturiväri: lerp kylmästä lämpimään välillä -15…30 °C. */
    fun forTemperature(celsius: Double?): Color {
        if (celsius == null || celsius.isNaN()) return sensorCold
        val t = ((celsius + 15.0) / 45.0).coerceIn(0.0, 1.0).toFloat()
        return lerp(sensorCold, sensorWarm, t)
    }
}

private val LightArki = ArkiColors(
    priceCheap = Color(0xFF1E7D32),
    priceCheapContainer = Color(0xFFC8F0CF),
    onPriceCheapContainer = Color(0xFF00210B),
    priceNormal = Color(0xFFA66A00),
    priceNormalContainer = Color(0xFFFFE8B8),
    onPriceNormalContainer = Color(0xFF2A1A00),
    priceExpensive = Color(0xFFC12018),
    priceExpensiveContainer = Color(0xFFFFDAD5),
    onPriceExpensiveContainer = Color(0xFF410001),
    warning = Color(0xFFA14E10),
    warningContainer = Color(0xFFFFDCC2),
    onWarningContainer = Color(0xFF341100),
    weatherAccent = Color(0xFF1B53C0),
    weatherContainer = Color(0xFFDCE6FF),
    onWeatherContainer = Color(0xFF001551),
    weatherSunny = Color(0xFFE8A100),
    weatherRain = Color(0xFF1565C0),
    weatherFrost = Color(0xFF0097B2),
    sensorCold = Color(0xFF1565C0),
    sensorWarm = Color(0xFFD2611A),
    health = Color(0xFF1E7D32),
    healthContainer = Color(0xFFC8F0CF),
    onHealthContainer = Color(0xFF00210B),
    newsAccent = Color(0xFFB5530F),
    newsContainer = Color(0xFFFFEBD6),
    onNewsContainer = Color(0xFF2A1500),
    transitBus = Color(0xFF007AC9),
    clockTop = Color(0xFFBFD3FF),
    clockBottom = Color(0xFFDEE8FF),
)

private val DarkArki = ArkiColors(
    priceCheap = Color(0xFF7FD894),
    priceCheapContainer = Color(0xFF00522A),
    onPriceCheapContainer = Color(0xFF9BF5AE),
    priceNormal = Color(0xFFF4C14E),
    priceNormalContainer = Color(0xFF5A4100),
    onPriceNormalContainer = Color(0xFFFFE08A),
    priceExpensive = Color(0xFFFFB4AB),
    priceExpensiveContainer = Color(0xFF93000A),
    onPriceExpensiveContainer = Color(0xFFFFDAD5),
    warning = Color(0xFFFFB68A),
    warningContainer = Color(0xFF6E3000),
    onWarningContainer = Color(0xFFFFDCC2),
    weatherAccent = Color(0xFFB0C6FF),
    weatherContainer = Color(0xFF1C3A78),
    onWeatherContainer = Color(0xFFDAE2FF),
    weatherSunny = Color(0xFFFFCF5C),
    weatherRain = Color(0xFF8FB7FF),
    weatherFrost = Color(0xFF4FD4E8),
    sensorCold = Color(0xFF8FB7FF),
    sensorWarm = Color(0xFFFFB68A),
    health = Color(0xFF7FD894),
    healthContainer = Color(0xFF00522A),
    onHealthContainer = Color(0xFF9BF5AE),
    newsAccent = Color(0xFFFFB68A),
    newsContainer = Color(0xFF5A2600),
    onNewsContainer = Color(0xFFFFDCC2),
    transitBus = Color(0xFF4FA8E0),
    clockTop = Color(0xFF003C90),
    clockBottom = Color(0xFF0A4AA8),
)

private val LocalArkiColors = staticCompositionLocalOf { LightArki }

/** Ergonominen pääsy semanttisiin korttiväreihin: `ArkiTheme.colors.priceCheap` ym. */
object ArkiTheme {
    val colors: ArkiColors
        @Composable @ReadOnlyComposable get() = LocalArkiColors.current
}

@Composable
fun ArkikeskusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalArkiColors provides if (darkTheme) DarkArki else LightArki) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content,
        )
    }
}
