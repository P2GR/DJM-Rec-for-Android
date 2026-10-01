package com.audiopro.djmrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.audio.TrackWaveformHistory
import com.audiopro.djmrec.ui.components.StereoVuMeter
import com.audiopro.djmrec.ui.components.TimelineRuler
import com.audiopro.djmrec.ui.components.TimelineWaveform
import com.audiopro.djmrec.ui.components.TrackMeter
import com.audiopro.djmrec.ui.theme.AccentAmber
import com.audiopro.djmrec.ui.theme.AccentRed
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.OutlineSubtle
import com.audiopro.djmrec.ui.theme.SurfaceVariantDark
import com.audiopro.djmrec.ui.theme.TextPrimary
import com.audiopro.djmrec.ui.theme.TextSecondary
import java.util.Locale

private val TrackHeaderWidth = 164.dp
// Compact rows put the arm button beside the title, so their (wide, landscape) header grows.
private val TrackHeaderWidthCompact = 184.dp
private val TrackRowHeight = 88.dp
private val TrackRowHeightCompact = 64.dp
private val floorLevels = ChannelLevel(-60f, -60f, false).let { StereoLevels(it, it) }

/**
 * Advanced mode: every input pair of the device as a track lane with its own arm button,
 * gain and live 3-band waveform, with the master (the set itself) pinned on top.
 */
@Composable
fun MultitrackPanel(viewModel: MainViewModel, onOpenSetup: () -> Unit, modifier: Modifier = Modifier) {
    val tracks by viewModel.multitrackTracks.collectAsState()
    val levels by viewModel.trackLevels.collectAsState()
    val masterLevels by viewModel.levels.collectAsState()
    val state by viewModel.recordingState.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val elapsed by viewModel.elapsedMillis.collectAsState()
    val active by viewModel.multitrackActive.collectAsState()
    val masterGain by viewModel.recordingGainDb.collectAsState()
    val revision = viewModel.timelineRevision.collectAsState()
    val recording = state is RecordingState.Recording || state is RecordingState.Paused
    val locked = saving || recording

    DisposableEffect(Unit) {
        viewModel.setMultitrackVisible(true)
        viewModel.setWaveformVisible(true)
        onDispose {
            viewModel.setMultitrackVisible(false)
            viewModel.setWaveformVisible(false)
        }
    }
    val timeline = viewModel.multitrackTimeline
    MultitrackContent(
        tracks = tracks,
        trackLevels = levels,
        masterLevels = masterLevels,
        historyFor = { if (it.isMaster) timeline.master else timeline.track(it.index) },
        revision = revision,
        spanMillis = timeline.master.capacity * timeline.master.columnMillis,
        elapsedMillis = elapsed,
        recording = recording,
        locked = locked,
        active = active,
        masterGainDb = masterGain,
        onArm = viewModel::setTrackArmed,
        onSource = viewModel::setTrackSource,
        onGain = viewModel::setTrackGainDb,
        onOpenSetup = onOpenSetup,
        modifier = modifier
    )
}

