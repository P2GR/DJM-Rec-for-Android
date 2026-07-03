#pragma once

#include <atomic>
#include <cstdint>
#include <vector>

namespace djmrec {

/**
 * Realtime BPM detector using multi-band onset detection + autocorrelation.
 *
 * Architecture:
 *   1. Splits incoming mono audio into low / mid / high bands via 2nd-order IIR filters
 *      (reuses the BandFilter struct from WaveformAnalyzer).
 *   2. Computes an onset detection function (ODF) per band: half-wave rectified energy delta.
 *   3. Every ~0.5 s, runs autocorrelation on each band's ODF history to find the dominant period.
 *   4. Combines per-band (BPM, confidence) via confidence-weighted average.
 *   5. Smooths with an exponential moving average (EMA, alpha ~0.15).
 *   6. Tracks beat phase via a phase-locked loop for visual pulse animation.
 *
 * Thread safety:
 *   - processFrames() is called from the encoder thread (NOT the realtime callback),
 *     so it may allocate and lock.
 *   - getResult() is called from the UI polling thread and reads atomically.
 */
class BpmDetector {
public:
    static constexpr int kSampleRate = 44100;
    static constexpr int kHopSize = 512;          // samples between ODF frames (~11.6 ms)
    static constexpr float kMinBpm = 60.0f;
    static constexpr float kMaxBpm = 200.0f;
    static constexpr int kOdfHistoryFrames = 512; // ~6 s of ODF at 11.6 ms/frame
    static constexpr float kEmaAlpha = 0.15f;     // smoothing factor
    static constexpr float kMinConfidence = 0.25f; // below this, band is ignored

    struct Result {
        float bpm = 0.0f;
        float confidence = 0.0f; // [0, 1]
        float beatPhase = 0.0f;  // [0, 1), 0 = on the beat
        int leadingBand = 0;     // 0=low, 1=mid, 2=high
    };

    BpmDetector();
    ~BpmDetector();

    BpmDetector(const BpmDetector&) = delete;
    BpmDetector& operator=(const BpmDetector&) = delete;

    /**
     * Feed a batch of mono audio samples. Called from the encoder thread.
     * @param monoSamples pointer to mono float samples (normalised to [-1, 1]).
     * @param count number of samples.
     */
    void processFrames(const float* monoSamples, size_t count);

    /**
     * Reads the latest BPM estimate. Safe to call from any thread.
     * Returns true if a valid BPM has been locked, false if still listening.
     */
    bool getResult(Result& outResult);

    /** Resets all filter state, ODF history, and BPM estimate. */
    void reset();

private:
    struct BandOdF {
        float lowpassEnergy = 0.0f;  // accumulated energy in current odf frame
        float prevEnergy = 0.0f;     // energy of previous odf frame
        int samplesAccum = 0;
        std::vector<float> odfHistory; // ring buffer of onset values
        int odfWriteIdx = 0;
        float bpm = 0.0f;
        float confidence = 0.0f;
    };

    // IIR band-split filters (reuse WaveformAnalyzer pattern).
    struct Biquad {
        float b0 = 0, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        float process(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
            return y;
        }
        void reset() { x1 = x2 = y1 = y2 = 0; }
    };

    void designLowpass(Biquad& f, float cutoffHz);
    void designHighpass(Biquad& f, float cutoffHz);
    void updateBandOdF(BandOdF& band, float bandEnergy);
    void estimateBpm(BandOdF& band);
    void combineBands();

    Biquad mLowFilter;    // ~250 Hz lowpass
    Biquad mLowHighpass;   // ~20 Hz highpass (DC block)
    Biquad mMidLowpass;    // ~2000 Hz lowpass
    Biquad mMidHighpass;   // ~250 Hz highpass
    Biquad mHighLowpass;   // ~8000 Hz lowpass
    Biquad mHighHighpass;  // ~2000 Hz highpass

    BandOdF mBandLow;
    BandOdF mBandMid;
    BandOdF mBandHigh;

    // Combined output, atomically updated.
    std::atomic<float> mBpm{0.0f};
    std::atomic<float> mConfidence{0.0f};
    std::atomic<float> mBeatPhase{0.0f};
    std::atomic<int> mLeadingBand{0};

    // Phase-locked loop state.
    float mPhaseAccum = 0.0f;
    int mFramesSinceBeat = 0;
    int mConsecutiveLocked = 0;
    static constexpr int kLockThreshold = 8; // consecutive consistent readings to lock

    float mBpmEma = 0.0f; // internal EMA accumulator
    bool mLocked = false;
};

} // namespace djmrec
