package com.audiopro.djmrec.storage

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TrackMarker(val positionMillis: Long, val label: String, val eventId: String? = null,
                       val deck: Int? = null, val track: com.audiopro.djmrec.prolink.TrackKey? = null,
                       val metadata: com.audiopro.djmrec.prolink.TrackMetadata? = null)

/** Non-destructive track boundaries. Audio remains one continuous lossless file. */
object TrackMarkerStore {
    private fun file(context: Context, uri: Uri): AtomicFile {
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "track-markers").apply { mkdirs() }
        return AtomicFile(File(dir, "$key.json"))
    }

    @Synchronized
    fun read(context: Context, uri: Uri): List<TrackMarker> = runCatching {
        val array = JSONArray(file(context, uri).readFully().toString(Charsets.UTF_8))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val key = item.optJSONObject("track")?.let {
                com.audiopro.djmrec.prolink.TrackKey(it.getInt("sourcePlayer"), it.getInt("slot"), it.getInt("type"), it.getLong("id"))
            }
            val metadata = item.optJSONObject("metadata")?.let {
                com.audiopro.djmrec.prolink.TrackMetadata(it.getString("title"), it.optString("artist"),
                    if (it.has("durationSeconds")) it.getLong("durationSeconds") else null)
            }
            TrackMarker(item.getLong("positionMillis"), item.getString("label"),
                if (item.has("eventId")) item.getString("eventId") else null,
                if (item.has("deck")) item.getInt("deck") else null, key, metadata)
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(context: Context, uri: Uri, positionMillis: Long): Int {
        val markers = read(context, uri)
        if (markers.lastOrNull()?.let { positionMillis - it.positionMillis < 1_000 } == true) return markers.size
        val updated = markers + TrackMarker(positionMillis.coerceAtLeast(0), "Track ${markers.size + 1}")
        return write(context, uri, updated)
    }

    /** Late metadata enriches the original event; its audio position never moves. */
    @Synchronized
    fun upsert(context: Context, uri: Uri, entry: com.audiopro.djmrec.prolink.TrackTimeline.Entry): Int {
        val markers = read(context, uri).toMutableList()
        val label = entry.metadata?.label ?: "Deck ${entry.deck} · Track ${entry.track.id} (metadata unavailable)"
        val marker = TrackMarker(entry.positionMillis, label, entry.id, entry.deck, entry.track, entry.metadata)
        val index = markers.indexOfFirst { it.eventId == entry.id }
        if (index >= 0) markers[index] = marker else markers.add(marker)
        return write(context, uri, markers.sortedBy { it.positionMillis })
    }

    private fun write(context: Context, uri: Uri, updated: List<TrackMarker>): Int {
        val array = JSONArray().apply {
            updated.forEach { marker ->
                put(JSONObject().put("positionMillis", marker.positionMillis).put("label", marker.label).apply {
                    marker.eventId?.let { put("eventId", it) }
                    marker.deck?.let { put("deck", it) }
                    marker.track?.let { key -> put("track", JSONObject().put("sourcePlayer", key.sourcePlayer)
                        .put("slot", key.slot).put("type", key.type).put("id", key.id)) }
                    marker.metadata?.let { metadata -> put("metadata", JSONObject().put("title", metadata.title)
                        .put("artist", metadata.artist).apply {
                            metadata.durationSeconds?.let { put("durationSeconds", it) }
                        }) }
                })
            }
        }
        val atomic = file(context, uri)
        val output = atomic.startWrite()
        try {
            output.write(array.toString().toByteArray())
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        return updated.size
    }
}
