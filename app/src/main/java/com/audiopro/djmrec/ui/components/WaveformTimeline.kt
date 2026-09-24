package com.audiopro.djmrec.ui.components

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
