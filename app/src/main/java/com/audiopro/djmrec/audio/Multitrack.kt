package com.audiopro.djmrec.audio

import androidx.compose.runtime.Immutable

/**
 * One multitrack track: [width] (1 or 2) wire channels starting at [firstChannel]. Mirrors the
 * native `TrackLayout`: consecutive stereo pairs, with a trailing odd channel as a mono track.
 */
@Immutable
data class TrackChannels(val index: Int, val firstChannel: Int, val width: Int) {
    /** "USB 3/4", or "USB 5" for a mono track. */
    val usbLabel: String
        get() = if (width == 2) "USB ${firstChannel + 1}/${firstChannel + 2}" else "USB ${firstChannel + 1}"
}

object MultitrackLayout {
    /** Matches native TrackBus::kMaxChannels and the capture format policy. */
    const val MAX_CHANNELS = 32
    const val MAX_TRACKS = MAX_CHANNELS / 2

    fun tracks(channelCount: Int): List<TrackChannels> {
        val channels = channelCount.coerceIn(0, MAX_CHANNELS)
        return (0 until channels step 2).take(MAX_TRACKS).mapIndexed { index, first ->
            TrackChannels(index, first, minOf(2, channels - first))
        }
    }

    /** Multitrack needs more than the master pair; a stereo device has nothing extra to record. */
    fun isAvailable(channelCount: Int): Boolean = channelCount > 2

    /** Track whose channels hold the master pair starting at [masterOffset], or -1. */
    fun masterTrack(masterOffset: Int, tracks: List<TrackChannels>): Int =
        if (masterOffset < 0) -1 else tracks.indexOfFirst { it.firstChannel == masterOffset }

    /**
     * Native levels are three floats per wire channel (peak dBFS, RMS dBFS, clip flag). Returns
     * one [StereoLevels] per track; a mono track reports the same reading on both sides.
     */
    fun trackLevels(raw: FloatArray, tracks: List<TrackChannels>): List<StereoLevels> {
        fun channel(index: Int): ChannelLevel {
            val base = index * 3
            if (base + 2 >= raw.size) return ChannelLevel(-60f, -60f, false)
            return ChannelLevel(raw[base], raw[base + 1], raw[base + 2] > 0.5f)
        }
        return tracks.map { track ->
            val left = channel(track.firstChannel)
            StereoLevels(left, if (track.width == 2) channel(track.firstChannel + 1) else left)
        }
    }

    /** Free-space need of the master plus armed tracks, in bytes per second. */
    fun bytesPerSecond(
        sampleRate: Int,
        bitDepth: Int,
        masterChannels: Int,
        armedTracks: List<TrackChannels>
    ): Long {
        val bytesPerSample = (bitDepth.coerceAtLeast(8) + 7) / 8
        val channels = masterChannels + armedTracks.sumOf { it.width }
        return sampleRate.coerceAtLeast(1).toLong() * channels * bytesPerSample
    }
}

/**
 * Tracks to record next to the master: native track indices and the label each file gets.
 * Passed from the UI to the service with ACTION_START.
 */
data class TrackRecordingPlan(val tracks: List<Int>, val labels: List<String>) {
    init { require(tracks.size == labels.size) }

    val isEmpty: Boolean get() = tracks.isEmpty()

    companion object {
        val NONE = TrackRecordingPlan(emptyList(), emptyList())
    }
}
