package org.jrs82.fsclock.mobile

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WhatsApp-tyylinen Varmuuskopiointi-sivu: Google-tilin valinta, iso Varmuuskopioi-nappi,
 * automaattisuuden frekvenssi ja palautus suoraan Drivestä ([DriveBackup]). Itsenäinen — ei
 * sisäistä Takaisin-nappia (system-back/swipe sulkee isäntä-dialogin). Verkkotyö taustasäikeessä.
 */
@Composable
internal fun DriveBackupScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fi = remember { Locale("fi", "FI") }

    var email by remember { mutableStateOf(DriveBackup.accountEmail(context)) }
    var lastInfo by remember { mutableStateOf(DriveBackup.cachedInfo(context)) }
    var freq by remember { mutableStateOf(DriveBackup.frequency(context)) }
    var busy by remember { mutableStateOf<String?>(null) } // null | "backup" | "restore"
    var freqDialog by remember { mutableStateOf(false) }
    var restoreConfirm by remember { mutableStateOf(false) }
    var restoreDone by remember { mutableStateOf<BackupManager.RestoreResult?>(null) }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        try {
            GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            email = DriveBackup.accountEmail(context)
            if (email != null) {
                DriveBackup.applySchedule(context)
                toast(context, "Kirjauduttu: $email")
            } else {
                toast(context, "Drive-oikeutta ei myonnetty.")
            }
        } catch (e: ApiException) {
            if (e.statusCode != 12501) { // 12501 = SIGN_IN_CANCELLED (kayttaja perui — ei virheilmoitusta)
                toast(context, "Kirjautuminen epaonnistui (${e.statusCode}).")
            }
        }
    }

    fun launchSignIn() {
        signInLauncher.launch(DriveBackup.signInClient(context).signInIntent)
    }

    fun doBackup() {
        if (busy != null) return
        busy = "backup"
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { DriveBackup.backupNow(context) } }
            busy = null
            res.onSuccess { info ->
                lastInfo = info
                toast(context, "Varmuuskopio tallennettu Driveen (${formatBytes(info.bytes)}).")
            }.onFailure { e ->
                DriveBackup.recordError(context, e)
                toast(context, "Varmuuskopiointi epaonnistui: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun doRestore() {
        if (busy != null) return
        if (WorkoutTracker.state.value.phase != WorkoutTracker.Phase.IDLE) {
            toast(context, "Lopeta kaynnissa oleva lenkki ennen palautusta.")
            return
        }
        busy = "restore"
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { DriveBackup.restore(context) } }
            busy = null
            res.onSuccess { restoreDone = it }
                .onFailure { e ->
                    toast(context, "Palautus epaonnistui: ${e.message ?: e.javaClass.simpleName}")
                }
        }
    }

    fun signOut() {
        DriveBackup.signInClient(context).signOut().addOnCompleteListener {
            email = null
            DriveBackup.applySchedule(context)
            toast(context, "Kirjauduttu ulos.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Varmuuskopiointi", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Varmuuskopioi lenkit, askeleet ja asetukset Google Driveen, jotta et menetä niitä " +
                "uudella puhelimella. Kopio tallentuu tilisi piilotettuun sovelluskansioon.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (email == null) {
            ArkiCard(modifier = Modifier.fillMaxWidth(), shape = ItemBoxShape) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Kirjaudu Google-tilillä, jotta varmuuskopiot tallentuvat Driveesi.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { launchSignIn() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Kirjaudu Google-tilillä")
                    }
                }
            }
        } else {
            // Tila
            ArkiCard(modifier = Modifier.fillMaxWidth(), shape = ItemBoxShape) {
                Column(Modifier.padding(16.dp)) {
                    Text("Viimeisin varmuuskopio", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        lastInfo?.let {
                            SimpleDateFormat("d.M. HH:mm", fi).format(Date(it.timeMs)) +
                                " · " + formatBytes(it.bytes)
                        } ?: "Ei vielä varmuuskopiota",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { doBackup() },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy == "backup") {
                    CircularProgressIndicator(
                        modifier = Modifier.height(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Varmuuskopioi", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))

            ClickableRow(
                title = "Google-tili",
                subtitle = email,
                onClick = { launchSignIn() },
            )
            Spacer(Modifier.height(8.dp))
            ClickableRow(
                title = "Automaattinen varmuuskopiointi",
                subtitle = freqLabel(freq),
                leadingIconRes = R.drawable.mobile_ic_backup_24,
                onClick = { freqDialog = true },
            )
            Spacer(Modifier.height(8.dp))
            ClickableRow(
                title = "Palauta varmuuskopio",
                subtitle = "Lataa Drivestä ja korvaa nykyiset tiedot",
                leadingIconRes = R.drawable.mobile_ic_restore_24,
                onClick = { restoreConfirm = true },
            )
            Spacer(Modifier.height(8.dp))
            ClickableRow(
                title = "Kirjaudu ulos",
                onClick = { signOut() },
            )
        }
    }

    // ---- Frekvenssivalinta ----
    if (freqDialog) {
        val options = listOf(
            DriveBackup.FREQ_DAILY, DriveBackup.FREQ_WEEKLY, DriveBackup.FREQ_MONTHLY,
            DriveBackup.FREQ_MANUAL, DriveBackup.FREQ_OFF,
        )
        AlertDialog(
            onDismissRequest = { freqDialog = false },
            title = { Text("Automaattinen varmuuskopiointi") },
            text = {
                Column {
                    options.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = freq == opt, onClick = {
                                    freq = opt
                                    DriveBackup.setFrequency(context, opt)
                                    freqDialog = false
                                })
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = freq == opt, onClick = null)
                            Text(freqLabel(opt), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { freqDialog = false }) { Text("Sulje") } },
        )
    }

    // ---- Palautuksen vahvistus ----
    if (restoreConfirm) {
        AlertDialog(
            onDismissRequest = { restoreConfirm = false },
            title = { Text("Palauta varmuuskopio") },
            text = {
                Text(
                    "Tämä lataa Driveen tallennetun varmuuskopion ja yhdistää sen nykyisiin " +
                        "tietoihin. Sovellus käynnistetään uudelleen palautuksen jälkeen. Jatketaanko?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreConfirm = false
                    doRestore()
                }) { Text("Palauta") }
            },
            dismissButton = { TextButton(onClick = { restoreConfirm = false }) { Text("Peruuta") } },
        )
    }

    // ---- Palautus valmis → uudelleenkäynnistys ----
    restoreDone?.let { res ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Varmuuskopio palautettu") },
            text = {
                Text(
                    "Palautettu ${res.workouts} lenkkiä ja ${res.prefs} asetusta" +
                        (if (res.skipped > 0) " (${res.skipped} jo laitteella)" else "") +
                        ".\n\nSovellus käynnistetään uudelleen.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreDone = null
                    RestartActivity.restartApp(context)
                }) { Text("Käynnistä uudelleen") }
            },
        )
    }
}

private fun freqLabel(freq: String): String = when (freq) {
    DriveBackup.FREQ_DAILY -> "Päivittäin"
    DriveBackup.FREQ_WEEKLY -> "Viikoittain"
    DriveBackup.FREQ_MONTHLY -> "Kuukausittain"
    DriveBackup.FREQ_MANUAL -> "Vain napautettaessa"
    else -> "Ei käytössä"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes t"
    bytes < 1024 * 1024 -> String.format(Locale("fi", "FI"), "%.0f kt", bytes / 1024.0)
    else -> String.format(Locale("fi", "FI"), "%.1f Mt", bytes / (1024.0 * 1024.0))
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}
