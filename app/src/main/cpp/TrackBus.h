#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

#include "MeterCalculator.h"
#include "SafetyLimiter.h"
#include "WaveformAnalyzer.h"

namespace djmrec {

/** One multitrack track: @p width (1 or 2) channels starting at wire channel @p first. */
struct TrackLayout {
    int first = 0;
    int width = 2;
};

/**
 * Splits a device's full interleaved frame into tracks: consecutive stereo pairs (USB 1/2,
 * 3/4, ...), with a trailing odd channel as a mono track. Mixer USB sends are stereo pairs,
 * and generic interfaces number their inputs the same way.
 */
inline std::vector<TrackLayout> makeTrackLayout(int channels, int maxTracks) {
    std::vector<TrackLayout> tracks;
    for (int first = 0; first < channels && static_cast<int>(tracks.size()) < maxTracks; first += 2) {
        tracks.push_back({first, std::min(2, channels - first)});
    }
    return tracks;
}

/**
 * Multitrack ("advanced mode") processing for every channel of the capture device, running
 * next to the unchanged master path.
 *
 * Capture thread: process() applies each track's gain, meters it and feeds its 3-band
 * waveform. Each track has its own SafetyLimiter so one hot deck never ducks the others. The
 * limiter only engages when the track is boosted above 0 dB; at 0 dB or below a track passes
 * through bit-for-bit. Every track keeps the limiter's look-ahead delay even when it is not
 * limiting, which is the same delay the master gets, so tracks stay sample-aligned with it.
 *
 * Any thread: levels and waveform snapshots are read through atomics. Storage is allocated
 * once in the constructor; UsbAudioEngine publishes and frees the bus only while every
 * capture source is stopped, like its master limiter.
 */
class TrackBus {
public:
    static constexpr int kMaxChannels = 32;
    static constexpr int kMaxTracks = kMaxChannels / 2;
    /** Floats per channel returned by readLevels(): peak dBFS, RMS dBFS, clip flag. */
    static constexpr int kLevelStride = 3;

    TrackBus(int sampleRate, int channels)
        : mChannels(std::clamp(channels, 1, kMaxChannels)),
          mLayout(makeTrackLayout(mChannels, kMaxTracks)),
          mRmsTimeConstantFrames(std::max(1.0, sampleRate * 0.3)) {
        for (const auto& track : mLayout) {
            auto limiter = std::make_unique<SafetyLimiter>();
            limiter->configure(sampleRate, track.width);
            mLimiters.push_back(std::move(limiter));
            mWaveforms.push_back(std::make_unique<WaveformAnalyzer>(sampleRate));
        }
        mMeanSquare.fill(0.0);
        for (auto& value : mPeakHold) value.store(0.0f, std::memory_order_relaxed);
        for (auto& value : mRms) value.store(0.0f, std::memory_order_relaxed);
        for (auto& value : mClipHold) value.store(false, std::memory_order_relaxed);
    }

    int channels() const { return mChannels; }
    int trackCount() const { return static_cast<int>(mLayout.size()); }
    const std::vector<TrackLayout>& layout() const { return mLayout; }

