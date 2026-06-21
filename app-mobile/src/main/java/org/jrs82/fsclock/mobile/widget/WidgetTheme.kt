package org.jrs82.fsclock.mobile.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as GlanceM3ColorProviders

/**
 * Widgettien varit. Kaksi tasoa:
 *  - [providers]: M3 ColorProviders GlanceTheme-kaarteelle (komponenttioletukset).
 *  - Yksittaiset design-tokenit (Claude Design "widget-uudistus"): [c1]/[c2]/[pos]/[warn]/[neg]/
 *    [strong]/[text]/[dim]/[track]/[rowline] + esilasketut sava-taustat chipeille (color-mix ei
 *    toimi Glancessa). Teema (vaalea/tumma) seuraa jarjestelmaa; korttitausta on res-drawable
 *    (drawable/ + drawable-night/ widget_card_bg.xml).
 */
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

    // --- Design-tokenit ---
    // Aksentit (TEEMAKOHTAISET, brandi: primary-sininen / secondary-vihrea — sama linja kuin
    // ArkikeskusTheme). c1 = sininen (FMI/bussit), c2 = vihrea (Open-Meteo/askeleet).
    val c1 = ColorProvider(day = Color(0xFF1B53C0), night = Color(0xFFB0C6FF))
    val c2 = ColorProvider(day = Color(0xFF1E7D43), night = Color(0xFF8FD89E))
    // Teksti c1-taytetyn badgen paalla: vaalea teema = valkoinen, tumma = tumma teksti pastellilla.
    val c1On = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF002A78))

    // Hintatasot (teemakohtaiset, kontrasti).
    val pos = ColorProvider(day = Color(0xFF1F9D63), night = Color(0xFF46CF8B))
    val warn = ColorProvider(day = Color(0xFFC6881A), night = Color(0xFFE8B23D))
    val neg = ColorProvider(day = Color(0xFFD4552E), night = Color(0xFFE8704E))

    // Tekstit.
    val strong = ColorProvider(day = Color(0xFF141821), night = Color(0xFFF3F6FB))
    val text = ColorProvider(day = Color(0xFF3C4452), night = Color(0xFFC2C8D4))
    val dim = ColorProvider(day = Color(0xFF7B8493), night = Color(0xFF838B9B))

    // Viivat/raidat.
    val track = ColorProvider(day = Color(0x17101E2D), night = Color(0x1AFFFFFF))   // ~.09 / ~.10
    val rowline = ColorProvider(day = Color(0x0F101E2D), night = Color(0x0EFFFFFF)) // ~.06 / ~.055

    // Saaikonien lisavarit: yo (kuu/puolipilvi-yo) + lumi (frost). Kaytetaan weatherIcon()-mappauksessa.
    val night = ColorProvider(day = Color(0xFF5B6CB8), night = Color(0xFF8FB7FF))
    val frost = ColorProvider(day = Color(0xFF0097B2), night = Color(0xFF4FD4E8))

    // Esilasketut sava-taustat (color-mix korvike). Brandiaksentit -> TEEMAKOHTAISET savyt.
    val c1Tint = ColorProvider(day = Color(0x1F1B53C0), night = Color(0x1FB0C6FF))      // ~12 %
    val c1TintRow = ColorProvider(day = Color(0x241B53C0), night = Color(0x24B0C6FF))   // ~14 % (bussilista-lappu)
    val c2TintIcon = ColorProvider(day = Color(0x291E7D43), night = Color(0x298FD89E))  // ~16 % (ikoni-chip)
    val c2TintChip = ColorProvider(day = Color(0x261E7D43), night = Color(0x268FD89E))  // ~15 % (prosenttilappu)
    val posTintIcon = ColorProvider(day = Color(0x291F9D63), night = Color(0x2946CF8B))
    val posTintChip = ColorProvider(day = Color(0x261F9D63), night = Color(0x2646CF8B))
    val warnTintChip = ColorProvider(day = Color(0x26C6881A), night = Color(0x26E8B23D))
    val negTintChip = ColorProvider(day = Color(0x26D4552E), night = Color(0x26E8704E))

    // Sahkon liikennevalovarit (legacy; sailytetaan kunnes kaikki widgetit uusittu).
    val cheap = ColorProvider(day = Color(0xFF1E7D32), night = Color(0xFF7FD894))
    val normal = ColorProvider(day = Color(0xFF43474E), night = Color(0xFFC4C6D0))
    val expensive = ColorProvider(day = Color(0xFFC12018), night = Color(0xFFFFB4AB))

    // --- HSL-kulkumuotovarit (Glance-turvalliset ColorProviderit) ---
    // Varit ovat teemariippumattomat (samat vaalea/tumma), vrt. res/values/mobile_colors.xml
    // (mobile_transit_*). Glance ei voi resolvoida colorResource()-arvoja widgetissa eika kayttaa
    // @Composable transitOnModeColoria, joten arvot kiinteina tassa. Tekstivari = TransitStyle.
    // onColorFor-WCAG-valinta esilaskettuna (valk. busille/junalle, pehmea musta ratikalle/metrolle/
    // lautalle). HSL: bus #007AC9, tram #00985F, rail #8C4799, subway #FF6319, ferry #00B9E4.
    private fun transitHex(mode: String?): Long = when (mode) {
        "TRAM" -> 0xFF00985FL
        "RAIL" -> 0xFF8C4799L
        "SUBWAY" -> 0xFFFF6319L
        "FERRY" -> 0xFF00B9E4L
        else -> 0xFF007AC9L // BUS + oletus
    }
    // Paluutyyppia ei annoteta: tassa tiedostossa "ColorProvider" on factory-funktio
    // (androidx.glance.color), tyyppi tulee paatellysti (androidx.glance.unit.ColorProvider) — kuten c1.
    /** Taytetyn badgen tausta = moodin HSL-vari. */
    fun transitMode(mode: String?) =
        Color(transitHex(mode)).let { ColorProvider(day = it, night = it) }
    /** Hero-laatikon ~14 % savytausta moodivarilla. */
    fun transitModeTint(mode: String?) =
        Color(0x24000000L or (transitHex(mode) and 0x00FFFFFFL)).let { ColorProvider(day = it, night = it) }
    /** Luettava teksti/ikoni moodivarin paalla (WCAG: valk. tai pehmea musta). */
    fun transitOnMode(mode: String?) =
        (if (mode == "TRAM" || mode == "SUBWAY" || mode == "FERRY") Color(0xFF1A1A1A) else Color(0xFFFFFFFF))
            .let { ColorProvider(day = it, night = it) }
}
