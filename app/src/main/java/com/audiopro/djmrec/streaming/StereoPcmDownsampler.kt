package com.audiopro.djmrec.streaming

import kotlin.math.*

/** Streaming low-pass FIR before integer decimation. Stereo and filter history survive blocks. */
internal class StereoPcmDownsampler(inputRate: Int, outputRate: Int) {
    private val ratio = inputRate / outputRate
    private val taps = 63
    private val left = DoubleArray(taps)
    private val right = DoubleArray(taps)
    private val weights = DoubleArray(taps)
    private var cursor = 0
    private var phase = 0
    init {
        require(outputRate > 0 && inputRate % outputRate == 0 && ratio in 1..4)
        val cutoff = 0.45 / ratio
        for (i in weights.indices) {
            val x = i - (taps - 1) / 2
            weights[i] = (if (x == 0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)) *
                (0.54 - 0.46 * cos(2 * PI * i / (taps - 1)))
        }
        val sum = weights.sum()
        for (i in weights.indices) weights[i] /= sum
    }

    fun convert(input: ByteArray, size: Int): ByteArray {
        require(size in 0..input.size && size % 4 == 0)
        if (ratio == 1) return input.copyOf(size)
        val output = ByteArray(((size / 4 + phase) / ratio) * 4)
        var written = 0
        fun sample(offset: Int) = ((input[offset].toInt() and 255) or
            (input[offset + 1].toInt() shl 8)).toShort().toDouble()
        for (offset in 0 until size step 4) {
            left[cursor] = sample(offset); right[cursor] = sample(offset + 2)
            if (++phase == ratio) {
                phase = 0
                var l = 0.0; var r = 0.0
                for (tap in 0 until taps) {
                    val index = (cursor - tap + taps) % taps
                    l += left[index] * weights[tap]; r += right[index] * weights[tap]
                }
                val pcmLeft = l.roundToInt().coerceIn(-32768, 32767)
                val pcmRight = r.roundToInt().coerceIn(-32768, 32767)
                output[written++] = pcmLeft.toByte(); output[written++] = (pcmLeft shr 8).toByte()
                output[written++] = pcmRight.toByte(); output[written++] = (pcmRight shr 8).toByte()
            }
            cursor = (cursor + 1) % taps
        }
        return output
    }
}
