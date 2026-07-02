#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace djmrec {

/**
 * CDJ-3000-style RGB waveform analyzer.
 *
 * Rather than an expensive full FFT, this uses three 2nd-order IIR filters
 * (low-pass ~250Hz, band-pass ~250–2000Hz, high-pass ~2000Hz) — the same
 * approach Pioneer's hardware uses — to split the stereo-mixed mono signal
 * into three frequency bands. Band energies are accumulated into fixed-width
 * display bins (~6ms each at 48kHz), producing a rolling 512-bin waveform
 * where each bin carries [amplitude, lowEnergy, midEnergy, highEnergy].
 *
 * Thread safety: pushFrames() is called from the realtime audio callback
 * (must not allocate/block); getBins() is called from the UI polling thread.
 * The output buffer is double-buffered via an atomic swap pointer so reads
 * never block writes.
 */
class WaveformAnalyzer {
public:
    static constexpr int kBinCount = 512;
    /** Samples accumulated per display bin at 48 kHz (~6.1 ms per bin). */
    static constexpr int kFramesPerBin = 294; // 48000 / 512 ≈ 93.75; 294 gives ~163 Hz bin rate

    WaveformAnalyzer();
    ~WaveformAnalyzer();

    WaveformAnalyzer(const WaveformAnalyzer&) = delete;
    WaveformAnalyzer& operator=(const WaveformAnalyzer&) = delete;

    /**
     * Called from the realtime audio callback (or libusb event thread) for
     * every captured frame batch. Mixes stereo → mono, runs the three IIR
     * filters per-sample, and accumulates peak + band energy into the current
     * display bin. When kFramesPerBin samples have been accumulated the bin
     * is committed and the next bin begins.
     *
     * Must be allocation-free and lock-free — only atomic stores and trivial
     * float math inside.
     */
    void pushFrames(const int32_t* interleavedStereo, size_t frameCount);

    /**
     * Copies the most recently committed waveform snapshot into @p outBins,
     * which must be at least kBinCount * 4 floats. Layout:
     *   [amp0, low0, mid0, high0, amp1, low1, mid1, high1, ...]
     *
     * Each value is normalized to [0, 1]. Safe to call from any thread.
     */
    void getBins(float* outBins) const;

    /** Resets all filter state and clears the bin buffer (e.g. on new session). */
    void reset();

private:
    /** 2nd-order Butterworth IIR filter (Direct Form I) for a single band. */
    struct BandFilter {
        // Coefficients (set once at construction).
        float b0 = 0.0f, b1 = 0.0f, b2 = 0.0f;
        float a1 = 0.0f, a2 = 0.0f; // a0 is always 1.0 (normalized)
        // Delay line state.
        float x1 = 0.0f, x2 = 0.0f; // input history
        float y1 = 0.0f, y2 = 0.0f; // output history

        /** Process one sample, returning the filtered output. */
        float process(float x) {
            const float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
            return y;
        }

        void resetState() { x1 = x2 = y1 = y2 = 0.0f; }
    };

    /**
     * Designs a 2nd-order Butterworth low-pass filter at @p cutoffHz for
     * @p sampleRate. Coefficients stored in @p f.
     */
    static void designLowPass(BandFilter& f, float cutoffHz, float sampleRate);

    /**
     * Designs a 2nd-order Butterworth high-pass filter at @p cutoffHz for
     * @p sampleRate. Coefficients stored in @p f.
     */
    static void designHighPass(BandFilter& f, float cutoffHz, float sampleRate);

    /** Accumulates a single mono sample through all three bands into the current bin. */
    void accumulateSample(float mono);

    /** Commits the current bin and advances to the next. */
    void commitBin();

    // Three IIR filters, designed at construction time (assumes 48 kHz).
    BandFilter mLowFilter;   // 250 Hz low-pass  → red channel
    BandFilter mMidFilter1;  // 250 Hz high-pass  → (then subtract mid lpf2)
    BandFilter mMidFilter2;  // 2000 Hz low-pass → mid = midFilter2(midFilter1(x))
    BandFilter mHighFilter;  // 2000 Hz high-pass → blue channel

    // Per-bin accumulators (written by realtime thread, swapped atomically).
    struct BinAccum {
        float peakAbs = 0.0f;    // max absolute amplitude this bin
        float lowSum = 0.0f;     // sum of |low| samples
        float midSum = 0.0f;     // sum of |mid| samples
        float highSum = 0.0f;    // sum of |high| samples
        int sampleCount = 0;
    };

    // Double-buffered bin arrays: one being written, one available for reading.
    // The "front" buffer (pointed to by mReadBuffer) is always consistent.
    BinAccum mBufferA[kBinCount];
    BinAccum mBufferB[kBinCount];
    std::atomic<BinAccum*> mReadBuffer{nullptr};
    BinAccum* mWriteBuffer = nullptr;
    int mCurrentBin = 0;

    static constexpr float kMaxAmplitude = 2147483648.0f; // 2^31 for int32 → float norm
};

} // namespace djmrec
