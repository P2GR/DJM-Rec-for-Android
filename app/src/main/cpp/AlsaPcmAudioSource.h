#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <thread>
#include <vector>

struct pcm;

namespace djmrec {

class AlsaPcmAudioSource {
public:
    struct Config {
        int card = -1;
        int device = -1;
        int sampleRate = 48000;
        int channels = 2;
        int bitDepth = 16;
        int extractChannelOffset = -1;
    };

    using FrameCallback = std::function<void(const int32_t* interleavedStereo, size_t frameCount)>;

    AlsaPcmAudioSource() = default;
    ~AlsaPcmAudioSource();

    AlsaPcmAudioSource(const AlsaPcmAudioSource&) = delete;
    AlsaPcmAudioSource& operator=(const AlsaPcmAudioSource&) = delete;

    std::string start(const Config& config, FrameCallback callback);
    void stop();

    int openedSampleRate() const { return mOpenedSampleRate; }
    int openedChannels() const { return mOpenedChannels; }
    int openedBitDepth() const { return mOpenedBitDepth; }

private:
    void captureLoop();
    void decodeAndEmit(const uint8_t* data, size_t framesRead);

    Config mConfig{};
    FrameCallback mCallback;
    pcm* mPcm = nullptr;
    std::atomic<bool> mRunning{false};
    std::thread mThread;
    std::vector<uint8_t> mReadBuffer;
    std::vector<int32_t> mScratch;

    int mOpenedSampleRate = 0;
    int mOpenedChannels = 0;
    int mOpenedBitDepth = 0;
    size_t mBytesPerSample = 0;
    size_t mFramesSinceLog = 0;
    uint64_t mBytesSinceLog = 0;
    uint64_t mNonZeroBytesSinceLog = 0;
};

} // namespace djmrec