package com.audiopro.djmrec.usb

/**
 * Minimal, spec-accurate walker over the raw USB configuration descriptor blob returned by
 * [android.hardware.usb.UsbDeviceConnection.getRawDescriptors]. We only need this to answer
 * "which alternate setting is the real audio streaming interface, and what does it advertise
 * (channels / bit depth / iso endpoint)?" — actual audio bytes are pulled through AAudio/Oboe,
 * not through raw bulk/iso transfers, so we do not need a full UAC2 stack here.
 *
 * Reference: USB Audio Class 2.0 spec, sections 4.9 (AudioStreaming Interface) and
 * Format Type I Descriptor (Frmts20 spec, table 2-1).
 */
object UsbAudioDescriptorParser {

    private const val DT_INTERFACE = 0x04
    private const val DT_ENDPOINT = 0x05
    private const val DT_CS_INTERFACE = 0x24
    private const val DT_CS_ENDPOINT = 0x25

    private const val USB_CLASS_AUDIO = 0x01
    private const val SUBCLASS_AUDIOCONTROL = 0x01
    private const val SUBCLASS_AUDIOSTREAMING = 0x02

    private const val AS_DESCRIPTOR_SUBTYPE_GENERAL = 0x01
    private const val AS_DESCRIPTOR_SUBTYPE_FORMAT_TYPE = 0x02

    private const val AC_DESCRIPTOR_SUBTYPE_HEADER = 0x01
    private const val AC_DESCRIPTOR_SUBTYPE_INPUT_TERMINAL = 0x02
    private const val AC_DESCRIPTOR_SUBTYPE_OUTPUT_TERMINAL = 0x03
    private const val AC_DESCRIPTOR_SUBTYPE_MIXER_UNIT = 0x04
    private const val AC_DESCRIPTOR_SUBTYPE_SELECTOR_UNIT = 0x05
    private const val AC_DESCRIPTOR_SUBTYPE_FEATURE_UNIT = 0x06
    private const val AC_DESCRIPTOR_SUBTYPE_CLOCK_SOURCE = 0x0A
    private const val AC_DESCRIPTOR_SUBTYPE_CLOCK_SELECTOR = 0x0B

    private const val UAC2_CLOCK_CONTROL_FREQUENCY_MASK = 0x03
    // UAC2 bmaControls uses two-bit Control selectors: 01 = read-only, 11 = read/write.
    private const val UAC2_CLOCK_CONTROL_FREQUENCY_PROGRAMMABLE = 0x03

    private const val ENDPOINT_DIR_IN_MASK = 0x80
    private const val ENDPOINT_ATTR_TRANSFER_TYPE_MASK = 0x03
    private const val ENDPOINT_ATTR_TRANSFER_TYPE_ISOCHRONOUS = 0x01

    private fun byteAt(bytes: ByteArray, index: Int): Int =
        if (index in bytes.indices) bytes[index].toInt() and 0xFF else 0

    private fun le16(bytes: ByteArray, index: Int): Int =
        byteAt(bytes, index) or (byteAt(bytes, index + 1) shl 8)

    private fun le24(bytes: ByteArray, index: Int): Int =
        byteAt(bytes, index) or (byteAt(bytes, index + 1) shl 8) or
            (byteAt(bytes, index + 2) shl 16)

    private fun le32(bytes: ByteArray, index: Int): Long =
        byteAt(bytes, index).toLong() or
            (byteAt(bytes, index + 1).toLong() shl 8) or
            (byteAt(bytes, index + 2).toLong() shl 16) or
            (byteAt(bytes, index + 3).toLong() shl 24)