/** Stateless multitrack view: everything it shows comes in as parameters. */
@Composable
fun MultitrackContent(
    tracks: List<MultitrackTrack>,
    trackLevels: List<StereoLevels>,
    masterLevels: StereoLevels,
    historyFor: (MultitrackTrack) -> TrackWaveformHistory,
    revision: State<Long>,
    spanMillis: Double,
    elapsedMillis: Long,
    recording: Boolean,
    locked: Boolean,
    active: Boolean,
    masterGainDb: Int,
    onArm: (track: Int, armed: Boolean) -> Unit,
    onSource: (track: Int, source: Int) -> Unit,
    onGain: (track: Int, gainDb: Float, persist: Boolean) -> Unit,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sheetTrack by rememberSaveable { mutableStateOf<Int?>(null) }
    if (tracks.isEmpty()) {
        Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                "This input has a single stereo pair, so there is nothing to record next to the master. " +
                    "Advanced mode needs a mixer or interface with more than 2 channels.",
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary
            )
        }
        return
    }

    // Master first, then the other pairs in USB order.
    val ordered = remember(tracks) { tracks.sortedByDescending { it.isMaster } }
    val armedCount = tracks.count { it.armed }
    BoxWithConstraints(modifier) {
        // Short panels (landscape phones) switch to one-line rows so more lanes stay visible.
        val compact = maxHeight < 420.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(if (compact) TrackHeaderWidthCompact else TrackHeaderWidth).padding(start = 8.dp)) {
                    Text("TRACKS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = TextSecondary)
                    Text(if (active) "$armedCount armed + master" else "Starting input...",
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
                }
                TimelineRuler(
                    spanMillis = spanMillis,
                    elapsedMillis = elapsedMillis,
                    recording = recording,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            HorizontalDivider(color = OutlineSubtle)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(ordered, key = { it.index }) { track ->
                    TrackRow(
                        track = track,
                        levels = if (track.isMaster) masterLevels else trackLevels.getOrNull(track.index) ?: floorLevels,
                        history = historyFor(track),
                        revision = revision,
                        masterGainDb = masterGainDb,
                        recording = recording,
                        locked = locked,
                        compact = compact,
                        onArm = { onArm(track.index, it) },
                        onSource = { onSource(track.index, it) },
                        onOpenTrack = { if (track.isMaster) onOpenSetup() else sheetTrack = track.index }
                    )
                    HorizontalDivider(color = OutlineSubtle)
                }
            }
        }
    }

    val sheetFor = sheetTrack?.let { index -> tracks.firstOrNull { it.index == index && !it.isMaster } }
    if (sheetFor != null) {
        TrackSheet(
            track = sheetFor,
            levels = trackLevels.getOrNull(sheetFor.index) ?: floorLevels,
            enabled = !locked,
            onSource = { onSource(sheetFor.index, it) },
            onGain = { value, persist -> onGain(sheetFor.index, value, persist) },
            onDismiss = { sheetTrack = null }
        )
    }
}

@Composable
private fun TrackRow(
    track: MultitrackTrack,
    levels: StereoLevels,
    history: TrackWaveformHistory,
    revision: State<Long>,
    masterGainDb: Int,
    recording: Boolean,
    locked: Boolean,
    compact: Boolean,
    onArm: (Boolean) -> Unit,
    onSource: (Int) -> Unit,
    onOpenTrack: () -> Unit
) {
    val number = track.index + 1
    val gainText = if (track.isMaster) formatGain(masterGainDb.toFloat(), decimals = false) else formatGain(track.gainDb)
    val gainDescription = if (track.isMaster) "Master gain $gainText, change in Recording setup"
        else "Track $number gain $gainText"
    Row(Modifier.fillMaxWidth().height(if (compact) TrackRowHeightCompact else TrackRowHeight)) {
        if (compact) {
            // One line: arm, then the title and gain; the header opens the track sheet.
            Row(
                Modifier.width(TrackHeaderWidthCompact).fillMaxHeight()
                    .background(SurfaceVariantDark.copy(alpha = 0.35f))
                    .clickable(enabled = track.isMaster || !locked, role = Role.Button,
                        onClickLabel = if (track.isMaster) "Open recording setup" else "Track source and gain") { onOpenTrack() }
                    .padding(start = 2.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArmControl(track, number, recording, locked, onArm)
                Column(Modifier.weight(1f)) {
                    TrackTitle(track, number, showDropdown = false, enabled = !locked)
                    Text(gainText, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = TextSecondary, maxLines = 1,
                        modifier = Modifier.semantics { contentDescription = gainDescription })
                }
            }
        } else {
            Column(
                Modifier.width(TrackHeaderWidth).fillMaxHeight()
                    .background(SurfaceVariantDark.copy(alpha = 0.35f))
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SourceSelector(track, number, enabled = !locked, onSource = onSource)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ArmControl(track, number, recording, locked, onArm)
                    GainChip(text = gainText, description = gainDescription,
                        enabled = track.isMaster || !locked, onClick = onOpenTrack)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp, vertical = if (compact) 4.dp else 6.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                TimelineWaveform(
                    history = history,
                    revision = revision,
                    dimmed = !track.armed && !track.isMaster,
                    recording = recording,
                    modifier = Modifier.fillMaxSize().semantics {
                        contentDescription = "Track $number waveform, ${track.subtitle}"
                    }
                )
                Text(track.subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(4.dp)
                        .background(BackgroundDark.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp))
            }
            Spacer(Modifier.height(4.dp))
            TrackMeter(levels, Modifier.fillMaxWidth().height(if (compact) 6.dp else 8.dp).semantics {
                contentDescription = "Track $number level, peak ${maxOf(levels.left.peakDb, levels.right.peakDb).toInt()} dBFS" +
                    if (levels.left.isClipping || levels.right.isClipping) ", clipping" else ""
            })
        }
    }
}

/** Arm toggle for a track; the master is always recorded and shows a badge instead. */
@Composable
private fun ArmControl(track: MultitrackTrack, number: Int, recording: Boolean, locked: Boolean, onArm: (Boolean) -> Unit) {
    if (track.isMaster) {
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(6.dp), color = AccentRed.copy(alpha = 0.16f)) {
                Text("MASTER", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = AccentRed, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
        return
    }
    IconToggleButton(
        checked = track.armed,
        onCheckedChange = onArm,
        enabled = !locked,
        modifier = Modifier.semantics {
            contentDescription = "Record track $number, ${track.title}"
            stateDescription = if (track.armed) "Armed" else "Not armed"
        }
    ) {
        Icon(
            if (track.armed) Icons.Default.FiberManualRecord else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = when {
                !track.armed -> TextSecondary
                locked && !recording -> AccentRed.copy(alpha = 0.5f)
                else -> AccentRed
            }
        )
    }
}

@Composable
private fun TrackTitle(track: MultitrackTrack, number: Int, showDropdown: Boolean, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$number", style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = TextSecondary, modifier = Modifier.width(16.dp))
        Text(track.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (track.routingError != null) {
            Icon(Icons.Default.WarningAmber, "Routing problem", Modifier.padding(start = 2.dp).size(16.dp), tint = AccentAmber)
        }
        if (showDropdown && track.sourceOptions.isNotEmpty()) {
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp),
                tint = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.4f))
        }
    }
}

