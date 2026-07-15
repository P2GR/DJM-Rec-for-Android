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
    const float alpha = sinW0 / (2.0f * 1.41421356f); // Q = 1/sqrt(2) for Butterworth

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
    const float alpha = sinW0 / (2.0f * 1.41421356f);

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

    // Low band: 20–250 Hz → red
    designLowPass(mLowFilter, 250.0f, sr);

    // Mid band: 250–2000 Hz → green
    // Constructed as: low-pass @ 2000Hz applied after high-pass @ 250Hz.
    // hpf(250) strips lows; lpf(2000) strips highs → band-pass.
    designHighPass(mMidFilter1, 250.0f, sr);
    designLowPass(mMidFilter2, 2000.0f, sr);

    // High band: 2000–20000 Hz → blue
    designHighPass(mHighFilter, 2000.0f, sr);

    // Write buffer starts as buffer A; read buffer points to buffer B (empty).
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
        const float mono  = (left + right) * 0.5f;

        accumulateSample(mono);
    }
}

void WaveformAnalyzer::accumulateSample(float mono) {
    BinAccum& bin = mCurrent;

    // Run through the three band filters.
    const float low  = mLowFilter.process(mono);
    const float mid  = mMidFilter2.process(mMidFilter1.process(mono));
    const float high = mHighFilter.process(mono);

    // Peak amplitude (full-band waveform envelope).
    const float absMono = std::fabs(mono);
    if (absMono > bin.peakAbs) bin.peakAbs = absMono;

    // Band energy accumulation (rectified average — cheap, effective).
    bin.lowSum  += std::fabs(low);
    bin.midSum  += std::fabs(mid);
    bin.highSum += std::fabs(high);
    bin.sampleCount++;

    if (bin.sampleCount >= mFramesPerBin) {
        commitBin();
    }
}

void WaveformAnalyzer::commitBin() {
    const int index = mWriteIndex.load(std::memory_order_relaxed);
    const int base = index * 4;
    const float invN = mCurrent.sampleCount > 0
        ? 1.0f / static_cast<float>(mCurrent.sampleCount) : 0.0f;
    mBins[base + 0].store(mCurrent.peakAbs, std::memory_order_relaxed);
    mBins[base + 1].store(mCurrent.lowSum * invN, std::memory_order_relaxed);
    mBins[base + 2].store(mCurrent.midSum * invN, std::memory_order_relaxed);
    mBins[base + 3].store(mCurrent.highSum * invN, std::memory_order_release);
    mWriteIndex.store((index + 1) % kBinCount, std::memory_order_release);
    mCurrent = {};
}

// ---------------------------------------------------------------------------
// Reader — called from UI polling thread.
// ---------------------------------------------------------------------------

void WaveformAnalyzer::getBins(float* outBins) const {
    const int start = mWriteIndex.load(std::memory_order_acquire);
    for (int i = 0; i < kBinCount; ++i) {
        const int sourceBase = ((start + i) % kBinCount) * 4;
        const int targetBase = i * 4;
        outBins[targetBase + 0] = mBins[sourceBase + 0].load(std::memory_order_relaxed);
        outBins[targetBase + 1] = mBins[sourceBase + 1].load(std::memory_order_relaxed);
        outBins[targetBase + 2] = mBins[sourceBase + 2].load(std::memory_order_relaxed);
        outBins[targetBase + 3] = mBins[sourceBase + 3].load(std::memory_order_acquire);
    }
}

void WaveformAnalyzer::reset() {
    mLowFilter.resetState();
    mMidFilter1.resetState();
    mMidFilter2.resetState();
    mHighFilter.resetState();

    mCurrent = {};
    for (auto& value : mBins) value.store(0.0f, std::memory_order_relaxed);
    mWriteIndex.store(0, std::memory_order_release);
}

} // namespace djmrec
