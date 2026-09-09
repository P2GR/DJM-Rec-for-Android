package com.audiopro.djmrec.diagnostics

import android.app.Application
import android.util.Log
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Bounded Crashlytics diagnostics. No SDK or network calls on audio callback thread. */
object RemoteDiagnostics {
    private const val KEY = "automatic_diagnostics"
    private lateinit var app: Application
    private lateinit var crashlytics: FirebaseCrashlytics
    private val _enabled = MutableStateFlow(true)
    val enabled = _enabled.asStateFlow()
    private val _status = MutableStateFlow("Starting diagnostics")
    val status = _status.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<() -> Unit>(64)
    @Volatile private var initialized = false
    private val healthGate = CaptureHealthGate()
    private val eventGate = DiagnosticEventGate()
    private val issueTimes = mutableMapOf<String, Long>()
    private val connections = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val descriptorConnections = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var activeConnection = "no-active-input"

    fun usbEvent(path: String, name: String, vendor: Int, product: Int, stage: String, detail: String = "") {
        if (!_enabled.value) return
        val id = connections.computeIfAbsent(path) { java.util.UUID.randomUUID().toString().take(8) }
        event(
            "MixerConnection",
            "connection=$id; stage=$stage; source=UsbAudioManager.$stage; " +
                MixerDiagnosticReport.identity(name, vendor, product) + "; $detail"
        )
    }

    fun usbDetached(path: String) {
        val id = connections.remove(path) ?: return
        descriptorConnections.remove(id)
        event("MixerConnection", "connection=$id; stage=detached; source=UsbAudioManager.usbDeviceReceiver")
    }

    fun start(application: Application) {
        app = application
        _enabled.value = app.getSharedPreferences("settings", 0).getBoolean(KEY, true)
        scope.launch {
            for (task in queue) if (_enabled.value) runCatching(task).onFailure {
                _status.value = "Diagnostics unavailable; recording is unaffected"
                Log.w("RemoteDiagnostics", "Crashlytics operation failed: ${it.javaClass.simpleName}")
            }
        }
        if (_enabled.value) safelyInitialize() else _status.value = "Automatic diagnostics off"
        PreviousExitReporter.check(app, _enabled.value)
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (!app.getSharedPreferences("settings", 0).edit().putBoolean(KEY, value).commit()) {
            _status.value = "Could not save diagnostics preference; try again"
            return
        }
        _enabled.value = value
        app.getSharedPreferences("settings", 0).edit()
            .putBoolean("diagnostics_previous_launch_enabled", value).apply()
        if (value) {
            healthGate.reset()
            eventGate.reset()
            descriptorConnections.clear()
            safelyInitialize()
            if (initialized) {
                crashlytics.setCrashlyticsCollectionEnabled(true)
                _status.value = "Crashlytics diagnostics on · delivery requires internet"
            }
            val current = (app as? com.audiopro.djmrec.DjmRecApplication)?.usbAudioManager?.deviceState?.value
            device(current)
            current?.let { descriptors(it.vendorId, it.productId, it.rawDescriptors) }
        } else {
            while (queue.tryReceive().isSuccess) { /* Discard queued diagnostics. */ }
            if (initialized) crashlytics.setCrashlyticsCollectionEnabled(false)
            _status.value = "Automatic diagnostics off"
        }
    }

    private fun safelyInitialize() {
        runCatching { initialize() }.onFailure {
            _status.value = "Diagnostics unavailable; recording is unaffected"
            Log.w("RemoteDiagnostics", "Crashlytics initialization failed: ${it.javaClass.simpleName}")
        }
    }

    @Synchronized
    private fun initialize() {
        if (!_enabled.value || initialized) return
        if (!BuildConfig.FIREBASE_CONFIGURED) {
            _status.value = "Crashlytics disabled for this build"
            return
        }
        crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(true)
        crashlytics.setCustomKey("app.build_type", BuildConfig.BUILD_TYPE)
        crashlytics.setCustomKey("app.version", BuildConfig.VERSION_NAME)
        crashlytics.setCustomKey("mixer.name", "None")
        crashlytics.setCustomKey("mixer.connected", false)
        initialized = true
        _status.value = "Crashlytics diagnostics on · delivery requires internet"
        event("App", "Diagnostics initialized; build=${BuildConfig.BUILD_TYPE} version=${BuildConfig.VERSION_NAME}")
    }

    private fun submit(work: () -> Unit) {
        if (_enabled.value && initialized) queue.trySend(work)
    }

