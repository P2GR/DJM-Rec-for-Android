package com.audiopro.djmrec.editor

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteOrder

/** Decoded audio as interleaved doubles (full scale = 1.0). */
internal interface PcmDecoder : Closeable {
    val sampleRate: Int
    val channels: Int
    /** Source resolution (16/24/32), used to pick the export bit depth. */
    val bitsPerSample: Int

    /** Reads up to [maxFrames] frames into [destination]; returns frames read, 0 at the end. */
    fun read(destination: DoubleArray, maxFrames: Int): Int
}

internal object PcmDecoders {
    fun open(context: Context, uri: Uri, extension: String): PcmDecoder {
        if (!extension.equals("wav", ignoreCase = true)) {
            return MediaCodecPcmDecoder(context, uri, if (extension.equals("mp3", ignoreCase = true)) 16 else 24)
        }
        val descriptor: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open recording")
        return try {
            WavPcmDecoder(BufferedInputStream(FileInputStream(descriptor.fileDescriptor), 1 shl 16)) {
                runCatching { descriptor.close() }
            }
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
    }
}

/** Reads PCM WAV (integer 8-32 bit, float 32, extensible), including unfinished headers. */
internal class WavPcmDecoder(
    private val input: InputStream,
    private val onClose: () -> Unit = {}
) : PcmDecoder {
    private val header: WavHeader = try {
        parseHeader()
    } catch (error: Throwable) {
        close()
        throw error
    }
    override val sampleRate: Int = header.sampleRate
    override val channels: Int = header.channels
    override val bitsPerSample: Int = header.bits
    private val isFloat: Boolean = header.format == 3
    private val bytesPerSample: Int = header.bits / 8
    private var remainingBytes: Long = header.dataBytes
    private var bytes = ByteArray(0)

    private class WavHeader(val format: Int, val channels: Int, val sampleRate: Int, val bits: Int, val dataBytes: Long)

    private fun parseHeader(): WavHeader {
        val riff = readTag()
        readUInt32()
        val wave = readTag()
        if (riff != "RIFF" || wave != "WAVE") throw IOException("Not a WAV file")
        var format = -1
        var channelCount = 0
        var rate = 0
        var bits = 0
        while (true) {
            val id = readTag()
            val size = readUInt32()
            when (id) {
                "fmt " -> {
                    val chunk = readBytes(size.toInt())
                    format = u16(chunk, 0)
                    channelCount = u16(chunk, 2)
                    rate = u32(chunk, 4).toInt()
                    bits = u16(chunk, 14)
                    if (format == 0xFFFE && chunk.size >= 26) format = u16(chunk, 24)
                    if (size % 2 == 1L) input.skipFully(1)
                }
                "data" -> {
                    if ((format != 1 && format != 3) || channelCount <= 0 || rate <= 0 || bits !in listOf(8, 16, 24, 32)) {
                        throw IOException("Unsupported WAV encoding (format $format, $bits-bit)")
                    }
                    // An unfinished header (0 or 0xFFFFFFFF) means "read to the end of the file".
                    val dataBytes = if (size == 0L || size == 0xFFFFFFFFL) Long.MAX_VALUE else size
                    return WavHeader(format, channelCount, rate, bits, dataBytes)
                }
                else -> input.skipFully(size + (size % 2))
            }
        }
    }

    override fun read(destination: DoubleArray, maxFrames: Int): Int {
        val frameBytes = bytesPerSample * channels
        val wanted = minOf(maxFrames.toLong() * frameBytes, remainingBytes).toInt() / frameBytes * frameBytes
        if (wanted <= 0) return 0
        if (bytes.size < wanted) bytes = ByteArray(wanted)
        var filled = 0
        while (filled < wanted) {
            val read = input.read(bytes, filled, wanted - filled)
            if (read < 0) break
            filled += read
        }
        val frames = filled / frameBytes
        if (remainingBytes != Long.MAX_VALUE) remainingBytes -= filled
        if (filled < wanted) remainingBytes = 0
        for (i in 0 until frames * channels) destination[i] = sample(i * bytesPerSample)
        return frames
    }

    private fun sample(offset: Int): Double = when {
        isFloat -> Float.fromBits(u32(bytes, offset).toInt()).toDouble()
        bytesPerSample == 1 -> ((bytes[offset].toInt() and 0xFF) - 128) / 128.0
        bytesPerSample == 2 -> ((bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)).toShort() / 32768.0
        bytesPerSample == 3 -> ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() shl 16)) / 8388608.0
        else -> u32(bytes, offset).toInt() / 2147483648.0
    }

    private fun readBytes(count: Int): ByteArray {
        val result = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = input.read(result, filled, count - filled)
            if (read < 0) throw IOException("Truncated WAV header")
            filled += read
        }
        return result
    }

    private fun readTag(): String = String(readBytes(4), Charsets.US_ASCII)
    private fun readUInt32(): Long = u32(readBytes(4), 0)

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) { if (read() < 0) throw IOException("Truncated WAV"); left-- } else left -= skipped
        }
    }

    override fun close() {
        runCatching { input.close() }
        onClose()
    }

    private companion object {
        fun u16(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        fun u32(data: ByteArray, offset: Int): Long = (u16(data, offset).toLong()) or (u16(data, offset + 2).toLong() shl 16)
    }
}

