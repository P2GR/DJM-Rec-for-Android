package com.audiopro.djmrec.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.diagnostics.LogExporter
import com.audiopro.djmrec.ui.components.DeviceStatusCard
import com.audiopro.djmrec.ui.components.FormatSelector
import com.audiopro.djmrec.ui.components.StereoVuMeter
import com.audiopro.djmrec.ui.components.TransportControls
import com.audiopro.djmrec.audio.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun RecorderScreen(viewModel: MainViewModel) {
    val device by viewModel.deviceState.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val levels by viewModel.levels.collectAsState()
    val elapsedMillis by viewModel.elapsedMillis.collectAsState()
    val selectedFormat by viewModel.selectedFormat.collectAsState()
    val rootUsbMode by viewModel.rootUsbMode.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isExportingLogs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(text = "USB settings") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Root USB assist",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Use su to request host/source role before rescanning.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rootUsbMode,
                            onCheckedChange = viewModel::setRootUsbModeEnabled
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text(text = "Done")
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = { showSettings = true }) {
                    Icon(imageVector = Icons.Filled.Settings, contentDescription = "USB settings")
                }

                FloatingActionButton(
                    onClick = {
                        if (isExportingLogs) return@FloatingActionButton
                        isExportingLogs = true
                        coroutineScope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    val report = LogExporter.collectDiagnosticReport(context)
                                    LogExporter.writeReportToFile(context, report)
                                }
                                LogExporter.shareReport(context, file)
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Failed to export logs: ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isExportingLogs = false
                            }
                        }
                    }
                ) {
                    if (isExportingLogs) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(imageVector = Icons.Filled.BugReport, contentDescription = "Export diagnostic logs")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DeviceStatusCard(
                device = device,
                onRescan = viewModel::rescanUsbDevices
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = formatElapsed(elapsedMillis),
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (recordingState is RecordingState.Error) {
                Text(
                    text = (recordingState as RecordingState.Error).message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            StereoVuMeter(levels = levels, modifier = Modifier.fillMaxSize().weight(1f))

            Spacer(modifier = Modifier.height(24.dp))

            FormatSelector(
                selected = selectedFormat,
                enabled = recordingState is RecordingState.Idle,
                onSelect = viewModel::selectFormat
            )

            Spacer(modifier = Modifier.height(24.dp))

            TransportControls(
                state = recordingState,
                onRecord = viewModel::startRecording,
                onPause = viewModel::pauseRecording,
                onResume = viewModel::resumeRecording,
                onStop = viewModel::stopRecording,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
