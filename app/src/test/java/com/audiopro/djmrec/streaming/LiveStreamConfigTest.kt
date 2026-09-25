package com.audiopro.djmrec.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

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
    fun portraitProfileProducesVerticalEncoderDimensions() {
        val profile = liveVideoProfiles(LiveVideoMode.BACK_CAMERA, portrait = true).first()

        assertEquals(1280, profile.sourceWidth)
        assertEquals(720, profile.sourceHeight)
        assertEquals(90, profile.rotation)
        assertEquals(720, profile.encodedWidth)
        assertEquals(1280, profile.encodedHeight)
    }

    @Test
    fun landscapeProfileKeepsHorizontalEncoderDimensions() {
        val profile = liveVideoProfiles(LiveVideoMode.BACK_CAMERA, portrait = false).first()

        assertEquals(0, profile.rotation)
        assertEquals(1280, profile.encodedWidth)
        assertEquals(720, profile.encodedHeight)
    }

    @Test
    fun youtubeLetsIngestDimensionsDetermineOrientation() {
        assertEquals("variable", YOUTUBE_CDN_RESOLUTION)
        assertEquals("variable", YOUTUBE_CDN_FRAME_RATE)
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

    @Test
    fun qualityPresetsMatchRequestedResolutionsAndBitrates() {
        assertEquals(5_000_000, LiveStreamQuality.P720.videoBitrate)
        assertEquals(8_000_000, LiveStreamQuality.P1080.videoBitrate)
        assertEquals(15_000_000, LiveStreamQuality.P1440.videoBitrate)
        assertEquals(2560, LiveStreamQuality.P1440.width)
        assertEquals(LiveStreamQuality.P720, config().quality)
        assertEquals(5_000_000, config().videoBitrate)
    }

    @Test
    fun customQualityCarriesItsOwnSizeAndBitrate() {
        val custom = LiveStreamQuality("custom", "Custom", 2560, 1440, 22_000_000, preset = false)
        assertEquals(22_000_000, config(quality = custom).videoBitrate)
        assertEquals("Custom \u00b7 1440p \u00b7 22 Mbps", custom.detail)
    }

    @Test
    fun qualityProfilesPreferRequestedSizeWithFallbacks() {
        val profiles = liveVideoProfiles(
            LiveVideoMode.BACK_CAMERA, portrait = true, quality = LiveStreamQuality.P1080
        )
        assertEquals(1920 to 1080, profiles.first().sourceWidth to profiles.first().sourceHeight)
        assertEquals(1080, profiles.first().encodedWidth)
        assertEquals(1920, profiles.first().encodedHeight)
        assertEquals(
            listOf(1920 to 1080, 1280 to 720, 640 to 480),
            profiles.map { it.sourceWidth to it.sourceHeight }
        )
    }

    private fun config(
        platform: LivePlatform = LivePlatform.CUSTOM,
        serverUrl: String = "rtmp://example.com/live",
        streamKey: String = "key",
        quality: LiveStreamQuality = LiveStreamQuality.P720
    ) = LiveStreamConfig(
        platform = platform,
        serverUrl = serverUrl,
        streamKey = streamKey,
        videoMode = LiveVideoMode.ARTWORK,
        portrait = false,
        quality = quality
    )
}
