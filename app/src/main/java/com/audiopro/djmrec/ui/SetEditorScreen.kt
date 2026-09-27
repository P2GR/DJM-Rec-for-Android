package com.audiopro.djmrec.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.editor.EditMath
import com.audiopro.djmrec.editor.EditSettings
import com.audiopro.djmrec.editor.EditorAnalysis
import com.audiopro.djmrec.editor.EditorState
import com.audiopro.djmrec.editor.SetEditor
import com.audiopro.djmrec.storage.LibraryRecording
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.SurfaceDark
import com.audiopro.djmrec.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Loudness targets offered by the editor, with where they fit. */
private enum class LoudnessTarget(val lufs: Double?, val label: String, val hint: String) {
    OFF(null, "Keep level", "The level stays as recorded."),
    STREAMING(-14.0, "-14 LUFS", "Matches YouTube, Spotify, SoundCloud and Mixcloud playback."),
    PODCAST(-16.0, "-16 LUFS", "A little quieter, with more headroom; suits Apple Music."),
    LOUD(-11.0, "-11 LUFS", "Loud club-style master; the limiter holds peaks at -1 dBFS.")
}

/**
 * Post-set editor: trim, fades, loudness normalization and export to MP3/WAV/FLAC. Writes a
 * new "(edit)" file in Music/DJMRec; the original recording is never changed.
 */
@Composable
fun SetEditorDialog(recording: LibraryRecording, onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(recording.uri) { SetEditor.open(context, recording) }
    val close = {
        SetEditor.close()
        onClose()
    }
    Dialog(onDismissRequest = { if (SetEditor.state.value !is EditorState.Exporting) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = BackgroundDark) {
            val state by SetEditor.state.collectAsState()
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close, enabled = state !is EditorState.Exporting) {
                        Icon(Icons.Default.Close, "Close editor")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Edit set", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(recording.displayName, style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                when (val current = state) {
                    is EditorState.Analyzing -> AnalyzingPane(current.progress)
                    is EditorState.Failed -> if (current.analysis == null) {
                        MessagePane(current.message, action = "Close", onAction = close)
                    } else {
                        EditorPane(current.analysis, state, onClose = close)
                    }
                    is EditorState.Ready -> EditorPane(current.analysis, state, onClose = close)
                    is EditorState.Exporting -> EditorPane(current.analysis, state, onClose = close)
                    is EditorState.Exported -> EditorPane(current.analysis, state, onClose = close)
                    EditorState.Idle -> AnalyzingPane(0f)
                }
            }
        }
    }
}