    /** Parses AudioControl topology plus descriptor-advertised sample rates. */
    internal fun parseTopology(rawDescriptors: ByteArray): UacTopology {
        val streaming = findAudioStreamingInterfaces(rawDescriptors)
        val clockSources = mutableListOf<ClockSourceInfo>()
        val clockSelectors = mutableListOf<ClockSelectorInfo>()
        val featureUnits = mutableListOf<FeatureUnitInfo>()
        val mixerUnits = mutableListOf<MixerUnitInfo>()
        val selectorUnits = mutableListOf<SelectorUnitInfo>()
        val inputTerminals = mutableListOf<TerminalInfo>()
        val outputTerminals = mutableListOf<TerminalInfo>()
        val descriptorRates = linkedSetOf<Int>()
        val audioControlInterfaces = mutableListOf<AudioControlInterface>()
        var audioControlInterface: AudioControlInterface? = null
        var currentClass = -1
        var currentSubclass = -1
        var currentInterfaceNumber = -1
        var currentAudioClassVersion = 0
        var offset = 0

        while (offset + 1 < rawDescriptors.size) {
            val length = byteAt(rawDescriptors, offset)
            if (length < 2 || offset + length > rawDescriptors.size) break
            when (byteAt(rawDescriptors, offset + 1)) {
                DT_INTERFACE -> {
                    currentInterfaceNumber = byteAt(rawDescriptors, offset + 2)
                    currentClass = byteAt(rawDescriptors, offset + 5)
                    currentSubclass = byteAt(rawDescriptors, offset + 6)
                    currentAudioClassVersion = when (byteAt(rawDescriptors, offset + 7)) {
                        0x20 -> 0x0200
                        0x00 -> 0x0100
                        else -> 0
                    }
                }

                DT_CS_INTERFACE -> if (currentClass == USB_CLASS_AUDIO) {
                    val subtype = byteAt(rawDescriptors, offset + 2)
                    if (currentSubclass == SUBCLASS_AUDIOCONTROL) {
                        when (subtype) {
                            AC_DESCRIPTOR_SUBTYPE_HEADER -> {
                                currentAudioClassVersion = le16(rawDescriptors, offset + 3)
                                val controlInterface = AudioControlInterface(
                                    currentInterfaceNumber,
                                    if (currentAudioClassVersion >= 0x0200) {
                                        le16(rawDescriptors, offset + 6)
                                    } else {
                                        le16(rawDescriptors, offset + 5)
                                    },
                                    currentAudioClassVersion
                                )
                                audioControlInterfaces += controlInterface
                                if (audioControlInterface == null) {
                                    audioControlInterface = controlInterface
                                }
                            }

                            AC_DESCRIPTOR_SUBTYPE_INPUT_TERMINAL -> if (length >= 17) {
                                inputTerminals += TerminalInfo(
                                    id = byteAt(rawDescriptors, offset + 3),
                                    terminalType = le16(rawDescriptors, offset + 4),
                                    sourceId = 0,
                                    clockSourceId = byteAt(rawDescriptors, offset + 7),
                                    channelCount = byteAt(rawDescriptors, offset + 8),
                                    channelConfig = le32(rawDescriptors, offset + 9),
                                    nameStringIndex = byteAt(rawDescriptors, offset + 13)
                                )
                            }

                            AC_DESCRIPTOR_SUBTYPE_OUTPUT_TERMINAL -> if (length >= 12) {
                                outputTerminals += TerminalInfo(
                                    id = byteAt(rawDescriptors, offset + 3),
                                    terminalType = le16(rawDescriptors, offset + 4),
                                    sourceId = byteAt(rawDescriptors, offset + 7),
                                    clockSourceId = byteAt(rawDescriptors, offset + 8),
                                    channelCount = 0,
                                    channelConfig = 0,
                                    nameStringIndex = byteAt(rawDescriptors, offset + 11)
                                )
                            }

                            AC_DESCRIPTOR_SUBTYPE_MIXER_UNIT -> if (length >= 6) {
                                val inputCount = byteAt(rawDescriptors, offset + 4)
                                val sourceStart = offset + 5
                                val sourceIds = (0 until inputCount).map { byteAt(rawDescriptors, sourceStart + it) }
                                val outputCountIndex = sourceStart + inputCount
                                val outputCount = byteAt(rawDescriptors, outputCountIndex)
                                val controlStart = outputCountIndex + 1
                                val controlBytes = (length - (controlStart - offset) - 1).coerceAtLeast(0)
                                mixerUnits += MixerUnitInfo(
                                    id = byteAt(rawDescriptors, offset + 3),
                                    sourceIds = sourceIds,
                                    outputChannelCount = outputCount,
                                    controls = (0 until controlBytes).map { byteAt(rawDescriptors, controlStart + it) },
                                    nameStringIndex = byteAt(rawDescriptors, offset + length - 1)
                                )
                            }

                            AC_DESCRIPTOR_SUBTYPE_SELECTOR_UNIT -> if (length >= 6) {
                                val inputCount = byteAt(rawDescriptors, offset + 4)
                                val sourceStart = offset + 5
                                val controlIndex = sourceStart + inputCount
                                selectorUnits += SelectorUnitInfo(
                                    id = byteAt(rawDescriptors, offset + 3),
                                    sourceIds = (0 until inputCount).map { byteAt(rawDescriptors, sourceStart + it) },
                                    controls = byteAt(rawDescriptors, controlIndex),
                                    nameStringIndex = byteAt(rawDescriptors, offset + length - 1)
                                )
                            }

                            AC_DESCRIPTOR_SUBTYPE_FEATURE_UNIT -> if (length >= 10) {
                                val controlBytes = (length - 6).coerceAtLeast(0)
                                val controls = (0 until (controlBytes / 4)).map { index ->
                                    byteAt(rawDescriptors, offset + 5 + index * 4) or
                                        (byteAt(rawDescriptors, offset + 6 + index * 4) shl 8) or
                                        (byteAt(rawDescriptors, offset + 7 + index * 4) shl 16) or
                                        (byteAt(rawDescriptors, offset + 8 + index * 4) shl 24)
                                }
                                featureUnits += FeatureUnitInfo(
                                    id = byteAt(rawDescriptors, offset + 3),
                                    sourceId = byteAt(rawDescriptors, offset + 4),
                                    controls = controls,
                                    nameStringIndex = byteAt(rawDescriptors, offset + length - 1)
                                )
                            }

                            AC_DESCRIPTOR_SUBTYPE_CLOCK_SOURCE -> if (
                                currentAudioClassVersion >= 0x0200 && length >= 8
                            ) {
                                val controls = byteAt(rawDescriptors, offset + 5)
                                clockSources += ClockSourceInfo(
                                    id = byteAt(rawDescriptors, offset + 3),
                                    interfaceNumber = currentInterfaceNumber,
                                    attributes = byteAt(rawDescriptors, offset + 4),
                                    controls = controls,
                                    associatedTerminalId = byteAt(rawDescriptors, offset + 6),
                                    nameStringIndex = byteAt(rawDescriptors, offset + 7),
                                    supportsFrequencyControl =
                                        (controls and UAC2_CLOCK_CONTROL_FREQUENCY_MASK) in 0x01..0x03,
                                    supportsFrequencySet =
                                        (controls and UAC2_CLOCK_CONTROL_FREQUENCY_MASK) ==
                                            UAC2_CLOCK_CONTROL_FREQUENCY_PROGRAMMABLE
                                )
                            }

                            AC_DESCRIPTOR_SUBTYPE_CLOCK_SELECTOR -> if (
                                currentAudioClassVersion >= 0x0200 && length >= 7
                            ) {
                                val inputCount = byteAt(rawDescriptors, offset + 4)
                                val sourceStart = offset + 5
                                val controlIndex = sourceStart + inputCount
                                clockSelectors += ClockSelectorInfo(
                                    id = byteAt(rawDescriptors, offset + 3),
                                    sourceIds = (0 until inputCount).map { byteAt(rawDescriptors, sourceStart + it) },
                                    controls = byteAt(rawDescriptors, controlIndex),
                                    nameStringIndex = byteAt(rawDescriptors, offset + length - 1)
                                )
                            }
                        }
                    } else if (currentSubclass == SUBCLASS_AUDIOSTREAMING && subtype == AS_DESCRIPTOR_SUBTYPE_FORMAT_TYPE) {
                        val formatType = byteAt(rawDescriptors, offset + 3)
                        val rateCount = byteAt(rawDescriptors, offset + 6)
                        if (formatType == 0x01 && length >= 8 && rateCount > 0) {
                            for (index in 0 until rateCount) {
                                val rate = le24(rawDescriptors, offset + 7 + index * 3)
                                if (rate > 0) descriptorRates += rate
                            }
                        }
                    }
                }
            }
            offset += length
        }

        return UacTopology(
            audioControlInterface = audioControlInterface,
            audioControlInterfaces = audioControlInterfaces,
            audioStreamingInterfaces = streaming,
            clockSources = clockSources,
            clockSelectors = clockSelectors,
            featureUnits = featureUnits,
            mixerUnits = mixerUnits,
            selectorUnits = selectorUnits,
            inputTerminals = inputTerminals,
            outputTerminals = outputTerminals,
            descriptorSampleRates = descriptorRates.toList()
        )
    }

