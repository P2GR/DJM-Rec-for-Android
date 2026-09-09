package com.audiopro.djmrec.prolink

import kotlin.test.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket

class ProLinkTest {
    private val key = TrackKey(2, 3, 1, 123)
    private fun deck(number: Int = 1, track: TrackKey = key) = DeckState(number, track, true, true,
        false, 128.0, 10, 1, 100, TrackMetadata("Title", "Artist"))

    /** Synthetic NXS2-shaped fixture with independently chosen known wire offsets. */
    private fun status() = ByteArray(0x124).apply {
        "Qspt1WmJOL".toByteArray().copyInto(this)
        this[10] = 10; this[0x21] = 1; this[0x22] = 1; this[0x23] = 0
        this[0x24] = 1; this[0x28] = 2; this[0x29] = 3; this[0x2a] = 1; this[0x2f] = 123
        this[0x7b] = 3; this[0x89] = 0x68; this[0x8d] = 0x10
        this[0x92] = 0x32; this[0x93] = 0; this[0xa3] = 10; this[0xcb] = 1
    }

    @Test fun `decode linked source rather than playing deck and apply pitch`() {
        val bytes = status()
        val decoded = assertNotNull(ProLinkPackets.deck(bytes, 500))
        assertEquals(key, decoded.track)
        assertTrue(decoded.playing); assertEquals(true, decoded.onAir); assertTrue(decoded.tempoMaster)
        assertEquals(128.0, decoded.bpm); assertEquals(10L, decoded.beat)
        bytes[0x8d] = 0x18
        assertEquals(192.0, ProLinkPackets.deck(bytes, 500)?.bpm)
    }

    @Test fun `malformed packets and every truncated prefix are rejected`() {
        val valid = status()
        for (size in 0 until valid.size) assertNull(ProLinkPackets.deck(valid.copyOf(size), 0))
        valid[0] = 0; assertNull(ProLinkPackets.deck(valid, 0))
        assertNull(ProLinkPackets.deck(ProLinkPackets.keepAlive(3, byteArrayOf(10, 0, 0, 1), ByteArray(6)), 0))
    }

    @Test fun `unknown values are not fabricated`() {
        val bytes = status().apply { this[0x89] = 0; this[0x92] = -1; this[0x93] = -1
            for (i in 0xa0..0xa3) this[i] = -1 }
        val decoded = assertNotNull(ProLinkPackets.deck(bytes, 0))
        assertNull(decoded.onAir); assertNull(decoded.bpm); assertNull(decoded.beat)
        bytes[0x2a] = 0
        assertNull(ProLinkPackets.deck(bytes, 0)?.track)
        for (play in listOf(0, 2, 5, 6, 8, 9, 0x11)) {
            bytes[0x7b] = play.toByte(); assertFalse(ProLinkPackets.deck(bytes, 0)!!.playing)
        }
    }

    @Test fun `counter wrap accepted but duplicate and old packets rejected`() {
        assertTrue(ProLinkPackets.newer(0, 0xffffffffL))
        assertFalse(ProLinkPackets.newer(1, 1)); assertFalse(ProLinkPackets.newer(0xffffffffL, 0))
    }

    @Test fun `announcement reports identity and conflicting channel`() {
        val bytes = ProLinkPackets.keepAlive(3, byteArrayOf(10, 0, 0, 7), byteArrayOf(2, 3, 4, 5, 6, 7))
        assertEquals(54, bytes.size)
        assertEquals("DJM Rec", ProLinkPackets.device(bytes, "10.0.0.7", 4)?.name)
        assertTrue(ProLinkPackets.conflicts(bytes, 3)); assertFalse(ProLinkPackets.conflicts(bytes, 2))
    }

    @Test fun `blends include both audible decks and exclude master cue deck`() {
        val state = DjLinkState(decks = listOf(deck(), deck(2), deck(3).copy(onAir = false, tempoMaster = true)))
        assertEquals(listOf(1, 2), state.nowPlaying().map { it.number })
        assertEquals(3, state.nowPlaying(false).size)
        assertTrue(state.copy(decks = listOf(deck().copy(onAir = null))).nowPlaying().isEmpty())
    }

    @Test fun `late metadata retains original audio offset without duplicate boundary`() {
        val timeline = TrackTimeline()
        val initial = timeline.update("set1", true, 1500, listOf(deck().copy(metadata = null))).single()
        val enriched = timeline.update("set1", true, 2800, listOf(deck())).single()
        assertEquals(initial.id, enriched.id); assertEquals(1500L, enriched.positionMillis)
        assertTrue(timeline.update("set1", true, 3000, listOf(deck())).isEmpty())
        assertTrue(timeline.update("set1", false, 3000, listOf(deck(1, key.copy(id = 124)))).isEmpty())
        val resumed = timeline.update("set1", true, 3000, listOf(deck(1, key.copy(id = 124)))).single()
        assertEquals(3000L, resumed.positionMillis)
        assertEquals(0L, timeline.update("part2", true, 0, listOf(deck())).single().positionMillis)
    }

