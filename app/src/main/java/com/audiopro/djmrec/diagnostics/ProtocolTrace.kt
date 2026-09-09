package com.audiopro.djmrec.diagnostics

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.prolink.WireObserver
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Explicit opt-in only. Raw payloads never go to Logcat, analytics, or Crashlytics. */
object ProtocolTrace : WireObserver {
    private val buffer = TraceBuffer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var drain: Job? = null
    private var generation = 0L
    val active: Boolean get() = BuildConfig.PROTOCOL_RESEARCH && buffer.isActive()
    fun snapshot() = buffer.snapshot()
    @Synchronized fun start() {
        check(BuildConfig.PROTOCOL_RESEARCH)
        stop()
        buffer.start()
        AudioEngine.setProtocolTracing(true)
        event("session", "version=${BuildConfig.VERSION_NAME}; raw PCM excluded; TCP chunks are not message boundaries")
        val token = generation
        drain = scope.launch {
            while (isActive && active) { drainNative(); delay(200) }
            synchronized(this@ProtocolTrace) {
                if (token == generation) AudioEngine.setProtocolTracing(false)
            }
        }
    }
    @Synchronized fun stop() {
        generation++
        drain?.cancel(); drain = null
        if (BuildConfig.PROTOCOL_RESEARCH) {
            AudioEngine.setProtocolTracing(false)
            drainNative()
        }
        if (buffer.isActive()) buffer.stop()
    }
    @Synchronized fun clear() { stop(); buffer.clear() }
    @Synchronized private fun drainNative() {
        val text = AudioEngine.drainProtocolTrace()
        if (text.isNotEmpty()) event("usb-native-batch", "JSON lines; native steady-clock timestamps", text.toByteArray())
    }
    fun event(kind: String, detail: String, bytes: ByteArray = byteArrayOf()) {
        if (active) buffer.add(kind, detail, bytes)
    }
    override fun packet(transport: String, direction: String, peer: String, port: Int, bytes: ByteArray) =
        event(transport, "$direction $peer:$port", bytes)

    fun control(connection: UsbDeviceConnection, type: Int, request: Int, value: Int, index: Int,
                data: ByteArray?, length: Int, timeout: Int): Int {
        val result = connection.controlTransfer(type, request, value, index, data, length, timeout)
        if (active) {
            val setup = byteArrayOf(type.toByte(), request.toByte(), value.toByte(), (value shr 8).toByte(),
                index.toByte(), (index shr 8).toByte(), length.toByte(), (length shr 8).toByte())
            val payload = data?.copyOf(minOf(data.size, if (type and 0x80 != 0) result.coerceAtLeast(0) else length)) ?: byteArrayOf()
            event("usb-control", "result=$result; setup[8] then ${if (type and 0x80 != 0) "received" else "attempted OUT"} data", setup + payload)
        }
        return result
    }

    /** Stops first so export is a consistent snapshot. Last export only; explicit share sheet follows. */
    @Synchronized fun export(context: Context): File {
        stop()
        val snapshot = buffer.snapshot()
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        return File(dir, "protocol-trace.ndjson").also { file ->
            file.bufferedWriter().use { out ->
                out.appendLine(JSONObject().put("schema", 1).put("version", BuildConfig.VERSION_NAME)
                    .put("reason", snapshot.reason).put("dropped", snapshot.dropped).toString())
                snapshot.events.forEach { e ->
                    out.appendLine(JSONObject().put("wallMs", e.wallMs).put("monotonicNs", e.monotonicNs)
                        .put("kind", e.kind).put("detail", e.detail).put("length", e.originalLength)
                        .put("capturedLength", e.bytes.size).put("hex", e.bytes.joinToString("") { "%02x".format(it.toInt() and 255) }).toString())
                }
            }
        }
    }
}
