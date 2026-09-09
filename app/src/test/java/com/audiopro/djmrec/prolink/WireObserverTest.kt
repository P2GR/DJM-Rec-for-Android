package com.audiopro.djmrec.prolink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class WireObserverTest {
    @Test fun readsCaptureOnlyReturnedBytesWithoutDuplicatesOrEof() {
        val captured = ByteArrayOutputStream()
        val observer = WireObserver { transport, direction, peer, port, data ->
            assertEquals("tcp", transport); assertEquals("in", direction)
            assertEquals("player", peer); assertEquals(12523, port); captured.write(data)
        }
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3)).observed(observer, "player", 12523)
        assertEquals(1, input.read())
        val destination = ByteArray(8) { 99 }
        assertEquals(2, input.read(destination, 2, 5))
        assertEquals(99, destination[0].toInt()); assertEquals(2, destination[2].toInt())
        assertEquals(-1, input.read()); assertEquals(-1, input.read(destination))
        assertContentEquals(byteArrayOf(1, 2, 3), captured.toByteArray())
    }
    @Test fun writesPreserveWireAndCaptureExactlyOnce() {
        val captured = ByteArrayOutputStream()
        val wire = ByteArrayOutputStream()
        val output = wire.observed(WireObserver { _, direction, _, _, data ->
            assertEquals("out", direction); captured.write(data)
        }, "player", 1234)
        output.write(1); output.write(byteArrayOf(8, 2, 3, 9), 1, 2); output.flush()
        assertContentEquals(byteArrayOf(1, 2, 3), wire.toByteArray())
        assertContentEquals(wire.toByteArray(), captured.toByteArray())
    }
}
