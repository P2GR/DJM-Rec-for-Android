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
     * Mixers like the Pioneer DJM-A9 expose a combined 12-channel interface. This path can
     * select any stereo pair and, for the DJM-A9, routes MIX (REC OUT) to that pair first.
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
     * @param extractChannelOffset 0-indexed first channel of the stereo pair to pull out.
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
        clockControlInterfaceNumber: Int,
        clockSourceId: Int,
        clockSupportsFrequencySet: Boolean,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        vendorId: Int,
        productId: Int,
        rawDescriptors: ByteArray,
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
    external fun startRecording(outputPath: String, format: Int): Boolean

    external fun startRecordingFd(fd: Int, format: Int): Boolean

    external fun rollRecordingFd(fd: Int, format: Int): Boolean

    /** Flushes a recoverable checkpoint and returns current part bytes, or -1 on failure. */
    external fun checkpointRecording(): Long

    external fun getRecordingErrorCode(): Int

    external fun isStreamOpen(): Boolean

    /** Starts/stops independent stereo PCM tap used by AAC livestream encoder. */
    external fun startLivePcm(): Boolean

    external fun stopLivePcm()

    /** Reads little-endian stereo PCM16 without consuming recording writer data. */
    external fun readLivePcm16(destination: ByteArray): Int

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

    /** [completed, missed, empty, partial, bytes, nonZeroBytes, resubmitFailures] for raw USB capture. */
    external fun getUsbIsoTransferStats(): LongArray

    /** Structured native pipeline/session snapshot included in release diagnostic reports. */
    external fun getDiagnosticSummary(): String

    /**
     * RGB waveform snapshot: returns `kWaveformBinCount * 4` floats in the layout
     * `[amp0, low0, mid0, high0, amp1, low1, mid1, high1, ...]`, each in [0, 1].
     * Low ≈ red, mid ≈ green, high ≈ blue — the CDJ-3000 color mapping.
     * Intended to be polled at ~15–30 Hz from the UI thread.
     */
    external fun getWaveformBins(): FloatArray

    /** Enables native frequency analysis for the optional live waveform. */
    external fun setWaveformEnabled(enabled: Boolean)
}
