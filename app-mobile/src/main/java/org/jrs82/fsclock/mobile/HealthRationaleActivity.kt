package org.jrs82.fsclock.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jrs82.fsclock.R

/**
 * Health Connect -lupien perustelunäkymä. Järjestelmä avaa tämän kun käyttäjä haluaa
 * tietää, miksi Arkikeskus pyytää terveysdatan lukuoikeuksia:
 * - Android 13−: `androidx.health.connect.action.SHOW_PERMISSIONS_RATIONALE` -intent.
 * - Android 14+: `VIEW_PERMISSION_USAGE` + HEALTH_PERMISSIONS -alias osoittaa tähän.
 * Korvasi 5000-rivisen legacy-MobileMainActivityn tässä roolissa (Compose-migraatio).
 */
class HealthRationaleActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(FontScaleCap.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        MobileThemeController.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ArkikeskusTheme(dynamicColor = MobileThemeController.dynamicColor(this)) {
                HealthRationaleScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthRationaleScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.settings_health_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.mobile_ic_arrow_back),
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ArkiCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_health_why),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.settings_health_reads),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.settings_health_privacy),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.settings_health_revoke),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
