#include "WaveformAnalyzer.h"

#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace djmrec {

// ---------------------------------------------------------------------------
// 2nd-order Butterworth coefficient design (bilinear transform).
// ---------------------------------------------------------------------------

void WaveformAnalyzer::designLowPass(BandFilter& f, float cutoffHz, float sampleRate) {
    const float w0 = 2.0f * static_cast<float>(M_PI) * cutoffHz / sampleRate;
    const float cosW0 = std::cos(w0);
    const float sinW0 = std::sin(w0);
    const float alpha = sinW0 / (2.0f * 0.70710678f); // Q = 1/sqrt(2) for Butterworth

    const float b0 = (1.0f - cosW0) / 2.0f;
    const float b1 = 1.0f - cosW0;
    const float b2 = (1.0f - cosW0) / 2.0f;
    const float a0 = 1.0f + alpha;
    const float a1 = -2.0f * cosW0;
    const float a2 = 1.0f - alpha;

    f.b0 = b0 / a0;
    f.b1 = b1 / a0;
    f.b2 = b2 / a0;
    f.a1 = a1 / a0;
    f.a2 = a2 / a0;
}

void WaveformAnalyzer::designHighPass(BandFilter& f, float cutoffHz, float sampleRate) {
    const float w0 = 2.0f * static_cast<float>(M_PI) * cutoffHz / sampleRate;
    const float cosW0 = std::cos(w0);
    const float sinW0 = std::sin(w0);
    const float alpha = sinW0 / (2.0f * 0.70710678f);

    const float b0 = (1.0f + cosW0) / 2.0f;
    const float b1 = -(1.0f + cosW0);
    const float b2 = (1.0f + cosW0) / 2.0f;
    const float a0 = 1.0f + alpha;
    const float a1 = -2.0f * cosW0;
    const float a2 = 1.0f - alpha;

    f.b0 = b0 / a0;
    f.b1 = b1 / a0;
    f.b2 = b2 / a0;
    f.a1 = a1 / a0;
    f.a2 = a2 / a0;
}

// ---------------------------------------------------------------------------
// Construction — design the three bands at 48 kHz.
// ---------------------------------------------------------------------------

WaveformAnalyzer::WaveformAnalyzer(int sampleRate) {
    const float sr = static_cast<float>(std::max(sampleRate, 8000));
    mFramesPerBin = std::max(1, static_cast<int>(sr / 163.0f));
    mBinDurationMillis = 1000.0f * mFramesPerBin / sr;

    // Low band: 20–250 Hz → red
    for (auto& filter : mLowFilter) designLowPass(filter, 250.0f, sr);

    // Mid band: 250–2000 Hz → green
    // Constructed as: low-pass @ 2000Hz applied after high-pass @ 250Hz.
    // hpf(250) strips lows; lpf(2000) strips highs → band-pass.
    for (auto& filter : mMidFilter1) designHighPass(filter, 250.0f, sr);
    for (auto& filter : mMidFilter2) designLowPass(filter, 2000.0f, sr);

    // High band: 2000–20000 Hz → blue
    for (auto& filter : mHighFilter) designHighPass(filter, 2000.0f, sr);

    // Start with an empty rolling history.
    for (auto& value : mBins) value.store(0.0f, std::memory_order_relaxed);
}

WaveformAnalyzer::~WaveformAnalyzer() = default;

// ---------------------------------------------------------------------------
// Realtime push — called from audio callback / libusb event thread.
// ---------------------------------------------------------------------------

void WaveformAnalyzer::pushFrames(const int32_t* interleavedStereo, size_t frameCount) {
    for (size_t i = 0; i < frameCount; ++i) {
        // Mix stereo → mono (average L+R).
        const float left  = static_cast<float>(interleavedStereo[i * 2])     / kMaxAmplitude;
        const float right = static_cast<float>(interleavedStereo[i * 2 + 1]) / kMaxAmplitude;
        accumulateSample(left, right);
    }
}

void WaveformAnalyzer::accumulateSample(float left, float right) {
    BinAccum& bin = mCurrent;
    // Never sum L+R before analysis: opposite-phase stereo is still real audio.
    const float samples[] = {left, right};
    for (int channel = 0; channel < 2; ++channel) {
        const float sample = samples[channel];
        const float low = mLowFilter[channel].process(sample);
        const float mid = mMidFilter2[channel].process(mMidFilter1[channel].process(sample));
        const float high = mHighFilter[channel].process(sample);
        bin.peakAbs = std::max(bin.peakAbs, std::fabs(sample));
        bin.lowSum += std::fabs(low) * 0.5f;
        bin.midSum += std::fabs(mid) * 0.5f;
        bin.highSum += std::fabs(high) * 0.5f;
    }
    bin.sampleCount++;

    if (bin.sampleCount >= mFramesPerBin) {
        commitBin();
    }
}

void WaveformAnalyzer::commitBin() {
    const uint32_t committed = mCommitted.load(std::memory_order_relaxed);
    const int index = committed % kRingCount;
    const int base = index * 4;
    const float invN = mCurrent.sampleCount > 0
        ? 1.0f / static_cast<float>(mCurrent.sampleCount) : 0.0f;
    mBins[base + 0].store(mCurrent.peakAbs, std::memory_order_relaxed);
    mBins[base + 1].store(mCurrent.lowSum * invN, std::memory_order_relaxed);
    mBins[base + 2].store(mCurrent.midSum * invN, std::memory_order_relaxed);
    mBins[base + 3].store(mCurrent.highSum * invN, std::memory_order_release);
    mCommitted.store(committed + 1, std::memory_order_release);
    mCurrent = {};
}

// ---------------------------------------------------------------------------
// Reader — called from UI polling thread.
// ---------------------------------------------------------------------------

void WaveformAnalyzer::getBins(float* outBins, uint32_t* sequence) const {
    for (int attempt = 0; attempt < 3; ++attempt) {
        const uint32_t end = mCommitted.load(std::memory_order_acquire);
        for (int i = 0; i < kBinCount; ++i) {
            const int sourceBase = ((end - kBinCount + i) % kRingCount) * 4;
            for (int band = 0; band < 4; ++band) {
                outBins[i * 4 + band] = mBins[sourceBase + band].load(std::memory_order_relaxed);
            }
        }
        if (mCommitted.load(std::memory_order_acquire) - end < kBinCount) {
            if (sequence) *sequence = end;
            return;
        }
    }
    // A severely stalled reader must not display torn history.
    std::fill(outBins, outBins + kBinCount * 4, 0.0f);
    if (sequence) *sequence = mCommitted.load(std::memory_order_acquire);
}

void WaveformAnalyzer::reset() {
    for (auto& filter : mLowFilter) filter.resetState();
    for (auto& filter : mMidFilter1) filter.resetState();
    for (auto& filter : mMidFilter2) filter.resetState();
    for (auto& filter : mHighFilter) filter.resetState();

    mCurrent = {};
    for (auto& value : mBins) value.store(0.0f, std::memory_order_relaxed);
    mCommitted.store(0, std::memory_order_release);
}

} // namespace djmrec
