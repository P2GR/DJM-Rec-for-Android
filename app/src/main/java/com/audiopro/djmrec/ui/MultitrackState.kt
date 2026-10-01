package com.audiopro.djmrec.ui

import androidx.compose.runtime.Immutable
import com.audiopro.djmrec.audio.MultitrackLayout
import com.audiopro.djmrec.audio.TrackChannels
import com.audiopro.djmrec.audio.TrackWaveformHistory
import com.audiopro.djmrec.usb.MixerSendSource
import com.audiopro.djmrec.usb.PioneerTrackRouting
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo

/** One row of the multitrack view. */
@Immutable
data class MultitrackTrack(
    val channels: TrackChannels,
    /** The pair recorded as the set itself (gain + limiter, MP3 copy, livestream). */
    val isMaster: Boolean,
    /** "CH2 Post-fader", "Master" or "USB 3/4". */
    val title: String,
    /** "USB 3/4", or "USB 9/10 · Rec Out" for the master. */
    val subtitle: String,
    /** Name for the track's file, e.g. "CH2 Post-fader" or "USB 3-4". */
    val fileLabel: String,
    /** Mixer sources this pair can be switched to; empty when it is not routable. */
    val sourceOptions: List<MixerSendSource>,
    val selectedSource: Int?,
    /** Shown under the title when the mixer refused a routing change. */
    val routingError: String?,
    val armed: Boolean,
    val gainDb: Float
) {
    val index: Int get() = channels.index
}

/** Per-mixer multitrack choices, persisted by [MainViewModel]. */
@Immutable
data class MultitrackSettings(
    val armed: Map<Int, Boolean> = emptyMap(),
    val gainsDb: Map<Int, Float> = emptyMap(),
    /** Source the user picked per USB output. */
    val chosenSources: Map<Int, Int> = emptyMap(),
    /** Source read back from the mixer per USB output. */
    val currentSources: Map<Int, Int> = emptyMap(),
    val routingErrors: Map<Int, String> = emptyMap()
)

object MultitrackRows {
    /**
     * Builds the rows for [device] with [channelCount] wire channels. Mixers with a routing
     * catalog name each pair after its source; other devices use their USB pair numbers.
     */
    fun build(
        device: UsbAudioDeviceInfo,
        channelCount: Int,
        masterOffset: Int,
        settings: MultitrackSettings
    ): List<MultitrackTrack> {
        val catalog = PioneerTrackRouting.catalogFor(device.pioneerMixerProfile)
        val tracks = MultitrackLayout.tracks(channelCount)
        val masterTrack = MultitrackLayout.masterTrack(masterOffset, tracks)
        return tracks.map { channels ->
            val output = channels.index
            val options = catalog?.let { PioneerTrackRouting.options(it, output) }.orEmpty()
            val selected = if (options.isEmpty()) null
                else settings.currentSources[output] ?: settings.chosenSources[output] ?: catalog?.preset?.getOrNull(output)
            val source = selected?.let { PioneerTrackRouting.source(it, catalog?.channelOfOutput?.getOrNull(output)) }
            val isMaster = channels.index == masterTrack
            val usbFileLabel = if (channels.width == 2) "USB ${channels.firstChannel + 1}-${channels.firstChannel + 2}"
                else "USB ${channels.firstChannel + 1}"
            MultitrackTrack(
                channels = channels,
                isMaster = isMaster,
                title = when {
                    isMaster -> "Master"
                    source != null -> source.label
                    else -> channels.usbLabel
                },
                subtitle = when {
                    isMaster && source != null -> "${channels.usbLabel} · ${source.label}"
                    source != null -> channels.usbLabel
                    channels.width == 1 -> "Mono input"
                    else -> "Stereo input"
                },
                fileLabel = source?.label ?: usbFileLabel,
                // The master pair stays on the mix: its route is owned by the recording itself.
                sourceOptions = if (isMaster) emptyList() else options,
                selectedSource = selected,
                routingError = settings.routingErrors[output],
                armed = !isMaster && (settings.armed[channels.index] ?: true),
                gainDb = settings.gainsDb[channels.index] ?: 0f
            )
        }
    }
}

/** Timeline histories for the master and every track; fed and read on the main thread. */
class MultitrackTimeline {
    val master = TrackWaveformHistory()
    private val tracks = mutableListOf<TrackWaveformHistory>()

    fun track(index: Int): TrackWaveformHistory {
        while (tracks.size <= index) tracks += TrackWaveformHistory()
        return tracks[index]
    }

    fun acceptTracks(snapshots: List<FloatArray>) {
        snapshots.forEachIndexed { index, snapshot -> track(index).accept(snapshot) }
    }

    fun clear() {
        master.clear()
        tracks.forEach { it.clear() }
    }
}
