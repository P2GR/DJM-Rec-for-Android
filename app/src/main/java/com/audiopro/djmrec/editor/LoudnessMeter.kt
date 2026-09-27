package com.audiopro.djmrec.editor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * ITU-R BS.1770-4 / EBU R128 integrated loudness (LUFS) with absolute and relative gating.
 *
 * Audio is K-weighted and summed into 100 ms hops; each 400 ms gating block is four hops (75 %
 * overlap). Hop energies are kept, so [integratedLufs] can measure any trimmed range of a set
 * after a single analysis pass. A three-hour set keeps about 108,000 doubles.
 */
class LoudnessMeter(sampleRate: Int, private val channels: Int) {
    private val filters = Array(channels) { KWeightingFilter(sampleRate) }
    private val hopFrames = max(1, sampleRate / 10)
    private val channelEnergy = DoubleArray(channels)
    private var framesInHop = 0
    private var hops = DoubleArray(4096)

    /** Number of complete 100 ms hops measured so far. */
    var hopCount = 0
        private set

    /** Largest absolute sample value seen (1.0 = full scale). */
    var samplePeak = 0.0
        private set

    /** Feeds [frameCount] interleaved frames of full-scale-normalized samples. */
    fun process(interleaved: DoubleArray, frameCount: Int) {
        for (frame in 0 until frameCount) {
            val base = frame * channels
            for (channel in 0 until channels) {
                val sample = interleaved[base + channel]
                samplePeak = max(samplePeak, abs(sample))
                val weighted = filters[channel].process(sample)
                channelEnergy[channel] += weighted * weighted
            }
            if (++framesInHop == hopFrames) closeHop()
        }
    }

    private fun closeHop() {
        var sum = 0.0
        for (channel in 0 until channels) {
            // BS.1770 channel weight is 1.0 for left, right and centre-like channels.
            sum += channelEnergy[channel] / hopFrames
            channelEnergy[channel] = 0.0
        }
        framesInHop = 0
        if (hopCount == hops.size) hops = hops.copyOf(hops.size * 2)
        hops[hopCount++] = sum
    }

    /**
     * Gated integrated loudness over hops [fromHop, toHop), or null when the range holds no
     * block above the -70 LUFS absolute gate (silence).
     */
    fun integratedLufs(fromHop: Int = 0, toHop: Int = hopCount): Double? {
        val start = fromHop.coerceIn(0, hopCount)
        val end = toHop.coerceIn(start, hopCount)
        val blockCount = end - start - 3
        if (blockCount <= 0) return null
        val blocks = DoubleArray(blockCount) { index ->
            (hops[start + index] + hops[start + index + 1] + hops[start + index + 2] + hops[start + index + 3]) / 4.0
        }
        val absoluteGated = blocks.filter { loudness(it) > ABSOLUTE_GATE_LUFS }
        if (absoluteGated.isEmpty()) return null
        val relativeGate = loudness(absoluteGated.average()) + RELATIVE_GATE_LU
        val gated = absoluteGated.filter { loudness(it) > relativeGate }
        if (gated.isEmpty()) return null
        return loudness(gated.average())
    }

    companion object {
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = -10.0
        const val HOP_MILLIS = 100L

        fun loudness(meanSquare: Double): Double =
            if (meanSquare <= 0.0) Double.NEGATIVE_INFINITY else -0.691 + 10.0 * log10(meanSquare)
    }
}

/** BS.1770 K-weighting (high shelf + RLB high-pass) with coefficients for any sample rate. */
internal class KWeightingFilter(sampleRate: Int) {
    private val shelf: Biquad
    private val highPass: Biquad

    init {
        val rate = sampleRate.toDouble()
        // Stage 1: high shelf (+4 dB above ~1.7 kHz), per libebur128's analog prototype.
        var f0 = 1681.974450955533
        val gainDb = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / rate)
        val vh = 10.0.pow(gainDb / 20.0)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1.0 + k / q + k * k
        shelf = Biquad(
            (vh + vb * k / q + k * k) / a0,
            2.0 * (k * k - vh) / a0,
            (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0,
            (1.0 - k / q + k * k) / a0
        )
        // Stage 2: RLB high-pass at ~38 Hz.
        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / rate)
        a0 = 1.0 + k / q + k * k
        highPass = Biquad(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    fun process(sample: Double): Double = highPass.process(shelf.process(sample))

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }
    }
}
