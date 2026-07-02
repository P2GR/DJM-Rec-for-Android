package com.audiopro.djmrec.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.MainActivity
import com.audiopro.djmrec.R
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.StereoLevels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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
        const val ACTION_PAUSE = "com.audiopro.djmrec.action.PAUSE"
        const val ACTION_RESUME = "com.audiopro.djmrec.action.RESUME"
        const val ACTION_STOP = "com.audiopro.djmrec.action.STOP"

        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_SAMPLE_RATE = "extra_sample_rate"
        const val EXTRA_BIT_DEPTH = "extra_bit_depth"
        const val EXTRA_CHANNEL_COUNT = "extra_channel_count"
        const val EXTRA_FORMAT = "extra_format"

        /** [EXTRA_CAPTURE_MODE] value: standard AAudio/AudioRecord path via [EXTRA_DEVICE_ID]. */
        const val CAPTURE_MODE_AAUDIO = 0
        /** [EXTRA_CAPTURE_MODE] value: raw libusb isochronous path via the EXTRA_USB_* extras. */
        const val CAPTURE_MODE_USB_ISO = 1
        /** [EXTRA_CAPTURE_MODE] value: synthetic 12-channel mock source for testing without hardware. */
        const val CAPTURE_MODE_MOCK = 2
        const val EXTRA_CAPTURE_MODE = "extra_capture_mode"

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

        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001
        private const val METER_UPDATE_INTERVAL_MS = 66L // ~15 fps, plenty for a VU meter
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L
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

    private var wakeLock: PowerManager.WakeLock? = null

    // Dedicated urgent-audio-priority thread for pulling meter/elapsed data off the native
    // engine and refreshing the notification — kept separate from the main/UI thread so meter
    // polling never gets starved by UI work, matching the spec's thread-priority requirement.
    private lateinit var monitorThread: HandlerThread
    private lateinit var monitorHandler: Handler

    private var currentFormat: RecordingFormat = RecordingFormat.WAV
    private var currentOutputFile: File? = null
    private var deviceLabel: String = "USB Mixer"
    /** True when the in-progress session opened via [startUsbIsoSession] rather than [startSession]. */
    private var isUsbIsoSession = false

    private val meterRunnable = object : Runnable {
        override fun run() {
            if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused) {
                val raw = AudioEngine.getLevels()
                val clipping = AudioEngine.isClipping()
                _levels.value = StereoLevels(
                    left = ChannelLevel(peakDb = raw[0], rmsDb = raw[1], isClipping = clipping),
                    right = ChannelLevel(peakDb = raw[2], rmsDb = raw[3], isClipping = clipping)
                )
                _elapsedMillis.value = AudioEngine.getElapsedMillis()
                _waveformBins.value = AudioEngine.getWaveformBins()
                monitorHandler.postDelayed(this, METER_UPDATE_INTERVAL_MS)
            }
        }
    }

    private val notificationRunnable = object : Runnable {
        override fun run() {
            if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused) {
                updateNotification()
                monitorHandler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        monitorThread = HandlerThread("AudioMonitorThread", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        monitorHandler = Handler(monitorThread.looper)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)
                val bitDepth = intent.getIntExtra(EXTRA_BIT_DEPTH, 24)
                val formatOrdinal = intent.getIntExtra(EXTRA_FORMAT, RecordingFormat.WAV.nativeValue)
                val format = RecordingFormat.entries.first { it.nativeValue == formatOrdinal }
                val captureMode = intent.getIntExtra(EXTRA_CAPTURE_MODE, CAPTURE_MODE_AAUDIO)

                if (captureMode == CAPTURE_MODE_USB_ISO) {
                    startUsbIsoSession(
                        fd = intent.getIntExtra(EXTRA_USB_FD, -1),
                        interfaceNumber = intent.getIntExtra(EXTRA_USB_INTERFACE, -1),
                        alternateSetting = intent.getIntExtra(EXTRA_USB_ALT_SETTING, -1),
                        endpointAddress = intent.getIntExtra(EXTRA_USB_ENDPOINT, -1),
                        maxPacketSize = intent.getIntExtra(EXTRA_USB_MAX_PACKET_SIZE, -1),
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        subframeSize = intent.getIntExtra(EXTRA_USB_SUBFRAME_SIZE, 4),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        sampleRateHint = sampleRate,
                        format = format
                    )
                } else if (captureMode == CAPTURE_MODE_MOCK) {
                    startMockSession(sampleRate, format)
                } else {
                    val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                    val channelCount = intent.getIntExtra(EXTRA_CHANNEL_COUNT, 2)
                    startSession(deviceId, sampleRate, channelCount, bitDepth, format)
                }
            }

            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> stopSession()
        }
        // Deliberately not sticky: if the process is killed mid-recording we do not want to
        // silently resume capturing without the user re-confirming — safer default for a
        // professional recording tool than risking a corrupt/incomplete file being extended.
        return START_NOT_STICKY
    }

    /** Opens the native engine + starts encoding. Safe to call while already bound. */
    fun startSession(
        audioManagerDeviceId: Int,
        sampleRateHint: Int,
        channelCount: Int,
        bitDepth: Int,
        format: RecordingFormat
    ) {
        if (_state.value is RecordingState.Recording) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = false

        val negotiatedRate = AudioEngine.open(audioManagerDeviceId, sampleRateHint, channelCount, bitDepth)
        if (negotiatedRate <= 0) {
            _state.value = RecordingState.Error("Failed to open exclusive audio stream")
            return
        }

        beginEncodingOrFail(bitDepth, format)
    }

    /**
     * Opens the raw libusb isochronous capture path instead of AAudio -- used for Pioneer
     * multichannel mixers where the desired channel pair (e.g. Master Mix on channels 9/10)
     * sits at a non-zero offset AAudio has no API to select. `fd` must come from a
     * [android.hardware.usb.UsbDeviceConnection] that [UsbAudioManager.openIsoCaptureHandle]
     * is holding open on our behalf; see [releaseIsoConnectionIfNeeded] for the matching teardown.
     */
    fun startUsbIsoSession(
        fd: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        totalChannels: Int,
        subframeSize: Int,
        bitDepth: Int,
        channelOffset: Int,
        sampleRateHint: Int,
        format: RecordingFormat
    ) {
        if (_state.value is RecordingState.Recording) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = true

        if (fd < 0 || interfaceNumber < 0 || endpointAddress < 0 || maxPacketSize <= 0) {
            _state.value = RecordingState.Error("Invalid USB capture parameters")
            releaseIsoConnectionIfNeeded()
            return
        }

        val negotiatedRate = AudioEngine.openUsbIso(
            fd, interfaceNumber, alternateSetting, endpointAddress, maxPacketSize,
            totalChannels, subframeSize, bitDepth, channelOffset, sampleRateHint
        )
        if (negotiatedRate <= 0) {
            _state.value = RecordingState.Error("Failed to open USB isochronous capture")
            releaseIsoConnectionIfNeeded()
            return
        }

        beginEncodingOrFail(bitDepth, format)
    }

    /**
     * Opens a synthetic 12-channel mock audio source for testing without a physical mixer.
     * Channels 9/10 carry distinct tones (1 kHz / 1.2 kHz) so channel extraction and the
     * entire recording pipeline (meter, waveform, encoder) can be verified end-to-end.
     */
    private fun startMockSession(sampleRate: Int, format: RecordingFormat) {
        if (_state.value is RecordingState.Recording) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = false

        val negotiatedRate = AudioEngine.openMock(12, sampleRate)
        if (negotiatedRate <= 0) {
            _state.value = RecordingState.Error("Failed to open mock audio source")
            return
        }

        beginEncodingOrFail(24, format) // DJM-A9 reports 24-bit
    }

    /** Shared tail of both [startSession] and [startUsbIsoSession] once the native capture
     *  source is open: creates the output file, starts the encoder, and flips to Recording. */
    private fun beginEncodingOrFail(bitDepth: Int, format: RecordingFormat) {
        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "DJMRec").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(outputDir, "mix_$timestamp.${format.extension}")
        currentOutputFile = outputFile
        currentFormat = format

        val mp3Bitrate = 320
        val started = AudioEngine.startRecording(outputFile.absolutePath, format.nativeValue, mp3Bitrate)
        if (!started) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            _state.value = RecordingState.Error("Failed to start ${format.name} encoder")
            return
        }

        acquireWakeLock()
        startForegroundNotification()
        monitorHandler.post(meterRunnable)
        monitorHandler.post(notificationRunnable)
        _state.value = RecordingState.Recording
    }

    /** Closes the [UsbAudioManager] connection backing native libusb capture, if this session used it. */
    private fun releaseIsoConnectionIfNeeded() {
        if (isUsbIsoSession) {
            (application as DjmRecApplication).usbAudioManager.releaseIsoCaptureConnection()
        }
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
        if (_state.value is RecordingState.Idle) return
        AudioEngine.stopRecording()
        AudioEngine.close()
        releaseIsoConnectionIfNeeded()
        releaseWakeLock()
        _state.value = RecordingState.Idle
        _elapsedMillis.value = 0L
        _levels.value = StereoLevels(floorLevel, floorLevel)
        _waveformBins.value = emptyWaveform
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setDeviceLabel(label: String) {
        deviceLabel = label
    }

    override fun onDestroy() {
        if (_state.value !is RecordingState.Idle) {
            AudioEngine.stopRecording()
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
        }
        releaseWakeLock()
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

    private fun startForegroundNotification() {
        val notification = buildNotification()
        // minSdk is 29 (Q), so the ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE overload is
        // always available — no legacy startForeground(id, notification) fallback needed.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun updateNotification() {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPaused = _state.value is RecordingState.Paused
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
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop),
            servicePendingIntent(ACTION_STOP)
        )

        val title = if (isPaused) {
            getString(R.string.notification_title_paused)
        } else {
            getString(R.string.notification_title_recording, deviceLabel)
        }
        val text = getString(R.string.notification_text_elapsed, elapsed) +
            if (hasSignal && !isPaused) " • ●" else ""

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(toggleAction)
            .addAction(stopAction)
            .build()
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
