#include "BeatClock.h"

#include <cmath>

namespace djmrec {

BeatClock::BeatClock() = default;

void BeatClock::update(float bpm, float beatPhase, bool locked) {
    if (mManualMode.load(std::memory_order_relaxed)) return;
    if (bpm > 0.0f) mBpm.store(bpm, std::memory_order_release);
    mBeatPhase.store(beatPhase, std::memory_order_release);
    mLocked.store(locked, std::memory_order_release);
}

void BeatClock::setManualBpm(float bpm) {
    mBpm.store(bpm, std::memory_order_release);
    mLocked.store(true, std::memory_order_release);
    mManualMode.store(true, std::memory_order_release);
    mPhaseAccum = 0.0f;
}

void BeatClock::clearManualBpm() {
    mManualMode.store(false, std::memory_order_release);
}

float BeatClock::getBeatPhase() const {
    return std::fmod(mPhaseAccum, 1.0f);
}

size_t BeatClock::getLoopLengthSamples(int numerator, int denominator) const {
    const float bpm = mBpm.load(std::memory_order_relaxed);
    if (bpm <= 0.0f || denominator <= 0) return kSampleRate; // default 1 second
    const float beatsPerSecond = bpm / 60.0f;
    const float secondsPerBeat = 1.0f / beatsPerSecond;
    const float divisionBeats = static_cast<float>(numerator) / static_cast<float>(denominator);
    return static_cast<size_t>(secondsPerBeat * divisionBeats * static_cast<float>(kSampleRate));
}

void BeatClock::advanceSamples(size_t frameCount) {
    const float bpm = mBpm.load(std::memory_order_relaxed);
    const float beatPhase = mBeatPhase.load(std::memory_order_relaxed);
    if (bpm > 0.0f) {
        const float beatsPerSecond = bpm / 60.0f;
        const float samplesPerBeat = static_cast<float>(kSampleRate) / beatsPerSecond;
        mPhaseAccum = std::fmod(beatPhase + static_cast<float>(frameCount) / samplesPerBeat, 1.0f);
    }
}

} // namespace djmrec
