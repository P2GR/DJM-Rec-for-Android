package com.audiopro.djmrec.storage

import kotlin.math.max

object RecordingStoragePolicy {
    const val CRITICAL_FREE_BYTES = 64L * 1024 * 1024
    const val MINIMUM_START_BYTES = 256L * 1024 * 1024
    const val WAV_ROLL_BYTES = 3_750_000_000L

    fun worstCaseBytesPerSecond(sampleRate: Int, channelCount: Int, bitDepth: Int): Long {
        val bytesPerSample = (bitDepth.coerceAtLeast(8) + 7) / 8
        return sampleRate.coerceAtLeast(1).toLong() * channelCount.coerceAtLeast(1) * bytesPerSample
    }

    fun requiredStartBytes(bytesPerSecond: Long): Long =
        max(MINIMUM_START_BYTES, bytesPerSecond.coerceAtLeast(1) * 10 * 60)

    fun remainingSeconds(freeBytes: Long, bytesPerSecond: Long): Long =
        freeBytes.coerceAtLeast(0) / bytesPerSecond.coerceAtLeast(1)

    fun shouldRollWav(bytesWritten: Long): Boolean = bytesWritten >= WAV_ROLL_BYTES
}
