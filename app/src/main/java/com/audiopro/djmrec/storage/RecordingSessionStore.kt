package com.audiopro.djmrec.storage

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.audiopro.djmrec.audio.RecordingFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecordingPartRecord(
    val uri: String,
    val displayName: String,
    val format: String,
    val index: Int,
    val finalized: Boolean
)

data class ActiveRecordingSession(
    val id: String,
    val format: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val deviceLabel: String,
    val startedAtMillis: Long,
    val elapsedMillis: Long,
    val parts: List<RecordingPartRecord>
)

object RecordingSessionStore {
    private const val FILE_NAME = "active-recording.json"

    @Synchronized
    fun begin(
        context: Context,
        id: String,
        format: RecordingFormat,
        sampleRate: Int,
        bitDepth: Int,
        deviceLabel: String,
        part: RecordingPartRecord
    ) {
        write(
            context,
            ActiveRecordingSession(
                id = id,
                format = format.name,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                deviceLabel = deviceLabel,
                startedAtMillis = System.currentTimeMillis(),
                elapsedMillis = 0,
                parts = listOf(part)
            )
        )
    }

    @Synchronized
    fun checkpoint(context: Context, elapsedMillis: Long) {
        val active = read(context) ?: return
        write(context, active.copy(elapsedMillis = elapsedMillis.coerceAtLeast(0)))
    }

    @Synchronized
    fun addPart(context: Context, part: RecordingPartRecord) {
        val active = read(context) ?: return
        write(context, active.copy(parts = active.parts + part))
    }

    @Synchronized
    fun markFinalized(context: Context, uri: Uri) {
        val active = read(context) ?: return
        write(
            context,
            active.copy(parts = active.parts.map {
                if (it.uri == uri.toString()) it.copy(finalized = true) else it
            })
        )
    }

    @Synchronized
    fun complete(context: Context) {
        AtomicFile(file(context)).delete()
    }

    @Synchronized
    fun completeIfFinalized(context: Context): Boolean {
        val active = read(context) ?: return true
        if (active.parts.any { !it.finalized }) return false
        complete(context)
        return true
    }

    @Synchronized
    fun read(context: Context): ActiveRecordingSession? {
        return runCatching {
            val jsonText = AtomicFile(file(context)).openRead().bufferedReader().use { it.readText() }
            val json = JSONObject(jsonText)
            val partsJson = json.getJSONArray("parts")
            val parts = buildList {
                for (index in 0 until partsJson.length()) {
                    val item = partsJson.getJSONObject(index)
                    add(
                        RecordingPartRecord(
                            uri = item.getString("uri"),
                            displayName = item.getString("displayName"),
                            format = item.getString("format"),
                            index = item.getInt("index"),
                            finalized = item.optBoolean("finalized", false)
                        )
                    )
                }
            }
            ActiveRecordingSession(
                id = json.getString("id"),
                format = json.getString("format"),
                sampleRate = json.getInt("sampleRate"),
                bitDepth = json.getInt("bitDepth"),
                deviceLabel = json.optString("deviceLabel", "USB Mixer"),
                startedAtMillis = json.getLong("startedAtMillis"),
                elapsedMillis = json.optLong("elapsedMillis", 0),
                parts = parts
            )
        }.getOrNull()
    }

    fun describe(context: Context): String = read(context)?.let {
        "active id=${it.id} format=${it.format} elapsedMs=${it.elapsedMillis} " +
            "parts=${it.parts.size} pending=${it.parts.count { part -> !part.finalized }}"
    } ?: "none"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun write(context: Context, session: ActiveRecordingSession) {
        val parts = JSONArray().apply {
            session.parts.forEach { part ->
                put(
                    JSONObject()
                        .put("uri", part.uri)
                        .put("displayName", part.displayName)
                        .put("format", part.format)
                        .put("index", part.index)
                        .put("finalized", part.finalized)
                )
            }
        }
        val json = JSONObject()
            .put("schema", 1)
            .put("id", session.id)
            .put("format", session.format)
            .put("sampleRate", session.sampleRate)
            .put("bitDepth", session.bitDepth)
            .put("deviceLabel", session.deviceLabel)
            .put("startedAtMillis", session.startedAtMillis)
            .put("elapsedMillis", session.elapsedMillis)
            .put("parts", parts)
        val atomic = AtomicFile(file(context))
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
