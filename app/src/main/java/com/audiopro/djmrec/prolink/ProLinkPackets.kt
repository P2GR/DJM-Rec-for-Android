package com.audiopro.djmrec.prolink

/** Independent implementation of the documented legacy CDJ wire format. All offsets are bytes. */
object ProLinkPackets {
    private val magic = "Qspt1WmJOL".toByteArray(Charsets.US_ASCII)
    fun valid(data: ByteArray): Boolean = data.size >= 0x24 && magic.indices.all { data[it] == magic[it] }
    private fun ByteArray.u(i: Int) = this[i].toInt() and 255
    private fun ByteArray.n(i: Int, size: Int): Long = (i until i + size).fold(0L) { n, p -> (n shl 8) or u(p).toLong() }

    fun device(data: ByteArray, address: String, now: Long): LinkDevice? {
        if (!valid(data) || data.u(10) != 6 || data.size < 0x36 || data.n(0x22, 2) != data.size.toLong()) return null
        val number = data.u(0x24)
        if (number == 0) return null
        return LinkDevice(number, String(data, 12, 20, Charsets.US_ASCII).trimEnd('\u0000'), address, now)
    }

    fun deck(data: ByteArray, now: Long): DeckState? {
        if (!valid(data) || data.u(10) != 0x0a || data.size < 0xd0 || data.n(0x22, 2) != (data.size - 0x24).toLong()) return null
        val number = data.u(0x21)
        if (number !in 1..4 || data.u(0x24) != number) return null
        val key = if (data.u(0x2a) == 0 || data.n(0x2c, 4) == 0L) null
            else TrackKey(data.u(0x28), data.u(0x29), data.u(0x2a), data.n(0x2c, 4))
        val flags = data.u(0x89)
        val bpm = data.n(0x92, 2).takeUnless { it == 0xffffL }?.let { it / 100.0 * data.n(0x8c, 4) / 0x100000 }
        return DeckState(number, key, data.u(0x7b) in setOf(3, 4, 7, 0x12),
            if (flags == 0) null else flags and 8 != 0, flags and 0x20 != 0, bpm,
            data.n(0xa0, 4).takeUnless { it == 0xffffffffL }, data.n(0xc8, 4), now,
            loading = data.u(0x58) == 0x80 || data.u(0x7b) == 2,
            usbMounted = mounted(data.u(0x6f)), sdMounted = mounted(data.u(0x73)))
    }

    private fun mounted(value: Int): Boolean? = when (value) { 0 -> true; 2, 3, 4 -> false; else -> null }

    fun conflicts(data: ByteArray, number: Int): Boolean {
        if (!valid(data)) return false
        return when (data.u(10)) {
            2 -> data.size >= 0x32 && data.u(0x2e) == number
            4, 6, 8 -> data.size >= 0x25 && data.u(0x24) == number
            else -> false
        }
    }

    fun keepAlive(number: Int, ip: ByteArray, mac: ByteArray): ByteArray {
        require(number in 1..4 && ip.size == 4 && mac.size == 6)
        return ByteArray(0x36).apply {
            magic.copyInto(this); this[10] = 6
            "DJM Rec".toByteArray(Charsets.US_ASCII).copyInto(this, 12)
            this[0x20] = 1; this[0x21] = 2; this[0x23] = 0x36
            this[0x24] = number.toByte(); this[0x25] = 1
            mac.copyInto(this, 0x26); ip.copyInto(this, 0x2c)
            this[0x30] = 1; this[0x34] = 1
        }
    }

    fun newer(counter: Long, previous: Long): Boolean = ((counter - previous) and 0xffffffffL) in 1..0x7fffffffL
}
