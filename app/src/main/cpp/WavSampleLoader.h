#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace djmrec {

/**
 * Minimal WAV file parser for loading drum one-shot samples into memory.
 * Supports 16-bit and 24-bit PCM, mono or stereo (stereo is mixed to mono).
 * All samples are resampled to 44100 Hz float mono for the sample player.
 */
class WavSampleLoader {
public:
    struct Sample {
        std::vector<float> data; // 44100 Hz mono float samples
        int sampleRate = 0;
        int originalChannels = 0;
        int originalBitDepth = 0;
        bool valid = false;
    };

    /** Parse a WAV file from a memory buffer. Returns a valid Sample on success. */
    static Sample loadFromMemory(const uint8_t* data, size_t size);

    /** Parse a WAV file from a file path. */
    static Sample loadFromFile(const std::string& path);

private:
    static Sample parseRiff(const uint8_t* data, size_t size);
};

} // namespace djmrec
