package com.audiopro.djmrec.ui

import android.os.SystemClock
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.streaming.LiveStreamStatus
import com.audiopro.djmrec.ui.components.StereoVuMeter
import com.audiopro.djmrec.ui.theme.AccentRed
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** These Compose overlays are local UI siblings of the camera surface, never encoder filters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraLiveScreen(viewModel: MainViewModel) {
    val live by viewModel.liveStreamState.collectAsState()
    val youtube by viewModel.youtubeBroadcastState.collectAsState()
    val recording by viewModel.recordingState.collectAsState()
    val elapsed by viewModel.elapsedMillis.collectAsState()
    val gain by viewModel.recordingGainDb.collectAsState()
    val saving by viewModel.saving.collectAsState()
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var awake by rememberSaveable { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val view = LocalView.current
    DisposableEffect(view, awake) {
        val previous = view.keepScreenOn
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(Unit) { onDispose { viewModel.detachLivePreview() } }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    BackHandler { if (settings) settings = false else confirmStop = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { SurfaceView(it).also(viewModel::attachLivePreview) },
            modifier = Modifier.fillMaxSize()
        )
        if (grid) Canvas(Modifier.fillMaxSize()) {
            for (i in 1..2) {
                drawLine(Color.White.copy(alpha = 0.35f), Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height))
                drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3))
            }
        }
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            color = Color.Black.copy(alpha = 0.78f), contentColor = Color.White,
            shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (live.status == LiveStreamStatus.LIVE) "LIVE · ${live.platform?.label.orEmpty()}" else live.status.name,
                        style = MaterialTheme.typography.labelLarge)
                    Text(cameraTime(if (live.startedAtMillis > 0) now - live.startedAtMillis else 0), fontFamily = FontFamily.Monospace)
                }
                if (live.platform == com.audiopro.djmrec.streaming.LivePlatform.YOUTUBE &&
                    youtube.status != com.audiopro.djmrec.streaming.YouTubeBroadcastStatus.LIVE) {
                    Text(youtube.message, style = MaterialTheme.typography.labelMedium, maxLines = 2,
                        color = if (youtube.status == com.audiopro.djmrec.streaming.YouTubeBroadcastStatus.ERROR) AccentRed else Color.White)
                }
                CameraAudioMeter(viewModel)
                Text("Mixer gain ${if (gain >= 0) "+" else ""}$gain dB · ${live.bitrateBitsPerSecond / 1000} kbps",
                    style = MaterialTheme.typography.labelMedium)
                Text(when {
                    live.audioFramesSent == 0L -> "Waiting for outgoing audio"
                    live.status == LiveStreamStatus.RECONNECTING -> "Reconnecting — check network"
                    else -> "AAC audio sent · peak ${live.audioPeakDb.roundToInt()} dBFS"
                }, style = MaterialTheme.typography.labelMedium)
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            color = Color.Black.copy(alpha = 0.78f), contentColor = Color.White,
            shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (recording) {
                    is RecordingState.Recording -> "REC ${cameraTime(elapsed)}"
                    is RecordingState.Paused -> "Recording paused · ${cameraTime(elapsed)}"
                    else -> "Streaming only · local recording off"
                }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::switchLiveCamera, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Cameraswitch, "Switch camera")
                    }
                    Button(onClick = { confirmStop = true }, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
                        Icon(Icons.Default.Stop, null); Text("End stream")
                    }
                    IconButton(onClick = { settings = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Settings, "Stream controls")
                    }
                }
            }
        }
    }
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("End livestream?") },
        text = { Text("Viewers will disconnect. Any local recording continues.") },
        confirmButton = { TextButton(onClick = { confirmStop = false; viewModel.stopLiveStream() }) { Text("End stream") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep streaming") } }
    )
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Stream controls", style = MaterialTheme.typography.titleLarge)
            Text("Meters, timers and guides appear only on your screen.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Framing grid"); Switch(grid, { grid = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Keep screen awake"); Switch(awake, { awake = it })
            }
            Text("Mixer gain: ${if (gain >= 0) "+" else ""}$gain dB")
            Slider(value = gain.toFloat(), onValueChange = { viewModel.setRecordingGainDb(it.roundToInt()) },
                valueRange = -12f..24f, steps = 35,
                enabled = recording is RecordingState.Monitoring && !saving)
            Text(if (recording is RecordingState.Monitoring) "Gain changes the audio sent to viewers. Watch for clipping."
                else "Gain is locked while recording or saving a set.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CameraAudioMeter(viewModel: MainViewModel) {
    val levels by viewModel.levels.collectAsState()
    StereoVuMeter(levels, Modifier.fillMaxWidth())
}

internal fun cameraTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "%02d:%02d:%02d".format(java.util.Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
}
