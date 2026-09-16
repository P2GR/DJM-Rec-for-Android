package com.audiopro.djmrec.diagnostics

import android.app.Application
import android.os.Bundle
import android.util.Log
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** Bounded Firebase telemetry. No SDK or network calls on audio callback thread. */
object RemoteDiagnostics {
    private const val KEY = "automatic_diagnostics"
    private lateinit var app: Application
    private lateinit var crashlytics: FirebaseCrashlytics
    private lateinit var analytics: FirebaseAnalytics
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
    @Volatile private var activeMixerName = "None"

    fun usbEvent(path: String, name: String, vendor: Int, product: Int, stage: String, detail: String = "") {
        if (!_enabled.value) return
        val id = connections.computeIfAbsent(path) { java.util.UUID.randomUUID().toString().take(8) }
        diagnosticEvent(
            "MixerConnection",
            "connection=$id; stage=$stage; source=UsbAudioManager.$stage; " +
                MixerDiagnosticReport.identity(name, vendor, product) + "; $detail",
            Bundle().apply {
                putSafeString("connection_id", id)
                putSafeString("stage", stage)
                putSafeString("mixer_name", name)
                putSafeString("usb_vendor", "%04x".format(vendor))
                putSafeString("usb_product", "%04x".format(product))
            }
        )
    }

    fun usbDetached(path: String) {
        val id = connections.remove(path) ?: return
        descriptorConnections.remove(id)
        diagnosticEvent(
            "MixerConnection",
            "connection=$id; stage=detached; source=UsbAudioManager.usbDeviceReceiver",
            Bundle().apply {
                putSafeString("connection_id", id)
                putSafeString("stage", "detached")
            }
        )
    }

    fun start(application: Application) {
        app = application
        _enabled.value = app.getSharedPreferences("settings", 0).getBoolean(KEY, true)
        scope.launch {
            for (task in queue) if (_enabled.value) runCatching(task).onFailure {
                _status.value = "Diagnostics unavailable; recording is unaffected"
                Log.w("RemoteDiagnostics", "Firebase telemetry operation failed: ${it.javaClass.simpleName}")
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
                analytics.setAnalyticsCollectionEnabled(true)
                _status.value = "Firebase telemetry on · delivery requires internet"
            }
            val current = (app as? com.audiopro.djmrec.DjmRecApplication)?.usbAudioManager?.deviceState?.value
            device(current)
            current?.let { descriptors(it.vendorId, it.productId, it.rawDescriptors) }
        } else {
            while (queue.tryReceive().isSuccess) { /* Discard queued diagnostics. */ }
            if (initialized) {
                crashlytics.setCrashlyticsCollectionEnabled(false)
                analytics.setAnalyticsCollectionEnabled(false)
            }
            _status.value = "Automatic diagnostics off"
        }
    }

    private fun safelyInitialize() {
        runCatching { initialize() }.onFailure {
            _status.value = "Diagnostics unavailable; recording is unaffected"
            Log.w("RemoteDiagnostics", "Firebase telemetry initialization failed: ${it.javaClass.simpleName}")
        }
    }

    @Synchronized
    private fun initialize() {
        if (!_enabled.value || initialized) return
        if (!BuildConfig.FIREBASE_CONFIGURED) {
            _status.value = "Firebase telemetry disabled for this build"
            return
        }
        crashlytics = FirebaseCrashlytics.getInstance()
        analytics = FirebaseAnalytics.getInstance(app)
        crashlytics.setCrashlyticsCollectionEnabled(true)
        analytics.setAnalyticsCollectionEnabled(true)
        crashlytics.setCustomKey("app.build_type", BuildConfig.BUILD_TYPE)
        crashlytics.setCustomKey("app.version", BuildConfig.VERSION_NAME)
        crashlytics.setCustomKey("mixer.name", "None")
        crashlytics.setCustomKey("mixer.connected", false)
        initialized = true
        _status.value = "Firebase telemetry on · delivery requires internet"
        event("App", "Diagnostics initialized; build=${BuildConfig.BUILD_TYPE} version=${BuildConfig.VERSION_NAME}")
    }

    private fun submit(work: () -> Unit) {
        if (_enabled.value && initialized) queue.trySend(work)
    }

    fun event(tag: String, message: String) {
        if (tag == "RecordingState" && message == "Preparing") {
            synchronized(this) { healthGate.reset() }
        }
        diagnosticEvent(tag, message)
    }

