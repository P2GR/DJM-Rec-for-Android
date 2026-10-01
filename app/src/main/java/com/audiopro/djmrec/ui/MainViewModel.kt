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
import com.audiopro.djmrec.audio.MultitrackLayout
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.RecordingHealth
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.service.RecordingService
import com.audiopro.djmrec.streaming.LiveStreamConfig
import com.audiopro.djmrec.streaming.LiveStreamQuality
import com.audiopro.djmrec.streaming.LiveStreamState
import com.audiopro.djmrec.streaming.LiveStreamStatus
import com.audiopro.djmrec.streaming.LiveVideoMode
import com.audiopro.djmrec.streaming.LivePlatform
import com.audiopro.djmrec.streaming.StreamSetupState
import com.audiopro.djmrec.streaming.StreamSetupStatus
import com.audiopro.djmrec.streaming.StreamingSetupRepository
import com.audiopro.djmrec.streaming.YouTubePrivacy
import com.audiopro.djmrec.streaming.YouTubeBroadcastState
import com.audiopro.djmrec.streaming.YouTubeBroadcastStatus
import com.audiopro.djmrec.streaming.YouTubeFinishResult
import com.audiopro.djmrec.streaming.YouTubeLiveSession
import com.audiopro.djmrec.usb.PioneerTrackRouting
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import com.audiopro.djmrec.usb.UsbAudioManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val KEY_USB_CHANNEL_OFFSET = "usb_channel_offset"
        private const val KEY_FORCE_ANDROID_CAPTURE = "force_android_capture"
        private const val KEY_DJMREC_PORT_MODE = "djmrec_port_mode"
        private const val KEY_WAVEFORM_ENABLED = "waveform_enabled"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_ADVANCED_MODE = "advanced_mode"
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
            ?: if (multitrackFor(device)) defaultMultitrackMasterOffset(device)
            else if (device.pioneerMixerProfile != null) UsbAudioManager.AUTO_CHANNEL_OFFSET else 0

    /**
     * Advanced mode never auto-picks the master: with channels routed to other pairs, the
     * loudest pair can be a deck instead of the mix. Mixers with a routing catalog put the
     * master on their master slot; other Pioneer profiles use their MIX pair; anything else
     * USB 1/2.
     */
    private fun defaultMultitrackMasterOffset(device: UsbAudioDeviceInfo): Int =
        PioneerTrackRouting.catalogFor(device.pioneerMixerProfile)?.masterOffset
            ?: device.pioneerMixerProfile?.defaultCaptureChannelOffset
            ?: 0

    /** Advanced mode is on and this input has more than the master pair to record. */
    private fun multitrackFor(device: UsbAudioDeviceInfo?): Boolean =
        device != null && _advancedMode.value && device.requiresIsoCapture &&
            MultitrackLayout.isAvailable(device.channelCount)
    private val sessionEvents = (application as DjmRecApplication).sessionEvents
    val lastSaved = sessionEvents.lastSaved.asStateFlow()
    // Default ON unless the user granted the battery-optimization exemption (Background usage):
    // without it Android may kill capture when the screen sleeps, so staying awake is safer.
    private val batteryExempt: Boolean = runCatching {
        (application.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(application.packageName)
    }.getOrDefault(false)
    val keepScreenOn = MutableStateFlow(prefs.getBoolean("keep_screen_on", !batteryExempt))
    val smoothWaveform = MutableStateFlow(prefs.getBoolean("smooth_waveform", true))
    val confirmStop = MutableStateFlow(prefs.getBoolean("confirm_stop", true))
    val trimLeadingSilence = MutableStateFlow(prefs.getBoolean(RecordingService.PREF_TRIM_LEADING_SILENCE, true))
    val safetyLimiter = MutableStateFlow(prefs.getBoolean(RecordingService.PREF_SAFETY_LIMITER, true))
    val preRecord = MutableStateFlow(prefs.getBoolean(RecordingService.PREF_PRE_RECORD, true))
    val mp3Copy = MutableStateFlow(prefs.getBoolean(RecordingService.PREF_MP3_COPY, false))
    val silenceAutoStopMinutes = MutableStateFlow(
        prefs.getInt(RecordingService.PREF_SILENCE_AUTO_STOP_MINUTES, RecordingService.DEFAULT_SILENCE_AUTO_STOP_MINUTES)
    )

    /** User-chosen livestream quality (resolution + bitrate); presets are named by resolution. */
    private fun persistedStreamQuality(): LiveStreamQuality {
        val custom = LiveStreamQuality(
            id = "custom",
            label = "Custom",
            width = prefs.getInt("live_stream_custom_width", 1920),
            height = prefs.getInt("live_stream_custom_height", 1080),
            videoBitrate = prefs.getInt("live_stream_custom_bitrate", 8_000_000),
            preset = false
        )
        return when (val id = prefs.getString("live_stream_quality", null)) {
            null -> LiveStreamQuality.P720
            "custom" -> custom
            else -> LiveStreamQuality.PRESETS.firstOrNull { it.id == id } ?: LiveStreamQuality.P720
        }
    }

    val streamQuality = MutableStateFlow(persistedStreamQuality())

    private val _onboardingComplete = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false))
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    fun completeOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
        _onboardingComplete.value = true
    }

    fun setKeepScreenOn(value: Boolean) { prefs.edit().putBoolean("keep_screen_on", value).apply(); keepScreenOn.value = value }
    fun setSmoothWaveform(value: Boolean) { prefs.edit().putBoolean("smooth_waveform", value).apply(); smoothWaveform.value = value }
    fun setConfirmStop(value: Boolean) { prefs.edit().putBoolean("confirm_stop", value).apply(); confirmStop.value = value }
    fun setTrimLeadingSilence(value: Boolean) {
        prefs.edit().putBoolean(RecordingService.PREF_TRIM_LEADING_SILENCE, value).apply()
        trimLeadingSilence.value = value
    }
    fun setSafetyLimiter(value: Boolean) {
        prefs.edit().putBoolean(RecordingService.PREF_SAFETY_LIMITER, value).apply()
        safetyLimiter.value = value
        boundService?.applyCapturePreferences()
    }
    fun setPreRecord(value: Boolean) {
        prefs.edit().putBoolean(RecordingService.PREF_PRE_RECORD, value).apply()
        preRecord.value = value
        boundService?.applyCapturePreferences()
    }
    fun setMp3Copy(value: Boolean) {
        prefs.edit().putBoolean(RecordingService.PREF_MP3_COPY, value).apply()
        mp3Copy.value = value
    }
    fun setSilenceAutoStopMinutes(minutes: Int) {
        prefs.edit().putInt(RecordingService.PREF_SILENCE_AUTO_STOP_MINUTES, minutes.coerceAtLeast(0)).apply()
        silenceAutoStopMinutes.value = minutes.coerceAtLeast(0)
    }
    fun setStreamQuality(value: LiveStreamQuality) {
        prefs.edit()
            .putString("live_stream_quality", value.id)
            .putInt("live_stream_custom_width", value.width)
            .putInt("live_stream_custom_height", value.height)
            .putInt("live_stream_custom_bitrate", value.videoBitrate)
            .apply()
        streamQuality.value = value
    }
    fun dismissSavedRecording() { sessionEvents.lastSaved.value = null }


    private val floorLevel = ChannelLevel(peakDb = -60f, rmsDb = -60f, isClipping = false)

    val saving = MutableStateFlow(false)
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _levels = MutableStateFlow(StereoLevels(floorLevel, floorLevel))
    val levels: StateFlow<StereoLevels> = _levels.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    private val _awaitingAudio = MutableStateFlow(false)
    val awaitingAudio: StateFlow<Boolean> = _awaitingAudio.asStateFlow()

    private val _limiterReductionDb = MutableStateFlow(0f)
    val limiterReductionDb: StateFlow<Float> = _limiterReductionDb.asStateFlow()

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

    /**
     * True while the fullscreen camera console is showing. Closing it returns to the app
     * WITHOUT ending the stream (the service keeps it running) — the console is a view, not
     * the stream itself.
     */
    val cameraConsoleOpen = androidx.compose.runtime.mutableStateOf(true)

    /**
     * True while the fullscreen power-saving overlay covers the workspace. Hoisted here so
     * MainScreen can hide the top/bottom chrome for a true fullscreen takeover.
     */
    val powerSaveActive = androidx.compose.runtime.mutableStateOf(false)

    /** Deeplink route (djmrec://record etc.) awaiting navigation; consumed by MainScreen. */
    val pendingRoute = MutableStateFlow<String?>(null)

    private val _waveformEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_WAVEFORM_ENABLED, true)
    )
    val waveformEnabled: StateFlow<Boolean> = _waveformEnabled.asStateFlow()

    // Gain range 0..+12 dB with +12 as the default (per product decision).
    private val _recordingGainDb = MutableStateFlow(prefs.getInt("recording_gain_db", 12).coerceIn(0, 12))
    val recordingGainDb: StateFlow<Int> = _recordingGainDb.asStateFlow()

    private val _selectedFormat = MutableStateFlow(
        RecordingFormat.entries.firstOrNull { it.name == prefs.getString("recording_format", "WAV") } ?: RecordingFormat.WAV)
    val selectedFormat: StateFlow<RecordingFormat> = _selectedFormat.asStateFlow()
    val availableFormats: List<RecordingFormat> = RecordingFormat.entries

    private val _usbChannelOffset = MutableStateFlow(
        prefs.getInt(KEY_USB_CHANNEL_OFFSET, UsbAudioManager.AUTO_CHANNEL_OFFSET)
    )
    val usbChannelOffset: StateFlow<Int> = _usbChannelOffset.asStateFlow()

    private val _forceAndroidCapture = MutableStateFlow(false)
    val forceAndroidCapture: StateFlow<Boolean> = _forceAndroidCapture.asStateFlow()

    private val _djmrecPortMode = MutableStateFlow(false)
    val djmrecPortMode: StateFlow<Boolean> = _djmrecPortMode.asStateFlow()

    // --- Multitrack (advanced mode) ---
    private val _advancedMode = MutableStateFlow(prefs.getBoolean(KEY_ADVANCED_MODE, false))
    val advancedMode: StateFlow<Boolean> = _advancedMode.asStateFlow()
    private val _trackChannels = MutableStateFlow(0)
    private val _masterChannelOffset = MutableStateFlow(-1)
    private val _trackLevelsRaw = MutableStateFlow(FloatArray(0))
    private val _multitrackSettings = MutableStateFlow(MultitrackSettings())
    val multitrackSettings: StateFlow<MultitrackSettings> = _multitrackSettings.asStateFlow()
    /** Waveform histories for the multitrack timeline; [timelineRevision] ticks on new data. */
    val multitrackTimeline = MultitrackTimeline()
    private val _timelineRevision = MutableStateFlow(0L)
    val timelineRevision: StateFlow<Long> = _timelineRevision.asStateFlow()

    /** True while the open capture session records every track (advanced mode, raw USB path). */
    val multitrackActive: StateFlow<Boolean> = _trackChannels
        .combine(_advancedMode) { channels, advanced -> advanced && channels > 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val multitrackTracks: StateFlow<List<MultitrackTrack>> = combine(
        usbAudioManager.deviceState, _advancedMode, _trackChannels, _masterChannelOffset, _multitrackSettings
    ) { device, advanced, openChannels, resolvedMaster, settings ->
        if (device == null || !advanced || !MultitrackLayout.isAvailable(device.channelCount) ||
            !device.requiresIsoCapture) return@combine emptyList()
        // Before capture opens, preview the tracks the device will provide.
        val channels = if (openChannels > 0) openChannels else device.channelCount
        val master = resolvedMaster.takeIf { it >= 0 } ?: captureChannelOffset(device)
        MultitrackRows.build(device, channels, master, settings)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** One reading per track, in [multitrackTracks] order. */
    val trackLevels: StateFlow<List<StereoLevels>> = combine(_trackLevelsRaw, multitrackTracks) { raw, tracks ->
        MultitrackLayout.trackLevels(raw, tracks.map { it.channels })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @SuppressLint("StaticFieldLeak")
    private var boundService: RecordingService? = null
    private var isBound = false
    private var uiVisible = false
    private var waveformVisible = false
    private var multitrackVisible = false

    fun setMultitrackVisible(visible: Boolean) {
        multitrackVisible = visible
        boundService?.setMultitrackVisible(visible)
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        boundService?.setVisualsVisible(uiVisible, waveformVisible)
    }

    fun setWaveformVisible(visible: Boolean) {
        waveformVisible = visible
        boundService?.setVisualsVisible(uiVisible, waveformVisible)
    }
    private var livePreview: SurfaceView? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as RecordingService.LocalBinder).getService()
            boundService = service
            isBound = true
            _recordingState.value = service.state.value
            service.setVisualsVisible(uiVisible, waveformVisible)
            service.setWaveformEnabled(_waveformEnabled.value)
            service.setRecordingGainDb(_recordingGainDb.value)
            viewModelScope.launch { service.saving.collect { saving.value = it } }
            viewModelScope.launch { service.state.collect { _recordingState.value = it } }
            viewModelScope.launch { service.levels.collect { _levels.value = it } }
            viewModelScope.launch { service.elapsedMillis.collect { _elapsedMillis.value = it } }
            viewModelScope.launch { service.awaitingAudio.collect { _awaitingAudio.value = it } }
            viewModelScope.launch { service.limiterReductionDb.collect { _limiterReductionDb.value = it } }
            viewModelScope.launch { service.waveformBins.collect { _waveformBins.value = it } }
            viewModelScope.launch { service.health.collect { _recordingHealth.value = it } }
            viewModelScope.launch { service.liveState.collect { _liveStreamState.value = it } }
            viewModelScope.launch { service.trackLevels.collect { _trackLevelsRaw.value = it } }
            viewModelScope.launch { service.masterChannelOffset.collect { _masterChannelOffset.value = it } }
            viewModelScope.launch {
                service.trackChannels.collect { channels ->
                    _trackChannels.value = channels
                    multitrackTimeline.clear()
                    _timelineRevision.value++
                    if (channels > 0) {
                        pushTrackGains()
                        applyTrackRouting()
                    } else {
                        _multitrackSettings.value = _multitrackSettings.value.copy(
                            currentSources = emptyMap(), routingErrors = emptyMap()
                        )
                    }
                }
            }
            viewModelScope.launch {
                service.waveformBins.collect { bins ->
                    if (!_advancedMode.value) return@collect
                    multitrackTimeline.master.accept(bins)
                    _timelineRevision.value++
                }
            }
            viewModelScope.launch {
                service.trackWaveforms.collect { snapshots ->
                    if (snapshots.isEmpty()) return@collect
                    multitrackTimeline.acceptTracks(snapshots)
                    _timelineRevision.value++
                }
            }
            service.setMultitrackVisible(multitrackVisible)
            pushTrackGains()
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
            .remove(KEY_FORCE_ANDROID_CAPTURE)
            .remove(KEY_DJMREC_PORT_MODE)
            .apply()
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
                _multitrackSettings.value = loadMultitrackSettings(device)
                pushTrackGains()
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
        val value = gainDb.coerceIn(0, 12)
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
        if (sessionEvents.closeRequested.value || boundService == null) return
        val context = getApplication<Application>()
        if (deviceState.value != null &&
            (_recordingState.value is RecordingState.Idle ||
                _recordingState.value is RecordingState.Error)) {
            startMonitoringDevice(context)
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
        restartMonitoring("Could not change the USB pair. Stop capture and reconnect the mixer.")
    }

    private fun restartMonitoring(failureMessage: String) {
        if (_recordingState.value !is RecordingState.Monitoring) return
        val context = getApplication<Application>()
        _recordingState.value = RecordingState.Preparing
        viewModelScope.launch {
            sendCommand(RecordingService.ACTION_STOP)
            val stopped = withTimeoutOrNull(5_000L) {
                boundService?.state?.first { it is RecordingState.Idle || it is RecordingState.Error }
            }
            if (stopped == null) {
                _recordingState.value = RecordingState.Error(failureMessage)
            } else {
                _recordingState.value = stopped
                startMonitoringDevice(context)
            }
        }
    }

    /**
     * Advanced mode records every input pair as its own track next to the master. Capture
     * reopens so the native engine can carry every channel (only while not recording).
     */
    fun setAdvancedMode(enabled: Boolean) {
        if (saving.value || liveStreamState.value.isActive || enabled == _advancedMode.value) return
        if (_recordingState.value is RecordingState.Recording ||
            _recordingState.value is RecordingState.Paused ||
            _recordingState.value is RecordingState.Preparing) return
        prefs.edit().putBoolean(KEY_ADVANCED_MODE, enabled).apply()
        _advancedMode.value = enabled
        multitrackTimeline.clear()
        restartMonitoring("Could not switch advanced mode. Stop capture and reconnect the mixer.")
    }

    private fun trackSettingsKey(device: UsbAudioDeviceInfo) = "mt_${device.vendorId}_${device.productId}"

    private fun loadMultitrackSettings(device: UsbAudioDeviceInfo): MultitrackSettings {
        val key = trackSettingsKey(device)
        val tracks = MultitrackLayout.tracks(device.channelCount)
        return MultitrackSettings(
            armed = tracks.mapNotNull { track ->
                prefs.getString("${key}_armed_${track.index}", null)?.let { track.index to (it == "1") }
            }.toMap(),
            gainsDb = tracks.associate { it.index to prefs.getFloat("${key}_gain_${it.index}", 0f) },
            chosenSources = tracks.mapNotNull { track ->
                prefs.getInt("${key}_source_${track.index}", -1).takeIf { it >= 0 }?.let { track.index to it }
            }.toMap()
        )
    }

    private fun captureSettingsLocked(): Boolean = saving.value ||
        _recordingState.value is RecordingState.Recording ||
        _recordingState.value is RecordingState.Paused

    fun setTrackArmed(track: Int, armed: Boolean) {
        val device = deviceState.value ?: return
        if (captureSettingsLocked()) return
        prefs.edit().putString("${trackSettingsKey(device)}_armed_$track", if (armed) "1" else "0").apply()
        _multitrackSettings.value = _multitrackSettings.value.let { it.copy(armed = it.armed + (track to armed)) }
    }

    /** Applies immediately; [persist] = false while a slider is still moving. */
    fun setTrackGainDb(track: Int, gainDb: Float, persist: Boolean = true) {
        val device = deviceState.value ?: return
        if (captureSettingsLocked()) return
        val value = (Math.round(gainDb.coerceIn(-24f, 12f) * 2f) / 2f)
        if (persist) prefs.edit().putFloat("${trackSettingsKey(device)}_gain_$track", value).apply()
        _multitrackSettings.value = _multitrackSettings.value.let { it.copy(gainsDb = it.gainsDb + (track to value)) }
        boundService?.setTrackGainDb(track, value)
    }

    /** Engine track gains are global; push this mixer's values (0 dB for unused tracks). */
    private fun pushTrackGains() {
        val service = boundService ?: return
        val gains = _multitrackSettings.value.gainsDb
        for (track in 0 until MultitrackLayout.MAX_TRACKS) service.setTrackGainDb(track, gains[track] ?: 0f)
    }

    /** Switches the mixer source of one track's USB pair (Pioneer mixers with a routing catalog). */
    fun setTrackSource(track: Int, source: Int) {
        val device = deviceState.value ?: return
        if (captureSettingsLocked()) return
        prefs.edit().putInt("${trackSettingsKey(device)}_source_$track", source).apply()
        _multitrackSettings.value = _multitrackSettings.value.let {
            it.copy(chosenSources = it.chosenSources + (track to source), routingErrors = it.routingErrors - track)
        }
        if (multitrackActive.value) viewModelScope.launch { routeTrack(track, source) }
    }

    /**
     * Applies each track's chosen (or preset) mixer source when a multitrack session opens,
     * then reads every route back for the labels. The native side restores the previous
     * routes when capture stops.
     */
    private fun applyTrackRouting() {
        val device = deviceState.value ?: return
        val catalog = PioneerTrackRouting.catalogFor(device.pioneerMixerProfile) ?: return
        val master = _masterChannelOffset.value.takeIf { it >= 0 } ?: captureChannelOffset(device)
        viewModelScope.launch {
            catalog.outputs.indices.filter { it * 2 != master }.forEach { output ->
                val desired = _multitrackSettings.value.chosenSources[output] ?: catalog.preset[output]
                routeTrack(output, desired)
            }
            val service = boundService ?: return@launch
            val current = withContext(Dispatchers.IO) {
                catalog.outputs.indices.associateWith { service.readTrackSource(it) }.filterValues { it >= 0 }
            }
            _multitrackSettings.value = _multitrackSettings.value.copy(currentSources = current)
        }
    }

    private suspend fun routeTrack(output: Int, source: Int) {
        val service = boundService ?: return
        val result = withContext(Dispatchers.IO) { service.setTrackSource(output, source) }
        val readBack = withContext(Dispatchers.IO) { service.readTrackSource(output) }
        val error = when (result) {
            0 -> null
            -2 -> "This mixer cannot report this route, so it stays as set on the mixer."
            -4 -> "The mixer did not confirm this source, so its own setting was kept."
            else -> "Could not change this source. Check the USB connection."
        }
        _multitrackSettings.value = _multitrackSettings.value.let { settings ->
            settings.copy(
                currentSources = if (readBack >= 0) settings.currentSources + (output to readBack) else settings.currentSources - output,
                routingErrors = if (error != null) settings.routingErrors + (output to error) else settings.routingErrors - output
            )
        }
    }

    /** Armed tracks for ACTION_START; the master pair is the set itself, never a track file. */
    private fun Intent.putTrackPlan(): Intent {
        if (!multitrackActive.value && !multitrackFor(deviceState.value)) return this
        val armed = multitrackTracks.value.filter { it.armed && !it.isMaster }
        if (armed.isEmpty()) return this
        putExtra(RecordingService.EXTRA_TRACKS_ARMED, armed.map { it.index }.toIntArray())
        putExtra(RecordingService.EXTRA_TRACK_LABELS, armed.map { it.fileLabel }.toTypedArray())
        return this
    }

    fun setForceAndroidCapture(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_ANDROID_CAPTURE, enabled).apply()
        _forceAndroidCapture.value = enabled
    }

    fun setDjmrecPortMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DJMREC_PORT_MODE, enabled).apply()
        _djmrecPortMode.value = enabled
    }

    fun startRecording() {
        val context = getApplication<Application>()

        if (_recordingState.value is RecordingState.Preparing) {
            startServiceSafely(
                context,
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)
                    .putTrackPlan()
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
                    .putTrackPlan()
            )
            return
        }

        // DJM-REC-equivalent path: the top digital send/return USB port exposes stereo audio.
        if (_djmrecPortMode.value) {
            tryDjmrecPortCapture(context)
            return
        }

        val device = deviceState.value ?: run {
            usbAudioManager.scanForConnectedMixer("record-button-rescan")
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
            putTrackPlan()

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
                putExtra(RecordingService.EXTRA_MULTITRACK, multitrackFor(device))
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
                putExtra(RecordingService.EXTRA_MULTITRACK, multitrackFor(device))
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

    private fun captureReady(state: RecordingState): Boolean =
        state is RecordingState.Monitoring ||
            state is RecordingState.Recording ||
            state is RecordingState.Paused

    /** Last Go Live settings, kept in memory to resume an interrupted YouTube broadcast. */
    private var lastLiveConfig: LiveStreamConfig? = null

    fun startLiveStream(config: LiveStreamConfig) {
        lastLiveConfig = config
        if (config.videoMode != LiveVideoMode.ARTWORK) cameraConsoleOpen.value = true
        val context = getApplication<Application>()
        _liveStreamState.value = LiveStreamState(
            status = LiveStreamStatus.PREPARING,
            message = "Arming USB mixer",
            platform = config.platform,
            videoMode = config.videoMode
        )
        viewModelScope.launch {
            if (!captureReady(_recordingState.value)) {
                if (deviceState.value == null) {
                    _liveStreamState.value = LiveStreamState(
                        status = LiveStreamStatus.ERROR,
                        message = "Connect a USB mixer before going live",
                        platform = config.platform,
                        videoMode = config.videoMode
                    )
                    return@launch
                }
                startMonitoringDevice(context)
                val readyState = withTimeoutOrNull(15_000L) {
                    recordingState.first { state ->
                        captureReady(state) || state is RecordingState.Error
                    }
                }
                if (readyState == null || !captureReady(readyState)) {
                    _liveStreamState.value = LiveStreamState(
                        status = LiveStreamStatus.ERROR,
                        message = (readyState as? RecordingState.Error)?.message
                            ?: "USB mixer did not become ready. Reconnect and try again.",
                        platform = config.platform,
                        videoMode = config.videoMode
                    )
                    return@launch
                }
            }

            _liveStreamState.value = LiveStreamState(
                status = LiveStreamStatus.PREPARING,
                message = "Starting ${config.platform.label} encoders",
                platform = config.platform,
                videoMode = config.videoMode
            )
            // The stream is lossy and platforms may mute DJ sets: keep a lossless local copy.
            if (config.alsoRecordAudio && _recordingState.value is RecordingState.Monitoring) startRecording()
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
                    .putExtra(RecordingService.EXTRA_LIVE_VIDEO_WIDTH, config.quality.width)
                    .putExtra(RecordingService.EXTRA_LIVE_VIDEO_HEIGHT, config.quality.height)
                    .putExtra(RecordingService.EXTRA_LIVE_VIDEO_BITRATE, config.quality.videoBitrate)
                    .putExtra(RecordingService.EXTRA_LIVE_RECORD_VIDEO, config.recordVideo)
            )
        }
    }

    fun stopLiveStream() {
        sendCommand(RecordingService.ACTION_STOP_LIVE)
        youtubeCoordinator.finishYouTubeSession()
    }

    /** Restarts the stream into the YouTube broadcast that dropped, keeping its watch link. */
    fun resumeYouTubeBroadcast() {
        val config = lastLiveConfig?.takeIf { it.platform == LivePlatform.YOUTUBE } ?: return
        if (!youtubeCoordinator.canResume) return
        startLiveStream(config)
    }

    /** Ends a dropped YouTube broadcast instead of resuming it. */
    fun endYouTubeBroadcast() = youtubeCoordinator.finishYouTubeSession()

    /** Stops saving MP4 video (ends a phone-only recording; a livestream keeps running). */
    fun stopVideoRecording() {
        boundService?.stopLiveVideoRecording()
    }

    fun setCameraLocks(locks: com.audiopro.djmrec.streaming.CameraLocks) {
        boundService?.setCameraLocks(locks)
    }

    /** Free space for the Go Live storage estimate; Long.MAX_VALUE when unknown. */
    fun freeStorageBytes(): Long = com.audiopro.djmrec.storage.RecordingOutputManager.freeBytes()

    fun prepareYouTubeDestination(accessToken: String, title: String, privacy: YouTubePrivacy) =
        youtubeCoordinator.prepareYouTubeDestination(accessToken, title, privacy)
    fun setStreamSetupError(platform: LivePlatform, message: String) = youtubeCoordinator.setStreamSetupError(platform, message)
    fun consumeStreamCredentials() = youtubeCoordinator.consumeStreamCredentials()
    fun cancelStreamSetup() = youtubeCoordinator.cancelStreamSetup()

    /** Tears down a prepared-but-unused YouTube broadcast when the user changes platform. */
    fun abandonYouTubeSetup() {
        cancelStreamSetup()
        youtubeCoordinator.abandonPlannedBroadcast()
    }

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
