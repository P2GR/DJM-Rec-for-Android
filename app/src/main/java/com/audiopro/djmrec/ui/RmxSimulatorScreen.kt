package com.audiopro.djmrec.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
private const val DEFAULT_BPM = 130f

@Composable
fun RmxSimulatorScreen() {
    val context = LocalContext.current
    var activePads by remember { mutableStateOf(listOf(false, false, false, false)) }
    var selectedPad by remember { mutableIntStateOf(0) }
    var isXpadHeld by remember { mutableStateOf(false) }
    var xpadTouchX by remember { mutableFloatStateOf(0f) }
    var bitCrush by remember { mutableFloatStateOf(0f) }
    var filterCutoff by remember { mutableFloatStateOf(20000f) }
    var filterType by remember { mutableIntStateOf(0) }
    var delayMix by remember { mutableFloatStateOf(0f) }
    var reverbMix by remember { mutableFloatStateOf(0f) }
    var bpm by remember { mutableFloatStateOf(DEFAULT_BPM) }
    var beatPhase by remember { mutableFloatStateOf(0f) }
    var autoBpm by remember { mutableStateOf(false) }
    var samplesLoaded by remember { mutableStateOf(false) }
    var manualBpm by remember { mutableFloatStateOf(DEFAULT_BPM) }

    // Lifecycle.
    DisposableEffect(Unit) {
        AudioEngine.openRmxOutput(-1, 44100, 2)
        if (!samplesLoaded) { loadRmxSamples(context); samplesLoaded = true }
        onDispose {
            AudioEngine.stopAllRmxSamples()
            AudioEngine.closeRmxOutput()
            AudioEngine.stopMicCapture()
        }
    }

    // BPM polling & effect param sync.
    LaunchedEffect(Unit) {
        while (true) {
            val result = AudioEngine.getBpmResult()
            val detectedBpm = if (result.size >= 5 && result[4] > 0.5f) result[0] else 0f
            val detectedPhase = if (result.size >= 5) result[2] else 0f

            val effectiveBpm = if (autoBpm && detectedBpm > 0f) detectedBpm else manualBpm
            val effectivePhase = if (autoBpm) detectedPhase else 0f

            bpm = effectiveBpm
            beatPhase = effectivePhase
            AudioEngine.updateRmxBeatClock(effectiveBpm, effectivePhase, autoBpm)

            // Sync loop lengths for active pads.
            for (i in 0 until 4) {
                if (activePads[i]) {
                    val loopLen = bpmToLoopLengthSamples(effectiveBpm)
                    AudioEngine.updateRmxVoiceLoop(i, loopLen)
                }
            }

            // Sync effect params.
            AudioEngine.setRmxEffectParam(0, bitCrush)
            AudioEngine.setRmxEffectParam(1, filterCutoff)
            AudioEngine.setRmxEffectParam(2, filterType.toFloat())
            AudioEngine.setRmxEffectParam(3, delayMix)
            AudioEngine.setRmxEffectParam(7, reverbMix)
            delay(33L)
        }
    }

    // Toggle AUTO mode.
    fun toggleAuto() {
        autoBpm = !autoBpm
        if (autoBpm) AudioEngine.startMicCapture() else AudioEngine.stopMicCapture()
    }

    // Toggle a sample pad on/off.
    fun togglePad(idx: Int) {
        val newPads = activePads.toMutableList()
        newPads[idx] = !newPads[idx]
        activePads = newPads
        selectedPad = idx
        if (newPads[idx]) {
            val loopLen = bpmToLoopLengthSamples(bpm)
            AudioEngine.triggerRmxSampleLooping(idx, 1.0f, 1.0f, loopLen)
        } else {
            AudioEngine.stopRmxSample(idx)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- BPM row: AUTO, display, +/- ---
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // AUTO button
            Button(
                onClick = { toggleAuto() },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (autoBpm) AccentGreen else SurfaceDark
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Text("AUTO", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (autoBpm) BackgroundDark else TextSecondary)
            }

            Spacer(Modifier.weight(1f))

            // - button
            IconButton(
                onClick = { manualBpm = (manualBpm - 1f).coerceAtLeast(40f) },
                colors = IconButtonDefaults.iconButtonColors(contentColor = TextPrimary),
                modifier = Modifier.size(32.dp)
            ) { Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary) }

            // BPM number
            Text(
                text = "${bpm.toInt()}",
                fontSize = 28.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, color = AccentGreen
            )

            // + button
            IconButton(
                onClick = { manualBpm = (manualBpm + 1f).coerceAtMost(220f) },
                colors = IconButtonDefaults.iconButtonColors(contentColor = TextPrimary),
                modifier = Modifier.size(32.dp)
            ) { Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary) }

            Spacer(Modifier.weight(1f))

            Text("BPM", fontSize = 12.sp, color = TextSecondary)
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = if (autoBpm) "AUTO — detecting from mic" else "MANUAL — use +/- to set",
            fontSize = 9.sp, color = TextSecondary.copy(alpha = 0.6f)
        )

        Spacer(Modifier.height(8.dp))

        // --- Sample Pads (1 row × 4) ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (i in 0 until 4) {
                val active = activePads[i]
                Button(
                    onClick = { togglePad(i) },
                    modifier = Modifier.size(62.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) sampleColors[i] else SurfaceDark
                    )
                ) {
                    Text(sampleNames[i], fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        color = if (active) BackgroundDark else TextPrimary)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- X-Pad (horizontal touch strip for loop length) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1D2E))
                .pointerInput(selectedPad) {
                    detectTapGestures(onPress = { offset ->
                        isXpadHeld = true; xpadTouchX = offset.x
                        // If the selected pad isn't playing, start it.
                        if (!activePads[selectedPad]) togglePad(selectedPad)
                        tryAwaitRelease(); isXpadHeld = false
                    })
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                for (i in beatDivisions.indices) {
                    val x = size.width * (i.toFloat() / (beatDivisions.size - 1))
                    drawLine(Color(0xFF333355), Offset(x, 0f), Offset(x, size.height), 1f)
                }
                if (isXpadHeld) {
                    drawCircle(Color.White.copy(alpha = 0.35f), 14f,
                        Offset(xpadTouchX.coerceIn(0f, size.width), size.height / 2f))
                }
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 4.dp),
                Arrangement.SpaceBetween, Alignment.Bottom) {
                divisionLabels.forEach { Text(it, fontSize = 8.sp,
                    color = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 4.dp)) }
            }
        }
        Text(
            text = if (activePads.any { it }) "Playing — hold X-Pad to adjust loop" else "Tap a pad above to start",
            fontSize = 10.sp, color = TextSecondary, modifier = Modifier.padding(top = 3.dp)
        )

        // --- STOP ALL button ---
        if (activePads.any { it }) {
            Button(
                onClick = { AudioEngine.stopAllRmxSamples(); activePads = listOf(false, false, false, false) },
                modifier = Modifier.fillMaxWidth().height(36.dp).padding(top = 4.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
            ) { Text("STOP ALL", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }

        Spacer(Modifier.height(8.dp))

        // --- Effect Knobs ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            EffectKnob("BIT CRUSH", bitCrush, 0f, 1f, "%.0f%%") { bitCrush = it }
            EffectKnob("FILTER", filterCutoff, 50f, 20000f, "%.0fHz") { filterCutoff = it }
            EffectKnob("DELAY", delayMix, 0f, 1f, "%.0f%%") { delayMix = it }
            EffectKnob("REVERB", reverbMix, 0f, 1f, "%.0f%%") { reverbMix = it }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), Arrangement.Center) {
            listOf("LP", "BP", "HP").forEachIndexed { i, l ->
                TextButton(onClick = { filterType = i }) {
                    Text(l, fontSize = 11.sp, color = if (filterType == i) AccentGreen else TextSecondary)
                }
            }
        }
    }
}

