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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

private const val BIN_COUNT = 512
private const val FLOATS_PER_BIN = 4
private const val DISPLAY_COLUMNS = 128

/** Bright CDJ-style waveform: warm lows, green mids, blue highs, and a white transient core. */
@Composable
fun RgbWaveform(bins: FloatArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        val centerY = size.height / 2f
        drawLine(
            color = Color.White.copy(alpha = 0.24f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.dp.toPx()
        )

        if (bins.size < BIN_COUNT * FLOATS_PER_BIN) return@Canvas

        val columnWidth = size.width / DISPLAY_COLUMNS
        var smoothedExtent = 0f
        var smoothedRed = 0f
        var smoothedGreen = 0f
        var smoothedBlue = 0f

        for (column in 0 until DISPLAY_COLUMNS) {
            val startBin = column * BIN_COUNT / DISPLAY_COLUMNS
            val endBin = (column + 1) * BIN_COUNT / DISPLAY_COLUMNS
            var peak = 0f
            var low = 0f
            var mid = 0f
            var high = 0f
            for (bin in startBin until endBin) {
                val base = bin * FLOATS_PER_BIN
                peak = maxOf(peak, bins[base].coerceIn(0f, 1f))
                low += bins[base + 1].coerceAtLeast(0f)
                mid += bins[base + 2].coerceAtLeast(0f)
                high += bins[base + 3].coerceAtLeast(0f)
            }

            val energy = (low + mid + high).coerceAtLeast(0.000001f)
            val lowShare = low / energy
            val midShare = mid / energy
            val highShare = high / energy
            val rawRed = lowShare * 1.55f + midShare * 0.35f
            val rawGreen = lowShare * 0.65f + midShare * 1.35f
            val rawBlue = midShare * 0.15f + highShare * 1.85f
            val channelMax = maxOf(rawRed, rawGreen, rawBlue, 0.000001f)
            val brightness = (0.76f + sqrt(peak) * 0.24f).coerceIn(0f, 1f)
            val red = (rawRed / channelMax * brightness).coerceIn(0f, 1f)
            val green = (rawGreen / channelMax * brightness).coerceIn(0f, 1f)
            val blue = (rawBlue / channelMax * brightness).coerceIn(0f, 1f)
            val extent = size.height * 0.46f * sqrt(peak)

            // A small one-pole spatial smoother removes isolated USB-bin spikes without lag.
            smoothedExtent += (extent - smoothedExtent) * 0.58f
            smoothedRed += (red - smoothedRed) * 0.58f
            smoothedGreen += (green - smoothedGreen) * 0.58f
            smoothedBlue += (blue - smoothedBlue) * 0.58f

            val x = (column + 0.5f) * columnWidth
            drawLine(
                color = Color(smoothedRed, smoothedGreen, smoothedBlue, alpha = 1f),
                start = Offset(x, centerY - smoothedExtent),
                end = Offset(x, centerY + smoothedExtent),
                strokeWidth = maxOf(1.5.dp.toPx(), columnWidth * 0.82f),
                cap = StrokeCap.Round
            )

            val coreExtent = smoothedExtent * (0.14f + sqrt(peak) * 0.24f)
            drawLine(
                color = Color(0xFFFFF7E6),
                start = Offset(x, centerY - coreExtent),
                end = Offset(x, centerY + coreExtent),
                strokeWidth = maxOf(1.25.dp.toPx(), columnWidth * 0.58f),
                cap = StrokeCap.Round
            )
        }
    }
}
