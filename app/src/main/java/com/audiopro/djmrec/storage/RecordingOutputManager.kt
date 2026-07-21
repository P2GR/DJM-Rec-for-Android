package com.audiopro.djmrec.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.system.Os
import com.audiopro.djmrec.audio.RecordingFormat

data class PendingRecordingOutput(
    val uri: Uri,
    val descriptor: ParcelFileDescriptor,
    val displayName: String,
    val format: RecordingFormat,
    val partIndex: Int
) {
    fun toRecord(finalized: Boolean = false) = RecordingPartRecord(
        uri = uri.toString(),
        displayName = displayName,
        format = format.name,
        index = partIndex,
        finalized = finalized
    )
}

data class RecoverySummary(val recovered: Int, val removed: Int, val failed: Int) {
    val hasWork: Boolean get() = recovered + removed + failed > 0
    val message: String
        get() = buildString {
            append("Interrupted recording recovery finished.")
            if (recovered > 0) append(" Recovered $recovered file(s).")
            if (removed > 0) append(" Removed $removed empty file(s).")
            if (failed > 0) append(" $failed file(s) need manual inspection.")
        }
}

object RecordingOutputManager {
    private const val RELATIVE_PATH = "Music/DJMRec"
    private const val MIN_RECOVERABLE_WAV_BYTES = 44L
    private const val MIN_RECOVERABLE_FLAC_BYTES = 42L

    fun create(
        context: Context,
        sessionId: String,
        format: RecordingFormat,
        partIndex: Int
    ): PendingRecordingOutput? = runCatching {
        val suffix = if (format == RecordingFormat.WAV) "_part${partIndex.toString().padStart(2, '0')}" else ""
        val displayName = "mix_${sessionId}$suffix.${format.extension}"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType(format))
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
        if (descriptor == null) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return@runCatching null
        }
        PendingRecordingOutput(uri, descriptor, displayName, format, partIndex)
    }.getOrNull()

    fun finalize(context: Context, output: PendingRecordingOutput, durationMillis: Long): Boolean {
        output.descriptor.closeQuietly()
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
            put(MediaStore.Audio.Media.DURATION, durationMillis.coerceAtLeast(0))
            put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }
        return runCatching {
            context.contentResolver.update(output.uri, values, null, null) == 1
        }.getOrDefault(false)
    }

    fun abandon(context: Context, output: PendingRecordingOutput) {
        output.descriptor.closeQuietly()
        runCatching { context.contentResolver.delete(output.uri, null, null) }
    }

    fun freeBytes(): Long = runCatching {
        StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    fun recoverInterrupted(context: Context): RecoverySummary {
        val journal = RecordingSessionStore.read(context)
        val journalUris = journal?.parts.orEmpty().associateBy { it.uri }
        var recovered = 0
        var removed = 0
        var failed = 0
        val pending = runCatching { pendingOutputs(context) }
            .getOrElse { return RecoverySummary(0, 0, 1) }
        pending.forEach { pendingOutput ->
            val journalPart = journalUris[pendingOutput.first.toString()]
            val format = journalPart?.format?.let { runCatching { RecordingFormat.valueOf(it) }.getOrNull() }
                ?: formatFromName(pendingOutput.second)
            val result = recoverOne(context, pendingOutput.first, pendingOutput.second, format)
            when (result) {
                RecoveryResult.RECOVERED -> recovered++
                RecoveryResult.REMOVED -> removed++
                RecoveryResult.FAILED -> failed++
            }
        }
        if (failed == 0) RecordingSessionStore.complete(context)
        return RecoverySummary(recovered, removed, failed)
    }

    internal fun wavHeaderSizes(fileSize: Long): Pair<Int, Int>? {
        if (fileSize < MIN_RECOVERABLE_WAV_BYTES || fileSize - 8 > UInt.MAX_VALUE.toLong()) return null
        return (fileSize - 8).toInt() to (fileSize - 44).coerceAtLeast(0).toInt()
    }

    private fun recoverOne(
        context: Context,
        uri: Uri,
        displayName: String,
        format: RecordingFormat?
    ): RecoveryResult = runCatching {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: return RecoveryResult.FAILED
        val size = descriptor.statSize.takeIf { it >= 0 }
            ?: runCatching { Os.fstat(descriptor.fileDescriptor).st_size }.getOrDefault(-1)
        if (format == null || size < minimumRecoverableBytes(format)) {
            descriptor.close()
            context.contentResolver.delete(uri, null, null)
            return RecoveryResult.REMOVED
        }
        if (!hasExpectedSignature(descriptor, format)) {
            descriptor.closeQuietly()
            return RecoveryResult.FAILED
        }
        if (format == RecordingFormat.WAV && !WavRecovery.patch(descriptor, size)) {
            descriptor.close()
            return RecoveryResult.FAILED
        }
        descriptor.closeQuietly()
        val recoveredName = displayName.substringBeforeLast('.') + "_recovered." + format.extension
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, recoveredName)
            put(MediaStore.Audio.Media.IS_PENDING, 0)
            put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }
        if (context.contentResolver.update(uri, values, null, null) == 1) {
            RecoveryResult.RECOVERED
        } else {
            RecoveryResult.FAILED
        }
    }.getOrDefault(RecoveryResult.FAILED)

    private fun pendingOutputs(context: Context): List<Pair<Uri, String>> {
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Audio.Media.IS_PENDING}=1 AND " +
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val result = mutableListOf<Pair<Uri, String>>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf("$RELATIVE_PATH%"),
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                result += ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn)
                ) to cursor.getString(nameColumn)
            }
        }
        return result
    }

    private fun minimumRecoverableBytes(format: RecordingFormat): Long = when (format) {
        RecordingFormat.WAV -> MIN_RECOVERABLE_WAV_BYTES
        RecordingFormat.FLAC -> MIN_RECOVERABLE_FLAC_BYTES
    }

    private fun formatFromName(name: String): RecordingFormat? =
        RecordingFormat.entries.firstOrNull { name.endsWith(".${it.extension}", ignoreCase = true) }

    private fun hasExpectedSignature(
        descriptor: ParcelFileDescriptor,
        format: RecordingFormat
    ): Boolean = runCatching {
        val bytes = ByteArray(if (format == RecordingFormat.WAV) 12 else 4)
        val read = Os.pread(descriptor.fileDescriptor, bytes, 0, bytes.size, 0)
        if (read != bytes.size) return@runCatching false
        when (format) {
            RecordingFormat.WAV ->
                bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                    bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
            RecordingFormat.FLAC -> bytes.contentEquals("fLaC".toByteArray())
        }
    }.getOrDefault(false)

    private fun mimeType(format: RecordingFormat): String = when (format) {
        RecordingFormat.WAV -> "audio/wav"
        RecordingFormat.FLAC -> "audio/flac"
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        runCatching { close() }
    }

    private enum class RecoveryResult { RECOVERED, REMOVED, FAILED }
}
