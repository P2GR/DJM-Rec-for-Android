package com.audiopro.djmrec.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.ui.components.ChannelPairSelector
import com.audiopro.djmrec.audio.RecordingHealth
import com.audiopro.djmrec.audio.RecordingHealthLevel
import com.audiopro.djmrec.ui.components.DeviceStatusCard
import com.audiopro.djmrec.ui.components.FormatSelector
import com.audiopro.djmrec.ui.components.RgbWaveform
import com.audiopro.djmrec.ui.components.StereoVuMeter
import com.audiopro.djmrec.ui.components.TransportControls
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.ui.theme.AccentAmber
import com.audiopro.djmrec.ui.theme.AccentRed
import java.util.Locale

@Composable
fun RecorderScreen(viewModel: MainViewModel) {
    val device by viewModel.deviceState.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val levels by viewModel.levels.collectAsState()
    val elapsedMillis by viewModel.elapsedMillis.collectAsState()
    val waveformBins by viewModel.waveformBins.collectAsState()
    val waveformEnabled by viewModel.waveformEnabled.collectAsState()
    val recordingHealth by viewModel.recordingHealth.collectAsState()
    val selectedFormat by viewModel.selectedFormat.collectAsState()
    val djmrecPortMode by viewModel.djmrecPortMode.collectAsState()
    val otgStatus by viewModel.otgStatus.collectAsState()
    val usbChannelOffset by viewModel.usbChannelOffset.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(device?.deviceName) {
        if (device != null) viewModel.ensureLiveMonitoring()
    }

    val otgWarning = otgStatus
    if (djmrecPortMode && otgWarning != null && !otgWarning.enabled) {
        AlertDialog(
            onDismissRequest = viewModel::dismissOtgWarning,
            title = { Text("USB OTG may be disabled") },
            text = {
                Text(
                    "The phone must act as USB host. Enable OTG under Connected devices, then rescan from USB Settings."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.openOtgSettings(context) }) {
                    Text("Open OTG settings")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissOtgWarning) { Text("Dismiss") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SignalSection(
                state = recordingState,
                bins = waveformBins,
                showWaveform = waveformEnabled,
                levels = levels
            )

            RecordingHealthStrip(health = recordingHealth)

            if (recordingState is RecordingState.Error) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = (recordingState as RecordingState.Error).message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            RecordingSetupSection(
                selectedFormat = selectedFormat,
                formats = viewModel.availableFormats,
                formatEnabled = recordingState is RecordingState.Idle ||
                    recordingState is RecordingState.Monitoring ||
                    recordingState is RecordingState.Error,
                onFormatSelected = viewModel::selectFormat,
                device = device,
                onRescan = viewModel::rescanUsbDevices,
                channelOffset = usbChannelOffset,
                onChannelOffsetSelected = viewModel::setUsbChannelOffset
            )
        }

        TransportSection(
            state = recordingState,
            elapsedMillis = elapsedMillis,
            onRecord = viewModel::startRecording,
            onPause = viewModel::pauseRecording,
            onResume = viewModel::resumeRecording,
            onStop = viewModel::stopRecording
        )
    }
}

@Composable
private fun RecordingHealthStrip(health: RecordingHealth) {
    val (label, color) = when (health.level) {
        RecordingHealthLevel.READY -> "STANDBY" to MaterialTheme.colorScheme.onSurfaceVariant
        RecordingHealthLevel.GOOD -> "HEALTHY" to AccentGreen
        RecordingHealthLevel.SILENCE -> "NO SIGNAL" to AccentAmber
        RecordingHealthLevel.USB_UNSTABLE -> "USB WARNING" to AccentAmber
        RecordingHealthLevel.LOW_STORAGE -> "LOW STORAGE" to AccentRed
        RecordingHealthLevel.ERROR -> "STOPPED" to MaterialTheme.colorScheme.error
    }
    val details = buildList {
        add(health.message)
        if (health.remainingSeconds in 1 until Long.MAX_VALUE) {
            add("${formatRemaining(health.remainingSeconds)} recording time left")
        }
        if (health.freeBytes > 0 && health.freeBytes < Long.MAX_VALUE) {
            add(String.format(Locale.US, "%.1f GB free", health.freeBytes / 1_073_741_824.0))
        }
    }.joinToString(" | ")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SignalSection(
    state: RecordingState,
    bins: FloatArray,
    showWaveform: Boolean,
    levels: com.audiopro.djmrec.audio.StereoLevels
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "LIVE INPUT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (showWaveform) "Live waveform" else "Input monitoring",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                StatePill(state)
            }

            if (showWaveform) {
                RgbWaveform(bins = bins)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
            }
            Text(
                "INPUT LEVELS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StereoVuMeter(levels = levels)
        }
    }
}

