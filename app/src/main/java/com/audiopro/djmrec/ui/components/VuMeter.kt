package com.audiopro.djmrec.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
private const val METER_CEILING_DB = 3f
private const val CLIP_LATCH_MS = 1500L

private fun dbToFraction(db: Float): Float =
    ((db - METER_FLOOR_DB) / (METER_CEILING_DB - METER_FLOOR_DB)).coerceIn(0f, 1f)

private fun colorForFraction(fraction: Float): Color = when {
    fraction >= dbToFraction(0f)  -> MeterRed
    fraction >= dbToFraction(-6f) -> MeterAmber
    else                           -> MeterGreen
}

/**
 * Horizontal stereo VU meter. Two horizontal bars (L on top, R below) with clip indicators
 * and a compact dB scale row beneath. Designed for a CDJ-style stacked layout.
 */
@Composable
fun StereoVuMeter(levels: StereoLevels, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HorizontalChannelMeter(label = "L", level = levels.left)
        HorizontalChannelMeter(label = "R", level = levels.right)
        HorizontalDbScale()
    }
}

@Composable
private fun HorizontalChannelMeter(label: String, level: ChannelLevel) {
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
    val peakFraction = dbToFraction(level.peakDb)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Channel label
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(16.dp)
        )

        // Clip indicator dot
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .width(6.dp)
                .height(18.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = if (clipLatched) MeterRed else Color(0xFF222433),
                    cornerRadius = CornerRadius(3f, 3f)
                )
            }
        }

        // Horizontal meter bar
        Box(modifier = Modifier.weight(1f).height(18.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawHorizontalMeterTrack()
                drawHorizontalMeterFill(rmsFraction)
                drawHorizontalPeakLine(peakFraction)
            }
        }

        // Peak dB readout
        Text(
            text = "${level.peakDb.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp).padding(start = 6.dp)
        )
    }
}

@Composable
private fun HorizontalDbScale() {
    val marks = listOf(-60, -48, -36, -24, -12, -6, 0, 3)
    Layout(
        modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = 34.dp),
        content = {
            marks.forEach { db ->
                Text(
                    text = if (db > 0) "+$db" else "$db",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
            }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                val fraction = dbToFraction(marks[index].toFloat())
                val centeredX = (constraints.maxWidth * fraction - placeable.width / 2f).toInt()
                val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
                val x = centeredX.coerceIn(0, maxX)
                placeable.placeRelative(x, 0)
            }
        }
    }
}

// --- Horizontal meter drawing helpers ---

private fun DrawScope.drawHorizontalMeterTrack() {
    drawRoundRect(
        color = Color(0xFF11141D),
        cornerRadius = CornerRadius(6f, 6f)
    )
}

private fun DrawScope.drawHorizontalMeterFill(fraction: Float) {
    val fillWidth = size.width * fraction
    val segmentCount = 60
    val segmentWidth = size.width / segmentCount
    val gapRatio = 0.15f

    for (i in 0 until segmentCount) {
        val segLeft = i * segmentWidth
        if (segLeft >= fillWidth) break
        val segFraction = segLeft / size.width
        val color = colorForFraction(segFraction)
        drawRect(
            color = color,
            topLeft = Offset(segLeft + segmentWidth * gapRatio / 2f, 0f),
            size = Size(segmentWidth * (1f - gapRatio), size.height)
        )
    }
}

private fun DrawScope.drawHorizontalPeakLine(fraction: Float) {
    val x = (size.width * fraction).coerceIn(0f, size.width - 2f)
    drawRect(
        color = Color.White,
        topLeft = Offset(x, 0f),
        size = Size(3f, size.height)
    )
}
