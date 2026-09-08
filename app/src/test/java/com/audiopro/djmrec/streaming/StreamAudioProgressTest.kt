package com.audiopro.djmrec.streaming

import org.junit.Assert.*
import org.junit.Test

class StreamAudioProgressTest {
    @Test fun timestampsFollowSamplesDespiteBurstDelivery() {
        for (rate in listOf(44100, 48000, 96000)) {
            val clock = PcmFrameClock(rate)
            val first = clock.next(4096, 1_000_000)
            val second = clock.next(4096, 1_000_001)
            assertEquals(1024L * 1_000_000 / rate, second - first)
            assertTrue(clock.next(4096, 2_000_000) > second)
        }
    }

    @Test fun audioStallDetectedEvenWhenVideoKeepsFlowing() {
        val watchdog = MediaProgressWatchdog()
        watchdog.reset(0)
        assertNull(watchdog.failure(1000, 100, 10, 10))
        assertNull(watchdog.failure(10000, 200, 10, 20))
        assertEquals("Outgoing AAC audio stalled during livestream", watchdog.failure(11000, 300, 10, 30))
        watchdog.reset(12000)
        assertNull(watchdog.failure(13000, 400, 11, 31))
        assertNull(watchdog.failure(22000, 500, 12, 32))
    }
}
