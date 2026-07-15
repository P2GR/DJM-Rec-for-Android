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

    /** [completed, missed, empty, partial, bytes, nonZeroBytes, resubmitFailures] for raw USB capture. */
    external fun getUsbIsoTransferStats(): LongArray

    /**
     * RGB waveform snapshot: returns `kWaveformBinCount * 4` floats in the layout
     * `[amp0, low0, mid0, high0, amp1, low1, mid1, high1, ...]`, each in [0, 1].
     * Low ≈ red, mid ≈ green, high ≈ blue — the CDJ-3000 color mapping.
     * Intended to be polled at ~15–30 Hz from the UI thread.
     */
    external fun getWaveformBins(): FloatArray

    // --- BPM detector (mic input) ---

    /** Starts microphone capture for BPM detection. Returns sample rate on success, -1 on failure. */
    external fun startMicCapture(): Int

    /** Stops microphone capture. Safe to call even if not active. */
    external fun stopMicCapture()

    /**
     * Returns [bpm, confidence, beatPhase, leadingBand, locked] where:
     *   bpm: detected BPM (0 if not yet detected)
     *   confidence: [0, 1] how reliable the detection is
     *   beatPhase: [0, 1) position within the current beat
     *   leadingBand: 0=low, 1=mid, 2=high
     *   locked: 1.0 if detection has stabilised, 0.0 if still listening
     */
    external fun getBpmResult(): FloatArray

    // --- RMX-1000 engine ---

    /** Open an AAudio output stream for RMX effects playback. Returns sample rate on success, -1 on failure. */
    external fun openRmxOutput(deviceId: Int, sampleRate: Int, channelCount: Int): Int

    /** Close the RMX output stream. */
    external fun closeRmxOutput()

    /** Trigger a one-shot sample without looping. */
    external fun triggerRmxSample(soundOrdinal: Int, gain: Float, pitchRatio: Float)

    /** Trigger a looping sample that wraps at loopLengthSamples frames. */
    external fun triggerRmxSampleLooping(soundOrdinal: Int, gain: Float, pitchRatio: Float, loopLengthSamples: Int)

    /** Update the loop length on an already-playing sample voice. */
    external fun updateRmxVoiceLoop(soundOrdinal: Int, loopLengthSamples: Int)

    /** Update pitch ratio on an already-playing sample voice. */
    external fun updateRmxVoicePitch(soundOrdinal: Int, pitchRatio: Float)

    /** Stop a specific sample sound. */
    external fun stopRmxSample(soundOrdinal: Int)

    /** Stop all playing samples immediately. */
    external fun stopAllRmxSamples()

    /** Set an effect parameter. effectId: 0=bitcrush, 1=filterCutoff, 2=filterType, 3=delayMix, 4=delayTime, 5=delayFb, 6=reverbSize, 7=reverbMix */
    external fun setRmxEffectParam(effectId: Int, value: Float)

    /** Load a WAV sample into the player. data is mono float at 44100 Hz. */
    external fun loadRmxSample(soundOrdinal: Int, data: FloatArray)

    /** Feed BPM info into the RMX beat clock. */
    external fun updateRmxBeatClock(bpm: Float, beatPhase: Float, locked: Boolean)

    /** Set manual BPM override for RMX beat clock. */
    external fun setRmxManualBpm(bpm: Float)

    /** Clear manual BPM and go back to auto-detection. */
    external fun clearRmxManualBpm()
}
