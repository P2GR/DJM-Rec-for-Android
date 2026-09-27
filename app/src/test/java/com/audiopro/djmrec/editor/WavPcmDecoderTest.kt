package com.audiopro.djmrec.editor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavPcmDecoderTest {
    private fun wav(format: Int, bits: Int, channels: Int, rate: Int, data: ByteArray, dataSize: Long = data.size.toLong(),
                    extraChunk: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Long) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array())
        fun u16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        out.write("RIFF".toByteArray()); u32(0); out.write("WAVE".toByteArray())
        if (extraChunk) { out.write("LIST".toByteArray()); u32(3); out.write(byteArrayOf(1, 2, 3, 0)) }
        out.write("fmt ".toByteArray()); u32(16)
        u16(format); u16(channels); u32(rate.toLong()); u32((rate * channels * bits / 8).toLong()); u16(channels * bits / 8); u16(bits)
        out.write("data".toByteArray()); u32(dataSize)
        out.write(data)
        return out.toByteArray()
    }

    @Test
    fun decodes24BitStereoAndSkipsUnknownChunks() {
        // Frames: (+full scale - 1 LSB, -full scale), (0, 1 LSB)
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x00, 0x80.toByte(), 0, 0, 0, 1, 0, 0)
        val decoder = WavPcmDecoder(ByteArrayInputStream(wav(1, 24, 2, 48_000, data, extraChunk = true)))
        assertEquals(48_000, decoder.sampleRate)
        assertEquals(2, decoder.channels)
        assertEquals(24, decoder.bitsPerSample)
        val out = DoubleArray(8)
        assertEquals(2, decoder.read(out, 4))
        assertEquals(8_388_607 / 8_388_608.0, out[0], 1e-12)
        assertEquals(-1.0, out[1], 1e-12)
        assertEquals(0.0, out[2], 1e-12)
        assertEquals(1 / 8_388_608.0, out[3], 1e-12)
        assertEquals(0, decoder.read(out, 4))
    }

    @Test
    fun unfinishedHeaderReadsToEndOfFile() {
        val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(16384).putShort(-16384).putShort(1).putShort(-1).array()
        val decoder = WavPcmDecoder(ByteArrayInputStream(wav(1, 16, 2, 44_100, data, dataSize = 0)))
        val out = DoubleArray(4)
        assertEquals(1, decoder.read(out, 1))
        assertEquals(0.5, out[0], 1e-12)
        assertEquals(-0.5, out[1], 1e-12)
        assertEquals(1, decoder.read(out, 5))
        assertEquals(0, decoder.read(out, 5))
    }

    @Test
    fun readsFloatWav() {
        val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).putFloat(-0.75f).array()
        val decoder = WavPcmDecoder(ByteArrayInputStream(wav(3, 32, 2, 96_000, data)))
        val out = DoubleArray(2)
        assertEquals(1, decoder.read(out, 1))
        assertEquals(0.25, out[0], 1e-9)
        assertEquals(-0.75, out[1], 1e-9)
    }

    @Test
    fun rejectsNonWav() {
        assertFailsWith<java.io.IOException> { WavPcmDecoder(ByteArrayInputStream("fLaC0000000000000000".toByteArray())) }
    }
}
