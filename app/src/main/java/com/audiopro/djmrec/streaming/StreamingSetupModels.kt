package com.audiopro.djmrec.streaming

enum class StreamSetupStatus {
    IDLE,
    CONNECTING,
    WAITING_FOR_USER,
    READY,
    ERROR
}

enum class YouTubePrivacy(val apiValue: String, val label: String) {
    PRIVATE("private", "Private"),
    UNLISTED("unlisted", "Unlisted"),
    PUBLIC("public", "Public")
}

data class StreamCredentials(
    val platform: LivePlatform,
    val serverUrl: String,
    val streamKey: String,
    val destinationUrl: String? = null,
    val shareUrl: String? = null
)

class YouTubeLiveSession internal constructor(
    internal val accessToken: String,
    val broadcastId: String,
    val streamId: String
) {
    val studioUrl: String = "https://studio.youtube.com/video/$broadcastId/livestreaming"
    val watchUrl: String = "https://www.youtube.com/watch?v=$broadcastId"

    override fun toString(): String =
        "YouTubeLiveSession(broadcastId=$broadcastId, streamId=$streamId, accessToken=***)"
}

data class PreparedYouTubeLive(
    val credentials: StreamCredentials,
    val session: YouTubeLiveSession
)

enum class YouTubeFinishResult {
    COMPLETED,
    DELETED
}

enum class YouTubeBroadcastStatus {
    IDLE,
    PLANNED,
    WAITING_FOR_INGEST,
    STARTING,
    LIVE,
    COMPLETING,
    COMPLETE,
    ERROR
}

data class YouTubeBroadcastState(
    val status: YouTubeBroadcastStatus = YouTubeBroadcastStatus.IDLE,
    val message: String = "",
    val watchUrl: String? = null,
    val studioUrl: String? = null
)

data class StreamSetupState(
    val platform: LivePlatform? = null,
    val status: StreamSetupStatus = StreamSetupStatus.IDLE,
    val message: String = "",
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val credentials: StreamCredentials? = null
) {
    val isBusy: Boolean
        get() = status == StreamSetupStatus.CONNECTING || status == StreamSetupStatus.WAITING_FOR_USER
}
