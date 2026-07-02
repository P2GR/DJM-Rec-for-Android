#pragma once

#include <FLAC/stream_encoder.h>
#include "AudioWriter.h"

namespace djmrec {

/**
 * Lossless FLAC encoder backed by libFLAC's streaming encoder. libFLAC handles the file I/O
 * internally (via FLAC__stream_encoder_init_file), so this class is mostly parameter setup
 * plus sample-format conversion (our canonical int32 -> the bit depth FLAC expects).
 */
class FlacWriter final : public AudioWriter {
public:
    ~FlacWriter() override { if (mEncoder) close(); }

    bool open(const std::string& path, const AudioFormatInfo& format) override;
    bool writeFrames(const int32_t* interleaved, size_t frameCount) override;
    bool close() override;

private:
    FLAC__StreamEncoder* mEncoder = nullptr;
    AudioFormatInfo mFormat;
    int mShiftAmount = 8; // 32 - bitsPerSample, converts our full-scale int32 to FLAC's scale
};

} // namespace djmrec
