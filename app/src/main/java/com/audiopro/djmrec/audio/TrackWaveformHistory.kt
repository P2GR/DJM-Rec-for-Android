package com.audiopro.djmrec.audio

/**
 * Longer waveform history for the multitrack timeline. A native analyzer snapshot holds only
 * the newest 512 bins (about 3 s); every snapshot's new bins are folded here, [binsPerColumn]
 * bins per column (the loudest value of each band), keeping the newest [capacity] columns.
 *
 * Snapshot layout (see AudioEngine.getWaveformBins): 512 x [amplitude, low, mid, high],
 * oldest first, then the committed-bin cursor modulo 1048576, then the bin length in ms.
 * Not thread-safe: feed and read it from one thread (the UI thread).
 */
class TrackWaveformHistory(val capacity: Int = 640, val binsPerColumn: Int = 8) {
    private val columns = FloatArray(capacity * BANDS)
    private val pending = FloatArray(BANDS)
    private var pendingBins = 0
    private var head = 0
    private var sequence = -1

    /** Number of stored columns, at most [capacity]. */
    var size = 0
        private set

    /** Bumped whenever a column is added or the history is cleared. */
    var revision = 0L
        private set

    var binMillis = 1000.0 / 163
        private set

    val columnMillis: Double get() = binMillis * binsPerColumn

    fun accept(snapshot: FloatArray) {
        if (snapshot.size < SNAPSHOT_FLOATS) return
        val binMs = snapshot[BIN_COUNT * BANDS + 1]
        if (!(binMs > 0f)) return // analyzer not running yet
        binMillis = binMs.toDouble()
        val next = snapshot[BIN_COUNT * BANDS].toInt()
        val delta = if (sequence < 0) BIN_COUNT else (next - sequence + CURSOR_WRAP) % CURSOR_WRAP
        sequence = next
        if (delta == 0) return
        if (delta > BIN_COUNT) {
            // The reader fell behind by more than a snapshot: keep the time axis honest with
            // silent columns instead of squeezing the gap out.
            repeat(((delta - BIN_COUNT) / binsPerColumn).coerceAtMost(capacity)) { pushColumn(0f, 0f, 0f, 0f) }
        }
        for (bin in (BIN_COUNT - minOf(delta, BIN_COUNT)) until BIN_COUNT) {
            val base = bin * BANDS
            addBin(snapshot[base], snapshot[base + 1], snapshot[base + 2], snapshot[base + 3])
        }
    }

    /** Band [band] (0 amplitude, 1 low, 2 mid, 3 high) of column [index]; 0 is the oldest. */
    fun value(index: Int, band: Int): Float {
        if (index !in 0 until size) return 0f
        val slot = (head - size + index + capacity) % capacity
        return columns[slot * BANDS + band]
    }

    fun clear() {
        columns.fill(0f)
        pending.fill(0f)
        pendingBins = 0
        head = 0
        size = 0
        sequence = -1
        revision++
    }

    private fun addBin(amplitude: Float, low: Float, mid: Float, high: Float) {
        pending[0] = maxOf(pending[0], amplitude.finiteOrZero())
        pending[1] = maxOf(pending[1], low.finiteOrZero())
        pending[2] = maxOf(pending[2], mid.finiteOrZero())
        pending[3] = maxOf(pending[3], high.finiteOrZero())
        if (++pendingBins < binsPerColumn) return
        pushColumn(pending[0], pending[1], pending[2], pending[3])
        pending.fill(0f)
        pendingBins = 0
    }

    private fun pushColumn(amplitude: Float, low: Float, mid: Float, high: Float) {
        val base = head * BANDS
        columns[base] = amplitude
        columns[base + 1] = low
        columns[base + 2] = mid
        columns[base + 3] = high
        head = (head + 1) % capacity
        size = minOf(capacity, size + 1)
        revision++
    }

    private fun Float.finiteOrZero(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f

    companion object {
        const val BIN_COUNT = 512
        const val BANDS = 4
        const val SNAPSHOT_FLOATS = BIN_COUNT * BANDS + 2
        private const val CURSOR_WRAP = 1048576
    }
}
