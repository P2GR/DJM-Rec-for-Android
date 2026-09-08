package com.audiopro.djmrec.ui.components

import kotlin.math.pow
import kotlin.math.roundToInt

/** Audio cursor drives translation; historical peaks never morph between snapshots. */
internal class WaveformTimeline {
    var bins = FloatArray(0)
        private set
    private var sequence = -1
    private var cursor = 0.0
    private var end = 0.0
    private var lastFrame = 0L
    private var binMillis = 1000.0 / 163

    fun accept(snapshot: FloatArray) {
        if (snapshot.size < 2050 || snapshot[2049] <= 0f) {
            bins = FloatArray(0)
            sequence = -1
            lastFrame = 0
            return
        }
        val next = snapshot[2048].toInt()
        if (next == sequence) return
        val delta = (next - sequence + 1048576) % 1048576
        if (sequence < 0 || delta > 512) {
            end = next.toDouble()
            cursor = end - 8
            lastFrame = 0
        } else {
            end += delta
        }
        sequence = next
        binMillis = snapshot[2049].toDouble()
        bins = snapshot
    }

    /** Number of newest bins withheld; small latency absorbs polling jitter. */
    fun lag(frameNanos: Long, smooth: Boolean): Float {
        if (!smooth) { cursor = end; lastFrame = frameNanos; return 0f }
        if (lastFrame != 0L) {
            val elapsed = ((frameNanos - lastFrame) / 1_000_000.0).coerceIn(0.0, 100.0)
            cursor = (cursor + elapsed / binMillis).coerceAtMost(end)
        }
        lastFrame = frameNanos
        // A stalled UI catches up without reshaping or averaging historical samples.
        cursor = cursor.coerceAtLeast(end - 24)
        return (end - cursor).toFloat()
    }
}

/** Relative band magnitude maps directly to additive RGB; equal bands produce white. */
internal fun waveformRgb(low: Float, mid: Float, high: Float): Int {
    fun clean(value: Float) = if (value.isFinite()) value.coerceAtLeast(0f) else 0f
    val r = clean(low); val g = clean(mid); val b = clean(high)
    val max = maxOf(r, g, b)
    if (max < 0.000001f) return 0xff000000.toInt()
    fun channel(value: Float) = ((value / max).pow(0.65f) * 255).roundToInt().coerceIn(0, 255)
    return (0xff shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}