    /**
     * Walks the whole configuration descriptor and returns every AudioStreaming (AS) interface
     * alternate-setting that exposes an isochronous IN endpoint, i.e. every candidate capture
     * format the mixer offers (e.g. 48kHz/24-bit, 96kHz/24-bit, etc.).
     */
    internal fun findAudioStreamingInterfaces(rawDescriptors: ByteArray): List<AudioStreamingInterfaceInfo> {
        val results = mutableListOf<AudioStreamingInterfaceInfo>()
        var offset = 0

        var currentInterfaceNumber = -1
        var currentAlternateSetting = -1
        var currentIsAudioStreaming = false
        var currentChannelCount = 0
        var currentTerminalLink = -1
        var pendingBitResolution = 0
        var pendingSubframeSize = 0
        var pendingIsoInEndpoint: Int? = null
        var pendingIsoInMaxPacketSize: Int? = null
        var pendingIsoFeedbackEndpoint: Int? = null
        var pendingIsoFeedbackMaxPacketSize: Int? = null

        fun flushInterfaceIfComplete() {
            if (currentIsAudioStreaming && currentInterfaceNumber >= 0 && pendingSubframeSize > 0) {
                results.add(
                    AudioStreamingInterfaceInfo(
                        interfaceNumber = currentInterfaceNumber,
                        alternateSetting = currentAlternateSetting,
                        channelCount = currentChannelCount,
                        terminalLink = currentTerminalLink,
                        bitResolution = pendingBitResolution,
                        subframeSize = pendingSubframeSize,
                        isochronousInEndpointAddress = pendingIsoInEndpoint,
                        isochronousInMaxPacketSize = pendingIsoInMaxPacketSize,
                        isochronousFeedbackEndpointAddress = pendingIsoFeedbackEndpoint,
                        isochronousFeedbackMaxPacketSize = pendingIsoFeedbackMaxPacketSize
                    )
                )
            }
        }

        while (offset + 1 < rawDescriptors.size) {
            val bLength = rawDescriptors[offset].toInt() and 0xFF
            if (bLength < 2 || offset + bLength > rawDescriptors.size) break
            val bDescriptorType = rawDescriptors[offset + 1].toInt() and 0xFF

            when (bDescriptorType) {
                DT_INTERFACE -> {
                    // Standard Interface Descriptor:
                    // 0 bLength,1 bDescriptorType,2 bInterfaceNumber,3 bAlternateSetting,
                    // 4 bNumEndpoints,5 bInterfaceClass,6 bInterfaceSubClass,7 bInterfaceProtocol
                    flushInterfaceIfComplete()

                    currentInterfaceNumber = rawDescriptors[offset + 2].toInt() and 0xFF
                    currentAlternateSetting = rawDescriptors[offset + 3].toInt() and 0xFF
                    val interfaceClass = rawDescriptors[offset + 5].toInt() and 0xFF
                    val interfaceSubClass = rawDescriptors[offset + 6].toInt() and 0xFF
                    currentIsAudioStreaming =
                        interfaceClass == USB_CLASS_AUDIO && interfaceSubClass == SUBCLASS_AUDIOSTREAMING

                    currentChannelCount = 0
                    currentTerminalLink = -1
                    pendingBitResolution = 0
                    pendingSubframeSize = 0
                    pendingIsoInEndpoint = null
                    pendingIsoInMaxPacketSize = null
                    pendingIsoFeedbackEndpoint = null
                    pendingIsoFeedbackMaxPacketSize = null
                }

                DT_CS_INTERFACE -> if (currentIsAudioStreaming) {
                    val subtype = rawDescriptors[offset + 2].toInt() and 0xFF
                    when (subtype) {
                        AS_DESCRIPTOR_SUBTYPE_GENERAL -> {
                            // UAC2 Class-Specific AS Interface Descriptor:
                            // ... 4 bmControls,5 bFormatType,6..9 bmFormats,10 bNrChannels
                            if (offset + 10 < rawDescriptors.size) {
                                currentTerminalLink = rawDescriptors[offset + 3].toInt() and 0xFF
                                currentChannelCount = rawDescriptors[offset + 10].toInt() and 0xFF
                            }
                        }

                        AS_DESCRIPTOR_SUBTYPE_FORMAT_TYPE -> {
                            // UAC2 Format Type I Descriptor:
                            // 3 bFormatType, 4 bSubslotSize, 5 bBitResolution
                            if (offset + 5 < rawDescriptors.size) {
                                pendingSubframeSize = rawDescriptors[offset + 4].toInt() and 0xFF
                                pendingBitResolution = rawDescriptors[offset + 5].toInt() and 0xFF
                            }
                        }
                    }
                }

                DT_ENDPOINT -> if (currentIsAudioStreaming) {
                    // Standard Endpoint Descriptor:
                    // 2 bEndpointAddress, 3 bmAttributes, 4-5 wMaxPacketSize (LE; bits 0-10 are
                    // the actual size, bits 11-12 are extra high-speed-only transactions/uframe,
                    // which we don't need to mask off separately since UAC2 iso IN endpoints on
                    // full/high-speed Android hosts don't use them here).
                    val address = rawDescriptors[offset + 2].toInt() and 0xFF
                    val attributes = rawDescriptors[offset + 3].toInt() and 0xFF
                    val isIn = (address and ENDPOINT_DIR_IN_MASK) != 0
                    val isIsochronous =
                        (attributes and ENDPOINT_ATTR_TRANSFER_TYPE_MASK) == ENDPOINT_ATTR_TRANSFER_TYPE_ISOCHRONOUS
                    if (isIn && isIsochronous) {
                        pendingIsoInEndpoint = address
                        if (offset + 5 < rawDescriptors.size) {
                            val wMaxPacketSizeRaw =
                                (rawDescriptors[offset + 4].toInt() and 0xFF) or
                                    ((rawDescriptors[offset + 5].toInt() and 0xFF) shl 8)
                            pendingIsoInMaxPacketSize = wMaxPacketSizeRaw and 0x7FF
                        }
                    } else if (isIsochronous && ((attributes shr 4) and 0x03) == 0x01) {
                        pendingIsoFeedbackEndpoint = address
                        if (offset + 5 < rawDescriptors.size) {
                            val wMaxPacketSizeRaw =
                                (rawDescriptors[offset + 4].toInt() and 0xFF) or
                                    ((rawDescriptors[offset + 5].toInt() and 0xFF) shl 8)
                            pendingIsoFeedbackMaxPacketSize = wMaxPacketSizeRaw and 0x7FF
                        }
                    }
                }
            }

            offset += bLength
        }
        flushInterfaceIfComplete()

        return results
    }

