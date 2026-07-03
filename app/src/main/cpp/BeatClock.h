#pragma once

#include <atomic>
#include <cstdint>

namespace djmrec {

/**
 * Beat-aligned clock driven by BPM detector output.
 * Provides frame-accurate beat division lengths and phase tracking.
 * All reads are lock-free — safe to call from the realtime audio callback.
 */
class BeatClock {
public:
    static constexpr int kSampleRate = 44100;

    BeatClock();

    /** Update the BPM and phase from the detector. Call from UI thread at ~30Hz. */
    void update(float bpm, float beatPhase, bool locked);

    /** Update the BPM manually (tap tempo). Sets locked=true. */
    void setManualBpm(float bpm);

    /** Clear manual BPM override — go back to auto-detection. */
    void clearManualBpm();
    bool isManualMode() const { return mManualMode.load(std::memory_order_relaxed); }

    /**
     * Get the current beat phase [0, 1). Advances automatically based on sample count
     * since the last update() call. Call from the render callback.
     */
    float getBeatPhase() const;

    /**
     * Get the loop length in samples for a given beat division.
     * @param numerator beat division numerator (e.g. 1 for 1/4)
     * @param denominator beat division denominator (e.g. 4 for 1/4)
     */
    size_t getLoopLengthSamples(int numerator, int denominator) const;

    /** Get current BPM. */
    float getBpm() const { return mBpm.load(std::memory_order_relaxed); }

    /** Is the BPM locked (confident detection or manual)? */
    bool isLocked() const { return mLocked.load(std::memory_order_relaxed); }

    /**
     * Advance the internal phase counter by N samples. Must be called from the render
     * callback every frame with the number of samples rendered.
     */
    void advanceSamples(size_t frameCount);

private:
    std::atomic<float> mBpm{120.0f};
    std::atomic<float> mBeatPhase{0.0f};
    std::atomic<bool> mLocked{false};
    std::atomic<bool> mManualMode{false};

    float mPhaseAccum = 0.0f;
};

} // namespace djmrec
