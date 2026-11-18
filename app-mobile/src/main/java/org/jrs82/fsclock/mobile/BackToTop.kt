package org.jrs82.fsclock.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jrs82.fsclock.R

/** Montako listan kohdetta on vieritettävä ohi, ennen kuin kelluva Takaisin ylös -painike näkyy. */
internal const val BACK_TO_TOP_MIN_INDEX = 8

/** Listan loppuun jätettävä tila, ettei kelluva painike peitä viimeistä kohdetta. */
internal val BACK_TO_TOP_CLEARANCE = 80.dp

/** Laiska lista: painike näkyy, kun vähintään [BACK_TO_TOP_MIN_INDEX] kohdetta on vieritetty ohi. */
internal fun backToTopVisible(firstVisibleIndex: Int): Boolean = firstVisibleIndex >= BACK_TO_TOP_MIN_INDEX

/** Tavallinen vieritys: painike näkyy, kun on vieritetty vähintään puolitoista näkymän korkeutta. */
internal fun backToTopVisible(scrollPx: Int, viewportPx: Int): Boolean =
    viewportPx > 0 && scrollPx >= viewportPx * 3 / 2

/**
 * Kelluva Takaisin ylös -painike pitkien listojen alareunaan (uutiset). Sama ulkoasu kuin sähkösivun
 * Siirry nykyhetkeen -painikkeella; häivytetään esiin ja pois.
 */
@Composable
internal fun FloatingBackToTop(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val cs = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .shadow(6.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(cs.primaryContainer)
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.mobile_ic_arrow_up_24),
                contentDescription = null,
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.common_back_to_top),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = cs.onPrimaryContainer,
            )
        }
    }
}
