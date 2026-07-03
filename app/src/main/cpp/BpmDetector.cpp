#include "BpmDetector.h"

#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <cstring>

#define TAG "BpmDetector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace djmrec {

namespace {
constexpr float kPi = 3.14159265358979323846f;

/** Bilinear transform: s = 2 * fs * (z-1)/(z+1). */
void bilinearLowpass(float cutoffHz, float fs, float& b0, float& b1, float& b2, float& a1, float& a2) {
    const float w0 = 2.0f * kPi * cutoffHz / fs;
    const float cosW0 = std::cos(w0);
    const float sinW0 = std::sin(w0);
    const float alpha = sinW0 / (2.0f * 0.7071f); // Q = 0.7071 (Butterworth)
    const float a0 = 1.0f + alpha;
    b0 = ((1.0f - cosW0) / 2.0f) / a0;
    b1 = (1.0f - cosW0) / a0;
    b2 = ((1.0f - cosW0) / 2.0f) / a0;
    a1 = (-2.0f * cosW0) / a0;
    a2 = (1.0f - alpha) / a0;
}

void bilinearHighpass(float cutoffHz, float fs, float& b0, float& b1, float& b2, float& a1, float& a2) {
    const float w0 = 2.0f * kPi * cutoffHz / fs;
    const float cosW0 = std::cos(w0);
    const float sinW0 = std::sin(w0);
    const float alpha = sinW0 / (2.0f * 0.7071f);
    const float a0 = 1.0f + alpha;
    b0 = ((1.0f + cosW0) / 2.0f) / a0;
    b1 = -(1.0f + cosW0) / a0;
    b2 = ((1.0f + cosW0) / 2.0f) / a0;
    a1 = (-2.0f * cosW0) / a0;
    a2 = (1.0f - alpha) / a0;
}
} // namespace

BpmDetector::BpmDetector() {
    // Design the 3-band crossover filters.
    designLowpass(mLowFilter, 250.0f);
    designHighpass(mLowHighpass, 20.0f);   // DC block for low band
    designLowpass(mMidLowpass, 2000.0f);
    designHighpass(mMidHighpass, 250.0f);
    designLowpass(mHighLowpass, 8000.0f);
    designHighpass(mHighHighpass, 2000.0f);

    mBandLow.odfHistory.resize(kOdfHistoryFrames, 0.0f);
    mBandMid.odfHistory.resize(kOdfHistoryFrames, 0.0f);
    mBandHigh.odfHistory.resize(kOdfHistoryFrames, 0.0f);
}

BpmDetector::~BpmDetector() = default;

void BpmDetector::designLowpass(Biquad& f, float cutoffHz) {
    bilinearLowpass(cutoffHz, static_cast<float>(kSampleRate), f.b0, f.b1, f.b2, f.a1, f.a2);
}

void BpmDetector::designHighpass(Biquad& f, float cutoffHz) {
    bilinearHighpass(cutoffHz, static_cast<float>(kSampleRate), f.b0, f.b1, f.b2, f.a1, f.a2);
}

void BpmDetector::processFrames(const float* monoSamples, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        const float sample = monoSamples[i];

        // Band-split via cascaded IIR filters.
        const float lowFiltered = mLowFilter.process(mLowHighpass.process(sample));
        const float midFiltered = mMidLowpass.process(mMidHighpass.process(sample));
        const float highFiltered = mHighLowpass.process(mHighHighpass.process(sample));

        // Accumulate energy per band.
        const float lowE = lowFiltered * lowFiltered;
        const float midE = midFiltered * midFiltered;
        const float highE = highFiltered * highFiltered;

        updateBandOdF(mBandLow, lowE);
        updateBandOdF(mBandMid, midE);
        updateBandOdF(mBandHigh, highE);

        // Beat phase tracking: advance phase at current BPM rate.
        const float currentBpm = mBpm.load(std::memory_order_relaxed);
        if (currentBpm > 0.0f) {
            const float samplesPerBeat = static_cast<float>(kSampleRate) * 60.0f / currentBpm;
            mPhaseAccum += 1.0f / samplesPerBeat;
            if (mPhaseAccum >= 1.0f) {
                mPhaseAccum -= 1.0f;
                mBeatPhase.store(0.0f, std::memory_order_release);
            } else {
                mBeatPhase.store(mPhaseAccum, std::memory_order_release);
            }
        }
        ++mFramesSinceBeat;
    }

    // Periodically run autocorrelation and combine.
    if (mFramesSinceBeat >= kSampleRate / 2) { // every ~0.5 s
        estimateBpm(mBandLow);
        estimateBpm(mBandMid);
        estimateBpm(mBandHigh);
        combineBands();
        mFramesSinceBeat = 0;
    }
}

