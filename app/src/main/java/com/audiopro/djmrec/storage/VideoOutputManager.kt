package com.audiopro.djmrec.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

data class PendingVideoOutput(
    val uri: Uri,
    val descriptor: ParcelFileDescriptor,
    val displayName: String
)

/**
 * MP4 video segments in Movies/DJMRec. Each segment stays pending (hidden from other apps)
 * until its MP4 index is written, then it is published.
 */
object VideoOutputManager {
    private const val RELATIVE_PATH = "Movies/DJMRec"

    fun create(context: Context, sessionId: String, segmentIndex: Int): PendingVideoOutput? = runCatching {
        val displayName = displayName(sessionId, segmentIndex)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
        if (descriptor == null) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return@runCatching null
        }
        PendingVideoOutput(uri, descriptor, displayName)
    }.getOrNull()

    fun finalize(context: Context, output: PendingVideoOutput, durationMillis: Long): Boolean {
        runCatching { output.descriptor.close() }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
            put(MediaStore.Video.Media.DURATION, durationMillis.coerceAtLeast(0))
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }
        return runCatching {
            context.contentResolver.update(output.uri, values, null, null) == 1
        }.getOrDefault(false)
    }

    fun abandon(context: Context, output: PendingVideoOutput) {
        runCatching { output.descriptor.close() }
        runCatching { context.contentResolver.delete(output.uri, null, null) }
    }

    /**
     * Removes segments a crash left pending. MediaMuxer writes the MP4 index only when a file
     * is closed, so such a segment cannot be played; the published segments before it are safe.
     */
    fun removeInterrupted(context: Context): Int = runCatching {
        val pending = mutableListOf<Uri>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.IS_PENDING}=1 AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_PATH%"),
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (cursor.moveToNext()) {
                pending += ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn))
            }
        }
        pending.count { uri -> runCatching { context.contentResolver.delete(uri, null, null) == 1 }.getOrDefault(false) }
    }.getOrDefault(0)

    internal fun displayName(sessionId: String, segmentIndex: Int): String =
        "video_${sessionId}_part${segmentIndex.toString().padStart(2, '0')}.mp4"
}
