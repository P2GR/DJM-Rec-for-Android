#pragma once

#include <cstdio>
#include "AudioWriter.h"

namespace djmrec {

/**
 * Writes uncompressed PCM to a standard RIFF/WAVE container. The header is written with
 * placeholder sizes on open() and patched in-place on close() once the true data length is
 * known — this avoids buffering the whole recording in memory and lets recordings of
 * arbitrary length stream straight to storage.
 */
class WavWriter final : public AudioWriter {
public:
    ~WavWriter() override { if (mFile) close(); }

    bool open(const std::string& path, const AudioFormatInfo& format) override;
    bool writeFrames(const int32_t* interleaved, size_t frameCount) override;
    bool close() override;

private:
    void writeHeaderPlaceholder();
    void patchHeaderSizes();

    FILE* mFile = nullptr;
    AudioFormatInfo mFormat;
    int mBytesPerSample = 3; // derived from bitsPerSample
    uint64_t mDataBytesWritten = 0;
};

} // namespace djmrec
