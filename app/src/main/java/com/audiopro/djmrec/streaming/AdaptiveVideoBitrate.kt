package com.audiopro.djmrec.streaming

/**
 * Keeps a livestream's audio intact on a weak uplink by trading video bitrate for headroom.
 *
 * RootEncoder shares one send queue between audio and video; once it overflows it discards
 * frames of both kinds, which viewers of a DJ set hear as gaps. [onSample] is fed once per
 * bitrate report (about every second) with whether that queue is congested:
 * - congested: cut the video bitrate by 25% right away, never below [minBitrate];
 * - clear for [recoverAfterSamples] reports in a row: raise it 15% towards [maxBitrate].
 *
 * Only congestion lowers the target, so a static artwork frame or a dark, low-motion camera
 * shot (which naturally encode below the target) keeps the quality the user chose.
 */
internal class AdaptiveVideoBitrate(
    private val maxBitrate: Int,
    minBitrate: Int = MIN_ADAPTIVE_VIDEO_BITRATE,
    private val recoverAfterSamples: Int = 5
) {
    private val minBitrate = minOf(minBitrate, maxBitrate)
    private var clearSamples = 0

    var current: Int = maxBitrate
        private set

    /** @return the new video bitrate when it changed, otherwise null. */
    fun onSample(congested: Boolean): Int? {
        val next = if (congested) {
            clearSamples = 0
            maxOf(minBitrate, (current.toLong() * 3 / 4).toInt())
        } else {
            if (++clearSamples < recoverAfterSamples) return null
            clearSamples = 0
            minOf(maxBitrate.toLong(), current.toLong() * 115 / 100).toInt()
        }
        if (next == current) return null
        current = next
        return next
    }
}

/** Floor for the adaptive video bitrate so the picture stays recognizable. */
internal const val MIN_ADAPTIVE_VIDEO_BITRATE = 500_000
