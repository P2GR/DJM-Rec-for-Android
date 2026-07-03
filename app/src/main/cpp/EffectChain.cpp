#include "EffectChain.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace djmrec {

EffectChain::EffectChain() {
    mDelayLine.resize(4 * 44100); // up to 4 seconds of delay
    mDelayLength = 22050;
}

void EffectChain::reset() {
    mFilterLow = mFilterBand = mFilterHigh = 0.0f;
    std::memset(mDelayLine.data(), 0, mDelayLine.size() * sizeof(float));
    mDelayWritePos = 0;
    for (auto& c : mCombs) { c.pos = 0; if (!c.buffer.empty()) std::memset(c.buffer.data(), 0, c.buffer.size() * sizeof(float)); }
    for (auto& a : mAllpasses) { a.pos = 0; if (!a.buffer.empty()) std::memset(a.buffer.data(), 0, a.buffer.size() * sizeof(float)); }
}

void EffectChain::initReverb() {
    const int sizes[4] = { 1557, 1617, 1491, 1422 };
    for (int i = 0; i < 4; ++i) {
        mCombs[i].buffer.resize(sizes[i], 0.0f);
        mCombs[i].feedback = 0.75f + 0.1f * static_cast<float>(i);
    }
    for (int i = 0; i < 2; ++i) {
        mAllpasses[i].buffer.resize(i == 0 ? 225 : 556, 0.0f);
    }
    mReverbInitialized = true;
}

void EffectChain::setBitCrush(float amount) { mBitCrush.store(std::max(0.0f, std::min(1.0f, amount)), std::memory_order_release); }
void EffectChain::setFilterCutoff(float hz) { mFilterCutoff.store(std::max(50.0f, std::min(20000.0f, hz)), std::memory_order_release); }
void EffectChain::setFilterType(int type) { mFilterType.store(std::max(0, std::min(2, type)), std::memory_order_release); }
void EffectChain::setDelayMix(float mix) { mDelayMix.store(std::max(0.0f, std::min(1.0f, mix)), std::memory_order_release); }
void EffectChain::setDelayTimeSamples(size_t samples) {
    mDelayTimeSamples.store(std::max(size_t(100), std::min(mDelayLine.size(), samples)), std::memory_order_release);
}
void EffectChain::setDelayFeedback(float fb) { mDelayFeedback.store(std::max(0.0f, std::min(0.95f, fb)), std::memory_order_release); }
void EffectChain::setReverbRoomSize(float size) { mReverbRoomSize.store(std::max(0.0f, std::min(1.0f, size)), std::memory_order_release); }
void EffectChain::setReverbMix(float mix) { mReverbMix.store(std::max(0.0f, std::min(1.0f, mix)), std::memory_order_release); }

void EffectChain::process(float& left, float& right) {
    const float monoIn = (left + right) * 0.5f;

    // --- Bit crush ---
    float bitCrush = mBitCrush.load(std::memory_order_relaxed);
    if (bitCrush > 0.001f) {
        const float steps = std::pow(2.0f, 16.0f * (1.0f - bitCrush));
        const float crushLeft = std::round(left * steps) / steps;
        const float crushRight = std::round(right * steps) / steps;
        left = left + bitCrush * (crushLeft - left);
        right = right + bitCrush * (crushRight - right);
    }

    // --- State-variable filter ---
    const float cutoff = mFilterCutoff.load(std::memory_order_relaxed);
    const int ftype = mFilterType.load(std::memory_order_relaxed);
    if (cutoff < 19900.0f) {
        const float f = 2.0f * std::sin(3.14159265f * cutoff / 44100.0f);
        const float q = 0.707f;
        const float monoProc = monoIn;

        mFilterHigh = monoProc - mFilterLow - q * mFilterBand;
        mFilterBand = f * mFilterHigh + mFilterBand;
        mFilterLow = f * mFilterBand + mFilterLow;

        float filtered = mFilterLow; // LP default
        if (ftype == 1) filtered = mFilterBand;
        else if (ftype == 2) filtered = mFilterHigh;

        left = filtered;
        right = filtered;
    }

    // --- Delay ---
    const float delayMix = mDelayMix.load(std::memory_order_relaxed);
    if (delayMix > 0.001f) {
        const size_t delayLen = mDelayTimeSamples.load(std::memory_order_relaxed);
        const float delayFb = mDelayFeedback.load(std::memory_order_relaxed);

        // Resize delay line if needed (rare, non-realtime safe — but only on param change)
        if (delayLen != mDelayLength && delayLen <= mDelayLine.size()) {
            mDelayLength = delayLen;
        }

        const size_t readPos = (mDelayWritePos + mDelayLine.size() - mDelayLength) % mDelayLine.size();
        const float delayed = mDelayLine[readPos];

        const float wetLeft = left + delayMix * delayed;
        const float wetRight = right + delayMix * delayed;

        mDelayLine[mDelayWritePos] = monoIn + delayFb * delayed;
        mDelayWritePos = (mDelayWritePos + 1) % mDelayLine.size();

        left = wetLeft;
        right = wetRight;
    }

    // --- Schroeder reverb ---
    const float reverbMix = mReverbMix.load(std::memory_order_relaxed);
    if (reverbMix > 0.001f) {
        if (!mReverbInitialized) initReverb();

        const float roomSize = mReverbRoomSize.load(std::memory_order_relaxed);
        for (auto& c : mCombs) { c.feedback = 0.7f + 0.14f * roomSize; }

        float revIn = monoIn;
        for (auto& c : mCombs) revIn += c.process(monoIn) * 0.25f;
        for (auto& a : mAllpasses) revIn = a.process(revIn);

        left = left + reverbMix * (revIn - left);
        right = right + reverbMix * (revIn - right);
    }
}

float EffectChain::CombFilter::process(float x) {
    if (buffer.empty()) return x;
    const float out = buffer[pos];
    buffer[pos] = x + out * feedback;
    pos = (pos + 1) % buffer.size();
    return out;
}

float EffectChain::AllpassFilter::process(float x) {
    if (buffer.empty()) return x;
    const float bufOut = buffer[pos];
    const float out = -x + bufOut;
    buffer[pos] = x + bufOut * 0.5f;
    pos = (pos + 1) % buffer.size();
    return out;
}

} // namespace djmrec
