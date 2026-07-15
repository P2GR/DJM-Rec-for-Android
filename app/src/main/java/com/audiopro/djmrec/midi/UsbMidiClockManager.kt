package com.audiopro.djmrec.midi

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

data class MidiClockState(
    val bpm: Float = 0f,
    val beatPhase: Float = 0f,
    val locked: Boolean = false,
    val deviceName: String? = null
)

/** Receives DJM MIDI Clock (0xF8, 24 pulses per quarter note) over its MIDIStreaming interface. */
class UsbMidiClockManager(private val context: Context) {
    companion object {
        private const val TAG = "UsbMidiClock"
        private const val CLOCK = 0xF8
        private const val START = 0xFA
        private const val CONTINUE = 0xFB
        private const val STOP = 0xFC
        private const val PULSES_PER_BEAT = 24
        private const val CLOCK_TIMEOUT_NS = 2_000_000_000L
    }

    private val midiManager = context.getSystemService(MidiManager::class.java)
    private val thread = HandlerThread("DjmMidiClock").apply { start() }
    private val handler = Handler(thread.looper)
    private val tickTimes = ArrayDeque<Long>()
    private val _state = MutableStateFlow(MidiClockState())
    val state: StateFlow<MidiClockState> = _state.asStateFlow()

    private var activeInfoId = -1
    private var openingInfoId = -1
    private var device: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null
    private var pulse = 0
    private var smoothedBpm = 0f
    private var lastClockNs = 0L

    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (index in offset until offset + count) {
                when (data[index].toInt() and 0xFF) {
                    CLOCK -> onClock(if (timestamp > 0L) timestamp else System.nanoTime())
                    START -> resetPhase()
                    CONTINUE -> pulse = 0
                    STOP -> _state.value = _state.value.copy(locked = false)
                }
            }
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = considerDevice(info)

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info.id == activeInfoId || info.id == openingInfoId) closeDevice()
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (lastClockNs > 0L && System.nanoTime() - lastClockNs > CLOCK_TIMEOUT_NS) {
                tickTimes.clear()
                smoothedBpm = 0f
                _state.value = _state.value.copy(bpm = 0f, locked = false)
            }
            handler.postDelayed(this, 500L)
        }
    }

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            Log.w(TAG, "Android MIDI feature unavailable")
            return
        }
        midiManager.registerDeviceCallback(callback, handler)
        midiManager.devices.forEach(::considerDevice)
        handler.post(watchdog)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        midiManager.unregisterDeviceCallback(callback)
        closeDevice()
        thread.quitSafely()
    }

    private fun considerDevice(info: MidiDeviceInfo) {
        if (activeInfoId >= 0 || openingInfoId >= 0 || info.outputPortCount <= 0) return
        val identity = listOf(
            info.properties.getString(MidiDeviceInfo.PROPERTY_NAME),
            info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
        ).joinToString(" ").lowercase()
        if ("djm" !in identity && "pioneer" !in identity && "alphatheta" !in identity) return

        openingInfoId = info.id
        midiManager.openDevice(info, { opened ->
            openingInfoId = -1
            if (opened == null) {
                Log.w(TAG, "Could not open MIDI device: $identity")
                return@openDevice
            }
            val port = opened.openOutputPort(0)
            if (port == null) {
                opened.close()
                Log.w(TAG, "DJM MIDI device has no readable output port")
                return@openDevice
            }
            device = opened
            outputPort = port
            activeInfoId = info.id
            port.connect(receiver)
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: "DJM MIDI"
            _state.value = MidiClockState(deviceName = name)
            Log.i(TAG, "Listening for MIDI Clock from $name")
        }, handler)
    }

    private fun onClock(timestampNs: Long) {
        val now = if (timestampNs > lastClockNs) timestampNs else System.nanoTime()
        lastClockNs = now
        tickTimes.addLast(now)
        while (tickTimes.size > 97) tickTimes.removeFirst()
        pulse = (pulse + 1) % PULSES_PER_BEAT

        if (tickTimes.size >= PULSES_PER_BEAT + 1) {
            val reference = tickTimes.elementAt(tickTimes.size - PULSES_PER_BEAT - 1)
            val beatDurationNs = now - reference
            if (beatDurationNs > 0L) {
                val candidate = 60_000_000_000.0 / beatDurationNs.toDouble()
                if (candidate in 40.0..300.0) {
                    smoothedBpm = if (smoothedBpm <= 0f) candidate.toFloat()
                    else smoothedBpm * 0.82f + candidate.toFloat() * 0.18f
                }
            }
        }
        _state.value = _state.value.copy(
            bpm = smoothedBpm,
            beatPhase = pulse.toFloat() / PULSES_PER_BEAT,
            locked = smoothedBpm > 0f && tickTimes.size >= PULSES_PER_BEAT * 2
        )
    }

    private fun resetPhase() {
        tickTimes.clear()
        pulse = 0
        smoothedBpm = 0f
        lastClockNs = 0L
        _state.value = _state.value.copy(bpm = 0f, beatPhase = 0f, locked = false)
    }

    private fun closeDevice() {
        outputPort?.disconnect(receiver)
        outputPort?.close()
        device?.close()
        outputPort = null
        device = null
        activeInfoId = -1
        openingInfoId = -1
        resetPhase()
        _state.value = MidiClockState()
    }
}
