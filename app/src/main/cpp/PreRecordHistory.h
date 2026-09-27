#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace djmrec {

/**
 * Rolling copy of the last few seconds of processed audio while monitoring, so pressing
 * Record late still captures the start of the mix.
 *
 * Unlike RingBuffer this overwrites the oldest audio when full. It is not internally
 * synchronized: UsbAudioEngine guarantees that only the capture thread writes while
 * monitoring and only the encoder thread reads once the capture thread has stopped writing
 * (see UsbAudioEngine::mHistoryFrozen). Storage is allocated once in the constructor.
 */
class PreRecordHistory {
public:
    PreRecordHistory(int sampleRate, int channelCount, int seconds)
        : mChannels(std::max(1, channelCount)),
          mCapacityFrames(static_cast<size_t>(std::max(0, sampleRate)) * std::max(0, seconds)),
          mBuffer(mCapacityFrames * mChannels, 0) {}

    size_t capacityFrames() const { return mCapacityFrames; }
    size_t storedFrames() const { return mStoredFrames; }
    int channelCount() const { return mChannels; }

    void clear() {
        mWriteFrame = 0;
        mStoredFrames = 0;
    }

    /** Capture thread only. Keeps the newest capacityFrames() frames. */
    void write(const int32_t* interleaved, size_t frameCount) {
        if (mCapacityFrames == 0 || frameCount == 0) return;
        if (frameCount > mCapacityFrames) {
            interleaved += (frameCount - mCapacityFrames) * mChannels;
            frameCount = mCapacityFrames;
        }
        const size_t firstRun = std::min(frameCount, mCapacityFrames - mWriteFrame);
        std::memcpy(mBuffer.data() + mWriteFrame * mChannels, interleaved,
                    firstRun * mChannels * sizeof(int32_t));
        if (frameCount > firstRun) {
            std::memcpy(mBuffer.data(), interleaved + firstRun * mChannels,
                        (frameCount - firstRun) * mChannels * sizeof(int32_t));
        }
        mWriteFrame = (mWriteFrame + frameCount) % mCapacityFrames;
        mStoredFrames = std::min(mCapacityFrames, mStoredFrames + frameCount);
    }

    /** Encoder thread only, after writes stopped. Copies stored frames oldest-first. */
    size_t copyOldestFirst(std::vector<int32_t>& out) const {
        out.resize(mStoredFrames * mChannels);
        if (mStoredFrames == 0) return 0;
        const size_t start = (mWriteFrame + mCapacityFrames - mStoredFrames) % mCapacityFrames;
        const size_t firstRun = std::min(mStoredFrames, mCapacityFrames - start);
        std::memcpy(out.data(), mBuffer.data() + start * mChannels,
                    firstRun * mChannels * sizeof(int32_t));
        if (mStoredFrames > firstRun) {
            std::memcpy(out.data() + firstRun * mChannels, mBuffer.data(),
                        (mStoredFrames - firstRun) * mChannels * sizeof(int32_t));
        }
        return mStoredFrames;
    }

private:
    int mChannels;
    size_t mCapacityFrames;
    std::vector<int32_t> mBuffer;
    size_t mWriteFrame = 0;
    size_t mStoredFrames = 0;
};

} // namespace djmrec
