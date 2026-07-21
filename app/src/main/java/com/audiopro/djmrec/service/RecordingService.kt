package com.audiopro.djmrec.service

import android.Manifest

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.MainActivity
import com.audiopro.djmrec.R
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.RecordingHealth
import com.audiopro.djmrec.audio.RecordingHealthEvaluator
import com.audiopro.djmrec.audio.RecordingHealthInput
import com.audiopro.djmrec.audio.RecordingHealthLevel
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.storage.PendingRecordingOutput
import com.audiopro.djmrec.storage.RecordingOutputManager
import com.audiopro.djmrec.storage.RecordingSessionStore
import com.audiopro.djmrec.storage.RecordingStoragePolicy
import com.audiopro.djmrec.streaming.LivePlatform
import com.audiopro.djmrec.streaming.LiveStreamConfig
import com.audiopro.djmrec.streaming.LiveStreamController
import com.audiopro.djmrec.streaming.LiveStreamState
import com.audiopro.djmrec.streaming.LiveStreamStatus
import com.audiopro.djmrec.streaming.LiveVideoMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground service hosting the entire recording session so the OS cannot kill the process
 * mid-capture. Exposes a [LocalBinder] for the UI's ViewModel to observe state directly, and
 * also reacts to notification action buttons (Pause/Resume/Stop) via `onStartCommand`.
 */