    private fun diagnosticEvent(tag: String, message: String, extra: Bundle = Bundle()) {
        if (!_enabled.value || !initialized) return
        val context = activeConnection
        val safeMessage = DiagnosticPrivacy.redact(message)
        val redacted = "capture_connection=$context; $safeMessage"
        if (!eventGate.shouldSend("$tag\u0000$redacted", android.os.SystemClock.elapsedRealtime())) return
        submit {
            crashlytics.log("[$tag] $redacted")
            val parameters = Bundle(extra).apply {
                if (!containsKey("connection_id")) putSafeString("connection_id", context)
                putSafeString("category", tag)
                putSafeString("detail", safeMessage)
            }
            analytics.logEvent(telemetryEventName(tag), parameters)
        }
    }

    fun issue(category: String, detail: String) {
        val connection = activeConnection
        val mixerName = activeMixerName
        submit {
            val now = android.os.SystemClock.elapsedRealtime()
            val issueKey = "$connection/$category"
            if (now - (issueTimes[issueKey] ?: -600_000L) >= 600_000L) {
                if (issueTimes.size >= 64) issueTimes.minByOrNull { it.value }?.key?.let(issueTimes::remove)
                issueTimes[issueKey] = now
                val redactedDetail = DiagnosticPrivacy.redact(detail)
                val safeDetail = "capture_connection=$connection; $redactedDetail"
                crashlytics.recordException(DiagnosticIssue(category, safeDetail))
                analytics.logEvent(
                    "diagnostic_issue",
                    Bundle().apply {
                        putSafeString("connection_id", connection)
                        putSafeString("category", category)
                        putSafeString("detail", redactedDetail)
                        putSafeString("mixer_name", mixerName)
                    }
                )
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
        val reportedMixerName = device?.let(::mixerDiagnosticName) ?: activeMixerName
        activeConnection = connection
        activeMixerName = device?.let(::mixerDiagnosticName) ?: "None"
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
                    analytics.logEvent(
                        "mixer_disconnected",
                        Bundle().apply {
                            putSafeString("connection_id", connection)
                            putSafeString("mixer_name", reportedMixerName)
                        }
                    )
                }
            } else {
                crashlytics.log(
                    "[Mixer] " + DiagnosticPrivacy.redact(
                        "connection=$connection; mixer=$mixerName; " +
                            "source=UsbAudioManager.inspectAndPublish\n${MixerDiagnosticReport.selected(device)}"
                    )
                )
                analytics.logEvent(
                    "mixer_connected",
                    Bundle().apply {
                        putSafeString("connection_id", connection)
                        putSafeString("mixer_name", mixerName)
                        putSafeString(
                            "profile",
                            device.pioneerMixerProfile?.name ?: device.allInOneProfile?.name ?: "Unknown"
                        )
                        putSafeString("usb_vendor", "%04x".format(device.vendorId))
                        putSafeString("usb_product", "%04x".format(device.productId))
                        putLong("channel_count", device.channelCount.toLong())
                        putLong("bit_depth", device.bitResolution.toLong())
                        putLong("subframe_bytes", device.subframeSize.toLong())
                        putLong("sample_rate", device.preferredSampleRate.toLong())
                        putLong("interface_number", device.streamingInterfaceNumber.toLong())
                        putLong("alternate_setting", device.activeAlternateSetting.toLong())
                        putSafeString("endpoint", "0x%02x".format(device.isochronousInEndpointAddress))
                        putLong("raw_iso_capture", if (device.requiresIsoCapture) 1L else 0L)
                        putLong(
                            "hardware_confirmed",
                            if (device.pioneerMixerProfile?.isHardwareConfirmed == true) 1L else 0L
                        )
                    }
                )
            }
        }
    }

    @Synchronized
    fun health(key: String, capturedSummary: String? = null) {
        if (!_enabled.value || !initialized) return
        val now = android.os.SystemClock.elapsedRealtime()
        val connection = activeConnection
        val mixerName = activeMixerName
        if (!healthGate.shouldLog(connection, key, now)) return
        submit {
            if (connection != activeConnection) return@submit
            val summary = capturedSummary ?: AudioEngine.getDiagnosticSummary()
            if (connection != activeConnection) return@submit
            crashlytics.log(
                "[CaptureHealth] " + DiagnosticPrivacy.redact(
                    "capture_connection=$connection; source=RecordingService.healthRunnable / " +
                        "UsbIsoAudioSource::diagnosticSummary\n$key\n$summary"
                )
            )
            analytics.logEvent(
                "capture_health",
                Bundle().apply {
                    putSafeString("connection_id", connection)
                    putSafeString("mixer_name", mixerName)
                    healthTelemetryValues(key, summary).forEach { (name, value) ->
                        when (value) {
                            is Long -> putLong(name, value)
                            is String -> putSafeString(name, value)
                        }
                    }
                }
            )
            val setup = setupTelemetryValues(summary)
            if (setup.isNotEmpty()) {
                analytics.logEvent("capture_setup", Bundle().apply {
                    putSafeString("connection_id", connection)
                    putSafeString("mixer_name", mixerName)
                    setup.forEach { (name, value) -> putLong(name, value) }
                })
            }
        }
    }

    internal fun setupTelemetryValues(summary: String): Map<String, Long> {
        val match = Regex(
            "capture_setup=rate_set_result:(-?\\d+) route_value:(-?\\d+) route_set_result:(-?\\d+)"
        ).find(summary) ?: return emptyMap()
        return listOf("rate_set_result", "route_value", "route_set_result")
            .mapIndexedNotNull { index, name ->
                match.groupValues[index + 1].toLongOrNull()?.let { name to it }
            }.toMap()
    }

    internal fun telemetryEventName(tag: String): String = when (tag) {
        "App" -> "diagnostics_started"
        "UsbConnection", "MixerConnection" -> "usb_connection"
        "RecordingSaved" -> "recording_saved"
        "Recovery" -> "recovery_detected"
        "RecordingState" -> "recording_state"
        "Streaming" -> "streaming_state"
        "StreamingAudio" -> "streaming_audio"
        else -> "diagnostic_event"
    }

    internal fun healthTelemetryValues(health: String, summary: String): Map<String, Any> {
        val values = linkedMapOf<String, Any>(
            "health_level" to health.substringBefore(':').trim(),
            "health_detail" to health.substringAfter(':', "").trim()
        )
        fun numbers(pattern: Regex, names: List<String>) {
            val match = pattern.find(summary) ?: return
            names.forEachIndexed { index, name ->
                match.groupValues.getOrNull(index + 1)?.toLongOrNull()?.let { values[name] = it }
            }
        }
        Regex("(?m)^profile=([^\\r\\n]+)").find(summary)?.groupValues?.get(1)?.let {
            values["profile"] = it
        }
        numbers(Regex("sample_rate=requested:(\\d+) opened:(\\d+)"), listOf("requested_rate", "opened_rate"))
        numbers(
            Regex("channel_offset=requested:(-?\\d+) resolved:(-?\\d+)"),
            listOf("requested_channel", "resolved_channel")
        )
        (values["requested_channel"] as? Long)?.let {
            values["requested_pair"] = if (it < 0) "auto" else "usb_${it + 1}_${it + 2}"
        }
        (values["resolved_channel"] as? Long)?.let {
            values["resolved_pair"] = if (it < 0) "unresolved" else "usb_${it + 1}_${it + 2}"
        }
        numbers(Regex("route_fallback_stage=(\\d+)"), listOf("route_fallback_stage"))
        Regex("playback_keepalive=required:(true|false) claimed_if:-?\\d+ transfers:(\\d+)")
            .find(summary)?.let { match ->
                values["playback_required"] = if (match.groupValues[1] == "true") 1L else 0L
                match.groupValues[2].toLongOrNull()?.let { values["playback_transfers"] = it }
            }
        numbers(
            Regex(
                "transfers=completed:(\\d+) missed:(\\d+) empty:(\\d+) partial:(\\d+) " +
                    "bytes:(\\d+) nonzero_bytes:(\\d+) resubmit_failures:(\\d+)"
            ),
            listOf(
                "packets_completed", "packets_missed", "packets_empty", "packets_partial",
                "bytes_received", "nonzero_bytes", "resubmit_failures"
            )
        )
        val channelPeaks = Regex("USB(\\d+)=(-?\\d+(?:\\.\\d+)?)dBFS\\((active|below threshold)\\)")
            .findAll(summary)
            .mapNotNull { match ->
                val channel = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val peak = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                Triple(channel, peak, match.groupValues[3] == "active")
            }
            .toList()
        if (channelPeaks.isNotEmpty()) {
            values["active_channels"] = channelPeaks.filter { it.third }.joinToString(",") { it.first.toString() }
                .ifEmpty { "none" }
            channelPeaks.maxByOrNull { it.second }?.let { loudest ->
                values["loudest_channel"] = loudest.first.toLong()
                values["loudest_db_x10"] = (loudest.second * 10).roundToLong()
            }
            (values["resolved_channel"] as? Long)?.takeIf { it >= 0 }?.let { offset ->
                channelPeaks.filter { it.first == offset.toInt() + 1 || it.first == offset.toInt() + 2 }
                    .maxOfOrNull { it.second }
                    ?.let { values["resolved_pair_db_x10"] = (it * 10).roundToLong() }
            }
        }
        return values
    }

    private fun Bundle.putSafeString(name: String, value: String) {
        putString(name, DiagnosticPrivacy.redact(value).replace(Regex("\\s+"), " ").take(100))
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
