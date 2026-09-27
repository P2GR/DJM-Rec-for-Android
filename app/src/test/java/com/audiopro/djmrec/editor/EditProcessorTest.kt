package com.audiopro.djmrec.editor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditProcessorTest {
    private fun sine(rate: Int, seconds: Double, amplitude: Double, hz: Double = 997.0): DoubleArray {
        val frames = (rate * seconds).toInt()
        return DoubleArray(frames * 2) { i -> amplitude * sin(2 * PI * hz * (i / 2) / rate) }
    }

    @Test
    fun ebuCalibrationSineReadsMinus23Lufs() {
        for (rate in listOf(44_100, 48_000, 96_000)) {
            val meter = LoudnessMeter(rate, 2)
            val audio = sine(rate, 20.0, 10.0.pow(-23.0 / 20.0))
            meter.process(audio, audio.size / 2)
            val lufs = meter.integratedLufs()!!
            assertTrue(abs(lufs + 23.0) < 0.1, "$rate Hz measured $lufs LUFS")
        }
    }

    @Test
    fun silenceIsGatedOutOfTheMeasurement() {
        val rate = 48_000
        val meter = LoudnessMeter(rate, 2)
        val tone = sine(rate, 10.0, 10.0.pow(-18.0 / 20.0))
        meter.process(tone, tone.size / 2)
        val silence = DoubleArray(rate * 2 * 30)
        meter.process(silence, silence.size / 2)
        assertTrue(abs(meter.integratedLufs()!! + 18.0) < 0.1)
        // A range of only silence has no loudness at all.
        assertNull(meter.integratedLufs(fromHop = 150, toHop = meter.hopCount))
    }

    @Test
    fun trimKeepsExactlyTheSelectedFrames() {
        val rate = 1_000
        val source = DoubleArray(10_000 * 2) { i -> (i / 2) / 100_000.0 } // ramp, far below the ceiling
        val processor = EditProcessor(rate, 2, EditSettings(trimStartMillis = 1_234, trimEndMillis = 7_000), 0.0, 10_000)
        val out = mutableListOf<Double>()
        val collect: (DoubleArray, Int) -> Unit = { buffer, count -> for (i in 0 until count * 2) out += buffer[i] }
        var offset = 0
        while (offset < 10_000) {
            val count = minOf(777, 10_000 - offset)
            processor.process(source.copyOfRange(offset * 2, (offset + count) * 2), count, collect)
            offset += count
        }
        processor.finish(collect)
        assertEquals(7_000 - 1_234, out.size / 2)
        assertEquals(1_234 / 100_000.0, out[0], 1e-12)
        assertEquals(6_999 / 100_000.0, out[out.size - 1], 1e-12)
    }

    @Test
    fun fadesStartAndEndAtSilenceAndLimiterHoldsTheCeiling() {
        val rate = 8_000
        val frames = rate * 4
        val source = sine(rate, 4.0, 0.9, hz = 200.0)
        val processor = EditProcessor(rate, 2,
            EditSettings(fadeInMillis = 1_000, fadeOutMillis = 500), gainDb = 12.0, sourceFrames = frames.toLong())
        val out = mutableListOf<Double>()
        val collect: (DoubleArray, Int) -> Unit = { buffer, count -> for (i in 0 until count * 2) out += buffer[i] }
        processor.process(source, frames, collect)
        processor.finish(collect)
        assertEquals(frames, out.size / 2)
        assertEquals(0.0, out[0], 1e-12)
        assertEquals(0.0, out[out.size - 1], 1e-3)
        var peak = 0.0
        out.forEach { peak = max(peak, abs(it)) }
        assertTrue(peak <= EditMath.CEILING + 1e-9, "peak $peak exceeds the ceiling")
        assertTrue(peak > 0.85, "limited audio should still reach near the ceiling, was $peak")
    }

    @Test
    fun normalizationGainIsBounded() {
        assertEquals(9.0, EditMath.normalizationGainDb(-23.0, -14.0), 1e-9)
        assertEquals(EditMath.MAX_BOOST_DB, EditMath.normalizationGainDb(-60.0, -14.0), 1e-9)
        assertEquals(0.0, EditMath.normalizationGainDb(null, -14.0), 1e-9)
        assertEquals(0.0, EditMath.normalizationGainDb(-20.0, null), 1e-9)
    }
}
