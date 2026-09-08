package com.audiopro.djmrec.usb

/** Only wire formats our signed PCM decoder actually understands. No inference from model names. */
object CaptureFormatPolicy {
    fun isSupported(channels: Int, subframe: Int, bits: Int): Boolean =
        channels in 1..32 && subframe in 2..4 && bits in setOf(16, 24, 32) && bits <= subframe * 8

    fun ratesInRange(minimum: Int, maximum: Int, resolution: Int): List<Int> {
        if (minimum <= 0 || maximum < minimum || maximum > 384_000 || resolution < 0) return emptyList()
        if (minimum == maximum) return listOf(minimum)
        return listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)
            .filter { it in minimum..maximum && (resolution == 0 || (it - minimum) % resolution == 0) }
    }
}