    /**
     * Picks the "best" capture-capable alternate setting.
     *
     * Many real UAC2 DJ mixers (e.g. Pioneer DJM-A9, DJM-V10) do not expose a dedicated
     * exactly-2-channel alternate setting at all — their USB audio is a single fixed
     * multi-channel interface representing several stereo pairs (Master, Rec Out, Ch1, Ch2,
     * ...). Requiring `channelCount == 2` here would reject every such mixer outright, which
     * looks to the user like "the app doesn't connect" even though the device, permission and
     * descriptor parsing all worked correctly.
     *
     * We therefore prefer an exact stereo (2ch) alternate if one exists, but fall back to the
     * narrowest available multi-channel alternate (closest to stereo) rather than rejecting the
     * device entirely. Callers must read the resulting [AudioStreamingInterfaceInfo.channelCount]
     * back out and use it end-to-end (AAudio stream + file writer) instead of assuming 2.
     */
    internal fun selectBestStereoInterface(
        interfaces: List<AudioStreamingInterfaceInfo>
    ): AudioStreamingInterfaceInfo? {
        val candidates = interfaces.filter {
            it.channelCount >= 1 && it.isochronousInEndpointAddress != null
        }
        val exactStereo = candidates.filter { it.channelCount == 2 }
        val pool = exactStereo.ifEmpty { candidates }
        // Within the chosen pool, prefer the narrowest channel count (closest to stereo when
        // falling back to multichannel), then the highest bit resolution/subframe size.
        return pool.maxWithOrNull(
            compareBy<AudioStreamingInterfaceInfo> { -it.channelCount }
                .thenBy { it.bitResolution }
                .thenBy { it.subframeSize }
        )
    }
}
