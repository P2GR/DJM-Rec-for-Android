package com.audiopro.djmrec.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.audio.*
import com.audiopro.djmrec.ui.components.*
import com.audiopro.djmrec.ui.theme.*
import java.util.Locale

/** Fixed recording workspace; detailed controls live in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(viewModel: MainViewModel, onOpenLibrary: () -> Unit = {}) {
    val device by viewModel.deviceState.collectAsState()
    val state by viewModel.recordingState.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val levels by viewModel.levels.collectAsState()
    val elapsed by viewModel.elapsedMillis.collectAsState()
    val awaitingAudio by viewModel.awaitingAudio.collectAsState()
    val limiterReduction by viewModel.limiterReductionDb.collectAsState()
    val waveform by viewModel.waveformEnabled.collectAsState()
    val smooth by viewModel.smoothWaveform.collectAsState()
    val confirmStop by viewModel.confirmStop.collectAsState()
    val health by viewModel.recordingHealth.collectAsState()
    val format by viewModel.selectedFormat.collectAsState()
    val gain by viewModel.recordingGainDb.collectAsState()
    val saved by viewModel.lastSaved.collectAsState()
    val advanced by viewModel.advancedMode.collectAsState()
    val multitrackActive by viewModel.multitrackActive.collectAsState()
    val multitrackTracks by viewModel.multitrackTracks.collectAsState()
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var stopPrompt by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var inputsOpen by rememberSaveable { mutableStateOf(false) }
    // Hoisted to the view model so MainScreen can hide the app chrome for true fullscreen.
    var powerSave by viewModel.powerSaveActive
    val connectionNotice by viewModel.connectionNotice.collectAsState()
    val context = LocalContext.current
    val active = state is RecordingState.Recording || state is RecordingState.Paused
    val signal = levels.left.peakDb > -55 || levels.right.peakDb > -55
    // Keep-awake is owned by MainActivity (window flag) so it holds on every screen while a
    // recording is active; this screen no longer toggles it.
    if (inputsOpen) InputPicker(viewModel) { inputsOpen = false }
    if (setupOpen) ModalBottomSheet(onDismissRequest = { setupOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Recording setup", style = MaterialTheme.typography.headlineSmall)
            RecordingSetupControls(viewModel)
            Button(onClick = { setupOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
    if (stopPrompt) AlertDialog(onDismissRequest = { stopPrompt = false },
        title = { Text("Save this set?") }, text = { Text("Recording will finish. Input monitoring stays ready for your next set.") },
        confirmButton = { TextButton(onClick = { stopPrompt = false; powerSave = false; viewModel.stopRecording() }) { Text("Stop & save") } },
        dismissButton = { TextButton(onClick = { stopPrompt = false }) { Text("Keep recording") } })
    if (detailsOpen) AlertDialog(onDismissRequest = { detailsOpen = false }, title = { Text("Input status") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text((state as? RecordingState.Error)?.message ?: health.message)
            Text(device?.allInOneProfile?.setupHint ?: device?.pioneerMixerProfile?.let {
                if (it.isHardwareConfirmed) "Recording confirmed on ${it.displayName}."
                else "${it.displayName}: implemented profile, physical validation pending."
            } ?: "Use a USB audio data cable. Grant audio and USB permission when prompted.")
            if (health.freeBytes in 1 until Long.MAX_VALUE)
                Text(String.format(Locale.US, "%.1f GB available", health.freeBytes / 1_073_741_824.0))
        } }, confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text("OK") } })
    val inputHeader: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (device != null) AccentGreen else TextSecondary, CircleShape))
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { inputsOpen = true }.padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center) {
                Text(device?.productName ?: "Connect your mixer", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Text(device?.let { "${it.preferredSampleRate / 1000f} kHz / ${it.bitResolution}-bit / USB" }
                    ?: "USB audio input", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            }
            IconButton(onClick = { powerSave = true }) {
                Icon(Icons.Default.BatterySaver, "Power saving mode")
            }
            IconButton(onClick = { inputsOpen = true }) {
                Icon(Icons.Default.Usb, "Choose audio input")
            }
            FilledTonalIconButton(onClick = { setupOpen = true }) { Icon(Icons.Default.Tune, "Recording setup") }
        }
    }
    val signalPanel: @Composable () -> Unit = {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(20.dp), color = SurfaceDark) {
            BoxWithConstraints(Modifier.padding(12.dp)) {
                val compact = maxHeight < 160.dp
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        val label = when {
                            saving -> "SAVING"
                            state is RecordingState.Recording -> "REC"
                            state is RecordingState.Paused -> "PAUSED"
                            state is RecordingState.Preparing -> "ARMING"
                            state is RecordingState.Monitoring -> if (signal) "INPUT LIVE" else "ARMED / NO SIGNAL"
                            else -> "STANDBY"
                        }
                        AnimatedContent(targetState = label, label = "captureStatus") { status ->
                            Text(status, color = if (active) AccentRed else AccentGreen,
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        Text(if (advanced && device != null) "MULTITRACK" else if (waveform) "3-BAND" else "METERS",
                            style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    if (advanced && device != null && !compact) {
                        // Advanced mode: one lane per input pair; the master lane carries the set's meter.
                        MultitrackPanel(viewModel, onOpenSetup = { setupOpen = true }, modifier = Modifier.fillMaxWidth().weight(1f))
                    } else {
                        if (waveform && !compact) LiveRgbWaveform(viewModel.waveformBins, Modifier.fillMaxWidth().weight(1f), smooth = smooth,
                            active = state is RecordingState.Monitoring || active, onVisible = viewModel::setWaveformVisible)
                        else if (!compact) Spacer(Modifier.weight(1f))
                        StereoVuMeter(levels)
                    }
                }
            }
        }
    }
    val transport: @Composable (Boolean) -> Unit = { compact ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact) {
            val message = when {
                saving -> "Finalizing your recording..."
                state is RecordingState.Error -> (state as RecordingState.Error).message
                active && (levels.left.isClipping || levels.right.isClipping) -> "Clipping: lower mixer output"
                active && limiterReduction >= 3f ->
                    String.format(Locale.US, "Limiter catching peaks (-%.0f dB): lower mixer output or gain", limiterReduction)
                state is RecordingState.Recording && awaitingAudio &&
                    (health.level == RecordingHealthLevel.GOOD || health.level == RecordingHealthLevel.SILENCE) ->
                    "Waiting for audio. Silence before the first sound is trimmed from the file."
                active -> health.message
                device == null -> connectionNotice ?: "Connect mixer, grant USB access, check signal"
                else -> health.message
            }
            val attention = saving || state is RecordingState.Error ||
                (active && (levels.left.isClipping || levels.right.isClipping || limiterReduction >= 3f)) ||
                health.level == RecordingHealthLevel.LOW_BATTERY || health.level == RecordingHealthLevel.OVERHEATING
            Surface(shape = RoundedCornerShape(14.dp), color = SurfaceVariantDark.copy(alpha = 0.5f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { detailsOpen = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (attention) Icons.Default.WarningAmber else Icons.Default.Info, null,
                        Modifier.size(18.dp), tint = if (attention) AccentAmber else TextSecondary)
                    Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = TextSecondary)
                }
            }
        }
        Column {
            Text(elapsedText(elapsed), style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace))
            val armedTracks = if (multitrackActive) multitrackTracks.count { it.armed } else 0
            Text("${format.name} / ${if (gain > 0) "+" else ""}$gain dB" +
                if (armedTracks > 0) " / +$armedTracks track${if (armedTracks == 1) "" else "s"}" else "",
                style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
        val reducedMotion = rememberReducedMotion()
        AnimatedContent(
            targetState = active,
            transitionSpec = { DjmRecMotion.fadeTransform(reduced = reducedMotion) },
            label = "transport"
        ) { transportActive ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (transportActive) {
                OutlinedButton(onClick = { if (state is RecordingState.Paused) viewModel.resumeRecording() else viewModel.pauseRecording() },
                    enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Icon(if (state is RecordingState.Paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                    Text(if (state is RecordingState.Paused) "Resume" else "Pause")
                }
                Button(onClick = { if (confirmStop) stopPrompt = true else viewModel.stopRecording() }, enabled = !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Stop, null)
                    Text(if (saving) " Saving" else " Save set")
                }
            } else {
                Button(onClick = viewModel::startRecording, enabled = device != null && state !is RecordingState.Preparing && !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed, contentColor = BackgroundDark)) {
                    if (state is RecordingState.Preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.FiberManualRecord, null)
                    Text(if (state is RecordingState.Preparing) " Arming input" else " Record set", fontWeight = FontWeight.Bold)
                }
            }
        }
        }
        }
    }
    // Power saving replaces the whole workspace: the waveform/meters leave composition (which
    // also stops the native waveform analyzer) while capture keeps running untouched.
    val reducedMotion = rememberReducedMotion()
    AnimatedContent(
        targetState = powerSave,
        transitionSpec = { DjmRecMotion.fadeTransform(reduced = reducedMotion) },
        label = "powerSave"
    ) { powerSaveOn ->
    if (powerSaveOn) {
        PowerSaveOverlay(
            recording = active,
            saving = saving,
            elapsedMillis = elapsed,
            onStop = {
                if (confirmStop) stopPrompt = true
                else { powerSave = false; viewModel.stopRecording() }
            },
            onClose = { powerSave = false }
        )
    } else BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (maxWidth >= 600.dp && maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    inputHeader()
                    Box(Modifier.weight(1f)) { signalPanel() }
                }
                Column(Modifier.weight(1f).align(Alignment.CenterVertically)) { transport(true) }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                inputHeader()
                Box(Modifier.weight(1f)) { signalPanel() }
                transport(false)
            }
        }
    }
    }
    saved?.let { recording ->
        AlertDialog(onDismissRequest = viewModel::dismissSavedRecording,
            title = { Text(if (recording.notice != null) "Recording stopped" else "Set saved") },
            text = {
                Text(listOfNotNull(recording.notice,
                    "${recording.name}\n${elapsedText(recording.durationMillis)} / Music/DJMRec",
                    recording.alsoSaved?.let { "MP3 copy: $it" },
                    recording.tracksFolder?.takeIf { recording.tracksSaved > 0 }?.let {
                        "${recording.tracksSaved} track file${if (recording.tracksSaved == 1) "" else "s"}: $it"
                    }).joinToString("\n\n"))
            },
            confirmButton = { TextButton(onClick = { viewModel.dismissSavedRecording(); onOpenLibrary() }) { Text("Open sets") } },
            dismissButton = { TextButton(onClick = viewModel::dismissSavedRecording) { Text("Done") } })
    }
}

@Composable
internal fun RecordingSetupControls(viewModel: MainViewModel) {
    val live by viewModel.liveStreamState.collectAsState()
    val device by viewModel.deviceState.collectAsState()
    val state by viewModel.recordingState.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val format by viewModel.selectedFormat.collectAsState()
    val gain by viewModel.recordingGainDb.collectAsState()
    val pair by viewModel.usbChannelOffset.collectAsState()
    val enabled = !saving && !live.isActive && (state is RecordingState.Idle || state is RecordingState.Monitoring || state is RecordingState.Error)
    if (!enabled) Text("Capture settings locked while recording or streaming.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    Text("File format", style = MaterialTheme.typography.titleSmall)
    FormatSelector(format, viewModel.availableFormats, enabled, viewModel::selectFormat)
    if (format != RecordingFormat.MP3) {
        val mp3Copy by viewModel.mp3Copy.collectAsState()
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Also save an MP3 copy", style = MaterialTheme.typography.bodyMedium)
                Text("320 kbps, ready to share while the ${format.name} master stays lossless.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Switch(mp3Copy, viewModel::setMp3Copy, enabled = enabled)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Gain: ${if (gain > 0) "+" else ""}$gain dB", modifier = Modifier.weight(1f))
        TextButton(onClick = { viewModel.setRecordingGainDb(0) }, enabled = enabled) { Text("Reset to 0 dB") }
    }
    Slider(gain.toFloat(), { viewModel.setRecordingGainDb(it.toInt()) }, enabled = enabled,
        valueRange = 0f..12f, steps = 11, modifier = Modifier.semantics { contentDescription = "Recording gain in decibels" })
    Text("0 dB preserves input level. Keep peaks below 0 dBFS.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    val advanced by viewModel.advancedMode.collectAsState()
    val multitrackCapable = device?.let { it.requiresIsoCapture && MultitrackLayout.isAvailable(it.channelCount) }
    HorizontalDivider(color = OutlineSubtle)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Advanced mode", style = MaterialTheme.typography.bodyMedium)
            Text(
                when (multitrackCapable) {
                    false -> "This input has a single stereo pair, so there are no extra tracks to record."
                    else -> "Record every input pair as its own track next to the master, with per-track gain and waveform."
                },
                style = MaterialTheme.typography.bodySmall, color = TextSecondary
            )
        }
        Switch(advanced, viewModel::setAdvancedMode, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Advanced mode: multitrack recording" })
    }
    if (advanced && multitrackCapable == true) {
        Text("Track files are saved lossless (an MP3 set records its tracks as FLAC) in a folder next to the set, " +
            "starting and stopping on the same sample as the master.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
    if (device?.requiresIsoCapture == true) {
        Text(if (advanced) "Master pair" else "Stereo input pair", style = MaterialTheme.typography.titleSmall)
        ChannelPairSelector(pair, device!!.channelCount / 2, enabled, viewModel::setUsbChannelOffset)
        Text(when {
            advanced -> "Auto puts the master on the mixer's master slot (USB 1/2 on other devices). The other pairs become tracks."
            else -> device?.allInOneProfile?.recordChannelOffset?.let { "Auto: master return on USB ${it + 1}/${it + 2}." }
                ?: if (device?.pioneerMixerProfile == null) "Auto uses USB 1/2. Choose another pair to audition it before recording."
                else "Auto locks an audible pair; S11 uses dedicated REC OUT. Selection is remembered per mixer."
        }, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

internal fun elapsedText(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
