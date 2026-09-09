package com.audiopro.djmrec.storage

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TrackMarker(val positionMillis: Long, val label: String)

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
            TrackMarker(item.getLong("positionMillis"), item.getString("label"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(context: Context, uri: Uri, positionMillis: Long): Int {
        val markers = read(context, uri)
        if (markers.lastOrNull()?.let { positionMillis - it.positionMillis < 1_000 } == true) return markers.size
        val updated = markers + TrackMarker(positionMillis.coerceAtLeast(0), "Track ${markers.size + 1}")
        val array = JSONArray().apply {
            updated.forEach { put(JSONObject().put("positionMillis", it.positionMillis).put("label", it.label)) }
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