/** Track number and source; a dropdown when the mixer lets the app route this pair. */
@Composable
private fun SourceSelector(track: MultitrackTrack, number: Int, enabled: Boolean, onSource: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val routable = track.sourceOptions.isNotEmpty()
    Box {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 32.dp)
                .clickable(enabled = routable && enabled, role = Role.Button, onClickLabel = "Change source") { expanded = true }
                .padding(start = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            TrackTitle(track, number, showDropdown = true, enabled = enabled)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            track.routingError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = AccentAmber,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(240.dp))
            }
            track.sourceOptions.forEach { option ->
                val selected = option.code == track.selectedSource
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                            Text(option.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    },
                    leadingIcon = {
                        if (selected) Icon(Icons.Default.Check, "Selected") else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        expanded = false
                        if (!selected) onSource(option.code)
                    }
                )
            }
        }
    }
}

@Composable
private fun GainChip(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = SurfaceVariantDark,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }
    ) {
        Box(Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = if (enabled) TextPrimary else TextSecondary, maxLines = 1)
        }
    }
}

/** Source (when routable) and gain for one track, with its live meter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSheet(
    track: MultitrackTrack,
    levels: StereoLevels,
    enabled: Boolean,
    onSource: (Int) -> Unit,
    onGain: (Float, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(track.index) { mutableFloatStateOf(track.gainDb) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Track ${track.index + 1}", style = MaterialTheme.typography.headlineSmall)
            Text("${track.title} · ${track.channels.usbLabel}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            if (!enabled) Text("Track settings are locked while recording.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            if (track.sourceOptions.isNotEmpty()) {
                Text("Mixer source", style = MaterialTheme.typography.titleSmall)
                track.routingError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AccentAmber) }
                Column(Modifier.selectableGroup()) {
                    track.sourceOptions.forEach { option ->
                        val selected = option.code == track.selectedSource
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                                    if (!selected) onSource(option.code)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = null, enabled = enabled)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                Text(option.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                        }
                    }
                }
                HorizontalDivider(color = OutlineSubtle)
            }
            Text("Gain", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatGain(value), style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { value = 0f; onGain(0f, true) }, enabled = enabled) { Text("Reset to 0 dB") }
            }
            Slider(
                value = value,
                onValueChange = { value = it; onGain(it, false) },
                onValueChangeFinished = { onGain(value, true) },
                valueRange = -24f..12f,
                steps = 71,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Track ${track.index + 1} gain in decibels" }
            )
            Text("0 dB keeps the input level exactly. Above 0 dB this track's own limiter keeps peaks under -1 dBFS; it never affects the other tracks.",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            StereoVuMeter(levels)
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun formatGain(db: Float, decimals: Boolean = true): String {
    val sign = if (db > 0f) "+" else ""
    return if (decimals) String.format(Locale.US, "%s%.1f dB", sign, db) else String.format(Locale.US, "%s%.0f dB", sign, db)
}