void BpmDetector::updateBandOdF(BandOdF& band, float bandEnergy) {
    band.lowpassEnergy += bandEnergy;
    ++band.samplesAccum;

    if (band.samplesAccum >= kHopSize) {
        // Normalise energy per sample.
        const float energy = band.lowpassEnergy / static_cast<float>(kHopSize);
        band.lowpassEnergy = 0.0f;
        band.samplesAccum = 0;

        // Half-wave rectified energy difference = onset detection function.
        const float onset = std::max(0.0f, energy - band.prevEnergy);
        band.prevEnergy = energy;

        // Write to ring buffer.
        band.odfHistory[band.odfWriteIdx] = onset;
        band.odfWriteIdx = (band.odfWriteIdx + 1) % kOdfHistoryFrames;
    }
}

void BpmDetector::estimateBpm(BandOdF& band) {
    // Autocorrelation on the ODF ring buffer.
    // Compute for lags corresponding to kMinBpm .. kMaxBpm.
    const int minLag = static_cast<int>(60.0f / kMaxBpm * static_cast<float>(kSampleRate) / static_cast<float>(kHopSize));
    const int maxLag = static_cast<int>(60.0f / kMinBpm * static_cast<float>(kSampleRate) / static_cast<float>(kHopSize));

    // Build a linear buffer from the ring buffer for easier autocorrelation.
    std::vector<float> linear(kOdfHistoryFrames);
    for (int i = 0; i < kOdfHistoryFrames; ++i) {
        linear[i] = band.odfHistory[(band.odfWriteIdx + i) % kOdfHistoryFrames];
    }

    if (maxLag <= minLag || maxLag >= kOdfHistoryFrames) return;

    float bestCorr = 0.0f;
    int bestLag = minLag;

    for (int lag = minLag; lag < maxLag; ++lag) {
        float sum = 0.0f;
        int count = 0;
        for (int i = 0; i < kOdfHistoryFrames - lag; ++i) {
            sum += linear[i] * linear[i + lag];
            ++count;
        }
        const float corr = (count > 0) ? sum / static_cast<float>(count) : 0.0f;

        // Bias toward shorter lags (faster tempos) slightly — they're more common.
        const float lagPenalty = 1.0f - 0.15f * static_cast<float>(lag - minLag) / static_cast<float>(maxLag - minLag + 1);
        const float weightedCorr = corr * lagPenalty;

        if (weightedCorr > bestCorr) {
            bestCorr = weightedCorr;
            bestLag = lag;
        }
    }

    // Convert lag to BPM.
    const float periodSec = static_cast<float>(bestLag) * static_cast<float>(kHopSize) / static_cast<float>(kSampleRate);
    band.bpm = (periodSec > 0.0f) ? 60.0f / periodSec : 0.0f;

    // Confidence: ratio of best correlation to mean correlation.
    float meanCorr = 0.0f;
    int corrCount = 0;
    for (int lag = minLag; lag < maxLag; ++lag) {
        float sum = 0.0f;
        for (int i = 0; i < kOdfHistoryFrames - lag; ++i) {
            sum += linear[i] * linear[i + lag];
        }
        meanCorr += sum / static_cast<float>(kOdfHistoryFrames - lag);
        ++corrCount;
    }
    meanCorr = (corrCount > 0) ? meanCorr / static_cast<float>(corrCount) : 1.0f;
    band.confidence = (meanCorr > 0.0f) ? std::min(1.0f, bestCorr / meanCorr) : 0.0f;
}