    @Test fun `disconnect clears selection and reappearance creates new event`() {
        val timeline = TrackTimeline()
        val first = timeline.update("set", true, 0, listOf(deck())).single()
        timeline.update("set", true, 3000, emptyList())
        val next = timeline.update("set", true, 5000, listOf(deck())).single()
        assertNotEquals(first.id, next.id)
    }

    @Test fun `reloaded ID creates separate marker and metadata failure never erases existing title`() {
        val timeline = TrackTimeline()
        val first = timeline.update("set", true, 0, listOf(deck())).single()
        assertTrue(timeline.update("set", true, 1000, listOf(deck().copy(metadata = null))).isEmpty())
        val reload = timeline.update("set", true, 2000, listOf(deck().copy(loadGeneration = 1))).single()
        assertNotEquals(first.id, reload.id)
        assertEquals(2000L, reload.positionMillis)
    }

    @Test fun `NXS2 load and media removal signals are retained`() {
        val bytes = status().apply { this[0x58] = 0x80.toByte(); this[0x6f] = 4; this[0x73] = 0 }
        val state = ProLinkPackets.deck(bytes, 0)!!
        assertTrue(state.loading); assertEquals(false, state.usbMounted); assertEquals(true, state.sdMounted)
    }

    @Test fun `banner options hide unknown metadata and respect artist selection`() {
        val state = DjLinkState(decks = listOf(deck()))
        assertEquals("", NowPlayingOptions().text(state))
        assertEquals("Set: Title", NowPlayingOptions(bannerEnabled = true, prefix = "Set", showArtist = false).text(state))
        assertEquals("", NowPlayingOptions(bannerEnabled = true).text(state.copy(decks = emptyList())))
        assertEquals("", NowPlayingOptions(bannerEnabled = true).text(state.copy(decks = listOf(deck().copy(metadata = null)))))
    }

    private fun message(transaction: Long, type: Int, args: List<Any>): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        fun number(value: Long, bytes: Int = 4) {
            out.writeByte(if (bytes == 1) 15 else if (bytes == 2) 16 else 17)
            for (i in bytes - 1 downTo 0) out.writeByte((value shr (i * 8)).toInt())
        }
        number(0x872349ae); number(transaction); number(type.toLong(), 2); number(args.size.toLong(), 1)
        out.writeByte(20); out.writeInt(12)
        out.write(ByteArray(12) { if (it >= args.size) 0 else if (args[it] is String) 2 else 6 })
        args.forEach { arg ->
            if (arg is String) {
                out.writeByte(38); out.writeInt(arg.length + 1); out.write((arg + '\u0000').toByteArray(Charsets.UTF_16BE))
            } else number(arg as Long)
        }
        return buffer.toByteArray()
    }

    @Test fun `complete metadata exchange decodes unicode and queries source player`() {
        fun item(type: Long, title: String, value: Long = 123L) = message(2, 0x4101,
            listOf(0L, value, (title.length + 1L) * 2, title, 2L, "", type, 0L, 0L, 0L, 0L, 0L))
        val response = byteArrayOf(17, 0, 0, 0, 1) + message(0xfffffffeL, 0x4000, listOf(0L, 2L)) +
            message(1, 0x4000, listOf(0x2002L, 3L)) + message(2, 0x4001, emptyList()) +
            item(4, "夜の音") + item(7, "Björk") + item(11, "", 315) + message(2, 0x4201, emptyList())
        val requests = mutableListOf<ByteArrayOutputStream>()
        val ports = mutableListOf<Int>()
        val client = RemoteDbClient { address, port ->
            assertEquals("10.0.0.2", address); ports.add(port)
            val bytes = if (port == 12523) byteArrayOf(4, 27) else response // 1051
            val input = ByteArrayInputStream(bytes)
            val output = ByteArrayOutputStream().also(requests::add)
            object : Socket() {
                override fun getInputStream() = input
                override fun getOutputStream() = output
            }
        }
        assertEquals(TrackMetadata("夜の音", "Björk", 315), client.query("10.0.0.2", 3, key))
        assertEquals(listOf(12523, 1051), ports)
        assertEquals(19, requests.first().size())
        val sent = ByteArrayInputStream(requests[1].toByteArray())
        assertEquals(1L, DbWire.number(sent))
        assertEquals(listOf(3L), DbWire.read(sent, 0xfffffffeL).args)
        val query = DbWire.read(sent, 1)
        assertEquals(0x2002, query.type); assertEquals(listOf(0x03010301L, 123L), query.args)
        assertEquals(0x3000, DbWire.read(sent, 2).type)
    }

    @Test fun `metadata rejects truncation wrong transaction and oversized fields`() {
        val good = message(1, 0x4000, listOf(0L, 2L))
        assertFails { DbWire.read(ByteArrayInputStream(good), 2) }
        for (size in good.indices) assertFails { DbWire.read(ByteArrayInputStream(good.copyOf(size)), 1) }
        val huge = message(1, 0x4101, listOf("ok")).apply {
            // 32-byte message header, string type then four-byte UTF-16 character count.
            this[33] = 0x7f; this[34] = -1; this[35] = -1; this[36] = -1
        }
        assertFails { DbWire.read(ByteArrayInputStream(huge), 1) }
    }
}
