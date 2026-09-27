package com.audiopro.djmrec.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdaptiveVideoBitrateTest {
    @Test
    fun clearUplinkKeepsChosenBitrate() {
        val adaptive = AdaptiveVideoBitrate(maxBitrate = 8_000_000)

        repeat(20) { assertNull(adaptive.onSample(congested = false)) }
        assertEquals(8_000_000, adaptive.current)
    }

    @Test
    fun congestionStepsDownImmediatelyToTheFloor() {
        val adaptive = AdaptiveVideoBitrate(maxBitrate = 2_000_000, minBitrate = 1_000_000)

        assertEquals(1_500_000, adaptive.onSample(congested = true))
        assertEquals(1_125_000, adaptive.onSample(congested = true))
        assertEquals(1_000_000, adaptive.onSample(congested = true))
        assertNull(adaptive.onSample(congested = true))
    }

    @Test
    fun recoversOnlyAfterSustainedClearUplink() {
        val adaptive = AdaptiveVideoBitrate(maxBitrate = 4_000_000, recoverAfterSamples = 3)
        adaptive.onSample(congested = true)
        assertEquals(3_000_000, adaptive.current)

        assertNull(adaptive.onSample(congested = false))
        assertNull(adaptive.onSample(congested = false))
        assertEquals(3_450_000, adaptive.onSample(congested = false))

        // A congested report resets the recovery streak.
        assertNull(adaptive.onSample(congested = false))
        assertEquals(2_587_500, adaptive.onSample(congested = true))
        assertNull(adaptive.onSample(congested = false))
        assertNull(adaptive.onSample(congested = false))
        assertEquals(2_975_625, adaptive.onSample(congested = false))
    }

    @Test
    fun recoveryNeverExceedsChosenBitrate() {
        val adaptive = AdaptiveVideoBitrate(maxBitrate = 5_000_000, recoverAfterSamples = 1)
        adaptive.onSample(congested = true)

        assertEquals(4_312_500, adaptive.onSample(congested = false))
        assertEquals(4_959_375, adaptive.onSample(congested = false))
        assertEquals(5_000_000, adaptive.onSample(congested = false))
        assertNull(adaptive.onSample(congested = false))
    }

    @Test
    fun floorNeverExceedsChosenBitrate() {
        val adaptive = AdaptiveVideoBitrate(maxBitrate = 400_000)

        assertNull(adaptive.onSample(congested = true))
        assertEquals(400_000, adaptive.current)
    }
}
