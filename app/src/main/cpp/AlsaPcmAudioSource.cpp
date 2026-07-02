#include "AlsaPcmAudioSource.h"

#include <android/log.h>
#include <tinyalsa/pcm.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#define TAG "AlsaPcmAudioSource"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace djmrec {

namespace {
struct FormatAttempt {
    pcm_format format;
    int bitDepth;
    size_t bytesPerSample;
};

std::vector<FormatAttempt> formatAttemptsFor(int bitDepth) {
    std::vector<FormatAttempt> attempts;
    if (bitDepth == 24) {
        attempts.push_back({PCM_FORMAT_S24_3LE, 24, 3});
        attempts.push_back({PCM_FORMAT_S16_LE, 16, 2});
        attempts.push_back({PCM_FORMAT_S32_LE, 32, 4});
    } else if (bitDepth == 32) {
        attempts.push_back({PCM_FORMAT_S32_LE, 32, 4});
        attempts.push_back({PCM_FORMAT_S24_3LE, 24, 3});
        attempts.push_back({PCM_FORMAT_S16_LE, 16, 2});
    } else {
        attempts.push_back({PCM_FORMAT_S16_LE, 16, 2});
        attempts.push_back({PCM_FORMAT_S24_3LE, 24, 3});
        attempts.push_back({PCM_FORMAT_S32_LE, 32, 4});
    }
    return attempts;
}

int32_t decodeSample(const uint8_t* sample, int bitDepth) {
    if (bitDepth == 16) {
        auto v = static_cast<int16_t>(sample[0] | (sample[1] << 8));
        return static_cast<int32_t>(v) << 16;
    }
    if (bitDepth == 24) {
        int32_t v = sample[0] | (sample[1] << 8) | (sample[2] << 16);
        if (v & 0x00800000) {
            v |= static_cast<int32_t>(0xFF000000);
        }
        return v << 8;
    }
    return static_cast<int32_t>(
        static_cast<uint32_t>(sample[0]) | (static_cast<uint32_t>(sample[1]) << 8) |
        (static_cast<uint32_t>(sample[2]) << 16) | (static_cast<uint32_t>(sample[3]) << 24));
}
} // namespace

AlsaPcmAudioSource::~AlsaPcmAudioSource() {
    stop();
}

std::string AlsaPcmAudioSource::start(const Config& config, FrameCallback callback) {
    if (mRunning.load(std::memory_order_acquire)) {
        return "already running";
    }
    if (config.card < 0 || config.device < 0 || config.sampleRate <= 0) {
        return "invalid ALSA capture configuration";
    }

    mConfig = config;
    mCallback = std::move(callback);

    std::vector<int> channelAttempts;
    channelAttempts.push_back(2);
    if (config.channels > 2) {
        channelAttempts.push_back(config.channels);
    }

    std::string lastError;
    for (int channels : channelAttempts) {
        for (const auto& format : formatAttemptsFor(config.bitDepth)) {
            pcm_config pcmConfig{};
            pcmConfig.channels = static_cast<unsigned int>(channels);
            pcmConfig.rate = static_cast<unsigned int>(config.sampleRate);
            pcmConfig.period_size = 1024;
            pcmConfig.period_count = 4;
            pcmConfig.format = format.format;
            pcmConfig.start_threshold = 1;
            pcmConfig.stop_threshold = pcmConfig.period_size * pcmConfig.period_count;
            pcmConfig.avail_min = pcmConfig.period_size;

            pcm* candidate = pcm_open(
                static_cast<unsigned int>(config.card),
                static_cast<unsigned int>(config.device),
                PCM_IN,
                &pcmConfig);
            if (candidate && pcm_is_ready(candidate)) {
                mPcm = candidate;
                mOpenedSampleRate = config.sampleRate;
                mOpenedChannels = channels;
                mOpenedBitDepth = format.bitDepth;
                mBytesPerSample = format.bytesPerSample;
                LOGI("Opened root ALSA hw:%d,%d @ %dHz / %dbit / %dch",
                     config.card, config.device, mOpenedSampleRate, mOpenedBitDepth, mOpenedChannels);
                mReadBuffer.resize(pcmConfig.period_size * static_cast<size_t>(channels) * mBytesPerSample);
                mScratch.resize(pcmConfig.period_size * 2);
                mFramesSinceLog = 0;
                mBytesSinceLog = 0;
                mNonZeroBytesSinceLog = 0;
                mRunning.store(true, std::memory_order_release);
                mThread = std::thread(&AlsaPcmAudioSource::captureLoop, this);
                return {};
            }

            lastError = candidate ? pcm_get_error(candidate) : "pcm_open returned null";
            LOGW("Failed root ALSA hw:%d,%d @ %dHz / %dbit / %dch: %s",
                 config.card, config.device, config.sampleRate, format.bitDepth, channels, lastError.c_str());
            if (candidate) {
                pcm_close(candidate);
            }
        }
    }

    return "root ALSA open failed: " + lastError;
}

