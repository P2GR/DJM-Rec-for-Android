package com.audiopro.djmrec.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.ui.theme.MeterAmber
import com.audiopro.djmrec.ui.theme.MeterGreen
import com.audiopro.djmrec.ui.theme.MeterRed
import kotlinx.coroutines.delay

private const val METER_FLOOR_DB = -60f
private const val METER_CEILING_DB = 0f
private const val CLIP_LATCH_MS = 1500L

/** Converts a dBFS value in [-60, 0] to a fill fraction in [0, 1]. */
private fun dbToFraction(db: Float): Float =
    ((db - METER_FLOOR_DB) / (METER_CEILING_DB - METER_FLOOR_DB)).coerceIn(0f, 1f)

private fun colorForFraction(fraction: Float): Color = when {
    fraction >= dbToFraction(-3f) -> MeterRed
    fraction >= dbToFraction(-12f) -> MeterAmber
    else -> MeterGreen
}

/**
 * Full stereo VU meter: two vertical bars (RMS fill + peak hold line) with a dB scale and a
 * latched clip indicator per channel. Designed to be driven by a value polled at ~15-60 Hz.
 */
@Composable
fun StereoVuMeter(levels: StereoLevels, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(220.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        DbScale(modifier = Modifier.fillMaxHeight())
        ChannelMeter(label = "L", level = levels.left, modifier = Modifier.weight(1f).fillMaxHeight())
        ChannelMeter(label = "R", level = levels.right, modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun DbScale(modifier: Modifier = Modifier) {
    val marks = listOf(0, -6, -12, -24, -48, -60)
    Column(
        modifier = modifier.width(28.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        marks.forEach { db ->
            Text(
                text = "$db",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChannelMeter(label: String, level: ChannelLevel, modifier: Modifier = Modifier) {
    var clipLatched by remember { mutableStateOf(false) }

    LaunchedEffect(level.isClipping) {
        if (level.isClipping) {
            clipLatched = true
            delay(CLIP_LATCH_MS)
            clipLatched = false
        }
    }

    val rmsFraction by animateFloatAsState(
        targetValue = dbToFraction(level.rmsDb),
        animationSpec = tween(durationMillis = 80),
        label = "rmsFraction"
    )
    val peakFraction = dbToFraction(level.peakDb) // no smoothing: peak hold must be instantaneous

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        ClipIndicator(active = clipLatched)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                drawMeterTrack()
                drawMeterFill(rmsFraction)
                drawPeakLine(peakFraction)
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun ClipIndicator(active: Boolean) {
    val color = if (active) MeterRed else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .width(28.dp)
            .height(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            drawRoundRect(color = color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
        }
    }
}

private fun DrawScope.drawMeterTrack() {
    drawRoundRect(
        color = Color(0xFF11141D),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
    )
}

private fun DrawScope.drawMeterFill(fraction: Float) {
    val fillHeight = size.height * fraction
    val top = size.height - fillHeight

    // Segmented look: draw thin horizontal "LED" slices instead of one solid gradient block,
    // each colored according to its own position on the dB scale.
    val segmentCount = 40
    val segmentHeight = size.height / segmentCount
    val gapRatio = 0.18f

    for (i in 0 until segmentCount) {
        val segmentTopY = size.height - (i + 1) * segmentHeight
        if (segmentTopY < top) continue
        val segmentFraction = 1f - (segmentTopY / size.height)
        val color = colorForFraction(segmentFraction)
        drawRect(
            color = color,
            topLeft = Offset(0f, segmentTopY + segmentHeight * gapRatio / 2f),
            size = androidx.compose.ui.geometry.Size(size.width, segmentHeight * (1f - gapRatio))
        )
    }
}

private fun DrawScope.drawPeakLine(fraction: Float) {
    val y = size.height * (1f - fraction)
    drawRect(
        color = Color.White,
        topLeft = Offset(0f, (y - 1.5f).coerceIn(0f, size.height - 3f)),
        size = androidx.compose.ui.geometry.Size(size.width, 3f)
    )
}
