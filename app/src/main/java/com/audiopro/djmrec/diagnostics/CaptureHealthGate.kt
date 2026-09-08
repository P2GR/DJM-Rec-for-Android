package com.audiopro.djmrec.diagnostics

/** Log state transitions plus one settled snapshot, never a healthy heartbeat. */
internal class CaptureHealthGate {
    private var lastKey: String? = null
    private var changedAt = 0L
    private var settled = false

    fun reset() { lastKey = null }

    fun shouldLog(connection: String, health: String, now: Long): Boolean {
        val key = "$connection/$health"
        if (key != lastKey) {
            lastKey = key
            changedAt = now
            settled = false
            return true
        }
        if (!settled && now - changedAt >= 5_000) {
            settled = true
            return true
        }
        return false
    }
}
