package com.audiopro.djmrec.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LiveStreamConfigTest {
    @Test
    fun endpointJoinsServerAndKey() {
        val config = LiveStreamConfig(
            platform = LivePlatform.YOUTUBE,
            serverUrl = "rtmps://example.com/live/",
            streamKey = "/secret-key",
            videoMode = LiveVideoMode.ARTWORK,
            portrait = false
        )

        assertEquals("rtmps://example.com/live/secret-key", config.endpoint())
    }

    @Test
    fun endpointRejectsNonRtmpUrls() {
        val config = config(serverUrl = "https://example.com/live")

        assertFailsWith<IllegalArgumentException> { config.endpoint() }
    }

    @Test
    fun endpointRejectsMissingHostAndKey() {
        assertFailsWith<IllegalArgumentException> { config(serverUrl = "rtmps://").endpoint() }
        assertFailsWith<IllegalArgumentException> { config(streamKey = " ").endpoint() }
    }

    @Test
    fun mixcloudUsesMusicAudioBitrate() {
        assertEquals(320_000, config(platform = LivePlatform.MIXCLOUD).audioBitrate)
        assertEquals(128_000, config(platform = LivePlatform.YOUTUBE).audioBitrate)
        assertEquals(256_000, config(platform = LivePlatform.CUSTOM).audioBitrate)
    }

    @Test
    fun providerDefaultsKeepKnownServersAndSetupLinks() {
        assertEquals("rtmp://rtmp.mixcloud.com/broadcast", LivePlatform.MIXCLOUD.defaultServerUrl)
        assertEquals("rtmps://a.rtmps.youtube.com/live2", LivePlatform.YOUTUBE.defaultServerUrl)
        assertEquals("", LivePlatform.CUSTOM.defaultServerUrl)
        assertNotNull(LivePlatform.MIXCLOUD.setupUrl)
    }

    @Test
    fun youtubeSessionBuildsShareLinksWithoutExposingToken() {
        val session = YouTubeLiveSession("sensitive-token", "broadcast-id", "stream-id")

        assertEquals("https://www.youtube.com/watch?v=broadcast-id", session.watchUrl)
        assertEquals(
            "https://studio.youtube.com/video/broadcast-id/livestreaming",
            session.studioUrl
        )
        assertFalse(session.toString().contains("sensitive-token"))
    }

    @Test
    fun cameraTextureTracksEveryDisplayRotation() {
        assertEquals(270, cameraTextureRotation(0))
        assertEquals(0, cameraTextureRotation(90))
        assertEquals(90, cameraTextureRotation(180))
        assertEquals(180, cameraTextureRotation(270))
    }

    @Test
    fun mediaValidationReportsFailedPipelineStage() {
        assertEquals(
            "No mixer PCM reached livestream encoder",
            mediaValidationFailure(LiveStreamState())
        )
        assertEquals(
            "AAC encoder produced no stream packets",
            mediaValidationFailure(LiveStreamState(audioPcmBytes = 8_192))
        )
        assertEquals(
            "Camera did not open",
            mediaValidationFailure(
                LiveStreamState(
                    videoMode = LiveVideoMode.BACK_CAMERA,
                    audioPcmBytes = 8_192,
                    audioFramesSent = 1
                )
            )
        )
        assertEquals(
            "H.264 encoder produced no stream packets",
            mediaValidationFailure(
                LiveStreamState(audioPcmBytes = 8_192, audioFramesSent = 1)
            )
        )
    }

    private fun config(
        platform: LivePlatform = LivePlatform.CUSTOM,
        serverUrl: String = "rtmp://example.com/live",
        streamKey: String = "key"
    ) = LiveStreamConfig(
        platform = platform,
        serverUrl = serverUrl,
        streamKey = streamKey,
        videoMode = LiveVideoMode.ARTWORK,
        portrait = false
    )
}
