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
        executor.execute { startInternal(config, sampleRate, endpoint) }
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
        val audioSource = DjmPcmAudioSource(sourceFailure) { totalBytes, peakDb ->
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
        candidate.getGlInterface().autoHandleOrientation = config.videoMode != LiveVideoMode.ARTWORK
        stream = candidate
        val prepared = runCatching {
            candidate.getStreamClient().apply {
                setLogs(false)
                setReTries(5)
                setCheckServerAlive(true)
            }
            prepareVideo(candidate, config) && candidate.prepareAudio(
                sampleRate = sampleRate,
                isStereo = true,
                bitrate = config.audioBitrate
            )
        }.getOrDefault(false)
        if (!prepared) {
            stopInternal(
                LiveStreamState(
                    LiveStreamStatus.ERROR,
                    "Phone could not prepare livestream encoders or camera",
                    config.platform,
                    config.videoMode
                )
            )
            return
        }
        if (config.videoMode != LiveVideoMode.ARTWORK) {
            // StreamBase's default only follows requested output shape. Camera texture rotation
            // must start from current display orientation; sensor updates then keep it correct.
            candidate.setOrientation(currentCameraTextureRotation())
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
            active.setOrientation(currentCameraTextureRotation())
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
        val current = config ?: return
        Log.i(TAG, "Connected to ${current.platform.label}")
        _state.update { state ->
            state.copy(
                status = LiveStreamStatus.CONNECTING,
                message = "RTMP connected; validating encoded media",
                platform = current.platform,
                videoMode = current.videoMode,
                startedAtMillis = SystemClock.elapsedRealtime()
            )
        }
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
        } else if (_state.value.isActive) {
            val current = config
            _state.value = LiveStreamState(
                LiveStreamStatus.ERROR,
                "Livestream disconnected",
                current?.platform,
                current?.videoMode ?: LiveVideoMode.ARTWORK
            )
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

    private fun currentCameraTextureRotation(): Int {
        val displayRotation = CameraHelper.getCameraOrientation(appContext)
        return cameraTextureRotation(displayRotation)
    }

    private fun prepareVideo(candidate: RtmpStream, config: LiveStreamConfig): Boolean {
        val rotation = if (config.portrait) 90 else 0
        val sizes = if (config.videoMode == LiveVideoMode.ARTWORK) {
            listOf(1280 to 720)
        } else {
            listOf(1280 to 720, 640 to 480)
        }
        return sizes.any { (width, height) ->
            runCatching {
                candidate.prepareVideo(
                    width = width,
                    height = height,
                    bitrate = config.videoBitrate,
                    fps = 30,
                    iFrameInterval = 2,
                    rotation = rotation
                )
            }.getOrDefault(false)
        }
    }
}

internal fun cameraTextureRotation(displayCameraOrientation: Int): Int =
    if (displayCameraOrientation == 0) 270 else displayCameraOrientation - 90

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
