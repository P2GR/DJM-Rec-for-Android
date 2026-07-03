package com.audiopro.djmrec.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
private const val RMX_ACTIVE_REFRESH_MS = 33L
private const val RMX_IDLE_REFRESH_MS = 250L
private const val FX_ISOLATOR_LOW = 8
private const val FX_ISOLATOR_MID = 9
private const val FX_ISOLATOR_HIGH = 10

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
    var sourceSamples by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
    var stopping by remember { mutableStateOf(false) }
    var isoLow by remember { mutableFloatStateOf(0.5f) }
    var isoMid by remember { mutableFloatStateOf(0.5f) }
    var isoHigh by remember { mutableFloatStateOf(0.5f) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioMgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val spkOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val outDeviceId = spkOut?.id ?: -1
        val result = AudioEngine.openRmxOutput(outDeviceId, 44100, 2)
        outputStatus = if (result > 0) "Speaker ready" else "Audio unavailable"
        if (!samplesLoaded) {
            sourceSamples = loadRmxSamples(context)
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
            val result = if (autoBpm) AudioEngine.getBpmResult() else FloatArray(0)
            val detectedBpm = if (autoBpm && result.size >= 5 && result[4] > 0.5f && result[0] > 0f) result[0] else 0f
            val detectedPhase = if (autoBpm && result.size >= 5) result[2] else 0f
            val effectiveBpm = if (autoBpm && detectedBpm > 0f) detectedBpm else manualBpm
            bpm = effectiveBpm
            AudioEngine.updateRmxBeatClock(effectiveBpm, if (autoBpm) detectedPhase else 0f, autoBpm)
            activePads.forEachIndexed { i, active ->
                if (active) {
                    AudioEngine.updateRmxVoiceLoop(i, bpmToLoopLengthSamples(effectiveBpm, loopDivisionIndex))
                }
            }
            AudioEngine.setRmxEffectParam(0, bitCrush)
            AudioEngine.setRmxEffectParam(1, filterCutoff)
            AudioEngine.setRmxEffectParam(2, filterType.toFloat())
            AudioEngine.setRmxEffectParam(3, delayMix)
            AudioEngine.setRmxEffectParam(4, bpmToLoopLengthSamples(effectiveBpm, loopDivisionIndex).toFloat())
            AudioEngine.setRmxEffectParam(5, 0.45f)
            AudioEngine.setRmxEffectParam(7, reverbMix)
            AudioEngine.setRmxEffectParam(FX_ISOLATOR_LOW, isoLow)
            AudioEngine.setRmxEffectParam(FX_ISOLATOR_MID, isoMid)
            AudioEngine.setRmxEffectParam(FX_ISOLATOR_HIGH, isoHigh)
            val activeAudio = activePads.any { it } || autoBpm || delayMix > 0f || reverbMix > 0f || bitCrush > 0f || filterCutoff < MAX_FILTER_HZ
            delay(if (activeAudio) RMX_ACTIVE_REFRESH_MS else RMX_IDLE_REFRESH_MS)
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
                1.0f,
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
        val nextKey = (keyShift + delta).coerceIn(MIN_KEY, MAX_KEY)
        if (nextKey == keyShift) return
        keyShift = nextKey
        scope.launch(Dispatchers.Default) {
            loadShiftedSamples(sourceSamples, nextKey)
            withContext(Dispatchers.Main) {
                activePads.forEachIndexed { idx, active ->
                    if (active) AudioEngine.updateRmxVoiceLoop(idx, bpmToLoopLengthSamples(bpm, loopDivisionIndex))
                }
            }
        }
    }

    fun applySceneFx(index: Int) {
        sceneFx = index
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
                    if (stopping) return@CompactButton
                    stopping = true
                    val hadWet = delayMix > 0.01f || reverbMix > 0.01f
                    if (!hadWet) { delayMix = 0.35f; reverbMix = 0.22f }
                    activePads = listOf(false, false, false, false)
                    scope.launch {
                        delay(1200L)
                        if (!hadWet) { delayMix = 0f; reverbMix = 0f }
                        AudioEngine.stopAllRmxSamples()
                        stopping = false
                    }
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
                    scope.launch(Dispatchers.Default) {
                        loadShiftedSamples(sourceSamples, 0)
                        withContext(Dispatchers.Main) {
                            activePads.forEachIndexed { idx, active ->
                                if (active) AudioEngine.updateRmxVoiceLoop(idx, bpmToLoopLengthSamples(bpm, loopDivisionIndex))
                            }
                        }
                    }
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
            Text("ISOLATOR", fontSize = 10.sp, color = TextSecondary.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ParamDial("LOW", isoLow, isoDisplay(isoLow), Color(0xFFE53935), 65.dp) { isoLow = it }
                ParamDial("MID", isoMid, isoDisplay(isoMid), Color(0xFFFB8C00), 65.dp) { isoMid = it }
                ParamDial("HIGH", isoHigh, isoDisplay(isoHigh), Color(0xFF43A047), 65.dp) { isoHigh = it }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ParamDial("FILTER", filterNorm(filterCutoff), filterDisplay(filterCutoff), MeterAmber, 80.dp) {
                    filterCutoff = normToFilter(it)
                }
                ParamDial("DELAY", delayMix, percentDisplay(delayMix), AccentGreen, 80.dp) { delayMix = it }
                ParamDial("REVERB", reverbMix, percentDisplay(reverbMix), Color(0xFF74A7FF), 80.dp) { reverbMix = it }
                ParamDial("CRUSH", bitCrush, percentDisplay(bitCrush), AccentRed, 80.dp) { bitCrush = it }
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
    val dragState = remember { DragState() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(knobSize + 18.dp)
                .clip(CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId: PointerId = down.id
                        var lastY = down.position.y
                        dragState.startValue = normalized
                        dragState.totalDragY = 0f
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            val dy = change.position.y - lastY
                            lastY = change.position.y
                            dragState.totalDragY += dy
                            val newVal = (dragState.startValue - dragState.totalDragY / 280f).coerceIn(0f, 1f)
                            onValueChange(newVal)
                            change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(knobSize)
                    .clip(CircleShape)
                    .background(SurfaceDark),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val knobCx = size.width / 2f
                    val knobCy = size.height / 2f
                    val knobR = size.minDimension / 2f - 12f
                    val valueAngle = Math.toRadians((-150.0 + normalized.coerceIn(0f, 1f) * 300.0))
                    val lineEnd = Offset(
                        knobCx + cos(valueAngle).toFloat() * knobR,
                        knobCy + sin(valueAngle).toFloat() * knobR
                    )
                    drawLine(
                        color = accent,
                        start = Offset(knobCx, knobCy),
                        end = lineEnd,
                        strokeWidth = 3.5f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    drawCircle(
                        color = accent,
                        radius = 5f,
                        center = Offset(knobCx, knobCy)
                    )
                    drawArc(
                        accent,
                        -150f,
                        normalized.coerceIn(0f, 1f) * 300f,
                        false,
                        style = Stroke(width = 5f),
                        size = Size(size.width - 12f, size.height - 12f),
                        topLeft = Offset(6f, 6f)
                    )
                }
                Text(display, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f - 7f
                for (tick in 0..10) {
                    val angle = Math.toRadians((-150.0 + tick * 30.0))
                    val outer = Offset(
                        center.x + cos(angle).toFloat() * radius,
                        center.y + sin(angle).toFloat() * radius
                    )
                    val inner = Offset(
                        center.x + cos(angle).toFloat() * (radius - if (tick % 5 == 0) 8f else 5f),
                        center.y + sin(angle).toFloat() * (radius - if (tick % 5 == 0) 8f else 5f)
                    )
                    drawLine(
                        color = TextSecondary.copy(alpha = if (tick % 5 == 0) 0.5f else 0.32f),
                        start = inner,
                        end = outer,
                        strokeWidth = if (tick % 5 == 0) 2f else 1.4f
                    )
                }
                drawArc(
                    accent.copy(alpha = 0.18f),
                    -150f,
                    300f,
                    false,
                    style = Stroke(width = 2f),
                    size = Size(size.width - 8f, size.height - 8f),
                    topLeft = Offset(4f, 4f)
                )
            }
        }
        Text(label, fontSize = 10.sp, color = TextSecondary, modifier = Modifier.padding(top = 1.dp), maxLines = 1)
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

private fun loadShiftedSamples(sourceSamples: List<FloatArray>, semitones: Int) {
    if (sourceSamples.isEmpty()) return
    val ratio = pitchRatioForKey(semitones)
    sourceSamples.forEachIndexed { index, source ->
        val shifted = if (semitones == 0) source else pitchShiftTempoLocked(source, ratio)
        AudioEngine.loadRmxSample(index, shifted)
    }
}

private fun pitchShiftTempoLocked(source: FloatArray, ratio: Float): FloatArray {
    if (source.isEmpty()) return FloatArray(0)
    val pitchedLength = (source.size / ratio).toInt().coerceAtLeast(128)
    val pitched = FloatArray(pitchedLength)
    for (i in pitched.indices) pitched[i] = sampleLinear(source, i * ratio)
    return timeStretchOla(pitched, source.size)
}

private fun sampleLinear(source: FloatArray, position: Float): Float {
    if (source.isEmpty()) return 0f
    val wrapped = ((position % source.size) + source.size) % source.size
    val base = floor(wrapped).toInt().coerceIn(0, source.lastIndex)
    val next = if (base == source.lastIndex) 0 else base + 1
    val frac = wrapped - base.toFloat()
    return source[base] + (source[next] - source[base]) * frac
}

private fun timeStretchOla(source: FloatArray, targetLength: Int): FloatArray {
    val out = FloatArray(targetLength)
    val windowSize = 2048.coerceAtMost(source.size).coerceAtLeast(128)
    val hopOut = (windowSize / 4).coerceAtLeast(32)
    val hopIn = (hopOut * source.size.toFloat() / targetLength.toFloat()).coerceAtLeast(1f)
    val gain = FloatArray(targetLength)
    var outPos = 0
    var inPos = 0f
    while (outPos < targetLength) {
        for (i in 0 until windowSize) {
            val dst = outPos + i
            if (dst >= targetLength) break
            val window = 0.5f - 0.5f * cos((2.0 * PI * i) / (windowSize - 1)).toFloat()
            out[dst] += sampleLinear(source, inPos + i) * window
            gain[dst] += window
        }
        outPos += hopOut
        inPos += hopIn
    }
    for (i in out.indices) {
        if (gain[i] > 0.0001f) out[i] /= gain[i]
    }
    return out
}

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

private fun isoDisplay(norm: Float): String {
    if (norm <= 0.001f) return "KILL"
    if (norm <= 0.5f) {
        val db = (-60f + (norm / 0.5f) * 60f).toInt()
        return "${db}dB"
    }
    val db = ((norm - 0.5f) / 0.5f * 6f)
    return "+%.1fdB".format(db)
}

private class DragState {
    var startValue: Float = 0f
    var totalDragY: Float = 0f
}

private fun loadRmxSamples(context: Context): List<FloatArray> {
    val files = listOf("samples/kick.wav", "samples/snare.wav", "samples/hihat.wav", "samples/clap.wav")
    val loaded = mutableListOf<FloatArray>()
    files.forEachIndexed { i, path ->
        try {
            context.assets.open(path).use { input ->
                val sample = com.audiopro.djmrec.audio.WavSampleLoader.loadFromMemory(input.readBytes())
                if (sample.valid) {
                    loaded += sample.data
                    AudioEngine.loadRmxSample(i, sample.data)
                } else {
                    loaded += FloatArray(0)
                }
            }
        } catch (e: Exception) {
            loaded += FloatArray(0)
            android.util.Log.e("RmxScreen", "Failed to load $path: ${e.message}")
        }
    }
    return loaded
}
