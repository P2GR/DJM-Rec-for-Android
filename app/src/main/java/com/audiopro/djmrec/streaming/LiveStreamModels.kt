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
    val videoBitrate: Int = 3_000_000
) {
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
