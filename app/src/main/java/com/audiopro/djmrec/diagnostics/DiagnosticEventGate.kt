package com.audiopro.djmrec.diagnostics

/** Suppresses identical remote events during retry loops while keeping distinct state changes. */
internal class DiagnosticEventGate(
    private val cooldownMillis: Long = 60_000L,
    private val maxEntries: Int = 128
) {
    private val lastSent = LinkedHashMap<String, Long>()

    @Synchronized
    fun shouldSend(key: String, nowMillis: Long): Boolean {
        val previous = lastSent[key]
        if (previous != null && nowMillis - previous < cooldownMillis) return false
        if (lastSent.size >= maxEntries && !lastSent.containsKey(key)) {
            lastSent.entries.minByOrNull { it.value }?.key?.let(lastSent::remove)
        }
        lastSent[key] = nowMillis
        return true
    }

    @Synchronized
    fun reset() = lastSent.clear()
}
