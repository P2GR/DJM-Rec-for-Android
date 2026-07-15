#include "WavWriter.h"

#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <limits>

#define TAG "WavWriter"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace djmrec {

namespace {
// Android's arm64-v8a ABI is little-endian, so we can write multi-byte fields directly.
void writeU32(FILE* f, uint32_t v) { fwrite(&v, sizeof(v), 1, f); }
void writeU16(FILE* f, uint16_t v) { fwrite(&v, sizeof(v), 1, f); }
}

bool WavWriter::open(const std::string& path, const AudioFormatInfo& format) {
    if (format.sampleRate <= 0 || format.channelCount <= 0 ||
        (format.bitsPerSample != 16 && format.bitsPerSample != 24 && format.bitsPerSample != 32)) {
        LOGE("Invalid WAV format: %dHz / %d-bit / %dch", format.sampleRate,
             format.bitsPerSample, format.channelCount);
        return false;
    }
    mFormat = format;
    mBytesPerSample = format.bitsPerSample / 8;
    mDataBytesWritten = 0;

    mFile = fopen(path.c_str(), "wb");
    if (!mFile) {
        LOGE("Failed to open %s for writing", path.c_str());
        return false;
    }

    writeHeaderPlaceholder();
    if (ferror(mFile)) {
        LOGE("Failed to write WAV header for %s", path.c_str());
        fclose(mFile);
        mFile = nullptr;
        return false;
    }
    LOGI("Opened WAV %s @ %dHz / %d-bit / %dch", path.c_str(), mFormat.sampleRate,
         mFormat.bitsPerSample, mFormat.channelCount);
    return true;
}

void WavWriter::writeHeaderPlaceholder() {
    const uint32_t byteRate = mFormat.sampleRate * mFormat.channelCount * mBytesPerSample;
    const uint16_t blockAlign = static_cast<uint16_t>(mFormat.channelCount * mBytesPerSample);

    fwrite("RIFF", 1, 4, mFile);
    writeU32(mFile, 0); // placeholder: total size - 8, patched on close()
    fwrite("WAVE", 1, 4, mFile);

    fwrite("fmt ", 1, 4, mFile);
    writeU32(mFile, 16); // PCM fmt chunk size
    writeU16(mFile, 1);  // 1 = PCM, uncompressed
    writeU16(mFile, static_cast<uint16_t>(mFormat.channelCount));
    writeU32(mFile, static_cast<uint32_t>(mFormat.sampleRate));
    writeU32(mFile, byteRate);
    writeU16(mFile, blockAlign);
    writeU16(mFile, static_cast<uint16_t>(mFormat.bitsPerSample));

    fwrite("data", 1, 4, mFile);
    writeU32(mFile, 0); // placeholder: data chunk size, patched on close()
}

bool WavWriter::writeFrames(const int32_t* interleaved, size_t frameCount) {
    if (!mFile) return false;
    const size_t sampleCount = frameCount * mFormat.channelCount;
    const uint64_t bytesRequested = sampleCount * static_cast<uint64_t>(mBytesPerSample);
    if (mDataBytesWritten + bytesRequested > std::numeric_limits<uint32_t>::max()) {
        LOGE("WAV reached the 4 GiB RIFF limit; finalizing current file");
        return false;
    }

    // Repack each full-scale int32 sample down to the container width the source hardware
    // actually negotiated (16/24/32-bit) so the file reflects true captured resolution rather
    // than always ballooning to 32-bit.
    if (mFormat.bitsPerSample == 32) {
        // Fast path: already the correct width, write straight through.
        const size_t written = fwrite(interleaved, sizeof(int32_t), sampleCount, mFile);
        mDataBytesWritten += written * sizeof(int32_t);
        return written == sampleCount;
    }

    if (mFormat.bitsPerSample == 24) {
        static thread_local uint8_t scratch[4096 * 3];
        size_t remaining = sampleCount;
        const int32_t* src = interleaved;
        while (remaining > 0) {
            const size_t chunk = std::min(remaining, sizeof(scratch) / 3);
            for (size_t i = 0; i < chunk; ++i) {
                const int32_t sample = src[i] >> 8; // top 24 bits of the full-scale int32
                scratch[i * 3 + 0] = static_cast<uint8_t>(sample & 0xFF);
                scratch[i * 3 + 1] = static_cast<uint8_t>((sample >> 8) & 0xFF);
                scratch[i * 3 + 2] = static_cast<uint8_t>((sample >> 16) & 0xFF);
            }
            const size_t bytesToWrite = chunk * 3;
            const size_t written = fwrite(scratch, 1, bytesToWrite, mFile);
            mDataBytesWritten += written;
            if (written != bytesToWrite) return false;
            src += chunk;
            remaining -= chunk;
        }
        return true;
    }

    // 16-bit path.
    static thread_local int16_t scratch16[4096];
    size_t remaining = sampleCount;
    const int32_t* src = interleaved;
    while (remaining > 0) {
        const size_t chunk = std::min(remaining, sizeof(scratch16) / sizeof(int16_t));
        for (size_t i = 0; i < chunk; ++i) {
            scratch16[i] = static_cast<int16_t>(src[i] >> 16);
        }
        const size_t bytesToWrite = chunk * sizeof(int16_t);
        const size_t written = fwrite(scratch16, 1, bytesToWrite, mFile);
        mDataBytesWritten += written;
        if (written != bytesToWrite) return false;
        src += chunk;
        remaining -= chunk;
    }
    return true;
}

bool WavWriter::patchHeaderSizes() {
    const uint32_t riffSize = static_cast<uint32_t>(36 + mDataBytesWritten);
    const uint32_t dataSize = static_cast<uint32_t>(mDataBytesWritten);

    if (fseek(mFile, 4, SEEK_SET) != 0) return false;
    writeU32(mFile, riffSize);

    if (fseek(mFile, 40, SEEK_SET) != 0) return false;
    writeU32(mFile, dataSize);
    return fflush(mFile) == 0 && !ferror(mFile);
}

bool WavWriter::close() {
    if (!mFile) return true;
    const bool headerOk = patchHeaderSizes();
    const bool closeOk = fclose(mFile) == 0;
    const bool ok = headerOk && closeOk;
    mFile = nullptr;
    LOGI("Closed WAV, %llu bytes of audio data (finalized=%d)",
         static_cast<unsigned long long>(mDataBytesWritten), ok);
    return ok;
}

} // namespace djmrec
