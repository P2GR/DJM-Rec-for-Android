package com.audiopro.djmrec.diagnostics

import kotlin.test.*

class TraceBufferTest {
    @Test fun optInAndStopExcludeEvents() {
        val trace = TraceBuffer()
        trace.add("test", "before")
        assertTrue(trace.snapshot().events.isEmpty())
        trace.start(); trace.add("test", "during"); trace.stop(); trace.add("test", "after")
        assertEquals(listOf("during"), trace.snapshot().events.map { it.detail })
    }
    @Test fun budgetsStopWithoutOverwritingEvidence() {
        val trace = TraceBuffer(maxRecords = 1)
        trace.start(); trace.add("test", "first"); trace.add("test", "second")
        assertFalse(trace.isActive())
        assertEquals(1L, trace.snapshot().dropped)
        assertEquals("first", trace.snapshot().events.single().detail)
        val bytes = TraceBuffer(budget = 150)
        bytes.start(); bytes.add("a", "b", ByteArray(100))
        assertFalse(bytes.isActive()); assertTrue(bytes.snapshot().events.isEmpty())
    }
    @Test fun truncationIsExplicitAndInputIsCopied() {
        val trace = TraceBuffer(maxPayload = 2)
        val bytes = byteArrayOf(1, 2, 3)
        trace.start(); trace.add("test", "", bytes); bytes[0] = 9
        val event = trace.snapshot().events.single()
        assertEquals(3, event.originalLength)
        assertContentEquals(byteArrayOf(1, 2), event.bytes)
    }
    @Test fun deadlineAndRestart() {
        var time = 10L
        val trace = TraceBuffer(clock = { time })
        trace.start(); trace.add("test", "before")
        time += 600_000_000_000L
        trace.add("test", "expired")
        assertFalse(trace.isActive()); assertEquals(1, trace.snapshot().events.size)
        trace.start(); assertTrue(trace.isActive()); assertTrue(trace.snapshot().events.isEmpty())
        trace.clear(); assertFalse(trace.isActive())
    }
    @Test fun concurrentWritersRespectLimit() {
        val trace = TraceBuffer(maxRecords = 100)
        trace.start()
        List(4) { Thread { repeat(100) { trace.add("concurrent", "$it") } }.apply { start() } }.forEach { it.join() }
        assertEquals(100, trace.snapshot().events.size)
        assertEquals(1L, trace.snapshot().dropped)
    }
}
