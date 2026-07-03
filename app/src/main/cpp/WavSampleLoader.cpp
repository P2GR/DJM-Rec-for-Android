#include "WavSampleLoader.h"

#include <android/log.h>
#include <cstring>

#define TAG "WavSampleLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace djmrec {

namespace {

struct RiffHeader {
    char chunkId[4];      // "RIFF"
    uint32_t chunkSize;
    char format[4];       // "WAVE"
};

struct ChunkHeader {
    char id[4];
    uint32_t size;
};

struct FmtChunk {
    uint16_t audioFormat;   // 1 = PCM
    uint16_t numChannels;
    uint32_t sampleRate;
    uint32_t byteRate;
    uint16_t blockAlign;
    uint16_t bitsPerSample;
};

bool readU32(const uint8_t*& p, const uint8_t* end, uint32_t& out) {
    if (p + 4 > end) return false;
    std::memcpy(&out, p, 4);
    p += 4;
    return true;
}

bool readU16(const uint8_t*& p, const uint8_t* end, uint16_t& out) {
    if (p + 2 > end) return false;
    std::memcpy(&out, p, 2);
    p += 2;
    return true;
}

} // namespace

WavSampleLoader::Sample WavSampleLoader::loadFromMemory(const uint8_t* data, size_t size) {
    if (!data || size < 44) {
        LOGE("Invalid WAV data: null or too small (%zu bytes)", size);
        return {};
    }
    return parseRiff(data, size);
}

WavSampleLoader::Sample WavSampleLoader::loadFromFile(const std::string& path) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) {
        LOGE("Cannot open WAV file: %s", path.c_str());
        return {};
    }
    fseek(f, 0, SEEK_END);
    const long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz <= 0 || sz > 16 * 1024 * 1024) { // 16 MB sanity limit
        fclose(f);
        return {};
    }
    std::vector<uint8_t> buf(static_cast<size_t>(sz));
    fread(buf.data(), 1, buf.size(), f);
    fclose(f);
    return parseRiff(buf.data(), buf.size());
}

WavSampleLoader::Sample WavSampleLoader::parseRiff(const uint8_t* data, size_t size) {
    const uint8_t* p = data;
    const uint8_t* end = data + size;

    RiffHeader riff;
    if (end - p < static_cast<ptrdiff_t>(sizeof(RiffHeader))) return {};
    std::memcpy(&riff, p, sizeof(RiffHeader));
    p += sizeof(RiffHeader);

    if (std::strncmp(riff.chunkId, "RIFF", 4) != 0 ||
        std::strncmp(riff.format, "WAVE", 4) != 0) {
        LOGE("Not a valid WAV file");
        return {};
    }

    FmtChunk fmt{};
    bool haveFmt = false;
    const uint8_t* dataStart = nullptr;
    uint32_t dataSize = 0;

    while (p + 8 <= end) {
        ChunkHeader ch;
        std::memcpy(&ch, p, sizeof(ChunkHeader));
        p += sizeof(ChunkHeader);

        if (std::strncmp(ch.id, "fmt ", 4) == 0) {
            if (p + sizeof(FmtChunk) > end) return {};
            std::memcpy(&fmt, p, std::min(static_cast<size_t>(ch.size), sizeof(FmtChunk)));
            p += ch.size;
            haveFmt = true;
        } else if (std::strncmp(ch.id, "data", 4) == 0) {
            dataStart = p;
            dataSize = ch.size;
            p += ch.size;
        } else {
            p += ch.size; // skip unknown chunks
        }
    }

    if (!haveFmt || !dataStart || dataSize == 0) {
        LOGE("WAV missing fmt or data chunk");
        return {};
    }

    if (fmt.audioFormat != 1) {
        LOGE("Unsupported WAV format: %d (only PCM=1 supported)", fmt.audioFormat);
        return {};
    }

    Sample sample;
    sample.sampleRate = static_cast<int>(fmt.sampleRate);
    sample.originalChannels = fmt.numChannels;
    sample.originalBitDepth = fmt.bitsPerSample;

    const size_t bytesPerSample = fmt.bitsPerSample / 8;
    const size_t totalFrames = dataSize / (fmt.numChannels * bytesPerSample);

    // Resample to 44100 Hz mono float.
    const double resampleRatio = static_cast<double>(fmt.sampleRate) / 44100.0;
    const size_t outFrames = static_cast<size_t>(totalFrames / resampleRatio);
    sample.data.reserve(outFrames);

    double accumPos = 0.0;
    for (size_t i = 0; i < outFrames; ++i) {
        const size_t srcFrame = static_cast<size_t>(accumPos);
        if (srcFrame >= totalFrames) break;
        accumPos += resampleRatio;

        const uint8_t* framePtr = dataStart + srcFrame * fmt.numChannels * bytesPerSample;

        // Mix channels to mono.
        float monoSum = 0.0f;
        for (int ch = 0; ch < fmt.numChannels; ++ch) {
            const uint8_t* samplePtr = framePtr + ch * bytesPerSample;
            float val = 0.0f;
            if (fmt.bitsPerSample == 16) {
                int16_t s;
                std::memcpy(&s, samplePtr, 2);
                val = static_cast<float>(s) / 32768.0f;
            } else if (fmt.bitsPerSample == 24) {
                int32_t s = samplePtr[0] | (samplePtr[1] << 8) | (samplePtr[2] << 16);
                if (s & 0x800000) s |= 0xFF000000; // sign-extend
                val = static_cast<float>(s) / 8388608.0f;
            } else if (fmt.bitsPerSample == 32) {
                int32_t s;
                std::memcpy(&s, samplePtr, 4);
                val = static_cast<float>(s) / 2147483648.0f;
            }
            monoSum += val;
        }
        sample.data.push_back(monoSum / static_cast<float>(fmt.numChannels));
    }

    sample.valid = !sample.data.empty();
    LOGI("Loaded WAV: %dHz/%dbit/%dch → 44100Hz mono float, %zu samples",
         sample.sampleRate, sample.originalBitDepth, sample.originalChannels, sample.data.size());

    return sample;
}

} // namespace djmrec
