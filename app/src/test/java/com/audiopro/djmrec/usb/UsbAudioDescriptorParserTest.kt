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
            9, 4, 1, 1, 1, 1, 2, 0, 0,
            16, 0x24, 1, 1, 0, 1, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0,
            10, 0x24, 2, 1, 3, 24, 1, 0x80, 0xBB, 0x00,
            7, 5, 0x81, 1, 0x80, 1, 1
        )

        val topology = UsbAudioDescriptorParser.parseTopology(descriptors)

        assertEquals(0, topology.audioControlInterface?.interfaceNumber)
        assertEquals(listOf(0), topology.audioControlInterfaces.map { it.interfaceNumber })
        assertEquals(1, topology.clockSources.single().id)
        assertTrue(topology.clockSources.single().supportsFrequencyControl)
        assertEquals(1, topology.featureUnits.single().sourceId)
        assertEquals(2, topology.inputTerminals.single().channelCount)
        assertEquals(listOf(48000), topology.descriptorSampleRates)
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

    private fun descriptorBytes(vararg values: Int): ByteArray =
        values.map { it.toByte() }.toByteArray()
}
