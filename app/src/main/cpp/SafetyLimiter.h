#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

namespace djmrec {

/**
 * Recording gain stage with a look-ahead peak limiter, so the gain never hard-clips loud peaks.
 *
 * The gain is applied in floating point *before* anything is clamped to the int32 range;
 * limiting an already-clamped signal would only lower the flattened, distorted tops.
 * Runs on the realtime capture thread: no allocation after configure(), no locks, O(1) work
 * per frame. All channels share one limiter gain so the stereo image never shifts.
 *
 * Per frame t it computes the gain each frame needs to stay under kCeiling, takes the minimum
 * over the next kLookaheadMillis (a sliding-window minimum), lets that recover with a
 * kReleaseMillis release, then averages it over the same window. Output is the input
 * delayed by (lookahead - 1) frames times that average, which provably never exceeds the
 * ceiling while ramping the gain smoothly into each peak instead of cutting it off.
 *
 * With nothing to limit the limiter gain is exactly 1.0, so at 0 dB recording gain samples
 * pass through bit-for-bit, only delayed. Disabled, it clamps like the plain gain stage but
 * keeps the same delay, so toggling never clicks.
 */
class SafetyLimiter {
public:
    /** -1 dBFS leaves headroom for MP3/AAC encoding and inter-sample peaks. */
    static constexpr double kCeiling = 0.8912509381337456;
    static constexpr double kLookaheadMillis = 1.5;
    static constexpr double kReleaseMillis = 150.0;

    void configure(int sampleRate, int channelCount) {
        mChannels = std::max(1, channelCount);
        const int rate = std::max(8000, sampleRate);
        mWindow = static_cast<size_t>(std::max(2.0, std::ceil(rate * kLookaheadMillis / 1000.0)));
        mRelease = 1.0 - std::exp(-1.0 / (rate * kReleaseMillis / 1000.0));
        mDelay.assign(mWindow * mChannels, 0.0);
        mEnvelope.assign(mWindow, 1.0);
        mMinValues.assign(mWindow, 1.0);
        mMinTimes.assign(mWindow, 0);
        reset();
    }

    void reset() {
        std::fill(mDelay.begin(), mDelay.end(), 0.0);
        std::fill(mEnvelope.begin(), mEnvelope.end(), 1.0);
        mEnvelopeSum = static_cast<double>(mWindow);
        mMinHead = 0;
        mMinCount = 0;
        mTime = 0;
        mLastEnvelope = 1.0;
        mMinGainSinceRead = 1.0;
    }

    bool configured() const { return mWindow > 0; }
    int channelCount() const { return mChannels; }

    /** Frames of delay the limiter adds (look-ahead). */
    size_t latencyFrames() const { return mWindow > 0 ? mWindow - 1 : 0; }

    /** Applies @p linearGain and limits @p frameCount interleaved frames in place. */
    void process(int32_t* interleaved, size_t frameCount, double linearGain, bool enabled) {
        if (mWindow == 0) return;
        constexpr double fullScale = 2147483648.0;
        for (size_t frame = 0; frame < frameCount; ++frame) {
            int32_t* samples = interleaved + frame * mChannels;

            double peak = 0.0;
            for (int ch = 0; ch < mChannels; ++ch) {
                peak = std::max(peak, std::fabs(static_cast<double>(samples[ch]) * linearGain) / fullScale);
            }
            const double required = (enabled && peak > kCeiling) ? kCeiling / peak : 1.0;
            const double windowMin = pushWindowMin(required);

            double envelope = mLastEnvelope + (1.0 - mLastEnvelope) * mRelease;
            if (envelope > 1.0 - 1e-6) envelope = 1.0; // settle exactly, keeps passthrough bit-exact
            envelope = std::min(envelope, windowMin);
            mLastEnvelope = envelope;

            const size_t slot = static_cast<size_t>(mTime % mWindow);
            mEnvelopeSum += envelope - mEnvelope[slot];
            mEnvelope[slot] = envelope;
            if (slot == mWindow - 1) {
                // Re-sum once per window so floating-point drift never accumulates.
                mEnvelopeSum = 0.0;
                for (double value : mEnvelope) mEnvelopeSum += value;
            }
            const double gain = std::min(1.0, mEnvelopeSum / static_cast<double>(mWindow));
            mMinGainSinceRead = std::min(mMinGainSinceRead, gain);

            // Delay line: store gained x(t) in this slot; the next slot holds x(t - window + 1).
            double* delayed = mDelay.data() + slot * mChannels;
            const double* oldest = mDelay.data() + ((slot + 1) % mWindow) * mChannels;
            for (int ch = 0; ch < mChannels; ++ch) {
                const double output = oldest[ch] * gain;
                delayed[ch] = static_cast<double>(samples[ch]) * linearGain;
                samples[ch] = toSample(output);
            }
            ++mTime;
        }
    }

    /** Largest gain reduction applied since the previous call, in dB (>= 0). */
    float takeReductionDb() {
        const double gain = mMinGainSinceRead;
        mMinGainSinceRead = 1.0;
        return gain >= 1.0 ? 0.0f : static_cast<float>(-20.0 * std::log10(std::max(gain, 1e-6)));
    }

private:
    static int32_t toSample(double value) {
        return static_cast<int32_t>(std::clamp(std::round(value),
            static_cast<double>(std::numeric_limits<int32_t>::min()),
            static_cast<double>(std::numeric_limits<int32_t>::max())));
    }

    /** Monotonic-deque sliding minimum of the last mWindow required gains. */
    double pushWindowMin(double value) {
        // Expire first so the deque never holds more than mWindow entries.
        while (mMinCount > 0 && mMinTimes[mMinHead] + mWindow <= mTime) {
            mMinHead = (mMinHead + 1) % mWindow;
            --mMinCount;
        }
        while (mMinCount > 0) {
            const size_t back = (mMinHead + mMinCount - 1) % mWindow;
            if (mMinValues[back] < value) break;
            --mMinCount;
        }
        const size_t insert = (mMinHead + mMinCount) % mWindow;
        mMinValues[insert] = value;
        mMinTimes[insert] = mTime;
        ++mMinCount;
        return mMinValues[mMinHead];
    }

    int mChannels = 2;
    size_t mWindow = 0;
    double mRelease = 0.0;
    std::vector<double> mDelay;
    std::vector<double> mEnvelope;
    double mEnvelopeSum = 0.0;
    std::vector<double> mMinValues;
    std::vector<uint64_t> mMinTimes;
    size_t mMinHead = 0;
    size_t mMinCount = 0;
    uint64_t mTime = 0;
    double mLastEnvelope = 1.0;
    double mMinGainSinceRead = 1.0;
};

} // namespace djmrec
