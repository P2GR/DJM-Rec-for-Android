package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.TextSecondary
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/** Collect here so waveform updates do not recompose the entire recorder workspace. */
@Composable
fun LiveRgbWaveform(source: StateFlow<FloatArray>, modifier: Modifier = Modifier,
                    smooth: Boolean = true, active: Boolean = true, onVisible: (Boolean) -> Unit = {}) {
    DisposableEffect(Unit) {
        onVisible(true)
        onDispose { onVisible(false) }
    }
    val bins by source.collectAsState()
    RgbWaveform(bins, modifier, smooth, active)
}

/** Fixed historical envelopes translate on the display frame clock, with additive RGB shading. */
@Composable
fun RgbWaveform(bins: FloatArray, modifier: Modifier = Modifier, smooth: Boolean = true, active: Boolean = true) {
    val timeline = remember { WaveformTimeline() }
    val heights = remember { FloatArray(512) }
    val colors = remember { IntArray(512) }
    val path = remember { Path() }
    val frame = remember { mutableLongStateOf(0L) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(bins) {
        timeline.accept(bins)
        for (i in 0 until 512) {
            val base = i * 4
            val peak = bins.getOrElse(base) { 0f }
            heights[i] = sqrt(if (peak.isFinite()) peak.coerceIn(0f, 1f) else 0f)
            colors[i] = waveformRgb(bins.getOrElse(base + 1) { 0f },
                bins.getOrElse(base + 2) { 0f }, bins.getOrElse(base + 3) { 0f })
        }
        if (!active || !smooth) frame.longValue = System.nanoTime()
    }
    LaunchedEffect(active, smooth, owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            val limiter = WaveformFrameLimiter()
            if (active && smooth) while (isActive) withFrameNanos {
                if (limiter.shouldRender(it)) frame.longValue = it
            }
        }
    }
    Canvas(modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(BackgroundDark)
        .semantics { contentDescription = "Live waveform. Red bass below 250 Hz, green mids to 2 kHz, blue highs. Mixed bands blend RGB. New audio enters at right." }) {
        val lag = timeline.lag(frame.longValue, smooth && active)
        val center = size.height / 2f
        for (line in 1..5) {
            val x = size.width * line / 6f
            drawLine(TextSecondary.copy(alpha = 0.09f), Offset(x, 0f), Offset(x, size.height))
        }
        for (line in 1..3) {
            val y = size.height * line / 4f
            drawLine(TextSecondary.copy(alpha = 0.09f), Offset(0f, y), Offset(size.width, y))
        }
        // Keep 24 bins outside the viewport for jitter recovery; never resample shifted peaks.
        val step = size.width / 487f
        if (timeline.bins.isNotEmpty()) clipRect {
            for (i in 0 until 511) {
                val x = (i - 24 + lag) * step
                if (x + step < 0 || x > size.width) continue
                val a = heights[i] * center * 0.91f
                val b = heights[i + 1] * center * 0.91f
                path.reset()
                path.moveTo(x, center - a)
                path.lineTo(x + step + 0.25f, center - b)
                path.lineTo(x + step + 0.25f, center + b)
                path.lineTo(x, center + a)
                path.close()
                drawPath(path, Color(colors[i]))
            }
        }
        drawLine(TextSecondary.copy(alpha = 0.3f), Offset(0f, center), Offset(size.width, center))
    }
}