    fun event(tag: String, message: String) {
        if (!_enabled.value || !initialized) return
        val context = activeConnection
        val redacted = DiagnosticPrivacy.redact("capture_connection=$context; $message")
        if (!eventGate.shouldSend("$tag\u0000$redacted", android.os.SystemClock.elapsedRealtime())) return
        submit { crashlytics.log("[$tag] $redacted") }
    }

    fun issue(category: String, detail: String) {
        val connection = activeConnection
        submit {
            val now = android.os.SystemClock.elapsedRealtime()
            val issueKey = "$connection/$category"
            if (now - (issueTimes[issueKey] ?: -600_000L) >= 600_000L) {
                if (issueTimes.size >= 64) issueTimes.minByOrNull { it.value }?.key?.let(issueTimes::remove)
                issueTimes[issueKey] = now
                val safeDetail = DiagnosticPrivacy.redact("capture_connection=$connection; $detail")
                crashlytics.recordException(DiagnosticIssue(category, safeDetail))
            }
        }
    }

    fun descriptors(vendor: Int, product: Int, raw: ByteArray, path: String? = null) {
        if (!_enabled.value || !initialized) return
        val copy = raw.copyOf()
        val connection = path?.let { connections[it] } ?: activeConnection
        if (!descriptorConnections.add(connection)) return
        submit {
            val prefix = "connection=$connection; usb=%04x:%04x; source=UsbAudioManager.inspectAndPublish"
                .format(vendor, product)
            MixerDiagnosticReport.capabilities(copy).forEach {
                crashlytics.log("[MixerCapabilities] $prefix; $it")
            }
            DiagnosticPrivacy.descriptorHex(copy).chunked(3000).forEachIndexed { index, chunk ->
                crashlytics.log("[UsbDescriptors] $prefix chunk=$index $chunk")
            }
        }
    }

    fun device(device: UsbAudioDeviceInfo?) {
        val connection = device?.let {
            connections.computeIfAbsent(it.deviceName) { java.util.UUID.randomUUID().toString().take(8) }
        } ?: activeConnection
        activeConnection = connection
        submit {
            val mixerName = mixerDiagnosticName(device)
            crashlytics.setCustomKey("mixer.name", mixerName)
            crashlytics.setCustomKey("mixer.connected", device != null)
            crashlytics.setCustomKey("mixer.connection", if (device == null) "None" else connection)
            crashlytics.setCustomKey(
                "mixer.usb_id",
                device?.let { "%04x:%04x".format(it.vendorId, it.productId) } ?: "None"
            )
            crashlytics.setCustomKey(
                "mixer.profile",
                device?.pioneerMixerProfile?.name
                    ?: device?.allInOneProfile?.name
                    ?: if (device == null) "None" else "Unknown"
            )
            crashlytics.setCustomKey("mixer.channels", device?.channelCount ?: 0)
            if (device == null) {
                if (connection != "no-active-input") {
                    crashlytics.log("[Mixer] connection=$connection; USB input disconnected")
                }
            } else {
                crashlytics.log(
                    "[Mixer] " + DiagnosticPrivacy.redact(
                        "connection=$connection; mixer=$mixerName; " +
                            "source=UsbAudioManager.inspectAndPublish\n${MixerDiagnosticReport.selected(device)}"
                    )
                )
            }
        }
    }

    @Synchronized
    fun health(key: String) {
        if (!_enabled.value || !initialized) return
        val now = android.os.SystemClock.elapsedRealtime()
        val connection = activeConnection
        if (!healthGate.shouldLog(connection, key, now)) return
        submit {
            if (connection != activeConnection) return@submit
            val summary = AudioEngine.getDiagnosticSummary()
            if (connection != activeConnection) return@submit
            crashlytics.log(
                "[CaptureHealth] " + DiagnosticPrivacy.redact(
                    "capture_connection=$connection; source=RecordingService.healthRunnable / " +
                        "UsbIsoAudioSource::diagnosticSummary\n$key\n$summary"
                )
            )
        }
    }

    internal fun mixerDiagnosticName(device: UsbAudioDeviceInfo?): String = when {
        device == null -> "None"
        device.pioneerMixerProfile != null -> device.pioneerMixerProfile!!.displayName
        device.allInOneProfile != null -> device.allInOneProfile!!.displayName
        else -> "Unknown"
    }

    private class DiagnosticIssue(category: String, detail: String) :
        IllegalStateException("$category: $detail")
}
