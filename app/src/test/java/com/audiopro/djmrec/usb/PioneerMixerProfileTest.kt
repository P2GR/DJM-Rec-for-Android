package com.audiopro.djmrec.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PioneerMixerProfileTest {
    @Test
    fun `recognizes all supported mixer product ids`() {
        val vendor = PioneerMixerProfile.ALPHATHETA_VENDOR_ID

        assertEquals(PioneerMixerProfile.DJM_A9, PioneerMixerProfile.find(vendor, 0x003C))
        assertEquals(PioneerMixerProfile.DJM_V10, PioneerMixerProfile.find(vendor, 0x0034))
        assertEquals(PioneerMixerProfile.DJM_900NXS2, PioneerMixerProfile.find(vendor, 0x000A))
        assertEquals(PioneerMixerProfile.DJM_750MK2, PioneerMixerProfile.find(vendor, 0x001B))
        assertEquals(PioneerMixerProfile.DJM_450, PioneerMixerProfile.find(vendor, 0x0013))
        listOf(0x0058, 0x0059, 0x005A, 0x005B).forEach { productId ->
            assertEquals(PioneerMixerProfile.DJM_V5, PioneerMixerProfile.find(vendor, productId))
        }
    }

    @Test
    fun `rejects unknown products and vendors`() {
        assertNull(PioneerMixerProfile.find(PioneerMixerProfile.ALPHATHETA_VENDOR_ID, 0xFFFF))
        assertNull(PioneerMixerProfile.find(0x08E4, 0x003C))
    }

    @Test
    fun `uses driver-derived default capture pairs`() {
        assertEquals(8, PioneerMixerProfile.DJM_A9.defaultCaptureChannelOffset)
        assertEquals(0, PioneerMixerProfile.DJM_V10.defaultCaptureChannelOffset)
        assertEquals(0, PioneerMixerProfile.DJM_V5.defaultCaptureChannelOffset)
        assertEquals(0, PioneerMixerProfile.DJM_900NXS2.defaultCaptureChannelOffset)
        assertEquals(0, PioneerMixerProfile.DJM_750MK2.defaultCaptureChannelOffset)
        assertEquals(0, PioneerMixerProfile.DJM_450.defaultCaptureChannelOffset)
    }

    @Test
    fun `uses mixer multichannel wire formats`() {
        assertEquals(12, PioneerMixerProfile.DJM_V10.vendorCaptureChannelCount)
        assertEquals(3, PioneerMixerProfile.DJM_V10.vendorCaptureSubframeSize)
        assertEquals(24, PioneerMixerProfile.DJM_V10.vendorCaptureBitResolution)
        assertEquals(listOf(44_100, 48_000, 96_000), PioneerMixerProfile.DJM_V10.vendorCaptureSampleRates)
        assertEquals(12, PioneerMixerProfile.DJM_900NXS2.vendorCaptureChannelCount)
        assertEquals(3, PioneerMixerProfile.DJM_900NXS2.vendorCaptureSubframeSize)
        assertEquals(24, PioneerMixerProfile.DJM_900NXS2.vendorCaptureBitResolution)
        assertEquals(listOf(96_000), PioneerMixerProfile.DJM_900NXS2.vendorCaptureSampleRates)
        assertEquals(12, PioneerMixerProfile.DJM_750MK2.vendorCaptureChannelCount)
        assertEquals(3, PioneerMixerProfile.DJM_750MK2.vendorCaptureSubframeSize)
        assertEquals(24, PioneerMixerProfile.DJM_750MK2.vendorCaptureBitResolution)
        assertEquals(listOf(96_000), PioneerMixerProfile.DJM_750MK2.vendorCaptureSampleRates)
        assertEquals(8, PioneerMixerProfile.DJM_450.vendorCaptureChannelCount)
        assertEquals(3, PioneerMixerProfile.DJM_450.vendorCaptureSubframeSize)
        assertEquals(24, PioneerMixerProfile.DJM_450.vendorCaptureBitResolution)
        assertEquals(listOf(48_000), PioneerMixerProfile.DJM_450.vendorCaptureSampleRates)
        assertEquals(0, PioneerMixerProfile.DJM_450.vendorCaptureInterface)
        assertEquals(1, PioneerMixerProfile.DJM_450.vendorCaptureAlternateSetting)
    }

    @Test
    fun `uses driver-derived routes without assuming readback semantics`() {
        assertEquals(6, PioneerMixerProfile.DJM_V10.outputCount)
        assertEquals(List(6) { 0x0A }, PioneerMixerProfile.DJM_V10.mixWithoutMicSources)
        assertEquals(PioneerMixerProfile.RouteReadMode.NONE, PioneerMixerProfile.DJM_V10.routeReadMode)
        assertEquals(3, PioneerMixerProfile.DJM_450.outputCount)
        assertEquals(listOf(0x0A, 0x0A, 0x0A), PioneerMixerProfile.DJM_450.mixWithoutMicSources)
        assertEquals(PioneerMixerProfile.RouteReadMode.NONE, PioneerMixerProfile.DJM_450.routeReadMode)
        assertEquals(false, PioneerMixerProfile.DJM_450.requiresPlaybackTraffic)
    }

    @Test
    fun `encodes each driver route GET convention`() {
        assertEquals(0, PioneerMixerProfile.DJM_A9.routeReadValue(0))
        assertEquals(4, PioneerMixerProfile.DJM_A9.routeReadValue(4))
        assertEquals(1, PioneerMixerProfile.DJM_V5.routeReadValue(0))
        assertEquals(4, PioneerMixerProfile.DJM_V5.routeReadValue(3))
        assertEquals(0, PioneerMixerProfile.DJM_900NXS2.routeReadValue(4))
        assertEquals(0, PioneerMixerProfile.DJM_750MK2.routeReadValue(4))
    }

    @Test
    fun `decodes per-output and all-output route replies`() {
        assertEquals(0x0A, PioneerMixerProfile.DJM_A9.decodeRouteSource(byteArrayOf(0, 0x0A), 4))
        assertEquals(0x0E, PioneerMixerProfile.DJM_V5.decodeRouteSource(byteArrayOf(0, 0x0E), 2))
        assertEquals(
            0x0A,
            PioneerMixerProfile.DJM_900NXS2.decodeRouteSource(
                byteArrayOf(1, 2, 3, 4, 0x0A),
                4
            )
        )
        assertEquals(
            0x0F,
            PioneerMixerProfile.DJM_750MK2.decodeRouteSource(
                byteArrayOf(0x0F, 2, 3, 4, 5),
                0
            )
        )
    }

    @Test
    fun `validates output selector byte for per-output replies`() {
        assertEquals(true, PioneerMixerProfile.DJM_A9.isRouteResponseValid(byteArrayOf(4, 0x0A), 4))
        assertEquals(false, PioneerMixerProfile.DJM_A9.isRouteResponseValid(byteArrayOf(3, 0x0A), 4))
        assertEquals(true, PioneerMixerProfile.DJM_V5.isRouteResponseValid(byteArrayOf(4, 0x0E), 3))
        assertEquals(false, PioneerMixerProfile.DJM_V5.isRouteResponseValid(byteArrayOf(3, 0x0E), 3))
    }
}
