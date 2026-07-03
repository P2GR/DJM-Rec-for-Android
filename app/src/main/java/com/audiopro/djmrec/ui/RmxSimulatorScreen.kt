package com.audiopro.djmrec.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.delay

private val sampleNames = listOf("KICK", "SNARE", "HIHAT", "CLAP")
private val sampleColors = listOf(AccentRed, MeterAmber, AccentGreen, Color(0xFF4488CC))
private val beatDivisions = listOf(1f / 16f, 1f / 8f, 1f / 4f, 1f / 2f, 1f, 2f, 4f)
private val divisionLabels = listOf("1/16", "1/8", "1/4", "1/2", "1", "2", "4")
private val sceneFxLabels = listOf("CLEAN", "FILTER", "ECHO", "SPIRAL", "REVERB", "CRUSH")
private val filterLabels = listOf("LP", "BP", "HP")
private const val DEFAULT_BPM = 130f
private const val MIN_BPM = 40f
private const val MAX_BPM = 220f
private const val MIN_FILTER_HZ = 50f
private const val MAX_FILTER_HZ = 20000f
private const val MIN_KEY = -12
private const val MAX_KEY = 12

@Composable
fun RmxSimulatorScreen() {
    val context = LocalContext.current
    var activePads by remember { mutableStateOf(listOf(false, false, false, false)) }
    var selectedPad by remember { mutableIntStateOf(0) }
    var isXpadHeld by remember { mutableStateOf(false) }
    var xpadTouchX by remember { mutableFloatStateOf(0f) }
    var bitCrush by remember { mutableFloatStateOf(0f) }
    var filterCutoff by remember { mutableFloatStateOf(MAX_FILTER_HZ) }
    var filterType by remember { mutableIntStateOf(0) }
    var delayMix by remember { mutableFloatStateOf(0f) }
    var reverbMix by remember { mutableFloatStateOf(0f) }
    var bpm by remember { mutableFloatStateOf(DEFAULT_BPM) }
    var autoBpm by remember { mutableStateOf(false) }
    var samplesLoaded by remember { mutableStateOf(false) }
    var manualBpm by remember { mutableFloatStateOf(DEFAULT_BPM) }
    var loopDivisionIndex by remember { mutableIntStateOf(4) }
    var outputStatus by remember { mutableStateOf("Opening speaker") }
    var keyShift by remember { mutableIntStateOf(0) }
    var sceneFx by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioMgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val spkOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val outDeviceId = spkOut?.id ?: -1
        val result = AudioEngine.openRmxOutput(outDeviceId, 44100, 2)
        outputStatus = if (result > 0) "Speaker ready" else "Audio unavailable"
        if (!samplesLoaded) {
            loadRmxSamples(context)
            samplesLoaded = true
        }
        onDispose {
            AudioEngine.stopAllRmxSamples()
            AudioEngine.closeRmxOutput()
            AudioEngine.stopMicCapture()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val result = AudioEngine.getBpmResult()
            val detectedBpm = if (result.size >= 5 && result[4] > 0.5f && result[0] > 0f) result[0] else 0f
            val detectedPhase = if (result.size >= 5) result[2] else 0f
            val effectiveBpm = if (autoBpm && detectedBpm > 0f) detectedBpm else manualBpm
            bpm = effectiveBpm
            AudioEngine.updateRmxBeatClock(effectiveBpm, if (autoBpm) detectedPhase else 0f, autoBpm)
            activePads.forEachIndexed { i, active ->
                if (active) {
                    AudioEngine.updateRmxVoiceLoop(i, bpmToLoopLengthSamples(effectiveBpm, loopDivisionIndex))
                    AudioEngine.updateRmxVoicePitch(i, pitchRatioForKey(keyShift))
                }
            }
            AudioEngine.setRmxEffectParam(0, bitCrush)
            AudioEngine.setRmxEffectParam(1, filterCutoff)
            AudioEngine.setRmxEffectParam(2, filterType.toFloat())
            AudioEngine.setRmxEffectParam(3, delayMix)
            AudioEngine.setRmxEffectParam(4, bpmToLoopLengthSamples(effectiveBpm, loopDivisionIndex).toFloat())
            AudioEngine.setRmxEffectParam(5, 0.45f)
            AudioEngine.setRmxEffectParam(7, reverbMix)
            delay(33L)
        }
    }

    fun triggerPad(idx: Int) {
        val wasActive = activePads[idx]
        AudioEngine.stopAllRmxSamples()
        activePads = listOf(false, false, false, false)
        selectedPad = idx
        if (!wasActive) {
            activePads = List(4) { it == idx }
            AudioEngine.triggerRmxSampleLooping(
                idx,
                1.0f,
                pitchRatioForKey(keyShift),
                bpmToLoopLengthSamples(bpm, loopDivisionIndex)
            )
        }
    }

    fun setLoopFromX(x: Float, width: Float) {
        val nextIndex = divisionIndexForX(x, width)
        loopDivisionIndex = nextIndex
        xpadTouchX = x.coerceIn(0f, width)
        if (activePads[selectedPad]) {
            AudioEngine.updateRmxVoiceLoop(selectedPad, bpmToLoopLengthSamples(bpm, nextIndex))
        }
    }

    fun adjustManualBpm(delta: Float) {
        manualBpm = (manualBpm + delta).coerceIn(MIN_BPM, MAX_BPM)
        bpm = manualBpm
        if (!autoBpm) AudioEngine.setRmxManualBpm(manualBpm)
    }

    fun setAuto(enabled: Boolean) {
        autoBpm = enabled
        if (enabled) {
            AudioEngine.clearRmxManualBpm()
            AudioEngine.startMicCapture()
        } else {
            AudioEngine.stopMicCapture()
            AudioEngine.setRmxManualBpm(manualBpm)
        }
    }

    fun adjustKey(delta: Int) {
        keyShift = (keyShift + delta).coerceIn(MIN_KEY, MAX_KEY)
        activePads.forEachIndexed { idx, active ->
            if (active) AudioEngine.updateRmxVoicePitch(idx, pitchRatioForKey(keyShift))
        }
    }

    fun applySceneFx(index: Int) {
        sceneFx = index
        when (index) {
            0 -> { bitCrush = 0f; filterCutoff = MAX_FILTER_HZ; delayMix = 0f; reverbMix = 0f }
            1 -> { bitCrush = 0f; filterCutoff = 900f; filterType = 1; delayMix = 0.12f; reverbMix = 0f }
            2 -> { bitCrush = 0f; filterCutoff = MAX_FILTER_HZ; delayMix = 0.58f; reverbMix = 0.08f }
            3 -> { bitCrush = 0.12f; filterCutoff = 1300f; filterType = 2; delayMix = 0.72f; reverbMix = 0.18f }
            4 -> { bitCrush = 0f; filterCutoff = 4200f; filterType = 0; delayMix = 0.2f; reverbMix = 0.62f }
            5 -> { bitCrush = 0.7f; filterCutoff = 2800f; filterType = 1; delayMix = 0.24f; reverbMix = 0.08f }
        }
    }

    fun releaseFx(kind: Int) {
        when (kind) {
            0 -> { delayMix = 0.86f; reverbMix = 0.18f; AudioEngine.stopAllRmxSamples(); activePads = listOf(false, false, false, false) }
            1 -> { bitCrush = 0f; delayMix = 0f; reverbMix = 0f; AudioEngine.stopAllRmxSamples(); activePads = listOf(false, false, false, false) }
            2 -> { keyShift = MIN_KEY; AudioEngine.stopAllRmxSamples(); activePads = listOf(false, false, false, false) }
        }
    }

    @Composable
    fun PerformancePanel(modifier: Modifier = Modifier) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { setAuto(!autoBpm) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (autoBpm) AccentGreen else SurfaceDark),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("AUTO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (autoBpm) BackgroundDark else TextSecondary)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { adjustManualBpm(-1f) }, modifier = Modifier.size(36.dp)) {
                    Text("-", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Text("${bpm.toInt()}", fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = AccentGreen)
                TextButton(onClick = { adjustManualBpm(1f) }, modifier = Modifier.size(36.dp)) {
                    Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(Modifier.weight(1f))
                Text("BPM", fontSize = 12.sp, color = TextSecondary)
            }

            Text(
                text = if (autoBpm) "AUTO mic detect" else "MANUAL ${manualBpm.toInt()} BPM | $outputStatus",
                fontSize = 9.sp,
                color = TextSecondary.copy(alpha = 0.7f),
                maxLines = 1
            )

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sampleNames.forEachIndexed { idx, name ->
                    val active = activePads[idx]
                    Button(
                        onClick = { triggerPad(idx) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = ButtonDefaults.ContentPadding,
                        colors = ButtonDefaults.buttonColors(containerColor = if (active) sampleColors[idx] else SurfaceDark)
                    ) {
                        Text(
                            text = name,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            color = if (active) BackgroundDark else TextPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            XPad(
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 112.dp),
                active = activePads.any { it },
                selectedIndex = loopDivisionIndex,
                touchX = xpadTouchX,
                held = isXpadHeld,
                onStart = { width, x ->
                    isXpadHeld = true
                    setLoopFromX(x, width)
                    if (!activePads.any { it }) triggerPad(selectedPad)
                },
                onMove = { width, x -> setLoopFromX(x, width) },
                onEnd = { isXpadHeld = false }
            )

            Text(
                text = if (activePads.any { it }) "${sampleNames[selectedPad]} | LOOP ${divisionLabels[loopDivisionIndex]} | KEY ${signedKey(keyShift)}" else "Touch X-Pad to arm selected sample",
                fontSize = 10.sp,
                color = TextSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp)
            )

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactButton("STOP", AccentRed, Modifier.weight(1f)) {
                    AudioEngine.stopAllRmxSamples()
                    activePads = listOf(false, false, false, false)
                }
                CompactButton("ECHO OUT", SurfaceDark, Modifier.weight(1f)) { releaseFx(0) }
                CompactButton("BRAKE", SurfaceDark, Modifier.weight(1f)) { releaseFx(2) }
            }
        }
    }

    @Composable
    fun EffectsPanel(modifier: Modifier = Modifier) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("KEY", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { adjustKey(-1) }, modifier = Modifier.size(34.dp)) {
                    Text("<", fontSize = 18.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                }
                Text(
                    signedKey(keyShift),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = AccentGreen,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { adjustKey(1) }, modifier = Modifier.size(34.dp)) {
                    Text(">", fontSize = 18.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                }
                CompactButton("RESET", SurfaceDark, Modifier.width(74.dp)) {
                    keyShift = 0
                    activePads.forEachIndexed { idx, active -> if (active) AudioEngine.updateRmxVoicePitch(idx, 1.0f) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                sceneFxLabels.forEachIndexed { idx, label ->
                    CompactButton(
                        label,
                        if (sceneFx == idx) AccentGreen else SurfaceDark,
                        Modifier.weight(1f),
                        textColor = if (sceneFx == idx) BackgroundDark else TextSecondary
                    ) { applySceneFx(idx) }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ParamDial("FILTER", filterNorm(filterCutoff), filterDisplay(filterCutoff), MeterAmber, 58.dp) {
                    filterCutoff = normToFilter(it)
                }
                ParamDial("DELAY", delayMix, percentDisplay(delayMix), AccentGreen, 58.dp) { delayMix = it }
                ParamDial("REVERB", reverbMix, percentDisplay(reverbMix), Color(0xFF74A7FF), 58.dp) { reverbMix = it }
                ParamDial("CRUSH", bitCrush, percentDisplay(bitCrush), AccentRed, 58.dp) { bitCrush = it }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                filterLabels.forEachIndexed { idx, label ->
                    TextButton(onClick = { filterType = idx }, modifier = Modifier.height(32.dp)) {
                        Text(label, fontSize = 11.sp, color = if (filterType == idx) AccentGreen else TextSecondary, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactButton("CLEAN", SurfaceDark, Modifier.weight(1f)) { applySceneFx(0) }
                CompactButton("CUT", SurfaceDark, Modifier.weight(1f)) { releaseFx(1) }
                CompactButton("WET", SurfaceDark, Modifier.weight(1f)) { delayMix = 0.5f; reverbMix = 0.45f }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PerformancePanel(Modifier.weight(1.08f).fillMaxSize())
                EffectsPanel(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                PerformancePanel(Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                EffectsPanel(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun XPad(
    modifier: Modifier,
    active: Boolean,
    selectedIndex: Int,
    touchX: Float,
    held: Boolean,
    onStart: (width: Float, x: Float) -> Unit,
    onMove: (width: Float, x: Float) -> Unit,
    onEnd: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1D2E))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        onStart(size.width.toFloat().coerceAtLeast(1f), offset.x)
                        tryAwaitRelease()
                        onEnd()
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> onStart(size.width.toFloat().coerceAtLeast(1f), offset.x) },
                    onDragEnd = onEnd,
                    onDragCancel = onEnd,
                    onDrag = { change, _ ->
                        change.consume()
                        onMove(size.width.toFloat().coerceAtLeast(1f), change.position.x)
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            beatDivisions.indices.forEach { i ->
                val x = size.width * (i.toFloat() / (beatDivisions.size - 1))
                drawLine(Color(0xFF333355), Offset(x, 0f), Offset(x, size.height), 1f)
            }
            val selectedX = size.width * (selectedIndex.toFloat() / (beatDivisions.size - 1))
            drawLine(AccentGreen, Offset(selectedX, 0f), Offset(selectedX, size.height), 4f)
            if (held || active) {
                drawCircle(Color.White.copy(alpha = if (held) 0.36f else 0.18f), 14f, Offset(touchX.coerceIn(0f, size.width), size.height / 2f))
            }
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), Arrangement.SpaceBetween, Alignment.Bottom) {
            divisionLabels.forEach {
                Text(it, fontSize = 8.sp, color = TextSecondary.copy(alpha = 0.65f), modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun ParamDial(
    label: String,
    normalized: Float,
    display: String,
    accent: Color,
    knobSize: Dp,
    onValueChange: (Float) -> Unit
) {
    var startValue by remember { mutableFloatStateOf(0f) }
    var totalDragY by remember { mutableFloatStateOf(0f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(knobSize)
                .clip(CircleShape)
                .background(SurfaceDark)
                .pointerInput(normalized) {
                    detectDragGestures(
                        onDragStart = {
                            startValue = normalized
                            totalDragY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalDragY += dragAmount.y
                            onValueChange((startValue - totalDragY / 220f).coerceIn(0f, 1f))
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(
                    accent,
                    -150f,
                    normalized.coerceIn(0f, 1f) * 300f,
                    false,
                    style = Stroke(width = 4f),
                    size = Size(size.width - 10f, size.height - 10f),
                    topLeft = Offset(5f, 5f)
                )
            }
            Text(display, color = TextPrimary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(label, fontSize = 8.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
    }
}

@Composable
private fun CompactButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    textColor: Color = TextPrimary,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = ButtonDefaults.ContentPadding
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = textColor, textAlign = TextAlign.Center)
    }
}

private fun bpmToLoopLengthSamples(bpm: Float, divisionIndex: Int): Int {
    val effectiveBpm = if (bpm > 0f) bpm else DEFAULT_BPM
    val samplesPerBeat = 44100f / (effectiveBpm / 60f)
    val beats = beatDivisions[divisionIndex.coerceIn(beatDivisions.indices)]
    return (samplesPerBeat * beats).toInt().coerceAtLeast(128)
}

private fun divisionIndexForX(x: Float, width: Float): Int {
    if (width <= 0f) return 4
    val normalized = (x / width).coerceIn(0f, 1f)
    return (normalized * (beatDivisions.size - 1)).toInt().coerceIn(beatDivisions.indices)
}

private fun pitchRatioForKey(semitones: Int): Float = 2.0.pow(semitones.toDouble() / 12.0).toFloat()

private fun signedKey(value: Int): String = when {
    value > 0 -> "+$value"
    else -> value.toString()
}

private fun filterNorm(hz: Float): Float {
    val minLn = ln(MIN_FILTER_HZ)
    val maxLn = ln(MAX_FILTER_HZ)
    return ((ln(hz.coerceIn(MIN_FILTER_HZ, MAX_FILTER_HZ)) - minLn) / (maxLn - minLn)).coerceIn(0f, 1f)
}

private fun normToFilter(value: Float): Float {
    val minLn = ln(MIN_FILTER_HZ)
    val maxLn = ln(MAX_FILTER_HZ)
    return exp(minLn + value.coerceIn(0f, 1f) * (maxLn - minLn))
}

private fun filterDisplay(hz: Float): String = if (hz >= 1000f) "%.1fk".format(hz / 1000f) else "${hz.toInt()}"

private fun percentDisplay(value: Float): String = "${(value.coerceIn(0f, 1f) * 100f).toInt()}%"

private fun loadRmxSamples(context: Context) {
    val files = listOf("samples/kick.wav", "samples/snare.wav", "samples/hihat.wav", "samples/clap.wav")
    files.forEachIndexed { i, path ->
        try {
            context.assets.open(path).use { input ->
                val sample = com.audiopro.djmrec.audio.WavSampleLoader.loadFromMemory(input.readBytes())
                if (sample.valid) AudioEngine.loadRmxSample(i, sample.data)
            }
        } catch (e: Exception) {
            android.util.Log.e("RmxScreen", "Failed to load $path: ${e.message}")
        }
    }
}
