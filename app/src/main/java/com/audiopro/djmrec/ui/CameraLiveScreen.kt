package com.audiopro.djmrec.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.streaming.LivePlatform
import com.audiopro.djmrec.streaming.LiveStreamStatus
import com.audiopro.djmrec.streaming.YouTubeBroadcastStatus
import com.audiopro.djmrec.ui.components.StereoVuMeter
import com.audiopro.djmrec.ui.theme.AccentAmber
import com.audiopro.djmrec.ui.theme.AccentRed
import com.audiopro.djmrec.ui.theme.rememberReducedMotion
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
    val context = LocalContext.current

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    fun shareUrl(url: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Watch my DJ set live: $url")
        }
        runCatching { context.startActivity(Intent.createChooser(share, "Share broadcast")) }
    }

    // True fullscreen camera while a set is running; an edge swipe still reveals system bars.
    DisposableEffect(context) {
        val activity = context as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(view, awake) {
        val previous = view.keepScreenOn
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(Unit) { onDispose { viewModel.detachLivePreview() } }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    // Back leaves the console WITHOUT ending the stream — the console is a view on a running
    // service stream. Ending the stream is always the explicit red button.
    BackHandler { if (settings) settings = false else viewModel.cameraConsoleOpen.value = false }

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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Explicit, labeled exit: leaves the console while the stream keeps
                        // running (the red End stream button is the only way to stop the show).
                        Row(
                            modifier = Modifier.clickable { viewModel.cameraConsoleOpen.value = false }
                                .heightIn(min = 48.dp).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                            Text("Exit", style = MaterialTheme.typography.labelMedium)
                        }
                        LiveDot(live.status)
                        Text(
                            if (live.status == LiveStreamStatus.LIVE) "LIVE · ${live.platform?.label.orEmpty()}"
                            else live.status.name.replace('_', ' '),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(cameraTime(if (live.startedAtMillis > 0) now - live.startedAtMillis else 0),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        youtube.viewerCount?.let { count ->
                            Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.14f)) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.Visibility, "Watching now",
                                        modifier = Modifier.size(14.dp))
                                    Text(formatViewerCount(count), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        youtube.watchUrl?.let { url ->
                            IconButton(onClick = { shareUrl(url) }) {
                                Icon(Icons.Default.Share, "Share watch link")
                            }
                        }
                    }
                }
                CameraAudioMeter(viewModel)
                Text(when {
                    live.status == LiveStreamStatus.RECONNECTING -> "Reconnecting — check network"
                    live.platform == LivePlatform.YOUTUBE && youtube.status != YouTubeBroadcastStatus.LIVE -> youtube.message
                    youtube.healthIssues.isNotEmpty() -> youtube.healthIssues.first()
                    live.audioFramesSent == 0L -> "Waiting for outgoing audio"
                    else -> "AAC audio sent · peak ${live.audioPeakDb.roundToInt()} dBFS"
                }, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            color = Color.Black.copy(alpha = 0.78f), contentColor = Color.White,
            shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(when (recording) {
                        is RecordingState.Recording -> "REC ${cameraTime(elapsed)}"
                        is RecordingState.Paused -> "Recording paused · ${cameraTime(elapsed)}"
                        else -> "Streaming only · local recording off"
                    }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                    Text("gain ${if (gain > 0) "+" else ""}$gain dB · ${live.bitrateBitsPerSecond / 1000} kbps",
                        style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    val recordingActive =
                        recording is RecordingState.Recording || recording is RecordingState.Paused
                    Button(
                        onClick = {
                            if (recordingActive) viewModel.stopRecording() else viewModel.startRecording()
                        },
                        enabled = !saving && (recordingActive || recording is RecordingState.Monitoring),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) {
                        Icon(Icons.Default.FiberManualRecord, null)
                        Text(if (recordingActive) " Stop" else " Rec")
                    }
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
            Text("Broadcast", style = MaterialTheme.typography.titleLarge)
            Text("${live.platform?.label.orEmpty()} · ${if (live.status == LiveStreamStatus.LIVE) "Live now" else live.status.name.replace('_', ' ')}")
            youtube.viewerCount?.let { count ->
                Text("${formatViewerCount(count)} watching now", style = MaterialTheme.typography.titleMedium)
            }
            youtube.healthIssues.forEach { issue ->
                Text(issue, style = MaterialTheme.typography.bodySmall, color = AccentAmber)
            }
            youtube.watchUrl?.let { url ->
                OutlinedButton(onClick = { openUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open watch page")
                }
                OutlinedButton(onClick = { shareUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Share watch link")
                }
            }
            youtube.studioUrl?.let { url ->
                OutlinedButton(onClick = { openUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open YouTube Studio")
                }
            }
            HorizontalDivider()
            Text("Stream controls", style = MaterialTheme.typography.titleLarge)
            Text("Meters, timers and guides appear only on your screen.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Framing grid"); Switch(grid, { grid = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Keep screen awake"); Switch(awake, { awake = it })
            }
            Text("Mixer gain: ${if (gain > 0) "+" else ""}$gain dB")
            Slider(value = gain.toFloat(), onValueChange = { viewModel.setRecordingGainDb(it.roundToInt()) },
                valueRange = 0f..12f, steps = 11,
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

/** Pulsing red dot while live, steady amber while connecting (steady under reduced motion). */
@Composable
private fun LiveDot(status: LiveStreamStatus) {
    val color = when (status) {
        LiveStreamStatus.LIVE, LiveStreamStatus.ERROR -> AccentRed
        LiveStreamStatus.PREPARING, LiveStreamStatus.CONNECTING, LiveStreamStatus.RECONNECTING -> AccentAmber
        LiveStreamStatus.IDLE -> Color.White
    }
    val reducedMotion = rememberReducedMotion()
    val alpha = if (status != LiveStreamStatus.LIVE || reducedMotion) 1f else {
        val transition = rememberInfiniteTransition(label = "liveDot")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "liveDotPulse"
        )
        pulse
    }
    Box(Modifier.size(10.dp).background(color.copy(alpha = alpha), CircleShape))
}

internal fun formatViewerCount(count: Int): String = when {
    count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000f)
    count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000f)
    else -> count.toString()
}
