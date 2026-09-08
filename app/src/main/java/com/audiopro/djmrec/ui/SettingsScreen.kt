package com.audiopro.djmrec.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.ui.theme.TextSecondary
import com.audiopro.djmrec.update.AppUpdate
import com.audiopro.djmrec.update.UpdateCheckResult
import com.audiopro.djmrec.update.UpdateChecker
import com.audiopro.djmrec.update.UpdateInstaller
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val waveform by viewModel.waveformEnabled.collectAsState()
    val smooth by viewModel.smoothWaveform.collectAsState()
    val keepScreen by viewModel.keepScreenOn.collectAsState()
    val confirm by viewModel.confirmStop.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics by com.audiopro.djmrec.diagnostics.RemoteDiagnostics.enabled.collectAsState()
    val diagnosticsStatus by com.audiopro.djmrec.diagnostics.RemoteDiagnostics.status.collectAsState()
    val diagnosticsRestart by com.audiopro.djmrec.diagnostics.RemoteDiagnostics.restartRequired.collectAsState()
    var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    var pendingPermissionUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    var installRequest by remember { mutableStateOf<AppUpdate?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateError by remember { mutableStateOf(false) }
    val unknownSourcesPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val update = pendingPermissionUpdate
        pendingPermissionUpdate = null
        if (update != null && context.packageManager.canRequestPackageInstalls()) {
            installRequest = update
        } else if (update != null) {
            updateError = true
            updateStatus = "Install permission was not granted."
        }
    }

    LaunchedEffect(installRequest?.tag) {
        val update = installRequest ?: return@LaunchedEffect
        updateBusy = true
        updateError = false
        updateStatus = "Downloading DJM REC ${update.version}..."
        runCatching { UpdateInstaller.download(context.applicationContext, update) }
            .onSuccess { apk ->
                updateStatus = "Download verified. Opening Android installer..."
                runCatching { UpdateInstaller.install(context, apk) }
                    .onFailure { error ->
                        updateError = true
                        updateStatus = error.message ?: "Could not open Android installer"
                    }
            }
            .onFailure { error ->
                updateError = true
                updateStatus = error.message ?: "Update download failed"
            }
        updateBusy = false
        installRequest = null
    }

    fun checkForUpdates() {
        scope.launch {
            updateBusy = true
            updateError = false
            updateStatus = "Checking GitHub for updates..."
            when (val result = UpdateChecker.checkNow(context.applicationContext)) {
                is UpdateCheckResult.Available -> {
                    availableUpdate = result.update
                    updateStatus = "DJM REC ${result.update.version} is available."
                }
                UpdateCheckResult.Current -> {
                    availableUpdate = null
                    updateStatus = "DJM REC is up to date."
                }
                is UpdateCheckResult.Failed -> {
                    updateError = true
                    updateStatus = result.message
                }
            }
            updateBusy = false
        }
    }

    fun requestInstall(update: AppUpdate) {
        if (context.packageManager.canRequestPackageInstalls()) {
            installRequest = update
        } else {
            pendingPermissionUpdate = update
            updateStatus = "Allow DJM REC to install updates, then return here."
            unknownSourcesPermission.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Capture", style = MaterialTheme.typography.titleLarge)
        Text("Monitoring arms automatically after USB connection and permission. Recording starts only when you press Record.", color = TextSecondary)
        PreferenceSwitch("Confirm stop", "Ask before stopping from the recorder. Notification Save & close always acts immediately.", confirm, viewModel::setConfirmStop)
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { RecordingSetupControls(viewModel) }
        }
        Text("Diagnostics & privacy", style = MaterialTheme.typography.titleLarge)
        PreferenceSwitch("Automatic diagnostics", "Send mixer formats, channel activity, recording health and crash reports to Bugfender. No recorded audio. Enabled by default in all builds.",
            diagnostics, com.audiopro.djmrec.diagnostics.RemoteDiagnostics::setEnabled)
        Text(diagnosticsStatus, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        if (diagnosticsRestart) Text("Finish your set, then force-stop DJM Rec in Android app settings and reopen it. Until then, previously queued reports and SDK traffic may continue. Closing the screen alone does not restart the app.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text("Display", style = MaterialTheme.typography.titleLarge)
        PreferenceSwitch("Live waveform", "RGB: red bass, green mids, blue highs. Mixed frequencies blend colors.", waveform, viewModel::setWaveformEnabled)
        PreferenceSwitch("Smooth waveform", "Scroll at the display frame rate. Turn off to reduce graphics work.", smooth, viewModel::setSmoothWaveform)
        PreferenceSwitch("Keep recorder screen awake", "Applies while monitoring or recording. Capture also works with screen locked.", keepScreen, viewModel::setKeepScreenOn)
        Text("Background recording", style = MaterialTheme.typography.titleLarge)
        Text("Keep the persistent notification enabled. Allow background battery use in Android settings for long sets. Force-stop, reboot or disconnecting USB still ends capture.", color = TextSecondary)
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }, modifier = Modifier.fillMaxWidth()) { Text("Android app settings") }
        Text("App updates", style = MaterialTheme.typography.titleLarge)
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DJM REC", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Installed version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                updateStatus?.let { status ->
                    Text(
                        status,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateError) MaterialTheme.colorScheme.error else TextSecondary
                    )
                }
                if (updateBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = ::checkForUpdates,
                    enabled = !updateBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(if (updateBusy) "Please wait" else "Check for updates") }
                availableUpdate?.let { update ->
                    Button(
                        onClick = { requestInstall(update) },
                        enabled = !updateBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Download & install ${update.version}") }
                    Text(
                        "Download is verified before Android asks you to confirm installation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
        Text("Track markers", style = MaterialTheme.typography.titleLarge)
        Text("Tap Mark during recording to identify tracks. Find markers in Sets and export the track list. Markers never cut or modify your recording.", color = TextSecondary)
        OutlinedButton(onClick = viewModel::stopAndClose, modifier = Modifier.fillMaxWidth()) { Text("Save everything & close") }
    }
}

@Composable
private fun PreferenceSwitch(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = onChange).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Switch(value, null)
        }
    }
}
