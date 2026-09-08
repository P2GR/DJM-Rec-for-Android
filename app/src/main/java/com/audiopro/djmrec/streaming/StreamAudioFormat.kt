package com.audiopro.djmrec.streaming

internal data class StreamAudioFormat(val captureRate: Int, val encoderRate: Int) {
    companion object {
        fun fromCapture(rate: Int): StreamAudioFormat? = when (rate) {
            88200 -> StreamAudioFormat(rate, 44100)
            96000, 192000 -> StreamAudioFormat(rate, 48000)
            176400 -> StreamAudioFormat(rate, 44100)
            8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000 -> StreamAudioFormat(rate, rate)
            else -> null
        }
    }
}