    /**
     * Capture thread only. Processes @p frameCount frames of @p channels() interleaved samples
     * in place. @p gainsLinear holds one gain per track.
     */
    void process(int32_t* interleaved, size_t frameCount, const float* gainsLinear,
                 bool limiterAllowed, bool waveformEnabled) {
        if (!interleaved || frameCount == 0) return;
        const size_t needed = frameCount * 2;
        if (mScratch.size() < needed) mScratch.resize(needed);
        const double alpha = 1.0 - std::exp(-static_cast<double>(frameCount) / mRmsTimeConstantFrames);

        for (size_t t = 0; t < mLayout.size(); ++t) {
            const TrackLayout& track = mLayout[t];
            for (size_t f = 0; f < frameCount; ++f) {
                const int32_t* src = interleaved + f * mChannels + track.first;
                for (int ch = 0; ch < track.width; ++ch) mScratch[f * track.width + ch] = src[ch];
            }
            const float gain = gainsLinear[t];
            mLimiters[t]->process(mScratch.data(), frameCount, gain, limiterAllowed && gain > 1.0001f);

            for (int ch = 0; ch < track.width; ++ch) {
                float peak = 0.0f;
                double sumSquares = 0.0;
                for (size_t f = 0; f < frameCount; ++f) {
                    const float sample = static_cast<float>(mScratch[f * track.width + ch]) / 2147483648.0f;
                    peak = std::max(peak, std::fabs(sample));
                    sumSquares += static_cast<double>(sample) * sample;
                }
                const int channel = track.first + ch;
                holdMax(mPeakHold[channel], peak);
                if (peak >= kClipThreshold) mClipHold[channel].store(true, std::memory_order_relaxed);
                double& meanSquare = mMeanSquare[channel];
                meanSquare += (sumSquares / static_cast<double>(frameCount) - meanSquare) * alpha;
                mRms[channel].store(static_cast<float>(std::sqrt(meanSquare)), std::memory_order_relaxed);
            }

            for (size_t f = 0; f < frameCount; ++f) {
                int32_t* dst = interleaved + f * mChannels + track.first;
                for (int ch = 0; ch < track.width; ++ch) dst[ch] = mScratch[f * track.width + ch];
            }
            if (waveformEnabled) {
                mWaveforms[t]->pushFrames(interleaved, frameCount, mChannels, track.first, track.width);
            }
        }
    }

    /**
     * Any thread. Writes kLevelStride floats per channel (peak dBFS since the previous read,
     * smoothed RMS dBFS, 1 when it clipped since the previous read) for up to @p maxChannels
     * channels and returns how many channels were written.
     */
    int readLevels(float* out, int maxChannels) {
        const int count = std::min(mChannels, maxChannels);
        for (int channel = 0; channel < count; ++channel) {
            out[channel * kLevelStride] =
                amplitudeToDb(mPeakHold[channel].exchange(0.0f, std::memory_order_relaxed));
            out[channel * kLevelStride + 1] =
                amplitudeToDb(mRms[channel].load(std::memory_order_relaxed));
            out[channel * kLevelStride + 2] =
                mClipHold[channel].exchange(false, std::memory_order_relaxed) ? 1.0f : 0.0f;
        }
        return count;
    }

    /** Any thread. Same layout as UsbAudioEngine::getWaveformBins(): bins, cursor, bin ms. */
    void readWaveform(int track, float* out) const {
        constexpr int bins = WaveformAnalyzer::kBinCount;
        if (track < 0 || track >= trackCount()) {
            std::memset(out, 0, (bins * 4 + 2) * sizeof(float));
            return;
        }
        uint32_t sequence = 0;
        mWaveforms[track]->getBins(out, &sequence);
        out[bins * 4] = static_cast<float>(sequence % 1048576);
        out[bins * 4 + 1] = mWaveforms[track]->binDurationMillis();
    }

private:
    static void holdMax(std::atomic<float>& target, float value) {
        float previous = target.load(std::memory_order_relaxed);
        while (value > previous &&
               !target.compare_exchange_weak(previous, value, std::memory_order_relaxed)) {
        }
    }

    const int mChannels;
    const std::vector<TrackLayout> mLayout;
    const double mRmsTimeConstantFrames;
    std::vector<std::unique_ptr<SafetyLimiter>> mLimiters;
    std::vector<std::unique_ptr<WaveformAnalyzer>> mWaveforms;
    std::vector<int32_t> mScratch;                    // capture thread only
    std::array<double, kMaxChannels> mMeanSquare{};   // capture thread only
    std::array<std::atomic<float>, kMaxChannels> mPeakHold;
    std::array<std::atomic<float>, kMaxChannels> mRms;
    std::array<std::atomic<bool>, kMaxChannels> mClipHold;
};

} // namespace djmrec
