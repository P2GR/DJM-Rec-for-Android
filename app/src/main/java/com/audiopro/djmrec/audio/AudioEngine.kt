package com.audiopro.djmrec.audio

/**
 * Thin Kotlin/JNI boundary over the native `UsbAudioEngine`. The engine owns exactly one
 * AAudio exclusive stream + one ring buffer + one encoder at a time, so this wrapper is a
 * singleton object rather than a class — mirrors the native side's lifetime.
 *
 * All native calls are safe to invoke from any thread; the native side takes its own locks
 * around state transitions. However callers should still serialize start/stop/pause calls
 * (the ViewModel does this) to avoid nonsensical overlapping transitions.
 */
object AudioEngine {

    init {
        System.loadLibrary("djmrec_audio")
    }

    /**
     * Opens the exclusive, low-latency AAudio input stream bound to [audioManagerDeviceId] and
     * allocates the ring buffer. Must be called before [startRecording].
     *
     * @return the sample rate AAudio actually negotiated (may differ from a hint if the
     *   hardware does not support it), or -1 on failure.
     */
    external fun open(
        audioManagerDeviceId: Int,
        sampleRateHint: Int,
        channelCount: Int,
        bitDepth: Int
    ): Int

    /**
     * Opens the raw libusb isochronous capture path instead of AAudio/AudioRecord, extracting
     * a stereo pair out of a wider multichannel USB Audio interface. This exists because
     * AAudio has no API to select an arbitrary channel *offset* out of a multichannel UAC2
     * interface -- it only ever gives you channels 1/2 (or all N channels, undifferentiated).
     * Mixers like the Pioneer DJM-A9 put the Master Mix on channels 9/10 of a combined
     * 12-channel interface, which this path reaches directly via a raw isochronous transfer
     * loop, bypassing the OS audio stack entirely.
     *
     * @param fd an *open* `UsbDeviceConnection.getFileDescriptor()` -- the connection must be
     *   kept open (not `.close()`'d) for the entire capture session; see
     *   `UsbAudioManager.openIsoCaptureHandle()`.
     * @param interfaceNumber the AudioStreaming interface number (not AudioControl).
     * @param alternateSetting the alt setting whose isochronous endpoint carries audio (UAC2
     *   devices idle on alt setting 0, which has no endpoint / zero bandwidth).
     * @param endpointAddress the isochronous IN endpoint address (e.g. `0x81`).
     * @param maxPacketSize `wMaxPacketSize` from that endpoint's descriptor.
     * @param totalChannels total interleaved channel count in the *wire* format (e.g. 12).
     * @param subframeSize bytes per sample container on the wire (1/2/3/4).
     * @param bitResolution significant bits per sample within that container (e.g. 24).
     * @param extractChannelOffset 0-indexed first channel of the stereo pair to pull out (e.g.
     *   8 for channels 9/10).
     * @param sampleRateHint trusted as-is; nothing in this path negotiates a rate back from
     *   the device the way AAudio does.
     * @return `sampleRateHint` on success, or -1 on failure.
     */
    external fun openUsbIso(
        fd: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        totalChannels: Int,
        subframeSize: Int,
        bitResolution: Int,
        extractChannelOffset: Int,
        sampleRateHint: Int
    ): Int

    external fun openRootAlsa(
        card: Int,
        device: Int,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        extractChannelOffset: Int
    ): Int

    /**
     * Begins pulling from the ring buffer into the selected encoder and writing to [outputPath].
     * [format] is [RecordingFormat.nativeValue].
     */
    external fun startRecording(outputPath: String, format: Int, mp3BitrateKbps: Int): Boolean

    external fun isMp3EncodingAvailable(): Boolean

    external fun pauseRecording()

    external fun resumeRecording()

    /** Stops the encoder, flushes/patches file headers, and returns the final duration in ms. */
    external fun stopRecording(): Long

    /** Tears down the AAudio stream and ring buffer. Safe to call even if never opened. */
    external fun close()

    /**
     * Instantaneous stereo meter reading, in the layout
     * `[leftPeakDb, leftRmsDb, rightPeakDb, rightRmsDb]`, already scaled to dBFS in
     * [-60, 0]. Intended to be polled at UI frame rate (~30-60 Hz) from a coroutine.
     */
    external fun getLevels(): FloatArray

    external fun isClipping(): Boolean

    external fun getElapsedMillis(): Long

    /** Underrun/overrun counters on the ring buffer, useful for diagnosing dropped audio. */
    external fun getXRunCount(): Int

    /**
     * RGB waveform snapshot: returns `kWaveformBinCount * 4` floats in the layout
     * `[amp0, low0, mid0, high0, amp1, low1, mid1, high1, ...]`, each in [0, 1].
     * Low ≈ red, mid ≈ green, high ≈ blue — the CDJ-3000 color mapping.
     * Intended to be polled at ~15–30 Hz from the UI thread.
     */
    external fun getWaveformBins(): FloatArray
}
