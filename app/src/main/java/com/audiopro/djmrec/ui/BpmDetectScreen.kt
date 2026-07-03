package com.audiopro.djmrec.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.ui.theme.AccentRed
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.MeterAmber
import com.audiopro.djmrec.ui.theme.TextPrimary
import com.audiopro.djmrec.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun BpmDetectScreen() {
    var bpm by remember { mutableStateOf(0f) }
    var confidence by remember { mutableStateOf(0f) }
    var beatPhase by remember { mutableStateOf(0f) }
    var leadingBand by remember { mutableStateOf(0) }
    var locked by remember { mutableStateOf(false) }

    var tapTimes by remember { mutableStateOf(listOf<Long>()) }
    var manualBpm by remember { mutableStateOf<Float?>(null) }

    // Start mic capture on enter, stop on leave.
    DisposableEffect(Unit) {
        AudioEngine.startMicCapture()
        onDispose { AudioEngine.stopMicCapture() }
    }

    // Poll native BPM engine at ~30 Hz.
    LaunchedEffect(Unit) {
        while (true) {
            val result = AudioEngine.getBpmResult()
            if (result.size >= 5) {
                bpm = result[0]
                confidence = result[1]
                beatPhase = result[2]
                leadingBand = result[3].toInt()
                locked = result[4] > 0.5f
            }
            delay(33L)
        }
    }

    val displayBpm = manualBpm ?: bpm
    val pulseScale by animateFloatAsState(
        targetValue = if (beatPhase < 0.12f) 1.10f else 1.0f,
        animationSpec = tween(durationMillis = 80),
        label = "pulse"
    )

    val isConfident = confidence >= 0.4f || manualBpm != null
    val accentColor = when {
        manualBpm != null -> MeterAmber
        confidence >= 0.7f -> AccentGreen
        confidence >= 0.4f -> MeterAmber
        else -> AccentRed
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // --- Pulsing BPM circle ---
        Box(modifier = Modifier.size(220.dp * pulseScale), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val sw = 8f
                val sweep = 360f * beatPhase.coerceIn(0f, 1f)
                drawArc(
                    color = Color(0xFF1A1D3E),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                    size = Size(size.width - sw, size.height - sw),
                    topLeft = Offset(sw / 2f, sw / 2f)
                )
                if (isConfident) {
                    drawArc(
                        color = accentColor,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = sw, cap = StrokeCap.Round),
                        size = Size(size.width - sw, size.height - sw),
                        topLeft = Offset(sw / 2f, sw / 2f)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (displayBpm > 0f) "${displayBpm.toInt()}" else "--",
                    fontSize = 64.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isConfident) TextPrimary else TextSecondary
                )
                Text("BPM", fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }

        Spacer(Modifier.height(28.dp))

        // --- Band activity dots ---
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BandDot("L", leadingBand == 0, AccentRed, isConfident)
            BandDot("M", leadingBand == 1, MeterAmber, isConfident)
            BandDot("H", leadingBand == 2, AccentGreen, isConfident)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                manualBpm != null -> "TAP  — manual BPM set"
                locked -> "LOCKED  — auto-detected"
                confidence > 0.3f -> "Listening..."
                else -> "Waiting for audio..."
            },
            fontSize = 12.sp, color = TextSecondary
        )

        Spacer(Modifier.height(32.dp))

        // --- Tap Tempo ---
        Button(
            onClick = {
                val now = System.currentTimeMillis()
                tapTimes = (tapTimes + now).takeLast(8)
                if (tapTimes.size >= 4) {
                    val intervals = tapTimes.zipWithNext { a, b -> b - a }
                    val avgMs = intervals.average().toFloat()
                    if (avgMs > 0f) manualBpm = 60000f / avgMs
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            modifier = Modifier.fillMaxWidth(0.7f).height(48.dp),
            shape = CircleShape
        ) {
            Text(
                text = if (manualBpm != null) "TAP TO RESET" else "TAP TEMPO",
                color = BackgroundDark, fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
        }

        if (manualBpm != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { manualBpm = null },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = TextSecondary),
                modifier = Modifier.height(36.dp)
            ) {
                Text("Clear manual BPM", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BandDot(label: String, active: Boolean, color: Color, isDetecting: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(if (active && isDetecting) 18.dp else 14.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(if (active && isDetecting) color else Color(0xFF2A2D3E), radius = size.minDimension / 2f)
                if (active && isDetecting) drawCircle(color.copy(alpha = 0.35f), radius = size.minDimension / 2f + 3f)
            }
        }
        Text(label, fontSize = 10.sp, color = if (active && isDetecting) color else TextSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}
