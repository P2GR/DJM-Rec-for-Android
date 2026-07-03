package com.audiopro.djmrec.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.ui.theme.AccentRed
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.MeterAmber
import com.audiopro.djmrec.ui.theme.SurfaceDark
import com.audiopro.djmrec.ui.theme.TextPrimary
import com.audiopro.djmrec.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private val sampleNames = listOf("KICK", "SNARE", "HI-HAT", "CLAP")
private val sampleColors = listOf(AccentRed, MeterAmber, AccentGreen, Color(0xFF4488CC))
private val beatDivisions = listOf(1f/16f, 1f/8f, 1f/4f, 1f/2f, 1f, 2f, 4f)
private val divisionLabels = listOf("1/16", "1/8", "1/4", "1/2", "1", "2", "4")

@Composable
fun RmxSimulatorScreen() {
    val context = LocalContext.current
    var selectedSample by remember { mutableIntStateOf(0) }
    var isXpadHeld by remember { mutableStateOf(false) }
    var xpadTouchX by remember { mutableFloatStateOf(0f) }
    var bitCrush by remember { mutableFloatStateOf(0f) }
    var filterCutoff by remember { mutableFloatStateOf(20000f) }
    var filterType by remember { mutableIntStateOf(0) }
    var delayMix by remember { mutableFloatStateOf(0f) }
    var reverbMix by remember { mutableFloatStateOf(0f) }
    var bpm by remember { mutableFloatStateOf(0f) }
    var beatPhase by remember { mutableFloatStateOf(0f) }
    var locked by remember { mutableStateOf(false) }
    var samplesLoaded by remember { mutableStateOf(false) }
    var tapTimes by remember { mutableStateOf(listOf<Long>()) }
    var manualBpm by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(Unit) {
        AudioEngine.startMicCapture()
        AudioEngine.openRmxOutput(-1 /* built-in output */, 44100, 2)
        if (!samplesLoaded) { loadRmxSamples(context); samplesLoaded = true }
        onDispose {
            AudioEngine.stopAllRmxSamples()
            AudioEngine.closeRmxOutput()
            AudioEngine.stopMicCapture()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val result = AudioEngine.getBpmResult()
            if (result.size >= 5) {
                val dBpm = manualBpm ?: result[0]
                bpm = dBpm; beatPhase = result[2]; locked = result[4] > 0.5f || manualBpm != null
                AudioEngine.updateRmxBeatClock(dBpm, result[2], locked)
            }
            AudioEngine.setRmxEffectParam(0, bitCrush)
            AudioEngine.setRmxEffectParam(1, filterCutoff)
            AudioEngine.setRmxEffectParam(2, filterType.toFloat())
            AudioEngine.setRmxEffectParam(3, delayMix)
            AudioEngine.setRmxEffectParam(7, reverbMix)
            delay(33L)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (bpm > 0f) "${bpm.toInt()} BPM" else "-- BPM", fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = AccentGreen)
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = {
                tapTimes = (tapTimes + System.currentTimeMillis()).takeLast(8)
                if (tapTimes.size >= 4) {
                    val avgMs = tapTimes.zipWithNext { a, b -> b - a }.average().toFloat()
                    if (avgMs > 0f) { manualBpm = 60000f / avgMs; AudioEngine.setRmxManualBpm(manualBpm!!) }
                }
            }) { Text("TAP", color = TextSecondary, fontSize = 11.sp) }
            if (manualBpm != null) TextButton(onClick = { manualBpm = null; AudioEngine.clearRmxManualBpm() }) { Text("auto", color = TextSecondary, fontSize = 10.sp) }
        }
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (i in 0 until 4) {
                val sel = selectedSample == i
                Button(
                    onClick = { selectedSample = i; AudioEngine.stopAllRmxSamples() },
                    modifier = Modifier.size(68.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) sampleColors[i] else SurfaceDark
                    )
                ) {
                    Text(sampleNames[i], fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        color = if (sel) BackgroundDark else TextPrimary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1D2E))
            .pointerInput(selectedSample) { detectTapGestures(onPress = { offset ->
                isXpadHeld = true; xpadTouchX = offset.x
                AudioEngine.triggerRmxSample(selectedSample, 1.0f, 1.0f)
                tryAwaitRelease(); isXpadHeld = false; AudioEngine.stopRmxSample(selectedSample)
            }) }) {
            Canvas(Modifier.fillMaxSize()) {
                for (i in beatDivisions.indices) drawLine(Color(0xFF333355), Offset(size.width * (i.toFloat() / (beatDivisions.size - 1)), 0f), Offset(size.width * (i.toFloat() / (beatDivisions.size - 1)), size.height), 1f)
                if (isXpadHeld) drawCircle(Color.White.copy(alpha = 0.3f), 12f, Offset(xpadTouchX.coerceIn(0f, size.width), size.height / 2f))
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), Arrangement.SpaceBetween, Alignment.Bottom) { divisionLabels.forEach { Text(it, fontSize = 8.sp, color = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 4.dp)) } }
        }
        Text(if (isXpadHeld) "HOLD — release to stop" else "Tap X-Pad to play on beat", fontSize = 10.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            EffectKnob("BIT CRUSH", bitCrush, 0f, 1f, "%.0f%%") { bitCrush = it }
            EffectKnob("FILTER", filterCutoff, 50f, 20000f, "%.0fHz") { filterCutoff = it }
            EffectKnob("DELAY", delayMix, 0f, 1f, "%.0f%%") { delayMix = it }
            EffectKnob("REVERB", reverbMix, 0f, 1f, "%.0f%%") { reverbMix = it }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.Center) {
            listOf("LP", "BP", "HP").forEachIndexed { i, l -> TextButton(onClick = { filterType = i }) { Text(l, fontSize = 11.sp, color = if (filterType == i) AccentGreen else TextSecondary) } }
        }
    }
}

@Composable
private fun EffectKnob(label: String, value: Float, min: Float, max: Float, format: String, onValueChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(SurfaceDark), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(AccentGreen, -150f, ((value - min) / (max - min)).coerceIn(0f, 1f) * 300f, false, style = Stroke(width = 4f), size = Size(size.width - 8f, size.height - 8f), topLeft = Offset(4f, 4f))
            }
        }
        Text(label, fontSize = 8.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
        Text(String.format(format, value), fontSize = 9.sp, color = TextPrimary)
    }
}

private fun loadRmxSamples(context: Context) {
    val files = listOf("samples/kick.wav", "samples/snare.wav", "samples/hihat.wav", "samples/clap.wav")
    files.forEachIndexed { i, path ->
        try {
            context.assets.open(path).use { input ->
                val bytes = input.readBytes()
                val sample = com.audiopro.djmrec.audio.WavSampleLoader.loadFromMemory(bytes)
                if (sample.valid) AudioEngine.loadRmxSample(i, sample.data)
            }
        } catch (e: Exception) { android.util.Log.e("RmxScreen", "Failed to load $path: ${e.message}") }
    }
}
