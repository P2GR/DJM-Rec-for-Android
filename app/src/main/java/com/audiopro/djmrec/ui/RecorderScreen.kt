package com.audiopro.djmrec.ui

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.LibraryMusic
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.diagnostics.LogExporter
import com.audiopro.djmrec.ui.components.DeviceStatusCard
import com.audiopro.djmrec.ui.components.FormatSelector
import com.audiopro.djmrec.ui.components.RgbWaveform
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
    val waveformBins by viewModel.waveformBins.collectAsState()
    val selectedFormat by viewModel.selectedFormat.collectAsState()
    val rootUsbMode by viewModel.rootUsbMode.collectAsState()
    val usbChannelOffset by viewModel.usbChannelOffset.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isExportingLogs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }

    if (showLibrary) {
        LibraryScreen(onBack = { showLibrary = false })
        return
    }

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

                    Spacer(modifier = Modifier.height(16.dp))

                    UsbChannelPairSelector(
                        totalChannels = device?.channelCount ?: 12,
                        selectedOffset = usbChannelOffset,
                        onSelect = viewModel::setUsbChannelOffset
                    )
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
                FloatingActionButton(onClick = { showLibrary = true }) {
                    Icon(imageVector = Icons.Filled.LibraryMusic, contentDescription = "Recordings library")
                }

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
            // Recording indicator + elapsed time — topmost, only visible while capturing.
            if (recordingState is RecordingState.Recording || recordingState is RecordingState.Paused) {
                RgbWaveform(bins = waveformBins)
                Spacer(modifier = Modifier.height(12.dp))
                RecordingTimer(elapsedMillis = elapsedMillis, isPaused = recordingState is RecordingState.Paused)
                Spacer(modifier = Modifier.height(16.dp))
            }

            DeviceStatusCard(
                device = device,
                onRescan = viewModel::rescanUsbDevices
            )

            Spacer(modifier = Modifier.height(24.dp))

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
                formats = viewModel.availableFormats,
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

@Composable
private fun UsbChannelPairSelector(
    totalChannels: Int,
    selectedOffset: Int,
    onSelect: (Int) -> Unit
) {
    Text(
        text = "USB stereo pair",
        style = MaterialTheme.typography.titleSmall
    )
    Text(
        text = "Auto follows the loudest pair; choose a pair manually if the mixer sends silence on the default pair.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(8.dp))

    val offsets = listOf(-1) + (0 until totalChannels - 1 step 2).toList()
    offsets.chunked(3).forEach { rowOffsets ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowOffsets.forEach { offset ->
                TextButton(onClick = { onSelect(offset) }) {
                    Text(
                        text = if (offset < 0) "Auto" else "${offset + 1}-${offset + 2}",
                        color = if (offset == selectedOffset) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

/**
 * Large elapsed-time display with a pulsing red dot that makes the "actively recording" state
 * visually unmistakable. The dot pulses gently (~2s cycle) while recording, and stays solid
 * dim when paused.
 */
@Composable
private fun RecordingTimer(elapsedMillis: Long, isPaused: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "recDotPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = if (isPaused) 0.4f else 0.3f,
        targetValue = if (isPaused) 0.4f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotPulse"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Pulsing red recording dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .alpha(dotAlpha)
                .background(Color(0xFFFF4D4D), CircleShape)
        )

        Text(
            text = formatElapsed(elapsedMillis),
            style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
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
