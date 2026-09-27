package com.audiopro.djmrec.streaming

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.pedro.common.frame.MediaFrame
import com.pedro.library.base.recording.AsyncBaseRecordController
import com.pedro.library.base.recording.RecordController
import java.io.FileDescriptor
import java.io.IOException

/** Where [SegmentedMp4RecordController] writes each MP4 segment. */
internal interface VideoSegmentSink {
    /** Creates segment [index] (1-based) and returns its open descriptor, or null on failure. */
    fun open(index: Int): FileDescriptor?

    /** Publishes (or, when not [playable], deletes) segment [index]. */
    fun close(index: Int, durationMillis: Long, playable: Boolean)
}

/**
 * MP4 recorder that starts a new file at the first video keyframe after every
 * [segmentDurationUs], so a set is saved as back-to-back segments. MediaMuxer only writes an
 * MP4's index when the file is closed; if the app is killed, only the open segment is lost.
 *
 * Segments switch on a keyframe with the same encoder, so no frames are dropped at the cut.
 * Frames arrive on RootEncoder's muxer coroutine; the sink is called from that coroutine too,
 * except for the final close, which runs on the thread that stops recording.
 */
internal class SegmentedMp4RecordController(
    private val segmentDurationUs: Long,
    private val sink: VideoSegmentSink,
    private val onSegmentStarted: (index: Int) -> Unit
) : AsyncBaseRecordController() {

    private companion object {
        const val TAG = "SegmentedMp4Recorder"
    }

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var segmentIndex = 0
    private var segmentStartUs = -1L
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L
    private var rotationKeyRequested = false
    @Volatile
    private var failed = false

    override fun startRecordImp(path: String, listener: RecordController.Listener?, tracks: RecordController.RecordTracks) {
        // The path is only a label: every segment's file comes from the sink.
        startSegments()
    }

    override fun startRecordImp(fd: FileDescriptor, listener: RecordController.Listener?, tracks: RecordController.RecordTracks) {
        startSegments()
    }

    private fun startSegments() {
        failed = false
        segmentIndex = 0
        openNextSegment()
    }

    @Throws(IOException::class)
    private fun openNextSegment() {
        val index = segmentIndex + 1
        val descriptor = sink.open(index) ?: throw IOException("Could not create video segment $index")
        val next = try {
            MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Exception) {
            sink.close(index, 0, playable = false)
            throw IOException("Could not open MP4 muxer for segment $index", error)
        }
        segmentIndex = index
        muxer = next
        muxerStarted = false
        videoTrack = -1
        audioTrack = -1
        segmentStartUs = -1L
        lastVideoUs = -1L
        lastAudioUs = -1L
        rotationKeyRequested = false
    }

    /** Adds both tracks and starts the muxer at the keyframe [startUs]. */
    private fun startMuxer(startUs: Long): Boolean {
        val active = muxer ?: return false
        val video = videoFormat ?: return false
        val audio = audioFormat ?: return false
        return try {
            videoTrack = active.addTrack(video)
            audioTrack = active.addTrack(audio)
            active.start()
            muxerStarted = true
            segmentStartUs = startUs
            onSegmentStarted(segmentIndex)
            true
        } catch (error: Exception) {
            fail(IOException("Could not start MP4 segment $segmentIndex", error))
            false
        }
    }

    /** Closes the current segment; it is only kept when it holds video. */
    private fun finishSegment() {
        val active = muxer ?: return
        muxer = null
        val playable = muxerStarted && lastVideoUs >= 0
        runCatching { if (muxerStarted) active.stop() }.onFailure { Log.e(TAG, "MP4 finalize failed", it) }
        runCatching { active.release() }
        val durationMillis = if (playable) (lastVideoUs - segmentStartUs).coerceAtLeast(0) / 1000 else 0
        sink.close(segmentIndex, durationMillis, playable)
        muxerStarted = false
    }

    private fun rotate(keyframeUs: Long) {
        finishSegment()
        try {
            openNextSegment()
        } catch (error: IOException) {
            fail(error)
            return
        }
        startMuxer(keyframeUs)
    }

    private fun fail(error: Exception) {
        if (failed) return
        failed = true
        Log.e(TAG, "Video recording failed", error)
        // The owner stops recording from its own thread; stopping from this coroutine would deadlock.
        listener?.onError(error)
    }

    override suspend fun onWriteFrame(frame: MediaFrame) {
        if (failed) return
        when (frame.type) {
            MediaFrame.Type.VIDEO -> {
                val keyframe = frame.info.isKeyFrame ||
                    (frame.info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || isKeyFrame(frame.data)
                val timestamp = frame.info.timestamp
                when (recordStatus) {
                    RecordController.Status.STARTED -> {
                        if (keyframe && startMuxer(timestamp)) {
                            recordStatus = RecordController.Status.RECORDING
                            listener?.onStatusChange(recordStatus)
                        } else if (!keyframe) {
                            myRequestKeyFrame?.onRequestKeyFrame()
                        }
                    }
                    RecordController.Status.RESUMED -> if (keyframe) {
                        recordStatus = RecordController.Status.RECORDING
                        listener?.onStatusChange(recordStatus)
                    }
                    else -> Unit
                }
                if (recordStatus != RecordController.Status.RECORDING || !muxerStarted) return
                val segmentFull = timestamp - segmentStartUs >= segmentDurationUs
                if (segmentFull && keyframe) {
                    rotate(timestamp)
                    if (failed || !muxerStarted) return
                } else if (segmentFull && !rotationKeyRequested) {
                    rotationKeyRequested = true
                    myRequestKeyFrame?.onRequestKeyFrame()
                }
                if (timestamp > lastVideoUs) {
                    write(videoTrack, frame, timestamp)
                    lastVideoUs = timestamp
                }
            }
            MediaFrame.Type.AUDIO -> {
                if (recordStatus != RecordController.Status.RECORDING || !muxerStarted) return
                val timestamp = frame.info.timestamp
                // Audio encoded before this segment's first keyframe already went to the previous one.
                if (timestamp < segmentStartUs || timestamp <= lastAudioUs) return
                write(audioTrack, frame, timestamp)
                lastAudioUs = timestamp
            }
        }
    }

    private fun write(track: Int, frame: MediaFrame, timestampUs: Long) {
        val active = muxer ?: return
        if (track < 0) return
        val info = MediaCodec.BufferInfo().apply {
            set(frame.info.offset, frame.info.size, timestampUs - segmentStartUs, frame.info.flags)
        }
        try {
            active.writeSampleData(track, frame.data, info)
        } catch (error: Exception) {
            fail(IOException("Could not write to MP4 segment $segmentIndex", error))
        }
    }

    override fun stopRecordImp() {
        finishSegment()
        segmentIndex = 0
        rotationKeyRequested = false
    }

    override fun setVideoFormat(videoFormat: MediaFormat) {
        this.videoFormat = videoFormat
    }

    override fun setAudioFormat(audioFormat: MediaFormat) {
        this.audioFormat = audioFormat
    }

    override fun resetFormats() {
        videoFormat = null
        audioFormat = null
    }
}
