package com.audiopro.djmrec.ui

import com.audiopro.djmrec.usb.PioneerTrackRouting
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultitrackRowsTest {
    private fun device(vendorId: Int, productId: Int, channels: Int) = UsbAudioDeviceInfo(
        deviceName = "/dev/bus/usb/001/002",
        productName = "Test device",
        vendorId = vendorId,
        productId = productId,
        streamingInterfaceNumber = 1,
        activeAlternateSetting = 1,
        isochronousInEndpointAddress = 0x81,
        isochronousInMaxPacketSize = 512,
        channelCount = channels,
        bitResolution = 24,
        subframeSize = 3,
        supportedSampleRates = listOf(48_000)
    )

    @Test
    fun `a9 rows name each pair after its routed source with the master on top slot`() {
        val a9 = device(0x2B73, 0x003C, 10)
        val rows = MultitrackRows.build(a9, 10, masterOffset = 0, settings = MultitrackSettings())
        assertEquals(5, rows.size)
        val master = rows[0]
        assertTrue(master.isMaster)
        assertEquals("Master", master.title)
        assertEquals("USB 1/2 · Rec Out", master.subtitle)
        assertFalse(master.armed)
        assertTrue(master.sourceOptions.isEmpty())
        assertEquals("CH1 Post-fader", rows[1].title)
        assertEquals("CH1 Post-fader", rows[1].fileLabel)
        assertEquals("USB 3/4", rows[1].subtitle)
        assertEquals(PioneerTrackRouting.POST_FADER, rows[1].selectedSource)
        assertTrue(rows[1].armed)
        assertTrue(rows[4].sourceOptions.isNotEmpty())
    }

    @Test
    fun `routes read back from the mixer win over the chosen source`() {
        val a9 = device(0x2B73, 0x003C, 10)
        val settings = MultitrackSettings(
            chosenSources = mapOf(2 to PioneerTrackRouting.PRE_FADER),
            currentSources = mapOf(2 to PioneerTrackRouting.PHONO),
            routingErrors = mapOf(2 to "The mixer did not accept this source.")
        )
        val row = MultitrackRows.build(a9, 10, 0, settings)[2]
        assertEquals("CH2 Phono", row.title)
        assertEquals("The mixer did not accept this source.", row.routingError)
    }

    @Test
    fun `generic interfaces use their usb pair numbers`() {
        val generic = device(0x1234, 0x0001, 6)
        val rows = MultitrackRows.build(generic, 6, masterOffset = 0, settings = MultitrackSettings(
            armed = mapOf(2 to false), gainsDb = mapOf(1 to -3.5f)
        ))
        assertEquals(listOf("Master", "USB 3/4", "USB 5/6"), rows.map { it.title })
        assertEquals("USB 3-4", rows[1].fileLabel)
        assertEquals("Stereo input", rows[1].subtitle)
        assertEquals(-3.5f, rows[1].gainDb)
        assertFalse(rows[2].armed)
        assertNull(rows[1].selectedSource)
        assertTrue(rows[1].sourceOptions.isEmpty())
    }

    @Test
    fun `an odd channel count ends with a mono track`() {
        val rows = MultitrackRows.build(device(0x1234, 0x0001, 5), 5, 0, MultitrackSettings())
        assertEquals("USB 5", rows.last().title)
        assertEquals("Mono input", rows.last().subtitle)
        assertEquals("USB 5", rows.last().fileLabel)
    }
}
