package com.audiopro.djmrec.usb

/** USB identities whose proprietary MIX/REC OUT routing is implemented natively. */
enum class PioneerMixerProfile(
    val displayName: String,
    val productIds: Set<Int>,
    val defaultCaptureChannelOffset: Int,
    val outputCount: Int,
    val routeReadMode: RouteReadMode,
    val mixWithoutMicSources: List<Int>,
    val requiresPlaybackTraffic: Boolean = false,
    val playbackInterface: Int = -1,
    val playbackAlternateSetting: Int = -1,
    /**
     * Some models (confirmed: DJM-900NXS2) don't declare their audio-carrying interface under
     * the standard USB Audio Class (class 1 / subclass 2) at all -- the isochronous IN endpoint
     * lives on a USB_CLASS_VENDOR_SPEC (255) interface instead, which
     * [com.audiopro.djmrec.usb.UsbAudioDescriptorParser.findAudioStreamingInterfaces] never
     * looks at. When set (>= 0), [UsbAudioManager] falls back to scanning this exact
     * (interface, alt setting) for its isochronous IN endpoint regardless of declared class, and
     * uses [vendorCaptureChannelCount]/[vendorCaptureSubframeSize]/[vendorCaptureBitResolution]
     * as the wire format, since a vendor-class interface has no CS_INTERFACE AS_GENERAL/FORMAT_TYPE
     * descriptors to read those from.
     *
     * DJM-900NXS2 values: endpoint (0x82 IN / 0x01 OUT, both isochronous, 1024B, interval 1)
     * confirmed via on-device descriptor dump (2026-07-20). Channel count/subframe/bit
     * resolution are NOT confirmed -- no CS descriptor declares them, and no known Pioneer driver
     * for this model has been decompiled. The guess of 10ch/24-bit mirrors the DJM-A9's confirmed
     * playbackChannels=10/subframeBytes=3, since both share the identical 5-output
     * PioneerMixerProfile shape (suggesting the same underlying multichannel wire template).
     * `extractChannelOffset = -1` (auto-pick loudest pair) is used at the call site specifically
     * to stay robust against this channel-count guess being off by a constant factor.
     *
     * UPDATE 2026-07-20 (a): a raw hex dump of the untouched capture-endpoint wire bytes (see
     * [com.audiopro.djmrec.diagnostics] logcat output, `raw iso packet #N dump`) confirmed
     * genuine all-zero payload on every MIX-routed pair while music was confirmed audibly playing
     * on the mixer -- this rules out the channel/bit-depth guess above as the cause of silence (a
     * wrong format would misplace real nonzero bytes into the wrong slots, not zero them). Fixed
     * by sending the UAC1 SET_CUR sampling-frequency control transfer unconditionally (see
     * `setPioneerCaptureSampleRate` call site in native `UsbIsoAudioSource.cpp`), matching
     * Pioneer's own driver sequence captured via USBPcap -- real audio started flowing.
     *
     * UPDATE 2026-07-20 (b): once real audio was flowing, recordings came out quiet and
     * "washing machine"-distorted. Testing the real captured wire bytes from Pioneer's own driver
     * (whit_sound_on.pcapng, device 2b73:000a, endpoint 0x82) against every plausible
     * channel-count/subframe combination -- scoring each by how smooth/autocorrelated the decoded
     * samples come out, since real audio is continuous and a wrong stride produces near-noise --
     * showed 12 channels at 3-byte (24-bit) subframes fits roughly 10x better than every other
     * combination, including the 10-channel guess below. Corroborated independently: this app's
     * own observed capture packets are consistently 216 bytes, which divides evenly into 6 frames
     * of 12ch x 3B (36B/frame) but never evenly into the old assumed 10ch x 3B (30B/frame,
     * 216/30=7.2) -- a whole session's worth of packets that never once contained a whole number
     * of "frames" under the old assumption. vendorCaptureChannelCount corrected to 12.
     */
    val vendorCaptureInterface: Int = -1,
    val vendorCaptureAlternateSetting: Int = -1,
    val vendorCaptureChannelCount: Int = -1,
    val vendorCaptureSubframeSize: Int = -1,
    val vendorCaptureBitResolution: Int = -1,
    /**
     * Extra 0-based output indices (beyond [defaultCaptureChannelOffset]'s output) that
     * [com.audiopro.djmrec.usb.UsbAudioManager.establishPioneerRoute] should also set to MIX.
     * DJM-900NXS2: a USBPcap capture of Pioneer's Setting Utility confirmed both output 1
     * (USB1/2) and output 5 (USB9/10, index 4) accept `source=0x0A` for MIX -- routing both
     * up front means the app's own USB-channel-pair picker (and the auto-pick-loudest-pair
     * fallback) can land on either without a second round of vendor requests.
     */
    val additionalMixOutputs: List<Int> = emptyList()
) {
    DJM_A9(
        "DJM-A9", setOf(0x003C), 8, 5, RouteReadMode.SINGLE_OUTPUT_ZERO_BASED,
        listOf(0x0A, 0x0A, 0x0A, 0x0A, 0x0A), true, 1, 1
    ),
    DJM_V5(
        "DJM-V5", setOf(0x0058, 0x0059, 0x005A, 0x005B), 0, 4,
        RouteReadMode.SINGLE_OUTPUT_ONE_BASED, listOf(0x0E, 0x0E, 0x0E, 0x0E)
    ),
    DJM_900NXS2(
        "DJM-900NXS2", setOf(0x000A), 0, 5, RouteReadMode.ALL_OUTPUTS,
        listOf(0x0A, 0x0A, 0x0A, 0x0A, 0x0A),
        requiresPlaybackTraffic = true, playbackInterface = 0, playbackAlternateSetting = 1,
        vendorCaptureInterface = 0, vendorCaptureAlternateSetting = 1,
        vendorCaptureChannelCount = 12, vendorCaptureSubframeSize = 3, vendorCaptureBitResolution = 24,
        additionalMixOutputs = listOf(4)
    ),
    DJM_750MK2(
        "DJM-750MK2", setOf(0x001B), 0, 5, RouteReadMode.ALL_OUTPUTS,
        listOf(0x0F, 0x0F, 0x0F, 0x0F, 0x0A)
    );

    val hasVendorCaptureOverride: Boolean get() = vendorCaptureInterface >= 0

    enum class RouteReadMode(val responseLength: Int) {
        SINGLE_OUTPUT_ZERO_BASED(2),
        SINGLE_OUTPUT_ONE_BASED(2),
        ALL_OUTPUTS(5)
    }

    fun routeReadValue(outputIndex: Int): Int = when (routeReadMode) {
        RouteReadMode.SINGLE_OUTPUT_ZERO_BASED -> outputIndex
        RouteReadMode.SINGLE_OUTPUT_ONE_BASED -> outputIndex + 1
        RouteReadMode.ALL_OUTPUTS -> 0
    }

    fun decodeRouteSource(response: ByteArray, outputIndex: Int): Int? = when (routeReadMode) {
        RouteReadMode.SINGLE_OUTPUT_ZERO_BASED,
        RouteReadMode.SINGLE_OUTPUT_ONE_BASED -> response.getOrNull(1)?.toInt()?.and(0xFF)
        RouteReadMode.ALL_OUTPUTS -> response.getOrNull(outputIndex)?.toInt()?.and(0xFF)
    }

    fun isRouteResponseValid(response: ByteArray, outputIndex: Int): Boolean =
        response.size == routeReadMode.responseLength && when (routeReadMode) {
            RouteReadMode.SINGLE_OUTPUT_ZERO_BASED ->
                response.firstOrNull()?.toInt()?.and(0xFF) == outputIndex
            RouteReadMode.SINGLE_OUTPUT_ONE_BASED ->
                response.firstOrNull()?.toInt()?.and(0xFF) == outputIndex + 1
            RouteReadMode.ALL_OUTPUTS -> outputIndex in response.indices
        }

    companion object {
        const val ALPHATHETA_VENDOR_ID = 0x2B73
        const val ROUTE_GET_REQUEST = 0x00
        const val ROUTE_SET_REQUEST = 0x03
        const val ROUTE_INDEX = 0x8002

        fun find(vendorId: Int, productId: Int): PioneerMixerProfile? {
            if (vendorId != ALPHATHETA_VENDOR_ID) return null
            return entries.firstOrNull { productId in it.productIds }
        }
    }
}
