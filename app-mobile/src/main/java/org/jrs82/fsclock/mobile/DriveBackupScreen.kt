package org.jrs82.fsclock.mobile

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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

/**
 * WhatsApp-tyylinen Varmuuskopiointi-sivu: Google-tilin valinta, iso Varmuuskopioi-nappi,
 * automaattisuuden frekvenssi ja palautus suoraan Drivestä ([DriveBackup]). Itsenäinen — ei
 * sisäistä Takaisin-nappia (system-back/swipe sulkee isäntä-dialogin). Verkkotyö taustasäikeessä.
 */
@Composable
internal fun DriveBackupScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = AppLocale.current(context)

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
                toast(context, context.getString(R.string.backup_signed_in_as, email))
            } else {
                toast(context, context.getString(R.string.backup_drive_access_denied))
            }
        } catch (e: ApiException) {
            if (e.statusCode != 12501) { // 12501 = SIGN_IN_CANCELLED (kayttaja perui — ei virheilmoitusta)
                toast(context, context.getString(R.string.backup_sign_in_failed, e.statusCode))
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
                toast(context, context.getString(R.string.backup_saved_drive, formatBackupSize(context, info.bytes)))
            }.onFailure { e ->
                DriveBackup.recordError(context, e)
                toast(context, context.getString(R.string.backup_failed_reason, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun doRestore() {
        if (busy != null) return
        if (WorkoutTracker.state.value.phase != WorkoutTracker.Phase.IDLE) {
            toast(context, context.getString(R.string.backup_stop_workout_first))
            return
        }
        busy = "restore"
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { DriveBackup.restore(context) } }
            busy = null
            res.onSuccess { restoreDone = it }
                .onFailure { e ->
                    toast(context, context.getString(R.string.backup_restore_failed_reason, e.displayMessage(context)))
                }
        }
    }

    fun signOut() {
        DriveBackup.signInClient(context).signOut().addOnCompleteListener {
            email = null
            DriveBackup.applySchedule(context)
            toast(context, context.getString(R.string.backup_signed_out))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.backup_drive_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (email == null) {
            ArkiCard(modifier = Modifier.fillMaxWidth(), shape = ItemBoxShape) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.backup_sign_in_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { launchSignIn() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.backup_sign_in))
                    }
                }
            }
        } else {
            // Tila
            ArkiCard(modifier = Modifier.fillMaxWidth(), shape = ItemBoxShape) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.backup_last), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        lastInfo?.let {
                            SimpleDateFormat(stringResource(R.string.backup_date_pattern), locale).format(Date(it.timeMs)) +
                                " · " + formatBackupSize(context, it.bytes)
                        } ?: stringResource(R.string.backup_none_yet),
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
                    Text(stringResource(R.string.backup_now), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))

            ClickableRow(
                title = stringResource(R.string.backup_google_account),
                subtitle = email,
                onClick = { launchSignIn() },
            )
            Spacer(Modifier.height(8.dp))
            ClickableRow(
                title = stringResource(R.string.backup_auto),
                subtitle = stringResource(driveFrequencyLabelRes(freq)),
                leadingIconRes = R.drawable.mobile_ic_backup_24,
                onClick = { freqDialog = true },
            )
            Spacer(Modifier.height(8.dp))
            ClickableRow(
                title = stringResource(R.string.backup_restore),
                subtitle = stringResource(R.string.backup_restore_sub),
                leadingIconRes = R.drawable.mobile_ic_restore_24,
                onClick = { restoreConfirm = true },
            )
            Spacer(Modifier.height(8.dp))
            ClickableRow(
                title = stringResource(R.string.backup_sign_out),
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
            title = { Text(stringResource(R.string.backup_auto)) },
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
                            Text(stringResource(driveFrequencyLabelRes(opt)), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { freqDialog = false }) { Text(stringResource(R.string.common_close)) } },
        )
    }

    // ---- Palautuksen vahvistus ----
    if (restoreConfirm) {
        AlertDialog(
            onDismissRequest = { restoreConfirm = false },
            title = { Text(stringResource(R.string.backup_restore)) },
            text = { Text(stringResource(R.string.backup_restore_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    restoreConfirm = false
                    doRestore()
                }) { Text(stringResource(R.string.backup_restore_action)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // ---- Palautus valmis → uudelleenkäynnistys ----
    restoreDone?.let { res ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.backup_restored_title)) },
            text = { Text(restoreSummaryText(res, R.string.backup_restart_note)) },
            confirmButton = {
                TextButton(onClick = {
                    restoreDone = null
                    RestartActivity.restartApp(context)
                }) { Text(stringResource(R.string.backup_restart)) }
            },
        )
    }
}

/** Palautuksen yhteenveto dialogiin (jaettu asetusten tiedostopalautuksen kanssa); [restartNote] kertoo
 *  mitä uudelleenkäynnistyksessä tapahtuu. */
@Composable
internal fun restoreSummaryText(res: BackupManager.RestoreResult, @StringRes restartNote: Int): String {
    val summary = stringResource(
        R.string.backup_restored_summary,
        pluralStringResource(R.plurals.backup_workouts, res.workouts, res.workouts),
        pluralStringResource(R.plurals.backup_settings, res.prefs, res.prefs),
    )
    val skipped = if (res.skipped > 0) {
        " " + pluralStringResource(R.plurals.backup_skipped, res.skipped, res.skipped)
    } else {
        ""
    }
    return summary + skipped + "\n\n" + stringResource(restartNote)
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}
