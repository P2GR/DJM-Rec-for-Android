package com.audiopro.djmrec.editor

import com.audiopro.djmrec.audio.RecordingFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What the post-set editor does to a recording. Times are in milliseconds of the source. */
data class EditSettings(
    val trimStartMillis: Long = 0,
    /** Exclusive end of the kept range; values past the end mean "to the end". */
    val trimEndMillis: Long = Long.MAX_VALUE,
    val fadeInMillis: Long = 0,
    val fadeOutMillis: Long = 0,
    /** Integrated loudness target in LUFS, or null to keep the level. */
    val targetLufs: Double? = null,
    val format: RecordingFormat = RecordingFormat.MP3
)

object EditMath {
    /** Output never exceeds this peak (-1 dBFS), matching the recorder's safety limiter. */
    const val CEILING = 0.8912509381337456
    const val MAX_BOOST_DB = 20.0
    const val MAX_CUT_DB = -30.0

    fun millisToFrames(millis: Long, sampleRate: Int): Long =
        if (millis == Long.MAX_VALUE) Long.MAX_VALUE else millis * sampleRate / 1000

    /** Gain in dB that moves [measuredLufs] to [targetLufs], within sane limits. */
    fun normalizationGainDb(measuredLufs: Double?, targetLufs: Double?): Double {
        if (measuredLufs == null || targetLufs == null || measuredLufs.isInfinite()) return 0.0
        return (targetLufs - measuredLufs).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
    }

    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /** Smooth S-shaped fade (raised cosine) from 0 at [position] 0 to 1 at [length]. */
    fun fadeCurve(position: Long, length: Long): Double {
        if (length <= 0 || position >= length) return 1.0
        if (position <= 0) return 0.0
        return 0.5 - 0.5 * cos(PI * position / length)
    }
}

/**
 * Renders a trimmed, level-adjusted, faded copy of a recording in one streaming pass:
 * trim -> gain -> look-ahead limiter (-1 dBFS) -> fades. The limiter's delay is compensated,
 * so the output holds exactly the kept frames. Pure Kotlin so it can be unit tested.
 */
class EditProcessor(
    sampleRate: Int,
    private val channels: Int,
    settings: EditSettings,
    gainDb: Double,
    sourceFrames: Long
) {
    private val startFrame = EditMath.millisToFrames(settings.trimStartMillis, sampleRate).coerceIn(0, sourceFrames)
    private val endFrame = EditMath.millisToFrames(settings.trimEndMillis, sampleRate).coerceIn(startFrame, sourceFrames)
    /** Frames the output file will contain. */
    val outputFrames = endFrame - startFrame
    private val fadeInFrames = min(EditMath.millisToFrames(settings.fadeInMillis, sampleRate), outputFrames)
    private val fadeOutFrames = min(EditMath.millisToFrames(settings.fadeOutMillis, sampleRate), outputFrames)
    private val gain = EditMath.dbToLinear(gainDb)
    // Transparent below -1 dBFS, so trims and fades of limiter-recorded sets stay sample-exact;
    // a raised level or an older, hotter recording is held under the ceiling.
    private val limiter = OfflineLimiter(sampleRate, channels)
    private var sourcePosition = 0L
    private var limiterInput = 0L
    private var emitted = 0L
    private val scratch = DoubleArray(4096 * channels)

    /**
     * Feeds [frameCount] decoded source frames (interleaved, full scale = 1.0). Calls [emit] with
     * finished output frames, in order.
     */
    fun process(source: DoubleArray, frameCount: Int, emit: (DoubleArray, Int) -> Unit) {
        val first = max(startFrame - sourcePosition, 0L).toInt().coerceAtMost(frameCount)
        val last = min(endFrame - sourcePosition, frameCount.toLong()).toInt()
        sourcePosition += frameCount
        if (last <= first) return
        var offset = first
        while (offset < last) {
            val count = min(last - offset, scratch.size / channels)
            for (i in 0 until count * channels) scratch[i] = source[(offset * channels) + i] * gain
            pushThroughLimiter(scratch, count, emit)
            offset += count
        }
    }

    /** Flushes the limiter's look-ahead once all source frames were fed. */
    fun finish(emit: (DoubleArray, Int) -> Unit) {
        var remaining = limiter.latencyFrames.toLong()
        java.util.Arrays.fill(scratch, 0.0)
        while (remaining > 0) {
            val count = min(remaining, (scratch.size / channels).toLong()).toInt()
            pushThroughLimiter(scratch, count, emit)
            java.util.Arrays.fill(scratch, 0, count * channels, 0.0)
            remaining -= count
        }
    }

    private fun pushThroughLimiter(buffer: DoubleArray, count: Int, emit: (DoubleArray, Int) -> Unit) {
        limiter.process(buffer, count)
        // The first latencyFrames outputs are the limiter's initial silence: skip them.
        val skip = max(0L, limiter.latencyFrames - limiterInput).toInt().coerceAtMost(count)
        limiterInput += count
        val usable = min((count - skip).toLong(), outputFrames - emitted).toInt()
        if (usable <= 0) return
        if (skip > 0) System.arraycopy(buffer, skip * channels, buffer, 0, usable * channels)
        applyFades(buffer, usable)
        emit(buffer, usable)
        emitted += usable
    }

    private fun applyFades(buffer: DoubleArray, count: Int) {
        if (fadeInFrames == 0L && fadeOutFrames == 0L) return
        for (frame in 0 until count) {
            val position = emitted + frame
            var factor = 1.0
            if (position < fadeInFrames) factor *= EditMath.fadeCurve(position, fadeInFrames)
            val fromEnd = outputFrames - 1 - position
            if (fromEnd < fadeOutFrames) factor *= EditMath.fadeCurve(fromEnd, fadeOutFrames)
            if (factor != 1.0) {
                val base = frame * channels
                for (channel in 0 until channels) buffer[base + channel] *= factor
            }
        }
    }
}

