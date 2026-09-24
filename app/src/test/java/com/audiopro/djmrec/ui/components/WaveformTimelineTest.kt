package com.audiopro.djmrec.ui.components

import org.junit.Assert.*
import org.junit.Test

class WaveformTimelineTest {
    private fun snapshot(cursor: Int) = FloatArray(2050).apply { this[2048] = cursor.toFloat(); this[2049] = 6f }

    // The old additive waveformRgb color-mixing test was removed together with the additive
    // RGB renderer; the current CDJ layered renderer draws theme-colored band paths instead.

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
