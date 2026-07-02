#include "MockAudioSource.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#define TAG "MockAudioSource"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace djmrec {

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

MockAudioSource::~MockAudioSource() {
    stop();
}

std::string MockAudioSource::start(const Config& config, FrameCallback callback) {
    if (mRunning.load(std::memory_order_acquire)) {
        return "MockAudioSource already running";
    }
    if (!callback) {
        return "Callback must not be null";
    }
    if (config.totalChannels < 2) {
        return "totalChannels must be >= 2";
    }
    if (config.extractChannelOffset + 2 > config.totalChannels) {
        return "extractChannelOffset out of range";
    }

    mConfig = config;
    mCallback = std::move(callback);
    mRunning.store(true, std::memory_order_release);

    mThread = std::make_unique<std::thread>(&MockAudioSource::generatorLoop, this);
    LOGI("Mock audio source started: %d ch @ %d Hz, extracting ch %d–%d",
         config.totalChannels, config.sampleRate,
         config.extractChannelOffset + 1, config.extractChannelOffset + 2);
    return {}; // success
}

void MockAudioSource::stop() {
    mRunning.store(false, std::memory_order_release);
    if (mThread && mThread->joinable()) {
        mThread->join();
    }
    mThread.reset();
    LOGI("Mock audio source stopped");
}

// ---------------------------------------------------------------------------
// Generator thread
// ---------------------------------------------------------------------------

void MockAudioSource::generatorLoop() {
    const int32_t totalCh = mConfig.totalChannels;
    const int32_t offset  = mConfig.extractChannelOffset;
    const int32_t sr      = mConfig.sampleRate;

    // ~20 ms chunks at 48 kHz
    constexpr size_t kChunkFrames = 960;
    // 12-ch int32 frame buffer + extracted stereo buffer
    std::vector<int32_t> frame12ch(kChunkFrames * totalCh);
    std::vector<int32_t> stereo(kChunkFrames * 2);

    uint64_t globalFrame = 0;
    using Clock = std::chrono::steady_clock;
    auto nextWake = Clock::now();

    while (mRunning.load(std::memory_order_acquire)) {
        // Fill one chunk of 12-channel PCM.
        for (size_t f = 0; f < kChunkFrames; ++f) {
            fillFrame(&frame12ch[f * totalCh], globalFrame + f);
            // Extract the stereo pair (channels offset..offset+1).
            stereo[f * 2]     = frame12ch[f * totalCh + offset];
            stereo[f * 2 + 1] = frame12ch[f * totalCh + offset + 1];
        }

        if (mCallback) {
            mCallback(stereo.data(), kChunkFrames);
        }

        globalFrame += kChunkFrames;

        // Sleep until the next chunk is due, compensating for drift.
        nextWake += std::chrono::microseconds(
            static_cast<int64_t>(kChunkFrames) * 1'000'000 / sr);
        std::this_thread::sleep_until(nextWake);
    }
}

// ---------------------------------------------------------------------------
// Per-frame synthesis — one distinct sine tone per channel.
// ---------------------------------------------------------------------------

void MockAudioSource::fillFrame(int32_t* frame12ch, uint64_t frameIndex) const {
    // Frequencies for each of the 12 channels (Hz).
    static constexpr float kFreqs[12] = {
        220.0f, 330.0f, 440.0f, 550.0f,
        660.0f, 770.0f, 880.0f, 990.0f,
        1000.0f, 1200.0f, 1500.0f, 1800.0f
    };

    const float sr = static_cast<float>(mConfig.sampleRate);
    // Amplitude: -12 dBFS to leave headroom.
    constexpr float kAmp = 0.25f; // ~ -12 dB
    constexpr float kInt32Max = 2147483648.0f; // 2^31

    for (int ch = 0; ch < 12; ++ch) {
        const float freq = kFreqs[ch];
        const float phase = static_cast<float>(frameIndex) * freq * 2.0f * 3.14159265f / sr;
        const float sample = std::sin(phase) * kAmp;
        frame12ch[ch] = static_cast<int32_t>(sample * kInt32Max);
    }
}

} // namespace djmrec
