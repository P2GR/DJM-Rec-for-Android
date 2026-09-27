package com.audiopro.djmrec.editor

import android.content.Context
import android.net.Uri
import android.util.Log
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.storage.LibraryRecording
import com.audiopro.djmrec.storage.RecordingOutputManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** Result of one decoding pass over a recording, reused for every edit of it. */
class EditorAnalysis internal constructor(
    val recording: LibraryRecording,
    val sampleRate: Int,
    /** Channels in the source; the editor always writes stereo. */
    val sourceChannels: Int,
    val bitsPerSample: Int,
    val totalFrames: Long,
    /** Peak level (0-1) of every 100 ms hop, for the overview waveform. */
    val hopPeaks: FloatArray,
    private val meter: LoudnessMeter
) {
    val durationMillis: Long get() = totalFrames * 1000 / sampleRate

    val samplePeak: Double get() = meter.samplePeak

    /** Integrated loudness of the kept range, or null if it is silent. */
    fun loudnessLufs(startMillis: Long, endMillis: Long): Double? =
        meter.integratedLufs(
            (startMillis / LoudnessMeter.HOP_MILLIS).toInt(),
            (endMillis / LoudnessMeter.HOP_MILLIS).toInt()
        )
}

sealed interface EditorState {
    data object Idle : EditorState
    data class Analyzing(val recording: LibraryRecording, val progress: Float) : EditorState
    data class Ready(val analysis: EditorAnalysis) : EditorState
    data class Exporting(val analysis: EditorAnalysis, val progress: Float) : EditorState
    data class Exported(val analysis: EditorAnalysis, val uri: Uri, val displayName: String) : EditorState
    data class Failed(val message: String, val analysis: EditorAnalysis?) : EditorState
}

/**
 * Post-set editor session: one analysis pass (waveform overview, loudness, exact length), then
 * an export that trims, fades, normalizes loudness and writes a new MP3/WAV/FLAC next to the
 * original, which is never modified. Work runs on a process scope, so leaving the screen does
 * not cancel an export.
 */
object SetEditor {
    private const val TAG = "SetEditor"
    private const val BLOCK_FRAMES = 4096

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<EditorState>(EditorState.Idle)
    val state: StateFlow<EditorState> = _state.asStateFlow()
    private var job: Job? = null

