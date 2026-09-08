package com.audiopro.djmrec.streaming

import android.content.Context
import android.media.MediaCodec
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.encoder.CodecErrorCallback
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.FrameCapturedCallback
import com.pedro.encoder.utils.CodecUtil.CodecTypeError
import com.pedro.library.rtmp.RtmpStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LiveStreamController(context: Context) : ConnectChecker {
    private companion object {
        const val TAG = "LiveStreamController"
    }

    private val appContext = context.applicationContext
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DjmRtmpController")
    }
    private val _state = MutableStateFlow(LiveStreamState())
    val state: StateFlow<LiveStreamState> = _state.asStateFlow()

    private var stream: RtmpStream? = null
    private var config: LiveStreamConfig? = null
    private var previewView: SurfaceView? = null
    @Volatile
    private var validationFuture: ScheduledFuture<*>? = null
    private var progressFuture: ScheduledFuture<*>? = null
    private val progressWatchdog = MediaProgressWatchdog()
    private val cameraFramesCaptured = AtomicLong(0)
    @Volatile
    private var userStopping = false

    fun start(config: LiveStreamConfig, sampleRate: Int) {
        val endpoint = runCatching { config.endpoint() }.getOrElse {
            _state.value = LiveStreamState(
                LiveStreamStatus.ERROR,
                it.message ?: "Invalid streaming settings",
                config.platform,
                config.videoMode
            )
            return
        }
        if (_state.value.isActive) return
        _state.value = LiveStreamState(
            LiveStreamStatus.PREPARING,
            "Preparing AAC and H.264 encoders",
            config.platform,
            config.videoMode
        )
        executor.execute {
            runCatching { startInternal(config, sampleRate, endpoint) }.onFailure { error ->
                stopInternal(LiveStreamState(LiveStreamStatus.ERROR,
                    "Livestream setup failed (${error.javaClass.simpleName}). Check camera access and retry.",
                    config.platform, config.videoMode))
            }
        }
    }

    private fun startInternal(config: LiveStreamConfig, sampleRate: Int, endpoint: String) {
        // Compose can create its SurfaceView before the service handles ACTION_START_LIVE.
        // Keep that view across stream replacement or preview remains permanently black.
        stopInternal(null, preservePreview = true)
        if (config.videoMode == LiveVideoMode.ARTWORK && config.artworkUri.isNullOrBlank()) {
            stopInternal(
                LiveStreamState(
                    LiveStreamStatus.ERROR,
                    "Choose custom artwork before going live",
                    config.platform,
                    config.videoMode
                )
            )
            return
        }
        val audioFormat = StreamAudioFormat.fromCapture(sampleRate)
        if (audioFormat == null) {
            stopInternal(LiveStreamState(LiveStreamStatus.ERROR,
                "Mixer reported unsupported rate $sampleRate Hz. Reconnect the mixer to detect its clock again.",
                config.platform, config.videoMode))
            return
        }
        this.config = config
        cameraFramesCaptured.set(0)
        userStopping = false
        Log.i(TAG, "Preparing ${config.platform.label} stream: ${config.videoMode}, ${sampleRate}Hz")
        val sourceFailure: (String) -> Unit = { reason ->
            Log.e(TAG, "Livestream source failed: $reason")
            executor.execute {
                stopInternal(
                    LiveStreamState(
                        LiveStreamStatus.ERROR,
                        reason,
                        config.platform,
                        config.videoMode
                    )
                )
            }
        }
        val videoSource = createVideoSource(config, sourceFailure)
        val audioSource = DjmPcmAudioSource(sampleRate, sourceFailure) { totalBytes, peakDb ->
            _state.update { state ->
                if (state.isActive) state.copy(audioPcmBytes = totalBytes, audioPeakDb = peakDb)
                else state
            }
        }
        val candidate = RtmpStream(appContext, this, videoSource, audioSource)
        candidate.setEncoderErrorCallback(object : CodecErrorCallback {
            override fun onCodecError(type: CodecTypeError, e: MediaCodec.CodecException) {
                sourceFailure("${type.label()} encoder failed: ${e.diagnosticInfo}")
            }

            override fun onEncodeError(type: CodecTypeError, e: IllegalStateException): Boolean {
                Log.e(TAG, "${type.label()} encoder crashed; attempting recovery", e)
                return true
            }
        })
        // prepareVideo owns both encoder shape and camera transform. Letting the sensor or a
        // later setOrientation call change only the transform stretches it inside the fixed frame.
        candidate.getGlInterface().autoHandleOrientation = false
        stream = candidate
        val preparation = runCatching {
            candidate.getStreamClient().apply {
                setLogs(false)
                setReTries(5)
                setCheckServerAlive(true)
            }
            com.audiopro.djmrec.diagnostics.RemoteDiagnostics.event("StreamingAudio",
                "LiveStreamController.prepare: capture=${audioFormat.captureRate}Hz stereo PCM16; " +
                    "AAC=${audioFormat.encoderRate}Hz/${config.audioBitrate}bps; FIR resampling=${sampleRate != audioFormat.encoderRate}")
            check(candidate.prepareAudio(sampleRate = audioFormat.encoderRate, isStereo = true,
                bitrate = config.audioBitrate)) {
                "AAC audio preparation failed at ${audioFormat.encoderRate} Hz stereo. Check device encoder support."
            }
            check(prepareVideo(candidate, config)) {
                if (config.videoMode == LiveVideoMode.ARTWORK) "Artwork/H.264 preparation failed. Choose a readable image."
                else "Camera/H.264 preparation failed. Close other camera apps and check camera permission."
            }
        }
        if (preparation.isFailure) {
            stopInternal(LiveStreamState(LiveStreamStatus.ERROR,
                preparation.exceptionOrNull()?.message ?: "Livestream preparation failed",
                config.platform, config.videoMode))
            return
        }
        _state.value = LiveStreamState(
            LiveStreamStatus.CONNECTING,
            "Connecting securely to ${config.platform.label}",
            config.platform,
            config.videoMode
        )
        runCatching {
            candidate.startStream(endpoint)
            previewView?.let(::startPreviewIfReady)
        }.onFailure { error ->
            Log.e(TAG, "Could not start RTMP stream", error)
            stopInternal(
                LiveStreamState(
                    LiveStreamStatus.ERROR,
                    "Could not start camera, encoders, or RTMP connection",
                    config.platform,
                    config.videoMode
                )
            )
        }
    }

    fun stop() {
        userStopping = true
        Log.i(TAG, "Stopping livestream")
        executor.execute { stopInternal(LiveStreamState()) }
    }

    fun stopWithError(message: String) {
        userStopping = true
        val current = _state.value
        executor.execute {
            stopInternal(
                LiveStreamState(
                    LiveStreamStatus.ERROR,
                    message,
                    current.platform,
                    current.videoMode
                )
            )
        }
    }

    fun reject(message: String, platform: LivePlatform?, videoMode: LiveVideoMode) {
        _state.value = LiveStreamState(
            LiveStreamStatus.ERROR,
            message,
            platform,
            videoMode
        )
    }

    private fun stopInternal(finalState: LiveStreamState?, preservePreview: Boolean = false) {
        validationFuture?.cancel(false)
        validationFuture = null
        progressFuture?.cancel(false)
        progressFuture = null
        val active = stream
        stream = null
        runCatching {
            if (active?.isOnPreview == true) active.stopPreview(removeCallbacks = true)
            if (active?.isStreaming == true) active.stopStream()
            active?.release()
        }
        config = null
        if (!preservePreview) previewView = null
        if (finalState != null) _state.value = finalState
    }

    fun attachPreview(surfaceView: SurfaceView) {
        executor.execute {
            if (previewView === surfaceView) {
                startPreviewIfReady(surfaceView)
                return@execute
            }
            val active = stream
            if (active?.isOnPreview == true) runCatching {
                active.stopPreview(removeCallbacks = true)
            }
            previewView = surfaceView
            startPreviewIfReady(surfaceView)
        }
    }

    fun detachPreview() {
        executor.execute {
            val active = stream
            if (active?.isOnPreview == true) runCatching {
                active.stopPreview(removeCallbacks = true)
            }
            previewView = null
        }
    }

    private fun startPreviewIfReady(surfaceView: SurfaceView) {
        val active = stream ?: return
        val usesCamera = config?.videoMode?.let {
            it == LiveVideoMode.BACK_CAMERA || it == LiveVideoMode.FRONT_CAMERA
        } == true
        if (!usesCamera || active.isOnPreview) return
        runCatching {
            active.startPreview(surfaceView, autoHandle = true)
        }.onFailure { Log.e(TAG, "Camera preview failed", it) }
    }

    fun switchCamera() {
        executor.execute {
            (stream?.videoSource as? Camera2Source)?.let { runCatching { it.switchCamera() } }
        }
    }

    fun release() {
        userStopping = true
        executor.submit { stopInternal(LiveStreamState()) }.get()
        executor.shutdownNow()
    }

    override fun onConnectionStarted(url: String) = Unit

    override fun onConnectionSuccess() {
        executor.execute { connectionSucceeded() }
    }

    private fun connectionSucceeded() {
        val current = config ?: return
        Log.i(TAG, "Connected to ${current.platform.label}")
        _state.update { state ->
            state.copy(
                status = LiveStreamStatus.CONNECTING,
                message = "RTMP connected; validating encoded media",
                platform = current.platform,
                videoMode = current.videoMode,
                startedAtMillis = state.startedAtMillis.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
            )
        }
        progressFuture?.cancel(false)
        progressWatchdog.reset(SystemClock.elapsedRealtime())
        progressFuture = executor.scheduleWithFixedDelay({
            val active = stream ?: return@scheduleWithFixedDelay
            val state = _state.value
            if (state.status != LiveStreamStatus.LIVE) return@scheduleWithFixedDelay
            val client = active.getStreamClient()
            progressWatchdog.failure(SystemClock.elapsedRealtime(), state.audioPcmBytes,
                client.getSentAudioFrames(), client.getSentVideoFrames())?.let { failure ->
                stopInternal(state.copy(status = LiveStreamStatus.ERROR, message = failure))
            }
        }, 1, 1, TimeUnit.SECONDS)
        validationFuture?.cancel(false)
        validationFuture = executor.schedule({
            val state = _state.value
            if (state.status == LiveStreamStatus.CONNECTING) {
                stopInternal(
                    state.copy(
                        status = LiveStreamStatus.ERROR,
                        message = mediaValidationFailure(state)
                    )
                )
            }
        }, 15, TimeUnit.SECONDS)
    }

    override fun onConnectionFailed(reason: String) {
        val active = stream ?: return
        val current = config ?: return
        val retrying = runCatching {
            active.getStreamClient().reTry(3_000, reason, null)
        }.getOrDefault(false)
        if (retrying) {
            _state.update { it.copy(
                status = LiveStreamStatus.RECONNECTING,
                message = "Connection interrupted. Reconnecting..."
            ) }
        } else {
            val safeReason = reason.replace(current.streamKey, "***").take(160)
            Log.e(TAG, "Livestream connection failed: $safeReason")
            executor.execute {
                stopInternal(
                    LiveStreamState(
                        LiveStreamStatus.ERROR,
                        "Stream connection failed: $safeReason",
                        current.platform,
                        current.videoMode
                    )
                )
            }
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        val active = stream ?: return
        val client = active.getStreamClient()
        _state.update { current ->
            if (!current.isActive) {
                current
            } else {
                val audioSent = client.getSentAudioFrames()
                val videoSent = client.getSentVideoFrames()
                val mediaReady = audioSent > 0 && videoSent > 0
                if (mediaReady) {
                    validationFuture?.cancel(false)
                    validationFuture = null
                }
                current.copy(
                    status = if (mediaReady) LiveStreamStatus.LIVE else current.status,
                    bitrateBitsPerSecond = bitrate,
                    droppedAudioFrames = client.getDroppedAudioFrames(),
                    droppedVideoFrames = client.getDroppedVideoFrames(),
                    audioFramesSent = audioSent,
                    videoFramesSent = videoSent,
                    cameraFramesCaptured = cameraFramesCaptured.get(),
                    message = when {
                        mediaReady -> "Encoded audio and video are streaming"
                        current.audioPcmBytes == 0L -> "RTMP connected; waiting for mixer PCM"
                        audioSent == 0L -> "Mixer PCM received; waiting for AAC packets"
                        current.videoMode != LiveVideoMode.ARTWORK && !current.cameraOpened ->
                            "AAC ready; waiting for camera"
                        else -> "AAC ready; waiting for H.264 packets"
                    }
                )
            }
        }
    }

    override fun onDisconnect() {
        if (userStopping) {
            _state.value = LiveStreamState()
        } else {
            executor.execute {
                val state = _state.value
                if (state.isActive && !userStopping) {
                    stopInternal(state.copy(status = LiveStreamStatus.ERROR, message = "Livestream disconnected"))
                }
            }
        }
    }

    override fun onAuthError() {
        val current = config ?: return
        Log.e(TAG, "${current.platform.label} rejected stream credentials")
        executor.execute {
            stopInternal(
                LiveStreamState(
                    LiveStreamStatus.ERROR,
                    "Stream key rejected by ${current.platform.label}",
                    current.platform,
                    current.videoMode
                )
            )
        }
    }

    override fun onAuthSuccess() = Unit

    private fun createVideoSource(
        config: LiveStreamConfig,
        onFailure: (String) -> Unit
    ): VideoSource = when (config.videoMode) {
        LiveVideoMode.ARTWORK -> ArtworkVideoSource(
            appContext,
            config.artworkUri.orEmpty(),
            onFailure
        )
        LiveVideoMode.BACK_CAMERA -> createCameraSource(front = false, onFailure)
        LiveVideoMode.FRONT_CAMERA -> createCameraSource(front = true, onFailure)
    }

    private fun createCameraSource(front: Boolean, onFailure: (String) -> Unit): Camera2Source =
        Camera2Source(appContext).apply {
            if (front) switchCamera()
            setCameraCallback(object : CameraCallbacks {
                override fun onCameraChanged(facing: CameraHelper.Facing) = Unit

                override fun onCameraError(error: String) {
                    onFailure("Camera failed: $error")
                }

                override fun onCameraOpened() {
                    _state.update { it.copy(cameraOpened = true) }
                }

                override fun onCameraDisconnected() {
                    onFailure("Camera disconnected")
                }
            })
            enableFrameCaptureCallback(object : FrameCapturedCallback {
                override fun onFrameCaptured(frameNumber: Long, timestamp: Long) {
                    cameraFramesCaptured.incrementAndGet()
                }
            })
        }

    private fun prepareVideo(candidate: RtmpStream, config: LiveStreamConfig): Boolean {
        return liveVideoProfiles(config.videoMode, config.portrait).any { profile ->
            val prepared = runCatching {
                candidate.prepareVideo(
                    width = profile.sourceWidth,
                    height = profile.sourceHeight,
                    bitrate = config.videoBitrate,
                    fps = if (config.videoMode == LiveVideoMode.ARTWORK) 15 else 30,
                    iFrameInterval = 2,
                    rotation = profile.rotation
                )
            }.getOrDefault(false)
            if (prepared) {
                Log.i(
                    TAG,
                    "H.264 output ${profile.encodedWidth}x${profile.encodedHeight} " +
                        "at ${if (config.videoMode == LiveVideoMode.ARTWORK) 15 else 30}fps"
                )
            }
            prepared
        }
    }
}

internal data class LiveVideoProfile(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotation: Int
) {
    val encodedWidth: Int
        get() = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
    val encodedHeight: Int
        get() = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
}

internal fun liveVideoProfiles(videoMode: LiveVideoMode, portrait: Boolean): List<LiveVideoProfile> {
    val rotation = if (portrait) 90 else 0
    val sizes = if (videoMode == LiveVideoMode.ARTWORK) {
        listOf(1280 to 720)
    } else {
        listOf(1280 to 720, 640 to 480)
    }
    return sizes.map { (width, height) -> LiveVideoProfile(width, height, rotation) }
}

internal fun mediaValidationFailure(state: LiveStreamState): String = when {
    state.audioPcmBytes == 0L -> "No mixer PCM reached livestream encoder"
    state.audioFramesSent == 0L -> "AAC encoder produced no stream packets"
    state.usesCamera && !state.cameraOpened -> "Camera did not open"
    state.videoFramesSent == 0L -> "H.264 encoder produced no stream packets"
    else -> "Livestream media validation timed out"
}

private fun CodecTypeError.label(): String = when (this) {
    CodecTypeError.AUDIO_CODEC -> "AAC"
    CodecTypeError.VIDEO_CODEC -> "H.264"
}
