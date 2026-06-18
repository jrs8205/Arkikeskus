package org.jrs82.fsclock.mobile

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
                title = { Text("Terveystietojen käyttö") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.mobile_ic_arrow_back), contentDescription = "Takaisin")
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
                        "Miksi Arkikeskus pyytää Health Connect -oikeuksia?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Arkikeskus lukee Health Connectista askeleet ja kalorit (aktiiviset ja " +
                            "kokonaiskulutuksen), jotta Askeleet-sivu voi näyttää päivän askelmäärän, " +
                            "kaloriarvion sekä päivä-, viikko- ja kuukausihistorian.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Tietoja käytetään vain näyttämiseen tässä sovelluksessa ja paikalliseen " +
                            "HTML-vientiin, jonka teet itse. Tietoja ei lähetetä verkkoon eikä jaeta " +
                            "kolmansille osapuolille. Sovellus ei kirjoita mitään Health Connectiin.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Voit perua oikeudet milloin tahansa Health Connectin asetuksista — " +
                            "askelmittari toimii silloin puhelimen oman anturin varassa.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
