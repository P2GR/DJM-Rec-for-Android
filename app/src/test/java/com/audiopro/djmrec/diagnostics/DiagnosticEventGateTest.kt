package com.audiopro.djmrec.diagnostics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticEventGateTest {
    @Test
    fun suppressesExactRetryButKeepsChangesAndLaterEvents() {
        val gate = DiagnosticEventGate(cooldownMillis = 60_000L)
        assertTrue(gate.shouldSend("USB/no-device", 0L))
        assertFalse(gate.shouldSend("USB/no-device", 5_000L))
        assertTrue(gate.shouldSend("USB/connected", 5_000L))
        assertTrue(gate.shouldSend("USB/no-device", 60_000L))
    }

    @Test
    fun remainsBoundedWithoutSuppressingNewKeys() {
        val gate = DiagnosticEventGate(cooldownMillis = 60_000L, maxEntries = 2)
        assertTrue(gate.shouldSend("one", 1L))
        assertTrue(gate.shouldSend("two", 2L))
        assertTrue(gate.shouldSend("three", 3L))
        assertTrue(gate.shouldSend("one", 4L))
    }
}