/** FLAC/MP3 through Android's decoders, asking for float output to keep 24-bit detail. */
internal class MediaCodecPcmDecoder(context: Context, uri: Uri, override val bitsPerSample: Int) : PcmDecoder {
    private val extractor = MediaExtractor()
    override var sampleRate: Int = 0
        private set
    override var channels: Int = 0
        private set
    private val codec: MediaCodec = try {
        startDecoder(context, uri)
    } catch (error: Throwable) {
        runCatching { extractor.release() }
        throw error
    }
    private var floatOutput = false
    private var inputDone = false
    private var outputDone = false
    private var pending = DoubleArray(0)
    private var pendingStart = 0
    private var pendingEnd = 0
    private val info = MediaCodec.BufferInfo()

    private fun startDecoder(context: Context, uri: Uri): MediaCodec {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        // Float keeps 24-bit FLAC detail; decoders that ignore it report 16-bit output instead.
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Unknown audio type"))
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
        } catch (error: Throwable) {
            decoder.release()
            throw error
        }
        return decoder
    }

    override fun read(destination: DoubleArray, maxFrames: Int): Int {
        while (pendingEnd - pendingStart < channels && !outputDone) decodeStep()
        val available = (pendingEnd - pendingStart) / channels
        val frames = minOf(available, maxFrames)
        if (frames <= 0) return 0
        System.arraycopy(pending, pendingStart, destination, 0, frames * channels)
        pendingStart += frames * channels
        return frames
    }

    private fun decodeStep() {
        if (!inputDone) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = codec.getInputBuffer(inputIndex)!!
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val format = codec.outputFormat
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatOutput = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            }
            outputIndex >= 0 -> {
                val buffer = codec.getOutputBuffer(outputIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val samples = if (floatOutput) info.size / 4 else info.size / 2
                compactPending(samples)
                if (floatOutput) {
                    val floats = buffer.asFloatBuffer()
                    for (i in 0 until samples) pending[pendingEnd++] = floats.get(i).toDouble()
                } else {
                    val shorts = buffer.asShortBuffer()
                    for (i in 0 until samples) pending[pendingEnd++] = shorts.get(i) / 32768.0
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
            }
        }
    }

    /** Drops consumed samples and makes room for [extra] more. */
    private fun compactPending(extra: Int) {
        val left = pendingEnd - pendingStart
        if (pendingStart > 0) {
            System.arraycopy(pending, pendingStart, pending, 0, left)
            pendingStart = 0
            pendingEnd = left
        }
        if (pending.size < left + extra) pending = pending.copyOf(maxOf(left + extra, pending.size * 2))
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
