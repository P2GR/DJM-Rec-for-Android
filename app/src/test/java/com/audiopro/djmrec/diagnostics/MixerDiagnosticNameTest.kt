package com.audiopro.djmrec.diagnostics

import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class MixerDiagnosticNameTest {
    @Test
    fun reportsProfileUnknownAndNoneValues() {
        assertEquals("DJM-A9", RemoteDiagnostics.mixerDiagnosticName(device(0x2B73, 0x003C)))
        assertEquals("DJM-750MK2", RemoteDiagnostics.mixerDiagnosticName(device(0x2B73, 0x001B)))
        assertEquals("Unknown", RemoteDiagnostics.mixerDiagnosticName(device(0x1234, 0x5678)))
        assertEquals("None", RemoteDiagnostics.mixerDiagnosticName(null))
    }

    private fun device(vendorId: Int, productId: Int) = UsbAudioDeviceInfo(
        deviceName = "/dev/bus/usb/test",
        productName = "USB Audio",
        vendorId = vendorId,
        productId = productId,
        streamingInterfaceNumber = 1,
        activeAlternateSetting = 1,
        isochronousInEndpointAddress = 0x81,
        channelCount = 2,
        bitResolution = 24,
        subframeSize = 3,
        supportedSampleRates = listOf(48_000)
    )
}
