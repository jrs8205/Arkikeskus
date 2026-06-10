package org.jrs82.fsclock.mobile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Jaetut Compose-palikat migroitaville näytöille (kelikamerat / lähilähdöt / reittihaku).
 * Korvaavat View-puolen EditText+×-kuviot ja moodibadget yhtenäisellä M3-ilmeellä.
 * Moodivärit/-ikonit luetaan [TransitAdapter]in olemassa olevista static-apureista,
 * jotta värit pysyvät identtisinä View-toteutuksen kanssa (HSL-brändivärit).
 */

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

/** HSL-moodiväri Compose-värinä — sama lähde kuin View-puolella (TransitAdapter.modeColor). */
@Composable
internal fun transitModeColor(mode: String?): Color {
    val ctx = LocalContext.current
    return Color(TransitAdapter.modeColor(ctx, mode ?: ""))
}

/** Moodi-ikonin drawable-resurssi (vektori) — sama lähde kuin View-puolella. */
internal fun transitModeIconRes(mode: String?): Int = TransitAdapter.modeIcon(mode ?: "")

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
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}
