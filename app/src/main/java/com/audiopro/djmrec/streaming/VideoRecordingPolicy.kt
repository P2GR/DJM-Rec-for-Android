package com.audiopro.djmrec.streaming

/** Bitrates, segment length and storage rules for MP4 video saved to the phone. */
object VideoRecordingPolicy {
    /** A killed app loses at most the MP4 segment being written, never the whole set. */
    const val SEGMENT_MINUTES = 10
    const val SEGMENT_DURATION_US = SEGMENT_MINUTES * 60L * 1_000_000L
    /** The file gets the full mixer quality even when the stream uses less. */
    const val AUDIO_BITRATE = 320_000
    /** Video stops below this, leaving the space to the lossless audio recording. */
    const val MIN_FREE_BYTES = 1_000_000_000L
    const val WARN_MINUTES = 30L

    /** Saved-file bitrate, independent of (and higher than) the adaptive stream bitrate. */
    fun recordBitrate(height: Int): Int = when {
        height >= 1440 -> 20_000_000
        height >= 1080 -> 12_000_000
        else -> 8_000_000
    }

    fun bytesPerSecond(videoBitrate: Int): Long = (videoBitrate.toLong() + AUDIO_BITRATE) / 8

    fun gigabytesPerHour(videoBitrate: Int): Double = bytesPerSecond(videoBitrate) * 3600 / 1e9

    /** Minutes of video that fit before [MIN_FREE_BYTES] is reached. */
    fun minutesRemaining(freeBytes: Long, videoBitrate: Int): Long =
        (freeBytes - MIN_FREE_BYTES).coerceAtLeast(0) / bytesPerSecond(videoBitrate) / 60

    fun storageSummary(freeBytes: Long, videoBitrate: Int): String {
        val perHour = String.format(java.util.Locale.US, "%.1f", gigabytesPerHour(videoBitrate))
        if (freeBytes == Long.MAX_VALUE) return "About $perHour GB per hour of video."
        val minutes = minutesRemaining(freeBytes, videoBitrate)
        return "About $perHour GB per hour of video; room for ${minutes / 60} h ${minutes % 60} min on this phone."
    }
}
