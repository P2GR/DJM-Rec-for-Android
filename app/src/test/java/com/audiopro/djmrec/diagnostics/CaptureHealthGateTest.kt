package com.audiopro.djmrec.diagnostics

import org.junit.Assert.*
import org.junit.Test

class CaptureHealthGateTest {
    @Test fun restartingTheSameSilentConnectionGetsFreshSnapshots() {
        val gate = CaptureHealthGate()
        assertTrue(gate.shouldLog("450", "SILENCE", 0))
        assertTrue(gate.shouldLog("450", "SILENCE", 5000))
        assertFalse(gate.shouldLog("450", "SILENCE", 6000))
        gate.reset()
        assertTrue(gate.shouldLog("450", "SILENCE", 7000))
        assertTrue(gate.shouldLog("450", "SILENCE", 12000))
        assertFalse(gate.shouldLog("450", "SILENCE", 13000))
    }

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