@Composable
private fun AnalyzingPane(progress: Float) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Reading your set", style = MaterialTheme.typography.titleMedium)
        Text("Measuring loudness and drawing the waveform. Long sets take a minute.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MessagePane(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text(action) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditorPane(analysis: EditorAnalysis, state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val duration = analysis.durationMillis.toFloat().coerceAtLeast(1f)
    var trimStart by rememberSaveable(analysis) { mutableFloatStateOf(0f) }
    var trimEnd by rememberSaveable(analysis) { mutableFloatStateOf(duration) }
    var fadeIn by rememberSaveable(analysis) { mutableFloatStateOf(0f) }
    var fadeOut by rememberSaveable(analysis) { mutableFloatStateOf(0f) }
    var target by rememberSaveable(analysis) { mutableStateOf(LoudnessTarget.OFF) }
    var format by rememberSaveable(analysis) { mutableStateOf(RecordingFormat.MP3) }
    val exporting = state is EditorState.Exporting
    val keptMillis = (trimEnd - trimStart).roundToLong()
    // Loudness of the kept range comes from the analysis pass, so this is instant.
    val measured = remember(analysis, trimStart.roundToLong() / 1000, trimEnd.roundToLong() / 1000) {
        analysis.loudnessLufs(trimStart.roundToLong(), trimEnd.roundToLong())
    }
    val gainDb = EditMath.normalizationGainDb(measured, target.lufs)
    val settings = EditSettings(
        trimStartMillis = trimStart.roundToLong(),
        trimEndMillis = trimEnd.roundToLong(),
        fadeInMillis = fadeIn.roundToLong(),
        fadeOutMillis = fadeOut.roundToLong(),
        targetLufs = target.lufs,
        format = format
    )
    val preview = rememberPreviewPlayer(analysis.recording)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EditorSection("Trim") {
                OverviewWaveform(analysis, trimStart / duration, trimEnd / duration,
                    fadeIn / duration, fadeOut / duration)
                RangeSlider(
                    value = trimStart..trimEnd,
                    onValueChange = { range ->
                        trimStart = range.start
                        trimEnd = max(range.endInclusive, range.start + 1000f).coerceAtMost(duration)
                    },
                    valueRange = 0f..duration,
                    enabled = !exporting,
                    modifier = Modifier.semantics { contentDescription = "Start and end of the kept part" }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeLabel("Start", trimStart.roundToLong())
                    TimeLabel("Length", keptMillis)
                    TimeLabel("End", trimEnd.roundToLong())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewButton("Listen at start", preview.playingFrom == PreviewFrom.START, Modifier.weight(1f)) {
                        preview.toggle(PreviewFrom.START, trimStart.roundToLong(), trimStart.roundToLong() + PREVIEW_MILLIS)
                    }
                    PreviewButton("Listen at end", preview.playingFrom == PreviewFrom.END, Modifier.weight(1f)) {
                        val end = trimEnd.roundToLong()
                        preview.toggle(PreviewFrom.END, max(trimStart.roundToLong(), end - PREVIEW_MILLIS), end)
                    }
                }
                Text("Previews play the original; fades and level apply to the export.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            EditorSection("Fades") {
                val maxFadeIn = min(10_000f, keptMillis / 2f)
                val maxFadeOut = min(30_000f, keptMillis / 2f)
                SliderRow("Fade in", fadeIn.coerceAtMost(maxFadeIn), 0f..maxFadeIn, !exporting) { fadeIn = it }
                SliderRow("Fade out", fadeOut.coerceAtMost(maxFadeOut), 0f..maxFadeOut, !exporting) { fadeOut = it }
            }
            EditorSection("Loudness") {
                Text(
                    measured?.let { String.format(Locale.US, "Selection: %.1f LUFS · peak %.1f dBFS", it, peakDb(analysis.samplePeak)) }
                        ?: "Selection is silent",
                    style = MaterialTheme.typography.bodyMedium
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoudnessTarget.entries.forEach { option ->
                        FilterChip(selected = target == option, onClick = { target = option }, enabled = !exporting,
                            label = { Text(option.label) })
                    }
                }
                Text(target.hint, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (target.lufs != null && measured != null) {
                    Text(String.format(Locale.US, "Level change: %+.1f dB", gainDb),
                        style = MaterialTheme.typography.bodyMedium, color = AccentGreen)
                }
            }
            EditorSection("Save as") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(RecordingFormat.MP3 to "MP3 320 kbps", RecordingFormat.WAV to "WAV", RecordingFormat.FLAC to "FLAC")
                        .forEach { (option, label) ->
                            FilterChip(selected = format == option, onClick = { format = option }, enabled = !exporting,
                                label = { Text(label) })
                        }
                }
                Text("${SetEditor.editedName(analysis.recording.title, format)} in Music/DJMRec. The original stays untouched.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        Surface(color = SurfaceDark, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is EditorState.Exporting -> {
                        Text("Exporting ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = SetEditor::cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Cancel export")
                        }
                    }
                    is EditorState.Exported -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, null, tint = AccentGreen)
                            Text("Saved ${state.displayName}", style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = mimeFor(state.displayName)
                                    putExtra(Intent.EXTRA_STREAM, state.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(Intent.createChooser(share, "Share edited set")) }
                            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Icon(Icons.Default.Share, null); Text(" Share")
                            }
                            Button(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Done") }
                        }
                        TextButton(onClick = SetEditor::backToEditing) { Text("Make another version") }
                    }
                    else -> {
                        if (state is EditorState.Failed) {
                            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { preview.stop(); SetEditor.export(context, analysis, settings) },
                            enabled = keptMillis >= 1000,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        ) { Text("Export ${format.name}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = SurfaceDark) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun TimeLabel(label: String, millis: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(elapsedText(millis), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
                      onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(if (value < 50f) "Off" else String.format(Locale.US, "%.1f s", value / 1000f),
                style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        }
        Slider(value, onChange, enabled = enabled && range.endInclusive > 0f,
            valueRange = 0f..max(range.endInclusive, 1f),
            modifier = Modifier.semantics { contentDescription = "$label length" })
    }
}

@Composable
private fun PreviewButton(label: String, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
        Text(if (playing) " Stop" else " $label", maxLines = 1)
    }
}

