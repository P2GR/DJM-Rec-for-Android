package com.audiopro.djmrec.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Wires the USB device stream, the bound [RecordingService], and the Compose UI together.
 * Transport commands are always sent as service `Intent`s (works whether or not the bind has
 * completed yet); the bind is only used to *observe* the service's StateFlows.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_ROOT_USB_MODE = "root_usb_mode"
        private const val KEY_USB_CHANNEL_OFFSET = "usb_channel_offset"
        private const val KEY_FORCE_ANDROID_CAPTURE = "force_android_capture"
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

    private val _rootUsbMode = MutableStateFlow(prefs.getBoolean(KEY_ROOT_USB_MODE, false))
    val rootUsbMode: StateFlow<Boolean> = _rootUsbMode.asStateFlow()

    private val _usbChannelOffset = MutableStateFlow(
        prefs.getInt(KEY_USB_CHANNEL_OFFSET, UsbAudioManager.AUTO_CHANNEL_OFFSET)
    )
    val usbChannelOffset: StateFlow<Int> = _usbChannelOffset.asStateFlow()

    private val _forceAndroidCapture = MutableStateFlow(prefs.getBoolean(KEY_FORCE_ANDROID_CAPTURE, false))
    val forceAndroidCapture: StateFlow<Boolean> = _forceAndroidCapture.asStateFlow()

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
        usbAudioManager.setRootModeEnabled(_rootUsbMode.value)
        context.bindService(
            Intent(context, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    fun selectFormat(format: RecordingFormat) {
        if (_recordingState.value is RecordingState.Idle && format in availableFormats) {
            _selectedFormat.value = format
        }
    }

    fun rescanUsbDevices() {
        usbAudioManager.scanForConnectedMixer()
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

    fun startRecording() {
        val context = getApplication<Application>()
        val device = deviceState.value ?: run {
            usbAudioManager.scanForConnectedMixer("record-button-rescan")
            return
        }
        val sampleRate = when {
            device.negotiatedSampleRate > 0 -> device.negotiatedSampleRate
            48000 in device.supportedSampleRates -> 48000
            else -> device.supportedSampleRates.firstOrNull() ?: 48000
        }

        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleRate)
            putExtra(RecordingService.EXTRA_BIT_DEPTH, device.bitResolution)
            putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)

            // Pioneer multichannel mixers (e.g. DJM-A9) need the raw libusb isochronous path
            // to reach the Master Mix pair at a non-zero channel offset -- AAudio can only ever
            // give us channels 1/2. Everything else keeps using the existing AAudio path.
            val handle = if (device.requiresIsoCapture && !_forceAndroidCapture.value) {
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
            } else {
                // Either a non-Pioneer / exact-stereo device, or openIsoCaptureHandle() failed
                // (e.g. permission lost) -- fall back to the AAudio path rather than silently
                // failing to record. For a Pioneer mixer this will still only capture channels
                // 1/2, not the Master Mix, but that's strictly better than no recording at all.
                putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_AAUDIO)
                putExtra(RecordingService.EXTRA_DEVICE_ID, device.audioManagerDeviceId)
                putExtra(RecordingService.EXTRA_CHANNEL_COUNT, if (device.isPioneer) 2 else device.channelCount)
            }
        }
        ContextCompat.startForegroundService(context, intent)
        boundService?.setDeviceLabel(device.productName)
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