    fun open(context: Context, recording: LibraryRecording) {
        val appContext = context.applicationContext
        job?.cancel()
        _state.value = EditorState.Analyzing(recording, 0f)
        job = scope.launch {
            runCatching { analyze(appContext, recording) }
                .onSuccess { _state.value = EditorState.Ready(it) }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    Log.e(TAG, "Analysis failed", error)
                    _state.value = EditorState.Failed("Could not read this set: ${error.message ?: "unsupported file"}", null)
                }
        }
    }

    fun export(context: Context, analysis: EditorAnalysis, settings: EditSettings) {
        val appContext = context.applicationContext
        job?.cancel()
        _state.value = EditorState.Exporting(analysis, 0f)
        job = scope.launch {
            runCatching { render(appContext, analysis, settings) }
                .onSuccess { (uri, name) -> _state.value = EditorState.Exported(analysis, uri, name) }
                .onFailure { error ->
                    if (error is CancellationException) {
                        _state.value = EditorState.Ready(analysis)
                        return@onFailure
                    }
                    Log.e(TAG, "Export failed", error)
                    _state.value = EditorState.Failed("Export failed: ${error.message ?: "write error"}", analysis)
                }
        }
    }

    /** Back to editing after an export finished or failed. */
    fun backToEditing() {
        when (val current = _state.value) {
            is EditorState.Exported -> _state.value = EditorState.Ready(current.analysis)
            is EditorState.Failed -> _state.value = current.analysis?.let { EditorState.Ready(it) } ?: EditorState.Idle
            else -> Unit
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun close() {
        job?.cancel()
        job = null
        _state.value = EditorState.Idle
    }

    private suspend fun analyze(context: Context, recording: LibraryRecording): EditorAnalysis {
        PcmDecoders.open(context, recording.uri, recording.extension).use { decoder ->
            val rate = decoder.sampleRate
            val meter = LoudnessMeter(rate, 2)
            val hopFrames = max(1, rate / 10)
            var hopPeaks = FloatArray(4096)
            var hops = 0
            var hopPeak = 0.0
            var framesInHop = 0
            val expectedFrames = max(1L, recording.duration * rate / 1000)
            val source = DoubleArray(BLOCK_FRAMES * max(1, decoder.channels))
            val stereo = DoubleArray(BLOCK_FRAMES * 2)
            var total = 0L
            var lastProgress = 0f
            while (true) {
                coroutineContext.ensureActive()
                val frames = decoder.read(source, BLOCK_FRAMES)
                if (frames <= 0) break
                toStereo(source, decoder.channels, stereo, frames)
                meter.process(stereo, frames)
                for (frame in 0 until frames) {
                    hopPeak = max(hopPeak, max(abs(stereo[frame * 2]), abs(stereo[frame * 2 + 1])))
                    if (++framesInHop == hopFrames) {
                        if (hops == hopPeaks.size) hopPeaks = hopPeaks.copyOf(hops * 2)
                        hopPeaks[hops++] = hopPeak.toFloat().coerceAtMost(1f)
                        hopPeak = 0.0
                        framesInHop = 0
                    }
                }
                total += frames
                val progress = (total.toFloat() / expectedFrames).coerceAtMost(0.99f)
                if (progress - lastProgress >= 0.01f) {
                    lastProgress = progress
                    _state.value = EditorState.Analyzing(recording, progress)
                }
            }
            if (total == 0L) throw java.io.IOException("the file holds no audio")
            return EditorAnalysis(
                recording, rate, decoder.channels, decoder.bitsPerSample, total,
                hopPeaks.copyOf(hops), meter
            )
        }
    }

    private suspend fun render(context: Context, analysis: EditorAnalysis, settings: EditSettings): Pair<Uri, String> {
        val recording = analysis.recording
        val keptEnd = minOf(settings.trimEndMillis, analysis.durationMillis)
        val gainDb = EditMath.normalizationGainDb(
            analysis.loudnessLufs(settings.trimStartMillis, keptEnd), settings.targetLufs
        )
        val format = settings.format
        val name = editedName(recording.title, format)
        val output = RecordingOutputManager.createNamed(context, name, format)
            ?: throw java.io.IOException("could not create $name in Music/DJMRec")
        val bits = if (analysis.bitsPerSample >= 24) 24 else 16
        val handle = NativeAudioFileWriter.open(output.descriptor.fd, format.nativeValue, analysis.sampleRate, 2, bits)
        runCatching { output.descriptor.close() } // the native writer holds its own duplicate
        if (handle == 0L) {
            RecordingOutputManager.abandon(context, output)
            throw java.io.IOException("could not open the ${format.name} encoder")
        }
        var handleOpen = true
        try {
            val processor = EditProcessor(analysis.sampleRate, 2, settings, gainDb, analysis.totalFrames)
            val ints = IntArray(BLOCK_FRAMES * 2)
            var writeFailed = false
            val emit: (DoubleArray, Int) -> Unit = { buffer, count ->
                if (!writeFailed) {
                    for (i in 0 until count * 2) ints[i] = toInt32(buffer[i])
                    if (!NativeAudioFileWriter.write(handle, ints, count)) writeFailed = true
                }
            }
            PcmDecoders.open(context, recording.uri, recording.extension).use { decoder ->
                val source = DoubleArray(BLOCK_FRAMES * max(1, decoder.channels))
                val stereo = DoubleArray(BLOCK_FRAMES * 2)
                var position = 0L
                var lastProgress = 0f
                val endFrame = processor.outputFrames + EditMath.millisToFrames(settings.trimStartMillis, analysis.sampleRate)
                while (position < endFrame) {
                    coroutineContext.ensureActive()
                    val frames = decoder.read(source, BLOCK_FRAMES)
                    if (frames <= 0) break
                    toStereo(source, decoder.channels, stereo, frames)
                    processor.process(stereo, frames, emit)
                    if (writeFailed) throw java.io.IOException("storage write failed")
                    position += frames
                    val progress = (position.toFloat() / max(1L, endFrame)).coerceAtMost(0.99f)
                    if (progress - lastProgress >= 0.01f) {
                        lastProgress = progress
                        _state.value = EditorState.Exporting(analysis, progress)
                    }
                }
            }
            processor.finish(emit)
            if (writeFailed) throw java.io.IOException("storage write failed")
            handleOpen = false
            if (!NativeAudioFileWriter.close(handle)) throw java.io.IOException("could not finalize the file")
            val durationMillis = processor.outputFrames * 1000 / analysis.sampleRate
            if (!RecordingOutputManager.finalize(context, output, durationMillis)) {
                throw java.io.IOException("could not publish $name")
            }
            return output.uri to name
        } catch (error: Throwable) {
            if (handleOpen) runCatching { NativeAudioFileWriter.close(handle) }
            RecordingOutputManager.abandon(context, output)
            throw error
        }
    }

    /** Stereo view of any decoder layout: mono is doubled, extra channels are dropped. */
    private fun toStereo(source: DoubleArray, channels: Int, stereo: DoubleArray, frames: Int) {
        for (frame in 0 until frames) {
            val base = frame * channels
            val left = source[base]
            stereo[frame * 2] = left
            stereo[frame * 2 + 1] = if (channels > 1) source[base + 1] else left
        }
    }

    private fun toInt32(sample: Double): Int =
        (sample * 2147483648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    internal fun editedName(title: String, format: RecordingFormat): String {
        val base = title.removeSuffix(" (edit)").take(90)
        return "$base (edit).${format.extension}"
    }
}