@Composable
private fun StatePill(state: RecordingState) {
    val (label, color) = when (state) {
        is RecordingState.Recording -> "RECORDING" to AccentRed
        is RecordingState.Paused -> "PAUSED" to MaterialTheme.colorScheme.secondary
        is RecordingState.Monitoring -> "SIGNAL READY" to AccentGreen
        is RecordingState.Preparing -> "CONNECTING" to MaterialTheme.colorScheme.secondary
        is RecordingState.Error -> "CHECK SETUP" to MaterialTheme.colorScheme.error
        is RecordingState.Idle -> "STANDBY" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TransportSection(
    state: RecordingState,
    elapsedMillis: Long,
    onRecord: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RecordingTimer(
            elapsedMillis = elapsedMillis,
            active = state is RecordingState.Recording,
            paused = state is RecordingState.Paused
        )
        TransportControls(
            state = state,
            onRecord = onRecord,
            onPause = onPause,
            onResume = onResume,
            onStop = onStop
        )
        Text(
            text = when (state) {
                is RecordingState.Recording -> "Recording to Music/DJMRec"
                is RecordingState.Paused -> "Recording paused"
                is RecordingState.Preparing -> "Opening USB audio stream..."
                is RecordingState.Monitoring -> "Press record when ready"
                else -> "Connect mixer and press record"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecordingSetupSection(
    selectedFormat: com.audiopro.djmrec.audio.RecordingFormat,
    formats: List<com.audiopro.djmrec.audio.RecordingFormat>,
    formatEnabled: Boolean,
    onFormatSelected: (com.audiopro.djmrec.audio.RecordingFormat) -> Unit,
    device: com.audiopro.djmrec.usb.UsbAudioDeviceInfo?,
    onRescan: () -> Unit,
    channelOffset: Int,
    onChannelOffsetSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(
                    "RECORDING SETUP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text("File format", style = MaterialTheme.typography.titleMedium)
            }
            FormatSelector(
                selected = selectedFormat,
                formats = formats,
                enabled = formatEnabled,
                onSelect = onFormatSelected
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
            Text(
                "AUDIO SOURCE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DeviceStatusCard(device = device, onRescan = onRescan)
            if (device != null && device.channelCount > 2) {
                Text(
                    "USB CHANNEL PAIR",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChannelPairSelector(
                    selectedOffset = channelOffset,
                    pairCount = device.channelCount / 2,
                    enabled = formatEnabled,
                    onSelect = onChannelOffsetSelected
                )
            }
        }
    }
}

@Composable
private fun RecordingTimer(elapsedMillis: Long, active: Boolean, paused: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "recordingPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = if (active) 0.32f else 0.55f,
        targetValue = if (active) 1f else 0.55f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "recordingDot"
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(12.dp).alpha(dotAlpha).background(
                if (active || paused) AccentRed else MaterialTheme.colorScheme.outline,
                CircleShape
            )
        )
        Text(
            formatElapsed(elapsedMillis),
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatRemaining(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes.coerceAtLeast(1)}m"
}
