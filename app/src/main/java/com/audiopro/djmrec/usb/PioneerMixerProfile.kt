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
    val playbackAlternateSetting: Int = -1
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
        listOf(0x0A, 0x0A, 0x0A, 0x0A, 0x0A)
    ),
    DJM_750MK2(
        "DJM-750MK2", setOf(0x001B), 0, 5, RouteReadMode.ALL_OUTPUTS,
        listOf(0x0F, 0x0F, 0x0F, 0x0F, 0x0A)
    );

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
