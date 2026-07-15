package com.audiopro.djmrec.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.service.RecordingService
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import com.audiopro.djmrec.usb.UsbAudioManager
import com.audiopro.djmrec.usb.RootUsbHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    }

    private val usbAudioManager = (application as DjmRecApplication).usbAudioManager
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val deviceState: StateFlow<UsbAudioDeviceInfo?> = usbAudioManager.deviceState

    private val floorLevel = ChannelLevel(peakDb = -60f, rmsDb = -60f, isClipping = false)

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _levels = MutableStateFlow(StereoLevels(floorLevel, floorLevel))
    val levels: StateFlow<StereoLevels> = _levels.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    private val emptyWaveform = FloatArray(0)
    private val _waveformBins = MutableStateFlow(emptyWaveform)
    val waveformBins: StateFlow<FloatArray> = _waveformBins.asStateFlow()

    private val _selectedFormat = MutableStateFlow(RecordingFormat.WAV)
    val selectedFormat: StateFlow<RecordingFormat> = _selectedFormat.asStateFlow()
    val availableFormats: List<RecordingFormat> = RecordingFormat.entries.filter {
        it != RecordingFormat.MP3 || AudioEngine.isMp3EncodingAvailable()
    }

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

    private var boundService: RecordingService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as RecordingService.LocalBinder).getService()
            boundService = service
            isBound = true
            viewModelScope.launch { service.state.collect { _recordingState.value = it } }
            viewModelScope.launch { service.levels.collect { _levels.value = it } }
            viewModelScope.launch { service.elapsedMillis.collect { _elapsedMillis.value = it } }
            viewModelScope.launch { service.waveformBins.collect { _waveformBins.value = it } }
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
                    if (activeDeviceKey != null) {
                        sendCommand(RecordingService.ACTION_STOP)
                        delay(150L)
                        sendCommand(RecordingService.ACTION_STOP)
                    }
                    activeDeviceKey = null
                    return@collect
                }
                val key = "${device.deviceName}:${device.vendorId}:${device.productId}"
                if (key == activeDeviceKey) return@collect
                activeDeviceKey = key
                delay(250L)
                if (_recordingState.value is RecordingState.Idle ||
                    _recordingState.value is RecordingState.Error) {
                    startMonitoringDevice(context)
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
        }
    }

    fun rescanUsbDevices() {
        usbAudioManager.scanForConnectedMixer()
    }

    fun ensureLiveMonitoring() {
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
        val sanitized = if (offset < 0) UsbAudioManager.AUTO_CHANNEL_OFFSET else offset
        prefs.edit().putInt(KEY_USB_CHANNEL_OFFSET, sanitized).apply()
        _usbChannelOffset.value = sanitized
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

        // If already monitoring, begin encoding to file.
        if (_recordingState.value is RecordingState.Monitoring) {
            context.startService(
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

        val sampleRate = when {
            device.negotiatedSampleRate > 0 -> device.negotiatedSampleRate
            48000 in device.supportedSampleRates -> 48000
            else -> device.supportedSampleRates.firstOrNull() ?: 48000
        }

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
            if (handle != null) {
                putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_USB_ISO)
                putExtra(RecordingService.EXTRA_USB_FD, handle.fd)
                putExtra(RecordingService.EXTRA_USB_INTERFACE, handle.interfaceNumber)
                putExtra(RecordingService.EXTRA_USB_ALT_SETTING, handle.alternateSetting)
                putExtra(RecordingService.EXTRA_USB_ENDPOINT, handle.endpointAddress)
                putExtra(RecordingService.EXTRA_USB_MAX_PACKET_SIZE, handle.maxPacketSize)
                putExtra(RecordingService.EXTRA_USB_TOTAL_CHANNELS, handle.totalChannels)
                putExtra(RecordingService.EXTRA_USB_SUBFRAME_SIZE, handle.subframeSize)
                putExtra(RecordingService.EXTRA_USB_CHANNEL_OFFSET, _usbChannelOffset.value)
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
        ContextCompat.startForegroundService(context, intent)
        boundService?.setDeviceLabel(device.productName)
    }

    /** Opens the audio stream for live monitoring (meters + waveform) without writing a file. */
    private fun startMonitoringDevice(context: Context) {
        val device = deviceState.value ?: return
        val sampleRate = when {
            device.negotiatedSampleRate > 0 -> device.negotiatedSampleRate
            48000 in device.supportedSampleRates -> 48000
            else -> device.supportedSampleRates.firstOrNull() ?: 48000
        }
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_MONITOR
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleRate)
            putExtra(RecordingService.EXTRA_BIT_DEPTH, device.bitResolution)

            val handle = if (device.requiresIsoCapture) {
                usbAudioManager.openIsoCaptureHandle()
            } else {
                null
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
                putExtra(RecordingService.EXTRA_USB_CHANNEL_OFFSET, _usbChannelOffset.value)
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
        ContextCompat.startForegroundService(context, intent)
        boundService?.setDeviceLabel(device.productName)
        Log.i(TAG, "USB attached: auto-starting live monitor for ${device.productName}")
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
            putExtra(RecordingService.EXTRA_USB_CHANNEL_OFFSET, _usbChannelOffset.value)
        }
        ContextCompat.startForegroundService(context, intent)
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
        val sampleRate = if (48000 in usbDevice.sampleRates.toList()) 48000 else usbDevice.sampleRates[0]
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_AAUDIO)
            putExtra(RecordingService.EXTRA_DEVICE_ID, usbDevice.id)
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleRate)
            putExtra(RecordingService.EXTRA_BIT_DEPTH, 24)
            putExtra(RecordingService.EXTRA_CHANNEL_COUNT, 2)
            putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)
        }
        ContextCompat.startForegroundService(context, intent)
        boundService?.setDeviceLabel("DJM REC Port (${usbDevice.productName})")
        Log.i(TAG, "DJM REC port: starting AAudio capture deviceId=${usbDevice.id} @ ${sampleRate}Hz")
        return true
    }

    fun pauseRecording() = sendCommand(RecordingService.ACTION_PAUSE)
    fun resumeRecording() = sendCommand(RecordingService.ACTION_RESUME)
    fun stopRecording() = sendCommand(RecordingService.ACTION_STOP)

    private fun sendCommand(action: String) {
        val context = getApplication<Application>()
        context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }

    override fun onCleared() {
        if (isBound) {
            getApplication<Application>().unbindService(connection)
            isBound = false
        }
        super.onCleared()
    }
}