void AlsaPcmAudioSource::captureLoop() {
    const unsigned int framesPerRead = static_cast<unsigned int>(
        mReadBuffer.size() / (static_cast<size_t>(mOpenedChannels) * mBytesPerSample));
    while (mRunning.load(std::memory_order_acquire)) {
        const int framesRead = pcm_readi(mPcm, mReadBuffer.data(), framesPerRead);
        if (framesRead < 0) {
            LOGW("pcm_readi failed: %s", pcm_get_error(mPcm));
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }
        if (framesRead == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }
        decodeAndEmit(mReadBuffer.data(), static_cast<size_t>(framesRead));
    }
}

void AlsaPcmAudioSource::decodeAndEmit(const uint8_t* data, size_t framesRead) {
    const size_t frameBytes = static_cast<size_t>(mOpenedChannels) * mBytesPerSample;
    const size_t bytesRead = framesRead * frameBytes;
    for (size_t i = 0; i < bytesRead; ++i) {
        if (data[i] != 0) {
            ++mNonZeroBytesSinceLog;
        }
    }
    mBytesSinceLog += bytesRead;

    if (mScratch.size() < framesRead * 2) {
        mScratch.resize(framesRead * 2);
    }

    int offset = mConfig.extractChannelOffset;
    if (offset < 0 || offset + 1 >= mOpenedChannels) {
        offset = 0;
    }
    const size_t offsetBytes = static_cast<size_t>(offset) * mBytesPerSample;
    uint32_t peak = 0;
    for (size_t frame = 0; frame < framesRead; ++frame) {
        const uint8_t* frameBase = data + frame * frameBytes + offsetBytes;
        const int32_t left = decodeSample(frameBase, mOpenedBitDepth);
        const int32_t right = decodeSample(frameBase + mBytesPerSample, mOpenedBitDepth);
        mScratch[frame * 2] = left;
        mScratch[frame * 2 + 1] = right;
        peak = std::max(peak, static_cast<uint32_t>(left == INT32_MIN ? INT32_MAX : std::abs(left)));
        peak = std::max(peak, static_cast<uint32_t>(right == INT32_MIN ? INT32_MAX : std::abs(right)));
    }

    if (mCallback) {
        mCallback(mScratch.data(), framesRead);
    }

    mFramesSinceLog += framesRead;
    if (mFramesSinceLog >= static_cast<size_t>(std::max(1, mOpenedSampleRate))) {
        LOGI("Root ALSA payload nonzero bytes=%llu/%llu; peak=%u; hw:%d,%d %dHz/%dbit/%dch selected ch %d-%d",
             static_cast<unsigned long long>(mNonZeroBytesSinceLog),
             static_cast<unsigned long long>(mBytesSinceLog),
             peak, mConfig.card, mConfig.device, mOpenedSampleRate, mOpenedBitDepth, mOpenedChannels,
             offset + 1, offset + 2);
        mFramesSinceLog = 0;
        mBytesSinceLog = 0;
        mNonZeroBytesSinceLog = 0;
    }
}

void AlsaPcmAudioSource::stop() {
    const bool wasRunning = mRunning.exchange(false, std::memory_order_acq_rel);
    if (mThread.joinable()) {
        mThread.join();
    }
    if (mPcm) {
        pcm_close(mPcm);
        mPcm = nullptr;
    }
    mCallback = nullptr;
    if (wasRunning) {
        LOGI("Root ALSA capture stopped");
    }
}

} // namespace djmrec