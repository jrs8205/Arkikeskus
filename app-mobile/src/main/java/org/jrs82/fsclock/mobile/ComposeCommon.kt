package org.jrs82.fsclock.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Jaetut Compose-palikat (kelikamerat / lähilähdöt / reittihaku). Moodivärit/-ikonit
 * luetaan [TransitStyle]-apureista, jotka jaetaan reittihaun View-toteutuksen kanssa
 * (HSL-brändivärit pysyvät identtisinä kaikkialla).
 */

/**
 * Sovelluksen yhteinen kortti — M3 [Card] litteällä, modernilla ilmeellä: 26 dp pyöristys, kevyt
 * 1 dp varjo ja tasainen `surfaceContainer`-pinta. Yksi paikka kaikelle korttityylille → varjon/
 * kulmien/värin säätö tehdään täällä. Välittää [modifier]/[onClick]/[shape]/[colors] kuten Card;
 * [onClick] != null → klikattava kortti. Erikoiskortit (omat värit/muoto) toimivat antamalla colors/shape.
 */
@Composable
internal fun ArkiCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(26.dp),
    colors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors,
            elevation = elevation, content = content)
    } else {
        Card(modifier = modifier, shape = shape, colors = colors,
            elevation = elevation, content = content)
    }
}

/**
 * Ikoni-chip: 40 dp pyöristetty laatta, taustana aihevärin kevyt sävy + täysi-ikoni keskellä.
 * Otsikkorivin tunniste — väri ei ole ainoa signaali, otsikko on aina mukana (saavutettavuus).
 */
@Composable
internal fun ArkiIconChip(icon: Painter, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
    }
}

/**
 * Yhtenäinen korttiotsikko: [ikoni-chip] otsikko (+ valinnainen alaotsikko) ........... [trailing].
 * Pelkkä tyyli/asettelu — [trailing]-slottiin annetaan olemassa oleva toiminto (esim. "Kaikki"-nappi
 * tai status-pilli) sellaisenaan, toiminta ei muutu.
 */
@Composable
internal fun ArkiCardHeader(
    icon: Painter,
    accent: Color,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ArkiIconChip(icon, accent)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/**
 * Pilli (status / laskuri): aihevärin kevyt sävytausta + aihevärin teksti, täysin pyöristetty.
 * Valinnainen alkuikoni. Vain tyyli.
 */
@Composable
internal fun ArkiPill(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    leadingIcon: Painter? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            color = accent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** Hakukenttä: yksi rivi, ×-tyhjennys näkyy vain kun tekstiä on, IME-haku sulkee näppäimistön. */
@Composable
internal fun SearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    onSearch: () -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { if (onClear != null) onClear() else onValueChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Tyhjennä haku")
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            focusManager.clearFocus()
            onSearch()
        }),
    )
}

/** Kelluva status-/ilmoituschippi kartan tai listan päällä (vastaa View-puolen cam_status-tekstiä). */
@Composable
internal fun MapStatusChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Text(
            text,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** HSL-moodiväri Compose-värinä — sama lähde kuin View-puolella (TransitStyle.modeColor). */
@Composable
internal fun transitModeColor(mode: String?): Color {
    val ctx = LocalContext.current
    return Color(TransitStyle.modeColor(ctx, mode ?: ""))
}

/** Luettava teksti-/ikoniväri moodivärin päällä (musta vaaleilla, valkoinen tummilla HSL-väreillä). */
@Composable
internal fun transitOnModeColor(mode: String?): Color {
    val ctx = LocalContext.current
    return Color(TransitStyle.onModeColor(ctx, mode ?: ""))
}

/** Moodi-ikonin drawable-resurssi (vektori) — sama lähde kuin View-puolella. */
internal fun transitModeIconRes(mode: String?): Int = TransitStyle.modeIcon(mode ?: "")

/** Linjabadge moodivärillä (esim. "550" HSL-sinisellä) — vastaa item-layouttien badge-tyyliä. */
@Composable
internal fun TransitLineBadge(text: String, mode: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = transitModeColor(mode),
    ) {
        Text(
            text.ifEmpty { "?" },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = transitOnModeColor(mode),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}
