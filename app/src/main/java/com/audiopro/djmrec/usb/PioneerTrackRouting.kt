package com.audiopro.djmrec.usb

import androidx.compose.runtime.Immutable

/** A source a Pioneer mixer can send on one of its USB output pairs. */
@Immutable
data class MixerSendSource(val code: Int, val label: String, val detail: String)

/**
 * What each USB output pair of a Pioneer mixer can carry, for multitrack routing.
 *
 * Source codes and per-output options come from the Linux kernel's snd-usb-audio Pioneer
 * DJM quirks (sound/usb/mixer_quirks.c, `snd_djm_opts_*`), which use the same vendor control
 * as this app (wIndex 0x8002, wValue = (output + 1) << 8 | source). Only models whose current
 * route can be read back are listed: the app restores every route it changes when capture
 * stops, and refuses changes it could not undo. The DJM-900NXS2 has a kernel table but its
 * route GET always answers the same bytes, so its routes can be neither verified nor restored.
 */
object PioneerTrackRouting {
    const val LINE = 0x00
    const val CD_LINE = 0x01
    const val DIGITAL = 0x02
    const val PHONO = 0x03
    const val PRE_FADER = 0x05
    const val POST_FADER = 0x06
    const val CROSSFADER_A = 0x07
    const val CROSSFADER_B = 0x08
    const val MIC = 0x09
    const val REC_OUT = 0x0A
    const val REC_OUT_NO_MIC = 0x0E
    private const val CH_POST_FADER_BASE = 0x10 // 0x11..0x14 = CH1..CH4 post-fader
    private const val CH_PRE_FADER_BASE = 0x30  // 0x31..0x34 = CH1..CH4 pre-fader

    @Immutable
    data class Catalog(
        /** USB output (0-based pair) that carries the master in advanced mode. */
        val masterOutput: Int,
        /** Source codes per USB output, most useful first. */
        val outputs: List<List<Int>>,
        /** Mixer channel whose own sends an output carries (CH n), or null for a shared slot. */
        val channelOfOutput: List<Int?>,
        /** Routing applied when advanced mode starts: master, then each channel post-fader. */
        val preset: List<Int>
    ) {
        val masterOffset: Int get() = masterOutput * 2
    }

    private val channelSlot900 = listOf(
        POST_FADER, LINE, DIGITAL, PHONO, CROSSFADER_A, CROSSFADER_B, MIC, REC_OUT
    )

    /** DJM-750MK2: USB 1/2..7/8 = CH1..CH4, USB 9/10 = master slot. */
    private val fourChannelCatalog = Catalog(
        masterOutput = 4,
        outputs = List(4) { channelSlot900 } + listOf(
            listOf(REC_OUT, MIC, CROSSFADER_A, CROSSFADER_B, 0x11, 0x12, 0x13, 0x14)
        ),
        channelOfOutput = listOf(1, 2, 3, 4, null),
        preset = listOf(POST_FADER, POST_FADER, POST_FADER, POST_FADER, REC_OUT)
    )

    /** DJM-A9: USB 1/2 = master slot, USB 3/4..9/10 = CH1..CH4. */
    private val a9Catalog = Catalog(
        masterOutput = 0,
        outputs = listOf(
            listOf(REC_OUT, REC_OUT_NO_MIC, MIC, CROSSFADER_A, CROSSFADER_B,
                0x11, 0x12, 0x13, 0x14, 0x31, 0x32, 0x33, 0x34)
        ) + List(4) {
            listOf(POST_FADER, PRE_FADER, CD_LINE, DIGITAL, PHONO,
                CROSSFADER_A, CROSSFADER_B, MIC, REC_OUT, REC_OUT_NO_MIC)
        },
        channelOfOutput = listOf(null, 1, 2, 3, 4),
        preset = listOf(REC_OUT, POST_FADER, POST_FADER, POST_FADER, POST_FADER)
    )

    fun catalogFor(profile: PioneerMixerProfile?): Catalog? = when (profile) {
        PioneerMixerProfile.DJM_A9 -> a9Catalog
        PioneerMixerProfile.DJM_750MK2 -> fourChannelCatalog
        else -> null
    }

    /** Options for USB output [output], or empty when it cannot be routed. */
    fun options(catalog: Catalog, output: Int): List<MixerSendSource> =
        catalog.outputs.getOrNull(output).orEmpty().map { source(it, catalog.channelOfOutput.getOrNull(output)) }

    fun source(code: Int, channel: Int?): MixerSendSource {
        val ch = channel?.let { "CH$it " }.orEmpty()
        return when (code) {
            LINE -> MixerSendSource(code, "${ch}Line", "Pre-fader, before the channel fader")
            CD_LINE -> MixerSendSource(code, "${ch}CD/Line", "Pre-fader, before the channel fader")
            DIGITAL -> MixerSendSource(code, "${ch}Digital", "Pre-fader, before the channel fader")
            PHONO -> MixerSendSource(code, "${ch}Phono", "Pre-fader; the turntable/DVS input")
            PRE_FADER -> MixerSendSource(code, "${ch}Pre-fader", "Includes everything you cue")
            POST_FADER -> MixerSendSource(code, "${ch}Post-fader", "What this channel sends to the mix")
            CROSSFADER_A -> MixerSendSource(code, "Crossfader A", "Channels assigned to side A")
            CROSSFADER_B -> MixerSendSource(code, "Crossfader B", "Channels assigned to side B")
            MIC -> MixerSendSource(code, "Mic", "Microphone input")
            REC_OUT -> MixerSendSource(code, "Rec Out", "The full mix")
            REC_OUT_NO_MIC -> MixerSendSource(code, "Rec Out (no mic)", "The full mix without the mic")
            in (CH_POST_FADER_BASE + 1)..(CH_POST_FADER_BASE + 4) ->
                MixerSendSource(code, "CH${code - CH_POST_FADER_BASE} Post-fader", "What this channel sends to the mix")
            in (CH_PRE_FADER_BASE + 1)..(CH_PRE_FADER_BASE + 4) ->
                MixerSendSource(code, "CH${code - CH_PRE_FADER_BASE} Pre-fader", "Includes everything you cue")
            else -> MixerSendSource(
                code, "Source 0x" + code.toString(16).uppercase().padStart(2, '0'), "Set on the mixer"
            )
        }
    }
}
