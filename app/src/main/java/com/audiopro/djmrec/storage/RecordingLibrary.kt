package com.audiopro.djmrec.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.os.Environment
import android.media.MediaMetadataRetriever
import androidx.core.content.FileProvider
import java.io.File

data class LibraryRecording(val uri: Uri, val displayName: String, val size: Long, val duration: Long, val modified: Long, val legacyFile: File? = null) {
    val extension get() = displayName.substringAfterLast('.').lowercase()
    val title get() = displayName.substringBeforeLast('.')
    val mimeType get() = if (extension == "flac") "audio/flac" else "audio/wav"
}

/** Uses published MediaStore rows; never lists, renames or deletes an active capture. */
object RecordingLibrary {
    fun list(context: Context): List<LibraryRecording> {
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.IS_PENDING)
        val indexedNames = mutableSetOf<String>()
        return buildList {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
                "${MediaStore.Audio.Media.RELATIVE_PATH}=?",
                arrayOf("Music/DJMRec/"), "${MediaStore.Audio.Media.DATE_MODIFIED} DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    indexedNames += name
                    if (cursor.getInt(5) != 0) continue
                    if (name.substringAfterLast('.').lowercase() !in listOf("wav", "flac")) continue
                    add(LibraryRecording(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0)), name, cursor.getLong(2), cursor.getLong(3), cursor.getLong(4) * 1000))
                }
            }
            // Older versions wrote direct files. Keep them accessible without moving or
            // deleting originals; exclude indexed rows, including every pending capture.
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "DJMRec")
            val privateDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { File(it, "DJMRec") }
            listOfNotNull(publicDir, privateDir).forEach { dir ->
                dir.listFiles().orEmpty().filter { file ->
                    file.isFile && file.extension.lowercase() in listOf("wav", "flac") &&
                        (dir != publicDir || file.name !in indexedNames)
                }.forEach { file ->
                    val duration = runCatching {
                        val metadata = MediaMetadataRetriever()
                        try {
                            metadata.setDataSource(file.absolutePath)
                            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } finally { metadata.release() }
                    }.getOrDefault(0L)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    add(LibraryRecording(uri, file.name, file.length(), duration, file.lastModified(), file))
                }
            }
        }.sortedByDescending { it.modified }
    }

    fun rename(context: Context, recording: LibraryRecording, input: String) {
        val name = RecordingNamePolicy.displayName(input, recording.extension)
        require(list(context).none { it.uri != recording.uri && it.displayName.equals(name, true) }) { "A set with that name already exists" }
        recording.legacyFile?.let { source ->
            val target = File(source.parentFile, name)
            if (source.name == name) return
            check(!target.exists() && source.renameTo(target)) { "Could not rename this set" }
            return
        }
        check(context.contentResolver.update(recording.uri, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
        }, "${MediaStore.Audio.Media.IS_PENDING}=0", null) == 1) { "Could not rename this set" }
    }

    fun delete(context: Context, recording: LibraryRecording) {
        recording.legacyFile?.let { file ->
            check(file.delete()) { "Could not delete this set" }
            return
        }
        check(context.contentResolver.delete(recording.uri, "${MediaStore.Audio.Media.IS_PENDING}=0", null) == 1) {
            "Could not delete this set"
        }
    }

    fun export(context: Context, recording: LibraryRecording, destination: Uri) {
        check(destination != recording.uri) { "Choose a different destination" }
        context.contentResolver.openInputStream(recording.uri).use { source ->
            checkNotNull(source) { "Could not open the recording" }
            context.contentResolver.openOutputStream(destination, "wt").use { target ->
                checkNotNull(target) { "Could not open the destination" }
                source.copyTo(target)
            }
        }
    }
}