/**
 * Kotlin twin of the recorder's native SafetyLimiter: sliding-window minimum of the gain each
 * frame needs, release smoothing, then a window average, applied to input delayed by the
 * window. Never exceeds [EditMath.CEILING]; exactly transparent below it.
 */
class OfflineLimiter(sampleRate: Int, private val channels: Int) {
    private val window = max(2, ceil(sampleRate * LOOKAHEAD_MILLIS / 1000.0).toInt())
    private val release = 1.0 - exp(-1.0 / (sampleRate * RELEASE_MILLIS / 1000.0))
    private val delay = DoubleArray(window * channels)
    private val envelope = DoubleArray(window) { 1.0 }
    private var envelopeSum = window.toDouble()
    private val minValues = DoubleArray(window)
    private val minTimes = LongArray(window)
    private var minHead = 0
    private var minCount = 0
    private var time = 0L
    private var lastEnvelope = 1.0

    /** Output is delayed by this many frames. */
    val latencyFrames: Int = window - 1

    /** Limits [frameCount] interleaved frames in place (output delayed by [latencyFrames]). */
    fun process(buffer: DoubleArray, frameCount: Int) {
        for (frame in 0 until frameCount) {
            val base = frame * channels
            var peak = 0.0
            for (channel in 0 until channels) peak = max(peak, abs(buffer[base + channel]))
            val required = if (peak > EditMath.CEILING) EditMath.CEILING / peak else 1.0
            val windowMin = pushWindowMin(required)
            var next = lastEnvelope + (1.0 - lastEnvelope) * release
            if (next > 1.0 - 1e-6) next = 1.0
            next = min(next, windowMin)
            lastEnvelope = next

            val slot = (time % window).toInt()
            envelopeSum += next - envelope[slot]
            envelope[slot] = next
            if (slot == window - 1) envelopeSum = envelope.sum()
            val gain = min(1.0, envelopeSum / window)

            val oldest = ((slot + 1) % window) * channels
            val current = slot * channels
            for (channel in 0 until channels) {
                val output = delay[oldest + channel]
                delay[current + channel] = buffer[base + channel]
                buffer[base + channel] = if (gain >= 1.0) output else output * gain
            }
            time++
        }
    }

    private fun pushWindowMin(value: Double): Double {
        while (minCount > 0 && minTimes[minHead] + window <= time) {
            minHead = (minHead + 1) % window
            minCount--
        }
        while (minCount > 0) {
            val back = (minHead + minCount - 1) % window
            if (minValues[back] < value) break
            minCount--
        }
        val insert = (minHead + minCount) % window
        minValues[insert] = value
        minTimes[insert] = time
        minCount++
        return minValues[minHead]
    }

    private companion object {
        const val LOOKAHEAD_MILLIS = 1.5
        const val RELEASE_MILLIS = 150.0
    }
}
