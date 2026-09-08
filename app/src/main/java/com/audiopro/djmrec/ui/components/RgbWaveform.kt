package com.audiopro.djmrec.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.ui.theme.*
import kotlin.math.sqrt

/** Continuous three-band envelope. Interpolation is visual only; captured samples are untouched. */
@Composable
fun RgbWaveform(bins: FloatArray, modifier: Modifier = Modifier, smooth: Boolean = true, active: Boolean = true) {
    var from by remember { mutableStateOf(FloatArray(2048)) }
    var target by remember { mutableStateOf(FloatArray(2048)) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(bins, smooth, active) {
        val fraction = progress.value
        from = FloatArray(2048) { index ->
            from.getOrElse(index) { 0f } * (1f - fraction) + target.getOrElse(index) { 0f } * fraction
        }
        target = bins
        progress.snapTo(if (smooth && active) 0f else 1f)
        if (smooth && active) progress.animateTo(1f, tween(45, easing = LinearEasing))
    }
    Canvas(modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(BackgroundDark)
        .semantics { contentDescription = "Live stereo waveform. Red lows, green mids, blue highs. New audio enters at right." }) {
        val center = size.height / 2f
        for (line in 1..5) {
            val x = size.width * line / 6f
            drawLine(TextSecondary.copy(alpha = 0.09f), Offset(x, 0f), Offset(x, size.height))
        }
        for (level in listOf(0.25f, 0.5f, 0.75f)) {
            val y = size.height * level
            drawLine(TextSecondary.copy(alpha = 0.09f), Offset(0f, y), Offset(size.width, y))
        }
        val fraction = progress.value
        fun value(index: Int): Float {
            val a = from.getOrElse(index) { 0f }.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            val b = target.getOrElse(index) { 0f }.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            return a + (b - a) * fraction
        }
        val amplitudes = FloatArray(256)
        val lows = FloatArray(256)
        val mids = FloatArray(256)
        for (column in 0 until 256) {
            val base = column * 8
            val peak = maxOf(value(base), value(base + 4))
            val low = value(base + 1) + value(base + 5)
            val mid = value(base + 2) + value(base + 6)
            val high = value(base + 3) + value(base + 7)
            val total = (low + mid + high).coerceAtLeast(0.000001f)
            amplitudes[column] = sqrt(peak) * center * 0.91f
            lows[column] = amplitudes[column] * sqrt(low / total)
            mids[column] = amplitudes[column] * sqrt((low + mid) / total)
            if (peak >= 0.999f) {
                val x = column * size.width / 255f
                drawLine(AccentRed, Offset(x, 2.dp.toPx()), Offset(x, 6.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
        }
        fun envelope(values: FloatArray): Path = Path().apply {
            moveTo(0f, center)
            for (i in values.indices) lineTo(i * size.width / 255f, center - values[i])
            for (i in values.indices.reversed()) lineTo(i * size.width / 255f, center + values[i])
            close()
        }
        val full = envelope(amplitudes)
        drawPath(full, WaveformHigh)
        drawPath(envelope(mids), WaveformMid.copy(alpha = 0.88f))
        drawPath(envelope(lows), WaveformLow.copy(alpha = 0.92f))
        drawPath(full, Color.White.copy(alpha = 0.4f), style = Stroke(0.7.dp.toPx()))
        drawLine(TextSecondary.copy(alpha = 0.3f), Offset(0f, center), Offset(size.width, center))
        drawLine(AccentGreen.copy(alpha = 0.8f), Offset(size.width - 1.dp.toPx(), 0f),
            Offset(size.width - 1.dp.toPx(), size.height), strokeWidth = 1.dp.toPx())
    }
}
