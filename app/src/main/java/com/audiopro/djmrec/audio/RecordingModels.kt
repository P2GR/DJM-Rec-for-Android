package com.audiopro.djmrec.audio

/** Container formats the native encoder chain supports. Ordinal maps 1:1 to the C++ enum. */
enum class RecordingFormat(val nativeValue: Int, val extension: String) {
    WAV(0, "wav"),
    FLAC(1, "flac"),
    MP3(2, "mp3")
}

/** Bit depth requested from the hardware. 24-bit is packed into a 32-bit container by AAudio. */
enum class BitDepth(val bits: Int) {
    PCM_16(16),
    PCM_24(24),
    PCM_32(32)
}

sealed class RecordingState {
    data object Idle : RecordingState()
    data object Preparing : RecordingState()
    /** Audio stream is open and meters/waveform are live, but no file is being written. */
    data object Monitoring : RecordingState()
    data object Recording : RecordingState()
    data object Paused : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/** Real-time peak/RMS reading for one channel, already converted to dBFS by the native meter. */
data class ChannelLevel(val peakDb: Float, val rmsDb: Float, val isClipping: Boolean)

data class StereoLevels(val left: ChannelLevel, val right: ChannelLevel)
