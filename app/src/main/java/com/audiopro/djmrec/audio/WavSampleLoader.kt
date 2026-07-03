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

        fun le16(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        fun le32(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

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
        var fmtAudioFormat = 0
        var dataOffset = -1
        var dataSize = 0

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4); pos += 4
            val chunkSize = le32(pos)
            pos += 4
            if (chunkSize < 0 || pos + chunkSize > bytes.size) return Sample()

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16) return Sample()
                    fmtAudioFormat = le16(pos)
                    fmtChannels = le16(pos + 2)
                    fmtSampleRate = le32(pos + 4)
                    fmtBitsPerSample = le16(pos + 14)
                    pos += chunkSize
                }
                "data" -> { dataOffset = pos; dataSize = chunkSize; pos += chunkSize }
                else -> pos += chunkSize
            }
            if (chunkSize % 2 == 1 && pos < bytes.size) pos += 1
        }

        if (dataOffset < 0 || dataSize == 0 || fmtChannels == 0) return Sample()
        if (fmtAudioFormat != 1 && fmtAudioFormat != 3 && fmtAudioFormat != 0xFFFE) return Sample()

        val bytesPerSample = fmtBitsPerSample / 8
        if (bytesPerSample <= 0 || dataOffset + dataSize > bytes.size) return Sample()
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
                    8 -> ((bytes[sPtr].toInt() and 0xFF) - 128).toFloat() / 128f
                    16 -> {
                        val s = le16(sPtr)
                        (s.toShort().toFloat()) / 32768f
                    }
                    24 -> {
                        var s = (bytes[sPtr].toInt() and 0xFF) or ((bytes[sPtr + 1].toInt() and 0xFF) shl 8) or ((bytes[sPtr + 2].toInt() and 0xFF) shl 16)
                        if (s and 0x800000 != 0) s = s or 0xFF000000.toInt()
                        s.toFloat() / 8388608f
                    }
                    32 -> if (fmtAudioFormat == 3) {
                        java.lang.Float.intBitsToFloat(le32(sPtr))
                    } else {
                        le32(sPtr).toFloat() / 2147483648f
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