/**
 * Overview of the whole set: bars outside the kept range are dimmed, and the fade ramps are
 * drawn into the bar heights so the result is visible before exporting.
 */
@Composable
private fun OverviewWaveform(analysis: EditorAnalysis, start: Float, end: Float, fadeIn: Float, fadeOut: Float) {
    val bars = remember(analysis) { overviewBars(analysis.hopPeaks, OVERVIEW_BARS) }
    val dim = TextSecondary.copy(alpha = 0.35f)
    Canvas(
        Modifier.fillMaxWidth().height(96.dp)
            .background(BackgroundDark, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Waveform overview of the set" }
    ) {
        val slot = size.width / bars.size
        val barWidth = max(1f, slot * 0.7f)
        bars.forEachIndexed { index, peak ->
            val position = (index + 0.5f) / bars.size
            val inside = position in start..end
            var factor = 1f
            if (inside && fadeIn > 0f && position < start + fadeIn) factor = (position - start) / fadeIn
            if (inside && fadeOut > 0f && position > end - fadeOut) factor = min(factor, (end - position) / fadeOut)
            val height = max(2f, peak * factor.coerceIn(0f, 1f) * size.height * 0.9f)
            drawRoundRect(
                color = if (inside) AccentGreen else dim,
                topLeft = Offset(index * slot, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

private enum class PreviewFrom { START, END }

/** Plays a few seconds of the original around the trim points. Main thread only. */
private class PreviewPlayer(val player: MediaPlayer) {
    var playingFrom by mutableStateOf<PreviewFrom?>(null)
    var stopAtMillis = 0L
    var ready = false

    fun toggle(from: PreviewFrom, startMillis: Long, endMillis: Long) {
        if (playingFrom == from) { stop(); return }
        if (!ready) return
        runCatching {
            player.seekTo(startMillis, MediaPlayer.SEEK_CLOSEST)
            player.start()
            stopAtMillis = endMillis
            playingFrom = from
        }
    }

    fun stop() {
        runCatching { if (player.isPlaying) player.pause() }
        playingFrom = null
    }
}

@Composable
private fun rememberPreviewPlayer(recording: LibraryRecording): PreviewPlayer {
    val context = LocalContext.current
    val preview = remember(recording.uri) { PreviewPlayer(MediaPlayer()) }
    DisposableEffect(preview) {
        val player = preview.player
        player.setOnPreparedListener { preview.ready = true }
        player.setOnCompletionListener { preview.playingFrom = null }
        runCatching { player.setDataSource(context, recording.uri); player.prepareAsync() }
        onDispose { runCatching { player.release() } }
    }
    val playingFrom = preview.playingFrom
    LaunchedEffect(preview, playingFrom) {
        while (preview.playingFrom != null) {
            val position = runCatching { preview.player.currentPosition.toLong() }.getOrDefault(0L)
            if (position >= preview.stopAtMillis) preview.stop()
            delay(100)
        }
    }
    return preview
}

private const val PREVIEW_MILLIS = 8_000L
private const val OVERVIEW_BARS = 160

/** Max-pools 100 ms hop peaks into [count] bars. */
internal fun overviewBars(hopPeaks: FloatArray, count: Int): FloatArray {
    if (hopPeaks.isEmpty()) return FloatArray(count)
    return FloatArray(count) { bar ->
        val from = (bar.toLong() * hopPeaks.size / count).toInt()
        val to = max(from + 1, ((bar + 1).toLong() * hopPeaks.size / count).toInt()).coerceAtMost(hopPeaks.size)
        var peak = 0f
        for (i in from until to) peak = max(peak, hopPeaks[i])
        peak
    }
}

private fun peakDb(peak: Double): Double = if (peak <= 0.0) -120.0 else 20.0 * log10(peak)

private fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    else -> "audio/mpeg"
}
