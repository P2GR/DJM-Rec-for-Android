#pragma once

#include <atomic>
#include <cstdint>
#include <vector>

namespace djmrec {

/**
 * Realtime effects chain for RMX-1000 simulator.
 * All setter methods use atomic stores, getters read atomically — safe for
 * cross-thread parameter updates without locks on the audio render path.
 */
class EffectChain {
public:
    EffectChain();

    /** Bit crush: 0.0 = clean, 1.0 = maximum destruction. */
    void setBitCrush(float amount);
    float getBitCrush() const { return mBitCrush.load(std::memory_order_relaxed); }

    /** Filter cutoff in Hz. 50.0 - 20000.0. */
    void setFilterCutoff(float hz);
    float getFilterCutoff() const { return mFilterCutoff.load(std::memory_order_relaxed); }

    /** Filter type: 0=lowpass, 1=bandpass, 2=highpass. */
    void setFilterType(int type);
    int getFilterType() const { return mFilterType.load(std::memory_order_relaxed); }

    /** Delay wet mix: 0.0 = dry, 1.0 = fully wet. */
    void setDelayMix(float mix);
    float getDelayMix() const { return mDelayMix.load(std::memory_order_relaxed); }

    /** Delay time in samples (e.g. from BeatClock::getLoopLengthSamples). */
    void setDelayTimeSamples(size_t samples);
    size_t getDelayTimeSamples() const { return mDelayTimeSamples.load(std::memory_order_relaxed); }

    /** Delay feedback: 0.0 = single echo, 0.9 = long trail. */
    void setDelayFeedback(float fb);
    float getDelayFeedback() const { return mDelayFeedback.load(std::memory_order_relaxed); }

    /** Reverb room size: 0.0 = tiny, 1.0 = cathedral. */
    void setReverbRoomSize(float size);
    float getReverbRoomSize() const { return mReverbRoomSize.load(std::memory_order_relaxed); }

    /** Reverb wet mix: 0.0 = dry, 1.0 = fully wet. */
    void setReverbMix(float mix);
    float getReverbMix() const { return mReverbMix.load(std::memory_order_relaxed); }

    /** Reset all effect state (call on new session). */
    void reset();

    /**
     * Process one stereo sample pair through the full effect chain.
     * Called from the realtime audio callback. No allocations, no locks.
     */
    void process(float& left, float& right);

private:
    // Bit crush
    std::atomic<float> mBitCrush{0.0f};

    // State-variable filter
    std::atomic<float> mFilterCutoff{20000.0f};
    std::atomic<int> mFilterType{0}; // 0=LP, 1=BP, 2=HP
    float mFilterLow = 0.0f, mFilterBand = 0.0f, mFilterHigh = 0.0f;

    // Delay
    std::atomic<float> mDelayMix{0.0f};
    std::atomic<size_t> mDelayTimeSamples{22050}; // ~0.5s at 44.1k
    std::atomic<float> mDelayFeedback{0.4f};
    std::vector<float> mDelayLine;
    size_t mDelayWritePos = 0;
    size_t mDelayLength = 0;

    // Simple Schroeder reverb (4 combs + 2 allpasses)
    std::atomic<float> mReverbRoomSize{0.5f};
    std::atomic<float> mReverbMix{0.0f};
    struct CombFilter {
        std::vector<float> buffer;
        size_t pos = 0;
        float feedback = 0.0f;
        float process(float x);
    };
    struct AllpassFilter {
        std::vector<float> buffer;
        size_t pos = 0;
        float process(float x);
    };
    CombFilter mCombs[4];
    AllpassFilter mAllpasses[2];
    bool mReverbInitialized = false;
    void initReverb();
};

} // namespace djmrec
