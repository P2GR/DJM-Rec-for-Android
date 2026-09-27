#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace djmrec {

/**
 * Drops the digital silence at the head of a recording. Mixers often need a few seconds
 * after Record is pressed before the first audio arrives over USB; without this gate that
 * dead air ends up at the start of every file.
 *
 * Runs on the encoder thread (never the realtime callback). While closed, incoming frames
 * are discarded except for a short rolling pre-roll, so a fade-in that starts below the
 * threshold is not clipped. The first frame at or above the threshold opens the gate: the
 * pre-roll is emitted, followed by the rest of the batch, and every later batch passes
 * straight through.
 *
 * Emit callbacks have the signature `bool(const int32_t* interleaved, size_t frameCount)`
 * and return false on a write failure, which is propagated to the caller.
 */
class LeadingSilenceGate {
public:
    /** ~-60 dBFS on the canonical left-justified int32 scale (2^31 * 10^(-60/20)). */
    static constexpr int32_t kThreshold = 2147484;
    static constexpr int kPreRollMillis = 500;

    void reset(bool enabled, int channelCount, int sampleRate) {
        mChannelCount = channelCount > 0 ? channelCount : 1;
        mAwaiting = enabled;
        mDiscardedFrames = 0;
        mPreRollFill = 0;
        mPreRollWrite = 0;
        mPreRollCapacity = enabled && sampleRate > 0
            ? static_cast<size_t>(sampleRate) * kPreRollMillis / 1000
            : 0;
        mPreRoll.assign(mPreRollCapacity * mChannelCount, 0);
    }

    bool awaiting() const { return mAwaiting; }

    static bool isAudible(int32_t sample) { return sample >= kThreshold || sample <= -kThreshold; }

    /** Index of the last frame holding an audible sample, or @p frameCount when all are silent. */
    static size_t lastAudibleFrame(const int32_t* interleaved, size_t frameCount, int channelCount) {
        const int channels = channelCount > 0 ? channelCount : 1;
        for (size_t i = frameCount * channels; i > 0; --i) {
            if (isAudible(interleaved[i - 1])) return (i - 1) / channels;
        }
        return frameCount;
    }

    /** Frames dropped before the gate opened; excludes the pre-roll that was kept. */
    uint64_t discardedFrames() const { return mDiscardedFrames; }

    template <typename Emit>
    bool process(const int32_t* interleaved, size_t frameCount, Emit&& emit) {
        if (!mAwaiting) return frameCount == 0 || emit(interleaved, frameCount);

        const size_t onset = firstAudibleFrame(interleaved, frameCount);
        if (onset == frameCount) {
            remember(interleaved, frameCount);
            return true;
        }

        // Keep up to kPreRollMillis of the quiet lead-in immediately before the onset.
        remember(interleaved, onset);
        mAwaiting = false;
        if (!emitPreRoll(emit)) return false;
        return emit(interleaved + onset * mChannelCount, frameCount - onset);
    }

    /**
     * Called when recording stops before any audio was detected: writes the retained
     * pre-roll so the file is still a valid, playable (short) recording.
     */
    template <typename Emit>
    bool flush(Emit&& emit) {
        if (!mAwaiting) return true;
        mAwaiting = false;
        return emitPreRoll(emit);
    }

private:
    size_t firstAudibleFrame(const int32_t* interleaved, size_t frameCount) const {
        const size_t samples = frameCount * mChannelCount;
        for (size_t i = 0; i < samples; ++i) {
            if (isAudible(interleaved[i])) return i / mChannelCount;
        }
        return frameCount;
    }

    void remember(const int32_t* interleaved, size_t frameCount) {
        if (mPreRollCapacity == 0) {
            mDiscardedFrames += frameCount;
            return;
        }
        for (size_t frame = 0; frame < frameCount; ++frame) {
            if (mPreRollFill == mPreRollCapacity) {
                ++mDiscardedFrames; // oldest pre-roll frame is overwritten below
            } else {
                ++mPreRollFill;
            }
            const int32_t* src = interleaved + frame * mChannelCount;
            int32_t* dst = mPreRoll.data() + mPreRollWrite * mChannelCount;
            for (int ch = 0; ch < mChannelCount; ++ch) dst[ch] = src[ch];
            mPreRollWrite = (mPreRollWrite + 1) % mPreRollCapacity;
        }
    }

    template <typename Emit>
    bool emitPreRoll(Emit& emit) {
        if (mPreRollFill == 0) return true;
        const size_t start = (mPreRollWrite + mPreRollCapacity - mPreRollFill) % mPreRollCapacity;
        const size_t firstRun = std::min(mPreRollFill, mPreRollCapacity - start);
        const size_t secondRun = mPreRollFill - firstRun;
        mPreRollFill = 0;
        mPreRollWrite = 0;
        if (!emit(mPreRoll.data() + start * mChannelCount, firstRun)) return false;
        return secondRun == 0 || emit(mPreRoll.data(), secondRun);
    }

    int mChannelCount = 2;
    bool mAwaiting = false;
    uint64_t mDiscardedFrames = 0;
    std::vector<int32_t> mPreRoll;
    size_t mPreRollCapacity = 0;
    size_t mPreRollFill = 0;
    size_t mPreRollWrite = 0;
};

} // namespace djmrec
