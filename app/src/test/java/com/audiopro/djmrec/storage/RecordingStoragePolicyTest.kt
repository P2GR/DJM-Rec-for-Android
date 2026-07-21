package com.audiopro.djmrec.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class RecordingStoragePolicyTest {
    @Test
    fun `24-bit stereo estimate matches PCM byte rate`() {
        assertEquals(288_000L, RecordingStoragePolicy.worstCaseBytesPerSecond(48_000, 2, 24))
    }

    @Test
    fun `start reserve never falls below 256 MiB`() {
        assertEquals(
            RecordingStoragePolicy.MINIMUM_START_BYTES,
            RecordingStoragePolicy.requiredStartBytes(288_000)
        )
    }

    @Test
    fun `WAV rolls before RIFF limit`() {
        assertFalse(RecordingStoragePolicy.shouldRollWav(RecordingStoragePolicy.WAV_ROLL_BYTES - 1))
        assertTrue(RecordingStoragePolicy.shouldRollWav(RecordingStoragePolicy.WAV_ROLL_BYTES))
    }

    @Test
    fun `recovery derives valid RIFF and data sizes`() {
        assertEquals(92 to 56, RecordingOutputManager.wavHeaderSizes(100))
        assertNull(RecordingOutputManager.wavHeaderSizes(43))
        assertNull(RecordingOutputManager.wavHeaderSizes(UInt.MAX_VALUE.toLong() + 9))
    }
}
