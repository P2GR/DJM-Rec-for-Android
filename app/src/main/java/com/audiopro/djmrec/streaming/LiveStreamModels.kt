package com.audiopro.djmrec.streaming

import java.net.URI

enum class LivePlatform(
    val label: String,
    val defaultServerUrl: String,
    val setupHint: String,
    val setupUrl: String?
) {
    YOUTUBE(
        "YouTube",
        "rtmps://a.rtmps.youtube.com/live2",
        "Connect Google to create a private, unlisted, or public broadcast automatically.",
        "https://studio.youtube.com/"
    ),
    MIXCLOUD(
        "Mixcloud",
        "rtmp://rtmp.mixcloud.com/broadcast",
        "Mixcloud Pro required. Open Mixcloud setup and paste its reusable key.",
        "https://www.mixcloud.com/live/new/"
    ),
    CUSTOM("Custom", "", "Enter RTMP or RTMPS server credentials.", null)
}

enum class LiveVideoMode(val label: String) {
    ARTWORK("Custom artwork"),
    BACK_CAMERA("Rear camera"),
    FRONT_CAMERA("Front camera")
}

/**
 * Concrete stream quality: encoder source size + video bitrate. Presets are named by
 * their resolution (720p/1080p/1440p); Custom carries the user-chosen size and a
 * 5-30 Mbps bitrate. [detail] renders resolution + bitrate for buttons and summaries.
 */
data class LiveStreamQuality(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val videoBitrate: Int,
    val preset: Boolean = true
) {
    val detail: String
        get() = (if (preset) "" else "Custom \u00b7 ") + "${height}p \u00b7 ${videoBitrate / 1_000_000} Mbps"

    companion object {
        val P720 = LiveStreamQuality("720", "720p", 1280, 720, 5_000_000)
        val P1080 = LiveStreamQuality("1080", "1080p", 1920, 1080, 8_000_000)
        val P1440 = LiveStreamQuality("1440", "1440p", 2560, 1440, 15_000_000)
        val PRESETS = listOf(P720, P1080, P1440)
    }
}

data class LiveStreamConfig(
    val platform: LivePlatform,
    val serverUrl: String,
    val streamKey: String,
    val videoMode: LiveVideoMode,
    val portrait: Boolean,
    val artworkUri: String? = null,
    val audioBitrate: Int = when (platform) {
        LivePlatform.YOUTUBE -> 128_000
        LivePlatform.MIXCLOUD -> 320_000
        else -> 256_000
    },
    val quality: LiveStreamQuality = LiveStreamQuality.P720
) {
    /** Video bitrate follows the selected [quality] preset. */
    val videoBitrate: Int
        get() = quality.videoBitrate
    fun endpoint(): String {
        val server = serverUrl.trim().trimEnd('/')
        val key = streamKey.trim().trimStart('/')
        require(server.startsWith("rtmp://") || server.startsWith("rtmps://")) {
            "Server URL must start with rtmp:// or rtmps://"
        }
        require(runCatching { URI(server).host }.getOrNull()?.isNotBlank() == true) {
            "Enter a valid RTMP server URL"
        }
        require(key.isNotEmpty()) { "Stream key is required" }
        require(!key.any(Char::isWhitespace)) { "Stream key cannot contain spaces" }
        return "$server/$key"
    }
}

enum class LiveStreamStatus {
    IDLE,
    PREPARING,
    CONNECTING,
    RECONNECTING,
    LIVE,
    ERROR
}

data class LiveStreamState(
    val status: LiveStreamStatus = LiveStreamStatus.IDLE,
    val message: String = "Ready to stream",
    val platform: LivePlatform? = null,
    val videoMode: LiveVideoMode = LiveVideoMode.ARTWORK,
    val bitrateBitsPerSecond: Long = 0,
    val droppedAudioFrames: Long = 0,
    val droppedVideoFrames: Long = 0,
    val audioFramesSent: Long = 0,
    val videoFramesSent: Long = 0,
    val cameraFramesCaptured: Long = 0,
    val cameraOpened: Boolean = false,
    val audioPcmBytes: Long = 0,
    val audioPeakDb: Float = -60f,
    val startedAtMillis: Long = 0
) {
    val isActive: Boolean
        get() = status == LiveStreamStatus.PREPARING ||
            status == LiveStreamStatus.CONNECTING ||
            status == LiveStreamStatus.RECONNECTING ||
            status == LiveStreamStatus.LIVE

    val usesCamera: Boolean
        get() = videoMode == LiveVideoMode.BACK_CAMERA || videoMode == LiveVideoMode.FRONT_CAMERA
}
