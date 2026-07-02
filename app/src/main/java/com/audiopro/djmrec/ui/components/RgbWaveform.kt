package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.ui.theme.BackgroundDark
import kotlin.math.abs
import kotlin.math.sqrt

private const val BIN_COUNT = 512
private const val FLOATS_PER_BIN = 4 // amp, low, mid, high

/**
 * CDJ-3000-style RGB waveform display.
 *
 * Renders a scrolling, frequency-colored waveform where each vertical slice's color is
 * determined by the relative energy in three bands:
 * - Red   = low frequencies  (20–250 Hz)
 * - Green = mid frequencies  (250–2000 Hz)
 * - Blue  = high frequencies (2000–20000 Hz)
 *
 * The waveform scrolls right-to-left as new bins arrive, exactly like a CDJ's deck display.
 * When [bins] is empty (idle state), a flat centre line is drawn so the component never
 * collapses to zero height.
 *
 * @param bins Raw float array from [com.audiopro.djmrec.audio.AudioEngine.getWaveformBins];
 *   layout is [amp0, low0, mid0, high0, amp1, low1, mid1, high1, ...].
 *   May be empty (FloatArray(0)) when no session is active.
 * @param modifier Standard Compose modifier.
 */
@Composable
fun RgbWaveform(bins: FloatArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(BackgroundDark)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val halfHeight = canvasHeight / 2.0f
        val binWidth = canvasWidth / BIN_COUNT

        if (bins.size < BIN_COUNT * FLOATS_PER_BIN) {
            // Idle / empty: draw a dim centre line.
            drawLine(
                color = Color.White.copy(alpha = 0.15f),
                start = Offset(0f, halfHeight),
                end = Offset(canvasWidth, halfHeight),
                strokeWidth = 1.5f
            )
            return@Canvas
        }

        // Draw each bin as a vertical slice coloured by band energy.
        for (i in 0 until BIN_COUNT) {
            val base = i * FLOATS_PER_BIN
            val amp   = bins[base + 0].coerceIn(0f, 1f)
            val low   = bins[base + 1].coerceIn(0f, 1f)
            val mid   = bins[base + 2].coerceIn(0f, 1f)
            val high  = bins[base + 3].coerceIn(0f, 1f)

            // Amplitude with a slight gamma curve to make quiet parts visible.
            val gain = sqrt(amp)

            // Colour: blend red (low), green (mid), blue (high), scaled by amplitude gain.
            val sliceColor = Color(
                red   = low  * gain,
                green = mid  * gain,
                blue  = high * gain,
                alpha = 0.92f
            )

            // Vertical extent: amplitude-gated so silence is a flat line.
            val halfExtent = (halfHeight * gain * 0.85f).coerceAtLeast(0.5f)
            val x = i * binWidth

            drawLine(
                color = sliceColor,
                start = Offset(x, halfHeight - halfExtent),
                end   = Offset(x, halfHeight + halfExtent),
                strokeWidth = binWidth.coerceAtLeast(1f)
            )
        }
    }
}
