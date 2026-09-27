package com.audiopro.djmrec.streaming

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
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
import com.pedro.library.base.recording.RecordController
import com.pedro.library.rtmp.RtmpStream
import com.audiopro.djmrec.storage.PendingVideoOutput
import com.audiopro.djmrec.storage.RecordingOutputManager
import com.audiopro.djmrec.storage.VideoOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LiveStreamController(context: Context) : ConnectChecker {
    private companion object {
        const val TAG = "LiveStreamController"
        /** Reconnect attempts per outage; refilled after every successful (re)connect. */
        const val MAX_RECONNECT_ATTEMPTS = 10
        const val RECONNECT_DELAY_MS = 3_000L
        /** RootEncoder wants a path; SegmentedMp4RecordController takes files from its sink. */
        const val VIDEO_RECORD_LABEL = "djmrec-video-segments"
        const val STORAGE_CHECK_SECONDS = 10L
        /** Heat protection: frame rate and share of the chosen bitrate while overheating. */
        const val OVERHEAT_FPS = 15
        const val OVERHEAT_BITRATE_PERCENT = 60
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
    // Replaced per stream on the executor; only read by onNewBitrate on the main thread.
    @Volatile
    private var adaptiveVideoBitrate: AdaptiveVideoBitrate? = null
    @Volatile
    private var userStopping = false
    private var storageFuture: ScheduledFuture<*>? = null
    /** Open MP4 segments by index; written from RootEncoder's muxer coroutine. */
    private val videoSegments = ConcurrentHashMap<Int, PendingVideoOutput>()
    private var recordBitrate = 0
    @Volatile
    private var overheating = false
    /** Latest lens focus distance from the camera, used to freeze focus where it is. */
    @Volatile
    private var lastFocusDistance: Float? = null

    private val recordListener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            Log.i(TAG, "Video recording: $status")
        }

        override fun onError(e: Exception?) {
            val reason = e?.message ?: "write error"
            runCatching { executor.execute { stopVideoRecording("Video recording stopped: $reason") } }
        }
    }

    fun start(config: LiveStreamConfig, sampleRate: Int) {
        val endpoint = if (!config.streams) null else runCatching { config.endpoint() }.getOrElse {
            _state.value = LiveStreamState(
                LiveStreamStatus.ERROR,
                it.message ?: "Invalid streaming settings",
                config.platform,
                config.videoMode
            )
            return
        }
        if (!config.streams && config.videoMode == LiveVideoMode.ARTWORK) {
            reject("Choose the rear or front camera to record video", config.platform, config.videoMode)
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

    private fun startInternal(config: LiveStreamConfig, sampleRate: Int, endpoint: String?) {
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
        adaptiveVideoBitrate = AdaptiveVideoBitrate(config.videoBitrate)
        recordBitrate = VideoRecordingPolicy.recordBitrate(config.quality.height)
        lastFocusDistance = null
        userStopping = false
        // The saved file gets full mixer quality; the one AAC encoder feeds stream and file alike.
        val audioBitrate = if (config.recordVideo) VideoRecordingPolicy.AUDIO_BITRATE else config.audioBitrate
        val freeBytes = RecordingOutputManager.freeBytes()
        val videoStorageOk = !config.recordVideo || freeBytes == Long.MAX_VALUE ||
            VideoRecordingPolicy.minutesRemaining(freeBytes, recordBitrate) >= 5
        if (!config.streams && !videoStorageOk) {
            stopInternal(LiveStreamState(LiveStreamStatus.ERROR,
                "Not enough free storage for video. Free at least ${VideoRecordingPolicy.MIN_FREE_BYTES / 1_000_000_000 + 1} GB and try again.",
                config.platform, config.videoMode))
            return
        }
        val recordVideo = config.recordVideo && videoStorageOk
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
                setReTries(MAX_RECONNECT_ATTEMPTS)
                setCheckServerAlive(true)
            }
            com.audiopro.djmrec.diagnostics.RemoteDiagnostics.event("StreamingAudio",
                "LiveStreamController.prepare: capture=${audioFormat.captureRate}Hz stereo PCM16; " +
                    "AAC=${audioFormat.encoderRate}Hz/${audioBitrate}bps; FIR resampling=${sampleRate != audioFormat.encoderRate}")
            check(candidate.prepareAudio(sampleRate = audioFormat.encoderRate, isStereo = true,
                bitrate = audioBitrate)) {
                "AAC audio preparation failed at ${audioFormat.encoderRate} Hz stereo. Check device encoder support."
            }
            check(prepareVideo(candidate, config, recordVideo)) {
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
        if (endpoint == null) {
            // Phone-only: the MP4 recording is the whole session.
            if (!startVideoRecording(candidate)) {
                stopInternal(LiveStreamState(LiveStreamStatus.ERROR,
                    "Could not start video recording. Check storage and camera access.",
                    config.platform, config.videoMode))
                return
            }
            _state.update {
                it.copy(
                    status = LiveStreamStatus.LIVE,
                    message = "Recording video to Movies/DJMRec",
                    startedAtMillis = SystemClock.elapsedRealtime()
                )
            }
            previewView?.let(::startPreviewIfReady)
            if (overheating) applyThermalLimits()
            return
        }
        _state.value = LiveStreamState(
            LiveStreamStatus.CONNECTING,
            "Connecting securely to ${config.platform.label}",
            config.platform,
            config.videoMode,
            videoMessage = if (config.recordVideo && !recordVideo) "Not enough free storage to save video" else null
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
            return
        }
        // A failed video start never takes the stream down; the state explains it instead.
        if (recordVideo) startVideoRecording(candidate)
        if (overheating) applyThermalLimits()
    }

    /** Starts MP4 segments on the prepared [candidate]. Executor thread only. */
    private fun startVideoRecording(candidate: RtmpStream): Boolean {
        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val segments = SegmentedMp4RecordController(
            VideoRecordingPolicy.SEGMENT_DURATION_US,
            videoSegmentSink(sessionId)
        ) { index -> _state.update { if (it.recordingVideo) it.copy(videoSegment = index) else it } }
        return runCatching {
            candidate.setRecordController(segments)
            candidate.startRecord(VIDEO_RECORD_LABEL, RecordController.RecordTracks.ALL, recordListener)
            _state.update {
                it.copy(recordingVideo = true, videoSegment = 1,
                    videoStartedAtMillis = SystemClock.elapsedRealtime(), videoMessage = null)
            }
            scheduleStorageChecks()
            Log.i(TAG, "Saving video: ${recordBitrate / 1000} kbps, ${VideoRecordingPolicy.SEGMENT_MINUTES} min segments")
            true
        }.getOrElse { error ->
            Log.e(TAG, "Could not start video recording", error)
            _state.update { it.copy(recordingVideo = false, videoMessage = "Could not start video recording") }
            false
        }
    }

    private fun videoSegmentSink(sessionId: String) = object : VideoSegmentSink {
        override fun open(index: Int): FileDescriptor? {
            val output = VideoOutputManager.create(appContext, sessionId, index) ?: return null
            videoSegments[index] = output
            return output.descriptor.fileDescriptor
        }

        override fun close(index: Int, durationMillis: Long, playable: Boolean) {
            val output = videoSegments.remove(index) ?: return
            val published = playable && VideoOutputManager.finalize(appContext, output, durationMillis)
            if (!playable) VideoOutputManager.abandon(appContext, output)
            Log.i(TAG, "Video segment $index ${if (published) "saved (${durationMillis / 1000} s)" else "discarded"}")
        }
    }

    /** Stops video before free space runs out so the lossless audio recording can finish. */
    private fun scheduleStorageChecks() {
        storageFuture?.cancel(false)
        storageFuture = executor.scheduleWithFixedDelay({
            val freeBytes = RecordingOutputManager.freeBytes()
            if (freeBytes == Long.MAX_VALUE) return@scheduleWithFixedDelay
            if (freeBytes < VideoRecordingPolicy.MIN_FREE_BYTES) {
                stopVideoRecording("Storage almost full: video stopped so the audio recording can finish.")
                return@scheduleWithFixedDelay
            }
            val minutes = VideoRecordingPolicy.minutesRemaining(freeBytes, recordBitrate)
            val warning = if (minutes < VideoRecordingPolicy.WARN_MINUTES) "Storage: about $minutes min of video left" else null
            _state.update { if (it.recordingVideo && it.videoMessage != warning) it.copy(videoMessage = warning) else it }
        }, STORAGE_CHECK_SECONDS, STORAGE_CHECK_SECONDS, TimeUnit.SECONDS)
    }

    /** Stops saving video; a phone-only session ends, a livestream keeps running. */
    fun stopVideoRecording() {
        executor.execute { stopVideoRecording(null) }
    }

    private fun stopVideoRecording(message: String?) {
        val active = stream ?: return
        storageFuture?.cancel(false)
        storageFuture = null
        if (active.isRecording) runCatching { active.stopRecord() }.onFailure { Log.e(TAG, "Stopping video failed", it) }
        val current = config
        if (current != null && !current.streams) {
            stopInternal(
                if (message == null) LiveStreamState()
                else LiveStreamState(LiveStreamStatus.ERROR, message, current.platform, current.videoMode)
            )
        } else {
            _state.update { it.copy(recordingVideo = false, videoSegment = 0, videoMessage = message) }
        }
    }

    /** Heat protection: lower frame rate and video bitrate while the phone is overheating. */
    fun setOverheating(hot: Boolean) {
        runCatching {
            executor.execute {
                if (overheating == hot) return@execute
                overheating = hot
                applyThermalLimits()
            }
        }
    }

    private fun applyThermalLimits() {
        val active = stream ?: return
        val current = config ?: return
        val fps = videoFps(current)
        runCatching { active.getGlInterface().forceFpsLimit(if (overheating) minOf(fps, OVERHEAT_FPS) else fps) }
        if (current.streams) {
            val cap = if (overheating) current.videoBitrate * OVERHEAT_BITRATE_PERCENT / 100 else null
            adaptiveVideoBitrate?.setCap(cap)?.let { runCatching { active.setVideoBitrateOnFly(it) } }
        } else {
            // Phone-only uses one encoder for the file: cap it directly.
            val bitrate = if (overheating) recordBitrate * OVERHEAT_BITRATE_PERCENT / 100 else recordBitrate
            runCatching { active.setVideoBitrateOnFly(bitrate) }
        }
        Log.i(TAG, if (overheating) "Overheating: ${OVERHEAT_FPS} fps, reduced bitrate" else "Temperature normal: full quality")
        _state.update { if (it.isActive) it.copy(overheated = overheating) else it }
    }

    /**
     * Freezes camera exposure, white balance and/or focus at their current values, so club
     * lights and strobes do not make the picture pump. Focus locks at the last measured distance.
     */
    fun setCameraLocks(locks: CameraLocks) {
        executor.execute {
            val camera = stream?.videoSource as? Camera2Source ?: return@execute
            val focusDistance = lastFocusDistance
            val lockFocus = locks.focus && focusDistance != null
            val wasFocusLocked = _state.value.cameraLocks.focus
            val applied = runCatching {
                camera.setCustomRequest { builder ->
                    builder.set(CaptureRequest.CONTROL_AE_LOCK, locks.exposure)
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, locks.whiteBalance)
                    if (locks.focus && focusDistance != null) {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                    }
                }
            }.getOrDefault(false)
            if (applied && wasFocusLocked && !lockFocus) runCatching { camera.enableAutoFocus() }
            if (applied) _state.update { it.copy(cameraLocks = locks.copy(focus = lockFocus)) }
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
        storageFuture?.cancel(false)
        storageFuture = null
        val active = stream
        stream = null
        // Finalize the open MP4 segment first, so a later teardown failure cannot lose it.
        runCatching { if (active?.isRecording == true) active.stopRecord() }
            .onFailure { Log.e(TAG, "Finalizing video failed", it) }
        runCatching {
            if (active?.isOnPreview == true) active.stopPreview(removeCallbacks = true)
            if (active?.isStreaming == true) active.stopStream()
            active?.release()
        }
        // Anything the muxer never handed back (e.g. teardown raced a segment switch) is unplayable.
        videoSegments.keys.toList().forEach { index ->
            videoSegments.remove(index)?.let { VideoOutputManager.abandon(appContext, it) }
        }
        config = null
        lastFocusDistance = null
        if (!preservePreview) previewView = null
        if (finalState != null) {
            _state.value = finalState.copy(
                recordingVideo = false,
                videoSegment = 0,
                overheated = false,
                cameraLocks = CameraLocks()
            )
        }
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
            // The other camera starts with automatic exposure, focus and white balance.
            lastFocusDistance = null
            _state.update { it.copy(cameraLocks = CameraLocks()) }
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
        // RootEncoder only refills its retry budget on a user stop, so without this every
        // network blip in a multi-hour set permanently spends one attempt until the stream dies.
        stream?.getStreamClient()?.setReTries(MAX_RECONNECT_ATTEMPTS)
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
            active.getStreamClient().reTry(RECONNECT_DELAY_MS, reason, null)
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
        if (_state.value.status == LiveStreamStatus.LIVE) {
            adaptiveVideoBitrate?.onSample(client.hasCongestion())?.let { video ->
                Log.i(TAG, "Adaptive video bitrate: ${video / 1000} kbps")
                runCatching { active.setVideoBitrateOnFly(video) }
            }
        }
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
            setCustomOnCaptureCompletedCallback { _, _, result ->
                result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
            }
        }

    private fun videoFps(config: LiveStreamConfig): Int = if (config.videoMode == LiveVideoMode.ARTWORK) 15 else 30

    /**
     * Streaming + saving video uses a second encoder for the file at [recordBitrate], so the
     * adaptive stream bitrate never lowers the saved quality. Phone-only uses one encoder.
     */
    private fun prepareVideo(candidate: RtmpStream, config: LiveStreamConfig, recordVideo: Boolean): Boolean {
        return liveVideoProfiles(config.videoMode, config.portrait, config.quality).any { profile ->
            val separateRecordEncoder = recordVideo && config.streams
            val prepared = runCatching {
                candidate.prepareVideo(
                    width = profile.sourceWidth,
                    height = profile.sourceHeight,
                    bitrate = if (config.streams) config.videoBitrate else recordBitrate,
                    fps = videoFps(config),
                    iFrameInterval = 2,
                    rotation = profile.rotation,
                    recordWidth = if (separateRecordEncoder) profile.sourceWidth else 0,
                    recordHeight = if (separateRecordEncoder) profile.sourceHeight else 0,
                    recordBitrate = recordBitrate
                )
            }.getOrDefault(false)
            if (prepared) {
                Log.i(
                    TAG,
                    "H.264 output ${profile.encodedWidth}x${profile.encodedHeight} at ${videoFps(config)}fps / " +
                        "${(if (config.streams) config.videoBitrate else recordBitrate) / 1000} kbps" +
                        if (separateRecordEncoder) " + file ${recordBitrate / 1000} kbps" else ""
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

internal fun liveVideoProfiles(
    videoMode: LiveVideoMode,
    portrait: Boolean,
    quality: LiveStreamQuality = LiveStreamQuality.P720
): List<LiveVideoProfile> {
    val rotation = if (portrait) 90 else 0
    // The requested quality leads; classic sizes remain as encoder-capability fallbacks.
    val sizes = buildList {
        add(quality.width to quality.height)
        add(1280 to 720)
        if (videoMode != LiveVideoMode.ARTWORK) add(640 to 480)
    }.distinct()
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
