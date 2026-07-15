package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.ui.theme.WaveformHigh
import com.audiopro.djmrec.ui.theme.WaveformLow
import com.audiopro.djmrec.ui.theme.WaveformMid
import kotlin.math.sqrt

private const val BIN_COUNT = 512
private const val FLOATS_PER_BIN = 4
private const val DISPLAY_POINTS = 256

/** CDJ-style rolling RGB waveform with all three frequency envelopes overlaid. */
@Composable
fun RgbWaveform(bins: FloatArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF090B11), Color(0xFF111620), Color(0xFF090B11))
                )
            )
    ) {
        drawWaveformGrid()

        val center = size.height / 2f
        val colors = arrayOf(WaveformLow, WaveformMid, WaveformHigh)

        drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = Offset(0f, center),
            end = Offset(size.width, center),
            strokeWidth = 1.dp.toPx()
        )

        if (bins.size < BIN_COUNT * FLOATS_PER_BIN) return@Canvas

        drawFrequencyLane(bins, bandOffset = 1, centerY = center, color = colors[0], laneHeight = size.height)
        drawFrequencyLane(bins, bandOffset = 2, centerY = center, color = colors[1], laneHeight = size.height)
        drawFrequencyLane(bins, bandOffset = 3, centerY = center, color = colors[2], laneHeight = size.height)
    }
}

private fun DrawScope.drawWaveformGrid() {
    repeat(9) { index ->
        val x = size.width * index / 8f
        drawLine(
            color = Color.White.copy(alpha = if (index == 4) 0.10f else 0.045f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f
        )
    }
}

private fun DrawScope.drawFrequencyLane(
    bins: FloatArray,
    bandOffset: Int,
    centerY: Float,
    color: Color,
    laneHeight: Float
) {
    var maxEnergy = 0.000001f
    for (index in 0 until BIN_COUNT) {
        maxEnergy = maxOf(maxEnergy, bins[index * FLOATS_PER_BIN + bandOffset])
    }

    val extents = FloatArray(DISPLAY_POINTS)
    val radius = 3
    for (point in 0 until DISPLAY_POINTS) {
        val source = point * (BIN_COUNT - 1) / (DISPLAY_POINTS - 1)
        var bandSum = 0f
        var ampSum = 0f
        var samples = 0
        for (offset in -radius..radius) {
            val bin = (source + offset).coerceIn(0, BIN_COUNT - 1)
            val base = bin * FLOATS_PER_BIN
            bandSum += bins[base + bandOffset].coerceAtLeast(0f)
            ampSum += bins[base].coerceIn(0f, 1f)
            samples++
        }
        val band = bandSum / samples
        val amplitudeGate = (sqrt(ampSum / samples) * 3.2f).coerceIn(0f, 1f)
        val normalized = sqrt((band / maxEnergy).coerceIn(0f, 1f))
        extents[point] = laneHeight * 0.46f * normalized * amplitudeGate
    }

    val path = Path()
    for (point in 0 until DISPLAY_POINTS) {
        val x = size.width * point / (DISPLAY_POINTS - 1f)
        val y = centerY - extents[point]
        if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (point in DISPLAY_POINTS - 1 downTo 0) {
        val x = size.width * point / (DISPLAY_POINTS - 1f)
        path.lineTo(x, centerY + extents[point])
    }
    path.close()

    drawPath(path, color.copy(alpha = 0.18f), style = Stroke(width = 8.dp.toPx()))
    drawPath(
        path,
        brush = Brush.horizontalGradient(
            listOf(color.copy(alpha = 0.24f), color.copy(alpha = 0.72f), color.copy(alpha = 0.38f))
        )
    )
    drawPath(path, color.copy(alpha = 0.95f), style = Stroke(width = 1.2.dp.toPx()))
}
