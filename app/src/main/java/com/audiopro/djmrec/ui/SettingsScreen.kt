package com.audiopro.djmrec.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.ui.theme.TextSecondary

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val waveform by viewModel.waveformEnabled.collectAsState()
    val smooth by viewModel.smoothWaveform.collectAsState()
    val keepScreen by viewModel.keepScreenOn.collectAsState()
    val confirm by viewModel.confirmStop.collectAsState()
    val context = LocalContext.current
    val diagnostics by com.audiopro.djmrec.diagnostics.RemoteDiagnostics.enabled.collectAsState()
    val diagnosticsStatus by com.audiopro.djmrec.diagnostics.RemoteDiagnostics.status.collectAsState()
    val diagnosticsRestart by com.audiopro.djmrec.diagnostics.RemoteDiagnostics.restartRequired.collectAsState()
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
