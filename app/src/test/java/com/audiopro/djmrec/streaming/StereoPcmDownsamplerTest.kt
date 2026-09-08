package com.audiopro.djmrec.streaming
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class StereoPcmDownsamplerTest {
    private fun tone(rate: Int, frequency: Int): ByteArray = ByteArray(rate / 5 * 4).apply {
        for (i in 0 until size / 4) {
            val value = (sin(2 * PI * frequency * i / rate) * 16000).toInt()
            this[i * 4] = value.toByte(); this[i * 4 + 1] = (value shr 8).toByte()
            this[i * 4 + 2] = (-value).toByte(); this[i * 4 + 3] = (-value shr 8).toByte()
        }
    }
    private fun rms(bytes: ByteArray): Double {
        var sum = 0.0
        for (i in 400 until bytes.size step 4) {
            val value = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += value * value
        }
        return sqrt(sum / ((bytes.size - 400) / 4))
    }
    @Test fun downsamplingPreservesDurationStereoAndRejectsAliasing() {
        val input = tone(96000, 1000)
        val output = StereoPcmDownsampler(96000, 48000).convert(input, input.size)
        assertEquals(input.size / 2, output.size)
        assertTrue(rms(output) in 10500.0..12000.0)
        for (i in output.indices step 4) {
            fun sample(j: Int) = ((output[j].toInt() and 255) or (output[j + 1].toInt() shl 8)).toShort().toInt()
            assertTrue(abs(sample(i) + sample(i + 2)) <= 1)
        }
        val high = tone(96000, 30000)
        assertTrue(rms(StereoPcmDownsampler(96000, 48000).convert(high, high.size)) < 100)
    }
    @Test fun arbitraryBlockBoundariesPreserveFilterState() {
        val input = tone(88200, 1000)
        val expected = StereoPcmDownsampler(88200, 44100).convert(input, input.size)
        val converter = StereoPcmDownsampler(88200, 44100)
        val output = java.io.ByteArrayOutputStream()
        for (offset in input.indices step 404) {
            val block = input.copyOfRange(offset, minOf(offset + 404, input.size))
            output.write(converter.convert(block, block.size))
        }
        assertArrayEquals(expected, output.toByteArray())
        assertNull(StreamAudioFormat.fromCapture(99271))
        assertEquals(48000, StreamAudioFormat.fromCapture(96000)?.encoderRate)
    }
}
