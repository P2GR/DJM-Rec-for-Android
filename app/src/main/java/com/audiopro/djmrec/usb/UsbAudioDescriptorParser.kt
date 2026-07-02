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
    private const val SUBCLASS_AUDIOSTREAMING = 0x02

    private const val AS_DESCRIPTOR_SUBTYPE_GENERAL = 0x01
    private const val AS_DESCRIPTOR_SUBTYPE_FORMAT_TYPE = 0x02

    private const val ENDPOINT_DIR_IN_MASK = 0x80
    private const val ENDPOINT_ATTR_TRANSFER_TYPE_MASK = 0x03
    private const val ENDPOINT_ATTR_TRANSFER_TYPE_ISOCHRONOUS = 0x01

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
        var pendingBitResolution = 0
        var pendingSubframeSize = 0
        var pendingIsoInEndpoint: Int? = null
        var pendingIsoInMaxPacketSize: Int? = null

        fun flushInterfaceIfComplete() {
            if (currentIsAudioStreaming && currentInterfaceNumber >= 0 && pendingSubframeSize > 0) {
                results.add(
                    AudioStreamingInterfaceInfo(
                        interfaceNumber = currentInterfaceNumber,
                        alternateSetting = currentAlternateSetting,
                        channelCount = currentChannelCount,
                        bitResolution = pendingBitResolution,
                        subframeSize = pendingSubframeSize,
                        isochronousInEndpointAddress = pendingIsoInEndpoint,
                        isochronousInMaxPacketSize = pendingIsoInMaxPacketSize
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
                    pendingBitResolution = 0
                    pendingSubframeSize = 0
                    pendingIsoInEndpoint = null
                    pendingIsoInMaxPacketSize = null
                }

                DT_CS_INTERFACE -> if (currentIsAudioStreaming) {
                    val subtype = rawDescriptors[offset + 2].toInt() and 0xFF
                    when (subtype) {
                        AS_DESCRIPTOR_SUBTYPE_GENERAL -> {
                            // UAC2 Class-Specific AS Interface Descriptor:
                            // ... 4 bmControls,5 bFormatType,6..9 bmFormats,10 bNrChannels
                            if (offset + 10 < rawDescriptors.size) {
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
