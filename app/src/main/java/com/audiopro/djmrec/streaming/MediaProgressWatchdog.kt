package com.audiopro.djmrec.streaming

/** Cumulative packet totals alone cannot prove that a live stream still carries audio/video. */
internal class MediaProgressWatchdog {
    private val counts = LongArray(3)
    private val advancedAt = LongArray(3)
    fun reset(now: Long) {
        counts.fill(0)
        advancedAt.fill(now)
    }
    fun failure(now: Long, pcm: Long, audio: Long, video: Long): String? {
        val current = longArrayOf(pcm, audio, video)
        for (i in current.indices) {
            if (current[i] != counts[i]) {
                counts[i] = current[i]
                advancedAt[i] = now
            }
        }
        return when {
            now - advancedAt[0] >= 10_000 -> "Mixer PCM stalled during livestream"
            now - advancedAt[1] >= 10_000 -> "Outgoing AAC audio stalled during livestream"
            now - advancedAt[2] >= 10_000 -> "Outgoing H.264 video stalled during livestream"
            else -> null
        }
    }
}
