package com.audiopro.djmrec.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsbAudioDescriptorParserTest {
    @Test
    fun parsesUacTopologyAndStreamingFormat() {
        val descriptors = descriptorBytes(
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            9, 0x24, 1, 0, 2, 9, 0, 0, 0,
            8, 0x24, 0x0A, 1, 1, 1, 0, 0,
            14, 0x24, 6, 2, 1, 1, 0, 0, 0, 0, 2, 0, 0, 0,
            17, 0x24, 2, 3, 1, 0x01, 0x02, 1, 2, 3, 0, 0, 0, 0, 0, 0, 0,
            9, 4, 1, 1, 1, 1, 2, 0x20, 0,
            16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 4, 0, 0, 0, 0, 0,
            6, 0x24, 2, 1, 3, 24,
            7, 5, 0x81, 1, 0x80, 1, 1
        )

        val topology = UsbAudioDescriptorParser.parseTopology(descriptors)

        assertEquals(0, topology.audioControlInterface?.interfaceNumber)
        assertEquals(listOf(0), topology.audioControlInterfaces.map { it.interfaceNumber })
        assertEquals(1, topology.clockSources.single().id)
        assertTrue(topology.clockSources.single().supportsFrequencyControl)
        assertEquals(1, topology.featureUnits.single().sourceId)
        assertEquals(2, topology.inputTerminals.single().channelCount)
        assertTrue(topology.descriptorSampleRates.isEmpty())
        assertEquals(4, topology.audioStreamingInterfaces.single().channelCount)
        assertEquals(0x81, topology.audioStreamingInterfaces.single().isochronousInEndpointAddress)
    }

    @Test
    fun identifiesReadWriteClockFrequencyControl() {
        val descriptors = descriptorBytes(
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            9, 0x24, 1, 0, 2, 9, 0, 0, 0,
            // UAC2 Clock Source bmaControls: bits 0-1 = 0b11 (read/write frequency control).
            8, 0x24, 0x0A, 1, 1, 0x03, 0, 0
        )

        val clock = UsbAudioDescriptorParser.parseTopology(descriptors).clockSources.single()

        assertTrue(clock.supportsFrequencyControl)
        assertTrue(clock.supportsFrequencySet)
    }

    @Test
    fun doesNotTreatUac1ExtensionUnitAsClockSource() {
        val descriptors = descriptorBytes(
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            9, 0x24, 1, 0, 1, 17, 0, 0, 0,
            // In UAC1 subtype 0x0A is an Extension Unit, not a Clock Source.
            8, 0x24, 0x0A, 1, 1, 0x03, 0, 0
        )

        val topology = UsbAudioDescriptorParser.parseTopology(descriptors)

        assertEquals(0x0100, topology.audioControlInterface?.audioClassVersion)
        assertTrue(topology.clockSources.isEmpty())
    }

    @Test
    fun tracksMixedAudioControlVersionsIndependently() {
        val descriptors = descriptorBytes(
            9, 4, 0, 0, 0, 1, 1, 0x20, 0,
            9, 0x24, 1, 0, 2, 17, 0, 0, 0,
            8, 0x24, 0x0A, 1, 1, 0x03, 0, 0,
            9, 4, 3, 0, 0, 1, 1, 0, 0,
            9, 0x24, 1, 0, 1, 17, 0, 0, 0,
            8, 0x24, 0x0A, 2, 1, 0x03, 0, 0
        )

        val topology = UsbAudioDescriptorParser.parseTopology(descriptors)

        assertEquals(listOf(0x0200, 0x0100), topology.audioControlInterfaces.map { it.audioClassVersion })
        assertEquals(listOf(1), topology.clockSources.map { it.id })
    }

    @Test
    fun parsesUac1StereoWithoutMistakingChannelsForSubslotSize() {
        val raw = descriptorBytes(
            9, 4, 1, 1, 1, 1, 2, 0, 0,
            7, 0x24, 1, 1, 1, 1, 0,
            11, 0x24, 2, 1, 2, 3, 24, 1, 0x80, 0xBB, 0,
            7, 5, 0x81, 1, 0x20, 1, 1
        )
        val format = UsbAudioDescriptorParser.selectBestStereoInterface(
            UsbAudioDescriptorParser.findAudioStreamingInterfaces(raw))!!
        assertEquals(2, format.channelCount)
        assertEquals(3, format.subframeSize)
        assertEquals(24, format.bitResolution)
        assertEquals(listOf(48000), UsbAudioDescriptorParser.parseTopology(raw).descriptorSampleRates)
    }

    @Test
    fun feedbackEndpointDoesNotReplaceCaptureData() {
        val raw = descriptorBytes(
            9, 4, 1, 1, 2, 1, 2, 0x20, 0,
            16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0,
            6, 0x24, 2, 1, 3, 24,
            7, 5, 0x81, 1, 0x20, 1, 1,
            7, 5, 0x82, 0x11, 4, 0, 1
        )
        val format = UsbAudioDescriptorParser.findAudioStreamingInterfaces(raw).single()
        assertEquals(0x81, format.isochronousInEndpointAddress)
        assertEquals(0x82, format.isochronousFeedbackEndpointAddress)
        val vendor = UsbAudioDescriptorParser.findVendorEndpoint(raw, 1, 1)!!
        assertEquals(0x81, vendor.isochronousInEndpointAddress)
        assertEquals(0x82, vendor.isochronousFeedbackEndpointAddress)
    }

    @Test
    fun truncatedDescriptorsNeverReadBeyondTheirOwnLength() {
        listOf(descriptorBytes(2, 4), descriptorBytes(3, 5, 0x81),
            descriptorBytes(9, 4, 0, 1, 1, 255, 0, 0, 0, 2, 5)).forEach { raw ->
            assertTrue(UsbAudioDescriptorParser.findAudioStreamingInterfaces(raw).isEmpty())
            assertEquals(null, UsbAudioDescriptorParser.findVendorEndpoint(raw, 0, 1))
        }
    }

    @Test
    fun vendorProfilesLocateOnlyTheirOwnCaptureInterface() {
        PioneerMixerProfile.entries.filter { it.hasVendorCaptureOverride }.forEach { profile ->
            val raw = descriptorBytes(
                9, 4, profile.vendorCaptureInterface, profile.vendorCaptureAlternateSetting, 2, 255, 0, 0, 0,
                7, 5, 1, 1, 0, 4, 1,
                7, 5, 0x82, 1, 0, 4, 1
            )
            assertTrue(UsbAudioDescriptorParser.findAudioStreamingInterfaces(raw).isEmpty())
            assertEquals(0x82, UsbAudioDescriptorParser.findVendorEndpoint(raw,
                profile.vendorCaptureInterface, profile.vendorCaptureAlternateSetting)?.isochronousInEndpointAddress)
            assertEquals(null, UsbAudioDescriptorParser.findVendorEndpoint(raw, 9, 1))
        }
    }

    @Test
    fun captureRatesDoNotLeakFromPlaybackInterface() {
        val raw = descriptorBytes(
            9, 4, 1, 1, 1, 1, 2, 0, 0,
            7, 0x24, 1, 1, 1, 1, 0,
            11, 0x24, 2, 1, 2, 3, 24, 1, 0x80, 0xBB, 0,
            7, 5, 0x81, 1, 0x20, 1, 1,
            9, 4, 2, 1, 1, 1, 2, 0, 0,
            7, 0x24, 1, 1, 1, 1, 0,
            11, 0x24, 2, 1, 2, 3, 24, 1, 0x00, 0x77, 1,
            7, 5, 1, 1, 0x20, 1, 1
        )
        val formats = UsbAudioDescriptorParser.findAudioStreamingInterfaces(raw)
        assertEquals(listOf(48000), UsbAudioDescriptorParser.selectBestStereoInterface(formats)!!.sampleRates)
    }

    @Test
    fun continuousCaptureRatesStayWithinAdvertisedRange() {
        val raw = descriptorBytes(
            9, 4, 1, 1, 1, 1, 2, 0, 0,
            7, 0x24, 1, 1, 1, 1, 0,
            14, 0x24, 2, 1, 2, 3, 24, 0, 0x80, 0xBB, 0, 0x00, 0x77, 1,
            7, 5, 0x81, 1, 0x20, 1, 1
        )
        val format = UsbAudioDescriptorParser.findAudioStreamingInterfaces(raw).single()
        assertEquals(listOf(48000, 88200, 96000), format.sampleRates)
    }

    private fun descriptorBytes(vararg values: Int): ByteArray =
        values.map { it.toByte() }.toByteArray()
}
