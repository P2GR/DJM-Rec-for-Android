package com.audiopro.djmrec.diagnostics

import android.app.Application
import android.util.Log
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import com.bugfender.sdk.Bugfender
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded, process-owned diagnostics. No SDK or network calls on the audio callback thread. */
object RemoteDiagnostics {
    private const val KEY = "automatic_diagnostics"
    private lateinit var app: Application
    private val _enabled = MutableStateFlow(true)
    val enabled = _enabled.asStateFlow()
    private val _restartRequired = MutableStateFlow(false)
    val restartRequired = _restartRequired.asStateFlow()
    private val _status = MutableStateFlow("Starting diagnostics")
    val status = _status.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<() -> Unit>(64)
    @Volatile private var initialized = false
    private var sdkCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var lastHealth = 0L
    private var lastHealthKey = ""
    private val issueTimes = mutableMapOf<String, Long>()

    fun start(application: Application) {
        app = application
        _enabled.value = app.getSharedPreferences("settings", 0).getBoolean(KEY, true)
        scope.launch {
            for (task in queue) if (_enabled.value) runCatching(task).onFailure {
                _status.value = "Diagnostics unavailable; recording is unaffected"
                Log.w("RemoteDiagnostics", "Diagnostic operation failed: ${it.javaClass.simpleName}")
            }
        }
        if (_enabled.value) safelyInitialize() else _status.value = "Automatic diagnostics off"
        PreviousExitReporter.check(app, _enabled.value)
    }

    @Synchronized fun setEnabled(value: Boolean) {
        // Persist before changing collection. No SDK initialization on opted-out launches.
        if (!app.getSharedPreferences("settings", 0).edit().putBoolean(KEY, value).commit()) {
            _status.value = "Could not save diagnostics preference; try again"
            return
        }
        _enabled.value = value
        if (!value) app.getSharedPreferences("settings", 0).edit()
            .putBoolean("diagnostics_previous_launch_enabled", false).apply()
        if (value) {
            _restartRequired.value = false
            safelyInitialize()
            sdkCrashHandler?.let { if (Thread.getDefaultUncaughtExceptionHandler() == previousCrashHandler)
                Thread.setDefaultUncaughtExceptionHandler(it) }
            if (initialized) _status.value = "Automatic diagnostics on · delivery requires internet"
            val current = (app as? com.audiopro.djmrec.DjmRecApplication)?.usbAudioManager?.deviceState?.value
            device(current)
            current?.let { descriptors(it.vendorId, it.productId, it.rawDescriptors) }
        } else {
            // False means dashboard-controlled, NOT SDK shutdown. Do not claim otherwise.
            if (initialized) runCatching { Bugfender.setForceEnabled(false) }
            while (queue.tryReceive().isSuccess) { /* Discard work awaiting the SDK. */ }
            if (Thread.getDefaultUncaughtExceptionHandler() == sdkCrashHandler)
                previousCrashHandler?.let(Thread::setDefaultUncaughtExceptionHandler)
            _restartRequired.value = initialized
            _status.value = if (initialized) "New logs stopped. Restart required to stop SDK traffic."
                else "Automatic diagnostics off"
        }
    }

    private fun safelyInitialize() {
        runCatching { initialize() }.onFailure {
            _status.value = "Diagnostics unavailable; recording is unaffected"
            Log.w("RemoteDiagnostics", "SDK initialization failed: ${it.javaClass.simpleName}")
        }
    }

    @Synchronized private fun initialize() {
        if (!_enabled.value) return
        if (!initialized) {
            Bugfender.setApiUrl("https://api.bugfender.com/")
            Bugfender.setBaseUrl("https://dashboard.bugfender.com/")
            Bugfender.overrideDeviceName("Android DJ recorder")
            // debug controls console echo, not release collection. ANDROID_ID collection disabled.
            Bugfender.init(app, "vbKfyZ1SLEBowXhQMa0RpMbJssz2ig3E", false, false)
            Bugfender.setMaximumLocalStorageSize(5L * 1024 * 1024)
            previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
            Bugfender.enableCrashReporting()
            sdkCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
            Bugfender.enableLogcatLogging { log ->
                if (!_enabled.value || !DiagnosticPrivacy.allowLogcat(log.tag.orEmpty(), log.message.orEmpty())) null
                else log.apply { message = DiagnosticPrivacy.redact(message.orEmpty()) }
            }
            initialized = true
        }
        Bugfender.setForceEnabled(true)
        Bugfender.setDeviceString("app.build_type", BuildConfig.BUILD_TYPE)
        Bugfender.setDeviceString("app.version", BuildConfig.VERSION_NAME)
        _status.value = "Automatic diagnostics on · delivery requires internet"
        event("App", "Diagnostics initialized; build=${BuildConfig.BUILD_TYPE} version=${BuildConfig.VERSION_NAME}")
    }

    private fun submit(work: () -> Unit) {
        if (_enabled.value && initialized) queue.trySend(work)
    }

    fun event(tag: String, message: String) = submit {
        Bugfender.i(tag, DiagnosticPrivacy.redact(message))
    }

    fun issue(category: String, detail: String) = submit {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (issueTimes[category] ?: -600_000L) >= 600_000L) {
            issueTimes[category] = now
            Bugfender.sendIssue(category, DiagnosticPrivacy.redact(detail))
        }
    }

    fun descriptors(vendor: Int, product: Int, raw: ByteArray) {
        if (!_enabled.value) return
        val copy = raw.copyOf()
        submit {
            val prefix = "usb=%04x:%04x".format(vendor, product)
            DiagnosticPrivacy.descriptorHex(copy).chunked(3000).forEachIndexed { index, chunk ->
                Bugfender.i("UsbDescriptors", "$prefix chunk=$index $chunk")
            }
        }
    }

    fun device(device: UsbAudioDeviceInfo?) = submit {
        Bugfender.setDeviceBoolean("mixer.connected", device != null)
        if (device == null) {
            listOf("mixer.usb_id", "mixer.profile", "mixer.channels").forEach(Bugfender::removeDeviceKey)
            Bugfender.i("Mixer", "USB input disconnected or no input selected")
        } else {
            Bugfender.setDeviceString("mixer.usb_id", "%04x:%04x".format(device.vendorId, device.productId))
            Bugfender.setDeviceString("mixer.profile", device.pioneerMixerProfile?.name ?: device.allInOneProfile?.name ?: "generic_pcm")
            Bugfender.setDeviceInteger("mixer.channels", device.channelCount)
            Bugfender.i("Mixer", "${device.profileDescription}; if=${device.streamingInterfaceNumber} alt=${device.activeAlternateSetting} " +
                "ep=${device.isochronousInEndpointAddress} packet=${device.isochronousInMaxPacketSize} " +
                "channels=${device.channelCount} bits=${device.bitResolution} subframe=${device.subframeSize} " +
                "rates=${device.supportedSampleRates} raw=${device.requiresIsoCapture}")
        }
    }

    @Synchronized fun health(key: String) {
        if (!_enabled.value || !initialized) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastHealthKey && now - lastHealth < 30_000) return
        lastHealth = now
        lastHealthKey = key
        submit {
            Bugfender.i("CaptureHealth", DiagnosticPrivacy.redact("$key\n${AudioEngine.getDiagnosticSummary()}"))
        }
    }
}
