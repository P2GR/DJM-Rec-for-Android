#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "writers/AudioWriter.h"

// Shine's public header (src/lib/layer3.h) is intentionally kept out of this header: it
// declares very generic global enums (STEREO, MONO, NONE...). Forward-declare the handle.
struct shine_global_flags;

namespace djmrec {

/**
 * Real-time MPEG Layer III (MP3) writer: 320 kbps CBR stereo (the best valid lower rate
 * for sub-32 kHz capture), backed by the shine fixed-point encoder. The engine feeds
 * left-justified, int32-interleaved PCM (see AudioWriter.h); shine consumes interleaved
 * int16 at <= 48 kHz, so samples are shifted down to 16 bit and 88.2/96/176.4/192 kHz
 * capture is low-pass filtered and decimated to 44.1/48 kHz first (the same rate map the
 * livestream PCM path uses).
 *
 * shine consumes exactly shine_samples_per_pass() frames per call, so incoming frames are
 * staged until a full pass is available; close() pads an incomplete final pass with silence
 * and flushes the encoder tail.
 */
class Mp3Writer final : public AudioWriter {
public:
    Mp3Writer() = default;
    ~Mp3Writer() override;

    bool open(const std::string& path, const AudioFormatInfo& format) override;
    bool openFd(int fd, const AudioFormatInfo& format) override;
    bool writeFrames(const int32_t* interleaved, size_t frameCount) override;
    bool close() override;
    bool checkpoint() override;
    uint64_t bytesWritten() const override { return mBytesWritten; }

private:
    /** One half-band FIR + decimate-by-2 stage (fixed 15-tap Blackman-windowed sinc). */
    struct Decimator {
        float history[15] = {};
        int cursor = 0;
        int phase = 0;
        /** Feeds one input sample; returns true (with `out` set) for each surviving sample. */
        bool process(float sample, float* out);
        static const float kCoeffs[15];
    };

    bool begin(const AudioFormatInfo& format, FILE* file);
    void pushDecimated(int16_t left, int16_t right);
    void encodeStagedPass();

    FILE* mFile = nullptr;
    shine_global_flags* mShine = nullptr;
    int mPassSamples = 0;
    int mDecimatorStages = 0;
    Decimator mDecL[2];
    Decimator mDecR[2];
    std::vector<int16_t> mStage; // interleaved, exactly mPassSamples frames
    int mStagedFrames = 0;
    uint64_t mBytesWritten = 0;
};

} // namespace djmrec
