package com.audiopro.djmrec.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.RecordingHealth
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.service.RecordingService
import com.audiopro.djmrec.streaming.LiveStreamConfig
import com.audiopro.djmrec.streaming.LiveStreamState
import com.audiopro.djmrec.streaming.LiveStreamStatus
import com.audiopro.djmrec.streaming.LivePlatform
import com.audiopro.djmrec.streaming.StreamSetupState
import com.audiopro.djmrec.streaming.StreamSetupStatus
import com.audiopro.djmrec.streaming.StreamingSetupRepository
import com.audiopro.djmrec.streaming.YouTubePrivacy
import com.audiopro.djmrec.streaming.YouTubeBroadcastState
import com.audiopro.djmrec.streaming.YouTubeBroadcastStatus
import com.audiopro.djmrec.streaming.YouTubeFinishResult
import com.audiopro.djmrec.streaming.YouTubeLiveSession
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import com.audiopro.djmrec.usb.UsbAudioManager
import com.audiopro.djmrec.usb.RootUsbHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Wires the USB device stream, the bound [RecordingService], and the Compose UI together.
 * Transport commands are always sent as service `Intent`s (works whether or not the bind has
 * completed yet); the bind is only used to *observe* the service's StateFlows.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS_NAME = "settings"
        private const val KEY_ROOT_USB_MODE = "root_usb_mode"
        private const val KEY_USB_CHANNEL_OFFSET = "usb_channel_offset"
        private const val KEY_FORCE_ANDROID_CAPTURE = "force_android_capture"
        private const val KEY_DJMREC_PORT_MODE = "djmrec_port_mode"
        private const val KEY_WAVEFORM_ENABLED = "waveform_enabled"
    }

    private val usbAudioManager = (application as DjmRecApplication).usbAudioManager
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val deviceState: StateFlow<UsbAudioDeviceInfo?> = usbAudioManager.deviceState
    val usbInputs = usbAudioManager.inputs
    val connectionNotice = usbAudioManager.connectionNotice
    fun refreshInputs() = usbAudioManager.refreshInputs()

    fun selectInput(deviceName: String) {
        if (saving.value || liveStreamState.value.isActive ||
            _recordingState.value is RecordingState.Recording || _recordingState.value is RecordingState.Paused ||
            _recordingState.value is RecordingState.Preparing || deviceState.value?.deviceName == deviceName) return
        val service = boundService ?: return
        _recordingState.value = RecordingState.Preparing
        viewModelScope.launch {
            if (service.state.value is RecordingState.Monitoring || service.state.value is RecordingState.Error) {
                sendCommand(RecordingService.ACTION_STOP)
                if (withTimeoutOrNull(5_000L) { service.state.first { it is RecordingState.Idle } } == null) {
                    _recordingState.value = RecordingState.Error("Input change timed out. Reconnect your device.")
                    return@launch
                }
            }
            _recordingState.value = RecordingState.Idle
            if (!usbAudioManager.selectDevice(deviceName))
                _recordingState.value = RecordingState.Error("Input unavailable. Refresh the device list.")
        }
    }

    private fun captureChannelOffset(device: UsbAudioDeviceInfo): Int =
        _usbChannelOffset.value.takeIf { it >= 0 }
            ?: device.allInOneProfile?.recordChannelOffset
            ?: if (device.pioneerMixerProfile != null) UsbAudioManager.AUTO_CHANNEL_OFFSET else 0
    private val sessionEvents = (application as DjmRecApplication).sessionEvents
    val lastSaved = sessionEvents.lastSaved.asStateFlow()
    val markerCount = sessionEvents.markerCount.asStateFlow()
    val autoArm = MutableStateFlow(prefs.getBoolean("auto_arm", true))
    val keepScreenOn = MutableStateFlow(prefs.getBoolean("keep_screen_on", false))
    val smoothWaveform = MutableStateFlow(prefs.getBoolean("smooth_waveform", true))
    val confirmStop = MutableStateFlow(prefs.getBoolean("confirm_stop", true))

    fun setAutoArm(value: Boolean) { prefs.edit().putBoolean("auto_arm", value).apply(); autoArm.value = value; if (value) ensureLiveMonitoring() }
    fun setKeepScreenOn(value: Boolean) { prefs.edit().putBoolean("keep_screen_on", value).apply(); keepScreenOn.value = value }
    fun setSmoothWaveform(value: Boolean) { prefs.edit().putBoolean("smooth_waveform", value).apply(); smoothWaveform.value = value }
    fun setConfirmStop(value: Boolean) { prefs.edit().putBoolean("confirm_stop", value).apply(); confirmStop.value = value }
    fun dismissSavedRecording() { sessionEvents.lastSaved.value = null }
    fun addTrackMarker() = sendCommand(RecordingService.ACTION_MARK_TRACK)
    fun stopAndClose() = sendCommand(RecordingService.ACTION_STOP_ALL)


    private val floorLevel = ChannelLevel(peakDb = -60f, rmsDb = -60f, isClipping = false)

    val saving = MutableStateFlow(false)
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _levels = MutableStateFlow(StereoLevels(floorLevel, floorLevel))
    val levels: StateFlow<StereoLevels> = _levels.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    private val emptyWaveform = FloatArray(0)
    private val _waveformBins = MutableStateFlow(emptyWaveform)
    val waveformBins: StateFlow<FloatArray> = _waveformBins.asStateFlow()

    private val _recordingHealth = MutableStateFlow(RecordingHealth.Ready)
    val recordingHealth: StateFlow<RecordingHealth> = _recordingHealth.asStateFlow()

    private val _liveStreamState = MutableStateFlow(LiveStreamState())
    val liveStreamState: StateFlow<LiveStreamState> = _liveStreamState.asStateFlow()

    private val youtubeCoordinator = (application as DjmRecApplication).youtubeCoordinator
    val streamSetupState = youtubeCoordinator.streamSetupState
    val youtubeBroadcastState = youtubeCoordinator.youtubeBroadcastState
    val liveStreamKey = androidx.compose.runtime.mutableStateOf("")

    private val _waveformEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_WAVEFORM_ENABLED, true)
    )
    val waveformEnabled: StateFlow<Boolean> = _waveformEnabled.asStateFlow()

    private val _recordingGainDb = MutableStateFlow(prefs.getInt("recording_gain_db", 12).coerceIn(-12, 24))
    val recordingGainDb: StateFlow<Int> = _recordingGainDb.asStateFlow()

    private val _selectedFormat = MutableStateFlow(
        RecordingFormat.entries.firstOrNull { it.name == prefs.getString("recording_format", "WAV") } ?: RecordingFormat.WAV)
    val selectedFormat: StateFlow<RecordingFormat> = _selectedFormat.asStateFlow()
    val availableFormats: List<RecordingFormat> = RecordingFormat.entries

    private val _rootUsbMode = MutableStateFlow(false)
    val rootUsbMode: StateFlow<Boolean> = _rootUsbMode.asStateFlow()

    private val _usbChannelOffset = MutableStateFlow(
        prefs.getInt(KEY_USB_CHANNEL_OFFSET, UsbAudioManager.AUTO_CHANNEL_OFFSET)
    )
    val usbChannelOffset: StateFlow<Int> = _usbChannelOffset.asStateFlow()

    private val _forceAndroidCapture = MutableStateFlow(false)
    val forceAndroidCapture: StateFlow<Boolean> = _forceAndroidCapture.asStateFlow()

    private val _djmrecPortMode = MutableStateFlow(false)
    val djmrecPortMode: StateFlow<Boolean> = _djmrecPortMode.asStateFlow()

    @SuppressLint("StaticFieldLeak")
    private var boundService: RecordingService? = null
    private var isBound = false
    private var livePreview: SurfaceView? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as RecordingService.LocalBinder).getService()
            boundService = service
            isBound = true
            _recordingState.value = service.state.value
            service.setWaveformEnabled(_waveformEnabled.value)
            service.setRecordingGainDb(_recordingGainDb.value)
            viewModelScope.launch { service.saving.collect { saving.value = it } }
            viewModelScope.launch { service.state.collect { _recordingState.value = it } }
            viewModelScope.launch { service.levels.collect { _levels.value = it } }
            viewModelScope.launch { service.elapsedMillis.collect { _elapsedMillis.value = it } }
            viewModelScope.launch { service.waveformBins.collect { _waveformBins.value = it } }
            viewModelScope.launch { service.health.collect { _recordingHealth.value = it } }
            viewModelScope.launch { service.liveState.collect { _liveStreamState.value = it } }
            livePreview?.let(service::attachLivePreview)
            ensureLiveMonitoring()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
        }
    }

    init {
        val context = getApplication<Application>()
        prefs.edit()
            .remove(KEY_ROOT_USB_MODE)
            .remove(KEY_FORCE_ANDROID_CAPTURE)
            .remove(KEY_DJMREC_PORT_MODE)
            .apply()
        usbAudioManager.setRootModeEnabled(_rootUsbMode.value)
        context.bindService(
            Intent(context, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        viewModelScope.launch {
            var activeDeviceKey: String? = null
            deviceState.collect { device ->
                if (device == null) {
                    activeDeviceKey = null
                    return@collect
                }
                val key = "${device.deviceName}:${device.vendorId}:${device.productId}"
                if (key == activeDeviceKey) return@collect
                activeDeviceKey = key
                val pairKey = "channel_pair_${device.vendorId}_${device.productId}"
                val storedPair = prefs.getInt(pairKey, UsbAudioManager.AUTO_CHANNEL_OFFSET)
                _usbChannelOffset.value = storedPair.takeIf { it >= 0 && it % 2 == 0 && it + 1 < device.channelCount }
                    ?: UsbAudioManager.AUTO_CHANNEL_OFFSET
                delay(250L)
                if (_recordingState.value is RecordingState.Idle ||
                    _recordingState.value is RecordingState.Error) {
                    ensureLiveMonitoring()
                }
            }
        }
    }

    fun selectFormat(format: RecordingFormat) {
        if ((_recordingState.value is RecordingState.Idle ||
                _recordingState.value is RecordingState.Monitoring ||
                _recordingState.value is RecordingState.Error) &&
            format in availableFormats) {
            _selectedFormat.value = format
            prefs.edit().putString("recording_format", format.name).apply()
        }
    }

    fun setRecordingGainDb(gainDb: Int) {
        if (saving.value) return
        if (_recordingState.value is RecordingState.Recording ||
            _recordingState.value is RecordingState.Paused ||
            _recordingState.value is RecordingState.Preparing) return
        val value = gainDb.coerceIn(-12, 24)
        _recordingGainDb.value = value
        prefs.edit().putInt("recording_gain_db", value).apply()
        boundService?.setRecordingGainDb(value)
    }

    fun setWaveformEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAVEFORM_ENABLED, enabled).apply()
        _waveformEnabled.value = enabled
        if (!enabled) _waveformBins.value = emptyWaveform
        boundService?.setWaveformEnabled(enabled)
    }

    fun rescanUsbDevices() {
        if (saving.value) return
        if (_recordingState.value is RecordingState.Recording ||
            _recordingState.value is RecordingState.Paused ||
            _recordingState.value is RecordingState.Preparing) return
        usbAudioManager.scanForConnectedMixer()
        ensureLiveMonitoring()
    }

    fun ensureLiveMonitoring() {
        if (!autoArm.value || sessionEvents.closeRequested.value || boundService == null) return
        val context = getApplication<Application>()
        if (deviceState.value != null &&
            (_recordingState.value is RecordingState.Idle ||
                _recordingState.value is RecordingState.Error)) {
            startMonitoringDevice(context)
        }
    }

    fun setRootUsbModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROOT_USB_MODE, enabled).apply()
        _rootUsbMode.value = enabled
        usbAudioManager.setRootModeEnabled(enabled)
        if (enabled) {
            usbAudioManager.scanForConnectedMixer("root-mode-enabled-rescan")
        }
    }

    fun setUsbChannelOffset(offset: Int) {
        if (saving.value || liveStreamState.value.isActive) return
        if (_recordingState.value is RecordingState.Recording ||
            _recordingState.value is RecordingState.Paused ||
            _recordingState.value is RecordingState.Preparing) return
        val channels = deviceState.value?.channelCount ?: return
        if (offset >= 0 && (offset % 2 != 0 || offset + 1 >= channels)) return
        val sanitized = if (offset < 0) UsbAudioManager.AUTO_CHANNEL_OFFSET else offset
        if (sanitized == _usbChannelOffset.value) return
        val device = deviceState.value ?: return
        prefs.edit().putInt("channel_pair_${device.vendorId}_${device.productId}", sanitized).apply()
        _usbChannelOffset.value = sanitized

        // The offset is only read when the native capture session opens (baked into the
        // service Intent), so an already-running monitor stream won't pick up the new pair on
        // its own. Restart it here so the VU meter reflects the new pair immediately -- this is
        // the whole point of exposing the picker: audition pairs against live audio, the same
        // way the Windows Setting Utility lets you flip MIX/REC OUT between USB pairs and watch
        // levels move. Never auto-restart out of Recording/Paused -- that would kill a take.
        if (_recordingState.value is RecordingState.Monitoring) {
            val context = getApplication<Application>()
            _recordingState.value = RecordingState.Preparing
            viewModelScope.launch {
                sendCommand(RecordingService.ACTION_STOP)
                val stopped = withTimeoutOrNull(5_000L) {
                    boundService?.state?.first { it is RecordingState.Idle || it is RecordingState.Error }
                }
                if (stopped == null) {
                    _recordingState.value = RecordingState.Error("Could not change the USB pair. Stop capture and reconnect the mixer.")
                } else {
                    _recordingState.value = stopped
                    startMonitoringDevice(context)
                }
            }
        }
    }

    fun setForceAndroidCapture(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_ANDROID_CAPTURE, enabled).apply()
        _forceAndroidCapture.value = enabled
    }

    private val _otgStatus = MutableStateFlow<RootUsbHostController.OtgStatus?>(null)
    val otgStatus: StateFlow<RootUsbHostController.OtgStatus?> = _otgStatus.asStateFlow()

    fun setDjmrecPortMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DJMREC_PORT_MODE, enabled).apply()
        _djmrecPortMode.value = enabled
        // The top digital send/return port is ordinary USB audio. Root is neither required nor
        // helpful; it must remain independent from the rear multi-channel USB-B capture path.
        _otgStatus.value = null
    }

    fun checkOtgAndWarn() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                RootUsbHostController.checkOtgStatus()
            }
            _otgStatus.value = status
            Log.i(TAG, "OTG status: enabled=${status.enabled} suggestions=${status.suggestions}")
        }
    }

    fun dismissOtgWarning() {
        _otgStatus.value = null
    }

    fun openOtgSettings(context: Context) {
        val specificIntents = listOf(
            "com.android.settings.Settings\$ConnectedDeviceDashboardActivity",
            "com.android.settings.connecteddevice.ConnectedDeviceDashboardActivity",
            "com.android.settings.connecteddevice.usb.UsbDetailsActivity",
        )
        for (className in specificIntents) {
            try {
                val intent = Intent().apply {
                    setClassName("com.android.settings", className)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) { }
        }
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) { }
    }

    fun startRecording() {
        val context = getApplication<Application>()

        if (_recordingState.value is RecordingState.Preparing) {
            startServiceSafely(
                context,
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)
            )
            return
        }

        // If already monitoring, begin encoding to file.
        if (_recordingState.value is RecordingState.Monitoring) {
            startServiceSafely(
                context,
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)
            )
            return
        }

        // DJM-REC-equivalent path: the top digital send/return USB port exposes stereo audio.
        if (_djmrecPortMode.value) {
            tryDjmrecPortCapture(context)
            return
        }

        val device = deviceState.value ?: run {
            if (_rootUsbMode.value && !_djmrecPortMode.value && startRootAlsaRecording(context, null)) {
                return
            }
            usbAudioManager.scanForConnectedMixer("record-button-rescan")
            return
        }

        if (_rootUsbMode.value && !_djmrecPortMode.value && startRootAlsaRecording(context, device)) {
            return
        }

        val sampleRate = device.preferredSampleRate

        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            val androidCapture = device.requiresIsoCapture && _forceAndroidCapture.value
            val captureBitDepth = if (androidCapture) 16 else device.bitResolution
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleRate)
            putExtra(RecordingService.EXTRA_BIT_DEPTH, captureBitDepth)
            putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)

            val handle = if (device.requiresIsoCapture && !androidCapture) {
                usbAudioManager.openIsoCaptureHandle()
            } else {
                null
            }
            if (handle == null && ((device.requiresIsoCapture && !androidCapture) || device.audioManagerDeviceId < 0)) {
                _recordingState.value = RecordingState.Error("Cannot open this USB input. Reconnect the mixer and rescan; check USB permission.")
                return
            }
            if (handle != null) {
                putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_USB_ISO)
                putExtra(RecordingService.EXTRA_USB_FD, handle.fd)
                putExtra(RecordingService.EXTRA_USB_INTERFACE, handle.interfaceNumber)
                putExtra(RecordingService.EXTRA_USB_ALT_SETTING, handle.alternateSetting)
                putExtra(RecordingService.EXTRA_USB_ENDPOINT, handle.endpointAddress)
                putExtra(RecordingService.EXTRA_USB_MAX_PACKET_SIZE, handle.maxPacketSize)
                putExtra(RecordingService.EXTRA_USB_TOTAL_CHANNELS, handle.totalChannels)
                putExtra(RecordingService.EXTRA_USB_SUBFRAME_SIZE, handle.subframeSize)
                putExtra(RecordingService.EXTRA_USB_CHANNEL_OFFSET, device?.let(::captureChannelOffset) ?: 0)
                putExtra(RecordingService.EXTRA_USB_CLOCK_CONTROL_INTERFACE, handle.clockControlInterfaceNumber)
                putExtra(RecordingService.EXTRA_USB_CLOCK_SOURCE_ID, handle.clockSourceId)
                putExtra(RecordingService.EXTRA_USB_CLOCK_FREQUENCY_SETTABLE, handle.clockSupportsFrequencySet)
                putExtra(RecordingService.EXTRA_USB_FEEDBACK_ENDPOINT, handle.feedbackEndpointAddress)
                putExtra(RecordingService.EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE, handle.feedbackMaxPacketSize)
                putExtra(RecordingService.EXTRA_USB_VENDOR_ID, handle.vendorId)
                putExtra(RecordingService.EXTRA_USB_PRODUCT_ID, handle.productId)
                putExtra(RecordingService.EXTRA_USB_RAW_DESCRIPTORS, handle.rawDescriptors)
            } else {
                putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_AAUDIO)
                putExtra(RecordingService.EXTRA_DEVICE_ID, device.audioManagerDeviceId)
                putExtra(RecordingService.EXTRA_CHANNEL_COUNT, if (device.isPioneer) 2 else device.channelCount)
            }
        }
        val hadIsoHandle = intent.hasExtra(RecordingService.EXTRA_USB_FD)
        if (startForegroundServiceSafely(context, intent, hadIsoHandle)) {
            boundService?.setDeviceLabel(device.productName)
        }
    }

    /** Opens the audio stream for live monitoring (meters + waveform) without writing a file. */
    private fun startMonitoringDevice(context: Context) {
        if (sessionEvents.closeRequested.value) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (_recordingState.value !is RecordingState.Idle && _recordingState.value !is RecordingState.Error) return
        val device = deviceState.value ?: return
        _recordingState.value = RecordingState.Preparing
        val sampleRate = device.preferredSampleRate
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_MONITOR
            val androidCapture = device.requiresIsoCapture && _forceAndroidCapture.value
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleRate)
            putExtra(RecordingService.EXTRA_BIT_DEPTH, if (androidCapture) 16 else device.bitResolution)

            val handle = if (device.requiresIsoCapture && !androidCapture) {
                usbAudioManager.openIsoCaptureHandle()
            } else {
                null
            }
            if (handle == null && ((device.requiresIsoCapture && !androidCapture) || device.audioManagerDeviceId < 0)) {
                _recordingState.value = RecordingState.Error("Cannot open this USB input. Reconnect the mixer and rescan; check USB permission.")
                return
            }
            if (handle != null) {
                putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_USB_ISO)
                putExtra(RecordingService.EXTRA_USB_FD, handle.fd)
                putExtra(RecordingService.EXTRA_USB_INTERFACE, handle.interfaceNumber)
                putExtra(RecordingService.EXTRA_USB_ALT_SETTING, handle.alternateSetting)
                putExtra(RecordingService.EXTRA_USB_ENDPOINT, handle.endpointAddress)
                putExtra(RecordingService.EXTRA_USB_MAX_PACKET_SIZE, handle.maxPacketSize)
                putExtra(RecordingService.EXTRA_USB_TOTAL_CHANNELS, handle.totalChannels)
                putExtra(RecordingService.EXTRA_USB_SUBFRAME_SIZE, handle.subframeSize)
                putExtra(RecordingService.EXTRA_USB_CHANNEL_OFFSET, device?.let(::captureChannelOffset) ?: 0)
                putExtra(RecordingService.EXTRA_USB_CLOCK_CONTROL_INTERFACE, handle.clockControlInterfaceNumber)
                putExtra(RecordingService.EXTRA_USB_CLOCK_SOURCE_ID, handle.clockSourceId)
                putExtra(RecordingService.EXTRA_USB_CLOCK_FREQUENCY_SETTABLE, handle.clockSupportsFrequencySet)
                putExtra(RecordingService.EXTRA_USB_FEEDBACK_ENDPOINT, handle.feedbackEndpointAddress)
                putExtra(RecordingService.EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE, handle.feedbackMaxPacketSize)
                putExtra(RecordingService.EXTRA_USB_VENDOR_ID, handle.vendorId)
                putExtra(RecordingService.EXTRA_USB_PRODUCT_ID, handle.productId)
                putExtra(RecordingService.EXTRA_USB_RAW_DESCRIPTORS, handle.rawDescriptors)
            } else {
                putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_AAUDIO)
                putExtra(RecordingService.EXTRA_DEVICE_ID, device.audioManagerDeviceId)
                putExtra(RecordingService.EXTRA_CHANNEL_COUNT, if (device.isPioneer) 2 else device.channelCount)
            }
        }
        val hadIsoHandle = intent.hasExtra(RecordingService.EXTRA_USB_FD)
        if (startForegroundServiceSafely(context, intent, hadIsoHandle)) {
            boundService?.setDeviceLabel(device.productName)
            Log.i(TAG, "USB attached: auto-starting live monitor for ${device.productName}")
        }
    }

    private fun startRootAlsaRecording(context: Context, device: UsbAudioDeviceInfo?): Boolean {
        val prepareResult = RootUsbHostController.prepareAlsaCaptureAccess()
        Log.i(
            TAG,
            "root ALSA prepare exit=${prepareResult.exitCode} timedOut=${prepareResult.timedOut}\n" +
                prepareResult.output
        )

        val candidates = RootUsbHostController.findAlsaCaptureDevices(prepareResult.output)
        Log.i(TAG, "root ALSA capture candidates=${candidates.joinToString()}")
        val rootAlsaDevice = candidates.firstOrNull()
        if (rootAlsaDevice == null) {
            _recordingState.value = RecordingState.Error("No root ALSA capture device found")
            return false
        }

        val sampleRate = when {
            device?.negotiatedSampleRate != null && device.negotiatedSampleRate > 0 -> device.negotiatedSampleRate
            device != null && 48000 in device.supportedSampleRates -> 48000
            else -> 48000
        }
        val totalChannels = device?.channelCount?.takeIf { it >= 2 } ?: 2
        val bitDepth = device?.bitResolution?.takeIf { it == 24 || it == 32 } ?: 16

        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_ROOT_ALSA)
            putExtra(RecordingService.EXTRA_ALSA_CARD, rootAlsaDevice.card)
            putExtra(RecordingService.EXTRA_ALSA_DEVICE, rootAlsaDevice.device)
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleRate)
            putExtra(RecordingService.EXTRA_BIT_DEPTH, bitDepth)
            putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)
            putExtra(RecordingService.EXTRA_USB_TOTAL_CHANNELS, totalChannels)
            putExtra(RecordingService.EXTRA_USB_CHANNEL_OFFSET, device?.let(::captureChannelOffset) ?: 0)
        }
        if (!startForegroundServiceSafely(context, intent)) return false
        boundService?.setDeviceLabel("Root ALSA ${rootAlsaDevice.description}")
        Log.i(TAG, "starting root ALSA capture from ${rootAlsaDevice.path}: ${rootAlsaDevice.description}")
        return true
    }

    /**
     * Top digital send/return port capture: scan for USB audio input, then use AAudio.
     * This is the physical port used by the iOS DJM-REC application; it is not a rear USB-B port.
     */
    private fun tryDjmrecPortCapture(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val usbInputs = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE }
        Log.i(TAG, "DJM REC port: ${usbInputs.size} USB audio input(s) found via AudioManager")
        usbInputs.forEach { info ->
            Log.i(TAG, "  id=${info.id} product=${info.productName} rates=${info.sampleRates.toList()}")
        }
        val usbDevice = usbInputs.firstOrNull()
        if (usbDevice == null) {
            Log.w(TAG, "DJM REC port: no USB audio input in AudioManager")
            _recordingState.value = RecordingState.Error("No USB audio input found on the top DJM-REC port")
            return false
        }
        val sampleRate = usbDevice.sampleRates.firstOrNull { it == 48000 }
            ?: usbDevice.sampleRates.firstOrNull() ?: 48000
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_AAUDIO)
            putExtra(RecordingService.EXTRA_DEVICE_ID, usbDevice.id)
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleRate)
            putExtra(RecordingService.EXTRA_BIT_DEPTH, 24)
            putExtra(RecordingService.EXTRA_CHANNEL_COUNT, 2)
            putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)
        }
        if (!startForegroundServiceSafely(context, intent)) return false
        boundService?.setDeviceLabel("DJM REC Port (${usbDevice.productName})")
        Log.i(TAG, "DJM REC port: starting AAudio capture deviceId=${usbDevice.id} @ ${sampleRate}Hz")
        return true
    }

    fun pauseRecording() = sendCommand(RecordingService.ACTION_PAUSE)
    fun resumeRecording() = sendCommand(RecordingService.ACTION_RESUME)
    fun stopRecording() = sendCommand(RecordingService.ACTION_STOP)

    fun startLiveStream(config: LiveStreamConfig) {
        val context = getApplication<Application>()
        _liveStreamState.value = LiveStreamState(
            status = LiveStreamStatus.PREPARING,
            message = "Starting ${config.platform.label} encoders",
            platform = config.platform,
            videoMode = config.videoMode
        )
        context.startService(
            Intent(context, RecordingService::class.java)
                .setAction(RecordingService.ACTION_START_LIVE)
                .putExtra(RecordingService.EXTRA_LIVE_PLATFORM, config.platform.name)
                .putExtra(RecordingService.EXTRA_LIVE_SERVER_URL, config.serverUrl)
                .putExtra(RecordingService.EXTRA_LIVE_STREAM_KEY, config.streamKey)
                .putExtra(RecordingService.EXTRA_LIVE_VIDEO_MODE, config.videoMode.name)
                .putExtra(RecordingService.EXTRA_LIVE_PORTRAIT, config.portrait)
                .putExtra(RecordingService.EXTRA_LIVE_ARTWORK_URI, config.artworkUri)
                .putExtra(RecordingService.EXTRA_LIVE_AUDIO_BITRATE, config.audioBitrate)
        )
    }

    fun stopLiveStream() {
        sendCommand(RecordingService.ACTION_STOP_LIVE)
        youtubeCoordinator.finishYouTubeSession()
    }

    fun prepareYouTubeDestination(accessToken: String, title: String, privacy: YouTubePrivacy) =
        youtubeCoordinator.prepareYouTubeDestination(accessToken, title, privacy)
    fun setStreamSetupError(platform: LivePlatform, message: String) = youtubeCoordinator.setStreamSetupError(platform, message)
    fun consumeStreamCredentials() = youtubeCoordinator.consumeStreamCredentials()
    fun cancelStreamSetup() = youtubeCoordinator.cancelStreamSetup()

    fun attachLivePreview(surfaceView: SurfaceView) {
        livePreview = surfaceView
        boundService?.attachLivePreview(surfaceView)
    }

    fun detachLivePreview() {
        boundService?.detachLivePreview()
        livePreview = null
    }

    fun switchLiveCamera() = boundService?.switchLiveCamera()

    private fun sendCommand(action: String) {
        val context = getApplication<Application>()
        startServiceSafely(context, Intent(context, RecordingService::class.java).setAction(action))
    }

    /**
     * Same background-start restriction as [startForegroundServiceSafely], but for plain
     * `startService()`: observed crashing with `BackgroundServiceStartNotAllowedException` when
     * a USB detach (`usbDeviceReceiver`, see `UsbAudioManager`) triggers an ACTION_STOP a few
     * minutes after the user last touched the app -- a background `BroadcastReceiver` doesn't
     * count as enough "foreground-ness" for Android to allow it. Whatever command this was
     * carrying (start/pause/resume/stop) is either already moot (service already gone) or not
     * actionable by the user right now (they're not looking at the app); either way, this only
     * needs to not crash it.
     */
    private fun startServiceSafely(context: Context, intent: Intent) {
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startService(${intent.action}) refused by the OS: ${e.message}")
        }
    }

    /**
     * Android 12+ refuses `startForegroundService()` outright (throwing
     * `ForegroundServiceStartNotAllowedException`, an `IllegalStateException`) when it decides
     * the app isn't in a state that justifies it -- observed on-device as an intermittent crash
     * right when the record button (or an auto-restart after a USB channel-pair change) tried to
     * start the service. There's no reliable way to predict the OS's call in advance, so this
     * just makes the failure a visible error instead of a fatal crash. `hadIsoHandle` releases
     * the just-opened libusb connection on failure -- otherwise it leaks open (never handed to a
     * service that would close it) and blocks the next attempt from claiming the interface.
     */
    private fun startForegroundServiceSafely(
        context: Context,
        intent: Intent,
        hadIsoHandle: Boolean = false
    ): Boolean {
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startForegroundService refused by the OS: ${e.message}")
            if (hadIsoHandle) usbAudioManager.releaseIsoCaptureConnection()
            _recordingState.value = RecordingState.Error(
                "Android blocked starting the recording service -- try pressing record again"
            )
            false
        }
    }

    override fun onCleared() {
        detachLivePreview()
        if (isBound) {
            getApplication<Application>().unbindService(connection)
            isBound = false
        }
        boundService = null
        super.onCleared()
    }
}