class RecordingService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.audiopro.djmrec.action.START"
        const val ACTION_MONITOR = "com.audiopro.djmrec.action.MONITOR"
        const val ACTION_PAUSE = "com.audiopro.djmrec.action.PAUSE"
        const val ACTION_RESUME = "com.audiopro.djmrec.action.RESUME"
        const val ACTION_STOP = "com.audiopro.djmrec.action.STOP"
        const val ACTION_DEVICE_DETACHED = "com.audiopro.djmrec.action.DEVICE_DETACHED"
        const val ACTION_START_LIVE = "com.audiopro.djmrec.action.START_LIVE"
        const val ACTION_STOP_LIVE = "com.audiopro.djmrec.action.STOP_LIVE"

        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_SAMPLE_RATE = "extra_sample_rate"
        const val EXTRA_BIT_DEPTH = "extra_bit_depth"
        const val EXTRA_CHANNEL_COUNT = "extra_channel_count"
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_LIVE_PLATFORM = "extra_live_platform"
        const val EXTRA_LIVE_SERVER_URL = "extra_live_server_url"
        const val EXTRA_LIVE_STREAM_KEY = "extra_live_stream_key"
        const val EXTRA_LIVE_VIDEO_MODE = "extra_live_video_mode"
        const val EXTRA_LIVE_PORTRAIT = "extra_live_portrait"
        const val EXTRA_LIVE_ARTWORK_URI = "extra_live_artwork_uri"
        const val EXTRA_LIVE_AUDIO_BITRATE = "extra_live_audio_bitrate"

        /** [EXTRA_CAPTURE_MODE] value: standard AAudio/AudioRecord path via [EXTRA_DEVICE_ID]. */
        const val CAPTURE_MODE_AAUDIO = 0
        /** [EXTRA_CAPTURE_MODE] value: raw libusb isochronous path via the EXTRA_USB_* extras. */
        const val CAPTURE_MODE_USB_ISO = 1
        /** [EXTRA_CAPTURE_MODE] value: rooted /dev/snd ALSA capture path. */
        const val CAPTURE_MODE_ROOT_ALSA = 2
        const val EXTRA_CAPTURE_MODE = "extra_capture_mode"
        const val EXTRA_ALSA_CARD = "extra_alsa_card"
        const val EXTRA_ALSA_DEVICE = "extra_alsa_device"

        // --- Raw USB iso capture params (only used when EXTRA_CAPTURE_MODE == CAPTURE_MODE_USB_ISO) ---
        /** `UsbDeviceConnection.getFileDescriptor()`; see [UsbAudioManager.openIsoCaptureHandle]. */
        const val EXTRA_USB_FD = "extra_usb_fd"
        const val EXTRA_USB_INTERFACE = "extra_usb_interface"
        const val EXTRA_USB_ALT_SETTING = "extra_usb_alt_setting"
        const val EXTRA_USB_ENDPOINT = "extra_usb_endpoint"
        const val EXTRA_USB_MAX_PACKET_SIZE = "extra_usb_max_packet_size"
        const val EXTRA_USB_TOTAL_CHANNELS = "extra_usb_total_channels"
        const val EXTRA_USB_SUBFRAME_SIZE = "extra_usb_subframe_size"
        const val EXTRA_USB_CHANNEL_OFFSET = "extra_usb_channel_offset"
        const val EXTRA_USB_CLOCK_CONTROL_INTERFACE = "extra_usb_clock_control_interface"
        const val EXTRA_USB_CLOCK_SOURCE_ID = "extra_usb_clock_source_id"
        const val EXTRA_USB_CLOCK_FREQUENCY_SETTABLE = "extra_usb_clock_frequency_settable"
        const val EXTRA_USB_FEEDBACK_ENDPOINT = "extra_usb_feedback_endpoint"
        const val EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE = "extra_usb_feedback_max_packet_size"
        const val EXTRA_USB_VENDOR_ID = "extra_usb_vendor_id"
        const val EXTRA_USB_PRODUCT_ID = "extra_usb_product_id"
        const val EXTRA_USB_RAW_DESCRIPTORS = "extra_usb_raw_descriptors"

        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001
        private const val METER_UPDATE_INTERVAL_MS = 66L // ~15 fps, plenty for a VU meter
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L
        private const val USB_SIGNAL_CHECK_INTERVAL_MS = 100L
        private const val USB_SIGNAL_CHECK_TIMEOUT_MS = 1500L
        private const val HEALTH_UPDATE_INTERVAL_MS = 2_000L
        private const val CHECKPOINT_INTERVAL_MS = 5_000L
        private const val MAX_STALLED_USB_CHECKS = 3
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val floorLevel = ChannelLevel(peakDb = -60f, rmsDb = -60f, isClipping = false)
    private val _levels = MutableStateFlow(StereoLevels(floorLevel, floorLevel))
    val levels: StateFlow<StereoLevels> = _levels.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    private val emptyWaveform = FloatArray(0)
    private val _waveformBins = MutableStateFlow(emptyWaveform)
    val waveformBins: StateFlow<FloatArray> = _waveformBins.asStateFlow()

    private val _health = MutableStateFlow(RecordingHealth.Ready)
    val health: StateFlow<RecordingHealth> = _health.asStateFlow()

    private val _liveState = MutableStateFlow(LiveStreamState())
    val liveState: StateFlow<LiveStreamState> = _liveState.asStateFlow()
    private lateinit var liveStreamController: LiveStreamController
    private var cameraForegroundActive = false

    private var wakeLock: PowerManager.WakeLock? = null

    // Dedicated urgent-audio-priority thread for pulling meter/elapsed data off the native
    // engine and refreshing the notification — kept separate from the main/UI thread so meter
    // polling never gets starved by UI work, matching the spec's thread-priority requirement.
    private lateinit var monitorThread: HandlerThread
    private lateinit var monitorHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentFormat: RecordingFormat = RecordingFormat.WAV
    private var currentBitDepth: Int = 24
    private var pendingRecordingFormat: RecordingFormat? = null
    private var currentOutput: PendingRecordingOutput? = null
    private var currentSessionId: String? = null
    private var currentPartIndex = 0
    private var currentPartStartedElapsed = 0L
    private var currentSampleRate = 48_000
    private var currentOutputChannels = 2
    private var bytesPerSecond = RecordingStoragePolicy.worstCaseBytesPerSecond(48_000, 2, 24)
    private var deviceLabel: String = "USB Mixer"
    /** True when the in-progress session opened via [startUsbIsoSession] rather than [startSession]. */
    private var isUsbIsoSession = false
    /** True when the audio stream is open for monitoring but no file is being written. */
    private var isMonitoringOnly = false
    @Volatile
    private var waveformEnabled = true
    private var waveformPollTick = 0
    private var lastCheckpointRealtime = 0L
    private var lastUsbStats = LongArray(7)
    private var usbHealthInitialized = false
    private var stalledUsbChecks = 0
    private var lastXRunCount = 0
    @Volatile
    private var safetyStopPending = false

    private val meterRunnable = object : Runnable {
        override fun run() {
            if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused ||
                _state.value is RecordingState.Monitoring) {
                val raw = AudioEngine.getLevels()
                val clipping = AudioEngine.isClipping()
                _levels.value = StereoLevels(
                    left = ChannelLevel(peakDb = raw[0], rmsDb = raw[1], isClipping = clipping),
                    right = ChannelLevel(peakDb = raw[2], rmsDb = raw[3], isClipping = clipping)
                )
                _elapsedMillis.value = AudioEngine.getElapsedMillis()
                // The waveform is visual context, not a meter. Half-rate polling avoids copying
                // 2,048 atomic floats every meter tick and materially reduces display/battery work.
                if (waveformEnabled && waveformPollTick++ % 2 == 0) {
                    _waveformBins.value = AudioEngine.getWaveformBins()
                }
                monitorHandler.postDelayed(this, METER_UPDATE_INTERVAL_MS)
            }
        }
    }

    private val notificationRunnable = object : Runnable {
        override fun run() {
            if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused ||
                _state.value is RecordingState.Monitoring) {
                updateNotification()
                monitorHandler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private val healthRunnable = object : Runnable {
        override fun run() {
            val active = _state.value is RecordingState.Recording ||
                _state.value is RecordingState.Paused || _state.value is RecordingState.Monitoring
            if (!active) return
            acquireWakeLock()

            val recording = _state.value is RecordingState.Recording || _state.value is RecordingState.Paused
            val freeBytes = RecordingOutputManager.freeBytes()
            val remaining = if (freeBytes == Long.MAX_VALUE) Long.MAX_VALUE
            else RecordingStoragePolicy.remainingSeconds(freeBytes, bytesPerSecond)
            val stats = AudioEngine.getUsbIsoTransferStats()
            val packetDelta = if (usbHealthInitialized) stats.getOrElse(0) { 0 } - lastUsbStats.getOrElse(0) { 0 } else 1
            val byteDelta = if (usbHealthInitialized) stats.getOrElse(4) { 0 } - lastUsbStats.getOrElse(4) { 0 } else 1
            val nonZeroDelta = if (usbHealthInitialized) stats.getOrElse(5) { 0 } - lastUsbStats.getOrElse(5) { 0 } else 1
            val missedDelta = if (usbHealthInitialized) stats.getOrElse(1) { 0 } - lastUsbStats.getOrElse(1) { 0 } else 0
            val resubmitDelta = if (usbHealthInitialized) stats.getOrElse(6) { 0 } - lastUsbStats.getOrElse(6) { 0 } else 0
            val xRunCount = AudioEngine.getXRunCount()
            val xRunDelta = (xRunCount - lastXRunCount).coerceAtLeast(0)
            lastUsbStats = stats
            lastXRunCount = xRunCount
            usbHealthInitialized = true

            val health = RecordingHealthEvaluator.evaluate(
                RecordingHealthInput(
                    recording = recording,
                    usbIso = isUsbIsoSession,
                    streamOpen = AudioEngine.isStreamOpen(),
                    freeBytes = freeBytes,
                    remainingSeconds = remaining,
                    packetDelta = packetDelta,
                    byteDelta = byteDelta,
                    nonZeroByteDelta = nonZeroDelta,
                    missedPacketDelta = missedDelta,
                    resubmitFailures = resubmitDelta,
                    xRuns = xRunDelta,
                    writerErrorCode = AudioEngine.getRecordingErrorCode()
                )
            )
            _health.value = health

            stalledUsbChecks = if (isUsbIsoSession && packetDelta <= 0) stalledUsbChecks + 1 else 0
            if (recording) {
                checkpointIfDue()
                when {
                    health.level == RecordingHealthLevel.ERROR -> requestSafetyStop(health.message)
                    health.level == RecordingHealthLevel.LOW_STORAGE -> requestSafetyStop(health.message)
                    stalledUsbChecks >= MAX_STALLED_USB_CHECKS ->
                        requestSafetyStop("USB audio stopped. Recording finalized safely.")
                }
            }
            monitorHandler.postDelayed(this, HEALTH_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        monitorThread = HandlerThread("AudioMonitorThread", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        monitorHandler = Handler(monitorThread.looper)
        liveStreamController = LiveStreamController(this)
        lifecycleScope.launch {
            liveStreamController.state.collect {
                _liveState.value = it
                if (!it.isActive && cameraForegroundActive) {
                    cameraForegroundActive = false
                    if (_state.value is RecordingState.Monitoring ||
                        _state.value is RecordingState.Recording ||
                        _state.value is RecordingState.Paused) {
                        startForegroundNotification()
                    }
                }
                if (::monitorHandler.isInitialized) updateNotification()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun setWaveformEnabled(enabled: Boolean) {
        waveformEnabled = enabled
        waveformPollTick = 0
        AudioEngine.setWaveformEnabled(enabled)
        if (!enabled) _waveformBins.value = emptyWaveform
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_MONITOR -> {
                if (_state.value is RecordingState.Monitoring ||
                    _state.value is RecordingState.Recording ||
                    _state.value is RecordingState.Paused ||
                    _state.value is RecordingState.Preparing) {
                    return START_NOT_STICKY
                }
                pendingRecordingFormat = null
                _state.value = RecordingState.Preparing
                // Promote before native USB open/rate probing can block.
                if (!startForegroundNotification()) {
                    _state.value = RecordingState.Error("Android blocked the recording service -- open the app and try again")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)
                val bitDepth = intent.getIntExtra(EXTRA_BIT_DEPTH, 24)
                val captureMode = intent.getIntExtra(EXTRA_CAPTURE_MODE, CAPTURE_MODE_AAUDIO)
                if (captureMode == CAPTURE_MODE_ROOT_ALSA) {
                    startRootAlsaSession(
                        card = intent.getIntExtra(EXTRA_ALSA_CARD, -1),
                        device = intent.getIntExtra(EXTRA_ALSA_DEVICE, -1),
                        sampleRateHint = sampleRate,
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        monitorOnly = true
                    )
                } else if (captureMode == CAPTURE_MODE_USB_ISO) {
                    startUsbIsoSession(
                        fd = intent.getIntExtra(EXTRA_USB_FD, -1),
                        interfaceNumber = intent.getIntExtra(EXTRA_USB_INTERFACE, -1),
                        alternateSetting = intent.getIntExtra(EXTRA_USB_ALT_SETTING, -1),
                        endpointAddress = intent.getIntExtra(EXTRA_USB_ENDPOINT, -1),
                        maxPacketSize = intent.getIntExtra(EXTRA_USB_MAX_PACKET_SIZE, -1),
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        subframeSize = intent.getIntExtra(EXTRA_USB_SUBFRAME_SIZE, 4),
                        clockControlInterfaceNumber = intent.getIntExtra(EXTRA_USB_CLOCK_CONTROL_INTERFACE, -1),
                        clockSourceId = intent.getIntExtra(EXTRA_USB_CLOCK_SOURCE_ID, -1),
                        clockSupportsFrequencySet = intent.getBooleanExtra(EXTRA_USB_CLOCK_FREQUENCY_SETTABLE, false),
                        feedbackEndpointAddress = intent.getIntExtra(EXTRA_USB_FEEDBACK_ENDPOINT, -1),
                        feedbackMaxPacketSize = intent.getIntExtra(EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE, -1),
                        vendorId = intent.getIntExtra(EXTRA_USB_VENDOR_ID, -1),
                        productId = intent.getIntExtra(EXTRA_USB_PRODUCT_ID, -1),
                        rawDescriptors = intent.getByteArrayExtra(EXTRA_USB_RAW_DESCRIPTORS) ?: byteArrayOf(),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        sampleRateHint = sampleRate,
                        monitorOnly = true
                    )
                } else {
                    val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                    val channelCount = intent.getIntExtra(EXTRA_CHANNEL_COUNT, 2)
                    startSession(deviceId, sampleRate, channelCount, bitDepth, monitorOnly = true)
                }
            }

            ACTION_START -> {
                // If already monitoring, just begin encoding.
                if (_state.value is RecordingState.Monitoring) {
                    currentFormat = recordingFormatFrom(intent)
                    beginRecordingNow()
                    return START_NOT_STICKY
                }
                // A record press while automatic monitoring is opening is queued. Opening a
                // second UsbDeviceConnection here would invalidate the first raw USB stream.
                if (_state.value is RecordingState.Preparing) {
                    pendingRecordingFormat = recordingFormatFrom(intent)
                    return START_NOT_STICKY
                }
                if (_state.value is RecordingState.Recording ||
                    _state.value is RecordingState.Paused) {
                    return START_NOT_STICKY
                }
                _state.value = RecordingState.Preparing
                if (!startForegroundNotification()) {
                    _state.value = RecordingState.Error("Android blocked the recording service -- open the app and try again")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Otherwise, open stream + encode immediately (full recording from idle).
                val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)
                val bitDepth = intent.getIntExtra(EXTRA_BIT_DEPTH, 24)
                val format = recordingFormatFrom(intent)
                val captureMode = intent.getIntExtra(EXTRA_CAPTURE_MODE, CAPTURE_MODE_AAUDIO)

                if (captureMode == CAPTURE_MODE_ROOT_ALSA) {
                    startRootAlsaSession(
                        card = intent.getIntExtra(EXTRA_ALSA_CARD, -1),
                        device = intent.getIntExtra(EXTRA_ALSA_DEVICE, -1),
                        sampleRateHint = sampleRate,
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        format = format,
                        monitorOnly = false
                    )
                } else if (captureMode == CAPTURE_MODE_USB_ISO) {
                    startUsbIsoSession(
                        fd = intent.getIntExtra(EXTRA_USB_FD, -1),
                        interfaceNumber = intent.getIntExtra(EXTRA_USB_INTERFACE, -1),
                        alternateSetting = intent.getIntExtra(EXTRA_USB_ALT_SETTING, -1),
                        endpointAddress = intent.getIntExtra(EXTRA_USB_ENDPOINT, -1),
                        maxPacketSize = intent.getIntExtra(EXTRA_USB_MAX_PACKET_SIZE, -1),
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        subframeSize = intent.getIntExtra(EXTRA_USB_SUBFRAME_SIZE, 4),
                        clockControlInterfaceNumber = intent.getIntExtra(EXTRA_USB_CLOCK_CONTROL_INTERFACE, -1),
                        clockSourceId = intent.getIntExtra(EXTRA_USB_CLOCK_SOURCE_ID, -1),
                        clockSupportsFrequencySet = intent.getBooleanExtra(EXTRA_USB_CLOCK_FREQUENCY_SETTABLE, false),
                        feedbackEndpointAddress = intent.getIntExtra(EXTRA_USB_FEEDBACK_ENDPOINT, -1),
                        feedbackMaxPacketSize = intent.getIntExtra(EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE, -1),
                        vendorId = intent.getIntExtra(EXTRA_USB_VENDOR_ID, -1),
                        productId = intent.getIntExtra(EXTRA_USB_PRODUCT_ID, -1),
                        rawDescriptors = intent.getByteArrayExtra(EXTRA_USB_RAW_DESCRIPTORS) ?: byteArrayOf(),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        sampleRateHint = sampleRate,
                        format = format,
                        monitorOnly = false
                    )
                } else {
                    val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                    val channelCount = intent.getIntExtra(EXTRA_CHANNEL_COUNT, 2)
                    startSession(deviceId, sampleRate, channelCount, bitDepth, format, monitorOnly = false)
                }
            }

            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> stopSession()
            ACTION_DEVICE_DETACHED -> handleDeviceDetached()
            ACTION_START_LIVE -> startLiveStream(intent)
            ACTION_STOP_LIVE -> stopLiveStream()
        }
        // Deliberately not sticky: if the process is killed mid-recording we do not want to
        // silently resume capturing without the user re-confirming — safer default for a
        // professional recording tool than risking a corrupt/incomplete file being extended.
        return START_NOT_STICKY
    }

    private fun recordingFormatFrom(intent: Intent): RecordingFormat {
        val value = intent.getIntExtra(EXTRA_FORMAT, currentFormat.nativeValue)
        return RecordingFormat.entries.firstOrNull { it.nativeValue == value } ?: RecordingFormat.WAV
    }

    private fun startLiveStream(intent: Intent) {
        val platform = intent.getStringExtra(EXTRA_LIVE_PLATFORM)
            ?.let { runCatching { LivePlatform.valueOf(it) }.getOrNull() }
            ?: LivePlatform.CUSTOM
        val videoMode = intent.getStringExtra(EXTRA_LIVE_VIDEO_MODE)
            ?.let { runCatching { LiveVideoMode.valueOf(it) }.getOrNull() }
            ?: LiveVideoMode.ARTWORK
        val captureReady = _state.value is RecordingState.Monitoring ||
            _state.value is RecordingState.Recording || _state.value is RecordingState.Paused
        if (!captureReady || !AudioEngine.isStreamOpen()) {
            liveStreamController.reject("Connect a mixer and wait for signal before going live", platform, videoMode)
            return
        }
        val usesCamera = videoMode != LiveVideoMode.ARTWORK
        if (usesCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            liveStreamController.reject("Camera permission is required for camera streaming", platform, videoMode)
            return
        }
        val config = LiveStreamConfig(
            platform = platform,
            serverUrl = intent.getStringExtra(EXTRA_LIVE_SERVER_URL).orEmpty(),
            streamKey = intent.getStringExtra(EXTRA_LIVE_STREAM_KEY).orEmpty(),
            videoMode = videoMode,
            portrait = intent.getBooleanExtra(EXTRA_LIVE_PORTRAIT, false),
            artworkUri = intent.getStringExtra(EXTRA_LIVE_ARTWORK_URI),
            audioBitrate = intent.getIntExtra(
                EXTRA_LIVE_AUDIO_BITRATE,
                when (platform) {
                    LivePlatform.YOUTUBE -> 128_000
                    LivePlatform.MIXCLOUD -> 320_000
                    else -> 256_000
                }
            ).coerceIn(96_000, 320_000)
        )
        cameraForegroundActive = usesCamera
        startForegroundNotification()
        liveStreamController.start(config, currentSampleRate)
    }

    fun stopLiveStream(errorMessage: String? = null) {
        if (errorMessage == null) liveStreamController.stop()
        else liveStreamController.stopWithError(errorMessage)
    }

    fun attachLivePreview(surfaceView: SurfaceView) = liveStreamController.attachPreview(surfaceView)

    fun detachLivePreview() = liveStreamController.detachPreview()

    fun switchLiveCamera() = liveStreamController.switchCamera()

    fun startSession(
        audioManagerDeviceId: Int,
        sampleRateHint: Int,
        channelCount: Int,
        bitDepth: Int,
        format: RecordingFormat = RecordingFormat.WAV,
        monitorOnly: Boolean = false
    ) {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Monitoring) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = false
        isMonitoringOnly = monitorOnly
        currentBitDepth = bitDepth
        currentOutputChannels = 2

        val negotiatedRate = AudioEngine.open(audioManagerDeviceId, sampleRateHint, channelCount, bitDepth)
        if (negotiatedRate <= 0) {
            failPreparation("Failed to open exclusive audio stream")
            return
        }
        updateRecordingFormat(negotiatedRate, bitDepth)

        if (monitorOnly) {
            beginMonitoring()
        } else {
            beginEncodingOrFail(bitDepth, format)
        }
    }

    fun startUsbIsoSession(
        fd: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        totalChannels: Int,
        subframeSize: Int,
        clockControlInterfaceNumber: Int,
        clockSourceId: Int,
        clockSupportsFrequencySet: Boolean,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        vendorId: Int,
        productId: Int,
        rawDescriptors: ByteArray,
        bitDepth: Int,
        channelOffset: Int,
        sampleRateHint: Int,
        format: RecordingFormat = RecordingFormat.WAV,
        monitorOnly: Boolean = false
    ) {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Monitoring) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = true
        isMonitoringOnly = monitorOnly
        currentBitDepth = bitDepth
        currentOutputChannels = 2

        if (fd < 0 || interfaceNumber < 0 || endpointAddress < 0 || maxPacketSize <= 0) {
            failPreparation("Invalid USB capture parameters")
            releaseIsoConnectionIfNeeded()
            return
        }
        val negotiatedRate = AudioEngine.openUsbIso(
            fd, interfaceNumber, alternateSetting, endpointAddress, maxPacketSize,
            totalChannels, subframeSize, bitDepth, channelOffset,
            clockControlInterfaceNumber, clockSourceId, clockSupportsFrequencySet,
            feedbackEndpointAddress, feedbackMaxPacketSize, vendorId, productId,
            rawDescriptors, sampleRateHint
        )
        if (negotiatedRate <= 0) {
            failPreparation("Failed to open USB isochronous capture")
            releaseIsoConnectionIfNeeded()
            return
        }
        updateRecordingFormat(negotiatedRate, bitDepth)

        if (monitorOnly) {
            beginMonitoring()
        } else {
            beginUsbIsoEncodingWhenSignalReady(bitDepth, format)
        }
    }

    fun startRootAlsaSession(
        card: Int,
        device: Int,
        sampleRateHint: Int,
        totalChannels: Int,
        bitDepth: Int,
        channelOffset: Int,
        format: RecordingFormat = RecordingFormat.WAV,
        monitorOnly: Boolean = false
    ) {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Monitoring) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = false
        isMonitoringOnly = monitorOnly
        currentBitDepth = bitDepth
        currentOutputChannels = 2

        val negotiatedRate = AudioEngine.openRootAlsa(
            card, device, sampleRateHint, totalChannels, bitDepth, channelOffset
        )
        if (negotiatedRate <= 0) {
            failPreparation("Failed to open root ALSA capture")
            return
        }
        updateRecordingFormat(negotiatedRate, bitDepth)

        if (monitorOnly) {
            beginMonitoring()
        } else {
            beginEncodingOrFail(bitDepth, format)
        }
    }

    /** Transitions from Monitoring to Recording: starts the encoder writing to a file. */
    fun beginRecordingNow() {
        if (_state.value !is RecordingState.Monitoring) return
        isMonitoringOnly = false
        beginEncodingOrFail(currentBitDepth, currentFormat)
    }

    /** Shared setup after stream open for monitoring: starts metering without file output. */
    private fun beginMonitoring() {
        val queuedFormat = pendingRecordingFormat
        if (queuedFormat != null) {
            pendingRecordingFormat = null
            isMonitoringOnly = false
            beginEncodingOrFail(currentBitDepth, queuedFormat)
            return
        }
        acquireWakeLock()
        if (!startForegroundNotification()) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            failPreparation("Android blocked the recording service -- open the app and try again")
            stopSelf()
            return
        }
        _state.value = RecordingState.Monitoring
        _health.value = RecordingHealth(
            RecordingHealthLevel.GOOD,
            "USB signal ready",
            RecordingOutputManager.freeBytes(),
            Long.MAX_VALUE
        )
        startPolling()
    }

    /** Shared tail of both [startSession] and [startUsbIsoSession] once the native capture
     *  source is open: creates the output file, starts the encoder, and flips to Recording. */
    private fun beginEncodingOrFail(bitDepth: Int, format: RecordingFormat) {
        val freeBytes = RecordingOutputManager.freeBytes()
        val requiredBytes = RecordingStoragePolicy.requiredStartBytes(bytesPerSecond)
        if (freeBytes != Long.MAX_VALUE && freeBytes < requiredBytes) {
            failEncoding("Not enough free storage. At least 256 MB is required.")
            return
        }

        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = RecordingOutputManager.create(this, sessionId, format, 1)
        if (output == null) {
            failEncoding("Failed to create recording in Music/DJMRec")
            return
        }

        val journalStarted = runCatching {
            RecordingSessionStore.begin(
                this,
                sessionId,
                format,
                currentSampleRate,
                bitDepth,
                deviceLabel,
                output.toRecord()
            )
        }.isSuccess
        if (!journalStarted) {
            RecordingOutputManager.abandon(this, output)
            failEncoding("Failed to create crash-recovery journal")
            return
        }
        currentFormat = format
        val started = AudioEngine.startRecordingFd(output.descriptor.fd, format.nativeValue)
        runCatching { output.descriptor.close() }
        if (!started) {
            RecordingOutputManager.abandon(this, output)
            RecordingSessionStore.complete(this)
            failEncoding("Failed to start ${format.name} encoder")
            return
        }

        currentOutput = output
        currentSessionId = sessionId
        currentPartIndex = 1
        currentPartStartedElapsed = 0L
        lastCheckpointRealtime = 0L
        safetyStopPending = false

        acquireWakeLock()
        if (!startForegroundNotification()) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            failPreparation("Android blocked the recording service -- open the app and try again")
            stopSelf()
            return
        }
        _state.value = RecordingState.Recording
        _health.value = RecordingHealth(
            RecordingHealthLevel.GOOD,
            "Recording healthy",
            freeBytes,
            RecordingStoragePolicy.remainingSeconds(freeBytes, bytesPerSecond)
        )
        startPolling()
    }

    private fun failEncoding(message: String) {
        if (_liveState.value.isActive) {
            currentOutput = null
            currentSessionId = null
            _state.value = RecordingState.Monitoring
            _health.value = RecordingHealth(RecordingHealthLevel.ERROR, message)
            return
        }
        AudioEngine.close()
        releaseIsoConnectionIfNeeded()
        currentOutput = null
        currentSessionId = null
        failPreparation(message)
    }

    private fun updateRecordingFormat(sampleRate: Int, bitDepth: Int) {
        currentSampleRate = sampleRate
        currentBitDepth = bitDepth
        bytesPerSecond = RecordingStoragePolicy.worstCaseBytesPerSecond(
            sampleRate,
            currentOutputChannels,
            bitDepth
        )
    }

    private fun startPolling() {
        resetHealthTracking()
        monitorHandler.removeCallbacks(meterRunnable)
        monitorHandler.removeCallbacks(notificationRunnable)
        monitorHandler.removeCallbacks(healthRunnable)
        monitorHandler.post(meterRunnable)
        monitorHandler.post(notificationRunnable)
        monitorHandler.post(healthRunnable)
    }

    private fun resetHealthTracking() {
        lastUsbStats = LongArray(7)
        usbHealthInitialized = false
        stalledUsbChecks = 0
        lastXRunCount = 0
        safetyStopPending = false
    }

    private fun checkpointIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckpointRealtime < CHECKPOINT_INTERVAL_MS) return
        lastCheckpointRealtime = now
        val partBytes = AudioEngine.checkpointRecording()
        if (partBytes < 0) {
            requestSafetyStop("Could not checkpoint recording. File finalized at last safe point.")
            return
        }
        val journalSaved = runCatching {
            RecordingSessionStore.checkpoint(this, AudioEngine.getElapsedMillis())
        }.isSuccess
        if (!journalSaved) {
            requestSafetyStop("Could not save recovery checkpoint. Recording finalized safely.")
            return
        }
        if (currentFormat == RecordingFormat.WAV && RecordingStoragePolicy.shouldRollWav(partBytes)) {
            rollWavPart()
        }
    }

    private fun rollWavPart() {
        val sessionId = currentSessionId ?: return
        val previous = currentOutput ?: return
        val nextIndex = currentPartIndex + 1
        val next = RecordingOutputManager.create(this, sessionId, RecordingFormat.WAV, nextIndex)
        if (next == null) {
            requestSafetyStop("Could not create next WAV part. Recording finalized safely.")
            return
        }
        val rolled = AudioEngine.rollRecordingFd(next.descriptor.fd, RecordingFormat.WAV.nativeValue)
        runCatching { next.descriptor.close() }
        if (!rolled) {
            RecordingOutputManager.abandon(this, next)
            requestSafetyStop("Could not continue WAV recording. Current part finalized safely.")
            return
        }

        val elapsed = AudioEngine.getElapsedMillis()
        val partJournaled = runCatching { RecordingSessionStore.addPart(this, next.toRecord()) }.isSuccess
        val previousFinalized = RecordingOutputManager.finalize(
            this,
            previous,
            elapsed - currentPartStartedElapsed
        )
        if (previousFinalized) RecordingSessionStore.markFinalized(this, previous.uri)
        currentOutput = next
        currentPartIndex = nextIndex
        currentPartStartedElapsed = elapsed
        if (!partJournaled) {
            requestSafetyStop("Could not journal next WAV part. Recording stopped safely.")
        } else if (!previousFinalized) {
            requestSafetyStop("Previous WAV part could not be published. Recording stopped safely.")
        }
    }

    private fun requestSafetyStop(message: String) {
        if (safetyStopPending) return
        safetyStopPending = true
        mainHandler.post {
            if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused) {
                stopSessionWithError(message)
            } else {
                safetyStopPending = false
            }
        }
    }

    /** A valid isochronous transfer can still contain digital silence. Wait for source bytes. */
    private fun beginUsbIsoEncodingWhenSignalReady(
        bitDepth: Int,
        format: RecordingFormat
    ) {
        val deadlineMs = SystemClock.elapsedRealtime() + USB_SIGNAL_CHECK_TIMEOUT_MS
        val checkSignal = object : Runnable {
            override fun run() {
                if (_state.value !is RecordingState.Preparing || !isUsbIsoSession) return

                val stats = AudioEngine.getUsbIsoTransferStats()
                val receivedBytes = stats.getOrElse(4) { 0L }
                val nonZeroBytes = stats.getOrElse(5) { 0L }
                if (nonZeroBytes > 0L) {
                    beginEncodingOrFail(bitDepth, format)
                    return
                }

                if (SystemClock.elapsedRealtime() < deadlineMs) {
                    monitorHandler.postDelayed(this, USB_SIGNAL_CHECK_INTERVAL_MS)
                    return
                }

                AudioEngine.close()
                releaseIsoConnectionIfNeeded()
                failPreparation(
                    if (receivedBytes == 0L) {
                        "USB audio endpoint sent no data. Reconnect the mixer and retry."
                    } else {
                        "$deviceLabel sent digital silence after MIX/REC OUT routing. " +
                            "Check the mixer's USB output setting, play audio, then retry."
                    }
                )
            }
        }
        monitorHandler.postDelayed(checkSignal, USB_SIGNAL_CHECK_INTERVAL_MS)
    }

    /** Closes the [UsbAudioManager] connection backing native libusb capture, if this session used it. */
    private fun releaseIsoConnectionIfNeeded() {
        if (isUsbIsoSession) {
            (application as DjmRecApplication).usbAudioManager.releaseIsoCaptureConnection()
        }
    }

    private fun failPreparation(message: String) {
        pendingRecordingFormat = null
        _state.value = RecordingState.Error(message)
        _health.value = RecordingHealth(RecordingHealthLevel.ERROR, message)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    fun pauseSession() {
        if (_state.value !is RecordingState.Recording) return
        AudioEngine.pauseRecording()
        _state.value = RecordingState.Paused
        updateNotification()
    }

    fun resumeSession() {
        if (_state.value !is RecordingState.Paused) return
        AudioEngine.resumeRecording()
        _state.value = RecordingState.Recording
        updateNotification()
    }

    fun stopSession() {
        pendingRecordingFormat = null
        if (_state.value is RecordingState.Preparing || _state.value is RecordingState.Error) {
            stopLiveStream()
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            _state.value = RecordingState.Idle
            _health.value = RecordingHealth.Ready
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (_state.value is RecordingState.Idle || _state.value is RecordingState.Monitoring) {
            // Full stop from monitoring: close the stream.
            if (_state.value is RecordingState.Monitoring) {
                stopLiveStream()
                AudioEngine.close()
                releaseIsoConnectionIfNeeded()
                releaseWakeLock()
                _state.value = RecordingState.Idle
                _elapsedMillis.value = 0L
                _levels.value = StereoLevels(floorLevel, floorLevel)
                _waveformBins.value = emptyWaveform
                _health.value = RecordingHealth.Ready
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        // Recording → stop encoding, keep monitoring.
        val duration = AudioEngine.stopRecording()
        val finalized = finalizeCurrentOutput(duration)
        val sessionComplete = finalized && RecordingSessionStore.completeIfFinalized(this)
        currentSessionId = null
        currentPartIndex = 0
        if (!sessionComplete) {
            stopSessionWithError("Recording stopped, but its file could not be published.", alreadyStopped = true)
            return
        }
        _state.value = RecordingState.Monitoring
        isMonitoringOnly = true
        _elapsedMillis.value = 0L
        _health.value = RecordingHealth(
            RecordingHealthLevel.GOOD,
            "USB signal ready",
            RecordingOutputManager.freeBytes(),
            Long.MAX_VALUE
        )
        safetyStopPending = false
        updateNotification()
    }

    private fun finalizeCurrentOutput(totalDurationMillis: Long): Boolean {
        val output = currentOutput ?: return true
        val partDuration = (totalDurationMillis - currentPartStartedElapsed).coerceAtLeast(0)
        val finalized = RecordingOutputManager.finalize(this, output, partDuration)
        if (finalized) runCatching { RecordingSessionStore.markFinalized(this, output.uri) }
        currentOutput = null
        return finalized
    }

    private fun stopSessionWithError(message: String, alreadyStopped: Boolean = false) {
        stopLiveStream("Mixer audio stopped: $message")
        val duration = if (alreadyStopped) AudioEngine.getElapsedMillis() else AudioEngine.stopRecording()
        val finalized = finalizeCurrentOutput(duration)
        if (finalized) RecordingSessionStore.completeIfFinalized(this)
        AudioEngine.close()
        releaseIsoConnectionIfNeeded()
        releaseWakeLock()
        currentSessionId = null
        currentPartIndex = 0
        isMonitoringOnly = false
        _state.value = RecordingState.Error(message)
        _health.value = RecordingHealth(
            RecordingHealthLevel.ERROR,
            message,
            RecordingOutputManager.freeBytes(),
            0
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        safetyStopPending = false
    }

    private fun handleDeviceDetached() {
        pendingRecordingFormat = null
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused) {
            stopSessionWithError("USB mixer disconnected. Recording finalized safely.")
            return
        }
        stopLiveStream("USB mixer disconnected")
        AudioEngine.close()
        releaseIsoConnectionIfNeeded()
        releaseWakeLock()
        _state.value = RecordingState.Error("USB mixer disconnected")
        _health.value = RecordingHealth(RecordingHealthLevel.ERROR, "USB mixer disconnected")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    fun setDeviceLabel(label: String) {
        deviceLabel = label
    }

    override fun onDestroy() {
        if (::liveStreamController.isInitialized) liveStreamController.release()
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused) {
            val duration = AudioEngine.stopRecording()
            if (finalizeCurrentOutput(duration)) RecordingSessionStore.completeIfFinalized(this)
        }
        if (_state.value !is RecordingState.Idle) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
        }
        releaseWakeLock()
        monitorHandler.removeCallbacksAndMessages(null)
        monitorThread.quitSafely()
        super.onDestroy()
    }

    // --- WakeLock -----------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "djmrec:recording"
        ).apply {
            setReferenceCounted(false)
            acquire(TimeUnit.HOURS.toMillis(6)) // safety timeout; renewed implicitly by continued use
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- Notification --------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, android.app.NotificationManager.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_name))
            .setDescription(getString(R.string.notification_channel_desc))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    /**
     * Returns false instead of crashing when the OS refuses to promote this service to
     * foreground -- observed on-device as `SecurityException: Starting FGS with type
     * microphone ... requires ... and the app must be in the eligible state/exemptions`,
     * thrown from deep inside `Service.startForeground()` itself (i.e. after
     * `ContextCompat.startForegroundService()` on the caller side already returned normally --
     * catching there, as [com.audiopro.djmrec.ui.MainViewModel.startForegroundServiceSafely]
     * does, is not enough). This can happen even with RECORD_AUDIO granted and
     * FOREGROUND_SERVICE_MICROPHONE declared: Android 14+ additionally requires the app to be
     * in a narrow "recently interacted with" eligibility window for a microphone-type FGS
     * specifically, which a device-attach auto-start can miss by the time onStartCommand runs.
     */
    private fun startForegroundNotification(): Boolean {
        val notification = buildNotification()
        // minSdk is 29 (Q), so the ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE overload is
        // always available — no legacy startForeground(id, notification) fallback needed.
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                if (cameraForegroundActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground refused by the OS: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startForeground refused by the OS: ${e.message}")
            false
        }
    }

    private fun updateNotification() {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val isPaused = _state.value is RecordingState.Paused
        val isRecording = _state.value is RecordingState.Recording || isPaused
        val live = _liveState.value
        val elapsed = formatElapsed(_elapsedMillis.value)
        val hasSignal = _levels.value.left.peakDb > -50f || _levels.value.right.peakDb > -50f

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleAction = if (isPaused) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, getString(R.string.action_resume),
                servicePendingIntent(ACTION_RESUME)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, getString(R.string.action_pause),
                servicePendingIntent(ACTION_PAUSE)
            )
        }
        val title = when {
            live.isActive -> "Live on ${live.platform?.label ?: "RTMP"}"
            isPaused -> getString(R.string.notification_title_paused)
            isRecording -> getString(R.string.notification_title_recording, deviceLabel)
            else -> "$deviceLabel connected"
        }
        val text = when {
            live.status == LiveStreamStatus.LIVE -> {
                val mbps = live.bitrateBitsPerSecond / 1_000_000f
                String.format(Locale.US, "Streaming %.1f Mbps%s", mbps, if (isRecording) " | REC $elapsed" else "")
            }
            live.isActive -> live.message
            isRecording -> getString(R.string.notification_text_elapsed, elapsed) +
                if (hasSignal && !isPaused) " | signal" else ""
            else -> if (hasSignal) "USB signal ready" else "Waiting for mixer signal"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (isRecording) {
            builder.addAction(toggleAction)
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop recording",
                    servicePendingIntent(ACTION_STOP)
                )
            )
        }
        if (live.isActive) {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop live",
                    servicePendingIntent(ACTION_STOP_LIVE)
                )
            )
        } else if (!isRecording) {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.action_stop),
                    servicePendingIntent(ACTION_STOP)
                )
            )
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatElapsed(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
