package com.audiopro.djmrec.editor

/**
 * The recorder's native WAV/FLAC/MP3 writers, opened on a MediaStore descriptor for export.
 * Samples are interleaved, left-justified int32 (full scale = Int.MAX_VALUE), like capture.
 * One handle must only be used from one thread at a time.
 */
internal object NativeAudioFileWriter {
    init {
        System.loadLibrary("djmrec_audio")
    }

    /** @return a handle, or 0 on failure. [format] is RecordingFormat.nativeValue. */
    external fun open(fd: Int, format: Int, sampleRate: Int, channelCount: Int, bitsPerSample: Int): Long

    external fun write(handle: Long, interleaved: IntArray, frameCount: Int): Boolean

    /** Finalizes the file and frees the handle; it must not be used afterwards. */
    external fun close(handle: Long): Boolean
}
