package com.audiopro.djmrec.ui.components
import org.junit.Assert.*
import org.junit.Test

class WaveformFrameLimiterTest {
    @Test fun capsDrawsAcrossRefreshRates() {
        for (refresh in listOf(60, 90, 120, 144)) {
            val limiter = WaveformFrameLimiter()
            var draws = 0
            for (tick in 0 until refresh * 10) {
                if (limiter.shouldRender(tick * 1_000_000_000L / refresh)) draws++
            }
            assertTrue("$refresh Hz: $draws draws", draws in 599..600)
        }
    }
}
