package com.audiopro.djmrec.streaming

import android.os.Process
import com.audiopro.djmrec.audio.AudioEngine
import com.pedro.common.TimeUtils
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import kotlin.math.abs
import kotlin.math.log10

/** Supplies RootEncoder with stereo PCM16 copied from native DJM capture, never phone mic. */
class DjmPcmAudioSource(
    private val onFailure: (String) -> Unit,
    private val onPcmStats: (totalBytes: Long, peakDb: Float) -> Unit
) : AudioSource() {

    @Volatile
    private var running = false
    private var worker: Thread? = null
    // RootEncoder 2.7.x configures AAC MediaCodec for 8192-byte PCM input buffers.
    private val inputSize = 8 * 1024
    private val buffers = Array(96) { ByteArray(inputSize) }

    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        return isStereo && sampleRate in 8_000..96_000 && AudioEngine.isStreamOpen()
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        if (running) return
        this.getMicrophoneData = getMicrophoneData
        if (!AudioEngine.startLivePcm()) {
            onFailure("DJM audio stream is not available")
            return
        }
        running = true
        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var bufferIndex = 0
            var totalBytes = 0L
            var intervalPeak = 0
            var nextStatsAt = android.os.SystemClock.elapsedRealtime() + 1_000L
            while (running) {
                val buffer = buffers[bufferIndex]
                val bytesRead = AudioEngine.readLivePcm16(buffer)
                if (bytesRead > 0) {
                    var index = 0
                    while (index + 1 < bytesRead) {
                        val sample = ((buffer[index].toInt() and 0xFF) or
                            (buffer[index + 1].toInt() shl 8)).toShort().toInt()
                        intervalPeak = maxOf(intervalPeak, abs(sample).coerceAtMost(32_767))
                        index += 2
                    }
                    getMicrophoneData.inputPCMData(
                        Frame(buffer, 0, bytesRead, TimeUtils.getCurrentTimeMicro())
                    )
                    totalBytes += bytesRead
                    bufferIndex = (bufferIndex + 1) % buffers.size
                    if (android.os.SystemClock.elapsedRealtime() >= nextStatsAt) {
                        val peakDb = if (intervalPeak == 0) -60f
                        else (20.0 * log10(intervalPeak / 32767.0)).toFloat().coerceIn(-60f, 0f)
                        onPcmStats(totalBytes, peakDb)
                        intervalPeak = 0
                        nextStatsAt = android.os.SystemClock.elapsedRealtime() + 1_000L
                    }
                } else {
                    try {
                        Thread.sleep(2)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }, "DjmLivePcm").apply { start() }
    }

    override fun stop() {
        running = false
        worker?.interrupt()
        runCatching { worker?.join(1_000) }
        worker = null
        AudioEngine.stopLivePcm()
        getMicrophoneData = null
    }

    override fun isRunning(): Boolean = running

    override fun release() {
        if (running) stop()
    }

}
