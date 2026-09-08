package com.audiopro.djmrec.usb

/**
 * Immutable snapshot of a detected UAC2 mixer, combining:
 *  - USB descriptor facts (parsed directly from the device, always accurate to what the
 *    hardware advertises), and
 *  - The AAudio/AudioManager device id that the native engine binds to for actual capture.
 */
data class UsbAudioDeviceInfo(
    val deviceName: String,
    val productName: String,
    val vendorId: Int,
    val productId: Int,
    /** USB interface number of the Audio Streaming (AS) interface used for capture. */
    val streamingInterfaceNumber: Int,
    /** Alternate setting index that carries the active (non-zero-bandwidth) format. */
    val activeAlternateSetting: Int,
    /** Isochronous IN endpoint address feeding the capture path, e.g. 0x81. */
    val isochronousInEndpointAddress: Int,
    /** wMaxPacketSize of that endpoint, in bytes. -1 if it could not be parsed. */
    val isochronousInMaxPacketSize: Int = -1,
    val channelCount: Int,
    /** Effective bits used per sample, as reported by the Format Type I descriptor (16/24/32). */
    val bitResolution: Int,
    /** Physical container size per sample in bytes (1/2/3/4), a.k.a. subslot size. */
    val subframeSize: Int,
    /** Sample rate(s) advertised by the device's clock source / sampling frequency descriptors. */
    val supportedSampleRates: List<Int>,
    /** Parsed AudioControl and AudioStreaming topology, when raw descriptors were readable. */
    val topology: UacTopology? = null,
    /** Raw configuration descriptors retained for native session initialization. */
    val rawDescriptors: ByteArray = byteArrayOf(),
    /** The rate AAudio actually negotiated once the exclusive stream opened. -1 until known. */
    val negotiatedSampleRate: Int = -1,
    /** AudioManager routing id used by AAudioStreamBuilder.setDeviceId(). -1 until resolved. */
    val audioManagerDeviceId: Int = -1,
    val hasPermission: Boolean = false,
    /** True if [vendorId] matches a known Pioneer/AlphaTheta USB vendor ID. */
    val isPioneer: Boolean = false
) {
    val preferredSampleRate: Int
        get() = negotiatedSampleRate.takeIf { it > 0 }
            ?: supportedSampleRates.firstOrNull { it == 48_000 }
            ?: supportedSampleRates.firstOrNull { it > 0 }
            ?: 48_000

    /** Proprietary routing profile, or null for generic USB Audio devices. */
    val pioneerMixerProfile: PioneerMixerProfile?
        get() = PioneerMixerProfile.find(vendorId, productId)

    val allInOneProfile: AllInOneProfile? get() = AllInOneProfile.find(vendorId, productId)
    val profileDescription: String get() = when {
        pioneerMixerProfile?.isHardwareConfirmed == true -> "Hardware confirmed"
        pioneerMixerProfile != null -> "Driver profile · validation pending"
        allInOneProfile != null -> "${allInOneProfile!!.displayName} · USB descriptor profile"
        else -> "Automatic USB PCM profile"
    }

    /**
     * Whether this device should be captured via the raw libusb isochronous path
     * ([com.audiopro.djmrec.audio.AudioEngine.openUsbIso]) rather than AAudio. Multichannel
     * inputs need explicit pair extraction; recognized all-in-ones use their master return.
     * Unrouted standard PCM inputs can also use raw capture. All require a supported format
     * and a usable endpoint packet size.
     */
    val requiresIsoCapture: Boolean
        get() = isochronousInMaxPacketSize > 0 &&
            CaptureFormatPolicy.isSupported(channelCount, subframeSize, bitResolution) &&
            ((pioneerMixerProfile != null && channelCount > 2) || allInOneProfile != null ||
                channelCount > 2 || audioManagerDeviceId < 0)
}

/** Raw result of walking a single USB Audio Streaming interface's descriptor block. */
data class AudioStreamingInterfaceInfo(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val terminalLink: Int,
    val channelCount: Int,
    val bitResolution: Int,
    val subframeSize: Int,
    val isochronousInEndpointAddress: Int?,
    val isochronousInMaxPacketSize: Int? = null,
    val isochronousFeedbackEndpointAddress: Int? = null,
    val isochronousFeedbackMaxPacketSize: Int? = null,
    val sampleRates: List<Int> = emptyList()
)

/**
 * Everything the native libusb raw-isochronous capture path
 * ([com.audiopro.djmrec.audio.AudioEngine.openUsbIso]) needs, bundled together by
 * [UsbAudioManager.openIsoCaptureHandle].
 */
data class UsbIsoCaptureHandle(
    /** `UsbDeviceConnection.getFileDescriptor()` -- the connection producing this fd must stay
     *  open for the lifetime of native capture. */
    val fd: Int,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val totalChannels: Int,
    val subframeSize: Int,
    val bitResolution: Int,
    val rawDescriptors: ByteArray = byteArrayOf(),
    val clockControlInterfaceNumber: Int = -1,
    val clockSourceId: Int = -1,
    val clockSupportsFrequencySet: Boolean = false,
    val feedbackEndpointAddress: Int = -1,
    val feedbackMaxPacketSize: Int = -1,
    val vendorId: Int = -1,
    val productId: Int = -1
)