void BpmDetector::combineBands() {
    // Confidence-weighted average of the three bands.
    float weightedSum = 0.0f;
    float weightTotal = 0.0f;
    int leadingIdx = 0;
    float leadingConf = 0.0f;

    const BandOdF* bands[3] = { &mBandLow, &mBandMid, &mBandHigh };
    for (int b = 0; b < 3; ++b) {
        if (bands[b]->bpm >= kMinBpm && bands[b]->bpm <= kMaxBpm && bands[b]->confidence >= kMinConfidence) {
            const float w = bands[b]->confidence * bands[b]->confidence; // square for stronger weighting
            weightedSum += bands[b]->bpm * w;
            weightTotal += w;
            if (bands[b]->confidence > leadingConf) {
                leadingConf = bands[b]->confidence;
                leadingIdx = b;
            }
        }
    }

    if (weightTotal > 0.0f) {
        const float rawBpm = weightedSum / weightTotal;

        // EMA smoothing.
        if (mBpmEma <= 0.0f) {
            mBpmEma = rawBpm;
        } else {
            // Accept big jumps only from tap tempo (handled externally).
            const float delta = std::abs(rawBpm - mBpmEma);
            const float alpha = (delta > 20.0f) ? 0.05f : kEmaAlpha;
            mBpmEma = alpha * rawBpm + (1.0f - alpha) * mBpmEma;
        }

        mBpm.store(mBpmEma, std::memory_order_release);

        const float avgConf = weightTotal / 3.0f;
        mConfidence.store(std::min(1.0f, avgConf), std::memory_order_release);
        mLeadingBand.store(leadingIdx, std::memory_order_release);

        // Lock detection.
        if (avgConf > 0.5f) {
            ++mConsecutiveLocked;
            if (mConsecutiveLocked >= kLockThreshold) {
                if (!mLocked) LOGI("BPM locked at %.1f (confidence %.2f)", mBpmEma, avgConf);
                mLocked = true;
            }
        } else {
            mConsecutiveLocked = std::max(0, mConsecutiveLocked - 1);
        }
    } else {
        mConfidence.store(0.0f, std::memory_order_release);
        mConsecutiveLocked = std::max(0, mConsecutiveLocked - 2);
        if (mConsecutiveLocked <= 0) mLocked = false;
    }
}

bool BpmDetector::getResult(Result& outResult) {
    outResult.bpm = mBpm.load(std::memory_order_acquire);
    outResult.confidence = mConfidence.load(std::memory_order_acquire);
    outResult.beatPhase = mBeatPhase.load(std::memory_order_acquire);
    outResult.leadingBand = mLeadingBand.load(std::memory_order_acquire);
    return mLocked;
}

void BpmDetector::reset() {
    mLowFilter.reset(); mLowHighpass.reset();
    mMidLowpass.reset(); mMidHighpass.reset();
    mHighLowpass.reset(); mHighHighpass.reset();

    mBandLow = BandOdF{};
    mBandMid = BandOdF{};
    mBandHigh = BandOdF{};
    mBandLow.odfHistory.resize(kOdfHistoryFrames, 0.0f);
    mBandMid.odfHistory.resize(kOdfHistoryFrames, 0.0f);
    mBandHigh.odfHistory.resize(kOdfHistoryFrames, 0.0f);

    mBpm.store(0.0f);
    mConfidence.store(0.0f);
    mBeatPhase.store(0.0f);
    mLeadingBand.store(0);
    mPhaseAccum = 0.0f;
    mFramesSinceBeat = 0;
    mConsecutiveLocked = 0;
    mBpmEma = 0.0f;
    mLocked = false;
}

} // namespace djmrec