private fun bpmToLoopLengthSamples(bpm: Float): Int {
    val effectiveBpm = if (bpm > 0f) bpm else DEFAULT_BPM
    val beatsPerSec = effectiveBpm / 60f
    val samplesPerBeat = 44100f / beatsPerSec
    return samplesPerBeat.toInt() // 1 beat loop
}

@Composable
private fun EffectKnob(label: String, value: Float, min: Float, max: Float, format: String, onValueChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(SurfaceDark), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(AccentGreen, -150f, ((value - min) / (max - min)).coerceIn(0f, 1f) * 300f, false,
                    style = Stroke(width = 3.5f), size = Size(size.width - 8f, size.height - 8f), topLeft = Offset(4f, 4f))
            }
        }
        Text(label, fontSize = 7.sp, color = TextSecondary, modifier = Modifier.padding(top = 1.dp))
        Text(String.format(format, value), fontSize = 8.sp, color = TextPrimary)
    }
}

private fun loadRmxSamples(context: Context) {
    val files = listOf("samples/kick.wav", "samples/snare.wav", "samples/hihat.wav", "samples/clap.wav")
    files.forEachIndexed { i, path ->
        try {
            context.assets.open(path).use { input ->
                val sample = com.audiopro.djmrec.audio.WavSampleLoader.loadFromMemory(input.readBytes())
                if (sample.valid) AudioEngine.loadRmxSample(i, sample.data)
            }
        } catch (e: Exception) { android.util.Log.e("RmxScreen", "Failed to load $path: ${e.message}") }
    }
}
