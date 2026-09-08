package com.audiopro.djmrec.streaming

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

/** Process-owned broadcast lifecycle; closing an Activity must not cancel stream finalization.
 * Tokens and stream credentials stay in memory and are never written to preferences.
 */
class YouTubeBroadcastCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val liveStreamState = MutableStateFlow(LiveStreamState())
    fun updateLiveState(state: LiveStreamState) {
        val previous = liveStreamState.value
        liveStreamState.value = state
        if (previous.platform == LivePlatform.YOUTUBE && previous.isActive && !state.isActive)
            finishYouTubeSession()
    }
    private val _streamSetupState = MutableStateFlow(StreamSetupState())
    val streamSetupState: StateFlow<StreamSetupState> = _streamSetupState.asStateFlow()
    private val _youtubeBroadcastState = MutableStateFlow(YouTubeBroadcastState())
    val youtubeBroadcastState: StateFlow<YouTubeBroadcastState> =
        _youtubeBroadcastState.asStateFlow()
    private var streamSetupJob: Job? = null
    private var youtubeLifecycleJob: Job? = null
    private var youtubeCompletionJob: Job? = null
    private var youtubeLiveSession: YouTubeLiveSession? = null

    fun prepareYouTubeDestination(accessToken: String, title: String, privacy: YouTubePrivacy) {
        streamSetupJob?.cancel()
        streamSetupJob = scope.launch {
            _streamSetupState.value = StreamSetupState(
                LivePlatform.YOUTUBE,
                StreamSetupStatus.CONNECTING,
                "Creating YouTube broadcast"
            )
            try {
                youtubeLifecycleJob?.cancel()
                youtubeCompletionJob?.cancel()
                youtubeLiveSession?.let { previous ->
                    runCatching { StreamingSetupRepository.finishYouTubeBroadcast(previous) }
                }
                val prepared = StreamingSetupRepository.prepareYouTubeLive(accessToken, title, privacy)
                youtubeLiveSession = prepared.session
                _youtubeBroadcastState.value = YouTubeBroadcastState(
                    status = YouTubeBroadcastStatus.PLANNED,
                    message = "Broadcast planned. Start streaming to go live.",
                    watchUrl = prepared.session.watchUrl,
                    studioUrl = prepared.session.studioUrl
                )
                _streamSetupState.value = StreamSetupState(
                    LivePlatform.YOUTUBE,
                    StreamSetupStatus.READY,
                    "YouTube broadcast ready",
                    credentials = prepared.credentials
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _streamSetupState.value = StreamSetupState(
                    LivePlatform.YOUTUBE,
                    StreamSetupStatus.ERROR,
                    error.message ?: "YouTube setup failed"
                )
            }
        }
    }

    fun startYouTubeLifecycle() {
        val session = youtubeLiveSession ?: return
        youtubeLifecycleJob?.cancel()
        youtubeLifecycleJob = scope.launch {
            _youtubeBroadcastState.value = YouTubeBroadcastState(
                YouTubeBroadcastStatus.WAITING_FOR_INGEST,
                "Connecting RTMP feed to YouTube",
                session.watchUrl,
                session.studioUrl
            )
            try {
                val rtmpState = withTimeoutOrNull(30_000L) {
                    liveStreamState.first { state ->
                        state.platform == LivePlatform.YOUTUBE &&
                            (state.status == LiveStreamStatus.LIVE ||
                                state.status == LiveStreamStatus.ERROR)
                    }
                } ?: throw IOException("YouTube RTMP connection timed out")
                if (rtmpState.status != LiveStreamStatus.LIVE) {
                    throw IOException(rtmpState.message)
                }
                _youtubeBroadcastState.value = _youtubeBroadcastState.value.copy(
                    status = YouTubeBroadcastStatus.STARTING,
                    message = "YouTube detected RTMP. Starting broadcast..."
                )
                StreamingSetupRepository.startYouTubeBroadcast(session)
                _youtubeBroadcastState.value = _youtubeBroadcastState.value.copy(
                    status = YouTubeBroadcastStatus.LIVE,
                    message = "Broadcast is live and ready to share"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _youtubeBroadcastState.value = _youtubeBroadcastState.value.copy(
                    status = YouTubeBroadcastStatus.ERROR,
                    message = error.message ?: "YouTube could not start the broadcast"
                )
            }
        }
    }

    fun finishYouTubeSession() {
        val session = youtubeLiveSession ?: return
        youtubeLiveSession = null
        youtubeLifecycleJob?.cancel()
        youtubeLifecycleJob = null
        youtubeCompletionJob?.cancel()
        youtubeCompletionJob = scope.launch {
            _youtubeBroadcastState.value = _youtubeBroadcastState.value.copy(
                status = YouTubeBroadcastStatus.COMPLETING,
                message = "Finishing YouTube broadcast..."
            )
            try {
                when (StreamingSetupRepository.finishYouTubeBroadcast(session)) {
                    YouTubeFinishResult.COMPLETED -> {
                        _youtubeBroadcastState.value = _youtubeBroadcastState.value.copy(
                            status = YouTubeBroadcastStatus.COMPLETE,
                            message = "YouTube broadcast finished"
                        )
                    }
                    YouTubeFinishResult.DELETED -> {
                        _youtubeBroadcastState.value = YouTubeBroadcastState(
                            status = YouTubeBroadcastStatus.COMPLETE,
                            message = "Unused planned broadcast removed"
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _youtubeBroadcastState.value = _youtubeBroadcastState.value.copy(
                    status = YouTubeBroadcastStatus.ERROR,
                    message = error.message ?: "YouTube broadcast could not be finalized"
                )
            }
        }
    }

    fun setStreamSetupError(platform: LivePlatform, message: String) {
        _streamSetupState.value = StreamSetupState(platform, StreamSetupStatus.ERROR, message)
    }

    fun consumeStreamCredentials() {
        _streamSetupState.value = StreamSetupState()
    }

    fun cancelStreamSetup() {
        streamSetupJob?.cancel()
        streamSetupJob = null
        _streamSetupState.value = StreamSetupState()
    }

}
