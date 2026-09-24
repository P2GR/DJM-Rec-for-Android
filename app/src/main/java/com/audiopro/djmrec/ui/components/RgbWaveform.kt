package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.TextSecondary
import com.audiopro.djmrec.ui.theme.WaveformCdjHi
import com.audiopro.djmrec.ui.theme.WaveformCdjLow
import com.audiopro.djmrec.ui.theme.WaveformCdjMid
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

/** Fixed historical envelopes translate on the display frame clock, as CDJ-style layered bands. */
@Composable
fun RgbWaveform(bins: FloatArray, modifier: Modifier = Modifier, smooth: Boolean = true, active: Boolean = true) {
    val timeline = remember { WaveformTimeline() }
    val bandHeights = remember { Array(3) { FloatArray(512) } }
    val cache = remember { LayeredPathCache() }
    val frame = remember { mutableLongStateOf(0L) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(bins) {
        timeline.accept(bins)
        for (i in 0 until 512) {
            val base = i * 4
            for (band in 0..2) {
                val value = bins.getOrElse(base + 1 + band) { 0f }
                bandHeights[band][i] =
                    sqrt(if (value.isFinite()) value.coerceIn(0f, 1f) else 0f)
            }
        }
        cache.invalidate()
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
        .semantics { contentDescription = "Live waveform in CDJ 3-band style. Blue = low, amber = mid, white = high, layered. New audio enters at right." }) {
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
        cache.ensureBuilt(bandHeights, size.width, center)
        if (timeline.bins.isNotEmpty()) clipRect {
            // Pioneer 3Band layout: the low band draws the full body, mids layer over it and
            // highs cap the top -- three stacked envelopes (blue/amber/white). The paths are
            // rebuilt only when analyzer data (or width) changes; scrolling between updates is
            // pure translation, so 60 fps stays smooth without rebuilding every frame.
            translate(left = (lag - 24f) * cache.step) {
                drawPath(cache.paths[0], WaveformCdjLow)
                drawPath(cache.paths[1], WaveformCdjMid)
                drawPath(cache.paths[2], WaveformCdjHi)
            }
        }
    }
}

/**
 * Holds the three band envelopes in bin space (x = binIndex * step). Rebuilt on data
 * revisions and resizes only -- per display frame the cached paths are just translated.
 */
private class LayeredPathCache {
    val paths = Array(3) { Path() }
    var step = 0f
        private set
    private var built = false
    private var builtWidth = -1f

    fun invalidate() {
        built = false
    }

    fun ensureBuilt(bands: Array<FloatArray>, width: Float, center: Float) {
        if (built && width == builtWidth) return
        step = width / 487f
        for (band in 0..2) {
            val values = bands[band]
            val path = paths[band]
            path.reset()
            // 511 ramp segments; keep 24 bins of history so translation never resamples peaks.
            for (i in 0 until 511) {
                val x = i * step
                val a = values[i] * center * 0.91f
                val b = values[i + 1] * center * 0.91f
                path.moveTo(x, center - a)
                path.lineTo(x + step + 0.25f, center - b)
                path.lineTo(x + step + 0.25f, center + b)
                path.lineTo(x, center + a)
                path.close()
            }
        }
        builtWidth = width
        built = true
    }
}
