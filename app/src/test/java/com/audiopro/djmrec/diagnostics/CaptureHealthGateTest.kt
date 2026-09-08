package com.audiopro.djmrec.diagnostics

import org.junit.Assert.*
import org.junit.Test

class CaptureHealthGateTest {
    @Test fun healthySessionSettlesThenStaysQuiet() {
        val gate = CaptureHealthGate()
        assertTrue(gate.shouldLog("a", "GOOD", 0))
        assertFalse(gate.shouldLog("a", "GOOD", 1000))
        assertTrue(gate.shouldLog("a", "GOOD", 5000))
        for (t in 6000L..3600000L step 1000) assertFalse(gate.shouldLog("a", "GOOD", t))
        assertTrue(gate.shouldLog("a", "ERROR", 3601000))
        assertTrue(gate.shouldLog("a", "GOOD", 3602000))
        assertTrue(gate.shouldLog("b", "GOOD", 3603000))
    }
}
