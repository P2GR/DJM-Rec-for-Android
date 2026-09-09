package com.audiopro.djmrec.diagnostics

/** RAM-only bounded trace. Stops rather than silently overwriting the beginning of an experiment. */
class TraceBuffer(private val budget: Int = 8 * 1024 * 1024, private val maxRecords: Int = 16384,
                  private val maxPayload: Int = 65535, private val clock: () -> Long = System::nanoTime) {
    data class Event(val wallMs: Long, val monotonicNs: Long, val kind: String, val detail: String,
                     val originalLength: Int, val bytes: ByteArray)
    data class Snapshot(val active: Boolean, val reason: String, val dropped: Long, val events: List<Event>)
    private val records = ArrayList<Event>()
    private var used = 0
    private var started = 0L
    private var active = false
    private var reason = "Not started"
    private var dropped = 0L
    @Synchronized fun start() {
        records.clear(); used = 0; dropped = 0; started = clock(); active = true; reason = "Capturing"
    }
    @Synchronized fun stop(reason: String = "Stopped by user") { active = false; this.reason = reason }
    @Synchronized fun clear() { stop("Cleared"); records.clear(); used = 0; dropped = 0 }
    @Synchronized fun isActive(): Boolean {
        if (active && clock() - started >= 600_000_000_000L) stop("10-minute limit reached")
        return active
    }
    @Synchronized fun add(kind: String, detail: String, data: ByteArray = byteArrayOf()) {
        if (!isActive()) return
        val boundedKind = kind.take(64)
        val boundedDetail = detail.take(512)
        val length = minOf(data.size, maxPayload)
        val cost = length + boundedKind.length * 2 + boundedDetail.length * 2 + 128
        if (records.size >= maxRecords || cost > budget - used) {
            dropped++; stop("Capture budget reached"); return
        }
        records.add(Event(System.currentTimeMillis(), clock(), boundedKind, boundedDetail, data.size, data.copyOf(length)))
        used += cost
    }
    @Synchronized fun snapshot() = Snapshot(isActive(), reason, dropped, records.toList())
}
