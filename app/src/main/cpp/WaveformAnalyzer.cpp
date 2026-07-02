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

WaveformAnalyzer::WaveformAnalyzer() {
    constexpr float sr = 48000.0f;

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
    mWriteBuffer = mBufferA;
    std::memset(mBufferA, 0, sizeof(mBufferA));
    std::memset(mBufferB, 0, sizeof(mBufferB));
    mReadBuffer.store(mBufferB, std::memory_order_release);
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
    BinAccum& bin = mWriteBuffer[mCurrentBin];

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

    if (bin.sampleCount >= kFramesPerBin) {
        commitBin();
    }
}

void WaveformAnalyzer::commitBin() {
    mCurrentBin++;
    if (mCurrentBin >= kBinCount) {
        // Buffer full — publish to readers, then wrap around.
        mReadBuffer.store(mWriteBuffer, std::memory_order_release);
        // Swap double buffers: start writing into the other one.
        mWriteBuffer = (mWriteBuffer == mBufferA) ? mBufferB : mBufferA;
        mCurrentBin = 0;
    }
    // Clear the new current bin (it may have stale data from previous cycle).
    BinAccum& next = mWriteBuffer[mCurrentBin];
    next.peakAbs = 0.0f;
    next.lowSum = 0.0f;
    next.midSum = 0.0f;
    next.highSum = 0.0f;
    next.sampleCount = 0;
}

// ---------------------------------------------------------------------------
// Reader — called from UI polling thread.
// ---------------------------------------------------------------------------

void WaveformAnalyzer::getBins(float* outBins) const {
    const BinAccum* src = mReadBuffer.load(std::memory_order_acquire);
    if (!src) {
        std::memset(outBins, 0, kBinCount * 4 * sizeof(float));
        return;
    }
    for (int i = 0; i < kBinCount; ++i) {
        const BinAccum& bin = src[i];
        const float n = static_cast<float>(bin.sampleCount);
        const float invN = (n > 0.0f) ? (1.0f / n) : 0.0f;

        outBins[i * 4 + 0] = bin.peakAbs;                          // amplitude
        outBins[i * 4 + 1] = bin.lowSum  * invN;                   // red   (low energy)
        outBins[i * 4 + 2] = bin.midSum  * invN;                   // green (mid energy)
        outBins[i * 4 + 3] = bin.highSum * invN;                   // blue  (high energy)
    }
}

void WaveformAnalyzer::reset() {
    mLowFilter.resetState();
    mMidFilter1.resetState();
    mMidFilter2.resetState();
    mHighFilter.resetState();

    std::memset(mBufferA, 0, sizeof(mBufferA));
    std::memset(mBufferB, 0, sizeof(mBufferB));
    mWriteBuffer = mBufferA;
    mCurrentBin = 0;
    mReadBuffer.store(mBufferB, std::memory_order_release);
}

} // namespace djmrec
