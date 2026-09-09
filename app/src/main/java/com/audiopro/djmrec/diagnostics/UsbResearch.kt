package com.audiopro.djmrec.diagnostics

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import com.audiopro.djmrec.BuildConfig

/** Fixed, read-only requests recovered from DJM-A9_Setup64.dll (see research document). */
object A9StateRequests {
    data class Read(val label: String, val value: Int, val index: Int)
    val reads = (1..4).map { Read("Input selector channel $it", it, 0x8002) } +
        Read("Serato active", 0, 0x8004)
}

object UsbResearch {
    fun snapshot(context: Context): String {
        check(BuildConfig.PROTOCOL_RESEARCH && ProtocolTrace.active)
        val manager = context.getSystemService(UsbManager::class.java)
        var count = 0
        manager.deviceList.values.forEach { device ->
            ProtocolTrace.event("usb-device", "vid=${device.vendorId} pid=${device.productId} interfaces=${device.interfaceCount} permission=${manager.hasPermission(device)}")
            if (manager.hasPermission(device)) manager.openDevice(device)?.let { connection ->
                try {
                    ProtocolTrace.event("usb-descriptors", "vid=${device.vendorId} pid=${device.productId}", connection.rawDescriptors ?: byteArrayOf())
                    count++
                } finally { connection.close() }
            }
        }
        return "Descriptors captured from $count permitted devices."
    }

    fun readA9(context: Context): String {
        check(BuildConfig.PROTOCOL_RESEARCH && ProtocolTrace.active)
        check(com.audiopro.djmrec.audio.AudioEngine.getDiagnosticSummary().lineSequence().none { it == "stream_open=true" }) {
            "Stop recording and monitoring before running state probes."
        }
        val manager = context.getSystemService(UsbManager::class.java)
        val devices = manager.deviceList.values.filter { it.vendorId == 0x2b73 && it.productId == 0x003c }
        require(devices.size == 1) { "Connect exactly one DJM-A9 first." }
        val device = devices.single()
        require(manager.hasPermission(device)) { "Select the mixer in USB input settings and grant USB access first." }
        val connection = manager.openDevice(device) ?: error("Could not open mixer")
        return try {
            A9StateRequests.reads.joinToString("\n") { read ->
                check(ProtocolTrace.active) { "Capture stopped" }
                val bytes = ByteArray(2)
                val result = ProtocolTrace.control(connection, 0xc0, 0, read.value, read.index, bytes, 2, 500)
                val summary = "${read.label}: result=$result data=${bytes.take(result.coerceIn(0, 2)).joinToString(" ") { b -> "%02x".format(b.toInt() and 255) }}"
                ProtocolTrace.event("usb-state-read", summary)
                summary
            }
        } finally { connection.close() }
    }
}

/** Reads Android MIDI output ports only. No MIDI writes, interface claims, or hidden-register scans. */
class UsbMidiListener(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(MidiManager::class.java)
    private val devices = mutableListOf<MidiDevice>()
    private val ports = mutableListOf<MidiOutputPort>()
    private var generation = 0
    @Synchronized fun start(): String {
        check(BuildConfig.PROTOCOL_RESEARCH && ProtocolTrace.active)
        close()
        val token = generation
        val candidates = manager?.devices.orEmpty().filter { info ->
            @Suppress("DEPRECATION") val usb = info.properties.getParcelable<UsbDevice>(MidiDeviceInfo.PROPERTY_USB_DEVICE)
            usb?.vendorId == 0x2b73 && usb.productId == 0x003c && info.outputPortCount > 0
        }
        candidates.forEach { info ->
            manager.openDevice(info, { device ->
                synchronized(this) {
                    if (device == null) ProtocolTrace.event("midi", "Failed to open device ${info.id}")
                    else if (token != generation || !ProtocolTrace.active) device.close()
                    else {
                        devices.add(device)
                        repeat(info.outputPortCount) { portNumber ->
                            device.openOutputPort(portNumber)?.let { port ->
                                ports.add(port)
                                port.connect(object : MidiReceiver() {
                                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                                        if (ProtocolTrace.active) ProtocolTrace.event("usb-midi", "device=${info.id} port=$portNumber midiTimestampNs=$timestamp; Android MIDI bytes", data.copyOfRange(offset, offset + count))
                                    }
                                })
                                ProtocolTrace.event("midi", "Listening device=${info.id} output=$portNumber")
                            } ?: ProtocolTrace.event("midi", "Output unavailable device=${info.id} port=$portNumber")
                        }
                    }
                }
            }, Handler(Looper.getMainLooper()))
        }
        return if (candidates.isEmpty()) "No DJM-A9 MIDI output exposed by Android. This does not rule out a private USB channel."
        else "Opening ${candidates.size} MIDI devices. Move faders or change tracks; inspect trace for received bytes."
    }
    @Synchronized override fun close() {
        generation++
        ports.forEach { runCatching { it.close() } }; ports.clear()
        devices.forEach { runCatching { it.close() } }; devices.clear()
    }
}
