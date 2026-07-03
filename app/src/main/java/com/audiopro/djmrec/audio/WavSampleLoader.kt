package com.audiopro.djmrec.audio

/**
 * Pure-Kotlin WAV parser that mirrors the native WavSampleLoader.
 * Parses WAV files from memory and provides float sample data for the RMX engine.
 */
object WavSampleLoader {

    data class Sample(
        val data: FloatArray = FloatArray(0),
        val sampleRate: Int = 0,
        val originalChannels: Int = 0,
        val originalBitDepth: Int = 0,
        val valid: Boolean = false
    )

    fun loadFromMemory(bytes: ByteArray): Sample {
        if (bytes.size < 44) return Sample()

        var pos = 0

        // RIFF header
        val riffId = String(bytes, pos, 4); pos += 4
        if (riffId != "RIFF") return Sample()
        pos += 4 // chunk size
        val waveId = String(bytes, pos, 4); pos += 4
        if (waveId != "WAVE") return Sample()

        var fmtChannels = 0
        var fmtSampleRate = 0
        var fmtBitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4); pos += 4
            val chunkSize = ((bytes[pos + 3].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                (bytes[pos].toInt() and 0xFF)
            pos += 4

            when (chunkId) {
                "fmt " -> {
                    val audioFmt = ((bytes[pos + 1].toInt() and 0xFF) shl 8) or (bytes[pos].toInt() and 0xFF)
                    if (audioFmt != 1) return Sample() // PCM only
                    fmtChannels = ((bytes[pos + 3].toInt() and 0xFF) shl 8) or (bytes[pos + 2].toInt() and 0xFF)
                    fmtSampleRate = ((bytes[pos + 7].toInt() and 0xFF) shl 24) or
                        ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                        (bytes[pos + 4].toInt() and 0xFF)
                    fmtBitsPerSample = ((bytes[pos + 15].toInt() and 0xFF) shl 8) or (bytes[pos + 14].toInt() and 0xFF)
                    pos += chunkSize
                }
                "data" -> { dataOffset = pos; dataSize = chunkSize; pos += chunkSize }
                else -> pos += chunkSize
            }
        }

        if (dataOffset < 0 || dataSize == 0 || fmtChannels == 0) return Sample()

        val bytesPerSample = fmtBitsPerSample / 8
        val totalFrames = dataSize / (fmtChannels * bytesPerSample)
        val resampleRatio = fmtSampleRate.toDouble() / 44100.0
        val outFrames = (totalFrames / resampleRatio).toInt()
        if (outFrames <= 0) return Sample()

        val out = FloatArray(outFrames)
        var accumPos = 0.0
        for (i in 0 until outFrames) {
            val srcFrame = accumPos.toInt()
            if (srcFrame >= totalFrames) break
            accumPos += resampleRatio
            val framePtr = dataOffset + srcFrame * fmtChannels * bytesPerSample
            var monoSum = 0f
            for (ch in 0 until fmtChannels) {
                val sPtr = framePtr + ch * bytesPerSample
                val v: Float = when (fmtBitsPerSample) {
                    16 -> {
                        val s = ((bytes[sPtr + 1].toInt() and 0xFF) shl 8) or (bytes[sPtr].toInt() and 0xFF)
                        (s.toShort().toFloat()) / 32768f
                    }
                    24 -> {
                        var s = (bytes[sPtr].toInt() and 0xFF) or ((bytes[sPtr + 1].toInt() and 0xFF) shl 8) or ((bytes[sPtr + 2].toInt() and 0xFF) shl 16)
                        if (s and 0x800000 != 0) s = s or 0xFF000000.toInt()
                        s.toFloat() / 8388608f
                    }
                    else -> 0f
                }
                monoSum += v
            }
            out[i] = monoSum / fmtChannels.toFloat()
        }

        return Sample(out, fmtSampleRate, fmtChannels, fmtBitsPerSample, true)
    }
}
