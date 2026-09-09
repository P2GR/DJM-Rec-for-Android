package com.audiopro.djmrec.prolink

import kotlinx.coroutines.flow.StateFlow

/** Canonical read-only contract. Consumers never interpret wire packets or vendor IDs. */
interface DjLinkSource {
    val state: StateFlow<DjLinkState>
    fun disconnect()
}

data class TrackKey(val sourcePlayer: Int, val slot: Int, val type: Int, val id: Long)
data class TrackMetadata(val title: String, val artist: String = "", val durationSeconds: Long? = null) {
    val label: String get() = if (artist.isBlank()) title else "$artist — $title"
}
data class LinkDevice(val number: Int, val name: String, val address: String, val seenAt: Long)
data class DeckState(
    val number: Int,
    val track: TrackKey?,
    val playing: Boolean,
    val onAir: Boolean?,
    val tempoMaster: Boolean,
    val bpm: Double?,
    val beat: Long?,
    val packetCounter: Long,
    val seenAt: Long,
    val metadata: TrackMetadata? = null,
    val metadataMessage: String = "Waiting for metadata",
    val loading: Boolean = false,
    val usbMounted: Boolean? = null,
    val sdMounted: Boolean? = null,
    val loadGeneration: Long = 0
)
enum class LinkStatus { DISCONNECTED, DISCOVERING, CONNECTED, ERROR }
data class DjLinkState(
    val status: LinkStatus = LinkStatus.DISCONNECTED,
    val message: String = "Connect to the players' LAN; mixer USB alone is not a verified data connection.",
    val devices: List<LinkDevice> = emptyList(),
    val decks: List<DeckState> = emptyList()
) {
    /** Strict default: never label a cue-only deck as audible. Null on-air is unknown. */
    fun nowPlaying(requireOnAir: Boolean = true): List<DeckState> = decks.filter {
        it.playing && it.track != null && (!requireOnAir || it.onAir == true)
    }.sortedBy { it.number }
}

enum class BannerPosition { TOP, BOTTOM }
data class NowPlayingOptions(
    val automaticMarkers: Boolean = false,
    val requireOnAir: Boolean = true,
    val bannerEnabled: Boolean = false,
    val position: BannerPosition = BannerPosition.BOTTOM,
    val prefix: String = "Now playing",
    val showArtist: Boolean = true,
    val lightBackground: Boolean = false
) {
    fun text(state: DjLinkState): String = if (!bannerEnabled) "" else {
        val tracks = state.nowPlaying(requireOnAir).mapNotNull { deck ->
            deck.metadata?.let { if (showArtist) it.label else it.title }
        }.distinct()
        if (tracks.isEmpty()) "" else listOf(prefix.trim().take(40), tracks.joinToString("  /  "))
            .filter { it.isNotBlank() }.joinToString(": ").replace(Regex("[\\r\\n\\t]"), " ").take(240)
    }
}

/** Uses the recorder's sample-derived position, never wall time; pause and file splits stay aligned. */
class TrackTimeline {
    data class Entry(val id: String, val positionMillis: Long, val deck: Int, val track: TrackKey,
                     val metadata: TrackMetadata?, val loadGeneration: Long = 0)
    private var output: String? = null
    private val active = mutableMapOf<Int, Entry>()
    private var sequence = 0L

    fun update(outputId: String?, recording: Boolean, positionMillis: Long,
               decks: List<DeckState>): List<Entry> {
        if (output != outputId) { output = outputId; active.clear() }
        if (outputId == null || !recording) return emptyList()
        val selected = decks.associateBy { it.number }
        active.keys.retainAll(selected.keys)
        return buildList {
            decks.forEach { deck ->
                val key = deck.track ?: return@forEach
                val previous = active[deck.number]
                val entry = if (previous?.track == key && previous.loadGeneration == deck.loadGeneration)
                    previous.copy(metadata = deck.metadata ?: previous.metadata)
                    else Entry("prolink-${++sequence}", positionMillis.coerceAtLeast(0), deck.number, key, deck.metadata, deck.loadGeneration)
                if (entry != previous) { active[deck.number] = entry; add(entry) }
            }
        }
    }
}
