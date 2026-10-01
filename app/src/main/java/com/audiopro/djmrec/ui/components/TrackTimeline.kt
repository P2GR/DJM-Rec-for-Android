package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.audio.TrackWaveformHistory
import com.audiopro.djmrec.ui.theme.AccentRed
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.MeterAmber
import com.audiopro.djmrec.ui.theme.MeterGreen
import com.audiopro.djmrec.ui.theme.MeterRed
import com.audiopro.djmrec.ui.theme.SurfaceVariantDark
import com.audiopro.djmrec.ui.theme.TextSecondary
import com.audiopro.djmrec.ui.theme.WaveformCdjHi
import com.audiopro.djmrec.ui.theme.WaveformCdjLow
import com.audiopro.djmrec.ui.theme.WaveformCdjMid
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * One track lane of the multitrack timeline: the 3-band CDJ waveform (blue low body, amber
 * mids, white highs) scrolling right to left, newest audio at the playhead on the right edge.
 * Only the draw phase reads [revision], so new data redraws the lane without recomposing.
 */
@Composable
fun TimelineWaveform(
    history: TrackWaveformHistory,
    revision: State<Long>,
    dimmed: Boolean,
    recording: Boolean,
    modifier: Modifier = Modifier
) {
    val cache = remember(history) { TimelinePathCache() }
    Canvas(modifier) {
        drawRoundRect(BackgroundDark, cornerRadius = CornerRadius(6.dp.toPx()))
        val center = size.height / 2f
        drawLine(TextSecondary.copy(alpha = 0.12f), Offset(0f, center), Offset(size.width, center))
        cache.ensureBuilt(history, revision.value, size)
        val alpha = if (dimmed) 0.35f else 1f
        drawPath(cache.paths[0], WaveformCdjLow, alpha = alpha)
        drawPath(cache.paths[1], WaveformCdjMid, alpha = alpha)
        drawPath(cache.paths[2], WaveformCdjHi, alpha = alpha)
        drawPlayhead(recording)
    }
}

private fun DrawScope.drawPlayhead(recording: Boolean) {
    val x = size.width - 1.dp.toPx()
    drawLine(
        if (recording) AccentRed else TextSecondary.copy(alpha = 0.6f),
        Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx()
    )
}

/** Band envelopes as one path per band, rebuilt only when the history or size changes. */
private class TimelinePathCache {
    val paths = Array(3) { Path() }
    private var builtRevision = -1L
    private var builtSize = Size.Zero

    fun ensureBuilt(history: TrackWaveformHistory, revision: Long, size: Size) {
        if (revision == builtRevision && size == builtSize) return
        paths.forEach { it.reset() }
        val columnWidth = size.width / history.capacity
        val center = size.height / 2f
        val count = history.size
        for (index in 0 until count) {
            val x = size.width - (count - index) * columnWidth
            if (x + columnWidth < 0f) continue
            for (band in 0..2) {
                val height = sqrt(history.value(index, band + 1)) * center * 0.92f
                if (height < 0.5f) continue
                paths[band].addRect(Rect(x, center - height, x + columnWidth + 0.5f, center + height))
            }
        }
        builtRevision = revision
        builtSize = size
    }
}

/**
 * Time axis above the lanes. While recording, ticks move with the set's elapsed time
 * (labels every 10 s); otherwise it shows how far back the live input history reaches.
 */
@Composable
fun TimelineRuler(
    spanMillis: Double,
    elapsedMillis: Long,
    recording: Boolean,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    Canvas(modifier) {
        if (spanMillis <= 0.0) return@Canvas
        val baseline = size.height
        fun xFor(ageMillis: Double) = (size.width - ageMillis / spanMillis * size.width).toFloat()
        if (recording) {
            val now = elapsedMillis.toDouble()
            var tick = floor(now / 5_000.0) * 5_000.0
            while (tick >= 0 && now - tick <= spanMillis) {
                val x = xFor(now - tick)
                val major = (tick.toLong() / 5_000L) % 2L == 0L
                drawLine(TextSecondary.copy(alpha = if (major) 0.7f else 0.35f),
                    Offset(x, baseline - (if (major) 8.dp else 4.dp).toPx()), Offset(x, baseline))
                if (major) {
                    val layout = measurer.measure(rulerLabel(tick.toLong() / 1000L), style)
                    val left = (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                    if (left + layout.size.width < size.width - 14.dp.toPx()) drawText(layout, topLeft = Offset(left, 0f))
                }
                tick -= 5_000.0
            }
        } else {
            for (seconds in listOf(30, 20, 10)) {
                if (seconds * 1000.0 > spanMillis) continue
                val x = xFor(seconds * 1000.0)
                drawLine(TextSecondary.copy(alpha = 0.5f), Offset(x, baseline - 6.dp.toPx()), Offset(x, baseline))
                val layout = measurer.measure("-${seconds}s", style)
                drawText(layout, topLeft = Offset((x - layout.size.width / 2f).coerceAtLeast(0f), 0f))
            }
        }
        // Playhead marker: a small downward triangle at "now".
        val tip = size.width - 1.dp.toPx()
        val half = 4.dp.toPx()
        val marker = Path().apply {
            moveTo(tip - half, baseline - 8.dp.toPx()); lineTo(tip + half, baseline - 8.dp.toPx()); lineTo(tip, baseline); close()
        }
        drawPath(marker, if (recording) AccentRed else TextSecondary)
    }
}

/** "0:50", or "1:24:10" once a set passes the hour. */
internal fun rulerLabel(totalSeconds: Long): String {
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    val minutes = totalSeconds / 60 % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}

/** Compact two-bar track meter (L above R): RMS fill plus a peak tick, VU colors. */
@Composable
fun TrackMeter(levels: StereoLevels, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val bar = (size.height - gap) / 2f
        drawMeterBar(levels.left, 0f, bar)
        drawMeterBar(levels.right, bar + gap, bar)
    }
}

private const val FLOOR_DB = -60f
private const val CEILING_DB = 3f

private fun fraction(db: Float) = ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).coerceIn(0f, 1f)

private fun meterColor(db: Float): Color = when {
    db >= 0f -> MeterRed
    db >= -6f -> MeterAmber
    else -> MeterGreen
}

private fun DrawScope.drawMeterBar(level: ChannelLevel, top: Float, height: Float) {
    val radius = CornerRadius(height / 2f)
    drawRoundRect(SurfaceVariantDark, Offset(0f, top), Size(size.width, height), radius)
    val rms = fraction(level.rmsDb) * size.width
    if (rms > 0.5f) drawRoundRect(meterColor(level.rmsDb), Offset(0f, top), Size(rms, height), radius)
    val peakX = fraction(level.peakDb) * size.width
    if (peakX > 1f) {
        val peakColor = if (level.isClipping) MeterRed else meterColor(level.peakDb)
        drawRect(peakColor, Offset((peakX - 2.dp.toPx()).coerceAtLeast(0f), top), Size(2.dp.toPx(), height))
    }
}
