package com.audiopro.djmrec.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackWaveformHistoryTest {
    /** Native snapshot whose 512 bins all hold [value]; the newest [fresh] bins hold [freshValue]. */
    private fun snapshot(cursor: Int, value: Float = 0.25f, fresh: Int = 0, freshValue: Float = value): FloatArray {
        val bins = TrackWaveformHistory.BIN_COUNT
        return FloatArray(TrackWaveformHistory.SNAPSHOT_FLOATS).also { out ->
            for (bin in 0 until bins) {
                val v = if (bin >= bins - fresh) freshValue else value
                for (band in 0 until 4) out[bin * 4 + band] = v
            }
            out[bins * 4] = cursor.toFloat()
            out[bins * 4 + 1] = 6.1f
        }
    }

    @Test
    fun `first snapshot imports its whole history`() {
        val history = TrackWaveformHistory(capacity = 100, binsPerColumn = 8)
        history.accept(snapshot(cursor = 512))
        assertEquals(64, history.size)
        assertEquals(0.25f, history.value(63, 2))
        assertEquals(6.1 * 8, history.columnMillis, 1e-4)
    }

    @Test
    fun `later snapshots append only their new bins`() {
        val history = TrackWaveformHistory(capacity = 100, binsPerColumn = 8)
        history.accept(snapshot(cursor = 512))
        history.accept(snapshot(cursor = 528, fresh = 16, freshValue = 0.9f))
        assertEquals(66, history.size)
        assertEquals(0.9f, history.value(65, 1))
        assertEquals(0.25f, history.value(63, 1))
    }

    @Test
    fun `a repeated snapshot adds nothing`() {
        val history = TrackWaveformHistory(capacity = 100, binsPerColumn = 8)
        history.accept(snapshot(cursor = 512))
        val revision = history.revision
        history.accept(snapshot(cursor = 512))
        assertEquals(64, history.size)
        assertEquals(revision, history.revision)
    }

    @Test
    fun `columns keep the loudest bin of each band`() {
        val history = TrackWaveformHistory(capacity = 100, binsPerColumn = 8)
        history.accept(snapshot(cursor = 512))
        history.accept(snapshot(cursor = 520, value = 0.1f, fresh = 1, freshValue = 0.8f))
        assertEquals(0.8f, history.value(history.size - 1, 3))
    }

    @Test
    fun `cursor wrap-around is treated as continuous`() {
        val history = TrackWaveformHistory(capacity = 100, binsPerColumn = 8)
        history.accept(snapshot(cursor = 1048570))
        history.accept(snapshot(cursor = 10, fresh = 16))
        assertEquals(66, history.size)
    }

    @Test
    fun `a reader that fell behind gets silent columns for the gap`() {
        val history = TrackWaveformHistory(capacity = 1000, binsPerColumn = 8)
        history.accept(snapshot(cursor = 512))
        history.accept(snapshot(cursor = 512 + 512 + 80)) // 80 bins were never seen
        assertEquals(64 + 10 + 64, history.size)
        assertEquals(0f, history.value(64, 0))
    }

    @Test
    fun `history keeps only the newest capacity columns`() {
        val history = TrackWaveformHistory(capacity = 10, binsPerColumn = 8)
        history.accept(snapshot(cursor = 512, value = 0.5f))
        assertEquals(10, history.size)
        assertEquals(0f, history.value(10, 0))
    }

    @Test
    fun `snapshots from a stopped analyzer are ignored`() {
        val history = TrackWaveformHistory()
        history.accept(FloatArray(TrackWaveformHistory.SNAPSHOT_FLOATS)) // bin length 0
        history.accept(FloatArray(10))
        assertEquals(0, history.size)
    }

    @Test
    fun `clear starts a fresh history`() {
        val history = TrackWaveformHistory(capacity = 100, binsPerColumn = 8)
        history.accept(snapshot(cursor = 512))
        history.clear()
        assertEquals(0, history.size)
        history.accept(snapshot(cursor = 9000))
        assertEquals(64, history.size)
    }
}
