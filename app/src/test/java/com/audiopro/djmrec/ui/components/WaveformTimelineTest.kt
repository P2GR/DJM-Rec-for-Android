package com.audiopro.djmrec.ui.components

import org.junit.Assert.*
import org.junit.Test

class WaveformTimelineTest {
    private fun snapshot(cursor: Int) = FloatArray(2050).apply { this[2048] = cursor.toFloat(); this[2049] = 6f }

    @Test fun additiveBandsProducePrimariesAndMixedColors() {
        assertEquals(0xffff0000.toInt(), waveformRgb(1f, 0f, 0f))
        assertEquals(0xff00ff00.toInt(), waveformRgb(0f, 1f, 0f))
        assertEquals(0xff0000ff.toInt(), waveformRgb(0f, 0f, 1f))
        assertEquals(0xffffff00.toInt(), waveformRgb(1f, 1f, 0f))
        assertEquals(0xff00ffff.toInt(), waveformRgb(0f, 1f, 1f))
        assertEquals(0xffff00ff.toInt(), waveformRgb(1f, 0f, 1f))
        assertEquals(0xffffffff.toInt(), waveformRgb(1f, 1f, 1f))
        assertEquals(0xff000000.toInt(), waveformRgb(Float.NaN, -1f, 0f))
    }

    @Test fun snapshotsTranslateWithoutMorphingHistory() {
        val timeline = WaveformTimeline()
        val first = snapshot(100)
        first[400] = 0.8f
        timeline.accept(first)
        assertEquals(8f, timeline.lag(1_000_000, true), 0.001f)
        assertEquals(5f, timeline.lag(19_000_000, true), 0.001f)
        timeline.accept(snapshot(103))
        assertEquals(8f, timeline.lag(19_000_000, true), 0.001f)
        assertEquals(0.8f, first[400], 0f)
        assertEquals(0f, timeline.lag(1_000_000_000, true), 0f)
        assertEquals(0f, timeline.lag(2_000_000_000, true), 0f)
    }

    @Test fun cursorWrapResetAndDisabledSmoothingStayBounded() {
        val timeline = WaveformTimeline()
        timeline.accept(snapshot(1048574))
        timeline.lag(1_000_000, true)
        timeline.accept(snapshot(1))
        assertEquals(8f, timeline.lag(19_000_000, true), 0.001f)
        timeline.accept(snapshot(0))
        assertEquals(8f, timeline.lag(20_000_000, true), 0.001f)
        assertEquals(0f, timeline.lag(30_000_000, false), 0f)
        timeline.accept(FloatArray(0))
        assertTrue(timeline.bins.isEmpty())
    }
}
