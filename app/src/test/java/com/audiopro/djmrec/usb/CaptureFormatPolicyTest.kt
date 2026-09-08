package com.audiopro.djmrec.usb

import kotlin.test.*

class CaptureFormatPolicyTest {
    @Test fun rejectsFormatsTheDecoderCannotRepresent() {
        assertTrue(CaptureFormatPolicy.isSupported(8, 3, 24))
        assertTrue(CaptureFormatPolicy.isSupported(2, 4, 24))
        assertTrue(CaptureFormatPolicy.isSupported(1, 2, 16))
        assertFalse(CaptureFormatPolicy.isSupported(64, 4, 32))
        assertFalse(CaptureFormatPolicy.isSupported(2, 2, 24))
        assertFalse(CaptureFormatPolicy.isSupported(2, 4, 20))
        assertFalse(CaptureFormatPolicy.isSupported(2, 1, 8))
    }

    @Test fun respectsClockRangeResolution() {
        assertEquals(listOf(48_000, 96_000, 192_000), CaptureFormatPolicy.ratesInRange(48_000, 192_000, 48_000))
        assertEquals(listOf(44_100, 48_000), CaptureFormatPolicy.ratesInRange(44_100, 48_000, 0))
        assertEquals(emptyList(), CaptureFormatPolicy.ratesInRange(96_000, 48_000, 0))
        assertEquals(emptyList(), CaptureFormatPolicy.ratesInRange(0, 48_000, 0))
    }

    @Test fun allInOneIdentitiesNeverBecomeDjmVendorRoutingProfiles() {
        AllInOneProfile.entries.forEach {
            assertEquals(it, AllInOneProfile.find(0x2B73, it.productId))
            assertNull(PioneerMixerProfile.find(0x2B73, it.productId))
            assertNull(AllInOneProfile.find(0x1234, it.productId))
        }
        assertEquals(4, AllInOneProfile.XDJ_XZ.recordChannelOffset)
        assertEquals(0, AllInOneProfile.XDJ_AZ.recordChannelOffset)
        assertEquals(0, AllInOneProfile.OPUS_QUAD.recordChannelOffset)
        assertEquals(0, AllInOneProfile.OMNIS_DUO.recordChannelOffset)
        assertNull(AllInOneProfile.XDJ_RX3.recordChannelOffset)
    }

    @Test fun enablesValidatedGenericMultichannelRawCapture() {
        val input = UsbAudioDeviceInfo("test", "USB interface", 0x1234, 1,
            1, 1, 0x81, 512, 8, 24, 3, listOf(48_000), audioManagerDeviceId = 10)
        assertTrue(input.requiresIsoCapture)
        assertFalse(input.copy(channelCount = 2).requiresIsoCapture)
        assertTrue(input.copy(channelCount = 2, audioManagerDeviceId = -1).requiresIsoCapture)
        assertFalse(input.copy(subframeSize = 2).requiresIsoCapture)
    }
}
