package com.audiopro.djmrec.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoRecordingPolicyTest {
    @Test
    fun recordBitrateFollowsResolution() {
        assertEquals(8_000_000, VideoRecordingPolicy.recordBitrate(720))
        assertEquals(12_000_000, VideoRecordingPolicy.recordBitrate(1080))
        assertEquals(20_000_000, VideoRecordingPolicy.recordBitrate(1440))
    }

    @Test
    fun fullHdLandsInTheExpectedGigabytesPerHour() {
        val perHour = VideoRecordingPolicy.gigabytesPerHour(VideoRecordingPolicy.recordBitrate(1080))
        assertTrue(perHour in 4.0..8.0, "1080p should need 4-8 GB per hour, was $perHour")
    }

    @Test
    fun remainingMinutesKeepAudioReserve() {
        val bitrate = VideoRecordingPolicy.recordBitrate(1080)
        assertEquals(0, VideoRecordingPolicy.minutesRemaining(VideoRecordingPolicy.MIN_FREE_BYTES, bitrate))
        val oneHourPlusReserve = VideoRecordingPolicy.MIN_FREE_BYTES + VideoRecordingPolicy.bytesPerSecond(bitrate) * 3600
        assertEquals(60, VideoRecordingPolicy.minutesRemaining(oneHourPlusReserve, bitrate))
    }

    @Test
    fun localConfigRecordsVideoAndHasNoEndpoint() {
        val local = LiveStreamConfig(LivePlatform.LOCAL, "", "", LiveVideoMode.BACK_CAMERA, portrait = false)
        assertTrue(local.recordVideo)
        assertFalse(local.streams)
        assertTrue(runCatching { local.endpoint() }.isFailure)
        val youtube = LiveStreamConfig(LivePlatform.YOUTUBE, "rtmps://a.example/live2", "key", LiveVideoMode.BACK_CAMERA, portrait = false)
        assertFalse(youtube.recordVideo)
        assertTrue(youtube.streams)
    }
}
