#pragma once

#include <cstdio>
#include <vector>
#include "AudioWriter.h"

#if HAVE_LAME
#include <lame/lame.h>
#endif

namespace djmrec {

/**
 * High-quality MP3 encoder wrapping libmp3lame. Encodes at a fixed 320kbps CBR by default
 * (studio-quality, matches the spec's "320kbps CBR or VBR Quality 0"); flip `mUseVbr` to
 * switch to VBR quality 0 (highest) if smaller files are preferred over guaranteed bitrate.
 *
 * If the project was built without the LAME sources present (see CMakeLists.txt), every
 * call becomes a safe, clearly-logged no-op that returns false so the UI can fall back to
 * WAV/FLAC instead of silently producing an empty file.
 */
class Mp3Writer final : public AudioWriter {
public:
    explicit Mp3Writer(int bitrateKbps = 320, bool useVbr = false)
        : mBitrateKbps(bitrateKbps), mUseVbr(useVbr) {}

    ~Mp3Writer() override { if (mFile) close(); }

    bool open(const std::string& path, const AudioFormatInfo& format) override;
    bool writeFrames(const int32_t* interleaved, size_t frameCount) override;
    bool close() override;

private:
    int mBitrateKbps;
    bool mUseVbr;
    FILE* mFile = nullptr;

#if HAVE_LAME
    lame_global_flags* mLame = nullptr;
    int mChannelCount = 2;
    std::vector<int16_t> mInt16Scratch;
    std::vector<unsigned char> mMp3Scratch;
#endif
};

} // namespace djmrec
